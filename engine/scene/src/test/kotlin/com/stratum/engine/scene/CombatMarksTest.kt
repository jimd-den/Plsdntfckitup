package com.stratum.engine.scene

import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CombatMarksTest {

    private val director = StyleSheetArtDirector(StyleLexicon.interpret("dark").direction)

    private val world = object : World {
        override val registry = BlockRegistry.build(emptyList())
        override val loadedChunks: Collection<Chunk> = emptyList()
        override fun chunkAt(pos: ChunkPos) = null
        override fun blockAt(pos: BlockPos) = BlockType.AIR
        override fun blockIndexAt(pos: BlockPos) = 0
        override fun lightAt(pos: BlockPos) = 15
        override fun surfaceAt(x: Int, y: Int) = -1
        override fun isLoaded(pos: ChunkPos) = true
    }

    private val camera = SceneCamera(Vec3(0f, 0f, 0f))

    private fun frame(vararg marks: CombatMark) = SceneBuilder(director, TextureLibrary()).build(world, camera, marks = marks.toList())

    @Test
    fun `a projectile flies as a lit glow with a trail`() {
        val quiet = frame()
        val flying = frame(CombatMark(CombatMarkKind.PROJECTILE, 1f, 1f, 1f, 0.2f, 0xFFFF8800, dirX = 1f, dirY = 0f))
        assertTrue(flying.glows.triangleCount >= quiet.glows.triangleCount + 2 * (2 + 4))
        assertEquals(quiet.decals.triangleCount, flying.decals.triangleCount, "nothing on the ground under a projectile")
        assertTrue(flying.lights.size > quiet.lights.size)
    }

    @Test
    fun `zones and telegraphs are marked on the ground, in every shape`() {
        val quiet = frame()
        listOf(
            CombatMark(CombatMarkKind.ZONE, 0f, 0f, 0f, 2f, 0xFF44AAFF),
            CombatMark(CombatMarkKind.TELEGRAPH, 0f, 0f, 0f, 2f, 0xFF44AAFF, progress = 0.5f, hostile = true),
            CombatMark(CombatMarkKind.TELEGRAPH, 0f, 0f, 0f, 5f, 0xFF44AAFF, progress = 0.5f, shape = MarkShape.LANE, halfWidth = 0.5f),
            CombatMark(CombatMarkKind.TELEGRAPH, 0f, 0f, 0f, 4f, 0xFF44AAFF, progress = 0.5f, shape = MarkShape.CONE, angleDegrees = 90f),
        ).forEach { mark ->
            val marked = frame(mark)
            assertTrue(marked.decals.triangleCount > quiet.decals.triangleCount, "$mark")
        }
    }

    @Test
    fun `a telegraph fills as it winds up`() {
        fun filled(progress: Float) = frame(
            CombatMark(CombatMarkKind.TELEGRAPH, 0f, 0f, 0f, 6f, 0xFF44AAFF, progress = progress, shape = MarkShape.LANE, halfWidth = 0.5f),
        ).decals.let { batch ->
            // The occlusion slot of a decal carries its opacity.
            (0 until batch.vertexCount).map { batch.vertices[it * Vertex.STRIDE + Vertex.AO] }.count { it >= 0.4f }
        }
        assertTrue(filled(0.9f) > filled(0.1f))
    }

    @Test
    fun `a forged attack draws in its own look, and two looks draw differently`() {
        fun look(seed: Long) = com.stratum.core.domain.attack.AttackForge.roll(seed).look
        fun glows(mark: CombatMark) = frame(mark).let { f -> f.glows.vertices.copyOf(f.glows.vertexCount * Vertex.STRIDE).toList() + f.decals.vertices.copyOf(f.decals.vertexCount * Vertex.STRIDE).toList() }
        val plain = CombatMark(CombatMarkKind.PROJECTILE, 1f, 1f, 1f, 0.2f, 0xFFFF8800, dirX = 1f, dirY = 0f)
        val a = plain.copy(look = look(1)); val b = plain.copy(look = look(2))
        assertTrue(frame(a).glows.triangleCount > 0)
        assertTrue(glows(a) != glows(b), "two looks drew the same")
        assertTrue(glows(a) != glows(plain))
        val zone = CombatMark(CombatMarkKind.ZONE, 0f, 0f, 0f, 2f, 0xFF44AAFF)
        assertTrue(frame(zone.copy(look = look(3))).decals.triangleCount >= frame(zone).decals.triangleCount)
    }
}
