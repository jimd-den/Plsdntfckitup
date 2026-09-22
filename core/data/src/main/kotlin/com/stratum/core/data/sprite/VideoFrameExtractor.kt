package com.stratum.core.data.sprite

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.stratum.core.domain.sprite.ClipCut
import com.stratum.core.domain.sprite.ClipSampling
import java.io.ByteArrayOutputStream

/**
 * Cuts still frames out of a generated clip.
 *
 * The decode half of the video path, and the reason it lives here rather than
 * in the domain: a clip is bytes in a container, and turning those into pixels
 * is Android's job, exactly as it already is for a PNG. The domain decides
 * *which* moments to cut -- see ClipSampling, which spaces them by the same
 * rule the drawn poses are spaced by -- and this turns a moment into an image.
 *
 * Frames come back as PNG bytes rather than bitmaps so that everything
 * downstream is unchanged: a frame cut from a clip and a frame generated as a
 * still are the same thing by the time they reach keying, anchoring and
 * packing, which is what lets a row be half of each.
 *
 * One thing to expect from video and not from stills: motion blur. A model
 * asked for movement will blur a fast limb, and a blurred edge against a chroma
 * backdrop keys to a fringe rather than to nothing. Keying reports its
 * survivors, so this is measurable rather than a surprise -- but a segment
 * generated too long, so that the movement through it is fast, is the setting
 * that causes it.
 */
object VideoFrameExtractor {

    /**
     * How long the clip is, or null if the container will not say.
     *
     * Separate because a caller with several cuts to make should read it once,
     * and because a clip whose duration is unreadable is worth failing on
     * early: every cut is a fraction of it, so without it nothing can be cut
     * at the right moment.
     */
    fun durationMillisOf(clip: ByteArray): Long? = withRetriever(clip) { retriever ->
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
    }

    /**
     * The frames at the given cuts, in the order asked for.
     *
     * A cut that cannot be decoded is dropped rather than failing the batch, so
     * a clip with one unreadable frame still yields the rest -- the pipeline
     * already knows how to redraw a single missing frame as a still, and losing
     * eleven good frames to salvage one is the worse trade. The result is keyed
     * by frame index so the caller can see which are missing rather than having
     * to count.
     *
     * @param durationMillis the clip's length, read once by the caller. Falls
     *   back to reading it here, which costs another open of the container.
     */
    fun framesAt(
        clip: ByteArray,
        cuts: List<ClipCut>,
        durationMillis: Long? = null,
        maxEdge: Int = 0,
    ): Map<Int, ByteArray> {
        if (cuts.isEmpty()) return emptyMap()
        return withRetriever(clip) { retriever ->
            val length = durationMillis
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                ?: return@withRetriever emptyMap()
            if (length <= 0) return@withRetriever emptyMap()

            buildMap {
                cuts.forEach { cut ->
                    val at = ClipSampling.millisFor(cut, length)
                    // CLOSEST rather than CLOSEST_SYNC. Sync frames are
                    // keyframes of the *encoding*, which fall wherever the
                    // encoder put them -- asking for the nearest one would
                    // quietly snap twelve evenly spaced cuts onto four
                    // distinct pictures.
                    val frame = runCatching {
                        retriever.getFrameAtTime(
                            at * MICROS_PER_MILLI,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                        )
                    }.getOrNull() ?: return@forEach

                    val scaled = frame.scaledTo(maxEdge)
                    val png = scaled.toPng()
                    if (scaled !== frame) scaled.recycle()
                    frame.recycle()
                    if (png != null) put(cut.index, png)
                }
            }
        } ?: emptyMap()
    }

    private fun Bitmap.scaledTo(maxEdge: Int): Bitmap {
        if (maxEdge <= 0) return this
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return this
        val scale = maxEdge.toFloat() / longest
        return runCatching {
            Bitmap.createScaledBitmap(
                this,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }.getOrDefault(this)
    }

    private fun Bitmap.toPng(): ByteArray? = runCatching {
        val out = ByteArrayOutputStream()
        // PNG and lossless on purpose. The frame is about to be chroma keyed,
        // and a lossy codec smears the backdrop into the character's edge --
        // which is the difference between keying to nothing and keying to a
        // green fringe.
        if (compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) out.toByteArray() else null
    }.getOrNull()

    /**
     * Opens the clip from memory, and always closes it.
     *
     * From memory rather than through a temporary file because a clip arrives
     * as bytes and writing it to disk to read it straight back is a copy, a
     * permission and a file to forget to delete. A retriever left open holds a
     * decoder, and a run generating a clip per beat would exhaust them.
     */
    private fun <T> withRetriever(clip: ByteArray, block: (MediaMetadataRetriever) -> T): T? {
        if (clip.isEmpty()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(clip.asDataSource())
            block(retriever)
        } catch (error: Exception) {
            // Anything the platform decoder throws on a clip it cannot read.
            // A generated clip is untrusted input: it can arrive truncated, in
            // a container this device has no decoder for, or not be a video at
            // all when a provider returns an error page with the wrong type.
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun ByteArray.asDataSource(): MediaDataSource = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= this@asDataSource.size) return -1
            val available = (this@asDataSource.size - position).toInt().coerceAtMost(size)
            if (available <= 0) return -1
            System.arraycopy(this@asDataSource, position.toInt(), buffer, offset, available)
            return available
        }

        override fun getSize(): Long = this@asDataSource.size.toLong()

        override fun close() = Unit
    }

    private const val MICROS_PER_MILLI = 1_000L
    private const val PNG_QUALITY = 100
}
