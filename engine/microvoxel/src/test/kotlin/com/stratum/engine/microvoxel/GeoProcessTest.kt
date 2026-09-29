package com.stratum.engine.microvoxel

import com.stratum.engine.microvoxel.gen.Fields
import com.stratum.engine.microvoxel.gen.MicroGenerator
import com.stratum.engine.microvoxel.gen.MicroWorldgen
import com.stratum.engine.microvoxel.gen.StageSpec
import com.stratum.engine.microvoxel.geo.GeoAtlas
import com.stratum.engine.microvoxel.geo.GeoProcessFactory
import com.stratum.engine.microvoxel.geo.GeoProcesses
import com.stratum.engine.microvoxel.geo.Provinces
import com.stratum.engine.microvoxel.geo.R
import com.stratum.engine.microvoxel.geo.ReliefProcess
import com.stratum.engine.microvoxel.geo.ResolvedProvince
import com.stratum.engine.microvoxel.geo.step
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The geological processes: registered by id, deterministic, and each doing its work where it should. */
class GeoProcessTest {

    private val seed = 20260928L

    private fun world(geology: String, vararg dials: Pair<String, String>): MicroGenerator = MicroWorldgen.build(
        seed,
        listOf(StageSpec("micro:terrain", mapOf("geology" to geology, "seaLevel" to "48", "maxHeight" to "160", "spawnRise" to "0") + dials)),
    )

    private fun atlas(g: MicroGenerator) = g.fields.require(Fields.GEOLOGY) as GeoAtlas

    /** Columns of an [n] x [n] grid [stride] apart from (x0, y0), as the atlas reports them. */
    private inline fun columns(a: GeoAtlas, x0: Int, y0: Int, n: Int, stride: Int, each: (GeoAtlas.Column) -> Unit) {
        val c = GeoAtlas.Column()
        for (j in 0 until n) for (i in 0 until n) each(a.column(x0 + i * stride, y0 + j * stride, c))
    }

    @Test
    fun `every province is a list of registered steps with a landform`() {
        val known = GeoProcesses.ids.toSet()
        assertEquals(24, Provinces.all(seed).size)
        for (p in Provinces.all(seed)) {
            assertTrue(p.processes.isNotEmpty(), "${p.id} has no steps")
            for (s in p.processes) assertTrue(s.id in known, "${p.id}: unknown step ${s.id}")
        }
        val palette = world("africa").palette
        val bad = Provinces.all(seed).first().copy(processes = listOf(step("no_such_thing")))
        assertFailsWith<IllegalArgumentException> { ResolvedProvince(bad, palette) }
    }

    @Test
    fun `a pack registers its own landform and a province uses it`() {
        GeoProcesses.register("test:table", GeoProcessFactory { _, o -> val h = o.float("height", 20f); ReliefProcess { _, _, _, _, _ -> h } })
        val palette = world("africa").palette
        val p = Provinces.all(seed).first().copy(processes = listOf(step("test:table", "height" to 33f), step("sinkholes", "depth" to 0f)))
        val r = ResolvedProvince(p, palette, seed)
        val n = com.stratum.engine.microvoxel.gen.Noise(seed)
        assertEquals(33f, r.relief(100f, 200f, 0f, n), 1e-3f)
    }

    @Test
    fun `chunks come out the same whatever order they are made in`() {
        val positions = ArrayList<MicroChunkPos>()
        val probe = MicroWorld(world("africa"))
        // Straddle a drainage tile's seam (1536 microvoxels, 24 chunks) and a fine tile's.
        for (cy in 22..25) for (cx in 22..25) for (cz in probe.verticalRange(cx, cy)) positions += MicroChunkPos(cx, cy, cz)
        val a = MicroWorld(world("africa"))
        val first = positions.associateWith { a.chunk(it).export() }
        val b = MicroWorld(world("africa"))
        // Another order, after visiting somewhere else first so every cache starts in a different state.
        for (cz in b.verticalRange(-70, 90)) b.chunk(MicroChunkPos(-70, 90, cz))
        for (pos in positions.shuffled(java.util.Random(7))) assertContentEquals(first.getValue(pos), b.chunk(pos).export(), "chunk $pos differs")
    }

    @Test
    fun `the land is continuous across the drainage tiles' seams`() {
        val a = atlas(world(Provinces.FOREST_HILLS))
        // Along a line crossing four seams, a step between neighbouring columns is never a wall erosion made.
        var worst = 0f
        for (y in listOf(300, 2100, 4000)) {
            var prev = a.heightAt(-3100, y)
            for (x in -3099..3100) { val h = a.heightAt(x, y); worst = maxOf(worst, abs(h - prev)); prev = h }
        }
        assertTrue(worst < 6f, "a $worst microvoxel step between neighbouring columns")
    }

