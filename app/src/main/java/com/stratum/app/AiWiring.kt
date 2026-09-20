package com.stratum.app

import android.content.Context
import com.stratum.core.data.ai.OpenRouterImageModel
import com.stratum.core.data.ai.OpenRouterLanguageModel
import com.stratum.core.data.sprite.SpriteLibrary
import com.stratum.core.data.sprite.PoseGuideStore
import com.stratum.core.data.sprite.PoseLibrary
import com.stratum.core.data.sprite.SpriteProjectStore
import com.stratum.core.data.sprite.WeaponFitStore
import com.stratum.core.data.sprite.WeaponLibrary
import com.stratum.core.data.settings.ProviderSettingsStore
import com.stratum.core.domain.ai.GenerateContentPackUseCase
import com.stratum.core.domain.ai.GenerateLoreUseCase
import com.stratum.core.domain.ai.GenerateBasePoseUseCase
import com.stratum.core.domain.ai.GeneratePoseFrameUseCase
import com.stratum.core.domain.ai.GenerateSpriteSheetUseCase
import com.stratum.core.domain.ai.GenerateWeaponUseCase

/**
 * Wires the generation use cases to a real provider.
 *
 * The settings store is read on every call rather than captured once, so
 * changing the key or the model in settings takes effect on the next
 * generation instead of the next app launch.
 */
class AiWiring(context: Context) {

    val settings = ProviderSettingsStore(context)

    private val languageModel = OpenRouterLanguageModel(configProvider = settings::load)

    private val imageModel = OpenRouterImageModel(configProvider = settings::load)

    /** Generated sheets live on the device, keyed by id. */
    val sprites = SpriteLibrary(context)

    /**
     * Hand-mapped atlases and the art they were mapped from, kept apart from
     * the baked sheets so re-cutting one never destroys the image it came from.
     */
    val spriteProjects = SpriteProjectStore(context)

    /**
     * The full-size poses a character was built from, kept so a set can be
     * resumed after a failure and re-packed at another frame size without
     * paying for a single generation twice.
     */
    val poses = PoseLibrary(context).apply {
        // Reading a set used to create its folder, and the forge probes for
        // one on every keystroke of the subject line -- so every device that
        // ran that build carries a folder for every prefix anyone ever typed.
        // They list as characters with no poses. Swept once on startup rather
        // than left for the person to delete by hand, twelve at a time.
        forgetEmptySets()
    }

    /**
     * Which skeletons each character was drawn against.
     *
     * Kept because the weapon has to be rigged against the same poses the art
     * followed: rigging a character generated from an imported OpenPose
     * skeleton against the built-in set hangs the sword where the drawing did
     * not put the hand.
     */
    val poseGuides = PoseGuideStore(context)

    /**
     * Weapons, kept apart from the characters that swing them: one sword serves
     * every actor in the game rather than belonging to whoever it was drawn on.
     */
    val weapons = WeaponLibrary(context)

    /** How each character holds a weapon: its hands, not the weapon's. */
    val weaponFits = WeaponFitStore(context)

    val generateContentPack = GenerateContentPackUseCase(languageModel)

    val generateLore = GenerateLoreUseCase(languageModel)

    val generateSpriteSheet = GenerateSpriteSheetUseCase(imageModel)

    /** The one drawing a character is built from, and the edits that animate it. */
    val generateBasePose = GenerateBasePoseUseCase(imageModel)

    val generatePoseFrame = GeneratePoseFrameUseCase(imageModel)

    val generateWeapon = GenerateWeaponUseCase(imageModel)

    val modelCatalog = languageModel

    fun isConfigured(): Boolean = settings.isConfigured
}
