package com.stratum.core.data.importing

/**
 * The arithmetic of placing imported art, kept apart from bitmaps so it can
 * be tested without a device.
 */
internal object ImportedArtLayout {

    /**
     * Pixel art is drawn tiny -- 16 pixels a tile is common -- and the renderer
     * resamples every texture to one large size with a smoothing filter. Scaled
     * by a whole number first, with no smoothing, each art pixel arrives as a
     * crisp block rather than a blur.
     */
    const val MIN_TEXTURE_SIZE = 128

    fun upscaleFactor(width: Int, height: Int): Int =
        (MIN_TEXTURE_SIZE / maxOf(width, height, 1)).coerceAtLeast(1)

    /** Top-left corner that puts a region at the bottom centre of its cell, where a character's feet go. */
    fun anchorInCell(regionWidth: Int, regionHeight: Int, cellWidth: Int, cellHeight: Int): Pair<Int, Int> =
        (cellWidth - regionWidth) / 2 to (cellHeight - regionHeight)
}
