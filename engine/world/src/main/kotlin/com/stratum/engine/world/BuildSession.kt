package com.stratum.engine.world

import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.Chunk

/**
 * Drag-to-build: the tool in hand, the ghost preview, and committing it.
 *
 * Holds only the pending plan. The player is passed in and handed back, so
 * the session stays the one owner of who the player is and what they carry.
 */
internal class BuildSession(
    private val world: StreamingWorld,
    private val interaction: BlockInteractionSystem,
    private val registry: BlockRegistry,
) {
    /** Blocks a pending build would place or remove, for the ghost preview. Empty when not building. */
    var preview: List<BlockPos> = emptyList()
        private set

    var tool: BuildTool = BuildTool.SINGLE
        private set

    /** A commit's result, and the player after paying for it. */
    data class Committed(val result: BuildResult, val player: PlayerState)

    fun selectTool(tool: BuildTool) {
        this.tool = tool
        preview = emptyList()
    }

    fun cancel() {
        preview = emptyList()
    }

    /**
     * Previews what a drag from [from] to [to] would build. Nothing is placed;
     * this exists so the player sees the shape before spending the blocks.
     */
    fun plan(from: BlockPos, to: BlockPos, player: PlayerState): BuildPreview {
        if (tool.removes) return planErase(from, to, player)
        val blockId = player.selectedBlockId ?: return BuildPreview(emptyList(), 0, 0, false).also { preview = emptyList() }

        // A floor tile lies on what was picked rather than replacing it, so its
        // plan is lifted one level: a drag across grass paves the grass.
        val lift = if (shapeOf(blockId) == BlockShape.FLOOR) 1 else 0
        // Only cells that are actually free: the preview should show what will
        // happen, not what was asked for.
        preview = BuildPlanner.plan(tool, from.above(lift), to.above(lift)).filter { pos ->
            isInWorld(pos, lowest = 0) && world.blockAt(pos).isAir && pos != player.feet && pos != player.feet.above()
        }
        val held = player.countOf(blockId)
        return BuildPreview(preview, required = preview.size, held = held, affordable = held >= preview.size)
    }

    /**
     * Commits the previewed build, spending one held block per cell.
     *
     * Partial builds are allowed: running out halfway through leaves what was
     * afforded rather than refusing the whole thing, which is what a player
     * expects from a drag that was slightly too ambitious.
     */
    fun commit(player: PlayerState): Committed {
        if (tool.removes) return commitErase(player)
        val blockId = player.selectedBlockId ?: return Committed(BuildResult.NothingSelected, player)
        val planned = preview
        if (planned.isEmpty()) return Committed(BuildResult.NothingToBuild, player)
        val index = registry.indexOrNull(blockId) ?: return Committed(BuildResult.NothingSelected, player)

        var paid = player
        var placed = 0
        for (pos in planned) {
            val spent = paid.consuming(blockId) ?: break
            if (!world.setBlock(pos, index)) continue
            paid = spent
            placed++
        }
        preview = emptyList()
        val result = if (placed == 0) BuildResult.OutOfBlocks else BuildResult.Built(placed, planned.size - placed)
        return Committed(result, paid)
    }

    /**
     * What an erase drag would remove: anything breakable, nothing the player
     * is standing on, and never bedrock. Needs nothing selected and costs
     * nothing, because removing is how a player gets their blocks back.
     */
    private fun planErase(from: BlockPos, to: BlockPos, player: PlayerState): BuildPreview {
        // Anchored on the cell the drag started *above*, so dragging across the
        // top of a wall erases the wall rather than the ground it stands on.
        preview = BuildPlanner.plan(BuildTool.ERASE, from.above(), to.above()).filter { pos ->
            isInWorld(pos, lowest = 1) && world.blockAt(pos).let { !it.isAir && it.isBreakable } && pos != player.feet.below()
        }
        return BuildPreview(preview, required = 0, held = 0, affordable = true)
    }

    private fun commitErase(player: PlayerState): Committed {
        val planned = preview
        preview = emptyList()
        var refunded = player
        var removed = 0
        // Top down, so a column comes apart the way it would if dug by hand and
        // nothing is asked to settle onto a cell that is about to go.
        for (pos in planned.sortedByDescending { it.z }) {
            val block = world.blockAt(pos)
            if (block.isAir || !block.isBreakable) continue
            if (!world.setBlock(pos, BlockRegistry.AIR_INDEX)) continue
            refunded = refunded.withItem(block.drop)
            interaction.settle(pos)
            removed++
        }
        val result = if (removed == 0) BuildResult.NothingToBuild else BuildResult.Erased(removed)
        return Committed(result, refunded)
    }

    private fun isInWorld(pos: BlockPos, lowest: Int): Boolean =
        pos.z in lowest until Chunk.HEIGHT && world.isLoaded(pos.chunkPos)

    private fun shapeOf(blockId: String): BlockShape? = registry.indexOrNull(blockId)?.let { registry.typeOf(it).shape }
}
