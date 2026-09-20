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
        heightMapValid = false
        revision++
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
