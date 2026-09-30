package com.stratum.engine.microvoxel.arch

import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.gen.Hash
import com.stratum.engine.microvoxel.gen.MicroGenContext
import com.stratum.engine.microvoxel.gen.TreesStage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What stands at the heart of a town (see [Sacred]), drawn around a point.
 * Each keeps within [RADIUS] microvoxels of it, and draws only the part
 * inside the chunk it is asked for.
 */
object Monuments {
    const val RADIUS = 12

    fun draw(ctx: MicroGenContext, sacred: Sacred, x: Int, y: Int, base: Int, seed: Long) {
        if (!ctx.overlaps(x - RADIUS, y - RADIUS, base - 2, x + RADIUS, y + RADIUS, base + 48)) return
        val p = ctx.palette
        when (sacred) {
            Sacred.NONE -> Unit
            Sacred.IROKO -> TreesStage.plant(ctx, TreesStage.Kind.IROKO, x, y, base, seed)
            Sacred.BAOBAB -> TreesStage.plant(ctx, TreesStage.Kind.BAOBAB, x, y, base, seed)
            Sacred.DATE_PALM -> {
                well(ctx, x + 5, y, base, p.id(A.PISE), p.id(M.WATER))
                TreesStage.plant(ctx, TreesStage.Kind.DATE_PALM, x - 3, y - 2, base, seed)
            }
            Sacred.CONICAL_TOWER -> conicalTower(ctx, x, y, base, p.id(A.DRYSTONE), p.id(A.DRYSTONE_DARK))
            Sacred.STELE -> stele(ctx, x, y, base, p.id(A.AKSUM_STONE))
            Sacred.PILLAR_TOMB -> pillarTomb(ctx, x, y, base, p.id(A.LIME), p.id(A.CORAL_BLOCK))
            Sacred.KRAAL -> kraal(ctx, x, y, base, p.id(A.THORN), seed)
            Sacred.TOGUNA -> toguna(ctx, x, y, base, p.id(A.POST), p.id(A.MILLET_THATCH), p.id(M.THATCH_DARK))
        }
    }

    private fun each(ctx: MicroGenContext, x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, f: (Int, Int, Int) -> Short?) {
        for (z in max(z0, ctx.z0)..min(z1, ctx.z1)) for (y in max(y0, ctx.y0)..min(y1, ctx.y1)) for (x in max(x0, ctx.x0)..min(x1, ctx.x1)) {
            f(x, y, z)?.let { ctx.set(x, y, z, it) }
        }
    }

    /** The Great Enclosure's conical tower: solid dry stone, tapering, with a dentelle band near the top. */
    private fun conicalTower(ctx: MicroGenContext, x: Int, y: Int, base: Int, stone: Short, dark: Short) {
        val height = 34
        each(ctx, x - 7, y - 7, base, x + 7, y + 7, base + height) { px, py, pz ->
            val t = (pz - base).toFloat() / height
            val r = 6.5f - t * 3f
            val d = sqrt(((px - x) * (px - x) + (py - y) * (py - y)).toFloat())
            if (d > r) null
            else if (pz in base + height - 6..base + height - 4 && d > r - 1 && (px + py + pz) % 2 == 0) dark
            else stone
        }
    }

    /** An Aksumite stele: a tall slab carved with storeys of false windows and a false door, on a stone base. */
    private fun stele(ctx: MicroGenContext, x: Int, y: Int, base: Int, stone: Short) {
        val height = 40
        each(ctx, x - 5, y - 4, base, x + 5, y + 4, base + 1) { _, _, _ -> stone } // the base plate
        each(ctx, x - 3, y - 1, base + 2, x + 3, y + 1, base + height) { px, py, pz ->
            val h = pz - base - 2
            val top = height - 2
            // A rounded head.
            if (h > top - 3 && abs(px - x) > (top + 1 - h)) return@each null
            // The south face: a false door at the foot, then storeys of paired false windows under beam lines.
            if (py == y + 1) {
                if (h in 1..6 && abs(px - x) <= 1) return@each null
                val storey = (h - 8) % 5
                if (h >= 8 && h < top - 4 && storey in 1..2 && (px == x - 2 || px == x + 2)) return@each null
            }
            stone
        }
    }

    /** A Swahili pillar tomb: a walled platform and a tall lime pillar, banded with coral. */
    private fun pillarTomb(ctx: MicroGenContext, x: Int, y: Int, base: Int, lime: Short, coral: Short) {
        each(ctx, x - 6, y - 6, base, x + 6, y + 6, base + 3) { px, py, pz ->
            val edge = max(abs(px - x), abs(py - y))
            if (edge == 6 || pz == base) lime else null
        }
        each(ctx, x - 1, y - 1, base, x + 1, y + 1, base + 28) { _, _, pz -> if (pz % 7 == 6) coral else lime }
    }

    /** A cattle kraal: a ring of thorn with a gate. */
    private fun kraal(ctx: MicroGenContext, x: Int, y: Int, base: Int, thorn: Short, seed: Long) {
        each(ctx, x - 9, y - 9, base, x + 9, y + 9, base + 4) { px, py, pz ->
            val d = sqrt(((px - x) * (px - x) + (py - y) * (py - y)).toFloat())
            if (d !in 7.2f..8.8f) return@each null
            val angle = atan2((py - y).toFloat(), (px - x).toFloat())
            if (abs(angle - 1.5708f) < 0.3f) return@each null // the gate
            if (Hash.unit(seed, px, py, pz, 5) < 0.2f + (pz - base) * 0.12f) null else thorn
        }
    }

    /** A Dogon toguna: a low, thick roof of millet stalks on carved pillars, too low to stand under, so anger stays seated. */
    private fun toguna(ctx: MicroGenContext, x: Int, y: Int, base: Int, post: Short, millet: Short, dark: Short) {
        for (px in intArrayOf(x - 5, x, x + 5)) for (py in intArrayOf(y - 4, y + 4)) {
            each(ctx, px, py, base, px + 1, py + 1, base + 6) { _, _, _ -> post }
        }
        each(ctx, x - 8, y - 7, base + 7, x + 9, y + 8, base + 12) { px, py, pz ->
            val shrink = pz - (base + 7)
            if (px < x - 8 + shrink / 2 || px > x + 9 - shrink / 2 || py < y - 7 + shrink / 2 || py > y + 8 - shrink / 2) null
            else if ((pz + px) % 3 == 0) dark else millet
        }
    }

    private fun well(ctx: MicroGenContext, x: Int, y: Int, base: Int, wall: Short, water: Short) {
        each(ctx, x - 2, y - 2, base - 3, x + 2, y + 2, base + 1) { px, py, pz ->
            val edge = max(abs(px - x), abs(py - y))
            when {
                edge == 2 && pz >= base - 1 -> wall
                edge < 2 && pz < base - 1 -> water
                edge < 2 -> ctx.palette.let { com.stratum.engine.microvoxel.MaterialPalette.AIR }
                else -> null
            }
        }
    }
}
