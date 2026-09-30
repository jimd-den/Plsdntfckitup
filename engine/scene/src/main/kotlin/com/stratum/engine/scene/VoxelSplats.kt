package com.stratum.engine.scene

import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.mesh.NeighborOpacity
import com.stratum.engine.microvoxel.mesh.VoxelGrid

/**
 * Voxel splats: the world drawn as one point per visible voxel instead of
 * as a mesh, each voxel carrying the light its surroundings give it.
 *
 * A mesh has to be rebuilt -- faces found, merged, wound, indexed -- every
 * time a voxel changes, and pays tens of bytes a vertex for geometry that is,
 * underneath, a grid. A splat is the voxel itself: sixteen bytes that say
 * where it is, which of its faces are open, what colour it is, and how its
 * neighbourhood lights it. A renderer draws each as a small sprite over the
 * voxel's projected cube and works out in the pixel which face it is
 * looking at. So:
 *
 * - **Building** a chunk is one pass over its voxels: no face merging, no
 *   index buffer. An edit re-extracts the layer it touched.
 * - **Light is per voxel** ([VoxelLight]): how much sky it sees, the colour
 *   bounced onto it by what blocks that sky, and the glow of every lamp and
 *   lit window near it, worked out once on the meshing threads by walking
 *   the voxel grid. Alleys and the ground under trees fall into shade on
 *   their own, a red wall warms the path beside it, and every lamp in view
 *   lights the street, not only the few point lights a phone can shade.
 *   The sun, its shadows and the moving lights stay live on top.
 * - **Colour is per voxel**: each carries its own jittered tone, so ground
 *   is grainy where a greedy mesh paints a merged run one flat colour.
 * - **Level of detail** is a coarser grid of bigger splats, from the same
 *   code.
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
 * The sixteen-byte splat, and the one decoder both backends follow.
 *
 * ```
 *  int 0  x (6 bits), y (6), z (8): microvoxels from the chunk origin
 *         lod (2): the splat is 1 shl lod microvoxels on a side
 *         faces (6): +x -x +y -y +z -z open
 *         ao (4): how shut in the top face is, 0 open .. 15 walled in
 *  int 1  red, green, blue (8 bits each, high to low), emission (8)
 *  int 2  bounce red, green, blue (8 each), sky (8): see [VoxelLight]
 *  int 3  lamp red, green, blue (8 each, over [LAMP_RANGE]), spare (8)
 * ```
 * The GLSL twin is the top of `SceneShaders.splatVertex`.
 */
object VoxelSplat {
    const val INTS = 4
    const val FACE_PX = 1
    const val FACE_NX = 2
    const val FACE_PY = 4
    const val FACE_NY = 8
    const val FACE_PZ = 16
    const val FACE_NZ = 32

