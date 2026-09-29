package com.stratum.engine.world

import com.stratum.core.domain.content.VoxelBlueprint
import com.stratum.core.domain.micro.BlockBox
import com.stratum.core.domain.micro.MicroBrushes
import com.stratum.core.domain.micro.MicroModel
import com.stratum.core.domain.micro.MicroStamp
import com.stratum.core.domain.micro.MicroStampSurface
import com.stratum.engine.microbridge.StampLayer
import com.stratum.engine.microvoxel.MicroTerrainSource
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

    /** How tall walls, rooms, fills, pillars and round walls rise, and how deep a dig goes. */
    val buildHeight: Int

    fun setBuildHeight(blocks: Int)

    val canUndo: Boolean
    val canRedo: Boolean

    /**
     * Takes back the last build, erase, paint, sculpt or placed model, blocks
     * and all: what was placed comes back to the bag, what was cleared goes
     * back where it stood. A player who can undo builds boldly.
     */
    fun undo(): UndoResult

    fun redo(): UndoResult

    /** The eyedropper: selects the block at [target] when the player has one to place. Returns its id, or null. */
    fun pickBlock(target: BlockPos): String?

    /** Whether this world has microvoxels to sculpt and models to place. */
    val canSculpt: Boolean

    val sculptBrush: SculptBrush

    fun setSculptBrush(brush: SculptBrush)

    /**
     * Shapes the land at a quarter block with the brush: [carve] digs a
     * rounded hollow at [target] (what it breaks goes in the bag); otherwise
     * it heaps material onto the top of [target].
     */
    fun sculpt(target: BlockPos, carve: Boolean): SculptResult

    /** Sets a microvoxel model on the ground a few steps in front of the player, turned [turns] quarter turns. */
    fun placeModel(model: MicroModel, turns: Int = 0): SculptResult
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
    /** The land's microvoxels and the stamps laid over them, when it has any. */
    private val micro: MicroTerrainSource? = null,
    private val stamps: MicroStampSurface? = null,
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

    override val buildHeight: Int get() = building.height

    override fun setBuildHeight(blocks: Int) = building.setHeight(blocks)

    override fun previewBuild(from: BlockPos, to: BlockPos): BuildPreview = building.plan(from, to, player)

    override fun cancelBuild() = building.cancel()

    override fun commitBuild(): BuildResult {
        val tool = building.tool
        val committed = building.commit(player)
        player = committed.player
        if (committed.changes.isNotEmpty()) record(BlockStep(tool.label, committed.changes))
        when (val result = committed.result) {
            is BuildResult.Built -> cues.built(result.placed, player.position)
            is BuildResult.Painted -> cues.built(result.painted, player.position)
            is BuildResult.Erased -> {
                player = motion.advance(player, 0f)
                cues.cleared(result.removed, player.position)
            }
            else -> Unit
        }
        return committed.result
    }

    // ---- Undo ------------------------------------------------------------------------

    private sealed interface Step { val what: String }

    private class BlockStep(override val what: String, val changes: List<BuildSession.Change>) : Step

    private class StampStep(override val what: String, val stamp: MicroStamp, val model: MicroModel?) : Step

    private val undone = ArrayDeque<Step>()
    private val history = ArrayDeque<Step>()

    override val canUndo: Boolean get() = history.isNotEmpty()
    override val canRedo: Boolean get() = undone.isNotEmpty()

    private fun record(step: Step) {
        history.addLast(step)
        while (history.size > HISTORY) history.removeFirst()
        undone.clear()
    }

    override fun undo(): UndoResult {
        val step = history.removeLastOrNull() ?: return UndoResult.NothingToUndo
        cancelMining()
        val cells = when (step) {
            is BlockStep -> revert(step.changes)
            is StampStep -> {
                // Only the newest stamp comes off; anything stamped since was stamped on top of it.
                if (stamps?.stamps()?.lastOrNull() != step.stamp) { history.clear(); return UndoResult.NothingToUndo }
                restamp(step.stamp, step.model, carve = !step.stamp.carve) { stamps.unstamp() }?.first ?: 0
            }
        }
        undone.addLast(step)
        player = motion.advance(player, 0f)
        return UndoResult.Undone(step.what, cells)
    }

    override fun redo(): UndoResult {
        val step = undone.removeLastOrNull() ?: return UndoResult.NothingToRedo
        cancelMining()
        val cells = when (step) {
            is BlockStep -> revert(step.changes.map { BuildSession.Change(it.pos, it.after, it.before) }.asReversed())
            is StampStep -> restamp(step.stamp, step.model, carve = step.stamp.carve) { stamps?.stamp(step.stamp, step.model) }?.first ?: 0
        }
        history.addLast(step)
        player = motion.advance(player, 0f)
        return UndoResult.Redone(step.what, cells)
    }

    /**
     * Puts each cell back from `after` to `before`, newest first, settling
     * the bag as it goes: a block taken off the world comes back to it, and a
     * block put back is paid for with what it dropped -- a cell the player can
     * no longer pay for stays as it is, so undo never makes blocks from nothing.
     */
    private fun revert(changes: List<BuildSession.Change>): Int {
        var cells = 0
        for (c in changes.asReversed()) {
            if (world.blockIndexAt(c.pos) != c.after) continue
            var paid = player
            if (c.before != BlockRegistry.AIR_INDEX) {
                val back = registry.typeOf(c.before)
                paid = paid.consuming(back.id) ?: paid.consuming(back.drop) ?: continue
            }
            if (c.pos == player.feet || c.pos == player.feet.above()) if (c.before != BlockRegistry.AIR_INDEX) continue
            if (!world.setBlock(c.pos, c.before)) continue
            if (c.after != BlockRegistry.AIR_INDEX) paid = paid.withItem(registry.typeOf(c.after).drop)
            player = paid
            cells++
        }
        return cells
    }

    override fun pickBlock(target: BlockPos): String? {
        val block = world.blockAt(target)
        if (block.isAir) return null
        val id = block.id.takeIf { player.countOf(it) > 0 } ?: block.drop.takeIf { registry.contains(it) && player.countOf(it) > 0 } ?: return null
        val hotbar = if (id in player.hotbar) player.hotbar else player.hotbar + id
        player = player.copy(hotbar = hotbar).let { it.selectingSlot(it.hotbar.indexOf(id)) }
        return id
    }

    // ---- Sculpting -------------------------------------------------------------------

    override val canSculpt: Boolean get() = micro != null && stamps != null

    override var sculptBrush: SculptBrush = SculptBrush()
        private set

    override fun setSculptBrush(brush: SculptBrush) {
        sculptBrush = brush.copy(radius = brush.radius.coerceIn(1, MicroBrushes.MAX_RADIUS))
    }

    override fun sculpt(target: BlockPos, carve: Boolean): SculptResult {
        if (!canSculpt) return SculptResult.NotMicrovoxel
        val b = sculptBrush
        val r = b.radius
        val m = MICRO
        val id = MicroBrushes.id(b.shape, r, b.material)
        // Carving centres on the block touched; heaping sits on its top, sunk a little so it joins the ground.
        val cz = if (carve) target.z * m + m / 2 else (target.z + 1) * m + r / 2 - 1
        val stamp = MicroStamp(id, target.x * m + m / 2 - r, target.y * m + m / 2 - r, cz - r, carve = carve)
        return stampAndRecord(stamp, null, if (carve) "Chisel" else "Sculpt")
    }

    override fun placeModel(model: MicroModel, turns: Int): SculptResult {
        if (!canSculpt) return SculptResult.NotMicrovoxel
        cancelMining()
        val m = MICRO
        val sx = if (Math.floorMod(turns, 2) == 1) model.sizeY else model.sizeX
        val sy = if (Math.floorMod(turns, 2) == 1) model.sizeX else model.sizeY
        val reach = maxOf(sx, sy) / m / 2 + BLUEPRINT_CLEARANCE
        val x = player.feet.x + player.facing.dx * reach
        val y = player.feet.y + player.facing.dy * reach
        val ground = world.surfaceAt(x, y)
        if (ground < 0 || ground + model.sizeZ / m + 1 >= com.stratum.core.domain.world.Chunk.HEIGHT) return SculptResult.NoRoom
        val stamp = MicroStamp(model.id, x * m + m / 2 - sx / 2, y * m + m / 2 - sy / 2, (ground + 1) * m, turns = Math.floorMod(turns, 4))
        return stampAndRecord(stamp, model, model.name)
    }

    private fun stampAndRecord(stamp: MicroStamp, model: MicroModel?, what: String): SculptResult {
        val stamps = stamps ?: return SculptResult.NotMicrovoxel
        val (cells, gathered) = restamp(stamp, model, stamp.carve) { stamps.stamp(stamp, model) } ?: return SculptResult.NoRoom
        record(StampStep(what, stamp, model))
        player = motion.advance(player, 0f)
        if (cells > 0) if (stamp.carve) cues.cleared(cells, player.position) else cues.built(cells, player.position)
        return SculptResult.Shaped(cells, gathered)
    }

    /**
     * Lays or lifts a stamp, then brings the block world in line with the land
     * as now generated, cell by cell over the stamp's box: a cell the player
     * never changed takes the new generated block; a cell they built on is
     * left theirs, unless the stamp carves it away. What a carving breaks goes
     * in the bag. Chunks whose blocks all read the same are still marked, so
     * their fine detail is drawn again.
     */
    private fun restamp(stamp: MicroStamp, model: MicroModel?, carve: Boolean, apply: () -> BlockBox?): Pair<Int, Map<String, Int>>? {
        val source = micro ?: return null
        val shape = model ?: MicroBrushes.model(stamp.modelId) ?: stamps?.stampModels()?.firstOrNull { it.id == stamp.modelId } ?: return null
        val box = StampLayer.blocksOf(StampLayer.boxOf(stamp, shape), MICRO)
        val z0 = box.minZ.coerceAtLeast(1); val z1 = box.maxZ.coerceAtMost(com.stratum.core.domain.world.Chunk.HEIGHT - 1)
        val w = box.maxX - box.minX + 1; val d = box.maxY - box.minY + 1
        val before = IntArray(w * d * (z1 - z0 + 1).coerceAtLeast(0))
        var i = 0
        for (z in z0..z1) for (y in box.minY..box.maxY) for (x in box.minX..box.maxX) before[i++] = source.generatedBlock(x, y, z)
        apply() ?: return null
        val occupied = actorCells()
        val gathered = HashMap<String, Int>()
        var cells = 0
        i = 0
        for (z in z0..z1) for (y in box.minY..box.maxY) for (x in box.minX..box.maxX) {
            val was = before[i++]
            val pos = BlockPos(x, y, z)
            if (!world.isLoaded(pos.chunkPos)) continue
            val now = source.generatedBlock(x, y, z)
            if (now == was) continue
            val current = world.blockIndexAt(pos)
            val take = current == was || (carve && now == BlockRegistry.AIR_INDEX) || (!carve && current == BlockRegistry.AIR_INDEX)
            if (!take || current == now) continue
            if (now != BlockRegistry.AIR_INDEX && pos in occupied) continue
            val broken = registry.typeOf(current)
            if (!broken.isAir && !broken.isBreakable) continue
            if (!world.setBlock(pos, now)) continue
            if (!broken.isAir && now == BlockRegistry.AIR_INDEX) {
                player = player.withItem(broken.drop)
                gathered.merge(broken.drop, 1, Int::plus)
            }
            cells++
        }
        for (cy in Math.floorDiv(box.minY, 16)..Math.floorDiv(box.maxY, 16)) for (cx in Math.floorDiv(box.minX, 16)..Math.floorDiv(box.maxX, 16)) {
            world.chunkAt(com.stratum.core.domain.world.ChunkPos(cx, cy))?.touch()
        }
        return cells to gathered
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
        /** Steps of undo kept. */
        const val HISTORY = 40
        const val MICRO = 4
    }
}
