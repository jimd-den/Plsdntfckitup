package com.stratum.core.domain.character

import com.stratum.core.domain.ai.SavedCharacter
import com.stratum.core.domain.sprite.PoseGuides
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.core.domain.sprite.WeaponFit

/**
 * A Character aggregate: the single unit of ownership for a character's lifecycle.
 *
 * In earlier builds, four separate stores held parts of a character:
 * - [PoseLibrary] held raw full-size pose files and the reference image.
 * - [SpriteLibrary] held the packed, cut sprite sheet.
 * - [PoseGuideStore] held the skeletons the character was posed against.
 * - [WeaponFitStore] held the hand offsets and grip scaling.
 *
 * Reconciling them was done by convention across four stores by hand, making
 * characters half-exist across app boundaries.
 *
 * This aggregate root unifies their lifecycle: setId, role, poses, sheet, guides,
 * and weapon fit are owned as one cohesive record.
 */
data class Character(
    val setId: String,
    val name: String = SavedCharacter.nameOf(setId),
    val role: CharacterRole = CharacterRole.fromSetId(setId),
    val posesDrawn: Set<String> = emptySet(),
    val hasReference: Boolean = false,
    val sheet: SpriteSheet? = null,
    val guides: PoseGuides = PoseGuides(),
    val weaponFit: WeaponFit? = null,
) {
    /** Whether this character has been packed into a sprite sheet the world can draw. */
    val isPacked: Boolean get() = sheet != null

    /** True when this set has no reference and no drawn poses. */
    val isEmpty: Boolean get() = posesDrawn.isEmpty() && !hasReference

    /** Whether poses have been drawn and could be packed into a sheet. */
    val canPack: Boolean get() = posesDrawn.isNotEmpty()

    val isHero: Boolean get() = role == CharacterRole.HERO

    val isEnemy: Boolean get() = role == CharacterRole.ENEMY

    /** Converts this aggregate to the lightweight view representation. */
    fun toSavedCharacter(): SavedCharacter = SavedCharacter(
        setId = setId,
        name = name,
        posesDrawn = posesDrawn.size,
        hasReference = hasReference,
        sheetId = sheet?.id,
    )
}
