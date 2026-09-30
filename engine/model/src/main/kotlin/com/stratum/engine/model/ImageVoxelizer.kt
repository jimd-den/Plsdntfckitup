package com.stratum.engine.model

import com.stratum.core.domain.micro.MicroModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** An image as the voxelizer reads it: ARGB pixels, row by row from the top. */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "an image of ${width}x$height needs ${width * height} pixels" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
}

/**
 * Pictures into microvoxel models: what an image model draws, standing in
 * the world.
 *
 * An image generator draws one view of a thing. Most of what makes it a
 * model can be read back from that view: which pixels are the thing (the
 * background is keyed out from the border inward), how thick it is (thickest
 * far from its outline, the way a pillow or a statue is), and its colours.
 * A second view from the side, or a depth map, pins the shape down
 * further. Each [Mode] is one of those readings:
 *
 * - [Mode.INFLATE]: one front view, puffed out from its silhouette -- a
 *   figure, a pot, a tree. The default, and the right one for most images.
 * - [Mode.EXTRUDE]: one front view cut out at an even thickness -- a sign,
 *   a shield, a door, a mural panel.
 * - [Mode.RELIEF]: a plaque whose surface stands out by brightness -- a
 *   carved panel, a map, a face in bas-relief.
 * - [Mode.HEIGHTMAP]: the picture laid flat, brightness as height -- a
 *   garden, a rocky patch, a model landscape.
 * - [Mode.TWO_VIEW]: a front and a side view carved into each other (the
 *   visual hull) -- the closest a pair of drawings gets to the real shape.
 * - [Mode.DEPTH]: a front view with a depth map from a depth model, for the
 *   front surface; the back mirrors it.
 *
 * Pure and deterministic: the same image gives the same model everywhere.
 */
object ImageVoxelizer {

    enum class Mode { INFLATE, EXTRUDE, RELIEF, HEIGHTMAP, TWO_VIEW, DEPTH }

    data class Options(
        val mode: Mode = Mode.INFLATE,
        /** The model's height in microvoxels (its width follows the image); 4 is one block. */
        val height: Int = 48,
        /** Deepest the model goes front to back, in microvoxels; 0 picks from its size. */
        val depth: Int = 0,
        /** How different from the border colour a pixel must be to count as the thing, 0..1. */
        val keyTolerance: Float = 0.18f,
        /** Keep the whole picture, background and all. */
        val keepBackground: Boolean = false,
        /** Colours to reduce to; the world snaps them to its paints in any case. */
        val colours: Int = 48,
        /** A hollow shell rather than solid through: lighter to place, the same from outside. */
        val hollow: Boolean = false,
    )

    fun voxelize(id: String, name: String, front: ArgbImage, options: Options = Options(), side: ArgbImage? = null, depthMap: ArgbImage? = null): MicroModel {
        val h = options.height.coerceIn(4, MicroModel.MAX_SIDE)
        return when (options.mode) {
            Mode.HEIGHTMAP -> heightmap(id, name, front, options)
            Mode.TWO_VIEW -> twoView(id, name, front, side ?: front, options)
            else -> standing(id, name, front, options, h, if (options.mode == Mode.DEPTH) depthMap else null)
        }
    }

    // ---- Standing models: image x is model x, image rows are model z (top row highest), depth is model y.

