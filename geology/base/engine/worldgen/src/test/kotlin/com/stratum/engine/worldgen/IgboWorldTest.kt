package com.stratum.engine.worldgen

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldMarkerKind
import kotlin.test.Test
import kotlin.test.assertTrue

/** The built-in pack's world holds the catacombs and ruins it describes. */
class IgboWorldTest {

    @Test
    fun `the igbo world has catacombs with bosses and ruined mbari houses`() {
        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
        val generator = StratumWorldgen.factory(content.terrain.generatorId)
            .create(content.terrainContext(WorldConfig(seed = 20260922L))) as PipelineTerrainGenerator
        val markers = (-24..24).flatMap { y -> (-24..24).flatMap { x -> generator.markersIn(ChunkPos(x, y)) } }
        val bosses = markers.filter { it.kind == WorldMarkerKind.BOSS && it.sourceId == "igbo:lost_catacomb" }
        assertTrue(bosses.isNotEmpty(), "no catacomb within two dozen chunks of the origin")
        assertTrue(bosses.all { it.refId == "igbo:agbara_priest" })
        assertTrue(markers.any { it.sourceId == "igbo:mbari_ruin" }, "no Mbari ruin within two dozen chunks of the origin")
    }
}
