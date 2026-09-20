package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.MutableWorld

/**
 * Mining and building, including the consequences: gravity settling, supported
 * blocks falling off walls, and the cascade both can set off.
 *
 * The system is pure and synchronous. It takes a world and a request and returns
 * what happened, which makes every rule here testable without a frame loop.
 */
class BlockInteractionSystem(
    private val world: MutableWorld,
    private val reach: Int = DEFAULT_REACH,
) {

    /**
     * Applies mining progress. Blocks do not vanish on contact: [deltaSeconds] of
     * effort accumulates until it clears the block's hardness, which is what makes
     * a pickaxe tier meaningful.
     */
    fun mine(request: MineRequest): MineResult {
        val target = world.blockAt(request.target)
        if (target.isAir) return MineResult.Rejected(MineRejection.NOTHING_THERE)
        if (!target.isBreakable) return MineResult.Rejected(MineRejection.UNBREAKABLE)
        if (request.toolTier < target.requiredTier) {
            return MineResult.Rejected(MineRejection.TOOL_TOO_WEAK)
        }
        if (request.origin.horizontalDistanceTo(request.target) > reach) {
            return MineResult.Rejected(MineRejection.OUT_OF_REACH)
        }

        val speed = toolSpeed(request.toolTier, target)
        val progress = request.progressSoFar + request.deltaSeconds * speed
        if (progress < target.hardness) {
            return MineResult.InProgress(progress, target.hardness)
        }

        world.setBlock(request.target, BlockRegistry.AIR_INDEX)
        val cascade = settle(request.target)
        return MineResult.Broken(
            block = target,
            drop = target.drop,
            cascade = cascade,
        )
    }

    /**
     * A tier above the requirement digs faster; matching the requirement exactly
     * is deliberately slow so upgrading a tool is felt rather than just unlocked.
     */
    private fun toolSpeed(toolTier: Int, target: BlockType): Float {
        val advantage = (toolTier - target.requiredTier).coerceAtLeast(0)
        return BASE_MINE_SPEED * (1f + advantage * TIER_SPEED_BONUS)
    }

    /**
     * The air cell a tap on [picked] should fill.
     *
     * A tap always lands on a *solid* block — that is what the renderer drew and
     * the only thing the picker can find. You never place into a block, you
     * place against the face you touched, so the cell has to be resolved before
     * a placement is even attempted. Without this every placement is rejected as
     * occupied, which reads as "building does nothing" while a mining loop
     * started by the same gesture quietly eats the world.
     *
     * Order: the top face first, because in this projection it is most of what
     * is visible; then the horizontal neighbour nearest [from], so building
     * towards yourself works; then the top of that neighbour, which is what
     * makes tapping the ground you are standing on raise a step beside you
     * instead of failing; then underneath.
     */
    fun placementCellFor(
        picked: BlockPos,
        from: BlockPos,
        /** Cells something is standing in. Skipped rather than offered and refused. */
        blocked: Set<BlockPos> = emptySet(),
    ): BlockPos? {
        val sides = Direction.entries
            .map { picked.offset(it) }
            .filter { it.z == picked.z }
            .sortedBy { it.horizontalDistanceTo(from) }

        val candidates = listOf(picked.above()) +
            sides +
            sides.map { it.above() } +
            listOf(picked.below())
        return candidates.firstOrNull { candidate ->
            candidate.z in 0 until Chunk.HEIGHT &&
                candidate !in blocked &&
                world.isLoaded(candidate.chunkPos) &&
                world.blockAt(candidate).isAir
        }
    }

    fun place(request: PlaceRequest): PlaceResult {
        val index = world.registry.indexOrNull(request.blockId)
            ?: return PlaceResult.Rejected(PlaceRejection.UNKNOWN_BLOCK)
        val type = world.registry.typeOf(index)

        if (request.target.z !in 0 until Chunk.HEIGHT) {
            return PlaceResult.Rejected(PlaceRejection.OUT_OF_BOUNDS)
        }
        if (!world.isLoaded(request.target.chunkPos)) {
            return PlaceResult.Rejected(PlaceRejection.OUT_OF_BOUNDS)
        }
        if (!world.blockAt(request.target).isAir) {
            return PlaceResult.Rejected(PlaceRejection.OCCUPIED)
        }
        if (request.origin.horizontalDistanceTo(request.target) > reach) {
            return PlaceResult.Rejected(PlaceRejection.OUT_OF_REACH)
        }
        if (request.target in request.occupiedByActors) {
            return PlaceResult.Rejected(PlaceRejection.ACTOR_IN_THE_WAY)
        }
        // Floating blocks are the fastest way to make a voxel world look broken,
        // so a placement has to touch something solid.
        if (!hasAnchor(request.target)) {
            return PlaceResult.Rejected(PlaceRejection.NOTHING_TO_ATTACH_TO)
        }

        world.setBlock(request.target, index)
        val cascade = settle(request.target)
        return PlaceResult.Placed(type, cascade)
    }

    private fun hasAnchor(pos: BlockPos): Boolean =
        Direction.entries.any { world.blockAt(pos.offset(it)).isSolid }

    /**
     * Runs gravity and support rules outward from a change until the world is
     * stable again.
     *
     * Bounded by [MAX_CASCADE] so a pathological column cannot stall a frame; the
     * remainder settles on the next interaction rather than never.
     */
    fun settle(origin: BlockPos): List<BlockChange> {
        val changes = mutableListOf<BlockChange>()
        val pending = ArrayDeque<BlockPos>()
        Direction.entries.forEach { pending += origin.offset(it) }
        pending += origin

        var steps = 0
        while (pending.isNotEmpty() && steps < MAX_CASCADE) {
            steps++
            val pos = pending.removeFirst()
            if (pos.z !in 0 until Chunk.HEIGHT) continue
            val type = world.blockAt(pos)
            if (type.isAir) continue

            if (type.hasGravity && canFall(pos)) {
                val landing = fallTarget(pos)
                world.setBlock(pos, BlockRegistry.AIR_INDEX)
                world.setBlock(landing, world.registry.indexOf(type.id))
                changes += BlockChange(pos, type, landing, BlockChangeKind.FELL)
                pending += pos.above()
                Direction.HORIZONTAL.forEach { pending += landing.offset(it) }
                continue
            }

            if (type.needsSupport && !isSupported(pos)) {
                world.setBlock(pos, BlockRegistry.AIR_INDEX)
                changes += BlockChange(pos, type, null, BlockChangeKind.LOST_SUPPORT)
                Direction.entries.forEach { pending += pos.offset(it) }
            }
        }
        return changes
    }

    private fun canFall(pos: BlockPos): Boolean =
        pos.z > 0 && !world.blockAt(pos.below()).isSolid

    private fun fallTarget(pos: BlockPos): BlockPos {
        var landing = pos
        while (landing.z > 0 && !world.blockAt(landing.below()).isSolid) {
            landing = landing.below()
        }
        return landing
    }

    /** A supported block needs one solid neighbour that is not itself hanging. */
    private fun isSupported(pos: BlockPos): Boolean =
        Direction.entries
            .map(pos::offset)
            .any { neighbour ->
                val type = world.blockAt(neighbour)
                type.isSolid && !type.needsSupport
            }

    companion object {
        const val DEFAULT_REACH = 4
        const val BASE_MINE_SPEED = 1f
        const val TIER_SPEED_BONUS = 0.75f
        const val MAX_CASCADE = 512
    }
}

