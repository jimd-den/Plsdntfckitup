package com.stratum.core.domain.sprite

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The colour of a figure, summarised per channel over its opaque pixels. */
data class ColourStats(
    val mean: FloatArray,
    val spread: FloatArray,
    val samples: Int,
) {
    init {
        require(mean.size == CHANNELS && spread.size == CHANNELS)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ColourStats && samples == other.samples &&
                mean.contentEquals(other.mean) && spread.contentEquals(other.spread))

    override fun hashCode(): Int = (mean.contentHashCode() * 31 + spread.contentHashCode()) * 31 + samples

    companion object {
        const val CHANNELS = 3

        /** Stats over every pixel at least half opaque; null when there are none. */
        fun of(pixels: IntArray): ColourStats? {
            val sum = DoubleArray(CHANNELS)
            val squares = DoubleArray(CHANNELS)
            var count = 0
            for (pixel in pixels) {
                if ((pixel ushr 24) and 0xFF < SOLID) continue
                count++
                for (c in 0 until CHANNELS) {
                    val v = channel(pixel, c).toDouble()
                    sum[c] += v
                    squares[c] += v * v
                }
            }
            if (count == 0) return null
            val mean = FloatArray(CHANNELS) { (sum[it] / count).toFloat() }
            val spread = FloatArray(CHANNELS) {
                sqrt((squares[it] / count - (sum[it] / count) * (sum[it] / count)).coerceAtLeast(0.0)).toFloat()
            }
            return ColourStats(mean, spread, count)
        }

        /**
         * The per-channel median of several frames' stats.
         *
         * Medians, because the frame this exists to correct is the outlier, and
         * a mean would move toward it.
         */
        fun consensus(all: List<ColourStats>): ColourStats? {
            if (all.isEmpty()) return null
            return ColourStats(
                mean = FloatArray(CHANNELS) { c -> median(all.map { it.mean[c] }) },
                spread = FloatArray(CHANNELS) { c -> median(all.map { it.spread[c] }) },
                samples = all.sumOf { it.samples },
            )
        }

        internal fun channel(pixel: Int, c: Int): Int = (pixel ushr (16 - 8 * c)) and 0xFF

        private fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
        }

        private const val SOLID = 128
    }
}

/** A per-channel gain and offset that pulls one frame's colours toward the set's. */
data class ColourCorrection(val gain: FloatArray, val offset: FloatArray) {

    val isIdentity: Boolean
        get() = gain.all { abs(it - 1f) < EPSILON } && offset.all { abs(it) < OFFSET_EPSILON }

    /** The frame with its colours corrected; alpha untouched, fully clear pixels untouched. */
    fun applyTo(pixels: IntArray): IntArray {
        if (isIdentity) return pixels
        return IntArray(pixels.size) { i ->
            val pixel = pixels[i]
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha == 0) {
                pixel
            } else {
                var out = alpha shl 24
                for (c in 0 until ColourStats.CHANNELS) {
                    val v = ColourStats.channel(pixel, c) * gain[c] + offset[c]
                    out = out or (v.roundToInt().coerceIn(0, 255) shl (16 - 8 * c))
                }
                out
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ColourCorrection && gain.contentEquals(other.gain) && offset.contentEquals(other.offset))

    override fun hashCode(): Int = gain.contentHashCode() * 31 + offset.contentHashCode()

    companion object {
        val NONE = ColourCorrection(FloatArray(ColourStats.CHANNELS) { 1f }, FloatArray(ColourStats.CHANNELS))
        private const val EPSILON = 1e-3f
        private const val OFFSET_EPSILON = 0.5f
    }
}

/**
 * Takes the colour drift out of frames drawn one generation at a time.
 *
 * Identity is held by the reference pixels, and it holds for shape far better
 * than for colour: across a row of separately edited frames the palette wanders
 * — one frame a little warmer, one a little darker — and played back the
 * character flickers in a way no single frame shows. Each frame's colours are
 * summarised and nudged toward the consensus of the whole set, a gain and an
 * offset per channel.
 *
 * Clamped hard, like [SpriteDrift], and for the same reason. A pose genuinely
 * shows different parts of the costume — the back of a cloak, the inside of a
 * shield arm — so the stats move for honest reasons too. The clamp is set where
 * a global tint is taken out and a real change in what is visible is left
 * alone.
 */
object PaletteMatch {

    /** How far a channel may be stretched or squeezed. */
    const val MAX_GAIN = 1.12f

    /** How far a channel may be shifted, in 0–255 steps. */
    const val MAX_OFFSET = 18f

    fun correctionFor(
        frame: ColourStats,
        target: ColourStats,
        maxGain: Float = MAX_GAIN,
        maxOffset: Float = MAX_OFFSET,
    ): ColourCorrection {
        val gain = FloatArray(ColourStats.CHANNELS)
        val offset = FloatArray(ColourStats.CHANNELS)
        for (c in 0 until ColourStats.CHANNELS) {
            val g = if (frame.spread[c] < MIN_SPREAD || target.spread[c] < MIN_SPREAD) {
                1f
            } else {
                (target.spread[c] / frame.spread[c]).coerceIn(1f / maxGain, maxGain)
            }
            gain[c] = g
            offset[c] = (target.mean[c] - g * frame.mean[c]).coerceIn(-maxOffset, maxOffset)
        }
        return ColourCorrection(gain, offset)
    }

    /**
     * A correction for each frame, keyed as given, toward the set's consensus.
     *
     * A set of one has nothing to agree with, and is left alone.
     */
    fun correctionsFor(stats: Map<String, ColourStats>): Map<String, ColourCorrection> {
        if (stats.size < 2) return stats.mapValues { ColourCorrection.NONE }
        val target = ColourStats.consensus(stats.values.toList()) ?: return emptyMap()
        return stats.mapValues { (_, frame) -> correctionFor(frame, target) }
    }

    /** A channel this flat has no spread to match; stretching it would be noise. */
    private const val MIN_SPREAD = 2f
}
