package com.stratum.engine.world

import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.actor.TerrainChange
import com.stratum.core.domain.attack.AttackCompiler
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What attacks do to the blocks: the voxel half of a payload.
 *
 * - A crater takes the top of the ground away where it lands, a shallow bowl
 *   deepest at its centre. Only natural ground goes: soil, stone, ore and
 *   brush; nothing built of wood, metal, cloth or ritual stone.
 * - A wall rises across the attack's path, two blocks high, and crumbles
 *   after its time.
 * - Fire burns the brush and grass around it away.
 * - Frost turns the water's surface to ice that can be walked on, and it
 *   thaws after its time.
 *
 * Walls and ice are temporary: each remembers what it replaced and puts it
 * back when it runs out, unless something else has changed that block since.
 */
internal class TerrainImpacts(private val world: MutableWorld) {

    private class Temporary(val pos: BlockPos, val placed: Int, val previous: Int, var left: Float)

    private val temporary = ArrayList<Temporary>()
    private val registry: BlockRegistry get() = world.registry

    /** Blocks changed so far, for tests and a map. */
    val standing: Int get() = temporary.size

    fun apply(effect: SkillEffect.Terrain, at: WorldPoint, aim: Aim) {
        when (effect.change) {
            TerrainChange.CRATER -> crater(at, effect.radius)
            TerrainChange.WALL -> wall(at, aim, effect.radius, effect.seconds)
            TerrainChange.IGNITE -> ignite(at, effect.radius)
            TerrainChange.FREEZE -> freeze(at, effect.radius, effect.seconds)
        }
    }

    /** Lets temporary walls and ice run out; each puts back what it replaced. */
    fun advance(deltaSeconds: Float) {
        if (temporary.isEmpty()) return
        val done = ArrayList<Temporary>()
        temporary.forEach { t ->
            t.left -= deltaSeconds
            if (t.left <= 0f) done += t
        }
        done.forEach(::revert)
        temporary.removeAll(done.toSet())
    }

    /** Puts every temporary block back at once: when the fight is reset or the world saved. */
    fun clear() {
        temporary.forEach(::revert)
        temporary.clear()
    }

    private fun revert(t: Temporary) {
        if (world.blockIndexAt(t.pos) == t.placed) world.setBlock(t.pos, t.previous)
    }

    private fun columns(at: WorldPoint, radius: Float, each: (x: Int, y: Int, distance: Float) -> Unit) {
        val r = ceil(radius).toInt()
        val cx = floor(at.x).toInt(); val cy = floor(at.y).toInt()
        for (dx in -r..r) for (dy in -r..r) {
            val d = sqrt((dx * dx + dy * dy).toFloat())
            if (d <= radius) each(cx + dx, cy + dy, d)
        }
    }

    private fun crater(at: WorldPoint, radius: Float) = columns(at, radius) { x, y, d ->
        val surface = world.surfaceAt(x, y)
        if (surface < 2) return@columns
        // Deepest at the centre, a block at the rim.
        val depth = (1 + ((radius - d) / radius * 1.5f).roundToInt()).coerceIn(1, 2)
        for (z in surface downTo surface - depth + 1) {
            val pos = BlockPos(x, y, z)
            val block = world.blockAt(pos)
            if (block.isAir || !block.isBreakable || block.material !in NATURAL) break
            world.setBlock(pos, BlockRegistry.AIR_INDEX)
        }
    }

    private fun wall(at: WorldPoint, aim: Aim, radius: Float, seconds: Float) {
        val index = registry.indexOrNull(AttackCompiler.WALL_BLOCK) ?: return
        // Square across the attack's path.
        val px = -aim.dy; val py = aim.dx
        val half = radius.roundToInt().coerceAtLeast(1)
        val seen = HashSet<Pair<Int, Int>>()
        for (t in -half..half) {
            val x = floor(at.x + px * t).toInt(); val y = floor(at.y + py * t).toInt()
            if (!seen.add(x to y)) continue
            val surface = world.surfaceAt(x, y)
            if (surface < 0) continue
            for (z in surface + 1..surface + WALL_HEIGHT) place(BlockPos(x, y, z), index, seconds) { it.isAir }
        }
    }

    private fun ignite(at: WorldPoint, radius: Float) = columns(at, radius) { x, y, _ ->
        val surface = world.surfaceAt(x, y)
        if (surface < 0) return@columns
        // Brush stands on the ground: burn it from the top of the column down to the soil.
        for (z in surface downTo (surface - 3).coerceAtLeast(1)) {
            val pos = BlockPos(x, y, z)
            val block = world.blockAt(pos)
            if (block.material == BlockMaterial.FOLIAGE) world.setBlock(pos, BlockRegistry.AIR_INDEX) else if (!block.isAir) break
        }
    }

    private fun freeze(at: WorldPoint, radius: Float, seconds: Float) {
        val index = registry.indexOrNull(AttackCompiler.ICE_BLOCK) ?: return
        columns(at, radius) { x, y, _ ->
            val surface = world.surfaceAt(x, y)
            if (surface < 0) return@columns
            place(BlockPos(x, y, surface), index, seconds) { it.material == BlockMaterial.LIQUID }
        }
    }

    private fun place(pos: BlockPos, index: Int, seconds: Float, allowed: (com.stratum.core.domain.world.BlockType) -> Boolean) {
        if (temporary.size >= MAX_TEMPORARY) return
        val before = world.blockIndexAt(pos)
        if (!allowed(registry.typeOf(before))) return
        if (world.setBlock(pos, index)) temporary += Temporary(pos, index, before, seconds)
    }

    companion object {
        const val WALL_HEIGHT = 2
        /** At most this many temporary blocks stand at once, however many walls are raised. */
        const val MAX_TEMPORARY = 512
        private val NATURAL = setOf(BlockMaterial.SOIL, BlockMaterial.STONE, BlockMaterial.ORE, BlockMaterial.FOLIAGE)
    }
}
