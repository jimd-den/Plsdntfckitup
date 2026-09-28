package com.stratum.engine.microvoxel

import com.stratum.engine.microvoxel.gen.CityPlanStage
import com.stratum.engine.microvoxel.gen.Fields
import com.stratum.engine.microvoxel.gen.LotUse
import com.stratum.engine.microvoxel.gen.MicroStage
import com.stratum.engine.microvoxel.gen.MicroWorldgen
import com.stratum.engine.microvoxel.gen.StageSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeneratorTest {

    private fun surfaceChunk(gen: com.stratum.engine.microvoxel.gen.MicroGenerator, cx: Int, cy: Int): MicroChunkPos {
        val h = gen.fields.require(Fields.SURFACE).heightAt(cx * 64 + 32, cy * 64 + 32).toInt()
        return MicroChunkPos(cx, cy, Math.floorDiv(h, 64))
    }

    @Test
    fun `a chunk is the same whatever was generated before it`() {
        val a = MicroWorldgen.preset(MicroWorldgen.MIXED, 42L)
        val b = MicroWorldgen.preset(MicroWorldgen.MIXED, 42L)
        val target = surfaceChunk(a, 3, -2)
        // Warm b's caches with other chunks, in a scrambled order.
        for (p in listOf(MicroChunkPos(9, 9, 1), MicroChunkPos(-4, 2, 2), MicroChunkPos(3, -1, target.z))) b.generate(p)
        assertContentEquals(a.generate(target).export(), b.generate(target).export())
    }

    @Test
    fun `different seeds give different worlds`() {
        val a = MicroWorldgen.preset(MicroWorldgen.WILDS, 1L)
        val b = MicroWorldgen.preset(MicroWorldgen.WILDS, 2L)
        val p = surfaceChunk(a, 0, 0)
        assertTrue(!a.generate(p).export().contentEquals(b.generate(p).export()))
    }

    @Test
    fun `terrain is continuous across chunk borders`() {
        val gen = MicroWorldgen.preset(MicroWorldgen.WILDS, 5L)
        val surface = gen.fields.require(Fields.SURFACE)
        var worst = 0f
        for (y in 0 until 256) {
            val d = kotlin.math.abs(surface.heightAt(63, y) - surface.heightAt(64, y))
            if (d > worst) worst = d
        }
        assertTrue(worst < 4f, "the ground jumped $worst microvoxels across a chunk border")
    }

    @Test
    fun `the city road network is continuous between regions`() {
        val gen = MicroWorldgen.preset(MicroWorldgen.CITY, 11L)
        val city = gen.fields.require(CityPlanStage.KEY)
        for (ry in -3..3) for (rx in -3..3) {
            val here = city.region(rx, ry)
            val west = city.region(rx - 1, ry)
            val hasWestBoulevard = here.roads.any { !it.alongX && it.rect.x0 < here.bounds.x0 && it.rect.x1 >= here.bounds.x0 }
            assertEquals(here.urban || west.urban, hasWestBoulevard, "region ($rx,$ry) west edge")
        }
    }

    @Test
    fun `every lot fronts a road and no lot sits on one`() {
        val gen = MicroWorldgen.preset(MicroWorldgen.CITY, 11L)
        val city = gen.fields.require(CityPlanStage.KEY)
        var lots = 0
        for (ry in -2..2) for (rx in -2..2) {
            val region = city.region(rx, ry)
            val roads = city.regionsTouching(region.bounds).flatMap { it.roads }
            for (lot in region.lots) {
                lots++
                assertTrue(roads.any { it.rect.intersects(lot.rect.grow(1)) }, "lot ${lot.rect} has no street")
                assertTrue(roads.none { it.rect.intersects(lot.rect) }, "lot ${lot.rect} overlaps a road")
            }
        }
        assertTrue(lots > 20, "the city preset should build a city; found $lots lots")
    }

    @Test
    fun `buildings stand in the city`() {
        val gen = MicroWorldgen.preset(MicroWorldgen.CITY, 11L)
        val city = gen.fields.require(CityPlanStage.KEY)
        val region = (-3..3).flatMap { ry -> (-3..3).map { rx -> city.region(rx, ry) } }.first { it.urban }
        val lot = region.lots.first { it.use == LotUse.BUILDING }
        val x = (lot.rect.x0 + lot.rect.x1) / 2; val y = (lot.rect.y0 + lot.rect.y1) / 2
        val world = MicroWorld(gen)
        val ground = gen.fields.require(Fields.SURFACE).heightAt(x, y).toInt()
        // Somewhere in the 20 voxels above the ground at the lot's centre there is a floor or a wall.
        val built = (ground + 1..ground + 20).map { world[x, y, it] }.filter { it != MaterialPalette.AIR }.toSet()
        val p = gen.palette
        assertTrue(built.any { it in setOf(p.id(M.TIMBER), p.id(M.CONCRETE), p.id(M.BRICK), p.id(M.PLASTER), p.id(M.PLASTER_BLUE)) }, "found only $built")
    }

    @Test
    fun `a custom stage plugs in by id`() {
        val registry = MicroWorldgen.stages.copy()
            .register("test:bedrock") { MicroStage { ctx -> if (ctx.z0 == 0) ctx.fill(ctx.x0, ctx.y0, 0, ctx.x1, ctx.y1, 0, 4) } }
        val gen = registry.build(1L, listOf(StageSpec("test:bedrock")))
        assertEquals(4.toShort(), gen.generate(MicroChunkPos(0, 0, 0))[5, 5, 0])
        assertEquals(listOf("test:bedrock"), gen.stageIds)
    }

    @Test
    fun `an unknown stage or a bad option fails loudly`() {
        assertFailsWith<IllegalArgumentException> { MicroWorldgen.build(1L, listOf(StageSpec("nope:nothing"))) }
        assertFailsWith<IllegalArgumentException> { MicroWorldgen.build(1L, listOf(StageSpec("micro:terrain", mapOf("seaLevel" to "deep")))) }
    }

    @Test
    fun `edits survive the chunk being dropped and regenerated, and are the whole save`() {
        val gen = MicroWorldgen.preset(MicroWorldgen.WILDS, 3L)
        val world = MicroWorld(gen)
        val z = gen.fields.require(Fields.SURFACE).heightAt(10, 10).toInt()
        world.set(10, 10, z + 5, gen.palette.id(M.LAMP))
        val dug = world.sphere(30, 30, z, 4f)
        val saved = world.exportEdits()
        val fresh = MicroWorld(gen)
        fresh.importEdits(saved)
        assertEquals(gen.palette.id(M.LAMP), fresh[10, 10, z + 5])
        assertEquals(1 + dug, saved.values.sumOf { it.size })
    }

    @Test
    fun `streaming loads nearest chunks first within the per-frame budget`() {
        val gen = MicroWorldgen.preset(MicroWorldgen.WILDS, 3L)
        val world = MicroWorld(gen, com.stratum.engine.microvoxel.mesh.QualityProfile.LOW)
        val loaded = world.stream(32, 32, gen.fields.require(Fields.SURFACE).heightAt(32, 32).toInt())
        assertEquals(1, loaded.size)
        assertEquals(0, loaded.first().x); assertEquals(0, loaded.first().y)
    }
}
