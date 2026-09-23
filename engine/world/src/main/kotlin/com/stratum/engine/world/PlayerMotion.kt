package com.stratum.engine.world

import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockShapes
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * How the player moves: stick intent, the dodge roll, collision and gravity.
 *
 * Split out of the session because it is the one part of a frame that is purely
 * a function of input and geometry — no combat, no loot, no content packs — and
 * because a movement bug is felt long before it is understood. Having it alone
 * means it can be tested against a world built by hand.
 *
 * Holds its own timers and takes the player state in and out, so the session
 * stays the only thing that owns [PlayerState].
 */
class PlayerMotion(private val world: World) {

    /**
     * The direction the player is being pushed. Length 0..1, so a
     * half-deflected stick walks at half speed.
     *
     * Held as intent rather than applied immediately: movement is integrated on
     * the simulation's clock so speed is blocks per second and does not depend
     * on how often the UI happens to call in.
     */
    var input: WorldPoint = WorldPoint.ZERO
        private set

    private var rollRemaining: Float = 0f
    private var rollDirection: WorldPoint = WorldPoint.ZERO
    private var invulnerableFor: Float = 0f
    private var rollCooldown: Float = 0f

    /** How long the player has been pushing against a ledge they could climb. */
    private var climbHeld: Float = 0f

    val isRolling: Boolean get() = rollRemaining > 0f

    val isInvulnerable: Boolean get() = invulnerableFor > 0f

    val rollCooldownFraction: Float get() = (rollCooldown / ROLL_COOLDOWN).coerceIn(0f, 1f)

    /**
     * Records where the player wants to go and returns the way they should now
     * face, or null when the stick is centred and facing should not change.
     */
    fun aim(dx: Float, dy: Float): Direction? {
        val length = sqrt(dx * dx + dy * dy)
        input = if (length <= INPUT_DEADZONE) {
            WorldPoint.ZERO
        } else {
            // Clamp to the unit circle so a diagonal is not faster than a
            // cardinal, which is the classic bug with square joystick input.
            val scale = if (length > 1f) 1f / length else 1f
            WorldPoint(dx * scale, dy * scale, 0f)
        }
        return if (input == WorldPoint.ZERO) null else facingFor(input.x, input.y)
    }

    /**
     * Starts a dodge roll: a burst of speed with a window of invulnerability.
     *
     * Rolls in the facing direction when the stick is neutral, so a dodge is
     * always available rather than requiring the player to be already moving.
     */
    fun dodge(facing: Direction, isAlive: Boolean): DodgeResult {
        // Most specific reason first: a roll always sets the cooldown, so
        // checking cooldown first would report every mid-roll press as
        // "on cooldown" and hide what is actually happening.
        if (!isAlive) return DodgeResult.Rejected
        if (isRolling) return DodgeResult.AlreadyRolling
        if (rollCooldown > 0f) return DodgeResult.OnCooldown

        rollDirection = if (input == WorldPoint.ZERO) {
            WorldPoint(facing.dx.toFloat(), facing.dy.toFloat(), 0f)
        } else {
            input
        }
        rollRemaining = ROLL_DURATION
        invulnerableFor = ROLL_INVULNERABILITY
        rollCooldown = ROLL_COOLDOWN
        return DodgeResult.Rolling
    }

    /**
     * Integrates one frame and returns where the player ends up.
     *
     * Axes resolve separately so walking into a wall at an angle slides along
     * it instead of stopping dead. Sticking on geometry is the most felt
     * movement bug in an isometric game, because the player cannot see the wall
     * they are caught on.
     */
    fun advance(player: PlayerState, deltaSeconds: Float): PlayerState {
        rollCooldown = (rollCooldown - deltaSeconds).coerceAtLeast(0f)
        invulnerableFor = (invulnerableFor - deltaSeconds).coerceAtLeast(0f)

        val velocity: WorldPoint
        if (rollRemaining > 0f) {
            rollRemaining = (rollRemaining - deltaSeconds).coerceAtLeast(0f)
            velocity = WorldPoint(rollDirection.x * ROLL_SPEED, rollDirection.y * ROLL_SPEED, 0f)
        } else if (input != WorldPoint.ZERO) {
            velocity = WorldPoint(input.x * WALK_SPEED, input.y * WALK_SPEED, 0f)
        } else {
            return settled(player)
        }

        val start = player.position
        var position = start
        position = tryAxis(position, velocity.x * deltaSeconds, 0f) ?: position
        position = tryAxis(position, 0f, velocity.y * deltaSeconds) ?: position

        // Nothing moved: is it a ledge the player could scramble up? Holding
        // into one for a moment climbs it. Without this, a terrace taller than
        // a step was a one-way drop and a dug pit was a grave: the only fix
        // was a new game.
        if (position == start && rollRemaining <= 0f) {
            val climbX = tryAxis(start, velocity.x * deltaSeconds, 0f, CLIMB_UP)
            val climbY = tryAxis(start, 0f, velocity.y * deltaSeconds, CLIMB_UP)
            val climb = climbX ?: climbY
            if (climb != null) {
                climbHeld += deltaSeconds
                if (climbHeld >= CLIMB_DELAY) {
                    climbHeld = 0f
                    return settled(player.copy(position = climb))
                }
            } else {
                climbHeld = 0f
            }
        } else {
            climbHeld = 0f
        }

        return settled(player.copy(position = position))
    }

