package com.stratum.core.domain.session

import com.stratum.core.domain.strategy.FollowerOrder
import com.stratum.core.domain.strategy.Outpost
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.WorldPoint

/**
 * The player as this world holds them, beyond what a [HeroSave] carries:
 * where they stand, how hurt and hungry they are, and the blocks in their
 * pockets. Blocks are the world's currency rather than the hero's -- a
 * stack of one world's stone means nothing in a world made of another's --
 * so they stay with the world.
 */
data class WorldPlayer(
    val position: WorldPoint,
    val facing: Direction = Direction.SOUTH,
    val health: Int,
    val resource: Int,
    /** Survival needs by id, 0..100. */
    val needs: Map<String, Float> = emptyMap(),
    val inventory: Map<String, Int> = emptyMap(),
    val hotbar: List<String> = emptyList(),
    val selectedSlot: Int = 0,
    val toolTier: Int = 1,
) {
    /** [player] -- already carrying the hero -- put back where this save left them. */
    fun applyTo(player: PlayerState): PlayerState = player.copy(
        position = position,
        facing = facing,
        health = health,
        resource = resource,
        needs = needs,
        inventory = inventory,
        hotbar = hotbar,
        selectedSlot = selectedSlot.coerceIn(0, (hotbar.size - 1).coerceAtLeast(0)),
        toolTier = toolTier,
    )

    companion object {
        fun of(player: PlayerState) = WorldPlayer(
            position = player.position,
            facing = player.facing,
            health = player.health,
            resource = player.resource,
            needs = player.needs,
            inventory = player.inventory,
            hotbar = player.hotbar,
            selectedSlot = player.selectedSlot,
            toolTier = player.toolTier,
        )
    }
}

/**
 * One changed chunk's blocks, by index into the save's block ids.
 *
 * Only blocks: light is never written by anything but the generator, and
 * the generator is not asked for a saved chunk, so there is nothing to
 * keep. [blocks] is the chunk's own layout, [Chunk.VOLUME] cells.
 */
class SavedChunk(val x: Int, val y: Int, val blocks: ShortArray) {
    init {
        require(blocks.size == Chunk.VOLUME) { "A saved chunk needs ${Chunk.VOLUME} cells, got ${blocks.size}" }
    }

    val pos: ChunkPos get() = ChunkPos(x, y)

    /**
     * The chunk again, its cells turned from the save's indices into the
     * loaded registry's through [remap] (see [remapTable]). A copy: the save
     * can be written out while the world digs on.
     */
    fun toChunk(remap: IntArray): Chunk {
        val cells = ShortArray(blocks.size) { i ->
            val saved = blocks[i].toInt()
            (if (saved in remap.indices) remap[saved] else BlockRegistry.AIR_INDEX).toShort()
        }
        return Chunk.restore(pos, cells, ByteArray(Chunk.VOLUME))
    }

    override fun equals(other: Any?): Boolean =
        other is SavedChunk && x == other.x && y == other.y && blocks.contentEquals(other.blocks)

    override fun hashCode(): Int = 31 * (31 * x + y) + blocks.contentHashCode()

    override fun toString(): String = "SavedChunk($x, $y)"

    companion object {
        /** A copy of [chunk]'s cells, by the registry index they were written with. */
        fun of(chunk: Chunk) = SavedChunk(chunk.pos.x, chunk.pos.y, chunk.exportBlocks())

        /**
         * Saved index to loaded index. A block whose pack is no longer loaded
         * becomes air: a hole in a wall is a better outcome than a save that
         * will not open.
         */
        fun remapTable(blockIds: List<String>, registry: BlockRegistry): IntArray =
            IntArray(blockIds.size) { registry.indexOrNull(blockIds[it]) ?: BlockRegistry.AIR_INDEX }
    }
}

/**
 * The player's holdings in one world: its outposts, the followers in the
 * field (by unit id, since a body is remade rather than kept), their
 * orders, and the towns already freed.
 */
data class RealmSave(
    val outposts: List<Outpost> = emptyList(),
    val followerOrder: FollowerOrder = FollowerOrder.FOLLOW,
    val holdAt: WorldPoint? = null,
    val followers: List<String> = emptyList(),
    val liberatedTowns: Set<String> = emptySet(),
)
