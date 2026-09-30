package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.World

/**
 * Decides whether a space is enclosed.
 *
 * Flood fills through air from a starting point. If the fill escapes -- reaches
 * the edge of the loaded world, climbs past the roof line, or simply grows too
 * large -- the space is open. If it closes, the visited cells are a room.
 *
 * Sealing is "not air" rather than "solid", which is what lets a door count as a
 * wall while still being walked through: the block exists, so it closes the
 * room, but it is not solid, so it does not block movement. The two questions
 * have different answers and the block model already separates them.
 */
class RoomScanner(
    private val world: World,
    private val maxVolume: Int = DEFAULT_MAX_VOLUME,
) {

    fun scan(start: BlockPos): RoomScan {
        if (!world.blockAt(start).isAir) {
            return RoomScan.Open(OpenReason.NOT_A_SPACE)
        }

        val interior = LinkedHashSet<BlockPos>()
        val boundary = LinkedHashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        queue += start
        interior += start

        while (queue.isNotEmpty()) {
            if (interior.size > maxVolume) {
                return RoomScan.Open(OpenReason.TOO_LARGE)
            }
            val current = queue.removeFirst()

            for (direction in Direction.entries) {
                val next = current.offset(direction)

                if (next.z !in 0 until Chunk.HEIGHT) {
                    return RoomScan.Open(OpenReason.ESCAPED_WORLD)
                }
                // An unloaded chunk reads as air, so a fill that reaches one has
                // left the part of the world we can actually answer for.
                if (!world.isLoaded(next.chunkPos)) {
                    return RoomScan.Open(OpenReason.ESCAPED_WORLD)
                }

                if (world.blockAt(next).isAir) {
                    if (interior.add(next)) queue += next
                } else {
                    boundary += next
                }
            }
        }

        val floorCells = interior.filter { !world.blockAt(it.below()).isAir }
        val doorways = boundary.filterNot { world.blockAt(it).isSolid }

        return RoomScan.Enclosed(
            interior = interior,
            boundary = boundary,
            floorArea = floorCells.size,
            doorways = doorways.toSet(),
        )
    }

    private companion object {
        /**
         * Large enough for a generous hall, small enough that scanning an open
         * field gives up quickly rather than walking the whole loaded world.
         */
        const val DEFAULT_MAX_VOLUME = 4096
    }
}

sealed interface RoomScan {

    data class Enclosed(
        val interior: Set<BlockPos>,
        val boundary: Set<BlockPos>,
        val floorArea: Int,
        /** Boundary blocks you can walk through: doors, curtains, open frames. */
        val doorways: Set<BlockPos>,
    ) : RoomScan {
        val volume: Int get() = interior.size

        /** A room with no way in is a tomb, and probably a mistake. */
        val hasEntrance: Boolean get() = doorways.isNotEmpty()

        /** Big enough to be a room rather than a cupboard. */
        val isHabitable: Boolean get() = floorArea >= MIN_HABITABLE_FLOOR && volume >= MIN_HABITABLE_VOLUME
    }

    data class Open(val reason: OpenReason) : RoomScan

    companion object {
        const val MIN_HABITABLE_FLOOR = 4
        const val MIN_HABITABLE_VOLUME = 8
    }
}

enum class OpenReason {
    /** The starting point was inside a block. */
    NOT_A_SPACE,

    /** The fill reached the edge of the world or an unloaded chunk. */
    ESCAPED_WORLD,

    /** The space kept going, so it is the outdoors. */
    TOO_LARGE,
}
