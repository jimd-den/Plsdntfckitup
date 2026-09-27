package com.stratum.engine.world

import com.stratum.core.domain.content.VoxelBlueprint
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintRaiseTest {

    @Test
    fun `a blueprint rises in front of the player, on the ground, and changes the world`() {
        val session = WorldSession(TestContent.assembled, WorldConfig(seed = 7L, simulationRadius = 1))
        val block = TestContent.assembled.registry.all.first { !it.isAir && it.isSolid && it.glyph == null }.id
        val pillar = VoxelBlueprint("bp:pillar", "Pillar", 1, 1, 3, listOf(block), intArrayOf(1, 1, 1))
        val before = session.snapshot().worldRevision

        val placed = session.raise(pillar)

        assertEquals(3, placed)
        assertTrue(session.snapshot().worldRevision != before, "the renderer would not see the change")
        assertTrue(session.world.blockAt(session.player.feet).isAir, "the pillar was raised on the player")
    }

    @Test
    fun `blocks the packs do not define are skipped`() {
        val session = WorldSession(TestContent.assembled, WorldConfig(seed = 7L, simulationRadius = 1))
        val unknown = VoxelBlueprint("bp:x", "X", 1, 1, 1, listOf("nobody:nothing"), intArrayOf(1))
        assertEquals(0, session.raise(unknown))
    }
}
