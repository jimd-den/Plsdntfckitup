package com.stratum.core.data.sprite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.stratum.core.domain.sprite.AtlasBakePlan
import com.stratum.core.domain.sprite.AtlasBaker
import com.stratum.core.domain.sprite.SpriteAtlas
import com.stratum.core.domain.sprite.SpriteSheet
import java.io.ByteArrayOutputStream

/** A hand-mapped atlas rendered down to a sheet the engine can draw. */
data class BakedAtlas(val sheet: SpriteSheet, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is BakedAtlas && sheet == other.sheet && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * sheet.hashCode() + bytes.contentHashCode()
}

/**
 * Executes a bake plan against real pixels.
 *
 * Everything about *where* each frame goes was decided in the domain and tested
 * without a bitmap. This is the half that cannot be: decode, blit, flip,
 * encode. Keeping the split that sharply is what makes the interesting
 * question — does a walk built from six differently cropped cells line up —
 * answerable by a unit test rather than by looking at a phone.
 */
object SpriteAtlasBaker {

    fun bake(plan: AtlasBakePlan, source: Bitmap): BakedAtlas? {
        if (plan.isEmpty || plan.sheetWidth <= 0 || plan.sheetHeight <= 0) return null

        val canvasBitmap = runCatching {
            Bitmap.createBitmap(plan.sheetWidth, plan.sheetHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        val canvas = Canvas(canvasBitmap)
        // Nearest neighbour, always. The frames being packed are pixel art, and
        // the one operation guaranteed to ruin it is a bilinear resample on the
        // way into the atlas -- which then bakes the mush in permanently,
        // unlike smoothing at draw time.
        val paint = Paint().apply {
            isFilterBitmap = false
            isAntiAlias = false
            isDither = false
        }

        for (frame in plan.frames) {
            val src = Rect(
                frame.source.left,
                frame.source.top,
                frame.source.right,
                frame.source.bottom,
            )
            val dst = Rect(
                frame.destination.left,
                frame.destination.top,
                frame.destination.left + frame.destination.width,
                frame.destination.top + frame.destination.height,
            )
            if (src.isEmpty || dst.isEmpty) continue

            if (frame.flippedX) {
                canvas.save()
                // Mirrored about the destination's own centre, so the frame
                // stays in its cell instead of landing in the neighbour's.
                canvas.scale(-1f, 1f, dst.exactCenterX(), dst.exactCenterY())
                canvas.drawBitmap(source, src, dst, paint)
                canvas.restore()
            } else {
                canvas.drawBitmap(source, src, dst, paint)
            }
        }

        val out = ByteArrayOutputStream()
        val ok = canvasBitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        canvasBitmap.recycle()
        return if (ok) BakedAtlas(plan.sheet, out.toByteArray()) else null
    }

    /** Plans and bakes in one step, for the common case of "save what I mapped". */
    fun bake(atlas: SpriteAtlas, source: Bitmap): BakedAtlas? =
        AtlasBaker.plan(atlas)?.let { bake(it, source) }

    /**
     * The whole image as ARGB, for the domain's slicing and trimming to read.
     *
     * Deliberately one array rather than a stream of callbacks: the domain
     * works on an `IntArray` the same way [com.stratum.core.domain.sprite.SheetInspection]
     * already does, and a sheet is a few megabytes at worst -- paid once when a
     * person opens the editor, not per frame.
     */
    fun pixelsOf(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    /** Decodes source art without letting the platform rescale it for density. */
    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                // A density-scaled sprite sheet is cut on a grid that no longer
                // matches the one the frames were measured against.
                inScaled = false
                inPremultiplied = false
            },
        )
    }.getOrNull()

    /** PNG ignores this, but the API demands it. */
    private const val PNG_QUALITY = 100
}
