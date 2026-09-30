package com.stratum.core.data.sprite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.ColourStats
import com.stratum.core.domain.sprite.FrameAnalyser
import com.stratum.core.domain.sprite.FrameMeasure
import com.stratum.core.domain.sprite.PackedSheet
import com.stratum.core.domain.sprite.PaletteMatch
import com.stratum.core.domain.sprite.PoseSheetLayout
import com.stratum.core.domain.sprite.PoseSheetPlan
import com.stratum.core.domain.sprite.SpriteDrift
import com.stratum.core.domain.sprite.SpriteSheet
import java.io.ByteArrayOutputStream

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
 * other, and every decision about that is made by [PoseSheetLayout] in the
 * domain, where it is tested without a device. This decodes, keys, measures,
 * and then draws each pose where the layout says.
 *
 * Two passes, each decoding a pose once: the first measures every frame and
 * summarises its colours, the second draws it. Holding forty decoded 1024
 * pixel images at once is a hundred and sixty megabytes, so nothing decoded
 * outlives the frame it belongs to.
 */
object PoseSheetComposer {

    /**
     * [loadPose] is asked for bytes by key rather than handed a list, because a
     * full character is forty 1024-pixel images, loaded, used and released one
     * at a time.
     *
     * @param expectedHeights how tall each frame's authored pose is, for the
     *   drift correction; defaults to the built-in skeleton.
     * @param matchPalette pulls each frame's colours toward the set's
     *   consensus ([PaletteMatch]). On by default; separately edited frames
     *   wander in tint, and played back the wander is a flicker.
     */
    fun compose(
        plan: PoseSheetPlan,
        expectedHeights: (AnimationState, Int) -> List<Float> = SpriteDrift::authoredHeightsFor,
        matchPalette: Boolean = true,
        loadPose: (String) -> ByteArray?,
    ): ComposedSheet? {
        if (plan.width <= 0 || plan.height <= 0) return null

        // Pass one: measure. Each pose decoded and keyed once.
        val measures = HashMap<String, FrameMeasure>()
        val colours = HashMap<String, ColourStats>()
        for (cell in plan.cells) {
            val frame = loadPose(cell.key)?.let(PoseFrameInspector::keyed) ?: continue
            val measure = FrameMeasure.of(FrameAnalyser.analyse(frame.pixels, frame.width, frame.height)) ?: continue
            measures[cell.key] = measure
            if (matchPalette) ColourStats.of(frame.pixels)?.let { colours[cell.key] = it }
        }

        val layout = PoseSheetLayout.of(plan, measures, expectedHeights) ?: return null
        val corrections = if (matchPalette) PaletteMatch.correctionsFor(colours) else emptyMap()
        val fitted = layout.plan

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

        val missing = layout.missing.toMutableList()
        for (placement in layout.placements) {
            val frame = loadPose(placement.key)?.let(PoseFrameInspector::keyed)
            if (frame == null) {
                missing += placement.key
                continue
            }
            val pixels = corrections[placement.key]?.applyTo(frame.pixels) ?: frame.pixels
            val bitmap = runCatching {
                Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).apply {
                    setPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                }
            }.getOrNull()
            if (bitmap == null) {
                missing += placement.key
                continue
            }
            val source = placement.source
            val to = placement.target
            canvas.drawBitmap(
                bitmap,
                Rect(source.left, source.top, source.right, source.bottom),
                Rect(to.left, to.top, to.left + to.width, to.top + to.height),
                paint,
            )
            bitmap.recycle()
        }

        val out = ByteArrayOutputStream()
        val ok = target.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        target.recycle()
        if (!ok) return null

        // fitted.sheet, not plan.sheet: the frame size the runtime cuts on has
        // to be the size the frames were actually drawn at.
        val order = fitted.cells.map { it.key }
        return ComposedSheet(
            sheet = fitted.sheet,
            bytes = out.toByteArray(),
            missing = missing.distinct().sortedBy { order.indexOf(it) },
        )
    }

    /** PNG ignores this, but the API demands it. */
    private const val PNG_QUALITY = 100
}
