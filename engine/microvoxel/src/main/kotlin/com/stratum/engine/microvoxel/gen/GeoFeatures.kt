package com.stratum.engine.microvoxel.gen

import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.geo.Provinces
import com.stratum.engine.microvoxel.geo.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `micro:features` -- the landscape's small wonders, which a height field
 * cannot make because they stand free or overhang:
 *
 * - **Termite mounds**: cathedral mounds of cemented subsoil, up to a few
 *   metres tall, with side spires -- the savanna's most common landmark
 *   (Macrotermes, from the Sahel to the Kalahari).
 * - **Tors and balancing rocks**: rounded granite boulders stacked where
 *   the joints weathered out -- the Matobo Hills, Epworth, Kubu Island.
 * - **Sandstone arches**: freestanding spans left by retreating cliffs --
 *   the Aloba arch in the Ennedi, the Tassili's arches.
 *
 * Needs `micro:terrain` with `geology` on (each province says which
 * features it has); does nothing otherwise. Options: `density` (0..2),
 * `termites`, `tors`, `arches` (true / false).
 */
object GeoFeaturesStage : MicroStageFactory, Describable {
    const val ID = "micro:features"

    override fun describe() = StageInfo(
        ID, "Rocks & mounds", "Termite mounds, balancing-rock tors and sandstone arches, where the geology has them.",
        listOf(
            StageParam.Number("density", "Density", "How many there are.", 0f, 2f, 1f),
            StageParam.Toggle("termites", "Termite mounds", "Cathedral mounds on the savannas.", true),
            StageParam.Toggle("tors", "Tors", "Stacked granite boulders on the old cratons.", true),
            StageParam.Toggle("arches", "Arches", "Freestanding sandstone arches below the escarpments.", true),
        ),
    )

    private val TERMITES = mapOf(
        Provinces.LATERITE_PLATEAU to 0.3f, Provinces.SAHEL_PLAIN to 0.35f, Provinces.INSELBERGS to 0.25f,
        Provinces.KALAHARI to 0.2f, Provinces.RIFT to 0.15f, Provinces.FOREST_HILLS to 0.06f, Provinces.KAROO to 0.08f,
    )
    private val TORS = mapOf(Provinces.INSELBERGS to 0.45f, Provinces.SALT_PAN to 0.05f, Provinces.LATERITE_PLATEAU to 0.05f)
    private val ARCHES = mapOf(Provinces.SANDSTONE_ESCARPMENT to 0.35f, Provinces.REG_HAMADA to 0.12f)

    private const val MOUND_CELL = 56
    private const val TOR_CELL = 96
    private const val ARCH_CELL = 420

