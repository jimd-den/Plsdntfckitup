package com.stratum.importer.common

/**
 * Turns whatever names a foreign project uses into ids the engine accepts:
 * namespaced, lower case, and stable across imports of the same project, so
 * re-importing a map updates it rather than adding a second copy.
 */
object ImportNaming {

    /** `Forest Tiles (v2)` becomes `forest_tiles_v2`. */
    fun slug(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "unnamed" }

    fun id(namespace: String, vararg parts: String): String =
        "${slug(namespace)}:${parts.joinToString("_") { slug(it) }}"

    /** `forest_tiles` becomes `Forest Tiles`. */
    fun displayName(text: String): String =
        text.split('_', '-', ' ').filter(String::isNotBlank).joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
}

/**
 * Colours for imported blocks that did not say what colour they are.
 *
 * The voxel renderer needs a colour for every block even when a texture will
 * be drawn over it -- it is what shows at a distance and while art loads. A
 * hash of the id gives each block its own, stably, and the band keeps them
 * muted so no untextured block shouts over the actors.
 */
object SeedColors {

    fun topFor(id: String): Long = argb(hueOf(id), saturation = 0.28f, value = 0.62f)

    fun sideFor(id: String): Long = argb(hueOf(id), saturation = 0.30f, value = 0.42f)

    /** Parses Tiled's `#rrggbb` or `#aarrggbb`, or null for anything else. */
    fun parse(text: String?): Long? {
        val hex = text?.trim()?.removePrefix("#") ?: return null
        val value = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> OPAQUE or value
            8 -> value
            else -> null
        }
    }

    /** Darkens a colour for the side of a block, keeping its alpha. */
    fun shade(color: Long, factor: Float = 0.7f): Long {
        fun channel(shift: Int) = (((color shr shift) and 0xFF) * factor).toLong().coerceIn(0, 255) shl shift
        return (color and OPAQUE) or channel(16) or channel(8) or channel(0)
    }

    private fun hueOf(id: String): Float = ((id.hashCode().toLong() and 0xFFFF) % 360).toFloat()

    private fun argb(hue: Float, saturation: Float, value: Float): Long {
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
        val m = value - c
        val (r, g, b) = when ((hue / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(v: Float) = ((v + m) * 255).toLong().coerceIn(0, 255)
        return OPAQUE or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    private const val OPAQUE = 0xFF000000L
}
