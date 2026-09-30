package com.stratum.engine.scene

import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.mesh.VoxelGrid
import com.stratum.engine.microvoxel.mesh.NeighborOpacity

/**
 * Voxel splats: the world drawn as one point per visible voxel instead of
 * as a mesh.
 *
 * A mesh has to be rebuilt -- faces found, merged, wound, indexed -- every
 * time a voxel changes, and pays tens of bytes a vertex for geometry that is,
 * underneath, a grid. A splat is the voxel itself: eight bytes that say where
 * it is, which of its faces are open, how shut in its top is and what colour
 * it is. A renderer draws each as a small screen-space square that covers the
 * voxel's projected cube, and works out in the pixel which face it is looking
 * at. So:
 *
 * - **Building** a chunk is one pass over its voxels: no face merging, no
 *   index buffer. An edit re-extracts the layer it touched in a few ms.
 * - **Colour is per voxel**: each carries its own jittered tone, so ground
 *   is grainy where a greedy mesh would paint a merged run one flat colour.
 * - **Level of detail** is a coarser grid of bigger splats, from the same
 *   code.
 * - **Memory** is eight bytes per surface voxel; what a mesh costs depends on
 *   how well its faces merge (see [SplatStats]).
 *
 * Only surface voxels are kept: solid, with at least one open face other
 * than the underside, which this camera never sees. Solid bricks buried in
 * the ground cost nothing.
 */
class SplatBatch(
    /** The block chunk's origin in blocks; splat positions are microvoxels from it. */
    val originX: Int,
    val originY: Int,
    /** [VoxelSplat.INTS] ints a splat, [count] splats. */
    val data: IntArray,
    val count: Int,
    /** Lowest and highest z of any splat, in blocks, for culling against the view. */
    val minZ: Float,
    val maxZ: Float,
    /** Microvoxels to a block. */
    val microPerBlock: Int,
) {
    val bytes: Int get() = count * VoxelSplat.INTS * 4
}

/**
 * The eight-byte splat, and the one decoder both backends follow.
 *
 * ```
 *  int 0  x (6 bits), y (6), z (8): microvoxels from the chunk origin
 *         lod (2): the splat is 1 shl lod microvoxels on a side
 *         faces (6): +x -x +y -y +z -z open
 *         ao (4): how shut in the top face is, 0 open .. 15 walled in
 *  int 1  red, green, blue (8 bits each, high to low), emission (8)
 * ```
 * The GLSL twin is the top of `SceneShaders.SPLAT_VERTEX`.
 */
object VoxelSplat {
    const val INTS = 2
    const val FACE_PX = 1
    const val FACE_NX = 2
    const val FACE_PY = 4
    const val FACE_NY = 8
    const val FACE_PZ = 16
    const val FACE_NZ = 32

    fun pack(out: IntArray, at: Int, x: Int, y: Int, z: Int, lod: Int, faces: Int, ao: Int, rgb: Int, emission: Int) {
        out[at] = (x and 63) or ((y and 63) shl 6) or ((z and 255) shl 12) or ((lod and 3) shl 20) or ((faces and 63) shl 22) or ((ao and 15) shl 28)
        out[at + 1] = ((rgb and 0xFFFFFF) shl 8) or (emission and 255)
    }

    fun x(a: Int) = a and 63
    fun y(a: Int) = (a ushr 6) and 63
    fun z(a: Int) = (a ushr 12) and 255
    fun lod(a: Int) = (a ushr 20) and 3
    fun size(a: Int) = 1 shl lod(a)
    fun faces(a: Int) = (a ushr 22) and 63
    fun ao(a: Int) = (a ushr 28) and 15
    fun rgb(b: Int) = b ushr 8
    fun emission(b: Int) = b and 255

    /** A splat's [ao] as the occlusion its top face is shaded with: 1 open, [MIN_OCCLUSION] walled in. */
    fun occlusion(ao: Int): Float = 1f - (1f - MIN_OCCLUSION) * ao / 15f

    /** [VoxelSplat.emission] back to the mesh path's emissive strength. */
    fun emissive(emission: Int): Float = emission / 255f

