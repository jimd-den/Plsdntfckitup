package com.stratum.app

import android.content.Context
import com.stratum.core.data.settings.GraphicsSettingsStore
import com.stratum.engine.scene.SplatMode
import com.stratum.engine.scene.quality.DeviceClassifier
import com.stratum.engine.scene.quality.QualityTier
import com.stratum.engine.scene.quality.RenderSettings
import com.stratum.feature.play.gl.AndroidDeviceProfiles

/**
 * The player's graphics choice, and what it means before a GL context exists:
 * how many chunks a run keeps in memory. The renderer settles the rest once it
 * can read the GPU.
 */
class GraphicsWiring(private val context: Context) {

    private val store = GraphicsSettingsStore(context)

    /** Null is Auto. */
    var chosen: QualityTier? = store.load()?.let { name -> QualityTier.entries.firstOrNull { it.name == name } }
        private set

    fun choose(tier: QualityTier?) {
        chosen = tier
        store.save(tier?.name)
    }

    /** How the land is drawn; null is the tier's own choice. */
    var terrain: SplatMode? = store.loadTerrain()?.let { name -> SplatMode.entries.firstOrNull { it.name == name } }
        private set

    fun chooseTerrain(mode: SplatMode?) {
        terrain = mode
        store.saveTerrain(mode?.name)
    }

    /** Settings from what the OS knows about this device, for decisions made before rendering starts. */
    fun startingSettings(): RenderSettings = DeviceClassifier.settingsFor(AndroidDeviceProfiles.fromContext(context), chosen).withTerrain(terrain)
}
