package com.stratum.engine.microvoxel

import com.stratum.engine.microvoxel.gen.CityPlanStage
import com.stratum.engine.microvoxel.gen.Fields
import com.stratum.engine.microvoxel.gen.MicroWorldgen
import com.stratum.engine.microvoxel.mesh.BinaryGreedyMesher
import com.stratum.engine.microvoxel.mesh.Lod
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps the microvoxel world affordable on a low-end phone.
 *
 * Memory and quad counts are deterministic, so they are asserted tightly;
 * time is asserted only loosely, to catch a stage that got ten times slower
 * rather than to benchmark.
 */
class MicroBudgetTest {

    private val gen = MicroWorldgen.preset(MicroWorldgen.CITY, 11L)
    private val city = gen.fields.require(CityPlanStage.KEY)
    private val region = (-3..3).flatMap { ry -> (-3..3).map { rx -> city.region(rx, ry) } }.first { it.urban }
    private val cx = Math.floorDiv((region.bounds.x0 + region.bounds.x1) / 2, 64)
    private val cy = Math.floorDiv((region.bounds.y0 + region.bounds.y1) / 2, 64)

    private fun downtownChunks(): List<MicroChunk> {
        val world = MicroWorld(gen)
        return (-1..1).flatMap { dy -> (-1..1).flatMap { dx -> world.verticalRange(cx + dx, cy + dy).map { MicroChunkPos(cx + dx, cy + dy, it) } } }
            .map { world.chunk(it) }
    }

    @Test
    fun `brick storage keeps a downtown chunk far below a flat array`() {
        val chunks = downtownChunks().filter { !it.isEmpty() }
        val avg = chunks.sumOf { it.approximateBytes() } / chunks.size
        assertTrue(avg < 160 * 1024, "average non-empty chunk costs ${avg / 1024} KB; a flat array is 512 KB")
    }

    @Test
    fun `greedy meshing and lod cut quads by large factors`() {
        val mesher = BinaryGreedyMesher(gen.palette)
        val chunks = downtownChunks().filter { !it.isEmpty() }
        var faces = 0L; var full = 0L; var quarter = 0L
        for (c in chunks) {
            val m = mesher.mesh(c)
            faces += m.area(); full += m.count
            quarter += mesher.mesh(Lod.downsample(c, 4, gen.palette)).count
        }
        assertTrue(faces > full * 3, "greedy merging saved too little: $faces faces -> $full quads")
        assertTrue(full > quarter * 4, "block-resolution LOD saved too little: $full -> $quarter quads")
    }

    @Test
    fun `a surface chunk generates well inside a streaming budget`() {
        val surface = gen.fields.require(Fields.SURFACE)
        val world = MicroWorld(gen)
        world.chunk(MicroChunkPos(cx + 5, cy + 5, Math.floorDiv(surface.heightAt((cx + 5) * 64, (cy + 5) * 64).toInt(), 64))) // warm up
        val start = System.nanoTime()
        var n = 0
        for (d in 0 until 6) {
            val x = cx - 3 + d; val y = cy + 2
            world.chunk(MicroChunkPos(x, y, Math.floorDiv(surface.heightAt(x * 64 + 32, y * 64 + 32).toInt(), 64))); n++
        }
        val ms = (System.nanoTime() - start) / 1e6 / n
        assertTrue(ms < 400.0, "a surface chunk took $ms ms to generate")
    }
}
