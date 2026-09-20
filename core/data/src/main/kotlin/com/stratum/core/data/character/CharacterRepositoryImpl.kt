package com.stratum.core.data.character

import com.stratum.core.data.sprite.PoseGuideStore
import com.stratum.core.data.sprite.PoseLibrary
import com.stratum.core.data.sprite.SpriteLibrary
import com.stratum.core.data.sprite.WeaponFitStore
import com.stratum.core.domain.ai.SavedCharacter
import com.stratum.core.domain.character.Character
import com.stratum.core.domain.character.CharacterRepository
import com.stratum.core.domain.character.CharacterRole
import com.stratum.core.domain.sprite.PoseGuides
import com.stratum.core.domain.sprite.SpriteNamespace
import com.stratum.core.domain.sprite.WeaponFit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Coordinates [PoseLibrary], [SpriteLibrary], [PoseGuideStore], and [WeaponFitStore]
 * into a single unified [Character] aggregate repository.
 */
class CharacterRepositoryImpl(
    private val poses: PoseLibrary,
    private val sprites: SpriteLibrary,
    private val poseGuides: PoseGuideStore,
    private val weaponFits: WeaponFitStore,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CharacterRepository {

    private val _characters = MutableStateFlow<List<Character>>(emptyList())
    override val characters: StateFlow<List<Character>> = _characters.asStateFlow()

    init {
        refresh()
        scope.launch {
            combine(
                poses.sets,
                sprites.sheets,
                poseGuides.version,
                weaponFits.version,
            ) { _, _, _, _ -> Unit }
                .collectLatest {
                    _characters.value = buildCharacters()
                }
        }
    }

    override fun all(): List<Character> =
        _characters.value.ifEmpty { buildCharacters() }

    override fun find(setId: String): Character? =
        all().firstOrNull { it.setId == setId }

    override fun delete(setId: String) {
        poses.deleteSet(setId)
        sprites.delete(setId)
        poseGuides.save(setId, PoseGuides())
        weaponFits.save(setId, WeaponFit.none)
        refresh()
    }

    override fun refresh() {
        _characters.value = buildCharacters()
    }

    private fun buildCharacters(): List<Character> {
        val poseSets = poses.sets()
        val allSheets = sprites.all()
        val sheetMap = allSheets.associateBy { it.id }

        val allIds = LinkedHashSet<String>().apply {
            addAll(poseSets)
            addAll(allSheets.filter { SpriteNamespace.isCharacter(it.id) }.map { it.id })
        }

        return allIds.map { setId ->
            val keys = poses.keysIn(setId)
            val hasRef = poses.hasReference(setId)
            val sheet = sheetMap[setId]
            Character(
                setId = setId,
                name = SavedCharacter.nameOf(setId),
                role = CharacterRole.fromSetId(setId),
                posesDrawn = keys,
                hasReference = hasRef,
                sheet = sheet,
                guides = poseGuides.guidesFor(setId),
                weaponFit = weaponFits.fitFor(setId),
            )
        }.filterNot { it.isEmpty && it.sheet == null }
    }
}
