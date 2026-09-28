package com.stratum.engine.microvoxel.gen

import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * `micro:trees` -- broadleaf and conifer trees on a jittered grid.
 *
 * One candidate per grid cell, displaced inside it by a hash, gives trees that
 * never overlap and never line up (a cheap blue-noise). Each candidate is
 * accepted by climate, slope, clustering noise and land use, then built as
 * a shape function -- trunk disc, noisy leaf blobs or stacked cone rings --
 * so a canopy crossing a chunk border is drawn half by each chunk.
 *
 * Options: `cell` (grid spacing, micro), `density` (0..2), `style`
 * (`temperate`: oaks and firs; `tropical`: iroko, oil palm and baobab by
 * how wet the land is).
 */
object TreesStage : MicroStageFactory, Describable {
    override fun describe() = StageInfo(
        ID, "Trees", "Forests and lone trees by climate.",
        listOf(
            StageParam.Choice("style", "Kind", "Temperate oaks and firs, or iroko, oil palm and baobab.", listOf("temperate", "tropical"), "temperate"),
            StageParam.Number("density", "Density", "How thickly trees grow.", 0f, 2f, 1f),
            StageParam.Number("cell", "Spacing", "Quarter-blocks between trees at their thickest.", 12f, 40f, 22f, 1f),
        ),
    )

    const val ID = "micro:trees"
    private const val REACH = 14 // max canopy radius; how far outside a chunk a tree can still touch it

    override fun create(setup: StageSetup): MicroStage {
        val cell = setup.options.int("cell", 22)
        val density = setup.options.float("density", 1f)
        val tropical = when (val style = setup.options.string("style", "temperate")) {
            "temperate" -> false
            "tropical" -> true
            else -> throw IllegalArgumentException("Stage '$ID' option 'style' is 'temperate' or 'tropical', not '$style'")
        }
        val p = setup.palette
        val bark = p.id(M.BARK); val leaves = p.id(M.LEAVES); val autumn = p.id(M.LEAVES_AUTUMN)
        val palm = p.id(M.PALM); val fruit = p.id(M.FRUIT); val timber = p.id(M.TIMBER)
        val seed = setup.seed
        val clump = Noise(seed xor 0x7EE5)
        return MicroStage { ctx ->
            // Most vertical chunks are sky or rock: rule them out before sampling any climate.
            val cols = ctx.fields.require(Fields.COLUMNS).columns(ctx.pos.x, ctx.pos.y)
            if (ctx.z0 > cols.heights.max() + 80 || ctx.z1 < cols.heights.min() - 8) return@MicroStage
            val surface = ctx.fields.require(Fields.SURFACE)
            val climate = ctx.fields.require(Fields.CLIMATE)
            val sea = ctx.fields.require(Fields.SEA_LEVEL)
            val footprint = ctx.fields.get(Fields.FOOTPRINT)
            for (gy in Math.floorDiv(ctx.y0 - REACH, cell)..Math.floorDiv(ctx.y1 + REACH, cell))
                for (gx in Math.floorDiv(ctx.x0 - REACH, cell)..Math.floorDiv(ctx.x1 + REACH, cell)) {
                    val tx = gx * cell + 3 + Hash.int(seed, gx, gy, 1, cell - 6)
                    val ty = gy * cell + 3 + Hash.int(seed, gx, gy, 2, cell - 6)
                    val moist = climate.moisture(tx, ty)
                    val groves = TerrainStage.smooth(0.35f, 0.75f, 0.5f + clump.fbm(tx * 0.006f, ty * 0.006f, 3) * 0.9f)
                    var chance = density * (0.15f + moist * 0.9f) * groves
                    val urban = footprint?.urban(tx, ty) ?: 0f
                    chance *= (1f - urban)
                    if (Hash.unit(seed, gx, gy, 0, 3) >= chance) continue
                    val h = surface.heightAt(tx, ty)
                    if (h < sea + 2) continue
                    val slope = abs2(surface.heightAt(tx + 2, ty) - surface.heightAt(tx - 2, ty), surface.heightAt(tx, ty + 2) - surface.heightAt(tx, ty - 2)) / 4f
                    if (slope > 0.9f) continue
                    if (footprint?.isOccupied(tx, ty) == true) continue
                    val base = floor(h).toInt() + 1
                    val cold = climate.temperature(tx, ty) < 0.38f || h > sea + 190
                    val treeSeed = Hash.mix(seed, gx, gy, 0, 77)
                    if (cold) conifer(ctx, tx, ty, base, treeSeed, bark, leaves)
                    else if (tropical) {
                        // Wet forest is iroko and oil palm; dry savanna is baobab with the odd palm.
                        val roll = Hash.unit(seed, gx, gy, 0, 5)
                        when {
                            moist < 0.38f && roll < 0.7f -> baobab(ctx, tx, ty, base, treeSeed, timber, leaves)
                            roll < 0.45f -> oilPalm(ctx, tx, ty, base, treeSeed, bark, palm, fruit)
                            else -> iroko(ctx, tx, ty, base, treeSeed, bark, leaves)
                        }
                    }
                    else broadleaf(ctx, tx, ty, base, treeSeed, bark, if ((treeSeed and 15L) == 0L) autumn else leaves)
                }
        }
    }

