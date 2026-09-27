package com.stratum.engine.crowd

import kotlin.math.sqrt

/** A direction or offset on the ground plane. */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Float) = Vec2(x * k, y * k)
    val length: Float get() = sqrt(x * x + y * y)

    /** This scaled to length one, or zero when it has no direction. */
    fun normalized(): Vec2 = length.let { if (it < EPSILON) ZERO else Vec2(x / it, y / it) }

    /** Turned a quarter to the left: the way to orbit something. */
    fun perpendicular(): Vec2 = Vec2(-y, x)

    companion object {
        val ZERO = Vec2(0f, 0f)
        private const val EPSILON = 1e-5f
    }
}

/**
 * The ground as the crowd sees it: where a body can stand, and at what
 * height. The engine adapts its voxel world to this; the crowd never learns
 * what a block is.
 */
fun interface NavGrid {
    /** The height a body stands at in column ([x], [y]) coming from [nearZ], or null when there is nowhere to stand. */
    fun standingZ(x: Int, y: Int, nearZ: Int): Int?
}

/**
 * Every cell's walking distance to one goal, and so every cell's way there.
 *
 * Built once per goal and shared by the whole crowd: a hundred monsters
 * chasing one hero cost one search, not a hundred. That is the difference
 * between a horde and a slideshow on a phone, and it is how RTS games since
 * Supreme Commander 2 move armies. Heights are followed as the search spreads,
 * so a ledge a body cannot climb is a wall to the field too.
 */
class FlowField private constructor(
    val goalX: Int,
    val goalY: Int,
    val radius: Int,
    private val distances: IntArray,
) {
    private val side = radius * 2 + 1

    /** Steps to the goal from ([x], [y]), in tenths of a block, or null when unreachable or outside the field. */
    fun distanceAt(x: Int, y: Int): Int? = index(x, y)?.let { distances[it] }?.takeIf { it != UNREACHABLE }

    /** The way to go from ([x], [y]): toward the neighbouring cell nearest the goal. Null where the field cannot help. */
    fun directionAt(x: Int, y: Int): Vec2? {
        val here = distanceAt(x, y) ?: return null
        if (here == 0) return Vec2.ZERO
        var best = here
        var bestDirection: Vec2? = null
        NEIGHBOURS.forEach { (dx, dy, _) ->
            val there = distanceAt(x + dx, y + dy) ?: return@forEach
            if (there < best) {
                best = there
                bestDirection = Vec2(dx.toFloat(), dy.toFloat()).normalized()
            }
        }
        return bestDirection
    }

    private fun index(x: Int, y: Int): Int? {
        val lx = x - goalX + radius
        val ly = y - goalY + radius
        return if (lx in 0 until side && ly in 0 until side) ly * side + lx else null
    }

    companion object {
        const val UNREACHABLE = Int.MAX_VALUE
        /** How high a body can climb in one step, and how far it will drop. */
        const val STEP_UP = 1
        const val STEP_DOWN = 4
        private const val STRAIGHT = 10
        private const val DIAGONAL = 14

        private val NEIGHBOURS = listOf(
            Triple(1, 0, STRAIGHT), Triple(-1, 0, STRAIGHT), Triple(0, 1, STRAIGHT), Triple(0, -1, STRAIGHT),
            Triple(1, 1, DIAGONAL), Triple(1, -1, DIAGONAL), Triple(-1, 1, DIAGONAL), Triple(-1, -1, DIAGONAL),
        )

        // The same eight, unboxed, for the search's inner loop.
        private val NX = IntArray(NEIGHBOURS.size) { NEIGHBOURS[it].first }
        private val NY = IntArray(NEIGHBOURS.size) { NEIGHBOURS[it].second }
        private val NCOST = IntArray(NEIGHBOURS.size) { NEIGHBOURS[it].third }

        /** Searches outward from the goal (Dijkstra), [radius] cells each way. */
        fun build(goalX: Int, goalY: Int, goalZ: Int, radius: Int, grid: NavGrid): FlowField {
            val side = radius * 2 + 1
            val distances = IntArray(side * side) { UNREACHABLE }
            val heights = IntArray(side * side)
            // A heap of (cost << 32 | cell) in a plain long array. The boxed
            // queue it replaces allocated on every push and every comparison,
            // and a chase rebuilds this field four times a second.
            val queue = LongHeap(side * 4)
            fun key(lx: Int, ly: Int) = ly * side + lx
            val start = key(radius, radius)
            distances[start] = 0
            heights[start] = goalZ
            queue.push(start.toLong())
            while (queue.isNotEmpty) {
                val entry = queue.pop()
                val cell = (entry and 0xFFFFFFFFL).toInt()
                val cost = (entry ushr 32).toInt()
                if (cost > distances[cell]) continue
                val lx = cell % side
                val ly = cell / side
                for (n in NX.indices) {
                    val dx = NX[n]
                    val dy = NY[n]
                    val nx = lx + dx
                    val ny = ly + dy
                    if (nx !in 0 until side || ny !in 0 until side) continue
                    // No cutting corners: a diagonal needs both sides open, or bodies clip walls.
                    if (dx != 0 && dy != 0 && (blocked(grid, goalX, goalY, radius, lx + dx, ly, heights[cell]) || blocked(grid, goalX, goalY, radius, lx, ly + dy, heights[cell]))) continue
                    val z = grid.standingZ(goalX + nx - radius, goalY + ny - radius, heights[cell]) ?: continue
                    // Searched from the goal outward, so the body walks the other way: it climbs what we descend.
                    if (heights[cell] - z > STEP_UP || z - heights[cell] > STEP_DOWN) continue
                    val next = key(nx, ny)
                    val total = distances[cell] + NCOST[n]
                    if (total < distances[next]) {
                        distances[next] = total
                        heights[next] = z
                        queue.push((total.toLong() shl 32) or next.toLong())
                    }
                }
            }
            return FlowField(goalX, goalY, radius, distances)
        }

        private fun blocked(grid: NavGrid, goalX: Int, goalY: Int, radius: Int, lx: Int, ly: Int, nearZ: Int): Boolean {
            val z = grid.standingZ(goalX + lx - radius, goalY + ly - radius, nearZ) ?: return true
            return z - nearZ > STEP_UP || nearZ - z > STEP_DOWN
        }
    }
}

