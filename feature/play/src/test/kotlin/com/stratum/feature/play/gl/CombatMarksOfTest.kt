package com.stratum.feature.play.gl

import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.scene.CombatMarkKind
import com.stratum.engine.scene.MarkShape
import com.stratum.engine.world.CombatSide
import com.stratum.engine.world.Projectile
import com.stratum.engine.world.Telegraph
import com.stratum.engine.world.TelegraphShape
import com.stratum.engine.world.Zone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CombatMarksOfTest {

    private val here = WorldPoint(3f, 4f, 5f)

    @Test
    fun `the 3D view is handed everything the 2D canvas draws, ground first`() {
        val marks = combatMarksOf(
            projectiles = listOf(Projectile(1, "s", "player", CombatSide.PLAYER, here, 1f, 0f, 10f, 8f, 0.2f, 0, 0, 0, true, color = 0xFF00FF00)),
            zones = listOf(Zone(2, "z", "m", CombatSide.MONSTERS, here, 2f, 3f, 1f, color = 0xFFFF0000)),
            telegraphs = listOf(Telegraph("m", "t", TelegraphShape.CONE, here, 4f, angleDegrees = 60f, aimX = 0f, aimY = -1f, progress = 0.4f, color = 0xFF0000FF, hostile = true)),
        )
        assertEquals(listOf(CombatMarkKind.ZONE, CombatMarkKind.TELEGRAPH, CombatMarkKind.PROJECTILE), marks.map { it.kind })
        val cone = marks[1]
        assertEquals(MarkShape.CONE, cone.shape)
        assertEquals(0.4f, cone.progress)
        assertEquals(-1f, cone.dirY)
        assertTrue(cone.hostile)
        val bolt = marks[2]
        assertEquals(1f, bolt.dirX)
        assertEquals(0xFF00FF00, bolt.color)
        assertEquals(here.z, bolt.z)
    }
}
