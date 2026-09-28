package com.stratum.engine.worldgen

import com.stratum.core.domain.world.DungeonLayout
import com.stratum.core.domain.world.PieceSide
import com.stratum.core.domain.world.StructureAnchor
import com.stratum.core.domain.world.StructureTemplate
import com.stratum.core.domain.world.WorldMarker
import com.stratum.core.domain.world.WorldMarkerKind
import kotlin.math.abs
import kotlin.random.Random

/**
 * Plans a room-and-corridor dungeon: a stair down from the surface, rooms
 * scattered around its foot, and corridors joining them into a tree.
 *
 * Every room is checked against the height field before it is kept: one
 * whose ceiling would come within two blocks of the ground above is dropped,
 * so a dungeon under a valley never shows as a box of wall standing in it.
 * The room farthest from the stair holds the boss.
 */
internal class DungeonBuilder(
    private val services: WorldServices,
    private val template: StructureTemplate,
    private val slot: Int,
) {
    private val layout: DungeonLayout = template.dungeon!!
    private val blocks = TemplateBlocks(template)

    private class Room(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val cx get() = (x0 + x1) / 2
        val cy get() = (y0 + y1) / 2

        fun overlaps(o: Room, gap: Int) = x0 - gap <= o.x1 && x1 + gap >= o.x0 && y0 - gap <= o.y1 && y1 + gap >= o.y0
    }

    fun build(anchorX: Int, anchorY: Int, random: Random): StructureLayout? {
        val ground = services.surfaceAt(anchorX, anchorY)
        val headroom = layout.roomHeight + 1
        val deepest = ground - 2 - headroom
        val floorZ = when (template.placement.anchor) {
            StructureAnchor.SURFACE -> ground - layout.depth
            StructureAnchor.UNDERGROUND -> template.placement.minZ + random.nextInt(template.placement.maxZ - template.placement.minZ + 1)
        }.coerceAtMost(deepest)
        if (floorZ < MIN_FLOOR) return null
        val ceilingZ = floorZ + headroom

        val side = PieceSide.entries[random.nextInt(4)]
        val stairLength = if (layout.entrance) ground - floorZ else 0
        val footX = anchorX + side.dx * stairLength
        val footY = anchorY + side.dy * stairLength

        val rooms = ArrayList<Room>()
        fun fits(room: Room): Boolean {
            if (rooms.any { it.overlaps(room, ROOM_GAP) }) return false
            // Clear of the ground at its corners and middle, with rock to spare.
            val probes = listOf(room.x0 to room.y0, room.x1 to room.y0, room.x0 to room.y1, room.x1 to room.y1, room.cx to room.cy)
            return probes.all { (x, y) -> services.surfaceAt(x, y) - 2 >= ceilingZ }
        }
        // The first room opens off the stair's foot.
        val firstSize = size(random)
        val first = roomAround(footX + side.dx * (firstSize / 2 + 2), footY + side.dy * (firstSize / 2 + 2), firstSize, size(random))
        if (!fits(first)) return null
        rooms += first
        val wanted = layout.minRooms + random.nextInt(layout.maxRooms - layout.minRooms + 1)
        repeat(wanted * ATTEMPTS_PER_ROOM) {
            if (rooms.size >= wanted) return@repeat
            val w = size(random)
            val d = size(random)
            val cx = first.cx + random.nextInt(-layout.extent, layout.extent + 1)
            val cy = first.cy + random.nextInt(-layout.extent, layout.extent + 1)
            val room = roomAround(cx, cy, w, d)
            if (fits(room)) rooms += room
        }
        if (rooms.size < layout.minRooms.coerceAtMost(2)) return null

        val ops = ArrayList<StructureOp>()
        val wall = blocks.slotOf(layout.wallBlockId)
        val floor = blocks.slotOf(layout.floorBlockId)
        val ceiling = blocks.slotOf(layout.ceilingBlockId ?: layout.wallBlockId)
        val stair = blocks.slotOf(layout.stairBlockId ?: layout.floorBlockId)
        val light = blocks.slotOf(layout.lightBlockId)

        // Corridors: each room joins the nearest room planned before it, the
        // stair's foot joins the first. A tree, so everything is reachable.
        val corridors = ArrayList<IntArray>()
        corridors += corridor(footX, footY, first.cx, first.cy, random)
        for (i in 1 until rooms.size) {
            val room = rooms[i]
            val nearest = (0 until i).minBy { abs(rooms[it].cx - room.cx) + abs(rooms[it].cy - room.cy) }
            corridors += corridor(room.cx, room.cy, rooms[nearest].cx, rooms[nearest].cy, random)
        }
        val w = layout.corridorWidth
        // Shells first, then hollows, then floors: order is what makes the
        // doorways, since a corridor's hollow cuts through a room's shell.
        rooms.forEach { ops += FillOp(it.x0 - 1, it.y0 - 1, floorZ, it.x1 + 1, it.y1 + 1, ceilingZ, wall) }
        rooms.forEach { ops += FillOp(it.x0 - 1, it.y0 - 1, ceilingZ, it.x1 + 1, it.y1 + 1, ceilingZ, ceiling) }
        corridors.forEach { c -> ops += FillOp(c[0] - 1, c[1] - 1, floorZ, c[2] + w, c[3] + w, floorZ + CORRIDOR_HEIGHT + 1, wall) }
        rooms.forEach { ops += FillOp(it.x0, it.y0, floorZ + 1, it.x1, it.y1, ceilingZ - 1, TemplateBlocks.AIR) }
        corridors.forEach { c -> ops += FillOp(c[0], c[1], floorZ + 1, c[2] + w - 1, c[3] + w - 1, floorZ + CORRIDOR_HEIGHT, TemplateBlocks.AIR) }
        rooms.forEach { ops += FillOp(it.x0, it.y0, floorZ, it.x1, it.y1, floorZ, floor) }
        corridors.forEach { c -> ops += FillOp(c[0], c[1], floorZ, c[2] + w - 1, c[3] + w - 1, floorZ, floor) }
        if (light != TemplateBlocks.NONE) rooms.forEach { r ->
            listOf(r.x0 to r.y0, r.x1 to r.y0, r.x0 to r.y1, r.x1 to r.y1).forEach { (x, y) -> ops += FillOp(x, y, floorZ + 1, x, y, floorZ + 1, light) }
        }
        if (stairLength > 0) stairOps(ops, anchorX, anchorY, side, ground, stairLength, stair, wall)

        val markers = markers(rooms, floorZ, anchorX, anchorY, ground, stairLength > 0, random)
        val xs = rooms.flatMap { listOf(it.x0 - 1, it.x1 + 1) } + corridors.flatMap { listOf(it[0] - 1, it[2] + w) } + listOf(anchorX - w, footX + w)
        val ys = rooms.flatMap { listOf(it.y0 - 1, it.y1 + 1) } + corridors.flatMap { listOf(it[1] - 1, it[3] + w) } + listOf(anchorY - w, footY + w)
        return StructureLayout(template, slot, xs.min(), ys.min(), xs.max(), ys.max(), ops, markers)
    }

    /**
     * The stair: one block down per block along, treads laid on rock, two
     * blocks of air over each. From the third tread down the ground itself is
     * the roof, so the way in is a notch in the ground two blocks deep, not a
     * pit -- nothing a player walking past can fall down.
     */
    private fun stairOps(ops: MutableList<StructureOp>, x: Int, y: Int, side: PieceSide, ground: Int, length: Int, stair: Int, wall: Int) {
        val w = layout.corridorWidth
        // Across the stair, perpendicular to the way down.
        val ax = if (side.dx == 0) 1 else 0
        val ay = if (side.dy == 0) 1 else 0
        for (k in 0..length) {
            val sx = x + side.dx * k
            val sy = y + side.dy * k
            val x0 = minOf(sx, sx + ax * (w - 1))
            val y0 = minOf(sy, sy + ay * (w - 1))
            val x1 = maxOf(sx, sx + ax * (w - 1))
            val y1 = maxOf(sy, sy + ay * (w - 1))
            val z = ground - k
            ops += FillOp(x0, y0, z - 2, x1, y1, z - 1, wall, FillMode.UNDER)
            ops += FillOp(x0, y0, z, x1, y1, z, stair)
            ops += FillOp(x0, y0, z + 1, x1, y1, z + CORRIDOR_HEIGHT, TemplateBlocks.AIR)
        }
    }

    private fun markers(rooms: List<Room>, floorZ: Int, anchorX: Int, anchorY: Int, ground: Int, entrance: Boolean, random: Random): List<WorldMarker> {
        val id = template.id
        val stand = floorZ + 1
        val boss = if (layout.bossRoom && rooms.size > 1) rooms.drop(1).maxBy { abs(it.cx - rooms[0].cx) + abs(it.cy - rooms[0].cy) } else null
        val markers = ArrayList<WorldMarker>()
        if (entrance) markers += WorldMarker(WorldMarkerKind.ENTRANCE, anchorX, anchorY, ground + 1, id)
        rooms.forEach { room ->
            // Inside the lights, which stand in the corners.
            fun spot() = (room.x0 + 1 + random.nextInt((room.x1 - room.x0 - 1).coerceAtLeast(1))) to
                (room.y0 + 1 + random.nextInt((room.y1 - room.y0 - 1).coerceAtLeast(1)))
            if (room === boss) {
                markers += WorldMarker(WorldMarkerKind.BOSS, room.cx, room.cy, stand, id, layout.bossId)
                markers += WorldMarker(WorldMarkerKind.LOOT, room.x1 - 1, room.y1 - 1, stand, id, layout.lootRef)
            }
            val spawns = if (room === rooms[0]) (layout.spawnsPerRoom - 1).coerceAtLeast(0) else layout.spawnsPerRoom
            repeat(spawns) {
                val (x, y) = spot()
                val ref = layout.enemyIds.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }
                markers += WorldMarker(WorldMarkerKind.ENEMY_SPAWN, x, y, stand, id, ref)
            }
            // Rolled in every room so the dice do not depend on which one is the boss's.
            val loot = random.nextFloat() < layout.lootChance
            if (room !== boss && loot) {
                val (x, y) = spot()
                markers += WorldMarker(WorldMarkerKind.LOOT, x, y, stand, id, layout.lootRef)
            }
        }
        return markers
    }

    private fun size(random: Random) = layout.minRoomSize + random.nextInt(layout.maxRoomSize - layout.minRoomSize + 1)

    private fun roomAround(cx: Int, cy: Int, w: Int, d: Int) = Room(cx - w / 2, cy - d / 2, cx - w / 2 + w - 1, cy - d / 2 + d - 1)

    /**
     * An L-shaped corridor as two boxes, each `[x0, y0, x1, y1]` of its
     * north-west cells; the width is added when drawn.
     */
    private fun corridor(ax: Int, ay: Int, bx: Int, by: Int, random: Random): List<IntArray> =
        if (random.nextBoolean()) {
            listOf(intArrayOf(minOf(ax, bx), ay, maxOf(ax, bx), ay), intArrayOf(bx, minOf(ay, by), bx, maxOf(ay, by)))
        } else {
            listOf(intArrayOf(ax, minOf(ay, by), ax, maxOf(ay, by)), intArrayOf(minOf(ax, bx), by, maxOf(ax, bx), by))
        }

    private companion object {
        const val MIN_FLOOR = 2
        const val ROOM_GAP = 2
        const val ATTEMPTS_PER_ROOM = 8
        const val CORRIDOR_HEIGHT = 2
    }
}
