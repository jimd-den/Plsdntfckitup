package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.Chunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsometricProjectionTest {

    private val projection = IsometricProjection(tileWidth = 64f, tileHeight = 32f, blockHeight = 32f)

    @Test
    fun `projecting and unprojecting on the same plane round trips`() {
        for (x in -20..20) {
            for (y in -20..20) {
                val screen = projection.project(x.toFloat(), y.toFloat(), 3f)
                val back = projection.unproject(screen.x, screen.y, 3f)
                assertEquals(x.toFloat(), back.x, absoluteTolerance = 0.001f)
                assertEquals(y.toFloat(), back.y, absoluteTolerance = 0.001f)
            }
        }
    }

    @Test
    fun `one step east and one step south mirror across the vertical axis`() {
        val origin = projection.project(0f, 0f, 0f)
        val east = projection.project(1f, 0f, 0f)
        val south = projection.project(0f, 1f, 0f)

        assertEquals(32f, east.x - origin.x, absoluteTolerance = 0.001f)
        assertEquals(16f, east.y - origin.y, absoluteTolerance = 0.001f)
        assertEquals(-32f, south.x - origin.x, absoluteTolerance = 0.001f)
        assertEquals(16f, south.y - origin.y, absoluteTolerance = 0.001f)
    }

    @Test
    fun `height lifts a block straight up the screen`() {
        val ground = projection.project(4f, 4f, 0f)
        val raised = projection.project(4f, 4f, 1f)
        assertEquals(ground.x, raised.x, absoluteTolerance = 0.001f)
        assertEquals(-32f, raised.y - ground.y, absoluteTolerance = 0.001f)
    }

    @Test
    fun `zoom scales the projection uniformly`() {
        val doubled = projection.copy(zoom = 2f)
        val a = projection.project(3f, 5f, 2f)
        val b = doubled.project(3f, 5f, 2f)
        assertEquals(a.x * 2f, b.x, absoluteTolerance = 0.001f)
        assertEquals(a.y * 2f, b.y, absoluteTolerance = 0.001f)
    }

    @Test
    fun `depth ordering draws far blocks before near ones`() {
        val far = projection.depthKey(BlockPos(0, 0, 0))
        val near = projection.depthKey(BlockPos(5, 5, 0))
        assertTrue(far < near, "a block closer to the camera must sort later")
    }

    @Test
    fun `within a column the higher block draws last`() {
        val low = projection.depthKey(BlockPos(2, 2, 0))
        val high = projection.depthKey(BlockPos(2, 2, 7))
        assertTrue(low < high)
    }

    @Test
    fun `draw order never revisits a column and covers the whole range`() {
        val range = VisibleRange(minX = -2, maxX = 3, minY = -1, maxY = 4)
        val visited = mutableListOf<Pair<Int, Int>>()
        range.forEachColumnInDrawOrder { x, y -> visited += x to y }

        assertEquals(range.columnCount, visited.size)
        assertEquals(range.columnCount, visited.toSet().size, "a column was drawn twice")

        // The ordering guarantee the painter's algorithm depends on.
        val sums = visited.map { it.first + it.second }
        assertEquals(sums.sorted(), sums, "columns were not emitted back to front")
    }

    @Test
    fun `picking returns the topmost solid block in a column`() {
        val pillarTop = BlockPos(2, 2, 9)
        val solid = setOf(BlockPos(2, 2, 0), BlockPos(2, 2, 5), pillarTop)
        val screen = projection.project(pillarTop)

        val hit = projection.pickColumn(screen.x, screen.y, isSolidAt = solid::contains)
        assertEquals(pillarTop, hit)
    }

    @Test
    fun `picking empty space returns nothing`() {
        val screen = projection.project(3f, 3f, 0f)
        assertNull(projection.pickColumn(screen.x, screen.y, isSolidAt = { false }))
    }

    @Test
    fun `a tall block in front shadows the ground behind it`() {
        // Screen y is (x + y) * halfHeight - z * blockHeight, so a block two
        // steps nearer and two levels higher lands on exactly the same pixel as
        // the ground behind it. Picking must choose the one in front.
        val pillar = BlockPos(4, 4, 2)
        val behind = BlockPos(2, 2, 0)
        val pillarScreen = projection.project(pillar)
        val behindScreen = projection.project(behind)
        assertEquals(pillarScreen.x, behindScreen.x, absoluteTolerance = 0.001f)
        assertEquals(pillarScreen.y, behindScreen.y, absoluteTolerance = 0.001f)

        val solid = setOf(pillar, behind)
        assertEquals(pillar, projection.pickColumn(pillarScreen.x, pillarScreen.y, solid::contains))
    }

    @Test
    fun `the visible range covers every column the viewport can show`() {
        val range = projection.visibleRange(
            viewportWidth = 1080f,
            viewportHeight = 1920f,
            cameraX = 540f,
            cameraY = 960f,
        )
        assertTrue(range.minX < range.maxX)
        assertTrue(range.minY < range.maxY)

        // Every screen corner must land inside the range, or geometry pops at the edges.
        listOf(0f to 0f, 1080f to 0f, 0f to 1920f, 1080f to 1920f).forEach { (cx, cy) ->
            for (z in intArrayOf(0, Chunk.HEIGHT - 1)) {
                val world = projection.unproject(cx - 540f, cy - 960f, z.toFloat())
                val bx = kotlin.math.floor(world.x).toInt()
                val by = kotlin.math.floor(world.y).toInt()
                assertTrue(bx in range.minX..range.maxX, "corner x $bx outside ${range.minX}..${range.maxX}")
                assertTrue(by in range.minY..range.maxY, "corner y $by outside ${range.minY}..${range.maxY}")
            }
        }
    }
}
