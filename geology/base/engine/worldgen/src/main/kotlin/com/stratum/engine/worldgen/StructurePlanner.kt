package com.stratum.engine.worldgen

import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.LiquidTarget
import com.stratum.core.domain.world.StructureAnchor
import com.stratum.core.domain.world.StructureTemplate
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Decides where every structure in a world stands, and plans each one.
 *
 * Each template cuts the world into cells of its own spacing, and each cell
 * holds at most one of it: whether it does, where in the cell, and every room
 * and piece are functions of the world seed, the template and the cell. A
 * structure keeps out of towns, landmark clearings, open water, and any
 * structure planned by a template listed before it -- all things this planner
 * can ask about any column without generating a chunk, so the answer never
 * depends on the order chunks are made in.
 */
class StructurePlanner(
    private val services: WorldServices,
    private val templates: List<StructureTemplate>,
) {
    private val layouts = ConcurrentHashMap<Key, Any>()

    private data class Key(val slot: Int, val cx: Int, val cy: Int)

    /** How far from its anchor any block of each template can reach. */
    private val reach = templates.map { t ->
        val dungeon = t.dungeon
        if (dungeon != null) {
            // The stair can run a world's height from the anchor, and rooms
            // spread the extent from the first room at its foot.
            Chunk.HEIGHT + dungeon.extent + 2 * dungeon.maxRoomSize + dungeon.corridorWidth + 4
        } else {
            val biggest = t.pieces.maxOf { maxOf(it.width, it.depth) }
            biggest * t.maxPieces + 1
        }
    }

    /** The top of open water, below which nothing is built on the surface. */
    private val sea = services.context.recipe.liquids.filter { it.target != LiquidTarget.CAVES }.maxOfOrNull { it.maxZ } ?: 0

    /** Every structure with any block inside the box, in a fixed order. */
    fun layoutsTouching(x0: Int, y0: Int, x1: Int, y1: Int): List<StructureLayout> = buildList {
        templates.indices.forEach { slot ->
            val spacing = templates[slot].placement.spacing
            val r = reach[slot]
            for (cy in Math.floorDiv(y0 - r, spacing)..Math.floorDiv(y1 + r, spacing)) {
                for (cx in Math.floorDiv(x0 - r, spacing)..Math.floorDiv(x1 + r, spacing)) {
                    layoutIn(slot, cx, cy)?.takeIf { it.touches(x0, y0, x1, y1) }?.let(::add)
                }
            }
        }
    }

    /** The structure of template [slot] in cell ([cx], [cy]), or null. Planned once, then remembered. */
    fun layoutIn(slot: Int, cx: Int, cy: Int): StructureLayout? {
        val key = Key(slot, cx, cy)
        layouts[key]?.let { return it as? StructureLayout }
        val planned = plan(slot, cx, cy)
        if (layouts.size > CACHE) layouts.clear()
        layouts[key] = planned ?: NONE
        return planned
    }

    private fun plan(slot: Int, cx: Int, cy: Int): StructureLayout? {
        val template = templates[slot]
        val placement = template.placement
        val random = Random(services.seed * PRIME_A + template.id.hashCode() * PRIME_B + cx * PRIME_C + cy * PRIME_D)
        if (random.nextFloat() >= placement.chance) return null
        val margin = placement.spacing / 4
        val x = cx * placement.spacing + margin + random.nextInt(placement.spacing - 2 * margin)
        val y = cy * placement.spacing + margin + random.nextInt(placement.spacing - 2 * margin)
        val biome = services.biomes.biomeAt(x, y)
        if (placement.biomeIds.isNotEmpty() && biome.id !in placement.biomeIds) return null
        if (placement.anchor == StructureAnchor.SURFACE && services.surfaceAt(x, y) < sea) return null
        if (services.composition?.siteAround(x, y) != null) return null
        val r = reach[slot]
        if (services.townSites?.settlementsNear(x, y, r)?.isNotEmpty() == true) return null
        val layout = if (template.dungeon != null) DungeonBuilder(services, template, slot).build(x, y, random)
        else JigsawBuilder(services, template, slot).build(x, y, random)
        layout ?: return null
        // Earlier templates were here first.
        val clash = (0 until slot).any { earlier ->
            layoutsNear(earlier, layout).any { it.touches(layout.minX - GAP, layout.minY - GAP, layout.maxX + GAP, layout.maxY + GAP) }
        }
        return layout.takeIf { !clash }
    }

    private fun layoutsNear(slot: Int, layout: StructureLayout): List<StructureLayout> {
        val spacing = templates[slot].placement.spacing
        val r = reach[slot]
        return (Math.floorDiv(layout.minY - r, spacing)..Math.floorDiv(layout.maxY + r, spacing)).flatMap { cy ->
            (Math.floorDiv(layout.minX - r, spacing)..Math.floorDiv(layout.maxX + r, spacing)).mapNotNull { cx -> layoutIn(slot, cx, cy) }
        }
    }

    private companion object {
        const val CACHE = 2_048
        const val GAP = 4
        const val PRIME_A = 6364136223846793005L
        const val PRIME_B = 1442695040888963407L
        const val PRIME_C = 2862933555777941757L
        const val PRIME_D = 3202034522624059733L
        val NONE = Any()
    }
}
