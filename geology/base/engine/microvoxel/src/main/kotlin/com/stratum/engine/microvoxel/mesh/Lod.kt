package com.stratum.engine.microvoxel.mesh

import com.stratum.engine.microvoxel.MaterialPalette

/** A downsampled copy of a grid, for distant chunks. */
class CoarseGrid(override val size: Int, private val data: ShortArray) : VoxelGrid {
    override fun get(x: Int, y: Int, z: Int): Short = data[(z * size + y) * size + x]
}

/**
 * Level of detail by downsampling.
 *
 * At factor 2 a 64^3 chunk becomes 32^3 (one eighth the voxels, roughly a
 * quarter the quads); at factor 4 it is 16^3 -- exactly the block world's
 * resolution. The far rings of the view are meshed from these, which is how
 * a low-end phone gets a long horizon out of a microvoxel world.
 *
 * A cell is solid when at least [fillShare] of it is, so thin walls and roofs
 * survive a step or two of reduction instead of flickering out; it takes the
 * material of its *topmost* solid voxel, so a hillside stays green instead of
 * turning to dirt.
 */
object Lod {
    fun downsample(grid: VoxelGrid, factor: Int, palette: MaterialPalette, fillShare: Float = 0.375f): CoarseGrid {
        require(factor >= 1 && grid.size % factor == 0) { "factor $factor does not divide ${grid.size}" }
        if (factor == 1) {
            val n = grid.size
            return CoarseGrid(n, ShortArray(n * n * n) { i -> grid[i % n, (i / n) % n, i / (n * n)] })
        }
        val n = grid.size / factor
        val out = ShortArray(n * n * n)
        val need = (factor * factor * factor * fillShare).toInt().coerceAtLeast(1)
        for (cz in 0 until n) for (cy in 0 until n) for (cx in 0 until n) {
            var solid = 0
            var top: Short = MaterialPalette.AIR
            for (dz in factor - 1 downTo 0) for (dy in 0 until factor) for (dx in 0 until factor) {
                val m = grid[cx * factor + dx, cy * factor + dy, cz * factor + dz]
                if (palette.isOpaque(m)) {
                    solid++
                    if (top == MaterialPalette.AIR) top = m
                } else if (m != MaterialPalette.AIR && top == MaterialPalette.AIR && solid == 0) {
                    top = m // water and glass survive as themselves
                }
            }
            out[(cz * n + cy) * n + cx] = when {
                solid >= need -> top
                !palette.isOpaque(top) -> top
                else -> MaterialPalette.AIR
            }
        }
        return CoarseGrid(n, out)
    }
}

/**
 * How much world a device can afford.
 *
 * The three knobs that matter on a phone are how far you see, how much of
 * that is at full resolution, and how many chunks may be generated per frame
 * (generation is the spike; everything else is steady-state).
 */
enum class QualityProfile(
    /** View radius in chunks (64 micro = 16 blocks each). */
    val viewRadius: Int,
    /** Chunks within this ring are meshed at full microvoxel resolution. */
    val fullDetailRadius: Int,
    /** Out to here at half resolution; beyond, quarter (block) resolution. */
    val halfDetailRadius: Int,
    /** Chunk generations allowed per frame; the rest queue. */
    val chunksPerFrame: Int,
) {
    LOW(viewRadius = 4, fullDetailRadius = 1, halfDetailRadius = 2, chunksPerFrame = 1),
    MEDIUM(viewRadius = 6, fullDetailRadius = 2, halfDetailRadius = 4, chunksPerFrame = 2),
    HIGH(viewRadius = 10, fullDetailRadius = 3, halfDetailRadius = 6, chunksPerFrame = 4),
    ;

    /** Downsample factor for a chunk at Chebyshev distance [d] from the viewer's chunk. */
    fun lodFactor(d: Int): Int = when {
        d <= fullDetailRadius -> 1
        d <= halfDetailRadius -> 2
        else -> 4
    }
}