    private fun abs2(a: Float, b: Float) = sqrt(a * a + b * b)

    /** The kinds of tree [plant] can grow. */
    enum class Kind { BROADLEAF, CONIFER, IROKO, OIL_PALM, BAOBAB }

    /**
     * Grows one tree of [kind] at a spot, for other stages: the sacred tree in
     * a village square, a palm by a compound gate. Draws only the part inside
     * [ctx]'s chunk, so it is as chunk-safe as the forest.
     */
    fun plant(ctx: MicroGenContext, kind: Kind, x: Int, y: Int, base: Int, seed: Long) {
        val p = ctx.palette
        val bark = p.id(M.BARK); val leaves = p.id(M.LEAVES)
        when (kind) {
            Kind.BROADLEAF -> broadleaf(ctx, x, y, base, seed, bark, leaves)
            Kind.CONIFER -> conifer(ctx, x, y, base, seed, bark, leaves)
            Kind.IROKO -> iroko(ctx, x, y, base, seed, bark, leaves)
            Kind.OIL_PALM -> oilPalm(ctx, x, y, base, seed, bark, p.id(M.PALM), p.id(M.FRUIT))
            Kind.BAOBAB -> baobab(ctx, x, y, base, seed, p.id(M.TIMBER), leaves)
        }
    }

    /**
     * Iroko: a tall clean trunk flaring into buttress roots, and a broad,
     * flat-topped umbrella crown held high -- the silhouette of a West African
     * forest giant, kept low enough for the isometric camera.
     */
    private fun iroko(ctx: MicroGenContext, tx: Int, ty: Int, base: Int, seed: Long, bark: Short, leaves: Short) {
        val trunk = 20 + (seed and 7L).toInt()
        val top = base + trunk
        if (!ctx.overlaps(tx - REACH, ty - REACH, base - 1, tx + REACH, ty + REACH, top + 8)) return
        for (z in base - 1 until top) {
            val r = if (z < base + 3) 2.6f - (z - base) * 0.35f else 1.3f // buttresses at the foot
            val ri = r.toInt() + 1
            for (dy in -ri..ri) for (dx in -ri..ri) {
                val d = sqrt((dx * dx + dy * dy).toFloat())
                // Buttresses are fins, not a cone: only along the diagonals and axes at the foot.
                val fin = z >= base + 3 || dx == 0 || dy == 0 || kotlin.math.abs(dx) == kotlin.math.abs(dy)
                if (d <= r && fin) ctx.place(tx + dx, ty + dy, z, bark)
            }
        }
        val blobs = 4 + (seed ushr 7 and 1L).toInt()
        repeat(blobs) { i ->
            val a = i * 6.2831855f / blobs + (seed and 15L) * 0.4f
            val bx = tx + (kotlin.math.cos(a) * 5f).toInt(); val by = ty + (kotlin.math.sin(a) * 5f).toInt()
            val r = 6f + Hash.unit(seed, i, 0, 0, 14) * 2.5f
            val ri = r.toInt() + 1
            for (z in top - 3..top + 4) for (y in by - ri..by + ri) for (x in bx - ri..bx + ri) {
                if (!ctx.overlaps(x, y, z, x, y, z)) continue
                val dx = (x - bx).toFloat(); val dy = (y - by).toFloat(); val dz = (z - top - 1) * 2.1f
                if (sqrt(dx * dx + dy * dy + dz * dz) < r - Hash.unit(seed, x, y, z, 15) * 1.6f) ctx.place(x, y, z, leaves)
            }
        }
    }

