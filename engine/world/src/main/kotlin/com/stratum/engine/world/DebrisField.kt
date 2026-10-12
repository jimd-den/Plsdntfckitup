package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.MutableWorld
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** One loose block in flight, as the renderer needs it. */
data class DebrisPiece(val x: Float, val y: Float, val z: Float, val blockIndex: Int, val size: Float)

/**
 * Blocks torn loose by an attack, flying as bodies until they come to rest.
 *
 * Each piece falls under gravity, bounces off what it hits with a little
 * friction, and when it has sat still on solid ground for a moment it
 * becomes a block again where it lies -- so a brute's slam throws the
 * ground about and leaves it reshaped, not merely holed. Pieces that cannot
 * settle (no room, or the world is not loaded there) crumble to nothing.
 *
 * Cheap by construction: a piece costs a few block reads a step, steps are
 * sized so nothing tunnels through a block, and at most [max] fly at once;
 * past that the oldest settles on the spot.
 */
class DebrisField(private val world: MutableWorld, private val max: Int) {

    private class Body(
        var x: Float, var y: Float, var z: Float,
        var vx: Float, var vy: Float, var vz: Float,
        val block: Int, val settles: Boolean,
        var age: Float = 0f, var resting: Float = 0f,
    )

    private val bodies = ArrayList<Body>()

    /** Blocks that came to rest and were laid back into the world. */
    var settled: Int = 0
        private set

    val count: Int get() = bodies.size

    /** What is in flight now, for the renderer. */
    fun pieces(): List<DebrisPiece> = bodies.map { DebrisPiece(it.x, it.y, it.z, it.block, PIECE) }

    /**
     * Throws [block] from the centre of [cell] with velocity ([vx], [vy], [vz])
     * in blocks a second. With [settles] false it crumbles where it lands.
     */
    fun launch(cell: BlockPos, block: Int, vx: Float, vy: Float, vz: Float, settles: Boolean = true) {
        if (max <= 0 || block == BlockRegistry.AIR_INDEX) return
        if (bodies.size >= max) bodies.removeAt(0).let(::settle)
        bodies += Body(cell.x + 0.5f, cell.y + 0.5f, cell.z + 0.5f, vx, vy, vz, block, settles)
    }

    fun advance(deltaSeconds: Float) {
        if (bodies.isEmpty() || deltaSeconds <= 0f) return
        val done = ArrayList<Body>()
        for (b in bodies) {
            b.age += deltaSeconds
            val speed = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz)
            // Never more than a third of a block per step, so nothing passes through a wall.
            val steps = ceil(speed * deltaSeconds / MAX_STEP).toInt().coerceIn(1, MAX_SUBSTEPS)
            val dt = deltaSeconds / steps
            repeat(steps) { step(b, dt) }
            if (b.resting >= REST_SECONDS || b.age >= LIFETIME) done += b
        }
        done.forEach { bodies.remove(it); settle(it) }
    }

    private fun step(b: Body, dt: Float) {
        b.vz -= GRAVITY * dt
        // Each axis on its own, so a piece slides along a wall it grazes instead of sticking to it.
        val nx = b.x + b.vx * dt
        if (solid(nx, b.y, b.z)) { b.vx = -b.vx * WALL_BOUNCE } else b.x = nx
        val ny = b.y + b.vy * dt
        if (solid(b.x, ny, b.z)) { b.vy = -b.vy * WALL_BOUNCE } else b.y = ny
        val nz = b.z + b.vz * dt
        if (solid(b.x, b.y, nz - HALF)) {
            // Landing: bounce a little, lose speed to friction, and count how long it has lain still.
            b.vz = if (abs(b.vz) > BOUNCE_SPEED) -b.vz * FLOOR_BOUNCE else 0f
            b.vx *= FRICTION; b.vy *= FRICTION
            b.z = floor(nz - HALF) + 1f + HALF
            val still = abs(b.vx) + abs(b.vy) + abs(b.vz) < STILL_SPEED
            b.resting = if (still) b.resting + dt else 0f
        } else {
            b.z = nz
            b.resting = 0f
        }
        if (b.z < 1f) b.resting = REST_SECONDS
    }

    private fun solid(x: Float, y: Float, z: Float): Boolean {
        if (z < 0f || z >= Chunk.HEIGHT) return z < 0f
        return world.isSolid(BlockPos(floor(x).toInt(), floor(y).toInt(), floor(z).toInt()))
    }

    private fun settle(b: Body) {
        if (!b.settles) return
        val cell = BlockPos(floor(b.x).toInt(), floor(b.y).toInt(), floor(b.z).toInt())
        if (cell.z !in 1 until Chunk.HEIGHT) return
        if (world.blockAt(cell).isAir && world.setBlock(cell, b.block)) settled++
    }

    /** Settles everything at once: the fight is over, or the world is being saved. */
    fun clear() {
        bodies.forEach(::settle)
        bodies.clear()
    }

    companion object {
        const val GRAVITY = 22f
        const val PIECE = 0.7f
        private const val HALF = PIECE / 2
        private const val MAX_STEP = 0.33f
        private const val MAX_SUBSTEPS = 8
        private const val WALL_BOUNCE = 0.35f
        private const val FLOOR_BOUNCE = 0.3f
        private const val BOUNCE_SPEED = 2.5f
        private const val FRICTION = 0.7f
        private const val STILL_SPEED = 0.6f
        private const val REST_SECONDS = 0.25f
        private const val LIFETIME = 6f
    }
}
