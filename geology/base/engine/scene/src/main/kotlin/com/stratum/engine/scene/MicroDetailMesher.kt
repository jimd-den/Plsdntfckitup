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
    private val r = source.microPerBlock
    private val blocksPerMicro = MicroChunk.SIZE / r

    /** What one meshing thread works in; several workers may mesh at once. */
    private inner class Work {
        val mesher = BinaryGreedyMesher(source.palette)
        val out = MeshBuilder(MaterialKind.OPAQUE)
        val idx = IntArray(4)
        val occ = FloatArray(4)
    }

    private val work = ThreadLocal.withInitial { Work() }
    private val out: MeshBuilder get() = work.get().out

    /** Quads emitted by the last [mesh] on the calling thread, for profiling. */
    val quadsLastMesh: Int get() = lastQuads.get()
    private val lastQuads = ThreadLocal.withInitial { 0 }

    /** Meshes from the live world; call on the thread that owns it. */
    fun mesh(world: World, pos: ChunkPos, factor: Int = 1): TerrainMesher.Result? = mesh(BlockSnapshot.of(world, pos), pos, factor)

    /**
     * Meshes from a snapshot, which any thread may do. [factor] is the level
     * of detail: 1 draws every quarter-block voxel; 2 draws half-block voxels
     * (an eighth of the voxels, about a quarter of the quads), for the far
     * ring of a view that is microvoxels to its edge.
     */
    fun mesh(world: BlockSnapshot, pos: ChunkPos, factor: Int = 1): TerrainMesher.Result? {
        requireFactor(factor)
        val w = work.get()
        w.out.clear()
        val props = ArrayList<PropInstance>()
        val lights = ArrayList<PointLight>()
        val changed = changedBlocks(world, pos) ?: return null
        var quads = 0
        for (cz in 0 until layers) quads += emitLayer(w, world, pos, cz, factor, changed[cz], props, lights)
        lastQuads.set(quads)
        return TerrainMesher.Result(finish(w), props, lights)
    }

    /**
     * Micro-chunk layers a block chunk is stacked from, bottom first. Each is
     * meshed, and remeshed, on its own by [meshLayers].
     */
    val layers: Int = Chunk.HEIGHT / blocksPerMicro

    /** Blocks one layer is tall. */
    val blocksPerLayer: Int get() = blocksPerMicro

    /**
     * The chunk as one mesh per layer, bottom first, remaking only the layers
     * [only] marks and taking the rest from [previous].
     *
     * This is what makes an edit cheap enough to show on the frame it
     * happens. A block laid on the ground changes one layer of the chunk --
     * two when it sits on a layer boundary and the shading across it
     * changes too -- and a layer is a third of the work of the whole chunk.
     * The layers left alone keep their meshes, and a GPU backend that caches
     * uploads by identity (the GLES renderer does) does not upload them again.
     *
     * Null, as from [mesh], when the chunk has changed too much for detail.
     */
    fun meshLayers(
        world: BlockSnapshot,
        pos: ChunkPos,
        factor: Int = 1,
        only: BooleanArray? = null,
        previous: List<TerrainMesher.Result>? = null,
    ): List<TerrainMesher.Result>? {
        requireFactor(factor)
        val w = work.get()
        val changed = changedBlocks(world, pos) ?: return null
        val reuse = only != null && previous != null && previous.size == layers
        var quads = 0
        val out = ArrayList<TerrainMesher.Result>(layers)
        for (cz in 0 until layers) {
            if (reuse && !only!![cz]) { out += previous!![cz]; continue }
            w.out.clear()
            val props = ArrayList<PropInstance>()
            val lights = ArrayList<PointLight>()
            quads += emitLayer(w, world, pos, cz, factor, changed[cz], props, lights)
            out += TerrainMesher.Result(finish(w), props, lights)
        }
        lastQuads.set(quads)
        return out
    }

    private fun requireFactor(factor: Int) = require(factor == 1 || factor == 2 || factor == 4) { "level of detail $factor is 1, 2 or 4" }

    private fun finish(w: Work): MeshBatch {
        val built = w.out.build()
        // A dense chunk grows the builder to many megabytes; do not keep that for every worker for ever.
        w.out.trimTo(MAX_KEPT_FLOATS)
        return built
    }

    /** Emits one micro-chunk layer of a block chunk into the worker's builder; returns its quads. */
    private fun emitLayer(
        w: Work, world: BlockSnapshot, pos: ChunkPos, cz: Int, factor: Int, mine: IntArray,
        props: MutableList<PropInstance>, lights: MutableList<PointLight>,
    ): Int {
        val mpos = MicroChunkPos(pos.x, pos.y, cz)
        val generated = source.microChunk(mpos)
        if (generated.isEmpty() && mine.isEmpty()) return 0
        val grid = if (mine.isEmpty()) generated else patched(generated, mine, pos, cz, world, props, lights)
        val neighbours = neighbours(world, mpos)
        val mesh = if (factor == 1) w.mesher.mesh(grid, neighbours, ambientOcclusion = true)
        else w.mesher.mesh(
            com.stratum.engine.microvoxel.mesh.Lod.downsample(grid, factor, palette),
            // Opacity beyond a coarse grid's edge, read at the middle of the coarse cell it would be.
            NeighborOpacity { x, y, z -> neighbours.opaque(x * factor + factor / 2, y * factor + factor / 2, z * factor + factor / 2) },
            ambientOcclusion = true,
        )
        emit(w, mesh.quads, mesh.count, mpos, factor)
        water(grid, mpos)
        lamps(grid, mpos, lights)
        return mesh.count
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
        // The whole chunk as generated, read once; asking block by block took a lock per block.
        val generated = source.generatedChunk(pos.x, pos.y)
        for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
            val wx = pos.originX + x; val wy = pos.originY + y
            val top = world.surface(wx, wy)
            for (z in 0 until Chunk.HEIGHT) {
                val here = world.index(wx, wy, z)
                if (here < 0) continue
                val cell = (z % blocksPerMicro) * Chunk.SIZE * Chunk.SIZE + y * Chunk.SIZE + x
                val made = if (generated != null) generated[Chunk.indexOf(x, y, z)].toInt() else source.generatedBlock(wx, wy, z)
                if (here == made) {
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
    private fun neighbours(world: BlockSnapshot, mpos: MicroChunkPos): NeighborOpacity {
        // The mesher asks thousands of times along each face, almost always of the same neighbour.
        var lastPos: MicroChunkPos? = null
        var last: MicroChunk? = null
        // Likewise the generated blocks: one block chunk's worth, read whole, instead of a locked lookup per voxel.
        var madeX = Int.MIN_VALUE; var madeY = Int.MIN_VALUE
        var made: ShortArray? = null
        return NeighborOpacity { x, y, z ->
        val mx = mpos.originX + x; val my = mpos.originY + y; val mz = mpos.originZ + z
        if (mz < 0) return@NeighborOpacity true
        val bx = Math.floorDiv(mx, r); val by = Math.floorDiv(my, r); val bz = Math.floorDiv(mz, r)
        if (bz >= Chunk.HEIGHT) return@NeighborOpacity false
        val here = world.index(bx, by, bz)
        val cx = Math.floorDiv(bx, Chunk.SIZE); val cy = Math.floorDiv(by, Chunk.SIZE)
        if (cx != madeX || cy != madeY) { made = source.generatedChunk(cx, cy); madeX = cx; madeY = cy }
        val generated = made?.let { it[Chunk.indexOf(bx - cx * Chunk.SIZE, by - cy * Chunk.SIZE, bz)].toInt() } ?: source.generatedBlock(bx, by, bz)
        if (here >= 0 && here != generated) {
            val b = world.type(here)
            return@NeighborOpacity b.isOpaque && b.shape == BlockShape.CUBE && b.glyph == null
        }
        val npos = MicroChunkPos.containing(mx, my, mz)
        val n = if (npos == lastPos) last!! else source.microChunk(npos).also { lastPos = npos; last = it }
        palette.isOpaque(n[mx - npos.originX, my - npos.originY, mz - npos.originZ])
        }
    }

    private fun emit(work: Work, quads: LongArray, count: Int, mpos: MicroChunkPos, factor: Int) {
        val out = work.out
        val s = factor.toFloat() / r
        val ox = mpos.originX / factor; val oy = mpos.originY / factor; val oz = mpos.originZ / factor
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
            val nx = NORMALS[face * 3]; val ny = NORMALS[face * 3 + 1]; val nz = NORMALS[face * 3 + 2]
            // A little tone per quad, from where it is: small pieces vary, big merged ones stay even.
            val tone = 1f + (hash(ox + x, oy + y, oz + z) - 0.5f) * 2f * mat.jitter * if (w * h <= 4) 1f else 0.35f
            val color = shade(mat.color, tone)
            // Lamps glow; lit windows only warm a little, or a town at noon would be lit up like a stage.
            val emissive = (mat.emission * 0.3f).coerceIn(0f, 1f)
            val order = ORDER[face]
            val idx = work.idx
            val occ = work.occ
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

        /** Vertex floats a worker's builder keeps between meshes (1 MB); anything larger is given back. */
        private const val MAX_KEPT_FLOATS = 1 shl 18

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
