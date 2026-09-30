package com.stratum.engine.model

import com.stratum.core.domain.content.VoxelBlueprint
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockType

/**
 * The blocks a voxelised model may be built from, by the colour each one looks.
 *
 * Only plain building blocks qualify: full solid cubes that are not props,
 * lights, liquids or unbreakable. A statue made partly of torches, water or
 * bedrock would be a hazard, not a statue. Which blocks those are is read from
 * whatever packs are loaded; the engine names none of them.
 */
class BlockPalette(val entries: List<Entry>) {

    /** A block and the colour it reads as from the game camera. */
    data class Entry(val blockId: String, val color: Int)

    init {
        require(entries.isNotEmpty()) { "a palette needs at least one block" }
    }

    /** The block whose colour is closest to [color]. */
    fun nearest(color: Int): Entry = entries.minBy { Colors.distance(it.color, color) }

    /**
     * Maps every cell to its nearest block, giving the blueprint only the
     * blocks it actually uses.
     */
    fun toBlueprint(grid: VoxelGrid, id: String, name: String): VoxelBlueprint {
        val cache = HashMap<Int, Int>()
        val used = ArrayList<String>()
        val cells = IntArray(grid.colors.size) { i ->
            val color = grid.colors[i]
            if (color == 0) 0 else cache.getOrPut(color) {
                val block = nearest(color).blockId
                val at = used.indexOf(block)
                if (at >= 0) at + 1 else { used += block; used.size }
            }
        }
        // The anchor is the cell over the model's own centre, which was the origin.
        return VoxelBlueprint(
            id = id, name = name, sizeX = grid.sizeX, sizeY = grid.sizeY, sizeZ = grid.sizeZ,
            palette = used, cells = cells,
            anchorX = (-grid.originX).coerceIn(0, grid.sizeX - 1),
            anchorY = (-grid.originY).coerceIn(0, grid.sizeY - 1),
        )
    }

    companion object {

        /** Plain building blocks from [blocks], each by the average of its top and side colours. */
        fun of(blocks: Collection<BlockType>): BlockPalette? {
            val usable = blocks.filter(::isBuildingBlock)
            if (usable.isEmpty()) return null
            return BlockPalette(usable.map { Entry(it.id, colorOf(it)) })
        }

        fun isBuildingBlock(block: BlockType): Boolean =
            !block.isAir && block.isSolid && block.isOpaque && block.isBreakable && block.shape == BlockShape.CUBE &&
                block.glyph == null && block.lightEmission == 0 && !block.hasGravity && !block.needsSupport

        /**
         * A block's colour as the camera mostly sees it: the top weighs more than
         * the side, because at fifty degrees above the horizon it covers more
         * of the screen.
         */
        fun colorOf(block: BlockType): Int {
            val top = block.topColor.toInt(); val side = block.sideColor.toInt()
            return Colors.argb(
                255,
                (Colors.r(top) * 3 + Colors.r(side) * 2) / 5,
                (Colors.g(top) * 3 + Colors.g(side) * 2) / 5,
                (Colors.b(top) * 3 + Colors.b(side) * 2) / 5,
            )
        }
    }
}