    /**
     * Oil palm: a slim ringed trunk with a gentle lean, a crown of long
     * fronds arching out and down, and a cluster of red fruit under them.
     */
    private fun oilPalm(ctx: MicroGenContext, tx: Int, ty: Int, base: Int, seed: Long, bark: Short, palm: Short, fruit: Short) {
        val height = 16 + (seed and 7L).toInt()
        val lean = ((seed ushr 5) and 3L).toInt() - 1.5f
        val leanDir = ((seed ushr 9) and 7L).toInt() * 0.785f
        val top = base + height
        if (!ctx.overlaps(tx - REACH, ty - REACH, base - 1, tx + REACH, ty + REACH, top + 4)) return
        fun trunkAt(z: Int): Pair<Int, Int> {
            val t = (z - base).toFloat() / height
            return (tx + (kotlin.math.cos(leanDir) * lean * t * t * 3f).toInt()) to (ty + (kotlin.math.sin(leanDir) * lean * t * t * 3f).toInt())
        }
        for (z in base - 1 until top) {
            val (x, y) = trunkAt(z)
            ctx.place(x, y, z, bark); ctx.place(x + 1, y, z, bark)
            if ((z - base) % 3 != 0) { ctx.place(x, y + 1, z, bark); ctx.place(x + 1, y + 1, z, bark) } // leaf-scar rings
        }
        val (cx, cy) = trunkAt(top)
        val fronds = 9
        repeat(fronds) { i ->
            val a = i * 6.2831855f / fronds + Hash.unit(seed, i, 0, 0, 16) * 0.4f
            val len = 8 + Hash.int(seed, i, 0, 17, 3)
            for (k in 0..len) {
                val x = cx + (kotlin.math.cos(a) * k).toInt(); val y = cy + (kotlin.math.sin(a) * k).toInt()
                val z = top + 1 + (k * 0.6f).toInt() - (k * k * 0.09f).toInt() // up, then arching down
                ctx.place(x, y, z, palm)
                if (k in 2 until len && k % 2 == 0) { // leaflets either side
                    ctx.place(x + (kotlin.math.sin(a) * 1.5f).toInt(), y - (kotlin.math.cos(a) * 1.5f).toInt(), z - 1, palm)
                    ctx.place(x - (kotlin.math.sin(a) * 1.5f).toInt(), y + (kotlin.math.cos(a) * 1.5f).toInt(), z - 1, palm)
                }
            }
        }
        for (dz in -2..-1) for (dy in 0..1) for (dx in -1..2) if ((dx + dy + dz) and 1 == 0) ctx.place(cx + dx, cy + dy, top + dz, fruit)
    }

    /**
     * Baobab: a vast bottle trunk, swelling in the middle, with a few stubby
     * branches and sparse leaves -- the savanna's landmark.
     */
    private fun baobab(ctx: MicroGenContext, tx: Int, ty: Int, base: Int, seed: Long, trunkMat: Short, leaves: Short) {
        val height = 14 + (seed and 3L).toInt()
        val top = base + height
        if (!ctx.overlaps(tx - REACH, ty - REACH, base - 1, tx + REACH, ty + REACH, top + 8)) return
        for (z in base - 1 until top) {
            val t = (z - base).toFloat() / height
            val r = 3.2f + 1.6f * kotlin.math.sin(t * 3.1415927f) // fattest in the middle
            val ri = r.toInt() + 1
            for (dy in -ri..ri) for (dx in -ri..ri) if (sqrt((dx * dx + dy * dy).toFloat()) <= r) ctx.place(tx + dx, ty + dy, z, trunkMat)
        }
        val branches = 5
        repeat(branches) { i ->
            val a = i * 6.2831855f / branches + (seed and 7L) * 0.3f
            for (k in 1..6) {
                val x = tx + (kotlin.math.cos(a) * k).toInt(); val y = ty + (kotlin.math.sin(a) * k).toInt(); val z = top + k / 2
                ctx.place(x, y, z, trunkMat)
                if (k >= 4) for (dz in 0..1) for (dy in -1..1) for (dx in -1..1)
                    if (Hash.unit(seed, x + dx, y + dy, z + dz, 18) < 0.55f) ctx.place(x + dx, y + dy, z + 1 + dz, leaves)
            }
        }
    }

