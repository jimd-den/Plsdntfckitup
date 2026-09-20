package com.stratum.core.data.sprite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.stratum.core.domain.sprite.GridOutcome
import com.stratum.core.domain.sprite.GridVerdict
import com.stratum.core.domain.sprite.KeyResult
import com.stratum.core.domain.sprite.KeyStrategy
import com.stratum.core.domain.sprite.SheetGrid
import com.stratum.core.domain.sprite.SheetInspection
import com.stratum.core.domain.sprite.SpriteKeying
import com.stratum.core.domain.sprite.SpriteSheet
import java.io.ByteArrayOutputStream

/** A generated sheet after the background is gone and the grid has been checked. */
data class PreparedSheet(
    val sheet: SpriteSheet,
    val bytes: ByteArray,
    val keyStrategy: KeyStrategy,
    val clearedPixels: Int,
    val grid: GridVerdict,
    /** Almost nothing is drawn on it, so the world would show no character. */
    val looksEmpty: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PreparedSheet &&
                sheet == other.sheet &&
                keyStrategy == other.keyStrategy &&
                clearedPixels == other.clearedPixels &&
                grid == other.grid &&
                bytes.contentEquals(other.bytes))

    override fun hashCode(): Int {
        var result = sheet.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + keyStrategy.hashCode()
        result = 31 * result + clearedPixels
        result = 31 * result + grid.hashCode()
        return result
    }
}

/**
 * Everything that has to happen to a generated image before it is a sprite
 * sheet: key out the background, then check it is laid out the way it was asked
 * for, and cut it on what is actually there if it is not.
 *
 * The order matters. Keying has to come first because the grid is read from
 * where the content *is not*, and a sheet still wearing its background has no
 * empty seams to find. Both decisions live in the domain; this decodes once,
 * hands the pixels over twice, and re-encodes at the end.
 */
object GeneratedSheetPreparer {

    fun prepare(sheet: SpriteSheet, bytes: ByteArray): PreparedSheet {
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions())
        }.getOrNull() ?: return unchanged(sheet, bytes)

        val width = decoded.width
        val height = decoded.height
        if (width <= 0 || height <= 0) {
            decoded.recycle()
            return unchanged(sheet, bytes)
        }

        val pixels = IntArray(width * height)
        decoded.getPixels(pixels, 0, width, 0, 0, width, height)
        decoded.recycle()

        val keyed = SpriteKeying.key(pixels, width, height)
        val verdict = SheetInspection.inspect(
            pixels = keyed.pixels,
            width = width,
            height = height,
            requested = SheetGrid(sheet.columns, sheet.rows),
        )

        val cut = when (verdict.outcome) {
            GridOutcome.AS_ASKED, GridOutcome.UNVERIFIED -> sheet
            GridOutcome.RECUT, GridOutcome.SINGLE_FRAME ->
                sheet.recut(verdict.grid, width, height)
        }

        // Second pass, now that the grid is known. A colour found framing every
        // cell is the canvas rather than anything the character wears, and can
        // be cleared wherever it appears — including the pockets a flood from
        // the outside can never reach. Run from the original pixels, not the
        // first pass's, so the two are genuinely alternatives rather than one
        // built on the other.
        val best = keyed.betterOf(
            SpriteKeying.key(pixels, width, height, cells = verdict.grid),
        )

        val empty = looksEmpty(best.pixels)

        // Nothing was cleared: re-encoding would only re-compress the same
        // pixels, so the original bytes are kept.
        if (!best.cleared) {
            return PreparedSheet(cut, bytes, best.strategy, 0, verdict, empty)
        }

        val encoded = encode(best.pixels, width, height)
            ?: return PreparedSheet(cut, bytes, KeyStrategy.NONE, 0, verdict, empty)

        return PreparedSheet(cut, encoded, best.strategy, best.clearedPixels, verdict, empty)
    }

    /**
     * Whether there is any sprite left to draw.
     *
     * Keying refuses to hand back a sheet it emptied, so this is the other
     * case: a model that returned a blank or near-blank image. Either way the
     * world would draw the fallback shape and say nothing, which is the one
     * outcome a player cannot diagnose.
     */
    private fun looksEmpty(pixels: IntArray): Boolean {
        var opaque = 0
        val enough = (pixels.size * MIN_DRAWN).toInt()
        for (pixel in pixels) {
            if ((pixel ushr 24) and 0xFF > 0) {
                opaque++
                if (opaque > enough) return false
            }
        }
        return true
    }

    /**
     * The pass that removed more backdrop.
     *
     * The grid-aware pass usually wins, but not always: when no colour frames
     * every cell it falls back to the same flood as the first pass and ties, and
     * a sheet cut as a single still has no cells to reason about at all. Taking
     * the larger result means the extra knowledge can only help.
     */
    private fun KeyResult.betterOf(other: KeyResult): KeyResult =
        if (other.clearedPixels > clearedPixels) other else this

    private fun encode(pixels: IntArray, width: Int, height: Int): ByteArray? {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArrayOutputStream()
        // A failed re-encode leaves the original rather than a blank file: a
        // sheet with a background is worse art, a sheet of nothing is a bug.
        val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return if (ok) out.toByteArray() else null
    }

    private fun unchanged(sheet: SpriteSheet, bytes: ByteArray) = PreparedSheet(
        sheet = sheet,
        bytes = bytes,
        keyStrategy = KeyStrategy.NONE,
        clearedPixels = 0,
        grid = GridVerdict(
            SheetGrid(sheet.columns, sheet.rows),
            GridOutcome.UNVERIFIED,
            0f,
        ),
    )

    /** Below this share of the sheet drawn on, there is no character to see. */
    private const val MIN_DRAWN = 0.005f

    private fun decodeOptions() = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        // Some providers return a pre-multiplied image; reading raw pixels back
        // needs it un-multiplied or every keyed edge darkens.
        inPremultiplied = false
        inMutable = false
    }
}
