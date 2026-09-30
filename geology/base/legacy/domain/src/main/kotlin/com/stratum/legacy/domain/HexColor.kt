package com.stratum.legacy.domain

/**
 * Parses `#RRGGBB` and `#AARRGGBB` into a packed ARGB int.
 *
 * Replaces `android.graphics.Color.parseColor`, which was the only reason this
 * module needed Android. Parsing a hex string is domain arithmetic, not a
 * platform service, and keeping it here is what lets the module stay pure.
 */
internal fun parseHexColor(hex: String, fallback: Int = 0xFFE0E0E0.toInt()): Int {
    val cleaned = hex.trim().removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return fallback
    if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return fallback
    val value = cleaned.toLong(16)
    return if (cleaned.length == 6) (0xFF000000L or value).toInt() else value.toInt()
}
