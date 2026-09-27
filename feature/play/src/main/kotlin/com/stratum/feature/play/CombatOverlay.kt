package com.stratum.feature.play

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.world.IsometricProjection
import com.stratum.engine.world.Projectile
import com.stratum.engine.world.Telegraph
import com.stratum.engine.world.TelegraphShape
import com.stratum.engine.world.Zone
import kotlin.math.cos
import kotlin.math.sin

// The combat core's world-space shapes on the 2D canvas: wind-up markers and
// burning ground under the actors, projectiles over them. Every outline is
// built from world points and projected, so a circle on the ground reads as a
// circle on the ground at any zoom.

/** Wind-ups fill as they come due: the outline is where, the fill is when. */
internal fun DrawScope.drawTelegraphs(telegraphs: List<Telegraph>, projection: IsometricProjection, originX: Float, originY: Float) {
    telegraphs.forEach { t ->
        val outline = outlineOf(t, 1f)
        val color = if (t.hostile) Color(0xFFD2544B) else Color(t.color)
        drawPath(path(outline, projection, originX, originY), color.copy(alpha = 0.18f))
        drawPath(path(outlineOf(t, t.progress), projection, originX, originY), color.copy(alpha = 0.35f))
        drawPath(path(outline, projection, originX, originY), color.copy(alpha = 0.9f), style = Stroke(2f))
    }
}

internal fun DrawScope.drawZones(zones: List<Zone>, projection: IsometricProjection, originX: Float, originY: Float) {
    zones.forEach { zone ->
        val ring = circle(zone.position, zone.radius)
        drawPath(path(ring, projection, originX, originY), Color(zone.color).copy(alpha = 0.22f))
        drawPath(path(ring, projection, originX, originY), Color(zone.color).copy(alpha = 0.6f), style = Stroke(1.5f))
    }
}

internal fun DrawScope.drawProjectiles(projectiles: List<Projectile>, projection: IsometricProjection, originX: Float, originY: Float) {
    val size = projection.tileWidth * projection.zoom
    projectiles.forEach { p ->
        val at = projection.project(p.position)
        val tail = projection.project(WorldPoint(p.position.x - p.dx * TAIL, p.position.y - p.dy * TAIL, p.position.z))
        val head = Offset(originX + at.x, originY + at.y)
        drawLine(Color(p.color).copy(alpha = 0.5f), Offset(originX + tail.x, originY + tail.y), head, strokeWidth = size * 0.06f)
        drawCircle(Color(p.color), radius = size * 0.08f, center = head)
    }
}

/** The ground a telegraph covers, shrunk towards its centre by [scale]: 1 is the whole marker. */
private fun outlineOf(t: Telegraph, scale: Float): List<WorldPoint> {
    val ground = WorldPoint(t.center.x, t.center.y, t.center.z)
    return when (t.shape) {
        TelegraphShape.CIRCLE -> circle(ground, t.radius * scale)
        TelegraphShape.CONE -> {
            val heading = kotlin.math.atan2(t.aimY, t.aimX)
            val half = Math.toRadians(t.angleDegrees / 2.0).toFloat()
            listOf(ground) + List(SEGMENTS / 2 + 1) { i ->
                val angle = heading - half + 2 * half * i / (SEGMENTS / 2)
                WorldPoint(ground.x + cos(angle) * t.radius * scale, ground.y + sin(angle) * t.radius * scale, ground.z)
            }
        }
        TelegraphShape.LANE -> {
            val length = t.radius * scale
            val sideX = -t.aimY * t.halfWidth
            val sideY = t.aimX * t.halfWidth
            val endX = ground.x + t.aimX * length
            val endY = ground.y + t.aimY * length
            listOf(
                WorldPoint(ground.x + sideX, ground.y + sideY, ground.z), WorldPoint(endX + sideX, endY + sideY, ground.z),
                WorldPoint(endX - sideX, endY - sideY, ground.z), WorldPoint(ground.x - sideX, ground.y - sideY, ground.z),
            )
        }
    }
}

private fun circle(center: WorldPoint, radius: Float): List<WorldPoint> = List(SEGMENTS) { i ->
    val angle = (Math.PI * 2 * i / SEGMENTS).toFloat()
    WorldPoint(center.x + cos(angle) * radius, center.y + sin(angle) * radius, center.z)
}

private fun path(points: List<WorldPoint>, projection: IsometricProjection, originX: Float, originY: Float): Path = Path().apply {
    points.forEachIndexed { i, point ->
        val s = projection.project(point)
        if (i == 0) moveTo(originX + s.x, originY + s.y) else lineTo(originX + s.x, originY + s.y)
    }
    close()
}

private const val SEGMENTS = 24

/** How long a projectile's streak is, in blocks. */
private const val TAIL = 0.6f
