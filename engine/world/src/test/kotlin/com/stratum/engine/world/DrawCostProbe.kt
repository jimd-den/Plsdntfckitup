package com.stratum.engine.world

import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the draw loop's cost.
 *
 * [IsometricProjection.visibleRange] must assume a column could be as tall as
 * the world, so it hands back far more columns than a phone can show. The per
 * column test is what brings the loop back down to what is on screen, and this
 * is here so a change that quietly removes it shows up as a failure rather than
 * as a frame rate complaint.
 */
class DrawCostProbe {

    private val projection = IsometricProjection(zoom = 1f)
    private val width = 1080f
    private val height = 2000f

    @Test
    fun `culling keeps the draw loop proportional to the screen, not the world`() {
        val session = WorldSession(
            content = TestContent.assembled,
            config = WorldConfig(seed = 7L, simulationRadius = 2),
        )
        val camera = session.player.position
        val originX = width / 2f - projection.project(camera).x
        val originY = height / 2f - projection.project(camera).y
        val range = projection.visibleRange(width, height, originX, originY)

        var loaded = 0
        var drawn = 0
        range.forEachColumnInDrawOrder { x, y ->
            val surface = session.world.surfaceAt(x, y)
            if (surface < 0) return@forEachColumnInDrawOrder
            loaded++
            val floor = maxOf(0, surface - 6)
            if (projection.isColumnOnScreen(x, y, surface, floor, originX, originY, width, height)) {
                drawn++
            }
        }

        println("range=${range.columnCount} loaded=$loaded drawn=$drawn")

        assertTrue(loaded > 0, "no columns were loaded, so this measured nothing")
        assertTrue(
            drawn < loaded / 2,
            "culling removed almost nothing: $drawn of $loaded columns still drawn",
        )
        // A phone shows on the order of a thousand tiles. An order of magnitude
        // above that means the cull has stopped working.
        assertTrue(drawn < 3000, "$drawn columns per frame is too many to hold a frame rate")
    }

    @Test
    fun `a column far off screen is culled and one at the camera is not`() {
        val originX = width / 2f
        val originY = height / 2f

        assertTrue(
            projection.isColumnOnScreen(0, 0, 12, 6, originX, originY, width, height),
            "the column under the camera was culled",
        )
        assertTrue(
            !projection.isColumnOnScreen(400, -400, 12, 6, originX, originY, width, height),
            "a column far to the side survived culling",
        )
        assertTrue(
            !projection.isColumnOnScreen(400, 400, 12, 6, originX, originY, width, height),
            "a column far below survived culling",
        )
    }
}