/**
 * Who is near whom, answered without comparing every body to every other.
 * Rebuilt each tick: cheaper than keeping one up to date as bodies move.
 */
class SpatialHash<T>(private val cellSize: Float, items: List<T>, private val position: (T) -> Vec2) {

    private val cells = HashMap<Long, MutableList<T>>()

    init {
        items.forEach { item -> cells.getOrPut(keyFor(position(item))) { ArrayList(4) }.add(item) }
    }

    /** Everything within [radius] of [at]. */
    fun near(at: Vec2, radius: Float): List<T> {
        val reach = kotlin.math.ceil(radius / cellSize).toInt()
        val cx = kotlin.math.floor(at.x / cellSize).toInt()
        val cy = kotlin.math.floor(at.y / cellSize).toInt()
        val found = ArrayList<T>()
        for (y in cy - reach..cy + reach) for (x in cx - reach..cx + reach) {
            cells[key(x, y)]?.forEach { item -> if ((position(item) - at).length <= radius) found += item }
        }
        return found
    }

    private fun keyFor(at: Vec2) = key(kotlin.math.floor(at.x / cellSize).toInt(), kotlin.math.floor(at.y / cellSize).toInt())

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
}

/**
 * Keeps one flow field and rebuilds it only when the target has moved to
 * another cell and the last build is old enough. A target standing still
 * costs nothing; one running costs a few searches a second.
 */
class FlowFieldCache(private val radius: Int = DEFAULT_RADIUS, private val minRebuildSeconds: Float = REBUILD_SECONDS) {

    private var field: FlowField? = null
    private var age = Float.MAX_VALUE

    fun fieldFor(goalX: Int, goalY: Int, goalZ: Int, grid: NavGrid, deltaSeconds: Float): FlowField {
        age += deltaSeconds
        val current = field
        val moved = current == null || current.goalX != goalX || current.goalY != goalY
        if (current != null && (!moved || age < minRebuildSeconds)) return current
        age = 0f
        return FlowField.build(goalX, goalY, goalZ, radius, grid).also { field = it }
    }

    fun clear() {
        field = null
        age = Float.MAX_VALUE
    }

    companion object {
        const val DEFAULT_RADIUS = 24
        const val REBUILD_SECONDS = 0.25f
    }
}

/** A min-heap of longs, ordered by value, with no boxing. Enough for a Dijkstra frontier. */
internal class LongHeap(capacity: Int) {
    private var items = LongArray(capacity.coerceAtLeast(16))
    private var size = 0

    val isNotEmpty: Boolean get() = size > 0

    fun push(value: Long) {
        if (size == items.size) items = items.copyOf(size * 2)
        var i = size++
        while (i > 0) {
            val parent = (i - 1) ushr 1
            if (items[parent] <= value) break
            items[i] = items[parent]
            i = parent
        }
        items[i] = value
    }

    fun pop(): Long {
        val top = items[0]
        val last = items[--size]
        var i = 0
        while (true) {
            val left = i * 2 + 1
            if (left >= size) break
            val right = left + 1
            val child = if (right < size && items[right] < items[left]) right else left
            if (items[child] >= last) break
            items[i] = items[child]
            i = child
        }
        items[i] = last
        return top
    }
}
