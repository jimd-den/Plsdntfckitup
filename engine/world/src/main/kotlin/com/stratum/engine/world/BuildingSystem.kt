package com.stratum.engine.world

import com.stratum.core.domain.content.VoxelBlueprint
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import kotlin.random.Random

/** The sandbox verbs a session offers; see [BuildingSystem]. */
interface SessionBuilding {
    /** How far the dig under way has got, 0..1. */
    val miningFraction: Float

    /** Blocks a pending build would place, for the ghost preview. Empty when not building. */
    val buildPreview: List<BlockPos>

    val buildTool: BuildTool

    /** Applies mining effort to a block, continuing a dig already under way on it. */
    fun mine(target: BlockPos, deltaSeconds: Float): MineResult

    fun cancelMining()

    /**
     * Places the selected hotbar block against the block the player touched,
     * spending one. [picked] is a solid block -- the only thing a tap can
     * resolve to -- so the cell to fill is the face next to it.
     */
    fun place(picked: BlockPos): PlaceResult

    /** Where a tap on [picked] would actually put a block, for the ghost preview. */
    fun placementPreviewFor(picked: BlockPos): BlockPos?

    /**
     * Raises [blueprint] on the ground a few steps in front of the player and
     * returns how many blocks went in. Clear of the player, standing on the
     * surface there, and only into air, so it can neither bury the hero nor
     * carve away what is already built.
     */
    fun raise(blueprint: VoxelBlueprint): Int

    fun selectBuildTool(tool: BuildTool)

    /** Previews what a drag from [from] to [to] would build. Nothing is placed. */
    fun previewBuild(from: BlockPos, to: BlockPos): BuildPreview

    fun cancelBuild()

    fun commitBuild(): BuildResult

    /** The room the player is standing in, if any. Recomputed on demand, because walls change while building. */
    fun shelter(): RoomScan
}

/**
 * The sandbox half of the game: digging, placing one block, the drag-to-build
 * tools, raising a blueprint, and asking whether the player stands in a room.
 *
 * Digging and building share a surface, so they share this part: placing
 * without stopping a dig would keep breaking the block being mined while the
 * player builds beside it.
 */
internal class BuildingSystem(
    private val state: SessionState,
    private val world: StreamingWorld,
    private val registry: BlockRegistry,
    private val motion: PlayerMotion,
    private val survival: SurvivalSystem,
    private val roomScanner: RoomScanner,
    private val cues: SessionCues,
    private val random: Random,
    private val insertOf: (String) -> InsertDefinition?,
) : SessionBuilding {
    private val interaction = BlockInteractionSystem(world)
    private val mining = MiningProgress()
    private val building = BuildSession(world, interaction, registry)
    private var player: PlayerState
        get() = state.player
        set(value) {
            state.player = value
        }

    val miningTarget: BlockPos? get() = mining.target

    override val miningFraction: Float get() = mining.fraction(world)

    override val buildPreview: List<BlockPos> get() = building.preview

    override val buildTool: BuildTool get() = building.tool

    override fun mine(target: BlockPos, deltaSeconds: Float): MineResult {
        val speed = player.sheet(insertOf).multiplier(Stat.MINING_SPEED)
        val result = interaction.mine(MineRequest(player.blockPos, target, player.toolTier, deltaSeconds * speed, mining.effortOn(target)))
        when (result) {
            is MineResult.InProgress -> mining.record(result.progress)
            is MineResult.Broken -> {
                mining.reset()
                player = motion.advance(pocketed(player, result.drop), 0f)
                survival.forage(result.block.id, result.block.material, random).forEach { player = player.withItem(it) }
            }
            is MineResult.Rejected -> mining.reset()
        }
        return result
    }

    override fun cancelMining() = mining.reset()

    override fun place(picked: BlockPos): PlaceResult {
        val blockId = player.selectedBlockId ?: return PlaceResult.Rejected(PlaceRejection.UNKNOWN_BLOCK)
        cancelMining()
        val target = interaction.placementCellFor(picked, player.blockPos, actorCells()) ?: return PlaceResult.Rejected(PlaceRejection.OCCUPIED)
        val spent = player.consuming(blockId) ?: return PlaceResult.Rejected(PlaceRejection.UNKNOWN_BLOCK)
        val result = interaction.place(PlaceRequest(player.blockPos, target, blockId, occupiedByActors = setOf(player.feet, player.feet.above())))
        if (result is PlaceResult.Placed) player = spent
        return result
    }

    override fun placementPreviewFor(picked: BlockPos): BlockPos? = interaction.placementCellFor(picked, player.blockPos, actorCells())

    override fun raise(blueprint: VoxelBlueprint): Int {
        cancelMining()
        val reach = maxOf(blueprint.sizeX, blueprint.sizeY) / 2 + BLUEPRINT_CLEARANCE
        val x = player.feet.x + player.facing.dx * reach
        val y = player.feet.y + player.facing.dy * reach
        val ground = world.surfaceAt(x, y)
        if (ground < 0) return 0
        val occupied = actorCells()
        return blueprint.raise(world, BlockPos(x, y, ground + 1)) { pos -> pos !in occupied && world.blockAt(pos).isAir }
    }

    override fun selectBuildTool(tool: BuildTool) = building.selectTool(tool)

    override fun previewBuild(from: BlockPos, to: BlockPos): BuildPreview = building.plan(from, to, player)

    override fun cancelBuild() = building.cancel()

    override fun commitBuild(): BuildResult {
        val committed = building.commit(player)
        player = committed.player
        when (val result = committed.result) {
            is BuildResult.Built -> cues.built(result.placed, player.position)
            is BuildResult.Erased -> {
                player = motion.advance(player, 0f)
                cues.cleared(result.removed, player.position)
            }
            else -> Unit
        }
        return committed.result
    }

    override fun shelter(): RoomScan = roomScanner.scan(player.blockPos)

    fun reset() = mining.reset()

    /** A mined block goes in the bag, and onto the hotbar if it is something that can be placed. */
    private fun pocketed(player: PlayerState, drop: String): PlayerState {
        val holding = player.withItem(drop)
        val placeable = drop !in holding.hotbar && registry.contains(drop)
        return if (placeable) holding.copy(hotbar = holding.hotbar + drop) else holding
    }

    /**
     * Cells a body is standing in. Tapping the ground at your feet should build
     * beside you rather than refuse, so these are skipped while resolving the
     * cell rather than rejected after one has been chosen.
     */
    private fun actorCells(): Set<BlockPos> = buildSet {
        add(player.feet)
        add(player.feet.above())
        state.enemies.forEach {
            add(it.blockPos)
            add(it.blockPos.above())
        }
    }

    private companion object {
        /** Blocks of open ground left between the player and a raised blueprint. */
        const val BLUEPRINT_CLEARANCE = 2
    }
}
