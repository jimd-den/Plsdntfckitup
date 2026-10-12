package com.stratum.app

import android.content.Context
import com.stratum.core.data.character.CharacterRepositoryImpl
import com.stratum.core.data.settings.PlayerPreferencesStore
import com.stratum.core.data.sprite.PoseGuideStore
import com.stratum.core.data.sprite.PoseLibrary
import com.stratum.core.data.sprite.SpriteLibrary
import com.stratum.core.data.sprite.SpriteProjectStore
import com.stratum.core.data.sprite.WeaponFitStore
import com.stratum.core.data.sprite.WeaponLibrary
import com.stratum.core.domain.character.CharacterRepository

/**
 * The art and preferences kept on the device: sprite sheets imported or
 * mapped by hand, weapons, the poses characters were built from, the 3D
 * models and blueprints the world draws, and the player's choices.
 *
 * Everything here is plain storage; nothing calls a network or a model.
 */
class AssetWiring(context: Context) {

    /** Persists player's active character art, weapon, and class choices across app restarts. */
    val playerPreferences = PlayerPreferencesStore(context)

    /** Sprite sheets on the device, keyed by id: imported with a pack or cut in the sprite mapper. */
    val sprites = SpriteLibrary(context)

    /**
     * Hand-mapped atlases and the art they were mapped from, kept apart from
     * the baked sheets so re-cutting one never destroys the image it came from.
     */
    val spriteProjects = SpriteProjectStore(context)

    /** The full-size poses a character was built from. */
    val poses = PoseLibrary(context).apply { forgetEmptySets() }

    /** Which skeletons each character was drawn against. */
    val poseGuides = PoseGuideStore(context)

    /** Weapons, kept apart from the characters that swing them. */
    val weapons = WeaponLibrary(context)

    /** How each character holds a weapon: its hands, not the weapon's. */
    val weaponFits = WeaponFitStore(context)

    /** Characters: poses, sheets, pose guides and weapon fits under one reactive boundary. */
    val characterRepository: CharacterRepository = CharacterRepositoryImpl(
        poses = poses,
        sprites = sprites,
        poseGuides = poseGuides,
        weaponFits = weaponFits,
    )

    /** Kept 3D models and voxel blueprints, and how the world draws them. */
    val models = ModelWiring(context)
}