    /**
     * A single fixed nudge, for anything that wants to move the player a set
     * distance rather than hold a direction.
     */
    fun step(player: PlayerState, dx: Float, dy: Float): MoveOutcome {
        if (dx == 0f && dy == 0f) return MoveOutcome(player, moved = false, blocked = false)

        val facing = facingFor(dx, dy)
        val aimed = player.copy(facing = facing)

        val afterX = tryAxis(aimed.position, dx, 0f)
        val afterY = tryAxis(afterX ?: aimed.position, 0f, dy)
        val resolved = afterY ?: afterX

        if (resolved == null || resolved == aimed.position) {
            return MoveOutcome(aimed, moved = false, blocked = true)
        }
        return MoveOutcome(settled(aimed.copy(position = resolved)), moved = true, blocked = false)
    }

    /** Clears roll and input, for a revive or a fresh run. */
    fun reset() {
        input = WorldPoint.ZERO
        rollRemaining = 0f
        rollDirection = WorldPoint.ZERO
        invulnerableFor = 0f
        rollCooldown = 0f
        climbHeld = 0f
    }

    /** Drops the player if there is nothing under them, e.g. after mining. */
    private fun settled(player: PlayerState): PlayerState {
        val feet = player.blockPos
        if (feet.z <= 0) return player
        val localX = player.position.x - feet.x
        val localY = player.position.y - feet.y
        if (BlockShapes.occupies(world, feet.below(), localX, localY)) return player

        var z = feet.z
        while (z > 0 && !BlockShapes.occupies(world, BlockPos(feet.x, feet.y, z - 1), localX, localY)) z--
        return player.copy(position = player.position.copy(z = z.toFloat()))
    }

    /**
     * One axis of movement, or null when the way is blocked. Climbing a single
     * step is free; anything taller is a wall.
     */
    private fun tryAxis(from: WorldPoint, dx: Float, dy: Float, maxStep: Int = STEP_UP): WorldPoint? {
        if (dx == 0f && dy == 0f) return from

        val target = from.translated(dx, dy, 0f)
        val columnX = floor(target.x).toInt()
        val columnY = floor(target.y).toInt()
        // Unloaded ground reads as air, and walking onto air drops the player
        // to the bottom of the world. A column that is not there yet is a wall.
        if (!world.isLoaded(BlockPos(columnX, columnY, 0).chunkPos)) return null
        val currentZ = from.toBlockPos().z

        // Tested against the block's real shape, not the cell. A wall a third
        // of a block thick leaves two thirds of its cell open, and treating the
        // whole cell as solid made building a room pointless: you could not
        // walk along the inside of your own wall.
        val localX = target.x - columnX
        val localY = target.y - columnY
        var highestSolid = -1
        for (z in (currentZ + maxStep) downTo 0) {
            if (BlockShapes.occupies(world, BlockPos(columnX, columnY, z), localX, localY, BODY_MARGIN)) {
                highestSolid = z
                break
            }
        }

        val standingZ = highestSolid + 1
        if (standingZ - currentZ > maxStep) return null
        // Climbing is for terrain. A player who could scramble over any wall
        // would make building one pointless.
        if (standingZ - currentZ > STEP_UP &&
            world.blockAt(BlockPos(columnX, columnY, highestSolid)).shape != BlockShape.CUBE
        ) {
            return null
        }
        if (standingZ + 1 >= Chunk.HEIGHT) return null
        // Room to stand: a body is two blocks tall, and climbing under an
        // overhang would put the player's head inside it.
        for (z in standingZ..standingZ + 1) {
            if (BlockShapes.occupies(world, BlockPos(columnX, columnY, z), localX, localY, BODY_MARGIN)) return null
        }
        return WorldPoint(target.x, target.y, standingZ.toFloat())
    }

    private fun facingFor(dx: Float, dy: Float): Direction = when {
        abs(dx) >= abs(dy) && dx > 0 -> Direction.EAST
        abs(dx) >= abs(dy) -> Direction.WEST
        dy > 0 -> Direction.SOUTH
        else -> Direction.NORTH
    }

    companion object {
        /** How far the player climbs without a jump. */
        const val STEP_UP = 1

        /**
         * The tallest ledge a player can scramble up by holding into it.
         *
         * Three, because that is the tallest terrace a pack is likely to make
         * and the deepest a player usually digs before noticing. Taller than
         * that is a cliff, and cliffs should still mean something.
         */
        const val CLIMB_UP = 3

        /** Seconds of pushing before a climb happens, so brushing past a ledge never scales it. */
        const val CLIMB_DELAY = 0.35f

        /**
         * How far outside a thin shape the body is still stopped, in blocks.
         *
         * The player is a point to the collision model and a body on screen.
         * Without a margin they could press their centre right up against a
         * wall and draw half inside it.
         */
        const val BODY_MARGIN = 0.18f
        /** Blocks per second at full stick deflection. */
        const val WALK_SPEED = 4.2f
        /** A roll is a burst, not a sprint: fast and over quickly. */
        const val ROLL_SPEED = 11f
        const val ROLL_DURATION = 0.28f
        /**
         * Shorter than the roll, so the end of a roll is vulnerable. Rolling
         * through an attack has to be timed rather than held.
         */
        const val ROLL_INVULNERABILITY = 0.2f
        const val ROLL_COOLDOWN = 1.1f
        /** Below this the stick is treated as centred. */
        const val INPUT_DEADZONE = 0.12f
    }
}
