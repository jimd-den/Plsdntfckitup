package com.stratum.engine.scene

import com.stratum.core.domain.art.CombatCue
import com.stratum.core.domain.art.CombatMoment
import com.stratum.core.domain.art.EffectKind
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EffectTrackTest {

    private val director = StyleSheetArtDirector(StyleLexicon.interpret("dark").direction)

    @Test
    fun `a critical hit plays, shakes the camera, and is gone once it has played`() {
        val track = EffectTrack(director)
        track.play(CombatCue(CombatMoment.CRITICAL, emphasis = 1f), 3f, 4f, 5f)
        val kinds = track.active.map { it.effect.kind }.toSet()
        assertTrue(EffectKind.IMPACT_RING in kinds && EffectKind.DEBRIS in kinds)
        assertTrue(EffectKind.NUMBER !in kinds, "numbers are the overlay's")
        track.advance(0.02f)
        assertTrue(track.shake().length() > 0f, "a crit shoves the camera")
        track.advance(2f)
        assertTrue(track.active.isEmpty())
        assertEquals(0f, track.shake().length())
    }

    @Test
    fun `the oldest effects give way when a fight gets crowded`() {
        val track = EffectTrack(director, capacity = 5)
        repeat(10) { track.play(CombatCue(CombatMoment.HIT), it.toFloat(), 0f, 0f) }
        assertEquals(5, track.active.size)
        assertEquals(9f, track.active.last().x)
    }

    @Test
    fun `effects become light and decals in the frame, and light the ground`() {
        val world = object : com.stratum.core.domain.world.World {
            override val registry = com.stratum.core.domain.world.BlockRegistry.build(emptyList())
            override val loadedChunks: Collection<com.stratum.core.domain.world.Chunk> = emptyList()
            override fun chunkAt(pos: com.stratum.core.domain.world.ChunkPos) = null
            override fun blockAt(pos: com.stratum.core.domain.world.BlockPos) = com.stratum.core.domain.world.BlockType.AIR
            override fun blockIndexAt(pos: com.stratum.core.domain.world.BlockPos) = 0
            override fun lightAt(pos: com.stratum.core.domain.world.BlockPos) = 15
            override fun surfaceAt(x: Int, y: Int) = -1
            override fun isLoaded(pos: com.stratum.core.domain.world.ChunkPos) = true
        }
        val builder = SceneBuilder(director, TextureLibrary())
        val camera = SceneCamera(Vec3(0f, 0f, 0f))
        val quiet = builder.build(world, camera)
        val track = EffectTrack(director)
        CombatMoment.entries.forEach { track.play(CombatCue(it, emphasis = 1f), 0f, 0f, 0f) }
        track.advance(0.05f)
        val loud = builder.build(world, camera, effects = track.active)
        assertTrue(loud.glows.triangleCount > quiet.glows.triangleCount + 40)
        assertTrue(loud.decals.triangleCount > quiet.decals.triangleCount)
        assertTrue(loud.lights.size > quiet.lights.size, "a hit lights its surroundings")
    }
}
