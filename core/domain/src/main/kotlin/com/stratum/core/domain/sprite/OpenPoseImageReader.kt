package com.stratum.core.domain.sprite

import kotlin.math.abs

/**
 * Recovers keypoints from a rendered OpenPose skeleton.
 *
 * This exists because of what pose libraries actually ship. The format is
 * nominally JSON, but what a person downloads is a PNG — the skeleton already
 * drawn, ready to drop into a ControlNet preprocessor. That file is perfectly
 * usable as a guide exactly as it is, and for generation nothing more is
 * needed.
 *
 * It is not enough for the weapon, which needs to know where the hand *is*. So
 * the picture is read back: OpenPose draws each joint as an opaque disc in one
 * of eighteen fixed palette colours, and finding the discs gives the keypoints
 * back.
 *
 * Best-effort, and honest about it. Limbs are drawn in the same palette, so a
 * renderer that draws them opaque rather than blended puts a long streak of a
 * joint's colour across the image. The largest *compact* blob is taken rather
 * than the centroid of everything matching, which distinguishes a disc from a
 * limb — but a pose whose arm lies exactly along its own colour can still be
 * misread, which is why the result carries a confidence and the caller is
 * expected to look at it.
 *
 * The larger limitation is the palette itself, and it was measured rather than
 * guessed: a real pose library draws its skeletons in Material blues and pinks,
 * not the canonical eighteen, so nothing here matches and this returns null.
 * That is the right failure — the image is still a perfectly good guide for the
 * drawing, and only the weapon anchor is lost — but it means this is the
 * fallback route and not the main one. Where a library publishes its keypoints,
 * [OpenPoseJson] reads them exactly, and exact beats inferred every time.
 */
object OpenPoseImageReader {

    /**
     * @param tolerance how far a pixel may be from a palette colour and still
     *   count. The palette steps 85 apart per channel, so anything below about
     *   40 cannot confuse two entries; the default leaves room for PNG
     *   quantisation without that risk.
     */
    fun read(
        pixels: IntArray,
        width: Int,
        height: Int,
        tolerance: Int = DEFAULT_TOLERANCE,
    ): OpenPoseBody? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null

        val keypoints = HashMap<OpenPoseJoint, OpenPoseKeypoint>()
        OpenPoseLayout.BODY_18.order.forEachIndexed { index, joint ->
            // BODY_18 has no empty slots, so this cannot happen; the type says
            // it might because whole-body layouts carry face and finger
            // positions this model does not name.
            if (joint == null) return@forEachIndexed
            val color = OpenPoseStyle.palette[index]
            val found = discOf(pixels, width, height, color, tolerance)
            keypoints[joint] = found ?: OpenPoseKeypoint(0f, 0f, 0f)
        }

        val body = OpenPoseBody(keypoints)
        // A handful of stray matches is not a skeleton. Requiring a torso's
        // worth of joints is what stops a photograph with a red jumper in it
        // being read as a person standing in the sleeve.
        return if (body.presentCount >= MIN_JOINTS) body else null
    }

    /**
     * The most disc-like blob of one colour.
     *
     * Compactness — how much of its own bounding box a blob fills — is what
     * separates a drawn joint from a drawn limb. A disc fills about three
     * quarters of its box; a line across the image fills almost none of one.
     */
    private fun discOf(
        pixels: IntArray,
        width: Int,
        height: Int,
        color: Int,
        tolerance: Int,
    ): OpenPoseKeypoint? {
        val matched = BooleanArray(width * height)
        var any = false
        for (i in matched.indices) {
            if (near(pixels[i], color, tolerance)) {
                matched[i] = true
                any = true
            }
        }
        if (!any) return null

        var best: Blob? = null
        val seen = BooleanArray(matched.size)
        val stack = ArrayDeque<Int>()
        for (start in matched.indices) {
            if (!matched[start] || seen[start]) continue
            var count = 0
            var sumX = 0L
            var sumY = 0L
            var minX = width
            var maxX = -1
            var minY = height
            var maxY = -1

            seen[start] = true
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val at = stack.removeLast()
                val x = at % width
                val y = at / width
                count++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0) push(stack, seen, matched, at - 1)
                if (x < width - 1) push(stack, seen, matched, at + 1)
                if (y > 0) push(stack, seen, matched, at - width)
                if (y < height - 1) push(stack, seen, matched, at + width)
            }

            val boxArea = (maxX - minX + 1).toLong() * (maxY - minY + 1)
            val compactness = if (boxArea <= 0) 0f else count.toFloat() / boxArea
            val blob = Blob(
                x = sumX.toFloat() / count,
                y = sumY.toFloat() / count,
                size = count,
                compactness = compactness,
            )
            // Bigger wins among blobs that are disc-shaped at all, because the
            // joint disc is drawn last and over everything else.
            if (blob.compactness >= MIN_COMPACTNESS &&
                (best == null || blob.size > best.size)
            ) {
                best = blob
            }
        }

        val blob = best ?: return null
        if (blob.size < MIN_BLOB_PIXELS) return null
        return OpenPoseKeypoint(
            x = blob.x / width,
            y = blob.y / height,
            confidence = blob.compactness.coerceIn(0f, 1f),
        )
    }

    private fun push(stack: ArrayDeque<Int>, seen: BooleanArray, matched: BooleanArray, at: Int) {
        if (!seen[at] && matched[at]) {
            seen[at] = true
            stack.addLast(at)
        }
    }

    private fun near(pixel: Int, color: Int, tolerance: Int): Boolean {
        // A transparent pixel is background whatever colour it claims to be.
        if ((pixel ushr 24) and 0xFF < OPAQUE_ENOUGH) return false
        return abs(((pixel ushr 16) and 0xFF) - ((color ushr 16) and 0xFF)) <= tolerance &&
            abs(((pixel ushr 8) and 0xFF) - ((color ushr 8) and 0xFF)) <= tolerance &&
            abs((pixel and 0xFF) - (color and 0xFF)) <= tolerance
    }

    private data class Blob(val x: Float, val y: Float, val size: Int, val compactness: Float)

    private const val DEFAULT_TOLERANCE = 30
    private const val OPAQUE_ENOUGH = 128

    /** A disc fills most of its bounding box; a limb fills almost none of one. */
    private const val MIN_COMPACTNESS = 0.45f

    /** Below this it is a compression artefact, not a drawn joint. */
    private const val MIN_BLOB_PIXELS = 6

    /** Fewer than a torso's worth of joints is not a person. */
    private const val MIN_JOINTS = 8
}
