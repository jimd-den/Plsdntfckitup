package com.stratum.engine.microbridge

import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockType
import com.stratum.engine.microvoxel.MaterialPalette

/**
 * Every pack block as a microvoxel material of its own, named `block:<id>`.
 *
 * What lets the microvoxel world build *with* a pack rather than beside it:
 * a town's walls are made of the pack's wall block, a region's soil of its
 * soil block, and when the world is read back into blocks those voxels become
 * exactly those blocks again -- not the nearest colour, the block itself.
 * Registered once, up front, so the palette never changes under a renderer.
 */
class BlockMaterials(val palette: MaterialPalette, blocks: List<BlockType>) {

    private val byBlock = HashMap<String, Short>()
    private val byMaterial = HashMap<Short, BlockType>()

    init {
        for (b in blocks) {
            if (b.id == BlockType.AIR_ID) continue
            // Opaque as voxels even when the block is not opaque as a cube: a thin mud wall or a
            // paving slab is see-through at block scale (it does not fill its cell) but its
            // microvoxels are solid mud and stone. Only liquids are see-through at any scale.
            val id = palette.register(
                PREFIX + b.id,
                (b.topColor and 0xFFFFFF).toInt(),
                opaque = b.material != com.stratum.core.domain.world.BlockMaterial.LIQUID,
                solid = b.isSolid,
                emission = b.lightEmission / 10f,
                jitter = 0.07f,
            )
            byBlock[b.id] = id
            byMaterial[id] = b
        }
    }

    /** The material for a pack block, or null when no pack defines it. */
    operator fun get(blockId: String?): Short? = blockId?.let(byBlock::get)

    /** The pack block a material stands for, when it is one of these. */
    fun blockOf(material: Short): BlockType? = byMaterial[material]

    /**
     * How much of a block's cell its material must fill for the cell to be
     * that block: a solid cube needs the usual 3/8; a thin floor (a rug, a
     * boardwalk) one layer; a prop (a brazier) half, so it is never lost.
     */
    fun thresholdOf(material: Short, cell: Int): Int {
        val b = byMaterial[material] ?: return Int.MAX_VALUE
        return when {
            b.glyph != null -> cell / 2
            b.shape == BlockShape.FLOOR -> cell / 4
            else -> (cell * MicrovoxelTerrainGenerator.SOLID_SHARE).toInt()
        }
    }

    companion object {
        const val PREFIX = "block:"
    }
}
