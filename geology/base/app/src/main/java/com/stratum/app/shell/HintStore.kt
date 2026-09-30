package com.stratum.app.shell

import android.content.Context

/**
 * The contextual hints the player has already been shown in play.
 *
 * A hint is a teacher for the first time a thing happens, not a caption: once
 * seen it is never shown again, on this run or the next, so it is written
 * down rather than held in memory.
 */
class HintStore(context: Context) {

    private val prefs = context.getSharedPreferences("play_hints", Context.MODE_PRIVATE)

    fun seen(): Set<String> = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toSet()

    fun markSeen(id: String) {
        val now = seen() + id
        prefs.edit().putStringSet(KEY_SEEN, now).apply()
    }

    private companion object {
        const val KEY_SEEN = "seen"
    }
}
