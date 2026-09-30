package com.stratum.core.data.sprite

import com.stratum.core.domain.sprite.FrameAnalyser
import com.stratum.core.domain.sprite.FrameAnalysis
import com.stratum.core.domain.sprite.KeyStrategy
import com.stratum.core.domain.sprite.SpriteKeying

/** A generated pose decoded and keyed: raw ARGB, background cleared. */
class KeyedFrame(val pixels: IntArray, val width: Int, val height: Int, val strategy: KeyStrategy)

/**
 * The only part of frame checking that needs Android: turning bytes into pixels.
 *
 * Keying and measuring are domain rules and are called from here unchanged, so
 * the frame the run judges is keyed exactly as the frame the composer will pack.
 */
object PoseFrameInspector {

    fun keyed(bytes: ByteArray): KeyedFrame? {
        val bitmap = SpriteAtlasBaker.decode(bytes) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            bitmap.recycle()
            return null
        }
        val pixels = SpriteAtlasBaker.pixelsOf(bitmap)
        bitmap.recycle()
        val keyed = SpriteKeying.key(pixels, width, height)
        return KeyedFrame(keyed.pixels, width, height, keyed.strategy)
    }

    /** What a pose holds, or null when the bytes are not an image at all. */
    fun analyse(bytes: ByteArray): FrameAnalysis? =
        keyed(bytes)?.let { FrameAnalyser.analyse(it.pixels, it.width, it.height) }
}
