package com.stratum.engine.scene

/**
 * How large each texture layer can be for a given number of layers.
 *
 * Renderers resample every tile to one size so they fit one texture array.
 * At 512 texels a layer costs over a megabyte with its mips, which is fine
 * for a kit of a few dozen and ruinous for an imported game of a few hundred.
 * Shrinking the layers as their number grows keeps the whole array inside a
 * fixed budget, so a big import draws a little softer rather than not at all.
 */
object TextureBudget {

    /** Enough for the shipped kits at full size, several times over. */
    const val BYTES: Long = 96L * 1024 * 1024

    val SIZES = listOf(512, 256, 128, 64)

    /** RGBA, plus a third for the mip chain. */
    fun bytesPerLayer(size: Int): Long = size.toLong() * size * 4 * 4 / 3

    fun layerSize(layers: Int, budget: Long = BYTES): Int =
        SIZES.firstOrNull { layers * bytesPerLayer(it) <= budget } ?: SIZES.last()

    /** Mip levels down to one texel. */
    fun mipLevels(size: Int): Int = Integer.numberOfTrailingZeros(Integer.highestOneBit(size)) + 1
}
