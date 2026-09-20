package com.stratum.core.data.sprite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.stratum.core.domain.sprite.PackedSheet
import com.stratum.core.domain.sprite.PoseSheetPlan
import com.stratum.core.domain.sprite.SourceRect
import com.stratum.core.domain.sprite.SpriteKeying
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.core.domain.sprite.SpriteSlicing
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** A set of separately generated poses, keyed, scaled and packed into one sheet. */
data class ComposedSheet(
    val sheet: SpriteSheet,
    val bytes: ByteArray,
    /** Poses the plan expected and never got, so the screen can say which to retry. */
    val missing: List<String>,
) {
    fun packed(): PackedSheet = PackedSheet(sheet, missing)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ComposedSheet &&
                sheet == other.sheet &&
                missing == other.missing &&
                bytes.contentEquals(other.bytes))

    override fun hashCode(): Int {
        var result = sheet.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + missing.hashCode()
        return result
    }
}

/**
 * Turns forty separately generated pictures into one sprite sheet.
 *
 * The hard part is not the packing, it is making the frames belong to each
 * other. Each pose comes back on its own 1024 pixel canvas with the figure at
 * whatever size and height the model felt like, and dropping those into cells
 * as they arrive produces a character that pulses in size and bobs off the
 * floor between frames — which reads as broken in a way the individual frames
 * never hint at.
 *
 * So every pose is measured before any is drawn. The whole set is scaled by one
 * factor, taken from the largest figure in it, and every frame is hung from the
 * same baseline. A crouching roll then really is shorter than a standing idle,
 * instead of being blown up to fill its cell like everything else.
 */
object PoseSheetComposer {

    /**
     * [loadPose] is asked for bytes by key rather than handed a list, because a
     * full character is forty 1024-pixel images and holding them all decoded at
     * once is a hundred and sixty megabytes. They are loaded, used and released
     * one at a time.
     */
    fun compose(plan: PoseSheetPlan, loadPose: (String) -> ByteArray?): ComposedSheet? {
        if (plan.width <= 0 || plan.height <= 0) return null

        // Pass one: measure. Every pose is decoded, keyed and measured, then
        // released. Doing this work twice costs a second and is the price of
        // not holding the whole set in memory at once.
        val bounds = HashMap<String, SourceRect>()
        val anchors = HashMap<String, Int>()
        // Measured either side of where the figure stands, not as one width.
        // A cell has to hold the furthest reach in each direction *from the
        // anchor*, because that is the point every frame is hung from; a cell
        // sized by the widest content box would clip a lunge whose weight is
        // over one foot.
        var leftReach = 0
        var rightReach = 0
        var tallest = 0
        for (cell in plan.cells) {
            val measured = measure(loadPose(cell.key)) ?: continue
            bounds[cell.key] = measured
            val anchor = anchorOf(loadPose(cell.key), measured)
                ?: (measured.left + measured.width / 2)
            anchors[cell.key] = anchor
            if (anchor - measured.left > leftReach) leftReach = anchor - measured.left
            if (measured.right - anchor > rightReach) rightReach = measured.right - anchor
            if (measured.height > tallest) tallest = measured.height
        }
        if (bounds.isEmpty()) return null

        // Symmetric about the anchor, so centring the anchor centres the cell.
        val widest = 2 * maxOf(leftReach, rightReach)

        // Now that the figure's proportions are known, the cells are cut to
        // fit it. A square cell would spend two thirds of its width on empty
        // background and shrink the character to pay for it.
        val fitted = plan.fittedTo(widest, tallest)

        // One factor for the whole set. The figure that needs the most room
        // decides it, and everything else keeps its real size relative to that.
        val scale = min(
            fitted.cellWidth.toFloat() / widest.coerceAtLeast(1),
            fitted.cellHeight.toFloat() / tallest.coerceAtLeast(1),
        )

        val target = runCatching {
            Bitmap.createBitmap(fitted.width, fitted.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val canvas = Canvas(target)
        // Filtered, unlike the atlas baker: this is a real downscale of
        // detailed art from 1024 pixels, where nearest-neighbour would drop
        // three pixels in four and alias every outline into a staircase.
        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            isDither = false
        }

        val missing = mutableListOf<String>()
        for (cell in fitted.cells) {
            val rect = bounds[cell.key]
            val bytes = if (rect == null) null else loadPose(cell.key)
            if (rect == null || bytes == null) {
                missing += cell.key
                continue
            }
            val keyed = keyedBitmap(bytes)
            if (keyed == null) {
                missing += cell.key
                continue
            }

            val width = (rect.width * scale).roundToInt().coerceAtLeast(1)
            val height = (rect.height * scale).roundToInt().coerceAtLeast(1)
            val cellRect = fitted.rectFor(cell)
            // Hung from where the figure meets the ground, and standing on the
            // floor of the cell.
            //
            // Not centred on the content box, which is what this used to do.
            // An animation is a body moving its limbs while its feet stay put,
            // so the box is the wrong reference: an arm coming up widens it
            // and shifts its centre, and the body slides the other way to
            // compensate. On a real walk that put the feet sixty-two pixels
            // apart across a hundred and ninety-one pixel cell, which does not
            // read as a walk with a wobble -- it reads as unrelated poses,
            // because what the eye tracks between frames is the part that is
            // supposed to be still.
            val anchor = anchors[cell.key] ?: (rect.left + rect.width / 2)
            val anchorOffset = ((anchor - rect.left) * scale).roundToInt()
            val left = cellRect.left + fitted.cellWidth / 2 - anchorOffset
            val top = cellRect.top + (fitted.cellHeight - height)

            canvas.drawBitmap(
                keyed,
                Rect(rect.left, rect.top, rect.right, rect.bottom),
                Rect(left, top, left + width, top + height),
                paint,
            )
            keyed.recycle()
        }

        val out = ByteArrayOutputStream()
        val ok = target.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        target.recycle()
        if (!ok) return null

        // fitted.sheet, not plan.sheet: the frame size the runtime cuts on has
        // to be the size the frames were actually drawn at.
        return ComposedSheet(sheet = fitted.sheet, bytes = out.toByteArray(), missing = missing)
    }

    /** Where the figure meets the ground, in the source image's coordinates. */
    private fun anchorOf(bytes: ByteArray?, rect: SourceRect): Int? {
        if (bytes == null) return null
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
        return SpriteSlicing.groundAnchorX(keyed.pixels, width, height, rect)
    }

    /** The box the figure actually occupies, once the chroma is gone. */
    private fun measure(bytes: ByteArray?): SourceRect? {
        if (bytes == null) return null
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
        return SpriteSlicing.contentBounds(
            pixels = keyed.pixels,
            imageWidth = width,
            imageHeight = height,
            rect = SourceRect(0, 0, width, height),
        )
    }

    /** The pose with its background cleared, ready to draw from. */
    private fun keyedBitmap(bytes: ByteArray): Bitmap? {
        val decoded = SpriteAtlasBaker.decode(bytes) ?: return null
        val width = decoded.width
        val height = decoded.height
        if (width <= 0 || height <= 0) {
            decoded.recycle()
            return null
        }
        val pixels = SpriteAtlasBaker.pixelsOf(decoded)
        decoded.recycle()

        val keyed = SpriteKeying.key(pixels, width, height)
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(keyed.pixels, 0, width, 0, 0, width, height)
            }
        }.getOrNull()
    }

    /** PNG ignores this, but the API demands it. */
    private const val PNG_QUALITY = 100
}
