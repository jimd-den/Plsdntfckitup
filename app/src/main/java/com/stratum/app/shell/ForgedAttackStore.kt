package com.stratum.app.shell

import android.content.Context
import com.stratum.core.domain.attack.AttackCode

/**
 * The attacks the player forged and kept, as attack codes, and which of them
 * they carry into a fight.
 *
 * A code is the whole attack, so nothing else needs saving: an attack kept
 * on one device is the same attack wherever its code is pasted.
 */
class ForgedAttackStore(context: Context) {

    private val prefs = context.getSharedPreferences("forged_attacks", Context.MODE_PRIVATE)

    /** Every kept attack, newest first. */
    fun kept(): List<String> = read(KEY_KEPT)

    /** The attacks carried into play, at most [MAX_EQUIPPED]. */
    fun equipped(): List<String> = read(KEY_EQUIPPED).filter(kept()::contains)

    fun keep(code: String) {
        if (AttackCode.decode(code) == null) return
        write(KEY_KEPT, listOf(code) + kept().filterNot { it == code })
    }

    fun forget(code: String) {
        write(KEY_KEPT, kept().filterNot { it == code })
        write(KEY_EQUIPPED, equipped().filterNot { it == code })
    }

    /** Carries [code], or puts it down when it is already carried; the oldest falls off a full belt. */
    fun toggleEquipped(code: String) {
        val now = equipped()
        write(KEY_EQUIPPED, if (code in now) now - code else (now + code).takeLast(MAX_EQUIPPED))
    }

    private fun read(key: String): List<String> = prefs.getString(key, "").orEmpty().lines().filter(String::isNotBlank)

    private fun write(key: String, codes: List<String>) {
        prefs.edit().putString(key, codes.joinToString("\n")).apply()
    }

    companion object {
        /** Forged attacks carried at once: each takes a button on the fight arc. */
        const val MAX_EQUIPPED = 3
        private const val KEY_KEPT = "kept"
        private const val KEY_EQUIPPED = "equipped"
    }
}
