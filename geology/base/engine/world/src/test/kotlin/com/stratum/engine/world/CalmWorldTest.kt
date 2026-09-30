package com.stratum.engine.world

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldRules
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Calm means calm: a hero who wanders a microvoxel world and never fights
 * back should not be run down on the calm monster setting.
 */
class CalmWorldTest {

    private val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
        .let { it.copy(terrain = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL)) }

    /** Damage taken and deaths over [seconds] of wandering, never attacking. */
    private fun wander(monsters: Float, seconds: Int = 180, seed: Long = 1234567L): Pair<Int, Int> {
        val config = WorldConfig(seed = seed, simulationRadius = 3, rules = WorldRules().withMonsters(monsters))
        val session = WorldSession(content, config)
        var heading = 0f; var last = session.player.position
        var taken = 0; var deaths = 0
        var health = session.player.health
        val dt = 1f / 20f
        for (frame in 0 until seconds * 20) {
            if (frame % 10 == 0) {
                val p = session.player.position
                if (kotlin.math.abs(p.x - last.x) + kotlin.math.abs(p.y - last.y) < 0.4f) heading += 1.9f
                last = p
            }
            session.setMoveInput(cos(heading), sin(heading))
            session.tick(dt)
            val now = session.player.health
            if (now < health) taken += health - now
            if (!session.player.isAlive) { deaths++; session.revive() }
            health = session.player.health
        }
        return taken to deaths
    }

    @Test
    fun `a calm world lets a wandering hero live`() {
        val (calmDamage, calmDeaths) = wander(0.6f)
        val (normalDamage, normalDeaths) = wander(1f)
        assertTrue(calmDeaths == 0, "a hero who never fights back died $calmDeaths times in three calm minutes ($calmDamage damage)")
        assertTrue(calmDamage < normalDamage || calmDamage < 100, "calm ($calmDamage) is no gentler than normal ($normalDamage, $normalDeaths deaths)")
    }
}