    override fun create(setup: StageSetup): MicroStage {
        val o = setup.options
        val density = o.float("density", 1f)
        val termites = o.boolean("termites", true)
        val tors = o.boolean("tors", true)
        val arches = o.boolean("arches", true)
        val p = setup.palette
        val clay = p.id(R.TERMITE_CLAY)
        val granite = p.id(R.GRANITE); val weathered = p.id(R.GRANITE_WEATHERED)
        val sandstone = shortArrayOf(p.id(R.SANDSTONE_RED), p.id(R.SANDSTONE_RED), p.id(R.SANDSTONE_BUFF), p.id(R.SANDSTONE_RED), p.id(R.SANDSTONE_PALE))
        val seed = setup.seed
        return MicroStage { ctx ->
            val geology = ctx.fields.get(Fields.GEOLOGY) ?: return@MicroStage
            val cols = ctx.fields.require(Fields.COLUMNS).columns(ctx.pos.x, ctx.pos.y)
            if (ctx.z0 > cols.heights.max() + 60 || ctx.z1 < cols.heights.min() - 4) return@MicroStage
            val surface = ctx.fields.require(Fields.SURFACE)
            val sea = ctx.fields.require(Fields.SEA_LEVEL)
            val footprint = ctx.fields.get(Fields.FOOTPRINT)
            fun site(x: Int, y: Int): Int? {
                val h = surface.heightAt(x, y)
                if (h < sea + 2) return null
                val slope = abs(surface.heightAt(x + 3, y) - surface.heightAt(x - 3, y)) + abs(surface.heightAt(x, y + 3) - surface.heightAt(x, y - 3))
                if (slope > 5f) return null
                if (footprint != null && (footprint.urban(x, y) > 0.2f || footprint.isOccupied(x, y))) return null
                return floor(h).toInt()
            }
            if (termites) scatter(ctx, seed, MOUND_CELL, 11, 8) { x, y, gx, gy ->
                val chance = (TERMITES[geology.provinceAt(x, y).id] ?: 0f) * density
                if (Hash.unit(seed, gx, gy, 0, 12) >= chance) return@scatter
                val base = site(x, y) ?: return@scatter
                mound(ctx, x, y, base, Hash.mix(seed, gx, gy, 0, 13), clay)
            }
            if (tors) scatter(ctx, seed, TOR_CELL, 21, 12) { x, y, gx, gy ->
                val chance = (TORS[geology.provinceAt(x, y).id] ?: 0f) * density
                if (Hash.unit(seed, gx, gy, 0, 22) >= chance) return@scatter
                val base = site(x, y) ?: return@scatter
                tor(ctx, x, y, base, Hash.mix(seed, gx, gy, 0, 23), granite, weathered)
            }
            if (arches) scatter(ctx, seed, ARCH_CELL, 31, 30) { x, y, gx, gy ->
                val chance = (ARCHES[geology.provinceAt(x, y).id] ?: 0f) * density
                if (Hash.unit(seed, gx, gy, 0, 32) >= chance) return@scatter
                val base = site(x, y) ?: return@scatter
                arch(ctx, x, y, base, Hash.mix(seed, gx, gy, 0, 33), surface, sandstone)
            }
        }
    }

    /** One candidate per [cell], jittered inside it, for every cell whose feature (up to [reach] wide) can touch this chunk. */
    private inline fun scatter(ctx: MicroGenContext, seed: Long, cell: Int, salt: Int, reach: Int, each: (x: Int, y: Int, gx: Int, gy: Int) -> Unit) {
        for (gy in Math.floorDiv(ctx.y0 - reach, cell)..Math.floorDiv(ctx.y1 + reach, cell))
            for (gx in Math.floorDiv(ctx.x0 - reach, cell)..Math.floorDiv(ctx.x1 + reach, cell)) {
                val x = gx * cell + reach + Hash.int(seed, gx, gy, salt, cell - 2 * reach)
                val y = gy * cell + reach + Hash.int(seed, gx, gy, salt + 1, cell - 2 * reach)
                each(x, y, gx, gy)
            }
    }

    /** A cathedral mound: a fluted main spire and one or two lesser ones, tapering from a broad foot. */
    private fun mound(ctx: MicroGenContext, x: Int, y: Int, base: Int, s: Long, clay: Short) {
        val height = 10 + (s and 7L).toInt() + ((s ushr 3) and 3L).toInt() * 2
        if (!ctx.overlaps(x - 8, y - 8, base - 1, x + 8, y + 8, base + height + 1)) return
        spire(ctx, x, y, base, height, 3.6f + ((s ushr 6) and 1L).toInt(), s, clay)
        val extra = 1 + ((s ushr 8) and 1L).toInt()
        repeat(extra) { i ->
            val a = i * 2.4f + ((s ushr 10) and 7L) * 0.8f
            spire(ctx, x + (cos(a) * 3f).toInt(), y + (sin(a) * 3f).toInt(), base, height * 3 / 5, 2.4f, s + i, clay)
        }
    }

    private fun spire(ctx: MicroGenContext, x: Int, y: Int, base: Int, height: Int, foot: Float, s: Long, clay: Short) {
        for (z in base - 1..base + height) {
            val t = (z - base).coerceAtLeast(0).toFloat() / height
            val r = foot * (1f - t).coerceAtLeast(0f).let { sqrt(it) } + 0.4f
            val ri = r.toInt() + 1
            for (dy in -ri..ri) for (dx in -ri..ri) {
                // Flutes: the wall ripples a little around the spire.
                val flute = 0.35f * sin(kotlin.math.atan2(dy.toFloat(), dx.toFloat()) * 5f + z * 0.3f)
                if (sqrt((dx * dx + dy * dy).toFloat()) <= r + flute) ctx.place(x + dx, y + dy, z, clay)
            }
        }
    }

