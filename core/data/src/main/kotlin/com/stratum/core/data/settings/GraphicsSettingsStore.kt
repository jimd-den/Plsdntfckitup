package com.stratum.core.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Remembers the player's graphics choice between runs.
 *
 * Kept as the tier's name rather than the renderer's own type, so the data
 * layer does not depend on the renderer; the composition root turns it back
 * into a tier. Null means Auto: let the device decide.
 */
class GraphicsSettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY_TIER, null)

    fun save(tierName: String?) {
        prefs.edit { putString(KEY_TIER, tierName) }
    }

    private companion object {
        const val FILE = "stratum_graphics"
        const val KEY_TIER = "quality_tier"
    }
}
