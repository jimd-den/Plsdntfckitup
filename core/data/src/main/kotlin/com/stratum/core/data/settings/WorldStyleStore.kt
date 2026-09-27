package com.stratum.core.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Remembers the world style the player last chose, in their own words, so a
 * style painted in the texture forge is still worn after a restart.
 */
class WorldStyleStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): String = prefs.getString(KEY_PROMPT, null).orEmpty()

    fun save(prompt: String) {
        prefs.edit { putString(KEY_PROMPT, prompt) }
    }

    private companion object {
        const val FILE = "stratum_world_style"
        const val KEY_PROMPT = "prompt"
    }
}
