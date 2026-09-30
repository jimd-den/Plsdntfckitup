package com.stratum.engine.microvoxel

import com.stratum.engine.microvoxel.gen.MicroWorldgen
import com.stratum.engine.microvoxel.gen.StageSpec
import com.stratum.engine.microvoxel.geo.Provinces
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the geology costs per chunk, measured the way the game pays for it:
 * a fresh world (cold caches, as on arriving somewhere new), every vertical
 * chunk of a 4 x 4 patch of chunk columns, in the block world's proportions.
 *
 * Writes a table to `build/geo-bench.txt` so a
 * change to the terrain can be compared before and after, and asserts a
 * loose budget so a stage that got many times slower fails the build.
 */
class GeoBenchTest {

    private val seed = 20260928L

    private fun world(geology: String) = MicroWorld(
        MicroWorldgen.build(
            seed,
            listOf(
                StageSpec("micro:terrain", mapOf("geology" to geology, "seaLevel" to "48", "maxHeight" to "160", "minHeight" to "8")),
                StageSpec("micro:features"),
            ),
        ),
    )

    /** Milliseconds per chunk column (every vertical chunk of it) over a 4 x 4 patch at (ox, oy), and the chunks made. */
    private fun measure(geology: String, ox: Int, oy: Int): Pair<Double, Int> {
        val world = world(geology)
        var chunks = 0
        val start = System.nanoTime()
        for (cy in oy until oy + 4) for (cx in ox until ox + 4) for (cz in world.verticalRange(cx, cy)) {
            world.chunk(MicroChunkPos(cx, cy, cz)); chunks++
        }
        return (System.nanoTime() - start) / 1e6 / 16 to chunks
    }

    @Test
    fun `geology per-chunk cost stays inside a phone budget`() {
        // Warm the JIT on a throwaway world so the first row is not charged for it.
        repeat(2) { measure("africa", 40, 40) }
        val kinds = listOf("africa") + Provinces.all(seed).map { it.id }
        // Best of three: the least disturbed by other work on the machine.
        val rows = kinds.map { k -> k to (0 until 3).map { measure(k, 3, -2) }.minBy { it.first } }
        val total = rows.sumOf { it.second.first } / rows.size
        val report = buildString {
            appendLine("geology bench (ms per 64x64 chunk column, all its vertical chunks, cold caches, 4x4 columns)")
            for ((k, r) in rows) appendLine("  %-22s %7.2f ms  (%d chunks)".format(java.util.Locale.ROOT, k, r.first, r.second))
            appendLine("  %-22s %7.2f ms".format(java.util.Locale.ROOT, "ALL", total))
        }
        println(report)
        java.io.File("build/geo-bench.txt").apply { parentFile.mkdirs() }.writeText(report)
        assertTrue(total < 250.0, "geology averages $total ms per chunk column")
    }
}
