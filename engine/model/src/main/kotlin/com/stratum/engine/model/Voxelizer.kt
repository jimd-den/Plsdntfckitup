package com.stratum.engine.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A model as coloured cells: what a mesh becomes on its way to being blocks.
 *
 * [colors] is ARGB per cell, x fastest then y then z, with 0 for empty. Cell
 * (0, 0, 0) sits at ([originX], [originY], 0) in the model's own block units,
 * so the model's centre — the origin, after [ModelNormalizer] — can be found
 * again when the grid is raised in the world.
 */
class VoxelGrid(
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val colors: IntArray,
    val originX: Int,
    val originY: Int,
) {
    init {
        require(colors.size == sizeX * sizeY * sizeZ) { "a ${sizeX}x${sizeY}x$sizeZ grid needs ${sizeX * sizeY * sizeZ} cells" }
    }

    fun index(x: Int, y: Int, z: Int): Int = (z * sizeY + y) * sizeX + x
    fun colorAt(x: Int, y: Int, z: Int): Int = colors[index(x, y, z)]
    fun isFilled(x: Int, y: Int, z: Int): Boolean = colors[index(x, y, z)] != 0
    val filledCount: Int get() = colors.count { it != 0 }
}

/**
 * Turns a normalised mesh into a [VoxelGrid] of one cell per block (or finer).
 *
 * The surface is found by sampling every triangle densely enough that no cell
 * it crosses is skipped — at most half a cell between samples — and each cell
 * takes the average colour of the surface that passed through it. That is
 * cruder than exact triangle-box overlap and far more forgiving: generated
 * meshes are rarely watertight, and sampling never needs them to be.
 *
 * With [solid] the inside is filled too, by flooding the empty space from the
 * outside and filling what the flood could not reach, each filled cell taking
 * the colour of the surface above it. A statue raised as blocks should be
 * stone all the way through, or mining it reveals a hollow shell. A model with
 * holes in its skin floods through them and stays hollow, which is the safe
 * way to be wrong.
 */
object Voxelizer {

    fun voxelize(mesh: ModelMesh, cellsPerBlock: Int = 1, solid: Boolean = true, maxCells: Int = MAX_EDGE): VoxelGrid {
        require(cellsPerBlock in 1..8) { "between one and eight cells a block" }
        val box = mesh.bounds() ?: throw ModelFormatException("The model has no vertices")
        val scale = cellsPerBlock.toFloat()
        // One empty cell of margin on every side, so the flood can get all the way round.
        val originX = floor(box.minX * scale).toInt() - 1
        val originY = floor(box.minY * scale).toInt() - 1
        val originZ = floor(box.minZ * scale).toInt().coerceAtMost(0) - 1
        val sizeX = ceil(box.maxX * scale).toInt() + 1 - originX + 1
        val sizeY = ceil(box.maxY * scale).toInt() + 1 - originY + 1
        val sizeZ = ceil(box.maxZ * scale).toInt() + 1 - originZ + 1
        if (maxOf(sizeX, sizeY, sizeZ) - 2 > maxCells) {
            throw ModelFormatException("The model is ${maxOf(sizeX, sizeY, sizeZ) - 2} cells across; the most a structure can be is $maxCells")
        }
        val cells = sizeX * sizeY * sizeZ
        val a = IntArray(cells); val r = IntArray(cells); val g = IntArray(cells); val b = IntArray(cells); val n = IntArray(cells)
        val p = mesh.positions

        fun mark(t: Int, w0: Float, w1: Float, w2: Float) {
            val i0 = mesh.indices[t * 3]; val i1 = mesh.indices[t * 3 + 1]; val i2 = mesh.indices[t * 3 + 2]
            val x = (p[i0 * 3] * w0 + p[i1 * 3] * w1 + p[i2 * 3] * w2) * scale
            val y = (p[i0 * 3 + 1] * w0 + p[i1 * 3 + 1] * w1 + p[i2 * 3 + 1] * w2) * scale
            val z = (p[i0 * 3 + 2] * w0 + p[i1 * 3 + 2] * w1 + p[i2 * 3 + 2] * w2) * scale
            val cx = (floor(x).toInt() - originX).coerceIn(1, sizeX - 2)
            val cy = (floor(y).toInt() - originY).coerceIn(1, sizeY - 2)
            val cz = (floor(z).toInt() - originZ).coerceIn(1, sizeZ - 2)
            val c = mesh.colorAt(t, w0, w1, w2)
            val i = (cz * sizeY + cy) * sizeX + cx
            a[i] += Colors.a(c); r[i] += Colors.r(c); g[i] += Colors.g(c); b[i] += Colors.b(c); n[i]++
        }

        for (t in 0 until mesh.triangleCount) {
            val i0 = mesh.indices[t * 3]; val i1 = mesh.indices[t * 3 + 1]; val i2 = mesh.indices[t * 3 + 2]
            val longest = maxOf(edge(p, i0, i1), edge(p, i1, i2), edge(p, i2, i0)) * scale
            val steps = (ceil(longest / SAMPLE_SPACING).toInt()).coerceIn(1, MAX_STEPS)
            for (i in 0..steps) for (j in 0..steps - i) {
                val w1 = i.toFloat() / steps
                val w2 = j.toFloat() / steps
                mark(t, 1f - w1 - w2, w1, w2)
            }
            mark(t, 1f / 3, 1f / 3, 1f / 3)
        }

        val colors = IntArray(cells)
        for (i in 0 until cells) {
            if (n[i] == 0) continue
            // Transparent surfaces — glass, leaves cut out by alpha — do not become blocks.
            if (a[i] / n[i] < MIN_ALPHA) continue
            colors[i] = Colors.argb(255, r[i] / n[i], g[i] / n[i], b[i] / n[i])
        }
        if (solid) fillInside(colors, sizeX, sizeY, sizeZ)
        return crop(colors, sizeX, sizeY, sizeZ, originX, originY, originZ)
    }

