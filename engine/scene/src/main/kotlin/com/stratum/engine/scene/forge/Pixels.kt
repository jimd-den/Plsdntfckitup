package com.stratum.engine.scene.forge

import com.stratum.engine.scene.Texture
import kotlin.math.max
import kotlin.math.min

/**
 * The clean-up a generated image needs before a world can use it.
 *
 * Image models are very good at the painting and indifferent to the things a
 * game needs: they ignore requested sizes, their "seamless" textures have
 * seams, and nothing they return has an alpha channel. All of that is fixed
 * here, deterministically, on plain pixel arrays — no platform bitmap, so the
 * phone and the build machine clean up identically.
 */
object Pixels {

    /** Area-averaged downscale. Never upscales. */
    fun downscale(src: Texture, maxSize: Int): Texture {
        val scale = min(1f, maxSize.toFloat() / max(src.width, src.height))
        if (scale >= 1f) return src
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = y * src.height / h
            val y1 = max(y0 + 1, (y + 1) * src.height / h)
            for (x in 0 until w) {
                val x0 = x * src.width / w
                val x1 = max(x0 + 1, (x + 1) * src.width / w)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L
                for (sy in y0 until y1) for (sx in x0 until x1) {
                    val c = src.argb[sy * src.width + sx]
                    // Colour weighted by alpha, so a cut-out edge does not
                    // average in the colour of the background it was cut from.
                    val ca = (c ushr 24) and 0xFF
                    a += ca
                    r += ((c shr 16) and 0xFF) * ca
                    g += ((c shr 8) and 0xFF) * ca
                    b += (c and 0xFF) * ca
                }
                val n = (x1 - x0) * (y1 - y0)
                out[y * w + x] = if (a == 0L) 0 else {
                    ((a / n).toInt() shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
                }
            }
        }
        return Texture(w, h, out)
    }

    /** Crops to the largest centred square: tiles are square, models are not always. */
    fun square(src: Texture): Texture {
        val size = min(src.width, src.height)
        val ox = (src.width - size) / 2
        val oy = (src.height - size) / 2
        val out = IntArray(size * size)
        for (y in 0 until size) System.arraycopy(src.argb, (y + oy) * src.width + ox, out, y * size, size)
        return Texture(size, size, out)
    }

    /**
     * Makes a texture wrap without a visible seam.
     *
     * The classic offset-and-blend: take a copy shifted by half the size in
     * both directions — whose edges are, by construction, continuous when
     * tiled — and blend the original over it everywhere except near the edges.
     * The seam is not removed so much as moved into the middle of the tile and
     * dissolved there, which on painterly ground is invisible.
     */
    fun seamless(src: Texture): Texture {
        require(src.height == src.width) { "seamless expects a square texture" }
        // One axis at a time. Doing both at once leaves the shifted copy's own
        // seam showing along the middle of each edge, where the blend weights
        // for the two axes disagree; two passes each hide their seam exactly
        // where their own weight is one.
        return seamlessAxis(seamlessAxis(src, horizontal = true), horizontal = false)
    }