    @Test
    fun `water cuts valleys and lays fans, and the dial turns it off`() {
        val wet = atlas(world(Provinces.FOREST_HILLS))
        val dry = atlas(world(Provinces.FOREST_HILLS, "erosion" to "0", "rivers" to "0"))
        var diff = 0.0; var lower = 0; var fans = 0
        val c = GeoAtlas.Column()
        for (j in 0 until 80) for (i in 0 until 80) {
            val x = i * 37 - 1500; val y = j * 41 - 1600
            val d = wet.heightAt(x, y) - dry.heightAt(x, y)
            diff += abs(d); if (d < -2f) lower++
            if (wet.column(x, y, c).mark == GeoAtlas.MARK_FAN) fans++
        }
        assertTrue(diff / 6400 > 0.5, "erosion moved the land only ${diff / 6400} on average")
        assertTrue(lower > 200, "only $lower columns were cut down by water")
        assertTrue(fans > 0, "no fans laid")
    }

    @Test
    fun `wet country has rivers with water in them, deserts have dry wadis, and the dial removes both`() {
        fun count(g: MicroGenerator): Pair<Int, Int> {
            var channel = 0; var water = 0
            columns(atlas(g), -3000, -3000, 150, 40) { if (it.mark == GeoAtlas.MARK_CHANNEL) { channel++; if (it.water != GeoAtlas.NO_WATER) water++ } }
            return channel to water
        }
        val (ch, w) = count(world(Provinces.FOREST_HILLS))
        assertTrue(ch > 50 && w > ch / 2, "forest hills: $ch channel columns, $w with water")
        val (dch, dw) = count(world(Provinces.REG_HAMADA, "rivers" to "2"))
        assertTrue(dw < dch / 4 + 1, "reg: $dch channel columns, $dw with water")
        val (nch, _) = count(world(Provinces.FOREST_HILLS, "rivers" to "0"))
        assertEquals(0, nch)
    }

    @Test
    fun `scree piles at the foot of cliffs only where the dial allows`() {
        fun talus(g: MicroGenerator): Int { var n = 0; columns(atlas(g), -3000, -3000, 150, 40) { if (it.mark == GeoAtlas.MARK_TALUS) n++ }; return n }
        val on = talus(world(Provinces.KAROO))
        assertTrue(on > 20, "only $on talus columns among the Karoo's mesas")
        assertEquals(0, talus(world(Provinces.KAROO, "scree" to "0")))
    }

    @Test
    fun `the dunes dial raises the sand sea`() {
        fun spread(g: MicroGenerator): Double {
            val a = atlas(g); val hs = ArrayList<Float>()
            for (j in 0 until 60) for (i in 0 until 60) hs += a.heightAt(i * 23, j * 29)
            val mean = hs.average(); return hs.sumOf { (it - mean) * (it - mean) } / hs.size
        }
        val low = spread(world(Provinces.ERG, "dunes" to "0.3")); val high = spread(world(Provinces.ERG, "dunes" to "2"))
        assertTrue(high > low * 2, "erg height variance $low at 0.3, $high at 2")
    }

    @Test
    fun `dykes cut the Karoo and the rock dial sets how many`() {
        fun dolerite(rock: String): Int {
            val g = world(Provinces.KAROO, "rockDetail" to rock)
            val w = MicroWorld(g); val id = g.palette.id(R.DOLERITE)
            var n = 0
            for (cy in 0..5) for (cx in 0..5) {
                // The chunk under the surface: bedding, not the caps.
                val z = w.verticalRange(cx, cy).first
                val c = w.chunk(MicroChunkPos(cx, cy, z))
                for (v in c.export()) if (v == id) n++
            }
            return n
        }
        val none = dolerite("0"); val many = dolerite("2")
        assertTrue(many > none + 500, "dolerite voxels: $none with no rock detail, $many with 2")
    }

    @Test
    fun `quartz veins and ore thread the basement shield`() {
        val g = world(Provinces.SHIELD)
        val w = MicroWorld(g)
        val quartz = g.palette.id(R.QUARTZ); val gold = g.palette.id(R.GOLD_REEF); val bif = g.palette.id(R.BIF_RED)
        val seen = HashSet<Short>()
        for (cy in 0..5) for (cx in 0..5) for (cz in w.verticalRange(cx, cy)) for (v in w.chunk(MicroChunkPos(cx, cy, cz)).export()) if (v == quartz || v == gold || v == bif) seen += v
        assertTrue(quartz in seen && gold in seen, "the shield holds ${seen.size} of quartz, gold reef and banded iron")
    }
}
