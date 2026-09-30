package com.stratum.feature.hero

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.ClassDraft
import com.stratum.core.domain.content.ClassOptions
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.sprite.SpriteNamespace
import com.stratum.core.domain.sprite.SpriteSheet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the class forge.
 *
 * Holds a [ClassDraft] and nothing else: every rule about what a build costs and
 * what it is worth lives in the domain, so the numbers on screen are the numbers
 * the session will spawn with rather than a second implementation of them.
 */
class ClassForgeViewModel(
    private val content: AssembledContent,
    /** Persists a finished class. Supplied by the composition root. */
    private val saveClass: (HeroClassDefinition) -> Unit,
    private val deleteClass: (String) -> Unit,
    private val loadClasses: () -> List<HeroClassDefinition>,
    /**
     * Sheets drawn in the sprite forge. Loaded through a lambda rather than
     * held, so art generated after this screen opened still appears.
     */
    private val loadSheets: () -> List<SpriteSheet> = { emptyList() },
    /**
     * Queues a sprite sheet for a subject as a background job and calls back
     * with it when it lands. False when no model is connected. The hero never
     * waits on it: the class saves and plays with the shape renderer meanwhile.
     */
    private val queueLook: ((subject: String, onDrawn: (SpriteSheet) -> Unit) -> Boolean)? = null,
) : ViewModel() {

    private val options = ClassOptions.from(content)

    private val _state = MutableStateFlow(
        ClassForgeUiState(
            draft = ClassDraft(),
            options = options,
            skills = content.skills,
            weapons = content.weapons,
            // Air and bedrock are not things a class can carry a stack of.
            blocks = content.registry.all.filter { !it.isAir && it.isBreakable },
            sheets = heroSheets(),
            saved = loadClasses(),
            canMakeLook = queueLook != null,
        ),
    )
    val state: StateFlow<ClassForgeUiState> = _state.asStateFlow()

    private fun edit(block: (ClassDraft) -> ClassDraft) {
        _state.value = _state.value.copy(draft = block(_state.value.draft), message = null)
    }

    fun setName(name: String) = edit { it.copy(name = name) }
    fun setTitle(title: String) = edit { it.copy(title = title) }
    fun setDescription(text: String) = edit { it.copy(description = text) }
    fun setResourceName(name: String) = edit { it.copy(resourceName = name) }

    fun adjust(attribute: ClassDraft.Attribute, delta: Int) = edit {
        val current = when (attribute) {
            ClassDraft.Attribute.STRENGTH -> it.strength
            ClassDraft.Attribute.AGILITY -> it.agility
            ClassDraft.Attribute.INSIGHT -> it.insight
        }
        it.withAttribute(attribute, current + delta)
    }

    /**
     * Picks the art this class is drawn with. Tapping the chosen one again
     * clears it, which falls the class back to the shape renderer — a real
     * choice, not a missing one.
     */
    fun selectSprite(sheetId: String) = edit {
        it.copy(spriteSetId = if (it.spriteSetId == sheetId) null else sheetId)
    }

    fun toggleSkill(skillId: String) = edit { it.toggling(skillId) }
    fun toggleBlock(blockId: String) = edit { it.togglingBlock(blockId) }
    fun selectWeapon(weaponId: String) = edit {
        // Tapping the chosen weapon again clears it, so "no preference" stays
        // reachable without a separate button for it.
        it.copy(startingWeaponId = if (it.startingWeaponId == weaponId) null else weaponId)
    }

    /** Starts over from an existing class, player-made or pack-shipped. */
    fun editExisting(hero: HeroClassDefinition) {
        _state.value = _state.value.copy(draft = ClassDraft.from(hero), message = null)
    }

    fun reset() {
        _state.value = _state.value.copy(draft = ClassDraft(), message = null)
    }

    /** Re-reads the sheets, for art generated while this screen was open. */
    fun refreshSheets() {
        _state.value = _state.value.copy(sheets = heroSheets())
    }

    /**
     * Every sheet a hero can be drawn with: all of them. Hero art is listed
     * first, then characters, monsters and pack art, so the likely choice is
     * nearest; the player may pick any.
     *
     * Generated sheets first within each group: the same id drawn again is
     * the newer art, and `distinctBy` keeps whichever it sees first.
     */
    private fun heroSheets(): List<SpriteSheet> =
        (loadSheets() + content.spriteSheets)
            .distinctBy { it.id }
            .sortedBy { if (SpriteNamespace.servesHero(it.id)) 0 else 1 }

    /**
     * Asks for this hero's look in the background: a sheet drawn from its name
     * and description. Returns at once; the look is picked for the draft when
     * it lands, if the draft is still the same hero.
     */
    fun makeLook() {
        val draft = _state.value.draft
        val queue = queueLook ?: return
        if (draft.name.isBlank()) {
            _state.value = _state.value.copy(message = "Name the hero first, so the look has someone to draw.")
            return
        }
        val subject = listOf(draft.name, draft.title, draft.description).filter { it.isNotBlank() }.joinToString(", ")
        val queued = queue(subject) { sheet ->
            val current = _state.value
            _state.value = current.copy(
                sheets = heroSheets(),
                lookPending = false,
                draft = if (current.draft.name == draft.name) current.draft.copy(spriteSetId = sheet.id) else current.draft,
                message = "${draft.name}'s look is ready.",
            )
        }
        _state.value = _state.value.copy(
            lookPending = queued,
            message = if (queued) "Drawing ${draft.name}'s look in the background. Save and play meanwhile." else "Connect a model provider to draw a look.",
        )
    }

    fun save() {
        val draft = _state.value.draft
        val problems = draft.problems(options)
        if (problems.isNotEmpty()) {
            _state.value = _state.value.copy(message = problems.first())
            return
        }
        saveClass(draft.toDefinition())
        _state.value = _state.value.copy(
            draft = ClassDraft(),
            saved = loadClasses(),
            message = "Saved ${draft.name}. It is playable from the menu.",
        )
    }

    fun delete(heroId: String) {
        deleteClass(heroId)
        _state.value = _state.value.copy(saved = loadClasses(), message = null)
    }

    companion object {
        fun factory(
            content: AssembledContent,
            saveClass: (HeroClassDefinition) -> Unit,
            deleteClass: (String) -> Unit,
            loadClasses: () -> List<HeroClassDefinition>,
            loadSheets: () -> List<SpriteSheet> = { emptyList() },
            queueLook: ((String, (SpriteSheet) -> Unit) -> Boolean)? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ClassForgeViewModel(content, saveClass, deleteClass, loadClasses, loadSheets, queueLook) as T
        }

    }
}

/** Everything the class forge renders. */
data class ClassForgeUiState(
    val draft: ClassDraft,
    val options: ClassOptions,
    val skills: List<com.stratum.core.domain.actor.SkillDefinition>,
    val weapons: List<com.stratum.core.domain.item.WeaponBase>,
    val blocks: List<com.stratum.core.domain.world.BlockType>,
    val sheets: List<SpriteSheet> = emptyList(),
    val saved: List<HeroClassDefinition> = emptyList(),
    val message: String? = null,
    /** A look is being drawn in the background. */
    val lookPending: Boolean = false,
    /** Whether a look can be asked for at all; false hides the step. */
    val canMakeLook: Boolean = false,
) {
    val problems: List<String> get() = draft.problems(options)

    val canSave: Boolean get() = problems.isEmpty()
}