    private fun broadleaf(ctx: MicroGenContext, tx: Int, ty: Int, base: Int, seed: Long, bark: Short, leaves: Short) {
        val trunk = 14 + (seed and 7L).toInt()
        val top = base + trunk
        if (!ctx.overlaps(tx - REACH, ty - REACH, base - 1, tx + REACH, ty + REACH, top + 12)) return
        for (z in base - 1 until top) for (dy in -1..1) for (dx in -1..1) {
            if (dx != 0 && dy != 0 && z > base + 1) continue // round the trunk above the root flare
            ctx.place(tx + dx, ty + dy, z, bark)
        }
        // Three to five overlapping blobs make a crown that is not a sphere.
        val blobs = 3 + ((seed ushr 8) and 1L).toInt() * 2
        repeat(blobs) { i ->
            val bx = tx + Hash.int(seed, i, 0, 1, 11) - 5
            val by = ty + Hash.int(seed, i, 0, 2, 11) - 5
            val bz = top + Hash.int(seed, i, 0, 3, 7) - 2
            val r = 5f + Hash.unit(seed, i, 0, 0, 4) * 3f
            val ri = r.toInt() + 1
            for (z in bz - ri..bz + ri) for (y in by - ri..by + ri) for (x in bx - ri..bx + ri) {
                if (!ctx.overlaps(x, y, z, x, y, z)) continue
                val dx = (x - bx).toFloat(); val dy = (y - by).toFloat(); val dz = (z - bz) * 1.25f
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                // A per-voxel ragged edge and a few holes: leaves, not a ball.
                val edge = r - Hash.unit(seed, x, y, z, 9) * 1.8f
                if (d < edge && Hash.unit(seed, x, y, z, 10) > 0.06f) ctx.place(x, y, z, leaves)
            }
        }
    }

    private fun conifer(ctx: MicroGenContext, tx: Int, ty: Int, base: Int, seed: Long, bark: Short, needles: Short) {
        val height = 24 + (seed and 15L).toInt()
        val top = base + height
        if (!ctx.overlaps(tx - 9, ty - 9, base - 1, tx + 9, ty + 9, top + 1)) return
        for (z in base - 1 until top - 2) ctx.place(tx, ty, z, bark)
        for (z in base + 5..top) {
            val t = (top - z).toFloat() / (height - 5)
            // Sawtooth rings: each whorl flares out then tucks in, the classic fir silhouette.
            val whorl = ((top - z) % 5) / 5f
            val r = (1f + t * 7f) * (0.65f + 0.35f * whorl)
            val ri = r.toInt() + 1
            for (y in ty - ri..ty + ri) for (x in tx - ri..tx + ri) {
                val dx = (x - tx).toFloat(); val dy = (y - ty).toFloat()
                if (sqrt(dx * dx + dy * dy) < r - Hash.unit(seed, x, y, z, 12) * 0.9f) ctx.place(x, y, z, needles)
            }
        }
    }
}

/**
 * `micro:groundcover` -- the detail only microvoxels can draw: grass tufts,
 * wild flowers and pebbles one quarter-block tall. It is a large part of why
 * a microvoxel landscape reads as a place rather than a board, and it costs
 * almost nothing because it only ever touches the top voxel of each column.
 *
 * Options: `density` (0..2), `tall` (0..1: share of tufts that grow up to a
 * block high, as elephant grass does).
 */
object GroundcoverStage : MicroStageFactory, Describable {
    override fun describe() = StageInfo(
        ID, "Grass & flowers", "Tufts, flowers, shrubs and pebbles.",
        listOf(
            StageParam.Number("density", "Density", "How much of the ground is covered.", 0f, 2f, 1f),
            StageParam.Number("tall", "Tall grass", "Share of tufts that grow to elephant grass.", 0f, 1f, 0f),
        ),
    )

    const val ID = "micro:groundcover"
    private const val NONE = 0
    private const val GREEN = 1
    private const val DRY = 2
    private const val EARTH = 3

