package com.stratum.engine.microvoxel

/**
 * Free colour for microvoxel models: a fixed cube of paints, eight levels a
 * channel plus sixteen greys, registered up front in every palette.
 *
 * A model a player paints, or a picture turned into voxels, is not made of
 * named materials but of colours. Each colour lands on its nearest paint, so
 * the palette never grows while a renderer reads it, and the same model looks
 * the same in every world.
 */
object Paints {
    const val PREFIX = "paint:"
    private val LEVELS = intArrayOf(0, 36, 73, 109, 146, 182, 219, 255)
    private const val GREYS = 16

    /** Every paint's RGB, in registration order. */
    val all: List<Int> = buildList {
        for (r in LEVELS) for (g in LEVELS) for (b in LEVELS) add((r shl 16) or (g shl 8) or b)
        for (i in 0 until GREYS) { val v = i * 255 / (GREYS - 1); add((v shl 16) or (v shl 8) or v) }
    }.distinct()

    fun name(rgb: Int): String = PREFIX + (rgb and 0xFFFFFF).toString(16).padStart(6, '0')

    fun isPaint(name: String): Boolean = name.startsWith(PREFIX)

    fun register(palette: MaterialPalette) {
        for (rgb in all) palette.register(name(rgb), rgb, jitter = 0.035f)
    }

    /** The paint nearest [rgb]: a grey when the colour is nearly neutral, else the nearest step on each channel. */
    fun nearest(rgb: Int): Int {
        val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
        if (maxOf(r, g, b) - minOf(r, g, b) < 14) {
            val v = ((r + g + b) / 3f / 255f * (GREYS - 1) + 0.5f).toInt().coerceIn(0, GREYS - 1) * 255 / (GREYS - 1)
            return (v shl 16) or (v shl 8) or v
        }
        return (level(r) shl 16) or (level(g) shl 8) or level(b)
    }

    private fun level(c: Int): Int = LEVELS.minByOrNull { kotlin.math.abs(it - c) }!!

    /**
     * The material a model's palette entry stands for in [palette]: a colour
     * (`#RRGGBB`) takes its nearest paint; a name its material; an unknown
     * name the stone every palette has.
     */
    fun resolve(entry: String, palette: MaterialPalette): Short {
        if (entry.length == 7 && entry[0] == '#') {
            val rgb = entry.substring(1).toIntOrNull(16) ?: return palette.id(M.STONE)
            return palette.id(name(nearest(rgb)))
        }
        return runCatching { palette.id(entry) }.getOrElse { palette.id(M.STONE) }
    }
}