    private fun standing(id: String, name: String, image: ArgbImage, o: Options, height: Int, depthMap: ArgbImage?): MicroModel {
        val cropped = crop(image, o)
        val width = (cropped.width * height / cropped.height.toFloat()).roundToInt().coerceIn(1, MicroModel.MAX_SIDE)
        val img = resample(cropped, width, height)
        val mask = mask(img, o)
        val depth = if (o.depth > 0) o.depth.coerceIn(1, MicroModel.MAX_SIDE) else (min(width, height) * if (o.mode == Mode.EXTRUDE) 0.15f else 0.55f).roundToInt().coerceIn(2, MicroModel.MAX_SIDE)
        val dist = distance(mask, width, height)
        val far = dist.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val depthImg = depthMap?.let { resample(it, width, height) }
        val (palette, colourOf) = quantize(img, mask, o.colours)
        val sizeY = depth
        val cells = IntArray(width * sizeY * height)
        for (py in 0 until height) for (x in 0 until width) {
            val p = py * width + x
            if (!mask[p]) continue
            val z = height - 1 - py
            // Half-thickness at this pixel, front to back about the middle.
            val half: Float = when (o.mode) {
                Mode.EXTRUDE -> depth / 2f
                Mode.RELIEF -> luminance(img.pixels[p]) * depth / 2f + 1f
                Mode.DEPTH -> (depthImg?.let { luminance(it.pixels[p]) } ?: 0.5f) * depth / 2f + 0.5f
                else -> {
                    val t = (dist[p] / far).coerceIn(0f, 1f)
                    // A rounded profile: steep at the outline, flat across the middle.
                    sqrt(1f - (1f - t) * (1f - t)) * depth / 2f + 0.5f
                }
            }
            val mid = (sizeY - 1) / 2f
            // A relief is a plaque: flat behind, standing out in front.
            val (y0, y1) = if (o.mode == Mode.RELIEF) {
                val back = sizeY - 1
                (back - (half * 2).roundToInt().coerceAtLeast(1) + 1).coerceAtLeast(0) to back
            } else (mid - half + 0.5f).roundToInt().coerceAtLeast(0) to (mid + half - 0.5f).roundToInt().coerceAtMost(sizeY - 1)
            for (y in y0..max(y0, y1)) cells[(z * sizeY + y) * width + x] = colourOf[p]
        }
        val model = MicroModel(id, name, width, sizeY, height, palette, cells, source = "image")
        return if (o.hollow) hollowed(model) else model
    }

    private fun heightmap(id: String, name: String, image: ArgbImage, o: Options): MicroModel {
        val side = o.height.coerceIn(4, MicroModel.MAX_SIDE)
        val scale = side / max(image.width, image.height).toFloat()
        val w = (image.width * scale).roundToInt().coerceIn(1, MicroModel.MAX_SIDE)
        val d = (image.height * scale).roundToInt().coerceIn(1, MicroModel.MAX_SIDE)
        val img = resample(image, w, d)
        val mask = if (o.keepBackground) BooleanArray(w * d) { true } else mask(img, o)
        val tall = if (o.depth > 0) o.depth.coerceIn(1, MicroModel.MAX_SIDE) else max(4, side / 4)
        val (palette, colourOf) = quantize(img, mask, o.colours)
        val cells = IntArray(w * d * tall)
        for (py in 0 until d) for (x in 0 until w) {
            val p = py * w + x
            if (!mask[p]) continue
            val top = (luminance(img.pixels[p]) * (tall - 1)).roundToInt().coerceIn(0, tall - 1)
            val y = d - 1 - py
            for (z in 0..top) cells[(z * d + y) * w + x] = colourOf[p]
        }
        return MicroModel(id, name, w, d, tall, palette, cells, source = "image")
    }

    /** Front view carves x-z, side view carves y-z: only what both see stays. */
    private fun twoView(id: String, name: String, front: ArgbImage, side: ArgbImage, o: Options): MicroModel {
        val height = o.height.coerceIn(4, MicroModel.MAX_SIDE)
        val f = crop(front, o); val s = crop(side, o)
        val w = (f.width * height / f.height.toFloat()).roundToInt().coerceIn(1, MicroModel.MAX_SIDE)
        val d = (s.width * height / s.height.toFloat()).roundToInt().coerceIn(1, MicroModel.MAX_SIDE)
        val fi = resample(f, w, height); val si = resample(s, d, height)
        val fm = mask(fi, o); val sm = mask(si, o)
        val (palette, colourOf) = quantize(fi, fm, o.colours)
        val (sidePalette, sideColour) = quantize(si, sm, o.colours)
        val merged = (palette + sidePalette).distinct().take(MicroModel.MAX_PALETTE)
        val remapFront = IntArray(palette.size + 1) { if (it == 0) 0 else merged.indexOf(palette[it - 1]) + 1 }
        val remapSide = IntArray(sidePalette.size + 1) { if (it == 0) 0 else (merged.indexOf(sidePalette[it - 1]) + 1).coerceAtLeast(1) }
        val cells = IntArray(w * d * height)
        for (py in 0 until height) for (y in 0 until d) for (x in 0 until w) {
            val fp = py * w + x; val sp = py * d + y
            if (!fm[fp] || !sm[sp]) continue
            val z = height - 1 - py
            // Colour from whichever view faces this voxel more: the front near the front and back, the side near the sides.
            val towardSide = min(y, d - 1 - y) * w > min(x, w - 1 - x) * d
            cells[(z * d + y) * w + x] = if (towardSide) remapSide[sideColour[sp]] else remapFront[colourOf[fp]]
        }
        val model = MicroModel(id, name, w, d, height, merged, cells, source = "image").compacted()
        return if (o.hollow) hollowed(model) else model
    }

