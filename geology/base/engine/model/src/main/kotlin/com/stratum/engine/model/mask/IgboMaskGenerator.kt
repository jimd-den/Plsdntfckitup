package com.stratum.engine.model.mask

import com.stratum.core.domain.micro.MicroModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Carves an Igbo masquerade mask from a [MaskGenome]: a standing relief about
 * 32 to 72 microvoxels tall, facing -Y, with a flat back so it hangs on a
 * wall or stands on its stepped plinth.
 *
 * ## How it is built
 *
 * A mask is designed the way a poster is: flat in the front view, with depth
 * added as relief. Every part -- the face, the brows, the lids, the crest --
 * is a smooth analytic shape (an ellipse, a capsule, a ring, a Bezier tube)
 * measured by its signed distance in "face units", where the face runs from
 * chin at -1 to hairline at +1. Each part says, for a point of the front view,
 * whether it covers it, how far it stands out from the back board, and which
 * palette slot it paints.
 *
 * The voxel grid then samples that design once per column, at the column's
 * centre. Because the shapes are analytic, their outlines land on the grid as
 * clean curves and crisp steps at any size -- no blobs, no noise, no
 * stair-stepped marching of a noisy field. A column is one colour front to
 * back (the last voxel is the board's bare [MaskPalette.back]), so the side of
 * a raised brow shows the brow's colour: flat colour fields, like a fashion
 * plate, rather than shading painted in.
 *
 * ## Why these rules
 *
 * - Symmetry is exact: when the genome is symmetric every part is evaluated at
 *   |x|, and the grid is laid symmetric about the centre, so the left half is
 *   the mirror of the right cell for cell.
 * - Fine lines are sized in voxels, not face units, so an uli line is always
 *   at least one voxel wide and never breaks into dots on a small mask.
 * - The palette has seven slots and a model at most seven colours: the
 *   restraint is the style.
 *
 * Pure and deterministic: the same genome always carves the same model.
 */
object IgboMaskGenerator {

    /** Palette slots, 1-based as model cells are. */
    private const val FACE = 1
    private const val INK = 2
    private const val CREST = 3
    private const val SECOND = 4
    private const val ACCENT = 5
    private const val IVORY = 6
    private const val BACK = 7

    /** The mask as a model. [id] defaults to one derived from the genome, so the same mask keeps the same id. */
    fun generate(genome: MaskGenome, id: String = defaultId(genome)): MicroModel {
        val g = genome.normalised()
        val palette = MaskPalettes[g.palette]
        // A first pass at a nominal scale finds the silhouette's bounds; lines
        // only repaint inside it, so the bounds do not depend on the scale.
        val probe = Design(g, palette, pixel = 0.06f)
        var vMin = Float.MAX_VALUE; var vMax = -Float.MAX_VALUE; var uMax = 0f
        val step = 0.02f
        var v = -3f
        while (v <= 5f) {
            var u = 0f
            while (u <= 4f) {
                if (probe.covers(u, v) || probe.covers(-u, v)) {
                    vMin = min(vMin, v); vMax = max(vMax, v); uMax = max(uMax, u)
                }
                u += step
            }
            v += step
        }
        vMin -= step / 2; vMax += step / 2; uMax += step / 2
        var scale = g.height / (vMax - vMin)
        if (2 * uMax * scale + 2 > MicroModel.MAX_SIDE) scale = (MicroModel.MAX_SIDE - 2) / (2 * uMax)
        val sizeZ = ceil((vMax - vMin) * scale).toInt().coerceIn(1, MicroModel.MAX_SIDE)
        val sizeX = (ceil(2 * uMax * scale).toInt() + 2).coerceIn(1, MicroModel.MAX_SIDE)
        val design = Design(g, palette, pixel = 1f / scale)

        // Sample every column once: its depth in voxels and its slot.
        val depth = IntArray(sizeX * sizeZ)
        val slot = IntArray(sizeX * sizeZ)
        val cell = Cell()
        for (z in 0 until sizeZ) for (x in 0 until sizeX) {
            val u = (x + 0.5f - sizeX / 2f) / scale
            val vv = vMin + (z + 0.5f) / scale
            design.sample(u, vv, cell)
            if (cell.h <= 0f) continue
            depth[z * sizeX + x] = max(1, (cell.h * scale).roundToInt())
            slot[z * sizeX + x] = cell.slot
        }
        val sizeY = (depth.maxOrNull() ?: 1).coerceIn(1, MicroModel.MAX_SIDE)
        val cells = IntArray(sizeX * sizeY * sizeZ)
        for (z in 0 until sizeZ) for (x in 0 until sizeX) {
            val n = depth[z * sizeX + x]
            if (n == 0) continue
            val s = slot[z * sizeX + x]
            for (k in 0 until n) {
                val y = sizeY - 1 - k
                // The board's bare back, where the column is deep enough to have one.
                cells[(z * sizeY + y) * sizeX + x] = if (k == 0 && n >= 3) BACK else s
            }
        }
        // Slots that share a colour share one palette entry.
        val colours = palette.colours()
        val distinct = colours.distinct()
        val remap = IntArray(colours.size + 1) { if (it == 0) 0 else distinct.indexOf(colours[it - 1]) + 1 }
        for (i in cells.indices) cells[i] = remap[cells[i]]
        return MicroModel(
            id = id, name = g.name, sizeX = sizeX, sizeY = sizeY, sizeZ = sizeZ,
            palette = distinct, cells = cells, source = "generator",
            tags = listOf("mask", g.tradition.name.lowercase(), MaskCodec.TAG_PREFIX + MaskCodec.encode(g)),
        ).compacted()
    }

    /** A stable id for a genome: the same dials give the same id, so keeping it twice overwrites rather than duplicates. */
    fun defaultId(genome: MaskGenome): String = "mask-" + (MaskCodec.encode(genome.normalised()).hashCode().toLong() and 0xFFFFFFFFL).toString(36)

    /** The height (in face units from the back board) and slot of one front-view point. */
    internal class Cell { var h = 0f; var slot = 0 }

    /**
     * The mask's front view as a function of a point. Built once per genome
     * (the curves and proportions are precomputed), then sampled per column.
     *
     * Points are in face units: u right, v up, the face spanning v -1..1.
     * [pixel] is one voxel in face units, the floor for every line's width.
     */
    internal class Design(private val g: MaskGenome, private val p: MaskPalette, private val pixel: Float) {

        // ---- Proportions -------------------------------------------------
        /** Half the face's width at its widest. */
        private val w = 0.5f + 0.32f * g.width * (if (g.face == FaceShape.LONG) 0.85f else 1f) * (if (g.face == FaceShape.ROUND) 1.12f else 1f)

        /** Feature heights, all derived from the eye line so they move together. */
        private val ey = 0.1f + 0.18f * (g.features - 0.5f)
        private val ex = 0.42f * w
        private val noseBottom = ey - 0.44f
        private val my = ey - 0.72f

        /** Relief multiplier: how far features stand from the face. */
        private val k = 0.6f + 0.8f * g.relief
        private val rim = 0.14f
        private val bulge = 0.2f + 0.14f * k

        /** Lines are at least a voxel and a bit wide, so they survive the grid. */
        private val line = max(pixel * 1.15f, 0.03f)

        /** True when the mask is big enough for small ornaments to read as shapes rather than specks. */
        private val fine = pixel < 0.05f

        /** When the line colour would vanish against a field, the next best contrasting slot. */
        private val lineOnCrest = if (p.ink != p.crest) INK else if (p.second != p.crest) SECOND else ACCENT
        private val discField = if (p.second != p.face) SECOND else CREST
        private val cheekColour = if (p.accent != p.face) ACCENT else INK
        private val lipColour = if (p.accent != p.face) ACCENT else if (p.second != p.face) SECOND else INK
        private val earFill = if (p.second != p.face) SECOND else CREST
        /** The plinth is the darkest mass on hand, so the mask sits on a solid base whatever the scheme. */
        private val baseSlot = listOf(CREST to p.crest, INK to p.ink, FACE to p.face).minBy { p.luminance(it.second) }.first
        private val beardStripe = if (p.second != p.crest) SECOND else ACCENT

        // ---- Crest geometry ---------------------------------------------
        private val ch = g.crestHeight
        private val capCy = 0.55f
        private val capRx = w * 1.04f
        private val capRy = 0.62f

        /** Horns: a cubic Bezier from the temple, out, up and curling back in -- a lyre. */
        private val horn = Tube(
            floatArrayOf(w * 0.5f, 0.7f, w * 1.45f + 0.2f, 0.66f, w * 1.3f + 0.3f, 1.15f + 0.45f * ch, w * 0.85f + 0.12f, 1.3f + 0.7f * ch),
            r0 = 0.17f, r1 = 0.055f,
        )

        /** Tusks: from the corners of the mouth, down and sweeping out and up. */
        private val tusk = Tube(
            floatArrayOf(0.14f, my + 0.02f, 0.3f, my - 0.5f, 0.62f, my - 0.55f, 0.8f, my - 0.18f),
            r0 = 0.11f, r1 = 0.045f,
        )

        /** The face's own outline, top to bottom: half its width at height v. */
        fun halfWidth(v: Float): Float {
            if (v <= -1f || v >= 1f) return 0f
            val a = abs(v)
            return when (g.face) {
                FaceShape.OVAL -> w * sqrt(1 - v * v) * (1f + 0.07f * v)
                FaceShape.ROUND -> w * sqrt(1 - v * v).pow(0.8f)
                FaceShape.LONG -> w * (1 - a.pow(2.6f)).pow(1 / 2.6f)
                FaceShape.SQUARE_JAW -> if (v >= 0f) w * sqrt(1 - v * v) else w * (1 - a.pow(4f)).pow(0.25f) * (1f - 0.08f * a)
                FaceShape.HEART -> if (v >= 0.15f) {
                    val t = (v - 0.15f) / 0.85f
                    w * 1.04f * sqrt(1 - t * t)
                } else {
                    val t = (0.15f - v) / 1.15f
                    w * 1.04f * (1 - t.pow(1.7f)).coerceAtLeast(0f).pow(0.8f)
                }
            }
        }

        /** The face's dome: rim at the edge, fullest at the cheekbones and forehead. */
        private fun faceHeight(x: Float, v: Float, hw: Float): Float {
            // A broad flat plane rolled over at the edges, not a dome: the
            // features then stand on one clean level, and the voxel grid has
            // no terraces to stair-step across the cheeks.
            val edge = (hw - x) / 0.22f
            val across = smooth(edge.coerceIn(0f, 1f))
            val down = smooth(((1f - abs(v)) / 0.3f).coerceIn(0f, 1f))
            return rim + bulge * across * (0.55f + 0.45f * down)
        }

        /** True where the mask has anything at all: the silhouette. */
        fun covers(u: Float, v: Float): Boolean {
            val c = Cell(); sample(u, v, c); return c.h > 0f
        }

        fun sample(u: Float, v: Float, c: Cell) {
            c.h = 0f; c.slot = 0
            // Everything bilateral is drawn at |u|; only the deliberately
            // one-sided ornaments look at the sign.
            val x = abs(u)
            val left = u < 0f
            val oneSided = !g.symmetric

            // ---- Behind the head -----------------------------------------
            when (g.crest) {
                CrestForm.DISC -> disc(x, v, c)
                CrestForm.PLUMES -> plumes(x, v, c)
                else -> Unit
            }
            when (g.ears) {
                EarForm.ELEPHANT -> elephantEar(x, v, c)
                EarForm.SMALL -> smallEar(x, v, c)
                EarForm.NONE -> Unit
            }
            if (g.crest == CrestForm.COMBS) combs(x, v, c)
            if (g.crest == CrestForm.TIERS) tiers(x, v, c)
            if (g.crest == CrestForm.HORNS) horns(x, v, c)

            // ---- Neck and plinth: a stepped Deco base the mask stands on --
            plinth(x, v, c)

            // ---- The face -------------------------------------------------
            val hw = halfWidth(v)
            val inFace = x < hw
            if (inFace) set(c, faceHeight(x, v, hw), FACE)
            // A disc the same colour as the hair would swallow it; there the bare
            // oval of the head against the disc reads better.
            if (g.crest != CrestForm.NONE && !(g.crest == CrestForm.DISC && discField == CREST)) hairCap(x, v, c)

            if (inFace) {
                brows(x, v, c)
                eyes(x, v, left, c)
                nose(x, v, c)
                mouth(x, v, c)
                if (g.ichi > 0) ichi(u, v, c)
                if (g.cheekMarks > 0 && (!oneSided || !left)) cheekMarks(x, v, c)
                uli(x, v, if (oneSided) left else true, c)
            }
            if (g.tusks) tusks(x, v, c)
            when (g.beard) {
                BeardForm.STRIPED -> stripedBeard(u, x, v, c)
                BeardForm.POINTED -> pointedBeard(x, v, c)
                BeardForm.NONE -> Unit
            }
        }

        // ---- Composition verbs ------------------------------------------

        /** Covers the point outright, whatever was there. */
        private fun set(c: Cell, h: Float, slot: Int) { c.h = h; c.slot = slot }

        /** Stands on top if it is higher than what is there. */
        private fun raise(c: Cell, h: Float, slot: Int) { if (h > c.h) { c.h = h; c.slot = slot } }

        /** Adds height over what is there (something must be), in its own colour. */
        private fun lift(c: Cell, dh: Float, slot: Int) { if (c.h > 0f) { c.h += dh; c.slot = slot } }

        /** Repaints what is there without changing its height. */
        private fun paint(c: Cell, slot: Int) { if (c.h > 0f) c.slot = slot }

        /** Cuts down into what is there, never through the board. */
        private fun carve(c: Cell, dh: Float, slot: Int) { if (c.h > 0f) { c.h = max(0.06f, c.h - dh); c.slot = slot } }

        // ---- Crests -----------------------------------------------------

        /**
         * The maiden's coiffure: crested combs fanning over the skull, each
         * outlined inside by a fine accent line, with a mirror-disc at its
         * root -- the real headdresses are hung with mirrors and combs.
         */
        private fun combs(x: Float, v: Float, c: Cell) {
            val n = g.crestCount.coerceIn(1, 7)
            val spread = if (n <= 1) 0f else min(0.55f, 1.25f / (n - 1))
            val baseLen = 0.22f + 0.45f * ch
            for (i in 0 until n) {
                val off = i - (n - 1) / 2f
                // Every comb is drawn on the right; its mirror covers the left.
                if (off < 0f) continue
                val ang = (PI / 2).toFloat() - off * spread
                val dx = cos(ang); val dy = sin(ang)
                val len = baseLen * (1f - 0.18f * abs(off))
                val halfW = (0.46f / (n + 0.6f) + 0.05f) * (1f - 0.1f * abs(off))
                val cx = dx * (capRy * 0.9f + len * 0.62f) * 1.0f
                val cy = capCy + dy * (capRy * 0.72f + len * 0.62f)
                val d = rotatedEllipse(x, v, cx, cy, dx, dy, len, halfW)
                if (d < 0f) {
                    raise(c, 0.2f * k + 0.08f * min(1f, -d / 0.06f), CREST)
                    val inner = d + 2.2f * line
                    if (inner < 0f && inner > -line * 1.05f) paint(c, ACCENT)
                }
            }
        }

        /**
         * Ijele's crown: tiers stepping back and in like a ziggurat, colour
         * bands alternating, each carrying a zigzag of accent, and a finial
         * disc on top.
         */
        private fun tiers(x: Float, v: Float, c: Cell) {
            val n = g.crestCount.coerceIn(2, 5)
            val total = 0.55f + 0.75f * ch
            val base = 0.86f
            val th = total / n
            for (i in 0 until n) {
                val v0 = base + i * th; val v1 = v0 + th
                if (v < v0 - 0.001f || v >= v1) continue
                val f = (v - v0) / th
                val hwTier = w * (1.12f - 0.62f * i / n) - 0.08f * f
                if (x >= hwTier) continue
                val band = if (i % 2 == 0) CREST else discField
                raise(c, (0.3f - 0.03f * i) * k + 0.05f, band)
                // Accent zigzag through the band's middle.
                val period = 0.18f
                val tri = abs(((x / period) % 1f) - 0.5f) * 2f
                val zig = v0 + th * 0.5f + (tri - 0.5f) * th * 0.36f
                if (abs(v - zig) < line * 0.75f && x < hwTier - line * 1.5f) paint(c, if (band == CREST) ACCENT else lineOnCrest)
                // A crisp ledge line at the top of each tier.
                if (v1 - v < line * 1.1f) paint(c, INK.takeIf { p.ink != p.colours()[band - 1] } ?: ACCENT)
            }
            val topV = base + total
            val r = 0.12f + 0.06f * ch
            val d = len(x, v - (topV + r * 0.8f)) - r
            if (d < 0f) {
                raise(c, 0.28f * k, ACCENT)
                if (d > -line * 1.2f) paint(c, CREST)
            }
        }

        /**
         * Ram horns sweeping out from the temples and curling back in: the
         * Ikenga crest, and Mgbedike's. Banded in the second colour at
         * intervals, as brass wire is wound round carved horns.
         */
        private fun horns(x: Float, v: Float, c: Cell) {
            val hit = horn.nearest(x, v)
            if (hit.d >= 0f) return
            val depthIn = (-hit.d / hit.r).coerceIn(0f, 1f)
            raise(c, 0.18f * k + 0.18f * k * sqrt(depthIn), CREST)
            val bands = floatArrayOf(0.34f, 0.5f, 0.64f, 0.76f)
            val nBands = if (g.ornament < 0.3f) 1 else if (g.ornament < 0.65f) 2 else 4
            for (b in 0 until nBands) {
                val t = bands[if (nBands == 1) 1 else if (nBands == 2) b * 2 else b]
                if (abs(hit.t - t) < 0.022f + pixel * 0.35f) paint(c, beardStripe)
            }
        }

        /** A fan of feathers from the crown -- a sunburst, the most Deco of motifs -- tipped with jewels. */
        private fun plumes(x: Float, v: Float, c: Cell) {
            val n = g.crestCount.coerceIn(3, 11)
            val ox = 0f; val oy = 0.55f
            val spread = min(1.25f, 0.22f * n)
            val len = 0.45f + 0.7f * ch
            val r0 = 0.5f
            for (i in 0 until n) {
                val off = i - (n - 1) / 2f
                if (off < 0f) continue
                val ang = (PI / 2).toFloat() - (if (n == 1) 0f else off * 2f * spread / (n - 1))
                val dx = cos(ang); val dy = sin(ang)
                val l = len * (1f - 0.22f * (abs(off) / ((n - 1) / 2f).coerceAtLeast(1f)))
                val halfW = min(0.16f, (r0 + l) * sin(spread / (n - 1).coerceAtLeast(1)) * 0.95f)
                val cx = ox + dx * (r0 + l * 0.5f); val cy = oy + dy * (r0 + l * 0.5f)
                val d = rotatedEllipse(x, v, cx, cy, dx, dy, l * 0.5f, halfW)
                if (d < 0f) {
                    val slot = if (i % 2 == 0) CREST else discField
                    raise(c, 0.16f * k + 0.06f * min(1f, -d / 0.05f), slot)
                    // A spine down the feather's middle.
                    if (g.ornament > 0.4f && abs(rotatedAcross(x, v, cx, cy, dx, dy)) < line * 0.5f) paint(c, if (slot == CREST) lineOnCrest else INK)
                }
                // The jewel at the tip.
                val tx = ox + dx * (r0 + l + 0.02f); val ty = oy + dy * (r0 + l + 0.02f)
                if (len(x - tx, v - ty) < 0.075f + 0.02f * ch) raise(c, 0.26f * k, ACCENT)
            }
        }

        /** A sun disc behind the head, in concentric bands with a ring of dots: a halo, an Art Deco radiator grille. */
        private fun disc(x: Float, v: Float, c: Cell) {
            val cy = 0.45f
            val r = max(w + 0.32f, 0.95f + 0.42f * ch)
            val d = len(x, v - cy)
            if (d >= r) return
            raise(c, 0.12f * k + 0.04f, CREST)
            val band = 0.16f
            if (d < r - band) paint(c, discField)
            if (abs(d - (r - band - 0.06f)) < line * 0.55f) paint(c, ACCENT)
            if (g.ornament > 0.35f) {
                // Dots in the outer band, evenly round, drawn on the right and mirrored.
                val n = 24
                val a = atan2(v - cy, x)
                val stepA = (2 * PI / n).toFloat()
                val nearest = (floor(a / stepA + 0.5f)) * stepA
                val px = cos(nearest) * (r - band / 2); val py = cy + sin(nearest) * (r - band / 2)
                if (len(x - px, v - py) < max(0.035f, pixel * 0.8f)) paint(c, if (p.ivory != p.crest) IVORY else ACCENT)
            }
        }

        /** The skull under a crest: the hair, rising a little proud of the forehead along a clean hairline. */
        private fun hairCap(x: Float, v: Float, c: Cell) {
            val hairline = 0.66f + 0.14f * (x / w).pow(2) - (if (g.crest == CrestForm.HORNS) 0.03f else 0f)
            if (v < hairline) return
            val d = ellipse(x, v, 0f, capCy, capRx, capRy)
            if (d >= 0f) return
            val hw = halfWidth(v.coerceAtMost(0.99f)).coerceAtLeast(0.001f)
            val under = if (x < hw) faceHeight(x, v, hw) else rim
            set(c, max(under + 0.05f, rim + 0.1f * min(1f, -d / 0.12f)), CREST)
            // A band along the hairline: the maiden's beaded headband.
            if (g.ornament > 0.25f && v - hairline < line * 2.2f && p.second != p.crest) {
                paint(c, SECOND)
            }
        }

        // ---- Ears ---------------------------------------------------------

        private fun smallEar(x: Float, v: Float, c: Cell) {
            val cx = halfWidth(ey - 0.05f) + 0.03f
            val d = ellipse(x, v, cx, ey - 0.1f, 0.1f, 0.19f)
            if (d >= 0f) return
            raise(c, 0.18f * k, FACE)
            if (d > -line * 1.1f - 0.02f && d < -0.02f) paint(c, INK)
        }

        /** Ogbodo Enyi's fan ears, striped in concentric arcs from where they meet the head. */
        private fun elephantEar(x: Float, v: Float, c: Cell) {
            val cx = w + 0.42f; val cy = ey - 0.02f
            val d = ellipse(x, v, cx, cy, 0.58f, 0.74f)
            if (d >= 0f) return
            raise(c, 0.14f * k + 0.03f, earFill)
            // Two bold concentric bands from where the ear meets the head, like a scallop shell.
            val r = len(x - w * 0.8f, v - cy)
            val band = max(0.07f, line * 1.2f)
            if ((abs(r - 0.42f) < band / 2 || abs(r - 0.72f) < band / 2) && d < -band) paint(c, INK)
            if (d > -line) paint(c, CREST)
        }

        // ---- Features -----------------------------------------------------

        /**
         * Brows: a hairline painted arch on a delicate mask, a carved ridge
         * on a fierce one. Either way the arch runs down into the nose's
         * bridge, as on the carvings, so brow and nose read as one line.
         */
        private fun brows(x: Float, v: Float, c: Cell) {
            val r = ex * 1.02f
            val cy = ey - 0.1f
            val thick = line * 0.5f + 0.025f * g.brow
            val d = arc(x, v, ex, cy, r, thick, 0.55f, 2.55f)
            // Always one crisp band of ink; a heavier brow is also thicker and carved proud.
            if (d < 0f) {
                if (g.brow < 0.35f) paint(c, INK) else lift(c, 0.02f + 0.1f * g.brow * k, INK)
            }
        }

        private fun eyes(x: Float, v: Float, left: Boolean, c: Cell) {
            val form = if (!g.symmetric && left && g.eyes == EyeForm.ALMOND) EyeForm.CRESCENT else g.eyes
            val tilt = 0.12f
            // Into the eye's own frame, tilted up at the outer corner.
            val lx = (x - ex) * cos(tilt) + (v - ey) * sin(tilt)
            val ly = -(x - ex) * sin(tilt) + (v - ey) * cos(tilt)
            val halfLen = 0.17f * (w / 0.66f).coerceIn(0.8f, 1.2f)
            when (form) {
                EyeForm.ALMOND -> {
                    val lid = almond(lx, ly, halfLen, 0.085f)
                    if (lid < 0f) {
                        lift(c, 0.05f * k, FACE)
                        if (almond(lx, ly + 0.004f, halfLen * 0.86f, max(0.022f, line * 0.62f)) < 0f) carve(c, 0.04f * k, INK)
                    }
                }
                EyeForm.CRESCENT -> {
                    val lid = almond(lx, ly, halfLen, 0.085f)
                    if (lid < 0f) lift(c, 0.05f * k, FACE)
                    // A line sagging to a closed curve.
                    val d = arc(lx, ly, 0f, 0.13f, 0.16f, line * 0.6f, (PI * 1.2).toFloat(), (PI * 1.8).toFloat())
                    if (d < 0f) paint(c, INK)
                }
                EyeForm.ROUND -> {
                    val d = len(lx, ly)
                    val r = 0.105f
                    if (d < r) {
                        lift(c, 0.06f * k, if (p.ivory != p.face) IVORY else SECOND)
                        if (d > r - line * 1.1f) paint(c, INK)
                        if (d < 0.045f + pixel * 0.3f) { carve(c, 0.02f, INK) }
                    }
                }
                EyeForm.TUBULAR -> {
                    val d = len(lx, ly)
                    val r = 0.1f
                    if (d < r) {
                        lift(c, 0.2f * k, FACE)
                        if (d > r - line * 1.1f) paint(c, lipColour)
                        if (d < 0.045f + pixel * 0.3f) carve(c, 0.26f * k, INK)
                    }
                }
            }
        }

        private fun nose(x: Float, v: Float, c: Cell) {
            val top = ey + 0.05f
            if (v > top || v < noseBottom - 0.1f) return
            val t = ((top - v) / (top - noseBottom)).coerceIn(0f, 1f)
            when (g.nose) {
                NoseForm.LONG_STRAIGHT -> {
                    val hwN = 0.035f + 0.045f * t
                    val tip = len(x, v - noseBottom) - 0.075f
                    if ((x < hwN && v >= noseBottom) || tip < 0f) {
                        lift(c, (0.04f + 0.13f * t) * k, FACE)
                    }
                }
                NoseForm.ARCHED -> {
                    val hwN = 0.03f + 0.04f * t
                    val tip = len(x, v - noseBottom) - 0.065f
                    if ((x < hwN && v >= noseBottom) || tip < 0f) {
                        val hump = sin(t * PI.toFloat() * 0.85f)
                        lift(c, (0.05f + 0.1f * hump + 0.05f * t) * k, FACE)
                    }
                }
                NoseForm.BROAD -> {
                    val hwN = 0.04f + 0.1f * t * t
                    val wing = len(x - 0.1f, v - (noseBottom + 0.02f)) - 0.07f
                    val tip = len(x, v - noseBottom) - 0.08f
                    if ((x < hwN && v >= noseBottom) || wing < 0f || tip < 0f) {
                        lift(c, (0.04f + 0.12f * t) * k, FACE)
                        if (len(x - 0.07f, v - (noseBottom - 0.01f)) < max(0.025f, line * 0.7f)) carve(c, 0.06f, INK)
                    }
                }
            }
        }

        private fun mouth(x: Float, v: Float, c: Cell) {
            when (g.mouth) {
                MouthForm.CLOSED_SMILE -> {
                    val d = ellipse(x, v, 0f, my, 0.17f, 0.065f)
                    if (d < 0f) {
                        lift(c, 0.06f * k, lipColour)
                        // The parting, curling up at the corners.
                        val parting = my + 0.035f * (x / 0.17f).pow(2)
                        if (abs(v - parting) < line * 0.55f) carve(c, 0.02f, INK)
                    }
                }
                MouthForm.PURSED -> {
                    val d = len(x, (v - my) * 1.15f) - 0.085f
                    if (d < 0f) {
                        lift(c, 0.1f * k, lipColour)
                        if (len(x * 0.8f, v - my) < max(0.025f, line * 0.6f)) carve(c, 0.06f, INK)
                    }
                }
                MouthForm.OPEN_TEETH -> {
                    val outer = ellipse(x, v, 0f, my - 0.02f, 0.25f, 0.12f)
                    if (outer >= 0f) return
                    lift(c, 0.07f * k, lipColour)
                    val inner = ellipse(x, v, 0f, my - 0.02f, 0.2f, 0.07f)
                    if (inner < 0f) {
                        carve(c, 0.08f * k, INK)
                        // Teeth: ivory blocks top and bottom with a dark gap between each.
                        val toothW = max(0.055f, pixel * 2.2f)
                        val gap = max(pixel * 0.9f, 0.018f)
                        val phase = ((x + toothW / 2) % toothW)
                        val between = phase < gap
                        val upper = v > my - 0.02f + 0.012f
                        val lower = v < my - 0.02f - 0.03f
                        if (!between && (upper || lower)) lift(c, 0.05f * k, IVORY)
                    }
                }
            }
        }

        /** Ichi: the parallel cuts across a titled man's forehead, as fine grooves. */
        private fun ichi(u: Float, v: Float, c: Cell) {
            val n = g.ichi
            val spacing = max(0.075f, pixel * 2.6f)
            val v0 = ey + 0.3f; val v1 = min(0.7f, ey + 0.56f)
            if (v < v0 || v > v1) return
            for (i in 0 until n) {
                val cx = (i - (n - 1) / 2f) * spacing
                // Symmetric by construction: the positions are mirrored about 0.
                if (abs(u - cx) < line * 0.5f) carve(c, 0.025f, INK)
            }
        }

        /** Short raised bars on each cheek, stacked: the cheek marks of the carvings. */
        private fun cheekMarks(x: Float, v: Float, c: Cell) {
            val cx = ex + 0.02f; val cy = ey - 0.25f
            for (i in 0 until g.cheekMarks) {
                val oy = cy - i * max(0.07f, pixel * 2.6f)
                val d = capsule(x, v, cx - 0.06f, oy, cx + 0.07f, oy, line * 0.55f)
                if (d < 0f) lift(c, 0.02f, cheekColour)
            }
        }

        /**
         * Uli: the fine black linework of Igbo women's body and wall painting,
         * drawn with restraint. More of it appears as [MaskGenome.ornament]
         * rises: a line down the forehead, spirals at the temples, a tear
         * line from each eye, dots along the jaw.
         */
        private fun uli(x: Float, v: Float, thisSide: Boolean, c: Cell) {
            val o = g.ornament
            if (o > 0.15f && g.ichi == 0) {
                // The forehead line, from the hairline to the bridge.
                if (x < line * 0.5f && v > ey + 0.26f && v < 0.72f) paint(c, INK)
            }
            if (!thisSide) return
            if (o > 0.35f && fine) {
                val tx = ex + 0.14f; val ty = ey + 0.36f
                val d = len(x - tx, v - ty)
                if (abs(d - 0.07f) < line * 0.5f || d < line * 0.6f) paint(c, INK)
            }
            if (o > 0.55f) {
                val d = arc(x, v, ex + 0.24f, ey - 0.12f, 0.2f, line * 0.5f, (PI * 0.95).toFloat(), (PI * 1.4).toFloat())
                if (d < 0f) paint(c, INK)
            }
            if (o > 0.75f && fine) {
                for (i in 0 until 4) {
                    val dv = my + 0.12f - i * 0.1f
                    val hw = halfWidth(dv)
                    if (hw <= 0.1f) continue
                    if (len(x - (hw - 0.09f), v - dv) < max(0.022f, line * 0.6f)) paint(c, INK)
                }
            }
        }

        private fun tusks(x: Float, v: Float, c: Cell) {
            val hit = tusk.nearest(x, v)
            if (hit.d >= 0f) return
            set(c, rim + bulge + 0.06f * k, IVORY)
            if (hit.t < 0.1f && g.ornament > 0.3f) paint(c, ACCENT)
        }

        /** A striped beard: flat vertical bands, like a Deco awning. */
        private fun stripedBeard(u: Float, x: Float, v: Float, c: Cell) {
            val top = -0.82f; val bottom = -1.28f
            if (v > top || v < bottom - 0.2f) return
            val t = ((top - v) / (top - bottom)).coerceIn(0f, 1f)
            val hw = max(0.14f, halfWidth(top) * (1f - 0.55f * t))
            val round = if (v < bottom) len(x / hw, (v - bottom) / 0.2f) else 0f
            if (x >= hw || round > 1f) return
            set(c, rim + 0.2f * k, CREST)
            val sw = max(0.07f, pixel * 2.4f)
            // Stripes laid out from the centre so the pattern is symmetric.
            if (floor(x / sw + 0.5f).toInt() % 2 == 1) paint(c, beardStripe)
        }

        private fun pointedBeard(x: Float, v: Float, c: Cell) {
            val top = -0.8f; val tipV = -1.42f
            if (v > top || v < tipV) return
            val t = (top - v) / (top - tipV)
            val hw = 0.3f * (1f - t).pow(0.8f)
            if (x >= hw) return
            set(c, rim + 0.18f * k * (1f - 0.5f * t), CREST)
            if (x < line * 0.55f) paint(c, beardStripe)
        }

        /** The neck and a two-step plinth under it, so the mask stands. */
        private fun plinth(x: Float, v: Float, c: Cell) {
            val bottomOfMask = when (g.beard) { BeardForm.STRIPED -> -1.48f; BeardForm.POINTED -> -1.42f; BeardForm.NONE -> -1.0f }
            val neckBottom = bottomOfMask - 0.12f
            val step = 0.1f
            val deep = rim + bulge * 0.95f
            if (v <= -0.7f && v > neckBottom && x < 0.24f) raise(c, deep * 0.8f, FACE)
            if (v <= neckBottom && v > neckBottom - step && x < 0.44f) {
                set(c, deep, baseSlot)
                if (neckBottom - v < line * 1.1f) paint(c, ACCENT)
            }
            if (v <= neckBottom - step && v > neckBottom - 2 * step && x < 0.62f) set(c, deep, baseSlot)
        }

        // ---- Signed distances -------------------------------------------

        private fun len(x: Float, y: Float) = sqrt(x * x + y * y)

        private fun smooth(t: Float) = t * t * (3 - 2 * t)

        /** Approximate distance to an axis-aligned ellipse: exact on the axes, good enough between. */
        private fun ellipse(x: Float, y: Float, cx: Float, cy: Float, rx: Float, ry: Float): Float =
            (len((x - cx) / rx, (y - cy) / ry) - 1f) * min(rx, ry)

        private fun rotatedAlong(x: Float, y: Float, cx: Float, cy: Float, dx: Float, dy: Float) = (x - cx) * dx + (y - cy) * dy
        private fun rotatedAcross(x: Float, y: Float, cx: Float, cy: Float, dx: Float, dy: Float) = -(x - cx) * dy + (y - cy) * dx

        /** An ellipse whose long axis runs along (dx, dy). */
        private fun rotatedEllipse(x: Float, y: Float, cx: Float, cy: Float, dx: Float, dy: Float, along: Float, across: Float): Float =
            ellipse(rotatedAlong(x, y, cx, cy, dx, dy), rotatedAcross(x, y, cx, cy, dx, dy), 0f, 0f, along, across)

        /** A pointed almond: two circular arcs meeting at the corners. */
        private fun almond(x: Float, y: Float, halfLen: Float, halfH: Float): Float {
            val r = (halfLen * halfLen + halfH * halfH) / (2 * halfH)
            val off = r - halfH
            return max(len(x, abs(y) + off) - r, abs(x) - halfLen)
        }

        private fun capsule(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float, r: Float): Float {
            val px = x - ax; val py = y - ay; val qx = bx - ax; val qy = by - ay
            val h = ((px * qx + py * qy) / (qx * qx + qy * qy)).coerceIn(0f, 1f)
            return len(px - qx * h, py - qy * h) - r
        }

        /** A band of [halfThick] along the arc of radius [r] between angles [a0]..[a1] (radians, counter-clockwise). */
        private fun arc(x: Float, y: Float, cx: Float, cy: Float, r: Float, halfThick: Float, a0: Float, a1: Float): Float {
            var a = atan2(y - cy, x - cx)
            if (a < 0f) a += (2 * PI).toFloat()
            return if (a in a0..a1) abs(len(x - cx, y - cy) - r) - halfThick
            else min(len(x - cx - r * cos(a0), y - cy - r * sin(a0)), len(x - cx - r * cos(a1), y - cy - r * sin(a1))) - halfThick
        }
    }

    /** Where a point is nearest a tube: signed distance, the curve parameter there, and the tube's radius there. */
    internal class Hit(var d: Float = 0f, var t: Float = 0f, var r: Float = 0f)

    /**
     * A tube along a cubic Bezier, tapering from [r0] to [r1]: horns and
     * tusks. The curve is flattened into short capsules once, so a sample is
     * a few dozen distance checks.
     */
    internal class Tube(control: FloatArray, private val r0: Float, private val r1: Float) {
        private val n = 32
        private val xs = FloatArray(n + 1)
        private val ys = FloatArray(n + 1)

        init {
            for (i in 0..n) {
                val t = i / n.toFloat(); val s = 1 - t
                val a = s * s * s; val b = 3 * s * s * t; val c = 3 * s * t * t; val d = t * t * t
                xs[i] = a * control[0] + b * control[2] + c * control[4] + d * control[6]
                ys[i] = a * control[1] + b * control[3] + c * control[5] + d * control[7]
            }
        }

        fun nearest(x: Float, y: Float): Hit {
            val hit = Hit(d = Float.MAX_VALUE)
            for (i in 0 until n) {
                val ax = xs[i]; val ay = ys[i]; val qx = xs[i + 1] - ax; val qy = ys[i + 1] - ay
                val px = x - ax; val py = y - ay
                val h = ((px * qx + py * qy) / (qx * qx + qy * qy)).coerceIn(0f, 1f)
                val t = (i + h) / n
                val r = r0 + (r1 - r0) * t
                val dx = px - qx * h; val dy = py - qy * h
                val d = sqrt(dx * dx + dy * dy) - r
                if (d < hit.d) { hit.d = d; hit.t = t; hit.r = r }
            }
            return hit
        }
    }
}