data class MineRequest(
    val origin: BlockPos,
    val target: BlockPos,
    val toolTier: Int = 0,
    val deltaSeconds: Float = 0f,
    /** Effort already spent on this block, carried between frames by the caller. */
    val progressSoFar: Float = 0f,
)

sealed interface MineResult {
    data class InProgress(val progress: Float, val required: Float) : MineResult {
        val fraction: Float get() = if (required <= 0f) 1f else (progress / required).coerceIn(0f, 1f)
    }

    data class Broken(
        val block: BlockType,
        val drop: String,
        val cascade: List<BlockChange>,
    ) : MineResult

    data class Rejected(val reason: MineRejection) : MineResult
}

enum class MineRejection { NOTHING_THERE, UNBREAKABLE, TOOL_TOO_WEAK, OUT_OF_REACH }

data class PlaceRequest(
    val origin: BlockPos,
    val target: BlockPos,
    val blockId: String,
    val occupiedByActors: Set<BlockPos> = emptySet(),
)

sealed interface PlaceResult {
    data class Placed(val block: BlockType, val cascade: List<BlockChange>) : PlaceResult

    data class Rejected(val reason: PlaceRejection) : PlaceResult
}

enum class PlaceRejection {
    UNKNOWN_BLOCK,
    OCCUPIED,
    OUT_OF_REACH,
    OUT_OF_BOUNDS,
    NOTHING_TO_ATTACH_TO,
    ACTOR_IN_THE_WAY,
}

/** One consequence of an edit. `to` is null when the block was destroyed outright. */
data class BlockChange(
    val from: BlockPos,
    val block: BlockType,
    val to: BlockPos?,
    val kind: BlockChangeKind,
)

enum class BlockChangeKind { FELL, LOST_SUPPORT }
