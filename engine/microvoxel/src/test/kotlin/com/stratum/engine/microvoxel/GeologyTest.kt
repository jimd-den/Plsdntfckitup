package com.stratum.engine.microvoxel

import com.stratum.engine.microvoxel.gen.Fields
import com.stratum.engine.microvoxel.gen.MicroGenerator
import com.stratum.engine.microvoxel.gen.MicroWorldgen
import com.stratum.engine.microvoxel.gen.StageSpec
import com.stratum.engine.microvoxel.geo.GeoAtlas
import com.stratum.engine.microvoxel.geo.Provinces
import com.stratum.engine.microvoxel.geo.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Africa's geology, as the terrain stage builds it. */
class GeologyTest {

    private val seed = 20260928L
    private fun world(geology: String, vararg more: StageSpec): MicroGenerator = MicroWorldgen.build(
        seed,
        listOf(StageSpec("micro:terrain", mapOf("geology" to geology, "seaLevel" to "48", "maxHeight" to "160", "spawnRise" to "0"))) + more,
    )

    @Test
    fun `the catalogue is whole and every province resolves against the palette`() {
        val provinces = Provinces.all(seed)
        assertEquals(20, provinces.size)
        assertEquals(provinces.size, provinces.map { it.id }.toSet().size, "province ids repeat")
        val atlas = world("africa").fields.require(Fields.GEOLOGY) as GeoAtlas
        for (p in atlas.resolved) {
            assertTrue(p.bedding.isNotEmpty() && p.province.places.isNotBlank(), "${p.province.id} is incomplete")
            assertTrue(p.province.fertility in 0f..1f, "${p.province.id} fertility")
        }
    }

    @Test
    fun `a continent-sized walk meets every kind of country`() {
        val atlas = world("africa").fields.require(Fields.GEOLOGY)
        val seen = HashSet<String>()
        val span = 400_000
        for (j in 0 until 120) for (i in 0 until 120) seen += atlas.provinceAt(-span / 2 + i * span / 120, -span / 2 + j * span / 120).id
        val missing = Provinces.all(seed).map { it.id } - seen
        assertTrue(missing.isEmpty(), "never found: $missing")
    }

    @Test
    fun `every province stays inside the block world's column and never breaks`() {
        for (p in Provinces.all(seed)) {
            val surface = world(p.id).fields.require(Fields.SURFACE)
            for (j in -20..20) for (i in -20..20) {
                val h = surface.heightAt(i * 97, j * 89)
                assertTrue(h.isFinite() && h in 2f..160f, "${p.id}: height $h at ${i * 97},${j * 89}")
            }
        }
    }

    @Test
    fun `one province everywhere, and the home region is the one asked for`() {
        val only = world(Provinces.ERG).fields.require(Fields.GEOLOGY)
        for (k in 0 until 50) assertEquals(Provinces.ERG, only.provinceAt(k * 3001 - 70_000, k * 1777 - 40_000).id)
        val homed = MicroWorldgen.build(seed, listOf(StageSpec("micro:terrain", mapOf("geology" to "africa", "home" to Provinces.RIFT))))
        assertEquals(Provinces.RIFT, homed.fields.require(Fields.GEOLOGY).provinceAt(0, 0).id)
    }

    @Test
    fun `a sandstone cliff shows its bedding, band over band`() {
        val gen = world(Provinces.SANDSTONE_ESCARPMENT)
        val surface = gen.fields.require(Fields.SURFACE)
        val world = MicroWorld(gen)
        // Find a column at the foot of the cliff and read the wall beside it.
        var best = 0 to 0; var drop = 0f
        for (j in -40..40) for (i in -40..40) {
            val x = i * 23; val y = j * 23
            val d = surface.heightAt(x + 8, y) - surface.heightAt(x, y)
            if (d > drop) { drop = d; best = x to y }
        }
        assertTrue(drop > 20f, "no escarpment found (steepest step $drop)")
        val (x, y) = best
        val wallX = x + 8
        val low = surface.heightAt(x, y).toInt(); val high = surface.heightAt(wallX, y).toInt()
        val bands = HashSet<Short>()
        for (z in low + 2 until high - 2) {
            val pos = MicroChunkPos.containing(wallX, y, z)
            bands += world.chunk(pos)[wallX - pos.originX, y - pos.originY, z - pos.originZ]
        }
        val rocks = setOf(R.SANDSTONE_RED, R.SANDSTONE_BUFF, R.SANDSTONE_PALE, R.IRONSTONE).map { gen.palette.id(it) }.toSet()
        assertTrue(bands.count { it in rocks } >= 2, "the cliff is one colour: ${bands.map { gen.palette[it].name }}")
    }

    @Test
    fun `sand seas and salt pans are bare, forests are green`() {
        val erg = world(Provinces.ERG, StageSpec("micro:groundcover"), StageSpec("micro:trees", mapOf("style" to "tropical")))
        val forest = world(Provinces.RAINFOREST_BASIN, StageSpec("micro:groundcover"), StageSpec("micro:trees", mapOf("style" to "tropical")))
        fun leafy(gen: MicroGenerator): Int {
            val world = MicroWorld(gen)
            val leaves = setOf(gen.palette.id(M.LEAVES), gen.palette.id(M.PALM), gen.palette.id(M.GRASS))
            // A dry 3 x 3 chunk patch: well above the sea at its centre.
            val surface = gen.fields.require(Fields.SURFACE)
            val (ox, oy) = (0 until 400).map { k -> (k % 20 - 10) * 3 to (k / 20 - 10) * 3 }
                .first { (cx, cy) -> surface.heightAt(cx * 64 + 32, cy * 64 + 32) > 60f }
            var n = 0
            for (cy in oy..oy + 2) for (cx in ox..ox + 2) for (cz in world.verticalRange(cx, cy)) {
                val c = world.chunk(MicroChunkPos(cx, cy, cz))
                if (c.isEmpty()) continue
                for (z in 0 until 64 step 2) for (y in 0 until 64 step 2) for (x in 0 until 64 step 2) if (c[x, y, z] in leaves) n++
            }
            return n
        }
        val sand = leafy(erg); val green = leafy(forest)
        assertTrue(green > sand * 20, "rainforest $green green voxels against the erg's $sand")
    }

    @Test
    fun `geology generates a surface chunk well inside a streaming budget`() {
        val gen = MicroWorldgen.preset(MicroWorldgen.AFRICA, seed)
        val world = MicroWorld(gen)
        val surface = gen.fields.require(Fields.SURFACE)
        fun at(x: Int, y: Int) = MicroChunkPos(x, y, Math.floorDiv(surface.heightAt(x * 64 + 32, y * 64 + 32).toInt(), 64))
        world.chunk(at(40, 40)) // warm up
        val start = System.nanoTime()
        for (d in 0 until 8) world.chunk(at(d * 3, 7))
        val ms = (System.nanoTime() - start) / 1e6 / 8
        assertTrue(ms < 400.0, "a surface chunk took $ms ms to generate")
    }
}
