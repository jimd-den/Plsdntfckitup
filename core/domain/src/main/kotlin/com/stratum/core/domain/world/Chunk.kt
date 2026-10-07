package com.stratum.core.domain.world

/**
 * A 16x16 column of the world, full height.
 *
 * Blocks are stored as registry indices in a flat `ShortArray`, which keeps a
 * loaded chunk at roughly 24 KB instead of the megabyte an object grid would
 * cost. [heightMap] caches the highest non-air block per column because the
 * isometric renderer asks that question for every visible column, every frame.
 */
class Chunk(
    val pos: ChunkPos,
    private val blocks: ShortArray = ShortArray(VOLUME),
    private val light: ByteArray = ByteArray(VOLUME),
) {
    init {
        require(blocks.size == VOLUME) { "Chunk needs $VOLUME cells, got ${blocks.size}" }
        require(light.size == VOLUME) { "Chunk needs $VOLUME light cells, got ${light.size}" }
    }

    private val heightMap = IntArray(SIZE * SIZE) { -1 }
    private var heightMapValid = false

    /** Bumped on every mutation so renderers and meshers can skip untouched chunks. */
    var revision: Int = 0
        private set

    /**
     * Bumped only when a block in the outermost ring of columns changes.
     *
     * A neighbour's mesh reads one cell into this chunk -- the faces it shares
     * and the shading at its corners -- and nothing deeper, so it depends on
     * this counter rather than on [revision]. Without the distinction every
     * block dug remeshed the nine chunks around it.
     */
    var edgeRevision: Int = 0
        private set

    /**
     * [edgeRevision] split by the neighbour that reads it: one counter per
     * side (the column of cells along it) and per corner (the one column
     * where two sides meet), indexed as [sideRevision] takes them. The chunk
     * to the east reads only this chunk's east column, and the one to the
     * north-east only its north-east corner, so a block laid against the west
     * side remeshes the west neighbour and no other. With one counter,
     * building a wall along a chunk border remeshed all eight neighbours per
     * block -- the biggest cost of a placement.
     */
    private val sideRevisions = IntArray(9)

    /**
     * How often the cells this chunk shares with the neighbour in direction
     * ([dx], [dy]), each -1, 0 or 1, have changed: a side's column, or for a
     * diagonal the corner column. (0, 0) is the whole chunk's [revision].
     * Only ever grows.
     */
    fun sideRevision(dx: Int, dy: Int): Int =
        if (dx == 0 && dy == 0) revision else sideRevisions[(dy.coerceIn(-1, 1) + 1) * 3 + dx.coerceIn(-1, 1) + 1]

    /**
     * Marks the chunk changed without changing a block: the microvoxels
     * behind it changed (a statue stamped in), so its detail must be drawn
     * again though every block reads the same.
     */
    fun touch() {
        revision++
        edgeRevision++
        for (i in sideRevisions.indices) sideRevisions[i]++
    }

    fun blockAt(localX: Int, localY: Int, z: Int): Int {
        if (!isInBounds(localX, localY, z)) return BlockRegistry.AIR_INDEX
        return blocks[indexOf(localX, localY, z)].toInt()
    }

    fun setBlock(localX: Int, localY: Int, z: Int, index: Int): Boolean {
        if (!isInBounds(localX, localY, z)) return false
        val cell = indexOf(localX, localY, z)
        val previous = blocks[cell].toInt()
        if (previous == index) return false
        blocks[cell] = index.toShort()
        // Keep the height map current for this one column instead of throwing it all away:
        // a fight's craters and walls edit a chunk every few frames, and each rebuild reads
        // every cell in it.
        if (heightMapValid) {
            val col = localY * SIZE + localX
            val top = heightMap[col]
            if (index != BlockRegistry.AIR_INDEX) { if (z > top) heightMap[col] = z }
            else if (z == top) {
                var highest = -1
                for (zz in z - 1 downTo 0) if (blocks[indexOf(localX, localY, zz)].toInt() != BlockRegistry.AIR_INDEX) { highest = zz; break }
                heightMap[col] = highest
            }
        }
        revision++
        if (localX == 0 || localY == 0 || localX == SIZE - 1 || localY == SIZE - 1) edgeRevision++
        val sx = if (localX == 0) -1 else if (localX == SIZE - 1) 1 else 0
        val sy = if (localY == 0) -1 else if (localY == SIZE - 1) 1 else 0
        if (sx != 0) sideRevisions[4 + sx]++
        if (sy != 0) sideRevisions[4 + sy * 3]++
        if (sx != 0 && sy != 0) sideRevisions[4 + sy * 3 + sx]++
        return true
    }

    fun lightAt(localX: Int, localY: Int, z: Int): Int {
        if (!isInBounds(localX, localY, z)) return 0
        return light[indexOf(localX, localY, z)].toInt()
    }

    fun setLight(localX: Int, localY: Int, z: Int, level: Int) {
        if (!isInBounds(localX, localY, z)) return
        light[indexOf(localX, localY, z)] = level.coerceIn(0, MAX_LIGHT).toByte()
    }

    /**
     * Highest non-air z in the column, or -1 when the column is empty. This is
     * the surface the isometric renderer draws and the value entity placement
     * uses to drop actors onto terrain.
     */
    fun surfaceAt(localX: Int, localY: Int): Int {
        if (!heightMapValid) rebuildHeightMap()
        if (localX !in 0 until SIZE || localY !in 0 until SIZE) return -1
        return heightMap[localY * SIZE + localX]
    }

    private fun rebuildHeightMap() {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                var highest = -1
                for (z in HEIGHT - 1 downTo 0) {
                    if (blocks[indexOf(x, y, z)].toInt() != BlockRegistry.AIR_INDEX) {
                        highest = z
                        break
                    }
                }
                heightMap[y * SIZE + x] = highest
            }
        }
        heightMapValid = true
    }

    /** Copy of the raw cells, for persistence. */
    fun exportBlocks(): ShortArray = blocks.copyOf()

    fun exportLight(): ByteArray = light.copyOf()

    companion object {
        const val SIZE = 16
        const val HEIGHT = 48
        const val VOLUME = SIZE * SIZE * HEIGHT
        const val MAX_LIGHT = 15

        /** Column-major within a layer, layers stacked: keeps a column contiguous. */
        fun indexOf(localX: Int, localY: Int, z: Int): Int =
            (z * SIZE * SIZE) + (localY * SIZE) + localX

        fun isInBounds(localX: Int, localY: Int, z: Int): Boolean =
            localX in 0 until SIZE && localY in 0 until SIZE && z in 0 until HEIGHT

        fun restore(pos: ChunkPos, blocks: ShortArray, light: ByteArray): Chunk =
            Chunk(pos, blocks.copyOf(), light.copyOf())
    }
}