    /** Occlusion of a top face with all eight cells round it filled: the mesher's darkest corner. */
    const val MIN_OCCLUSION = 0.5f

    /** Microvoxel side lengths a splat may have, by [lod]. */
    const val MAX_LOD = 2

    /**
     * A sprite's side over its voxel's: enough to hold the cube's outline
     * from any angle (its bounding sphere's diameter is the side times
     * root three), with a hair more for perspective.
     */
    const val SPRITE_SPREAD = 1.8f

    /**
     * A shadow sprite's side over its voxel's, in the sun's map. Square and
     * flat, so a little over the cube's width seen from the sun, not its
     * bounding sphere: wider and every ledge's shadow grows a fat lip.
     */
    const val SHADOW_SPREAD = 1.25f

    /** Offset along the sun from a splat's centre to where its shadow is looked up: out of its own shadow sprite. */
    const val SHADOW_LIFT = 0.7f
}

/**
 * How the micro-detail terrain is drawn.
 *
 * - [MESH]: greedy-meshed quads, as before.
 * - [FAST]: one point sprite per surface voxel, cut to the cube's outline
 *   and split into its top and two side faces by six dot products a pixel
 *   ([SplatFaces]); depth is the centre's, from the rasteriser, so the
 *   shader writes none. For low-end phones, and for tiers whose finish
 *   does not read depth: one depth a voxel reads as a crease at every seam
 *   (see `RenderSettings.splatDraw`).
 * - [EXACT]: each sprite ray-casts its voxel's box per pixel and writes the
 *   hit's depth: true cube silhouettes and intersections with meshes, at a
 *   few more instructions a pixel.
 */
enum class SplatMode { MESH, FAST, EXACT;
    val splats: Boolean get() = this != MESH
}

/**
 * Finds the surface voxels of micro-chunk layers and packs them, one block
 * chunk at a time. One per meshing thread: it keeps its buffer between chunks.
 */
class SplatExtractor(private val palette: MaterialPalette, private val microPerBlock: Int) {
    private var data = IntArray(4096)
    private var count = 0
    private var originX = 0
    private var originY = 0
    private var minZ = Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE

    fun begin(originX: Int, originY: Int) {
        this.originX = originX; this.originY = originY
        count = 0; minZ = Float.MAX_VALUE; maxZ = -Float.MAX_VALUE
    }

    val size: Int get() = count