    override fun create(setup: StageSetup): MicroStage {
        val density = setup.options.float("density", 1f)
        val p = setup.palette
        val grass = p.id(M.GRASS); val dry = p.id(M.GRASS_DRY); val gravel = p.id(M.GRAVEL)
        val red = p.id(M.FLOWER_RED); val yellow = p.id(M.FLOWER_YELLOW); val stone = p.id(M.STONE)
        val leaves = p.id(M.LEAVES)
        val seed = setup.seed
        val meadow = Noise(seed xor 0x6A55)
        // Where grass is lush and where flowers gather: fields that change over metres, sampled once a block.
        val lushness = LatticeHeight(step = 4, source = HeightFunction { x, y -> 0.5f + meadow.fbm(x * 0.02f, y * 0.02f, 2) })
        val blooms = LatticeHeight(step = 4, source = HeightFunction { x, y -> 0.5f + meadow.fbm(x * 0.01f + 90f, y * 0.01f, 2) })
        // Tall grass: the share of tufts that grow up to a block high -- elephant grass on a savanna.
        val tall = setup.options.float("tall", 0f)
        val bare = setOf(p.id(M.SAND), gravel, stone, p.id(M.DARK_STONE), p.id(M.SNOW), p.id(M.WATER))
        // What grows on a surface, decided once per material: its own green on turf, sparse dry
        // grass on bare earth (a pack's red laterite), nothing on sand, rock and snow.
        val growth = java.util.concurrent.ConcurrentHashMap<Short, Int>()
        fun growthOf(m: Short): Int = growth.getOrPut(m) {
            when {
                m == grass -> GREEN
                m == dry -> DRY
                m in bare || m == MaterialPalette.AIR -> NONE
                else -> {
                    val c = p[m].color
                    val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                    if (g > r + 8 && g > b) GREEN else if (p[m].opaque) EARTH else NONE
                }
            }
        }
        return MicroStage { ctx ->
            val cols = ctx.fields.require(Fields.COLUMNS).columns(ctx.pos.x, ctx.pos.y)
            val occupied = ctx.fields.get(Fields.FOOTPRINT)?.occupancyMask(ctx.pos.x, ctx.pos.y)
            val sea = ctx.fields.require(Fields.SEA_LEVEL)
            val s = MicroChunk.SIZE
            for (ly in 0 until s) for (lx in 0 until s) {
                val h = cols.height(lx, ly)
                if (h + 1 < ctx.z0 || h + 1 > ctx.z1 || h <= sea) continue
                if (occupied != null && occupied[ly * s + lx]) continue
                val top = cols.top(lx, ly)
                val wx = ctx.x0 + lx; val wy = ctx.y0 + ly
                val r = Hash.unit(seed, wx, wy, 0, 21)
                val kind = growthOf(top)
                if (kind == GREEN || kind == DRY || kind == EARTH) {
                    val tuft = when (kind) { GREEN -> top; else -> dry }
                    val lush = lushness.heightAt(wx, wy) * if (kind == EARTH) 0.45f else 1f
                    val flowers = blooms.heightAt(wx, wy)
                    when {
                        kind != EARTH && r < 0.012f * density * flowers * 3f -> {
                            ctx.place(wx, wy, h + 1, tuft)
                            ctx.place(wx, wy, h + 2, if ((wx + wy) and 1 == 0) red else yellow)
                        }
                        r < 0.28f * density * lush -> {
                            ctx.place(wx, wy, h + 1, tuft)
                            if (r < 0.07f * density * lush) ctx.place(wx, wy, h + 2, tuft)
                            if (tall > 0f && Hash.unit(seed, wx, wy, 0, 22) < tall) {
                                ctx.place(wx, wy, h + 2, tuft); ctx.place(wx, wy, h + 3, tuft)
                                if (Hash.unit(seed, wx, wy, 0, 23) < 0.5f) ctx.place(wx, wy, h + 4, tuft)
                            }
                        }
                        r > 0.997f && kind == GREEN -> { // low shrubs
                            for (dz in 1..2) for (dy in -1..1) for (dx in -1..1)
                                if (dx * dx + dy * dy + dz <= 3) ctx.place(wx + dx, wy + dy, h + dz, leaves)
                        }
                    }
                    continue
                }
                when (top) {
                    gravel -> if (r < 0.05f * density) ctx.place(wx, wy, h + 1, stone)
                }
            }
        }
    }
}
