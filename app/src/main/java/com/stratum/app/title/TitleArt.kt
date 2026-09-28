package com.stratum.app.title

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import com.stratum.core.designsystem.theme.StratumTheme

/**
 * The title screen's art: a small floating island of stacked blocks, seen at
 * the world's own 2:1 angle and coloured from the loaded pack.
 *
 * Drawn rather than shipped as a picture, so a pack that recolours the world
 * recolours its title too, and so it is crisp at any size. The island is a
 * fixed height map -- the same every launch -- because a title that changes
 * each time reads as a loading screen rather than as the game's face.
 */
@Composable
internal fun TitleArt(modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    val ground = colors.surfaceRaised
    val grass = colors.accentAlt
    val bronze = colors.accent
    Canvas(modifier) {
        val columns = ISLAND.size
        // Fit the island's diamond footprint plus its tallest stack to the canvas.
        val tallest = ISLAND.maxOf { row -> row.maxOf { it } }
        val tileWidth = minOf(size.width / (columns + 1f), size.height / ((columns + 1) / 2f + tallest * 0.5f + 1.2f))
        val tileHeight = tileWidth / 2f
        val depth = tileWidth / 2f
        val originX = size.width / 2f
        val originY = (size.height - (columns * tileHeight + tallest * depth)) / 2f + tallest * depth

        for (sum in 0 until columns * 2 - 1) {
            for (x in 0 until columns) {
                val y = sum - x
                if (y !in 0 until columns) continue
                val height = ISLAND[y][x]
                for (z in 0 until height) {
                    val top = z == height - 1
                    val base = when {
                        !top -> lerp(bronze, ground, 0.55f)
                        height >= 4 -> bronze
                        else -> grass
                    }
                    val cx = originX + (x - y) * tileWidth / 2f
                    val cy = originY + (x + y) * tileHeight / 2f - z * depth
                    cube(cx, cy, tileWidth, tileHeight, depth, base)
                }
            }
        }
    }
}

/** One block: a lit top, a mid-tone left face and a shaded right face. */
private fun DrawScope.cube(cx: Float, cy: Float, w: Float, h: Float, d: Float, base: Color) {
    val top = Offset(cx, cy - h / 2f)
    val right = Offset(cx + w / 2f, cy)
    val bottom = Offset(cx, cy + h / 2f)
    val left = Offset(cx - w / 2f, cy)
    face(listOf(top, right, bottom, left), lerp(base, Color.White, 0.12f))
    face(listOf(left, bottom, bottom + Offset(0f, d), left + Offset(0f, d)), lerp(base, Color.Black, 0.28f))
    face(listOf(bottom, right, right + Offset(0f, d), bottom + Offset(0f, d)), lerp(base, Color.Black, 0.48f))
}

private fun DrawScope.face(points: List<Offset>, color: Color) {
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, color)
}

/** Stack heights, back row first. Zero is open air around the island's edge. */
private val ISLAND = listOf(
    listOf(0, 1, 2, 1, 0, 0),
    listOf(1, 2, 3, 2, 1, 0),
    listOf(2, 3, 5, 3, 2, 1),
    listOf(1, 2, 3, 4, 2, 1),
    listOf(0, 1, 2, 2, 1, 1),
    listOf(0, 0, 1, 1, 1, 0),
)
