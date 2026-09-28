package com.stratum.engine.microvoxel.gen

import com.stratum.engine.microvoxel.M
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
 * Options: `cell` (grid spacing, micro), `density` (0..2).
 */
object TreesStage : MicroStageFactory {
    const val ID = "micro:trees"
    private const val REACH = 14 // max canopy radius; how far outside a chunk a tree can still touch it

    override fun create(setup: StageSetup): MicroStage {
        val cell = setup.options.int("cell", 22)
        val density = setup.options.float("density", 1f)
        val p = setup.palette
        val bark = p.id(M.BARK); val leaves = p.id(M.LEAVES); val autumn = p.id(M.LEAVES_AUTUMN)
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
                    else broadleaf(ctx, tx, ty, base, treeSeed, bark, if ((treeSeed and 15L) == 0L) autumn else leaves)
                }
        }
    }

    private fun abs2(a: Float, b: Float) = sqrt(a * a + b * b)

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
 * Options: `density` (0..2).
 */
object GroundcoverStage : MicroStageFactory {
    const val ID = "micro:groundcover"

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
                when (top) {
                    grass, dry -> {
                        val lush = lushness.heightAt(wx, wy)
                        val flowers = blooms.heightAt(wx, wy)
                        when {
                            r < 0.012f * density * flowers * 3f -> {
                                ctx.place(wx, wy, h + 1, top)
                                ctx.place(wx, wy, h + 2, if ((wx + wy) and 1 == 0) red else yellow)
                            }
                            r < 0.28f * density * lush -> {
                                ctx.place(wx, wy, h + 1, top)
                                if (r < 0.07f * density * lush) ctx.place(wx, wy, h + 2, top)
                            }
                            r > 0.997f && top == grass -> { // low shrubs
                                for (dz in 1..2) for (dy in -1..1) for (dx in -1..1)
                                    if (dx * dx + dy * dy + dz <= 3) ctx.place(wx + dx, wy + dy, h + dz, leaves)
                            }
                        }
                    }
                    gravel -> if (r < 0.05f * density) ctx.place(wx, wy, h + 1, stone)
                }
            }
        }
    }
}