    fun pack(out: IntArray, at: Int, x: Int, y: Int, z: Int, lod: Int, faces: Int, ao: Int, rgb: Int, emission: Int, bounce: Int = 0, sky: Int = 255, lamp: Int = 0) {
        out[at] = (x and 63) or ((y and 63) shl 6) or ((z and 255) shl 12) or ((lod and 3) shl 20) or ((faces and 63) shl 22) or ((ao and 15) shl 28)
        out[at + 1] = ((rgb and 0xFFFFFF) shl 8) or (emission and 255)
        out[at + 2] = ((bounce and 0xFFFFFF) shl 8) or (sky and 255)
        out[at + 3] = (lamp and 0xFFFFFF) shl 8
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
    fun bounce(c: Int) = c ushr 8
    fun sky(c: Int) = c and 255
    fun lamp(d: Int) = d ushr 8

    /** A splat's [ao] as the occlusion its top face is shaded with: 1 open, [MIN_OCCLUSION] walled in. */
    fun occlusion(ao: Int): Float = 1f - (1f - MIN_OCCLUSION) * ao / 15f

    /** [sky] as the share of the ambient light a voxel takes: never quite none, or a cave would be a hole. */
    fun skyShare(sky: Int): Float = SKY_FLOOR + (1f - SKY_FLOOR) * sky / 255f

    /** [VoxelSplat.emission] back to the mesh path's emissive strength. */
    fun emissive(emission: Int): Float = emission / 255f

    /** One channel of a packed [bounce] or [lamp] colour, back to light. */
    fun channel(rgb: Int, shift: Int, range: Float): Float = ((rgb ushr shift) and 255) / 255f * range

    /** Occlusion of a top face with all eight cells round it filled: the mesher's darkest corner. */
    const val MIN_OCCLUSION = 0.5f

    /** Ambient light a voxel that sees no sky still gets. */
    const val SKY_FLOOR = 0.2f

    /** Share of the daylight on it that a surface throws onto its neighbours. */
    const val BOUNCE_GAIN = 0.6f

    /** Largest lamp light a splat stores, per channel. */
    const val LAMP_RANGE = 2f

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
 * Lamps near a chunk layer, for [VoxelLight]: block-sized clusters of
 * glowing voxels, each (x, y, z, red, green, blue) in world microvoxels and
 * light, [count] of them.
 */
class Lamps(val data: FloatArray, val count: Int) {
    companion object {
        val NONE = Lamps(FloatArray(0), 0)
        const val FLOATS = 6
    }
}

/**
 * The light a voxel's surroundings give it, found by walking the voxel grid
 * around it once, when its chunk is built:
 *
 * - **Sky**: a dozen short rays over the upper hemisphere, weighted towards
 *   the zenith as daylight is. The share that escape is how much of the
 *   sky the voxel sees; it scales the ambient light, so a crevice or the
 *   ground under a canopy darkens by what is really over it rather than
 *   by its four neighbours.
 * - **Bounce**: each ray that is stopped picks up the colour of what
 *   stopped it. Their weighted sum is one bounce of daylight, which the
 *   shader scales by the sky and sun of the hour.
 * - **Lamps**: every lamp within [TerrainMesher.LIGHT_RADIUS] blocks that
 *   the voxel can see lights it by the mesh path's own falloff, in the
 *   lamp's colour.
 *
 * The rays step through the grid a whole cell at a time, further apart the
 * further they go; on a coarser level of detail the cells are bigger, so
 * the same dozen rays see as far in fewer steps.
 */
internal class VoxelLight(private val palette: MaterialPalette) {
    var sky = 255
        private set
    var bounce = 0
        private set
    var lamp = 0
        private set

    fun light(
        grid: VoxelGrid, neighbours: NeighborOpacity, x: Int, y: Int, z: Int, faces: Int, ownColor: Int,
        lamps: Lamps, worldX: Float, worldY: Float, worldZ: Float, factor: Int, microPerBlock: Int,
    ) {
        val n = grid.size
        val cx = x + 0.5f; val cy = y + 0.5f; val cz = z + 0.5f
        var open = 0f; var total = 0f
        var br = 0f; var bg = 0f; var bb = 0f
        for (k in 0 until RAYS) {
            val dx = DIRS[k * 4]; val dy = DIRS[k * 4 + 1]; val dz = DIRS[k * 4 + 2]; val w = DIRS[k * 4 + 3]
            total += w
            var hit = -1
            for (t in STEPS) {
                val px = kotlin.math.floor(cx + dx * t).toInt()
                val py = kotlin.math.floor(cy + dy * t).toInt()
                val pz = kotlin.math.floor(cz + dz * t).toInt()
                if (px == x && py == y && pz == z) continue
                val inside = px in 0 until n && py in 0 until n && pz in 0 until n
                if (inside) {
                    val m = grid[px, py, pz]
                    if (palette.isOpaque(m)) { hit = palette[m].color; break }
                } else if (neighbours.opaque(px, py, pz)) { hit = ownColor; break }
            }
            if (hit < 0) { open += w; continue }
            br += ((hit shr 16) and 255) * w; bg += ((hit shr 8) and 255) * w; bb += (hit and 255) * w
        }
        sky = (open / total * 255f + 0.5f).toInt().coerceIn(0, 255)
        bounce = (channel(br / total) shl 16) or (channel(bg / total) shl 8) or channel(bb / total)
        lamp = if (lamps.count == 0) 0 else lampLight(grid, neighbours, x, y, z, faces, lamps, worldX, worldY, worldZ, factor, microPerBlock)
    }

    /** Open sky, no bounce, no lamps: a splat lit like the mesh. */
    fun none() { sky = 255; bounce = 0; lamp = 0 }

    private fun channel(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)

    private fun lampLight(
        grid: VoxelGrid, neighbours: NeighborOpacity, x: Int, y: Int, z: Int, faces: Int, lamps: Lamps,
        wx: Float, wy: Float, wz: Float, factor: Int, r: Int,
    ): Int {
        val radius = TerrainMesher.LIGHT_RADIUS * r
        var lr = 0f; var lg = 0f; var lb = 0f
        val d = lamps.data
        for (i in 0 until lamps.count) {
            val o = i * Lamps.FLOATS
            val lx = d[o] - wx; val ly = d[o + 1] - wy; val lz = d[o + 2] - wz
            val dist = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz)
            if (dist >= radius || dist < 1e-3f) continue
            val ux = lx / dist; val uy = ly / dist; val uz = lz / dist
            // The open face turned most towards the lamp, as the mesh's lamp light would shade that face.
            var facing = 0f
            if (faces and VoxelSplat.FACE_PX != 0) facing = maxOf(facing, ux)
            if (faces and VoxelSplat.FACE_NX != 0) facing = maxOf(facing, -ux)
            if (faces and VoxelSplat.FACE_PY != 0) facing = maxOf(facing, uy)
            if (faces and VoxelSplat.FACE_NY != 0) facing = maxOf(facing, -uy)
            if (faces and VoxelSplat.FACE_PZ != 0) facing = maxOf(facing, uz)
            val fall = 1f - dist / radius
            var k = fall * fall * (facing * 0.7f + 0.3f)
            // Walls stop lamplight, all but a little that finds its way round.
            if (!sees(grid, neighbours, x, y, z, ux, uy, uz, dist / factor)) k *= LEAK
            lr += d[o + 3] * k; lg += d[o + 4] * k; lb += d[o + 5] * k
        }
        fun c(v: Float) = (v / VoxelSplat.LAMP_RANGE * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (c(lr) shl 16) or (c(lg) shl 8) or c(lb)
    }

    /** Whether nothing opaque lies between a voxel and a point [cells] grid cells away along ([ux], [uy], [uz]). */
    private fun sees(grid: VoxelGrid, neighbours: NeighborOpacity, x: Int, y: Int, z: Int, ux: Float, uy: Float, uz: Float, cells: Float): Boolean {
        val n = grid.size
        var t = 1f
        // The lamp's own cell glows; stop a cell short of it.
        while (t < cells - 1f) {
            val px = kotlin.math.floor(x + 0.5f + ux * t).toInt()
            val py = kotlin.math.floor(y + 0.5f + uy * t).toInt()
            val pz = kotlin.math.floor(z + 0.5f + uz * t).toInt()
            val solid = if (px in 0 until n && py in 0 until n && pz in 0 until n) palette.isOpaque(grid[px, py, pz]) else neighbours.opaque(px, py, pz)
            if (solid && !(px == x && py == y && pz == z)) return false
            t += 1f
        }
        return true
    }

    companion object {
        /** Distances along each sky ray, in grid cells, at which the grid is looked at. */
        private val STEPS = floatArrayOf(1f, 1.7f, 2.5f, 3.5f, 4.8f, 6.5f, 8.7f, 11.5f, 15f, 19.5f, 25f)

        /** Lamplight that gets past a wall: enough that a lit room glows at its door. */
        const val LEAK = 0.15f

        /** Sky rays: (x, y, z, weight), weight the cosine to the zenith, as a sky's light falls. */
        private val DIRS: FloatArray = run {
            val out = ArrayList<Float>()
            fun ring(elevation: Double, count: Int, turn: Double) {
                for (i in 0 until count) {
                    val a = turn + i * 2 * Math.PI / count
                    val c = kotlin.math.cos(elevation)
                    out += (c * kotlin.math.cos(a)).toFloat(); out += (c * kotlin.math.sin(a)).toFloat(); out += kotlin.math.sin(elevation).toFloat()
                    out += kotlin.math.sin(elevation).toFloat()
                }
            }
            out += 0f; out += 0f; out += 1f; out += 1f
            ring(Math.toRadians(62.0), 5, 0.3)
            ring(Math.toRadians(30.0), 6, 0.9)
            out.toFloatArray()
        }
        val RAYS = DIRS.size / 4
    }
}

/**
 * Finds the surface voxels of micro-chunk layers and packs them, one block
 * chunk at a time. One per meshing thread: it keeps its buffer between chunks.
 */
class SplatExtractor(private val palette: MaterialPalette, private val microPerBlock: Int, private val voxelLight: Boolean = true) {
    private var data = IntArray(4096)
    private var count = 0
    private var originX = 0
    private var originY = 0
    private var minZ = Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE
    private val light = VoxelLight(palette)

    fun begin(originX: Int, originY: Int) {
        this.originX = originX; this.originY = originY
        count = 0; minZ = Float.MAX_VALUE; maxZ = -Float.MAX_VALUE
    }

    val size: Int get() = count

    /**
     * Adds the surface of one layer. [grid] is the layer at [factor] times
     * the microvoxel size (1 for full detail, 2 or 4 for the far rings);
     * [neighbours] answers opacity just outside it in the same coarse cells;
     * [layerZ] is the layer's first microvoxel z in the chunk; [lamps] are
     * the lamps that may light it, in world microvoxels.
     */
    fun extract(grid: VoxelGrid, neighbours: NeighborOpacity, layerZ: Int, factor: Int, lamps: Lamps = Lamps.NONE) {
        val n = grid.size
        val lod = when (factor) { 1 -> 0; 2 -> 1; else -> 2 }
        val micro = grid as? MicroChunk
        val bricks = if (micro != null) MicroChunk.BRICKS_PER_AXIS else 1
        val span = n / bricks
        val baseX = originX * microPerBlock; val baseY = originY * microPerBlock
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
                val half = factor / 2f
                if (voxelLight) light.light(
                    grid, neighbours, x, y, z, faces, mat.color, lamps,
                    baseX + wx + half, baseY + wy + half, wz + half, factor, microPerBlock,
                ) else light.none()
                val k = 1f + (hash(baseX + wx, baseY + wy, wz) - 0.5f) * 2f * mat.jitter
                add(
                    wx, wy, wz, lod, faces, (shut * 15 + 4) / 8, tone(mat.color, k),
                    ((mat.emission * 0.3f).coerceIn(0f, 1f) * 255f).toInt(), factor,
                    light.bounce, light.sky, light.lamp,
                )
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

    private fun add(x: Int, y: Int, z: Int, lod: Int, faces: Int, ao: Int, rgb: Int, emission: Int, factor: Int, bounce: Int, sky: Int, lamp: Int) {
        if ((count + 1) * VoxelSplat.INTS > data.size) data = data.copyOf(data.size * 2)
        VoxelSplat.pack(data, count * VoxelSplat.INTS, x, y, z, lod, faces, ao, rgb, emission, bounce, sky, lamp)
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
