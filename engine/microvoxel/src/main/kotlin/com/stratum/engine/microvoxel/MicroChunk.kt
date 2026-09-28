package com.stratum.engine.microvoxel

import com.stratum.engine.microvoxel.mesh.VoxelGrid

/**
 * Where a [MicroChunk] sits, in chunk units on all three axes.
 *
 * The world is chunked vertically too, so height is as unbounded as distance:
 * an empty sky chunk costs one uniform brick table and is never meshed.
 */
data class MicroChunkPos(val x: Int, val y: Int, val z: Int) {
    val originX: Int get() = x * MicroChunk.SIZE
    val originY: Int get() = y * MicroChunk.SIZE
    val originZ: Int get() = z * MicroChunk.SIZE

    companion object {
        fun containing(mx: Int, my: Int, mz: Int) = MicroChunkPos(
            Math.floorDiv(mx, MicroChunk.SIZE),
            Math.floorDiv(my, MicroChunk.SIZE),
            Math.floorDiv(mz, MicroChunk.SIZE),
        )
    }
}

/**
 * A 64 x 64 x 64 cube of microvoxels, stored as a brick map.
 *
 * Why 64: a row of 64 voxels is exactly one `Long`, which is what makes the
 * binary greedy mesher possible (see [BinaryGreedyMesher]) -- face culling of a
 * whole row is one shift, one and-not.
 *
 * Why bricks: most of a world is either solid rock or empty sky. The chunk is
 * split into 8 x 8 x 8 bricks of 8^3 voxels; a brick whose voxels are all the
 * same material is stored as a single short. Only bricks that actually carry
 * detail -- the ground surface, a facade, a tree -- pay for per-voxel storage,
 * and that storage is palette-compressed to one byte per voxel. An empty chunk
 * is ~1 KB; a typical surface chunk 30-80 KB, against 512 KB for a flat array.
 *
 * Coordinates are local, x/y horizontal and z up, as in the block world.
 */
class MicroChunk(val pos: MicroChunkPos, fill: Short = MaterialPalette.AIR) : VoxelGrid {

    override val size: Int get() = SIZE

    private val uniform = ShortArray(BRICKS) { fill }
    private val detail = arrayOfNulls<Brick>(BRICKS)

    /** Bumped on every change, so meshers and renderers can skip untouched chunks. */
    var revision: Int = 0
        private set

    override operator fun get(x: Int, y: Int, z: Int): Short {
        val b = brickIndex(x, y, z)
        val brick = detail[b] ?: return uniform[b]
        return brick[voxelIndex(x, y, z)]
    }

    /** Writes a voxel; out-of-range coordinates are ignored. Returns whether anything changed. */
    fun set(x: Int, y: Int, z: Int, material: Short): Boolean {
        if (x !in 0 until SIZE || y !in 0 until SIZE || z !in 0 until SIZE) return false
        val b = brickIndex(x, y, z)
        var brick = detail[b]
        if (brick == null) {
            if (uniform[b] == material) return false
            brick = Brick(uniform[b]).also { detail[b] = it }
        }
        val changed = brick.set(voxelIndex(x, y, z), material)
        if (changed) revision++
        return changed
    }

    /** Fills an inclusive box, clipped to the chunk. Whole bricks inside it are written as uniform. */
    fun fill(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, material: Short) {
        val ax = maxOf(0, x0); val bx = minOf(SIZE - 1, x1)
        val ay = maxOf(0, y0); val by = minOf(SIZE - 1, y1)
        val az = maxOf(0, z0); val bz = minOf(SIZE - 1, z1)
        if (ax > bx || ay > by || az > bz) return
        for (bz0 in (az / BRICK) .. (bz / BRICK)) for (by0 in (ay / BRICK)..(by / BRICK)) for (bx0 in (ax / BRICK)..(bx / BRICK)) {
            val cx0 = bx0 * BRICK; val cy0 = by0 * BRICK; val cz0 = bz0 * BRICK
            val covers = ax <= cx0 && bx >= cx0 + BRICK - 1 && ay <= cy0 && by >= cy0 + BRICK - 1 && az <= cz0 && bz >= cz0 + BRICK - 1
            if (covers) {
                val b = (bz0 * BRICKS_PER_AXIS + by0) * BRICKS_PER_AXIS + bx0
                detail[b] = null
                uniform[b] = material
                revision++
            } else {
                for (z in maxOf(az, cz0)..minOf(bz, cz0 + BRICK - 1))
                    for (y in maxOf(ay, cy0)..minOf(by, cy0 + BRICK - 1))
                        for (x in maxOf(ax, cx0)..minOf(bx, cx0 + BRICK - 1)) set(x, y, z, material)
            }
        }
    }

