package com.stratum.core.domain.content

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.MutableWorld

/**
 * A model turned into blocks: a small grid of block ids that can be raised in
 * the world as a structure.
 *
 * Block ids rather than colours, because a structure in a block world has to be
 * made of blocks the world knows — minable, buildable, dropping what they are
 * made of — and the colours of a generated model are only a guide to which of
 * the pack's blocks to use. The mapping from colour to block happens once,
 * when the blueprint is made, so raising it is only copying.
 *
 * Cells run x fastest, then y, then z: `(z * sizeY + y) * sizeX + x`. Zero is
 * empty and `n` is `palette[n - 1]`.
 */
data class VoxelBlueprint(
    val id: String,
    val name: String,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val palette: List<String>,
    val cells: IntArray,
    /** The cell in plan that stands on the spot the blueprint is raised at; its base is at z = 0. */
    val anchorX: Int = sizeX / 2,
    val anchorY: Int = sizeY / 2,
) {
    init {
        require(sizeX > 0 && sizeY > 0 && sizeZ > 0) { "blueprint '$id' has no size" }
        require(sizeX.toLong() * sizeY * sizeZ <= MAX_CELLS) { "blueprint '$id' is larger than $MAX_CELLS cells" }
        require(cells.size == sizeX * sizeY * sizeZ) { "blueprint '$id' needs ${sizeX * sizeY * sizeZ} cells, has ${cells.size}" }
        require(cells.all { it in 0..palette.size }) { "blueprint '$id' names a block outside its palette" }
    }

    fun index(x: Int, y: Int, z: Int): Int = (z * sizeY + y) * sizeX + x

    /** The block at a cell, or null when it is empty. */
    fun blockAt(x: Int, y: Int, z: Int): String? = cells[index(x, y, z)].takeIf { it > 0 }?.let { palette[it - 1] }

    val filledCount: Int get() = cells.count { it > 0 }

    /** How many of each block raising it would place, which is what it costs. */
    fun bill(): Map<String, Int> = cells.filter { it > 0 }.groupingBy { palette[it - 1] }.eachCount()

    /** Every filled cell, placed with the anchor on [origin]. */
    fun placements(origin: BlockPos): Sequence<Pair<BlockPos, String>> = sequence {
        for (z in 0 until sizeZ) for (y in 0 until sizeY) for (x in 0 until sizeX) {
            val block = blockAt(x, y, z) ?: continue
            yield(BlockPos(origin.x + x - anchorX, origin.y + y - anchorY, origin.z + z) to block)
        }
    }

    /**
     * Raises the blueprint in [world] with its anchor on [origin].
     *
     * Only into cells [replaceable] allows — by default only air — so a
     * statue raised against a hillside is buried in it rather than carving the
     * hill away, and never overwrites a chest or a door. Blocks the world does
     * not know are skipped rather than placed as air. Returns how many blocks
     * were placed.
     */
    fun raise(
        world: MutableWorld,
        origin: BlockPos,
        replaceable: (BlockPos) -> Boolean = { world.blockAt(it).isAir },
    ): Int {
        var placed = 0
        placements(origin).forEach { (pos, blockId) ->
            if (!world.registry.contains(blockId) || !replaceable(pos)) return@forEach
            if (world.setBlock(pos, world.registry.indexOf(blockId))) placed++
        }
        return placed
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is VoxelBlueprint && id == other.id && name == other.name && sizeX == other.sizeX &&
                sizeY == other.sizeY && sizeZ == other.sizeZ && palette == other.palette &&
                anchorX == other.anchorX && anchorY == other.anchorY && cells.contentEquals(other.cells)
            )

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + palette.hashCode()) + cells.contentHashCode()

    companion object {
        /** 64 blocks on a side; anything bigger is a region, not a structure. */
        const val MAX_CELLS = 64 * 64 * 64
    }
}
