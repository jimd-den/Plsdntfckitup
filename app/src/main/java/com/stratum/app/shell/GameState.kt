package com.stratum.app.shell

import com.stratum.app.GameSetup
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.CustomClassPack
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.content.VoxelBlueprint
import com.stratum.core.domain.session.PlayerLoadout
import com.stratum.engine.scene.PropModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * The state that outlives any one screen: what content is loaded, who the
 * player is playing and how they look, the world style, and the 3D models
 * the forge made.
 *
 * Every screen reads from here and writes through the functions below, so a
 * class built in the forge is on the play hub's list the moment it is saved,
 * and the choice of hero survives leaving the menu it was made in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameState(private val graph: AppGraph, scope: CoroutineScope) {

    private val ai = graph.ai

    private val customClassList = MutableStateFlow(graph.classes.all())

    /** Classes the player built, newest store order. */
    val customClasses: StateFlow<List<HeroClassDefinition>> = customClassList.asStateFlow()

    /**
     * Everything loaded, assembled off the main thread: the built-in pack,
     * every active plugin (the crew's packs and the player's creations among
     * them), and the player's own classes as a pack of their own.
     */
    val content: StateFlow<AssembledContent> = combine(graph.plugins.repository.library, customClassList) { library, classes ->
        GameSetup.assemble(library.activePacks + if (classes.isEmpty()) emptyList() else listOf(CustomClassPack.of(classes)))
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, GameSetup.assemble())

    /** The loaded content with every generated sprite sheet laid over it. */
    val contentWithSprites: StateFlow<AssembledContent> = combine(content, ai.sprites.sheets) { content, sheets ->
        content.withSpriteSheets(sheets)
    }.stateIn(scope, SharingStarted.Eagerly, content.value.withSpriteSheets(ai.sprites.sheets.value))

    private val loadoutState = MutableStateFlow(ai.playerPreferences.load())

    /** The hero class, art and weapon the player picked, persisted as they change. */
    val loadout: StateFlow<PlayerLoadout> = loadoutState.asStateFlow()

    private val style = MutableStateFlow(graph.styles.load())

    /** The prompt the world was painted from; blank for the pack's own look. */
    val stylePrompt: StateFlow<String> = style.asStateFlow()

    private val modelRevision = MutableStateFlow(0)

    /**
     * Prop models, parsed off the main thread whenever content or the model
     * forge changes. A world entered before they finish draws those props as
     * sprites until next time.
     */
    val propModels: StateFlow<Map<String, PropModel>> = combine(contentWithSprites, modelRevision) { content, _ -> content }
        .mapLatest { content -> runCatching { ai.models.propModels(ai.models.withForgedModels(content)) }.getOrDefault(emptyMap()) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val blueprints: StateFlow<List<VoxelBlueprint>> = modelRevision
        .map { ai.models.blueprints() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // A choice pointing at something deleted is forgotten rather than
        // silently standing in for a different class or a blank sheet.
        combine(content, ai.sprites.sheets) { content, sheets -> content to sheets }
            .onEach { (content, sheets) ->
                val loadout = loadoutState.value
                if (loadout.heroClassId != null && content.heroClasses.none { it.id == loadout.heroClassId }) chooseHeroClass(null)
                if (loadout.heroSheetId != null && sheets.none { it.id == loadout.heroSheetId }) chooseHeroSheet(null)
            }
            .launchIn(scope)
    }

    /** The class the player plays, falling back to whatever the packs list first. */
    fun selectedHeroClassId(): String? = loadoutState.value.heroClassId ?: content.value.heroClasses.firstOrNull()?.id

    fun chooseHeroClass(id: String?) {
        loadoutState.update { it.copy(heroClassId = id) }
        ai.playerPreferences.saveHeroClass(id)
    }

    /** Picks a look; picking the one already worn goes back to the class's own art. */
    fun chooseHeroSheet(id: String?) {
        val next = if (id != null && id == loadoutState.value.heroSheetId) null else id
        loadoutState.update { it.copy(heroSheetId = next) }
        ai.playerPreferences.saveHeroSheet(next)
    }

    fun equipWeapon(id: String?) {
        loadoutState.update { it.copy(equippedWeaponId = id) }
        ai.playerPreferences.saveEquippedWeapon(id)
    }

    fun saveStyle(prompt: String) {
        graph.styles.save(prompt)
        style.value = prompt
    }

    fun saveClass(hero: HeroClassDefinition) {
        graph.classes.save(hero)
        customClassList.value = graph.classes.all()
    }

    fun deleteClass(id: String) {
        graph.classes.delete(id)
        // Playing as a class that no longer exists would silently fall back to another one.
        if (loadoutState.value.heroClassId == id) chooseHeroClass(null)
        customClassList.value = graph.classes.all()
    }

    /** Called by the model forge whenever a binding or a blueprint changes. */
    fun modelsChanged() {
        modelRevision.update { it + 1 }
    }
}
