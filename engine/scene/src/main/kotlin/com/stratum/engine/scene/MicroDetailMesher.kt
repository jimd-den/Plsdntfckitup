package com.stratum.engine.scene

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.MicroChunkPos
import com.stratum.engine.microvoxel.MicroTerrainSource
import com.stratum.engine.microvoxel.mesh.BinaryGreedyMesher
import com.stratum.engine.microvoxel.mesh.NeighborOpacity
import com.stratum.engine.microvoxel.mesh.Quad

/**
 * Meshes a block chunk from the microvoxels behind it: quarter-block slopes,
 * kerbs, window sills, leaves -- the fine version of the same ground.
 *
 * The block world stays the truth. Every block the player (or a town laid
 * over the land) has changed is drawn as that block, filled into the
 * microvoxel grid before meshing, so digging, building and settlement
 * stamps show exactly as they play. A chunk changed that much is not worth
 * the detail: past [MAX_CHANGED_SHARE] of its surface this returns null and
 * the caller draws it with the ordinary textured block mesher.
 *
 * Output is the same [TerrainMesher.Result] the block mesher gives --
 * flat-coloured quads with per-corner occlusion on the opaque path -- so
 * every backend (GLES renderer, preview rasteriser) draws it unchanged.
 */
class MicroDetailMesher(private val source: MicroTerrainSource) {

    private val palette: MaterialPalette get() = source.palette
    private val mesher = BinaryGreedyMesher(source.palette)
    private val out = MeshBuilder(MaterialKind.OPAQUE)
    private val r = source.microPerBlock
    private val blocksPerMicro = MicroChunk.SIZE / r

    /** Quads emitted by the last [mesh], for profiling. */
    var quadsLastMesh: Int = 0
        private set

    /** Meshes from the live world; call on the thread that owns it. */
    fun mesh(world: World, pos: ChunkPos): TerrainMesher.Result? = mesh(BlockSnapshot.of(world, pos), pos)

    /** Meshes from a snapshot, which any thread may do. */
    fun mesh(world: BlockSnapshot, pos: ChunkPos): TerrainMesher.Result? {
        out.clear()
        quadsLastMesh = 0
        val props = ArrayList<PropInstance>()
        val lights = ArrayList<PointLight>()
        val changed = changedBlocks(world, pos) ?: return null
        val columns = Chunk.HEIGHT / blocksPerMicro
        for (cz in 0 until columns) {
            val mpos = MicroChunkPos(pos.x, pos.y, cz)
            val generated = source.microChunk(mpos)
            val mine = changed[cz]
            if (generated.isEmpty() && mine.isEmpty()) continue
            val grid = if (mine.isEmpty()) generated else patched(generated, mine, pos, cz, world, props, lights)
            val mesh = mesher.mesh(grid, neighbours(world, mpos), ambientOcclusion = true)
            emit(mesh.quads, mesh.count, mpos)
            quadsLastMesh += mesh.count
            water(grid, mpos)
            lamps(grid, mpos, lights)
        }
        return TerrainMesher.Result(out.build(), props, lights)
    }