    /** Two to four rounded boulders, the lower ones sunk in the ground, the top one balanced on a narrow contact. */
    private fun tor(ctx: MicroGenContext, x: Int, y: Int, base: Int, s: Long, granite: Short, weathered: Short) {
        val count = 2 + (s and 3L).toInt().coerceAtMost(2)
        if (!ctx.overlaps(x - 12, y - 12, base - 4, x + 12, y + 12, base + 30)) return
        var z = base - 2
        var cx = x.toFloat(); var cy = y.toFloat()
        for (i in 0 until count) {
            val r = 7f - i * 1.6f + Hash.unit(s, i, 0, 0, 1) * 2f
            val squash = 0.75f + Hash.unit(s, i, 0, 0, 2) * 0.2f
            val bz = z + r * squash
            boulder(ctx, cx, cy, bz, r, squash, s + i, granite, weathered)
            z = (bz + r * squash - 1.5f).toInt()
            cx += (Hash.unit(s, i, 0, 0, 3) - 0.5f) * 3f; cy += (Hash.unit(s, i, 0, 0, 4) - 0.5f) * 3f
        }
    }

    private fun boulder(ctx: MicroGenContext, cx: Float, cy: Float, cz: Float, r: Float, squash: Float, s: Long, granite: Short, weathered: Short) {
        val ri = r.toInt() + 1
        val rz = (r * squash).toInt() + 1
        for (dz in -rz..rz) for (dy in -ri..ri) for (dx in -ri..ri) {
            val px = cx.toInt() + dx; val py = cy.toInt() + dy; val pz = cz.toInt() + dz
            val d = sqrt((dx * dx + dy * dy).toFloat() + (dz / squash) * (dz / squash))
            if (d > r - Hash.unit(s, px, py, pz) * 0.8f) continue
            // A darker weathered rind, and fresh grey where the top has spalled.
            ctx.place(px, py, pz, if (d > r - 1.2f && dz < rz - 2) weathered else granite)
        }
    }

    /**
     * A freestanding arch: a semicircular span of banded sandstone standing
     * on two legs that reach down to the ground wherever it lies.
     */
    private fun arch(ctx: MicroGenContext, x: Int, y: Int, base: Int, s: Long, surface: HeightFunction, bands: ShortArray) {
        val radius = 14f + (s and 7L).toInt()
        val thick = 5f
        val depth = 4f + ((s ushr 3) and 3L).toInt()
        val a = ((s ushr 6) and 7L) * 0.785f
        val reach = (radius + depth).toInt() + 2
        if (!ctx.overlaps(x - reach, y - reach, base - 10, x + reach, y + reach, base + radius.toInt() + 2)) return
        val ca = cos(a); val sa = sin(a)
        for (py in max(ctx.y0, y - reach)..min(ctx.y1, y + reach)) for (px in max(ctx.x0, x - reach)..min(ctx.x1, x + reach)) {
            val dx = (px - x).toFloat(); val dy = (py - y).toFloat()
            val along = dx * ca + dy * sa
            val across = -dx * sa + dy * ca
            if (abs(across) > depth / 2f || abs(along) > radius) continue
            val ground = floor(surface.heightAt(px, py)).toInt()
            for (pz in max(ctx.z0, ground)..min(ctx.z1, base + radius.toInt() + 1)) {
                val dz = (pz - base).toFloat()
                val d = sqrt(along * along + dz * dz)
                val inRing = dz >= 0f && d <= radius && d >= radius - thick
                val leg = dz < 0f && abs(along) >= radius - thick
                if (inRing || leg) ctx.set(px, py, pz, bands[Math.floorMod(pz / 3, bands.size)])
            }
        }
    }
}
