package com.stratum.engine.world

import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.actor.TerrainChange
import com.stratum.core.domain.attack.AttackCompiler
import com.stratum.core.domain.attack.AttackVocabulary
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards what an attack's voxel payload costs the game thread.
 *
 * A fight with forged attacks digs, raises, burns and freezes ground every
 * few frames, all on the thread that runs the session. Each change must cost
 * its handful of blocks, not a rescan of every chunk it touches.
 */
class TerrainImpactCostProbe {

    private fun world(): MutableWorld {
        val base = CombatCoreFixtures.pack()
        val forged = AttackCompiler.pack(emptyList(), AttackVocabulary.from(base.damageTypes))
        val pack = base.copy(blocks = base.blocks + forged.blocks)
        return CombatCoreFixtures.session(pack, rules = CombatCoreFixtures.unbound()).world as MutableWorld
    }

    @Test
    fun `a thousand voxel payloads cost the game thread a few milliseconds`() {
        val world = world()
        val impacts = TerrainImpacts(world)
        val r = Random(5)
        val changes = TerrainChange.entries
        fun storm(n: Int) = repeat(n) {
            val at = WorldPoint(r.nextFloat() * 24f - 4f, r.nextFloat() * 24f - 4f, 0f)
            impacts.apply(SkillEffect.Terrain(changes[it % changes.size], radius = 2.5f, seconds = 0.5f), at, Aim(1f, 0f))
            impacts.advance(0.05f)
        }
        storm(300) // warm up the JIT
        val start = System.nanoTime()
        storm(1000)
        val ms = (System.nanoTime() - start) / 1e6
        println("1000 voxel payloads: ${"%.1f".format(ms)} ms (${"%.1f".format(ms)} µs each)")
        assertTrue(ms < 400.0, "voxel payloads are too slow for the game thread: $ms ms per thousand")
    }
}
