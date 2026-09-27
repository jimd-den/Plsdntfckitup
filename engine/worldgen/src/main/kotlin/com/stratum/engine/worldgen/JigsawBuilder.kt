package com.stratum.engine.worldgen

import com.stratum.core.domain.world.PieceConnector
import com.stratum.core.domain.world.PieceSide
import com.stratum.core.domain.world.StructureAnchor
import com.stratum.core.domain.world.StructurePiece
import com.stratum.core.domain.world.StructureTemplate
import com.stratum.core.domain.world.WorldMarker
import com.stratum.core.domain.world.WorldMarkerKind
import kotlin.random.Random

/**
 * Plans a structure from pieces, jigsaw-fashion but kept small: a start
 * piece, then pieces joined breadth-first at open connectors, each chosen by
 * weight from those with a connector facing back, until the structure has
 * [StructureTemplate.maxPieces] or runs out of doorways. A piece that would
 * overlap one already placed is not placed. Pieces are never rotated -- the
 * author draws each facing the way it is used, which keeps the palette
 * readable and the art the right way round under an isometric camera.
 *
 * A surface structure stands on ground levelled to its start: the footprint
 * is cleared above that level and given a foundation below it.
 */
internal class JigsawBuilder(
    private val services: WorldServices,
    private val template: StructureTemplate,
    private val slot: Int,
) {
    private val blocks = TemplateBlocks(template)
    private val slotsByPiece = template.pieces.associateWith { piece ->
        piece.palette.mapValues { (_, entry) -> if (StructurePiece.isMarker(entry)) TemplateBlocks.AIR else blocks.slotOf(entry) }
    }

    private class Placed(val piece: StructurePiece, val x: Int, val y: Int) {
        val x1 get() = x + piece.width - 1
        val y1 get() = y + piece.depth - 1

        fun overlaps(o: Placed) = x <= o.x1 && x1 >= o.x && y <= o.y1 && y1 >= o.y
    }

    fun build(anchorX: Int, anchorY: Int, random: Random): StructureLayout? {
        val start = template.pieces.firstOrNull { it.start } ?: template.pieces.first()
        val placed = arrayListOf(Placed(start, anchorX - start.width / 2, anchorY - start.depth / 2))
        val open = ArrayDeque<Pair<Placed, PieceConnector>>()
        start.connectors.forEach { open += placed[0] to it }
        while (open.isNotEmpty() && placed.size < template.maxPieces) {
            val (from, connector) = open.removeFirst()
            val next = join(from, connector, placed, random) ?: continue
            placed += next.first
            next.first.piece.connectors.filter { it != next.second }.forEach { open += next.first to it }
        }

        val base = when (template.placement.anchor) {
            StructureAnchor.SURFACE -> services.surfaceAt(anchorX, anchorY)
            StructureAnchor.UNDERGROUND -> template.placement.minZ + random.nextInt(template.placement.maxZ - template.placement.minZ + 1)
        }
        val ops = ArrayList<StructureOp>()
        if (template.placement.anchor == StructureAnchor.SURFACE) {
            val foundation = blocks.slotOf(template.foundationBlockId)
            placed.forEach { p ->
                ops += FillOp(p.x, p.y, base + 1, p.x1, p.y1, base + p.piece.height + CLEARANCE, TemplateBlocks.AIR)
                if (foundation != TemplateBlocks.NONE) ops += FillOp(p.x, p.y, base - FOUNDATION_DEPTH, p.x1, p.y1, base, foundation, FillMode.UNDER)
            }
        }
        placed.forEach { p -> ops += PieceOp(p.piece, p.x, p.y, base, slotsByPiece.getValue(p.piece)) }
        return StructureLayout(
            template, slot,
            placed.minOf { it.x }, placed.minOf { it.y }, placed.maxOf { it.x1 }, placed.maxOf { it.y1 },
            ops, placed.flatMap { markers(it, base) },
        )
    }

    /** A piece joined at [connector] of [from], and the connector it joined by; null when none fits. */
    private fun join(from: Placed, connector: PieceConnector, placed: List<Placed>, random: Random): Pair<Placed, PieceConnector>? {
        val (doorX, doorY) = cell(from, connector)
        val candidates = template.pieces.flatMap { piece ->
            piece.connectors.filter { it.side == connector.side.opposite }.map { piece to it }
        }.filter { it.first.weight > 0 }
        if (candidates.isEmpty()) return null
        repeat(TRIES) {
            val total = candidates.sumOf { it.first.weight }
            var roll = random.nextInt(total)
            val (piece, back) = candidates.first { roll -= it.first.weight; roll < 0 }
            // The joining piece's doorway sits on the cell just past ours.
            val (offX, offY) = cell(Placed(piece, 0, 0), back)
            val at = Placed(piece, doorX + connector.side.dx - offX, doorY + connector.side.dy - offY)
            if (placed.none { it.overlaps(at) }) return at to back
        }
        return null
    }

    /** The world cell a connector sits on. */
    private fun cell(p: Placed, c: PieceConnector): Pair<Int, Int> = when (c.side) {
        PieceSide.NORTH -> p.x + c.offset to p.y
        PieceSide.SOUTH -> p.x + c.offset to p.y1
        PieceSide.WEST -> p.x to p.y + c.offset
        PieceSide.EAST -> p.x1 to p.y + c.offset
    }

    private fun markers(p: Placed, base: Int): List<WorldMarker> = buildList {
        for (z in 0 until p.piece.height) for (row in 0 until p.piece.depth) for (col in 0 until p.piece.width) {
            val entry = p.piece.entryAt(col, row, z) ?: continue
            if (!StructurePiece.isMarker(entry) || entry == StructurePiece.AIR) continue
            val kind = when (entry.substringBefore(':')) {
                "@enemy" -> WorldMarkerKind.ENEMY_SPAWN
                "@boss" -> WorldMarkerKind.BOSS
                "@loot" -> WorldMarkerKind.LOOT
                "@entrance" -> WorldMarkerKind.ENTRANCE
                else -> WorldMarkerKind.POINT_OF_INTEREST
            }
            val ref = entry.substringAfter(':', "").takeIf(String::isNotEmpty)
            add(WorldMarker(kind, p.x + col, p.y + row, base + z, template.id, ref))
        }
    }

    private companion object {
        const val TRIES = 4
        const val CLEARANCE = 2
        const val FOUNDATION_DEPTH = 4
    }
}