    private fun seamlessAxis(src: Texture, horizontal: Boolean): Texture {
        val n = src.width
        val half = n / 2
        val out = IntArray(n * n)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val along = if (horizontal) x else y
                val m = smoothstep(0.1f, 0.7f, min(along, n - 1 - along).toFloat() / half)
                val a = src.argb[y * n + x]
                val b = if (horizontal) src.argb[y * n + (x + half) % n] else src.argb[((y + half) % n) * n + x]
                out[y * n + x] = mix(b, a, m) or (0xFF shl 24)
            }
        }
        return Texture(n, n, out)
    }

    /**
     * Cuts an object out of the flat key colour it was drawn on.
     *
     * Only key-coloured pixels *connected to the border* are removed, by flood
     * fill. A plain colour threshold also removes every purple flower and every
     * magenta gem on the object itself; the object is, by definition, the part
     * the background does not reach.
     *
     * The edge is then softened by how magenta each boundary pixel still is,
     * and de-spilled, because the fringe of a painted object on magenta is
     * itself slightly magenta and reads as a pink halo in the world.
     */
    fun keyOut(src: Texture): Texture {
        val w = src.width; val h = src.height
        val px = src.argb
        val keyness = FloatArray(w * h) { i -> keyness(px[i]) }
        val background = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0; var tail = 0
        fun seed(i: Int) {
            if (!background[i] && keyness[i] > SEED_KEYNESS) { background[i] = true; queue[tail++] = i }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            if (x > 0) grow(i - 1, keyness, background, queue, tail).also { tail = it }
            if (x < w - 1) grow(i + 1, keyness, background, queue, tail).also { tail = it }
            if (y > 0) grow(i - w, keyness, background, queue, tail).also { tail = it }
            if (y < h - 1) grow(i + w, keyness, background, queue, tail).also { tail = it }
        }

        val out = IntArray(w * h)
        // Pure key colour enclosed by the object — between leaves, inside a
        // brazier's grille — is background the fill could not reach. Real art
        // is almost never this exact colour, so a strict threshold takes it
        // without taking the object's own purples.
        for (i in 0 until w * h) if (keyness[i] > ENCLOSED_KEYNESS) background[i] = true
        for (i in 0 until w * h) {
            if (background[i]) { out[i] = 0; continue }
            // A kept pixel next to the background is an edge: soften and de-spill it.
            val x = i % w; val y = i / w
            val edge = (x > 0 && background[i - 1]) || (x < w - 1 && background[i + 1]) ||
                (y > 0 && background[i - w]) || (y < h - 1 && background[i + w])
            val c = px[i]
            if (!edge) { out[i] = c or (0xFF shl 24); continue }
            val k = keyness[i]
            val alpha = (1f - smoothstep(0.15f, 0.5f, k)).coerceIn(0.35f, 1f)
            var r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; var b = c and 0xFF
            val spill = min(r, b) - g
            if (spill > 0) { r -= (spill * 0.8f).toInt(); b -= (spill * 0.8f).toInt() }
            out[i] = ((alpha * 255).toInt() shl 24) or (r.coerceIn(0, 255) shl 16) or (g shl 8) or b.coerceIn(0, 255)
        }
        return Texture(w, h, out)
    }

    private fun grow(i: Int, keyness: FloatArray, background: BooleanArray, queue: IntArray, tail: Int): Int {
        if (background[i] || keyness[i] <= GROW_KEYNESS) return tail
        background[i] = true
        queue[tail] = i
        return tail + 1
    }

    /**
     * Crops to the opaque content, with a little margin, keeping the bottom
     * edge on the object's base so a sprite stands on the ground it is placed on.
     */
    fun trim(src: Texture, margin: Float = 0.03f): Texture? {
        var minX = src.width; var minY = src.height; var maxX = -1; var maxY = -1
        for (y in 0 until src.height) for (x in 0 until src.width) {
            if (((src.argb[y * src.width + x] ushr 24) and 0xFF) > 24) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        val pad = ((maxX - minX + maxY - minY) / 2f * margin).toInt()
        minX = max(0, minX - pad); maxX = min(src.width - 1, maxX + pad); minY = max(0, minY - pad)
        val w = maxX - minX + 1; val h = maxY - minY + 1
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(src.argb, (y + minY) * src.width + minX, out, y * w, w)
        return Texture(w, h, out)
    }

    /**
     * Why an image is unusable, or null if it is fine.
     *
     * Image models occasionally answer a texture request with a flat screen of
     * chroma green or key magenta — a stand-in, not a painting. Shipping that
     * paints the whole cliff face of a world luminous green, so it is caught
     * here and the order retried rather than trusted.
     */
    fun defect(src: Texture): String? {
        var screen = 0
        var sum = 0.0
        var sumSq = 0.0
        val step = max(1, src.argb.size / 4096)
        var samples = 0
        var i = 0
        while (i < src.argb.size) {
            val c = src.argb[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (g > 170 && r < 90 && b < 90) screen++
            if (keyness(c) > ENCLOSED_KEYNESS) screen++
            val l = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            sum += l; sumSq += l * l
            samples++
            i += step
        }
        val mean = sum / samples
        val variance = sumSq / samples - mean * mean
        return when {
            screen > samples * SCREEN_SHARE -> "mostly a flat key colour"
            variance < MIN_VARIANCE -> "no visible detail"
            else -> null
        }
    }

    /**
     * Why a cut-out sprite is unusable, or null.
     *
     * After keying, a real object covers a good share of its trimmed box and
     * is not itself key-coloured. A sprite that is nearly all transparent is a
     * failed cut; one that is nearly all opaque came back without a key
     * background, and would stand in the world as a painted rectangle.
     */
    fun spriteDefect(sprite: Texture): String? {
        val opaque = sprite.argb.count { ((it ushr 24) and 0xFF) > 128 }
        val share = opaque.toFloat() / sprite.argb.size
        return when {
            share < MIN_SPRITE_COVER -> "almost nothing left after keying"
            share > MAX_SPRITE_COVER -> "no key background to cut the object from"
            defect(sprite)?.startsWith("mostly") == true -> "the object itself is key-coloured"
            else -> null
        }
    }

    /** How magenta a pixel is: high red and blue, low green. 0..1. */
    fun keyness(c: Int): Float {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val purity = (min(r, b) - g) / 255f
        val balance = 1f - kotlin.math.abs(r - b) / 255f
        return (purity * balance).coerceIn(0f, 1f)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t)).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val SEED_KEYNESS = 0.45f
    private const val GROW_KEYNESS = 0.3f
    private const val ENCLOSED_KEYNESS = 0.62f
    private const val SCREEN_SHARE = 0.5f
    private const val MIN_SPRITE_COVER = 0.08f
    private const val MAX_SPRITE_COVER = 0.97f
    private const val MIN_VARIANCE = 0.0008
}
