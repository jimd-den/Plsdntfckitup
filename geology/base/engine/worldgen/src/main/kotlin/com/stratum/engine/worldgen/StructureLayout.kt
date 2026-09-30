package com.stratum.engine.worldgen

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.StructurePiece
import com.stratum.core.domain.world.StructureTemplate
import com.stratum.core.domain.world.WorldMarker

/**
 * A structure planned in full: what to write, where, and what it marks.
 *
 * Planned once from the seed and its cell, then drawn by every chunk it
 * touches, each clipping the same operations to itself. The operations run in
 * order, so a dungeon's walls go down before its rooms are hollowed and its
 * floors laid, and every chunk agrees about the result at every cell.
 */
class StructureLayout internal constructor(
    val template: StructureTemplate,
    /** Index of the template in the world's list; orders overlapping work the same everywhere. */
    val slot: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    internal val ops: List<StructureOp>,
    val markers: List<WorldMarker>,
) {
    fun touches(x0: Int, y0: Int, x1: Int, y1: Int): Boolean = maxX >= x0 && minX <= x1 && maxY >= y0 && minY <= y1

    /** Draws the part of this structure inside [chunk]. [palette] maps the template's blocks to registry indices. */
    fun draw(chunk: ChunkContext, palette: IntArray) {
        ops.forEach { it.apply(chunk, palette) }
    }
}

/**
 * The blocks a template uses, in a fixed order, so operations can name a block
 * by a small number and each world resolve the numbers once.
 */
internal class TemplateBlocks(template: StructureTemplate) {
    val ids: List<String> = template.referencedBlockIds().toList()
    private val slots = ids.withIndex().associate { (i, id) -> id to i }

    fun slotOf(id: String?): Int = id?.let { slots.getValue(it) } ?: NONE

    fun resolve(blocks: BlockIndex): IntArray = IntArray(ids.size) { blocks.of(ids[it]) }

    companion object {
        const val NONE = -1
        const val AIR = -2
    }
}

/** How a fill treats what is already there. */
internal enum class FillMode {
    /** Writes every cell. */
    SET,

    /** Writes only where there is nothing to stand on: foundations under a structure on a slope. */
    UNDER,
}

internal sealed interface StructureOp {
    fun apply(chunk: ChunkContext, palette: IntArray)
}

/** A box of one block, clipped to the chunk. */
internal class FillOp(
    val x0: Int, val y0: Int, val z0: Int,
    val x1: Int, val y1: Int, val z1: Int,
    /** A template block slot, or [TemplateBlocks.AIR]. */
    val block: Int,
    val mode: FillMode = FillMode.SET,
) : StructureOp {
    override fun apply(chunk: ChunkContext, palette: IntArray) {
        if (block == TemplateBlocks.NONE) return
        val index = if (block == TemplateBlocks.AIR) BlockRegistry.AIR_INDEX else palette[block]
        val fromX = maxOf(x0, chunk.originX)
        val toX = minOf(x1, chunk.originX + Chunk.SIZE - 1)
        val fromY = maxOf(y0, chunk.originY)
        val toY = minOf(y1, chunk.originY + Chunk.SIZE - 1)
        if (fromX > toX || fromY > toY) return
        val fromZ = z0.coerceAtLeast(1)
        val toZ = z1.coerceAtMost(Chunk.HEIGHT - 1)
        for (y in fromY..toY) for (x in fromX..toX) for (z in fromZ..toZ) {
            val lx = x - chunk.originX
            val ly = y - chunk.originY
            val existing = chunk.chunk.blockAt(lx, ly, z)
            if (existing == chunk.blocks.bedrock) continue
            if (mode == FillMode.UNDER && chunk.blocks.isSolid(existing)) continue
            chunk.chunk.setBlock(lx, ly, z, index)
        }
    }
}

/** A jigsaw piece stamped with its north-west bottom corner at ([x], [y], [z]). */
internal class PieceOp(
    val piece: StructurePiece,
    val x: Int,
    val y: Int,
    val z: Int,
    /** Palette character to template block slot, or [TemplateBlocks.AIR] for air and markers. */
    val slots: Map<Char, Int>,
) : StructureOp {
    override fun apply(chunk: ChunkContext, palette: IntArray) {
        for (layer in 0 until piece.height) {
            val wz = z + layer
            if (wz < 1 || wz >= Chunk.HEIGHT) continue
            val rows = piece.layers[layer]
            for (row in rows.indices) {
                val wy = y + row
                val line = rows[row]
                for (col in line.indices) {
                    val wx = x + col
                    if (!chunk.containsWorld(wx, wy)) continue
                    val c = line[col]
                    val slot = when (c) {
                        ' ' -> continue
                        '.' -> TemplateBlocks.AIR
                        else -> slots[c] ?: continue
                    }
                    val index = if (slot == TemplateBlocks.AIR) BlockRegistry.AIR_INDEX else palette[slot]
                    if (chunk.get(wx, wy, wz) != chunk.blocks.bedrock) chunk.set(wx, wy, wz, index)
                }
            }
        }
    }
}
