package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.floor

/**
 * Maps the voxel grid onto the screen and back.
 *
 * This is a 2:1 dimetric projection -- the one everyone calls isometric -- where
 * one block step east moves half a tile width right and half a tile height down.
 * The 2:1 ratio is what keeps blocks pixel-aligned at integer tile sizes, which
 * matters for sprite art that must not shimmer when the camera moves.
 */
data class IsometricProjection(
    /**
     * Screen width of one block's top face.
     *
     * Sized so a phone shows a dozen or so blocks across. It was 96, which was
     * a correction of an earlier scale where the character was a speck and the
     * terrain read as texture -- and it over-corrected: five tiles on a screen
     * is not a landscape, it is a close-up of five tiles.
     */
    val tileWidth: Float = 72f,
    /** Screen height of one block's top face; half the width gives the 2:1 look. */
    val tileHeight: Float = 36f,
    /**
     * Screen height gained per z level.
     *
     * A third of the tile width rather than a half. At a half, a block's side
     * face is as tall as the whole tile is deep, so a two-block terrace became
     * a wall taller than the person standing under it: the ground read as a
     * stack of cubes rather than as ground, and every ledge looked like
     * something to be walled in by rather than stepped onto.
     *
     * Lower blocks are also what make the terrain read as *workable*. A ledge
     * you could climb, dig through or build up in a couple of actions invites
     * that; a canyon face does not.
     */
    val blockHeight: Float = 26f,
    val zoom: Float = 1f,
) {
    private val halfWidth get() = tileWidth * zoom / 2f
    private val halfHeight get() = tileHeight * zoom / 2f
    private val liftPerLevel get() = blockHeight * zoom

    /** Screen position of the centre of a block's top face, before camera offset. */
    fun project(x: Float, y: Float, z: Float): ScreenPoint = ScreenPoint(
        x = (x - y) * halfWidth,
        y = (x + y) * halfHeight - z * liftPerLevel,
    )

    fun project(pos: BlockPos): ScreenPoint = project(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat())

    fun project(point: WorldPoint): ScreenPoint = project(point.x, point.y, point.z)

    /**
     * Inverse projection onto a given z plane.
     *
     * A screen point alone is ambiguous in a voxel world -- it could be a tall
     * block far away or a short one nearby -- so the caller names the plane.
     * [pickColumn] resolves the ambiguity properly by walking down through the world.
     */
    fun unproject(screenX: Float, screenY: Float, z: Float = 0f): WorldPoint {
        val liftedY = screenY + z * liftPerLevel
        val a = screenX / halfWidth
        val b = liftedY / halfHeight
        return WorldPoint(
            x = (a + b) / 2f,
            y = (b - a) / 2f,
            z = z,
        )
    }

    /**
     * Finds which block a tap actually hit, by testing each z level from the top
     * down and taking the first solid surface. This is the honest version of
     * picking in an isometric voxel world: a tall pillar in front correctly
     * shadows the ground behind it.
     */
    fun pickColumn(
        screenX: Float,
        screenY: Float,
        isSolidAt: (BlockPos) -> Boolean,
        maxZ: Int = Chunk.HEIGHT - 1,
    ): BlockPos? {
        for (z in maxZ downTo 0) {
            val candidate = unproject(screenX, screenY, z.toFloat())
            val pos = BlockPos(
                floor(candidate.x).toInt(),
                floor(candidate.y).toInt(),
                z,
            )
            if (isSolidAt(pos)) return pos
        }
        return null
    }

    /**
     * Painter's-algorithm sort key: draw ascending and near things cover far ones.
     *
     * `x + y` orders along the view axis and `z` breaks ties within a column, so a
     * block and the actor standing on it never fight over who draws first.
     */
    fun depthKey(pos: BlockPos): Int = (pos.x + pos.y) * Chunk.HEIGHT + pos.z

    fun depthKey(point: WorldPoint): Float = (point.x + point.y) * Chunk.HEIGHT + point.z

    /**
     * Block positions that could appear inside the given screen rectangle.
     *
     * Returned in draw order. Corners are unprojected onto the lowest and highest
     * z planes and the union is taken, because a tall block whose base sits below
     * the viewport can still have its top in view.
     */
    fun visibleRange(
        viewportWidth: Float,
        viewportHeight: Float,
        cameraX: Float,
        cameraY: Float,
        maxZ: Int = Chunk.HEIGHT - 1,
    ): VisibleRange {
        val corners = listOf(
            0f to 0f,
            viewportWidth to 0f,
            0f to viewportHeight,
            viewportWidth to viewportHeight,
        )
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE

        for ((cornerX, cornerY) in corners) {
            for (z in intArrayOf(0, maxZ)) {
                val world = unproject(cornerX - cameraX, cornerY - cameraY, z.toFloat())
                val bx = floor(world.x).toInt()
                val by = floor(world.y).toInt()
                if (bx < minX) minX = bx
                if (bx > maxX) maxX = bx
                if (by < minY) minY = by
                if (by > maxY) maxY = by
            }
        }
        return VisibleRange(
            minX = minX - EDGE_PADDING,
            maxX = maxX + EDGE_PADDING,
            minY = minY - EDGE_PADDING,
            maxY = maxY + EDGE_PADDING,
        )
    }

    /**
     * Whether a column's drawn band touches the viewport at all.
     *
     * [visibleRange] has to assume a column could be as tall as the world, so it
     * returns a generous box — on a phone that is an order of magnitude more
     * columns than the screen can show. This is the exact test, applied per
     * column once its real height is known, and it is what keeps the draw loop
     * proportional to what is on screen rather than to what is loaded.
     *
     * [topZ] is the column's surface; [bottomZ] the lowest level the renderer
     * will draw for it.
     */
    fun isColumnOnScreen(
        x: Int,
        y: Int,
        topZ: Int,
        bottomZ: Int,
        originX: Float,
        originY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): Boolean {
        // Horizontal position does not depend on z, so this half is exact and
        // costs nothing: it is the same test whatever height the column is.
        val centreX = originX + (x - y) * halfWidth
        if (centreX + halfWidth < 0f || centreX - halfWidth > viewportWidth) return false

        val topY = originY + (x + y) * halfHeight - topZ * liftPerLevel
        // The band runs from the top face's upper tip down past the lowest
        // block's base, which sits a block-height below its own centre.
        val bottomY = originY + (x + y) * halfHeight - bottomZ * liftPerLevel +
            halfHeight + liftPerLevel
        if (bottomY < 0f || topY - halfHeight > viewportHeight) return false

        return true
    }

    private companion object {
        /** One block of slack so geometry straddling the edge is not popped away. */
        const val EDGE_PADDING = 1
    }
}

data class ScreenPoint(val x: Float, val y: Float)

data class VisibleRange(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
    val columnCount: Int get() = (maxX - minX + 1) * (maxY - minY + 1)

    /**
     * Columns in painter order: ascending `x + y` draws back to front, so later
     * draws overlap earlier ones exactly as depth requires.
     */
    inline fun forEachColumnInDrawOrder(action: (x: Int, y: Int) -> Unit) {
        for (sum in (minX + minY)..(maxX + maxY)) {
            for (x in maxOf(minX, sum - maxY)..minOf(maxX, sum - minY)) {
                action(x, sum - x)
            }
        }
    }
}