    /**
     * Per micro-chunk layer, the blocks that differ from what was generated,
     * as local indices; null when too much of the chunk has changed to be
     * worth drawing in detail.
     */
    private fun changedBlocks(world: BlockSnapshot, pos: ChunkPos): Array<IntArray>? {
        val layers = Chunk.HEIGHT / blocksPerMicro
        val lists = Array(layers) { ArrayList<Int>() }
        var surfaceChanged = 0
        for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
            val wx = pos.originX + x; val wy = pos.originY + y
            val top = world.surface(wx, wy)
            for (z in 0 until Chunk.HEIGHT) {
                val here = world.index(wx, wy, z)
                if (here < 0) continue
                val cell = (z % blocksPerMicro) * Chunk.SIZE * Chunk.SIZE + y * Chunk.SIZE + x
                if (here == source.generatedBlock(wx, wy, z)) {
                    // A pack prop the generator built (a town's brazier) is drawn as its sprite, like any prop --
                    // but a tree sprite standing in for microvoxel leaves is not: those leaves are drawn already.
                    if (here != BlockRegistry.AIR_INDEX && world.type(here).glyph != null && builtAsBlock(pos, x, y, z, here))
                        lists[z / blocksPerMicro] += cell
                    continue
                }
                lists[z / blocksPerMicro] += cell
                if (z >= top - 1) surfaceChanged++
            }
        }
        if (surfaceChanged > Chunk.SIZE * Chunk.SIZE * MAX_CHANGED_SHARE) return null
        return Array(layers) { lists[it].toIntArray() }
    }

    /** Whether the microvoxels at a block's centre are that block's own material: a block the generator placed whole. */
    private fun builtAsBlock(pos: ChunkPos, lx: Int, ly: Int, z: Int, index: Int): Boolean {
        val mc = source.microChunk(MicroChunkPos(pos.x, pos.y, z / blocksPerMicro))
        val c = r / 2
        return mc[lx * r + c, ly * r + c, (z % blocksPerMicro) * r + c] == source.materialForBlock(index)
    }

    /** The generated microvoxels with every changed block filled in as that block (or dug out as air). */
    private fun patched(
        generated: MicroChunk, changed: IntArray, pos: ChunkPos, cz: Int, world: BlockSnapshot,
        props: MutableList<PropInstance>, lights: MutableList<PointLight>,
    ): MicroChunk {
        val grid = generated.copy()
        for (i in changed) {
            val bx = i % Chunk.SIZE; val by = (i / Chunk.SIZE) % Chunk.SIZE; val bz = i / (Chunk.SIZE * Chunk.SIZE)
            val wx = pos.originX + bx; val wy = pos.originY + by; val wz = cz * blocksPerMicro + bz
            val index = world.index(wx, wy, wz)
            val block = world.type(index)
            // Sprites and thin shapes are drawn by the prop path, as everywhere else; their cell is left open.
            val fill = if (block.isAir || block.glyph != null || block.shape != BlockShape.CUBE) MaterialPalette.AIR
            else source.materialForBlock(index)
            grid.fill(bx * r, by * r, bz * r, bx * r + r - 1, by * r + r - 1, bz * r + r - 1, fill)
            if (block.glyph != null) props += PropInstance(wx, wy, wz, block, null)
            if (block.lightEmission > 0) lights += PointLight(
                wx + 0.5f, wy + 0.5f, wz + 1.2f, block.topColor, block.lightEmission / 15f,
                TerrainMesher.LIGHT_RADIUS * block.lightEmission / 15f + 2f,
            )
        }
        return grid
    }

    /** Opacity just outside a micro chunk: the neighbour's microvoxels, or the block world where it was changed. */
    private fun neighbours(world: BlockSnapshot, mpos: MicroChunkPos) = NeighborOpacity { x, y, z ->
        val mx = mpos.originX + x; val my = mpos.originY + y; val mz = mpos.originZ + z
        if (mz < 0) return@NeighborOpacity true
        val bx = Math.floorDiv(mx, r); val by = Math.floorDiv(my, r); val bz = Math.floorDiv(mz, r)
        if (bz >= Chunk.HEIGHT) return@NeighborOpacity false
        val here = world.index(bx, by, bz)
        if (here >= 0 && here != source.generatedBlock(bx, by, bz)) {
            val b = world.type(here)
            return@NeighborOpacity b.isOpaque && b.shape == BlockShape.CUBE && b.glyph == null
        }
        val npos = MicroChunkPos.containing(mx, my, mz)
        val n = source.microChunk(npos)
        palette.isOpaque(n[mx - npos.originX, my - npos.originY, mz - npos.originZ])
    }

    private fun emit(quads: LongArray, count: Int, mpos: MicroChunkPos) {
        val s = 1f / r
        for (i in 0 until count) {
            val q = quads[i]
            val face = Quad.face(q)
            if (face == 5) continue // this camera never sees an underside
            val x = Quad.x(q); val y = Quad.y(q); val z = Quad.z(q); val w = Quad.w(q); val h = Quad.h(q)
            val material = Quad.material(q)
            val mat = palette[material]
            // Min and max of the quad on each axis, in microvoxels, with the face plane on the far side for + faces.
            val x0: Int; val x1: Int; val y0: Int; val y1: Int; val z0: Int; val z1: Int
            when (face) {
                0, 1 -> { val p = if (face == 0) x + 1 else x; x0 = p; x1 = p; y0 = y; y1 = y + w; z0 = z; z1 = z + h }
                2, 3 -> { val p = if (face == 2) y + 1 else y; y0 = p; y1 = p; x0 = x; x1 = x + w; z0 = z; z1 = z + h }
                else -> { val p = z + 1; z0 = p; z1 = p; x0 = x; x1 = x + w; y0 = y; y1 = y + h }
            }
            val ox = mpos.originX; val oy = mpos.originY; val oz = mpos.originZ
            val nx = NORMALS[face * 3]; val ny = NORMALS[face * 3 + 1]; val nz = NORMALS[face * 3 + 2]
            // A little tone per quad, from where it is: small pieces vary, big merged ones stay even.
            val tone = 1f + (hash(ox + x, oy + y, oz + z) - 0.5f) * 2f * mat.jitter * if (w * h <= 4) 1f else 0.35f
            val color = shade(mat.color, tone)
            // Lamps glow; lit windows only warm a little, or a town at noon would be lit up like a stage.
            val emissive = (mat.emission * 0.3f).coerceIn(0f, 1f)
            val order = ORDER[face]
            val idx = IntArray(4)
            val occ = FloatArray(4)
            for (k in 0 until 4) {
                val c = order[k]
                val su = c == 1 || c == 2; val sv = c >= 2
                val px: Int; val py: Int; val pz: Int
                when (face) {
                    0, 1 -> { px = x0; py = if (su) y1 else y0; pz = if (sv) z1 else z0 }
                    2, 3 -> { px = if (su) x1 else x0; py = y0; pz = if (sv) z1 else z0 }
                    else -> { px = if (su) x1 else x0; py = if (sv) y1 else y0; pz = z0 }
                }
                occ[k] = TerrainMesher.AO_LEVELS[Quad.ao(q, c)]
                idx[k] = out.vertex(
                    (ox + px) * s, (oy + py) * s, (oz + pz) * s,
                    nx, ny, nz, color, occ[k], 0f, 0f, Vertex.FLAT, emissive,
                )
            }
            if (occ[0] + occ[2] >= occ[1] + occ[3]) out.quad(idx[0], idx[1], idx[2], idx[3])
            else out.quad(idx[1], idx[2], idx[3], idx[0])
        }
    }

    /**
     * Water is not opaque, so the mesher leaves it out; its open surface is
     * drawn here as flat runs along x -- the one face of water a player sees.
     */
    private fun water(grid: MicroChunk, mpos: MicroChunkPos) {
        val waterId = runCatching { palette.id(com.stratum.engine.microvoxel.M.WATER) }.getOrNull() ?: return
        val color = shade(palette[waterId].color, 1f)
        val s = 1f / r
        val n = MicroChunk.SIZE
        for (bz in 0 until MicroChunk.BRICKS_PER_AXIS) {
            // Skip layers of bricks with no water at all without touching a voxel.
            var any = false
            for (by in 0 until MicroChunk.BRICKS_PER_AXIS) for (bx in 0 until MicroChunk.BRICKS_PER_AXIS) {
                val u = grid.brickUniform(bx, by, bz)
                if (u == null || u == waterId) any = true
            }
            if (!any) continue
            for (z in bz * MicroChunk.BRICK until (bz + 1) * MicroChunk.BRICK) for (y in 0 until n) {
                var x = 0
                while (x < n) {
                    if (grid[x, y, z] != waterId || !open(grid, mpos, x, y, z + 1)) { x++; continue }
                    val start = x
                    while (x < n && grid[x, y, z] == waterId && open(grid, mpos, x, y, z + 1)) x++
                    val zz = (mpos.originZ + z + 1) * s - WATER_DROP
                    val a = out.vertex((mpos.originX + start) * s, (mpos.originY + y) * s, zz, 0f, 0f, 1f, color, 1f, 0f, 0f, Vertex.FLAT, 0f)
                    val b = out.vertex((mpos.originX + x) * s, (mpos.originY + y) * s, zz, 0f, 0f, 1f, color, 1f, 0f, 0f, Vertex.FLAT, 0f)
                    val c = out.vertex((mpos.originX + x) * s, (mpos.originY + y + 1) * s, zz, 0f, 0f, 1f, color, 1f, 0f, 0f, Vertex.FLAT, 0f)
                    val d = out.vertex((mpos.originX + start) * s, (mpos.originY + y + 1) * s, zz, 0f, 0f, 1f, color, 1f, 0f, 0f, Vertex.FLAT, 0f)
                    out.quad(a, b, c, d)
                }
            }
        }
    }

    private fun open(grid: MicroChunk, mpos: MicroChunkPos, x: Int, y: Int, z: Int): Boolean {
        if (z < MicroChunk.SIZE) return grid[x, y, z] == MaterialPalette.AIR
        val above = source.microChunk(MicroChunkPos(mpos.x, mpos.y, mpos.z + 1))
        return above[x, y, 0] == MaterialPalette.AIR
    }

    /** Emissive voxels (street lamps) become point lights, one per lamp head. */
    private fun lamps(grid: MicroChunk, mpos: MicroChunkPos, lights: MutableList<PointLight>) {
        for (bz in 0 until MicroChunk.BRICKS_PER_AXIS) for (by in 0 until MicroChunk.BRICKS_PER_AXIS) for (bx in 0 until MicroChunk.BRICKS_PER_AXIS) {
            if (grid.brickUniform(bx, by, bz) != null) continue
            for (z in bz * 8 until bz * 8 + 8) for (y in by * 8 until by * 8 + 8) for (x in bx * 8 until bx * 8 + 8) {
                val m = palette[grid[x, y, z]]
                if (m.emission < LAMP_EMISSION) continue
                lights += PointLight(
                    (mpos.originX + x + 0.5f) / r, (mpos.originY + y + 0.5f) / r, (mpos.originZ + z) / r.toFloat(),
                    0xFF000000 or m.color.toLong(), 0.8f, TerrainMesher.LIGHT_RADIUS,
                )
            }
        }
    }

    private fun shade(rgb: Int, tone: Float): Long {
        fun ch(shift: Int) = (((rgb shr shift) and 255) * tone).toInt().coerceIn(0, 255).toLong()
        return (0xFFL shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun hash(x: Int, y: Int, z: Int): Float {
        var h = x * 374761393 + y * 668265263 + z * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777216f
    }

    companion object {
        /** Past this share of its surface changed, a chunk is drawn from blocks, not microvoxels. */
        const val MAX_CHANGED_SHARE = 0.35f

        /** Emission at which a voxel counts as a lamp and lights its surroundings. */
        const val LAMP_EMISSION = 2f

        /** Water sits a hair below its cell top, as a liquid surface should. */
        private const val WATER_DROP = 0.06f

        private val NORMALS = floatArrayOf(1f, 0f, 0f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, -1f)

        /**
         * The mesher's corners (u0v0, u1v0, u1v1, u0v1) in the winding the
         * block mesher uses for each face, so both front-face the same way.
         */
        private val ORDER = arrayOf(
            intArrayOf(1, 0, 3, 2), intArrayOf(0, 1, 2, 3),
            intArrayOf(0, 1, 2, 3), intArrayOf(1, 0, 3, 2),
            intArrayOf(0, 1, 2, 3), intArrayOf(3, 2, 1, 0),
        )
    }
}
