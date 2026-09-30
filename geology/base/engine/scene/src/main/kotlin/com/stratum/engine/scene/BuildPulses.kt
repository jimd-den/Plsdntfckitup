package com.stratum.engine.scene

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World

/**
 * Blocks that have just appeared or vanished near the camera, found by
 * watching the chunks themselves.
 *
 * Building feels solid when every block answers the finger: a placed block
 * pops in, a removed one leaves a puff of dust. Rather than have every way
 * of editing the world report what it did -- a tap, a dragged wall, a room,
 * an undo, a redo, a raised blueprint, a dig -- the scene notices: it keeps a
 * copy of each chunk near the camera and, when a chunk's revision moves,
 * compares. That costs nothing while the world is still (one revision check
 * per nearby chunk) and a 24 KB compare per edited chunk when it is not.
 *
 * Chunks seen for the first time, or reloaded, only start being watched:
 * streaming new ground in is not an edit.
 */
class BuildPulses(
    /** Chunks each way from the camera's chunk that are watched; the player edits within reach, which is less. */
    private val reach: Int = 2,
    /** Pulses alive at once; a blueprint raising hundreds of blocks animates its first few dozen. */
    private val maxAlive: Int = MAX_ALIVE,
) {
    /**
     * One changed cell: [block] now stands where [was] stood, both registry
     * indices, since [born] (the scene clock, in seconds).
     */
    class Pulse(val x: Int, val y: Int, val z: Int, val block: Int, val was: Int, val born: Float) {
        val placed: Boolean get() = block != BlockRegistry.AIR_INDEX
        val chunk: ChunkPos get() = ChunkPos.containing(x, y)
    }

    private class Seen(val chunk: Chunk, var revision: Int, var cells: ShortArray)

    private val seen = HashMap<ChunkPos, Seen>()
    private val alive = ArrayList<Pulse>()
    private var lastRevision = Int.MIN_VALUE
    private var lastChunk: ChunkPos? = null
    private var lastNow = 0f

    /** Pulses still animating, oldest first. */
    val active: List<Pulse> get() = alive

    /**
     * Looks for changed cells around ([centreX], [centreY]) and ages the
     * pulses. [worldRevision] lets a frame in which nothing changed skip the
     * look altogether.
     */
    fun update(world: World, centreX: Int, centreY: Int, worldRevision: Int, now: Float) {
        // A clock that went backwards is a new world or a reloaded one: nothing carries over.
        if (now < lastNow) { alive.clear(); seen.clear() }
        lastNow = now
        alive.removeAll { now - it.born > LIFETIME }
        val centre = ChunkPos.containing(centreX, centreY)
        if (worldRevision == lastRevision && centre == lastChunk) return
        lastRevision = worldRevision
        lastChunk = centre
        for (dy in -reach..reach) for (dx in -reach..reach) {
            val pos = ChunkPos(centre.x + dx, centre.y + dy)
            val chunk = world.chunkAt(pos) ?: continue
            val known = seen[pos]
            if (known == null || known.chunk !== chunk) {
                seen[pos] = Seen(chunk, chunk.revision, chunk.exportBlocks())
                continue
            }
            if (known.revision == chunk.revision) continue
            val current = chunk.exportBlocks()
            compare(pos, known.cells, current, now)
            known.cells = current
            known.revision = chunk.revision
        }
        seen.keys.retainAll { kotlin.math.abs(it.x - centre.x) <= reach + 1 && kotlin.math.abs(it.y - centre.y) <= reach + 1 }
    }

    private fun compare(pos: ChunkPos, before: ShortArray, after: ShortArray, now: Float) {
        for (i in before.indices) {
            if (before[i] == after[i]) continue
            if (alive.size >= maxAlive) return
            val x = i % Chunk.SIZE; val y = (i / Chunk.SIZE) % Chunk.SIZE; val z = i / (Chunk.SIZE * Chunk.SIZE)
            alive += Pulse(pos.originX + x, pos.originY + y, z, after[i].toInt(), before[i].toInt(), now)
        }
    }

    companion object {
        /** Seconds a placed block takes to pop to its full size. */
        const val POP_SECONDS = 0.16f

        /** Seconds a puff of dust hangs in the air. */
        const val DUST_SECONDS = 0.4f

        /** Longest a pulse is kept: a placed block is drawn until its chunk's new mesh lands, but not for ever. */
        const val LIFETIME = 1.5f

        const val MAX_ALIVE = 48

        /**
         * A placed block's size over its pop, 0..1 of the way through:
         * quick growth from a little over half size, a slight overshoot and a
         * settle -- the ease-out-back curve, which reads as something landing
         * with weight rather than fading in.
         */
        fun popScale(t: Float): Float {
            if (t >= 1f) return 1f
            val u = t.coerceAtLeast(0f) - 1f
            val back = 1f + OVERSHOOT_C3 * u * u * u + OVERSHOOT_C1 * u * u
            return START_SCALE + (1f - START_SCALE) * back
        }

        private const val START_SCALE = 0.55f
        private const val OVERSHOOT_C1 = 1.9f
        private const val OVERSHOOT_C3 = OVERSHOOT_C1 + 1f
    }
}