    private fun edge(p: FloatArray, i: Int, j: Int): Float {
        val dx = p[i * 3] - p[j * 3]; val dy = p[i * 3 + 1] - p[j * 3 + 1]; val dz = p[i * 3 + 2] - p[j * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Floods the outside from a corner of the margin; whatever it cannot reach is inside. */
    private fun fillInside(colors: IntArray, sx: Int, sy: Int, sz: Int) {
        val outside = BooleanArray(colors.size)
        val queue = IntArray(colors.size)
        var head = 0; var tail = 0
        outside[0] = true; queue[tail++] = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % sx; val y = (i / sx) % sy; val z = i / (sx * sy)
            fun visit(nx: Int, ny: Int, nz: Int) {
                if (nx !in 0 until sx || ny !in 0 until sy || nz !in 0 until sz) return
                val j = (nz * sy + ny) * sx + nx
                if (outside[j] || colors[j] != 0) return
                outside[j] = true; queue[tail++] = j
            }
            visit(x - 1, y, z); visit(x + 1, y, z); visit(x, y - 1, z); visit(x, y + 1, z); visit(x, y, z - 1); visit(x, y, z + 1)
        }
        // Inside cells take the colour of the nearest surface above them in their column.
        for (y in 0 until sy) for (x in 0 until sx) {
            var above = 0
            for (z in sz - 1 downTo 0) {
                val i = (z * sy + y) * sx + x
                if (colors[i] != 0) above = colors[i]
                else if (!outside[i] && above != 0) colors[i] = above
            }
        }
    }

    /** Trims empty space, keeping the ground at z = 0 so the model still stands on it. */
    private fun crop(colors: IntArray, sx: Int, sy: Int, sz: Int, originX: Int, originY: Int, originZ: Int): VoxelGrid {
        var minX = sx; var minY = sy; var maxX = -1; var maxY = -1; var maxZ = -1; var minZ = sz
        for (z in 0 until sz) for (y in 0 until sy) for (x in 0 until sx) {
            if (colors[(z * sy + y) * sx + x] == 0) continue
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
            minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
        }
        if (maxX < 0) throw ModelFormatException("The model is too thin or too transparent to make any blocks")
        // The ground is world z = 0, which is cell -originZ; the grid starts there even if the model floats.
        val groundZ = -originZ
        val startZ = minOf(minZ, groundZ)
        val w = maxX - minX + 1; val h = maxY - minY + 1; val d = maxZ - startZ + 1
        val out = IntArray(w * h * d)
        for (z in 0 until d) for (y in 0 until h) for (x in 0 until w) {
            out[(z * h + y) * w + x] = colors[((z + startZ) * sy + (y + minY)) * sx + (x + minX)]
        }
        return VoxelGrid(w, h, d, out, originX + minX, originY + minY)
    }

    /** Samples at most this far apart, in cells, so no crossed cell is missed. */
    private const val SAMPLE_SPACING = 0.5f

    /** Caps the work for one enormous triangle; the grid is at most [MAX_EDGE] across anyway. */
    private const val MAX_STEPS = 256

    private const val MIN_ALPHA = 128

    const val MAX_EDGE = 64
}