    // ---- Image work -------------------------------------------------------------------

    /** Trims the picture to what is not background, with a pixel of margin. */
    private fun crop(image: ArgbImage, o: Options): ArgbImage {
        if (o.keepBackground) return image
        val m = mask(image, o)
        var x0 = image.width; var y0 = image.height; var x1 = -1; var y1 = -1
        for (y in 0 until image.height) for (x in 0 until image.width) if (m[y * image.width + x]) {
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        if (x1 < 0) return image
        x0 = max(0, x0 - 1); y0 = max(0, y0 - 1); x1 = min(image.width - 1, x1 + 1); y1 = min(image.height - 1, y1 + 1)
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        return ArgbImage(w, h, IntArray(w * h) { image[x0 + it % w, y0 + it / w] })
    }

    /** Box-filtered to [w] x [h], averaging colour by opacity so an edge does not go dark. */
    fun resample(image: ArgbImage, w: Int, h: Int): ArgbImage {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx0 = x * image.width / w; val sx1 = max(sx0 + 1, (x + 1) * image.width / w)
            val sy0 = y * image.height / h; val sy1 = max(sy0 + 1, (y + 1) * image.height / h)
            var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (sy in sy0 until min(sy1, image.height)) for (sx in sx0 until min(sx1, image.width)) {
                val c = image[sx, sy]; val al = (c ushr 24) and 255
                a += al; r += ((c shr 16) and 255) * al.toLong(); g += ((c shr 8) and 255) * al.toLong(); b += (c and 255) * al.toLong(); n++
            }
            out[y * w + x] = if (a == 0L) 0 else {
                (((a / n).toInt() and 255) shl 24) or (((r / a).toInt() and 255) shl 16) or (((g / a).toInt() and 255) shl 8) or ((b / a).toInt() and 255)
            }
        }
        return ArgbImage(w, h, out)
    }