    /** True when every voxel is [MaterialPalette.AIR]: nothing to mesh, nothing to collide with. */
    fun isEmpty(): Boolean = detail.all { it == null } && uniform.all { it == MaterialPalette.AIR }

    /** Collapses bricks that edits have made uniform again. Generators call it once at the end. */
    fun compact() {
        for (b in 0 until BRICKS) {
            val brick = detail[b] ?: continue
            val single = brick.singleMaterial() ?: continue
            detail[b] = null
            uniform[b] = single
        }
    }

    /** How many bricks carry per-voxel detail. The memory story of a chunk, in one number. */
    val detailBrickCount: Int get() = detail.count { it != null }

    /** Approximate heap bytes, for the budget tests and the debug overlay. */
    fun approximateBytes(): Int = BRICKS * 2 + BRICKS * 8 + detail.sumOf { it?.approximateBytes() ?: 0 }

    /** The uniform material of a brick, or null when it carries detail. Lets meshers and LOD skip bricks wholesale. */
    fun brickUniform(bx: Int, by: Int, bz: Int): Short? {
        val b = (bz * BRICKS_PER_AXIS + by) * BRICKS_PER_AXIS + bx
        return if (detail[b] == null) uniform[b] else null
    }

    /**
     * Occupancy rows along x: bit `x` of `rows[z * SIZE + y]` is set when that
     * voxel is opaque. The input of [BinaryGreedyMesher].
     */
    fun opaqueRows(palette: MaterialPalette, out: LongArray = LongArray(SIZE * SIZE)): LongArray {
        for (z in 0 until SIZE) for (y in 0 until SIZE) {
            var row = 0L
            for (bx in 0 until BRICKS_PER_AXIS) {
                val u = brickUniform(bx, y / BRICK, z / BRICK)
                if (u != null) {
                    if (palette.isOpaque(u)) row = row or (0xFFL shl (bx * BRICK))
                } else {
                    for (i in 0 until BRICK) {
                        val x = bx * BRICK + i
                        if (palette.isOpaque(get(x, y, z))) row = row or (1L shl x)
                    }
                }
            }
            out[z * SIZE + y] = row
        }
        return out
    }

    /** Raw copy of every voxel, x fastest. For saving and tests; never on a hot path. */
    fun export(): ShortArray {
        val out = ShortArray(SIZE * SIZE * SIZE)
        for (z in 0 until SIZE) for (y in 0 until SIZE) for (x in 0 until SIZE) out[(z * SIZE + y) * SIZE + x] = get(x, y, z)
        return out
    }

    companion object {
        const val SIZE = 64
        const val BRICK = 8
        const val BRICKS_PER_AXIS = SIZE / BRICK
        const val BRICKS = BRICKS_PER_AXIS * BRICKS_PER_AXIS * BRICKS_PER_AXIS

        private fun brickIndex(x: Int, y: Int, z: Int): Int =
            ((z shr 3) * BRICKS_PER_AXIS + (y shr 3)) * BRICKS_PER_AXIS + (x shr 3)

        private fun voxelIndex(x: Int, y: Int, z: Int): Int = ((z and 7) shl 6) or ((y and 7) shl 3) or (x and 7)
    }
}

/**
 * One 8^3 brick with per-voxel detail: a small palette plus one byte per
 * voxel. Degrades to raw shorts in the (pathological) case of more than 256
 * materials in 512 voxels.
 */
private class Brick(initial: Short) {
    private var palette = ShortArray(4).also { it[0] = initial }
    private var paletteSize = 1
    private var indices: ByteArray? = ByteArray(VOXELS)
    private var raw: ShortArray? = null

    operator fun get(i: Int): Short {
        raw?.let { return it[i] }
        return palette[indices!![i].toInt() and 0xFF]
    }

    fun set(i: Int, material: Short): Boolean {
        raw?.let { if (it[i] == material) return false; it[i] = material; return true }
        val idx = indices!!
        if (palette[idx[i].toInt() and 0xFF] == material) return false
        var slot = -1
        for (p in 0 until paletteSize) if (palette[p] == material) { slot = p; break }
        if (slot < 0) {
            if (paletteSize == 256) {
                raw = ShortArray(VOXELS) { get(it) }.also { it[i] = material }
                indices = null
                return true
            }
            if (paletteSize == palette.size) palette = palette.copyOf(palette.size * 2)
            slot = paletteSize++
            palette[slot] = material
        }
        idx[i] = slot.toByte()
        return true
    }

    fun singleMaterial(): Short? {
        val first = get(0)
        for (i in 1 until VOXELS) if (get(i) != first) return null
        return first
    }

    fun approximateBytes(): Int = raw?.let { it.size * 2 } ?: (VOXELS + palette.size * 2 + 32)

    companion object { const val VOXELS = 512 }
}