    /**
     * Adds the surface of one layer. [grid] is the layer at [factor] times
     * the microvoxel size (1 for full detail, 2 or 4 for the far rings);
     * [neighbours] answers opacity just outside it in the same coarse cells;
     * [layerZ] is the layer's first microvoxel z in the chunk.
     */
    fun extract(grid: VoxelGrid, neighbours: NeighborOpacity, layerZ: Int, factor: Int) {
        val n = grid.size
        val lod = when (factor) { 1 -> 0; 2 -> 1; else -> 2 }
        val micro = grid as? MicroChunk
        val bricks = if (micro != null) MicroChunk.BRICKS_PER_AXIS else 1
        val span = n / bricks
        for (bz in 0 until bricks) for (by in 0 until bricks) for (bx in 0 until bricks) {
            val uniform = micro?.brickUniform(bx, by, bz)
            if (uniform == MaterialPalette.AIR) continue
            // A solid brick walled in by solid bricks has no surface at all.
            if (uniform != null && palette.isOpaque(uniform) && buried(micro, bx, by, bz)) continue
            val shellOnly = uniform != null && palette.isOpaque(uniform)
            for (z in bz * span until (bz + 1) * span) for (y in by * span until (by + 1) * span) for (x in bx * span until (bx + 1) * span) {
                if (shellOnly && x != bx * span && x != (bx + 1) * span - 1 && y != by * span && y != (by + 1) * span - 1 &&
                    z != bz * span && z != (bz + 1) * span - 1
                ) continue
                val m = grid[x, y, z]
                if (!palette.isOpaque(m)) continue
                var faces = 0
                if (!solid(grid, neighbours, x + 1, y, z)) faces = faces or VoxelSplat.FACE_PX
                if (!solid(grid, neighbours, x - 1, y, z)) faces = faces or VoxelSplat.FACE_NX
                if (!solid(grid, neighbours, x, y + 1, z)) faces = faces or VoxelSplat.FACE_PY
                if (!solid(grid, neighbours, x, y - 1, z)) faces = faces or VoxelSplat.FACE_NY
                if (!solid(grid, neighbours, x, y, z + 1)) faces = faces or VoxelSplat.FACE_PZ
                if (!solid(grid, neighbours, x, y, z - 1)) faces = faces or VoxelSplat.FACE_NZ
                // Nothing to see but the underside, which this camera never looks at.
                if (faces and VoxelSplat.FACE_NZ.inv() == 0) continue
                var shut = 0
                if (faces and VoxelSplat.FACE_PZ != 0) {
                    for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && solid(grid, neighbours, x + dx, y + dy, z + 1)) shut++
                }
                val mat = palette[m]
                val wx = x * factor; val wy = y * factor; val wz = layerZ + z * factor
                val k = 1f + (hash(originX * microPerBlock + wx, originY * microPerBlock + wy, wz) - 0.5f) * 2f * mat.jitter
                add(wx, wy, wz, lod, faces, (shut * 15 + 4) / 8, tone(mat.color, k), (mat.emission * 0.3f).coerceIn(0f, 1f).let { (it * 255f).toInt() }.coerceIn(0, 255), factor)
            }
        }
    }

    private fun buried(micro: MicroChunk, bx: Int, by: Int, bz: Int): Boolean {
        val b = MicroChunk.BRICKS_PER_AXIS
        fun solidBrick(x: Int, y: Int, z: Int): Boolean {
            if (x !in 0 until b || y !in 0 until b || z !in 0 until b) return false
            val u = micro.brickUniform(x, y, z) ?: return false
            return palette.isOpaque(u)
        }
        return solidBrick(bx + 1, by, bz) && solidBrick(bx - 1, by, bz) && solidBrick(bx, by + 1, bz) &&
            solidBrick(bx, by - 1, bz) && solidBrick(bx, by, bz + 1) && solidBrick(bx, by, bz - 1)
    }

    private fun solid(grid: VoxelGrid, neighbours: NeighborOpacity, x: Int, y: Int, z: Int): Boolean {
        val n = grid.size
        return if (x in 0 until n && y in 0 until n && z in 0 until n) palette.isOpaque(grid[x, y, z]) else neighbours.opaque(x, y, z)
    }

    private fun add(x: Int, y: Int, z: Int, lod: Int, faces: Int, ao: Int, rgb: Int, emission: Int, factor: Int) {
        if ((count + 1) * VoxelSplat.INTS > data.size) data = data.copyOf(data.size * 2)
        VoxelSplat.pack(data, count * VoxelSplat.INTS, x, y, z, lod, faces, ao, rgb, emission)
        count++
        val zb = z.toFloat() / microPerBlock
        if (zb < minZ) minZ = zb
        val top = (z + factor).toFloat() / microPerBlock
        if (top > maxZ) maxZ = top
    }

    /** The splats added since [begin], as a batch of their own; null when there were none. */
    fun build(): SplatBatch? {
        if (count == 0) return null
        return SplatBatch(originX, originY, data.copyOf(count * VoxelSplat.INTS), count, minZ, maxZ, microPerBlock)
    }

    /** Gives back a buffer grown past [maxInts] by a dense chunk. */
    fun trimTo(maxInts: Int) {
        if (data.size > maxInts) data = IntArray(4096)
    }

    private fun tone(rgb: Int, k: Float): Int {
        fun ch(s: Int) = (((rgb shr s) and 255) * k).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun hash(x: Int, y: Int, z: Int): Float {
        var h = x * 374761393 + y * 668265263 + z * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777216f
    }

    companion object {
        /** Ints a worker's buffer keeps between chunks (256 KB); a larger one is given back. */
        const val MAX_KEPT_INTS = 1 shl 16
    }
}

/** What a chunk costs drawn each way, for the benchmark. */
data class SplatStats(val splats: Int, val splatBytes: Int, val quads: Int, val meshBytes: Int)