    /**
     * Which pixels are the thing: opaque, and not reached from the border
     * through pixels near the border's own colour. Flooding from the edge
     * rather than keying every pixel keeps a white shirt on a white ground.
     */
    fun mask(image: ArgbImage, o: Options): BooleanArray {
        val w = image.width; val h = image.height
        val out = BooleanArray(w * h) { ((image.pixels[it] ushr 24) and 255) >= 128 }
        if (o.keepBackground) return out
        val key = borderColour(image) ?: return out
        val tol = (o.keyTolerance.coerceIn(0f, 1f) * 441f).let { it * it }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h); var top = 0
        fun push(i: Int) { if (!seen[i]) { seen[i] = true; stack[top++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (top > 0) {
            val i = stack[--top]
            if (out[i] && colourDistance2(image.pixels[i], key) > tol) continue
            out[i] = false
            val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1); if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        return out
    }

    /** The picture's ground: the commonest opaque colour round its border, or null when the border is clear. */
    private fun borderColour(image: ArgbImage): Int? {
        val counts = HashMap<Int, Int>()
        fun see(c: Int) { if (((c ushr 24) and 255) >= 128) counts.merge(c and 0xF0F0F0, 1, Int::plus) }
        for (x in 0 until image.width) { see(image[x, 0]); see(image[x, image.height - 1]) }
        for (y in 0 until image.height) { see(image[0, y]); see(image[image.width - 1, y]) }
        val bucket = counts.maxByOrNull { it.value }?.key ?: return null
        // The bucket's average true colour.
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        fun add(c: Int) { if ((c and 0xF0F0F0) == bucket && ((c ushr 24) and 255) >= 128) { r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++ } }
        for (x in 0 until image.width) { add(image[x, 0]); add(image[x, image.height - 1]) }
        for (y in 0 until image.height) { add(image[0, y]); add(image[image.width - 1, y]) }
        return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Distance from each masked pixel to the nearest unmasked one (chamfer 3-4, in pixels). */
    private fun distance(mask: BooleanArray, w: Int, h: Int): FloatArray {
        val big = 1_000_000
        val d = IntArray(w * h) { if (mask[it]) big else 0 }
        fun at(x: Int, y: Int) = if (x < 0 || y < 0 || x >= w || y >= h) 0 else d[y * w + x]
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; if (d[i] == 0) continue
            d[i] = minOf(d[i], at(x - 1, y) + 3, at(x, y - 1) + 3, at(x - 1, y - 1) + 4, at(x + 1, y - 1) + 4)
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x; if (d[i] == 0) continue
            d[i] = minOf(d[i], at(x + 1, y) + 3, at(x, y + 1) + 3, at(x + 1, y + 1) + 4, at(x - 1, y + 1) + 4)
        }
        return FloatArray(w * h) { d[it] / 3f }
    }

    /**
     * The picture's colours reduced to at most [count]: the commonest
     * regions of colour space, averaged, and every pixel given its nearest.
     * Returns the palette (`#RRGGBB`) and each pixel's 1-based entry (0 off the mask).
     */
    private fun quantize(image: ArgbImage, mask: BooleanArray, count: Int): Pair<List<String>, IntArray> {
        val k = count.coerceIn(1, MicroModel.MAX_PALETTE)
        val sums = HashMap<Int, LongArray>()
        for (i in image.pixels.indices) {
            if (!mask[i]) continue
            val c = image.pixels[i]
            val key = ((c shr 20) and 15 shl 8) or ((c shr 12) and 15 shl 4) or ((c shr 4) and 15)
            val s = sums.getOrPut(key) { LongArray(4) }
            s[0] += ((c shr 16) and 255).toLong(); s[1] += ((c shr 8) and 255).toLong(); s[2] += (c and 255).toLong(); s[3]++
        }
        val centres = sums.values.sortedByDescending { it[3] }.take(k)
            .map { s -> (((s[0] / s[3]).toInt()) shl 16) or (((s[1] / s[3]).toInt()) shl 8) or (s[2] / s[3]).toInt() }
        val palette = centres.map { MicroModel.colour(it) }
        val out = IntArray(image.pixels.size)
        val memo = HashMap<Int, Int>()
        for (i in image.pixels.indices) {
            if (!mask[i]) continue
            val c = image.pixels[i] and 0xFFFFFF
            out[i] = memo.getOrPut(c) { centres.indices.minByOrNull { colourDistance2(c, centres[it]) }!! + 1 }
        }
        return palette to out
    }

    private fun luminance(argb: Int): Float =
        (0.2126f * ((argb shr 16) and 255) + 0.7152f * ((argb shr 8) and 255) + 0.0722f * (argb and 255)) / 255f

    private fun colourDistance2(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
        val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
        val db = (a and 255) - (b and 255)
        return (dr * dr + dg * dg + db * db).toFloat()
    }

    /** Keeps only cells with an empty neighbour: the shell a viewer sees. */
    fun hollowed(model: MicroModel): MicroModel {
        val out = model.cells.copyOf()
        for (z in 1 until model.sizeZ - 1) for (y in 1 until model.sizeY - 1) for (x in 1 until model.sizeX - 1) {
            val i = model.index(x, y, z)
            if (model.cells[i] == 0) continue
            val buried = model.cells[model.index(x - 1, y, z)] != 0 && model.cells[model.index(x + 1, y, z)] != 0 &&
                model.cells[model.index(x, y - 1, z)] != 0 && model.cells[model.index(x, y + 1, z)] != 0 &&
                model.cells[model.index(x, y, z - 1)] != 0 && model.cells[model.index(x, y, z + 1)] != 0
            if (buried) out[i] = 0
        }
        return model.withCells(out)
    }

}
