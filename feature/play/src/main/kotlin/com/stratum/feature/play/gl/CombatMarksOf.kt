package com.stratum.feature.play.gl

import com.stratum.engine.scene.CombatMark
import com.stratum.engine.scene.CombatMarkKind
import com.stratum.engine.scene.MarkShape
import com.stratum.engine.world.Projectile
import com.stratum.engine.world.Telegraph
import com.stratum.engine.world.TelegraphShape
import com.stratum.engine.world.Zone

/**
 * The combat core's world-space state, as the 3D scene's plain marks: the
 * same projectiles, zones and wind-ups the 2D canvas draws, in the order a
 * reader expects them layered -- ground first, then what flies over it.
 */
internal fun combatMarksOf(projectiles: List<Projectile>, zones: List<Zone>, telegraphs: List<Telegraph>): List<CombatMark> =
    zones.map { zone ->
        CombatMark(CombatMarkKind.ZONE, zone.position.x, zone.position.y, zone.position.z, zone.radius, zone.color, look = zone.look)
    } + telegraphs.map { t ->
        CombatMark(
            CombatMarkKind.TELEGRAPH, t.center.x, t.center.y, t.center.z, t.radius, t.color,
            progress = t.progress,
            shape = when (t.shape) {
                TelegraphShape.CIRCLE -> MarkShape.CIRCLE
                TelegraphShape.CONE -> MarkShape.CONE
                TelegraphShape.LANE -> MarkShape.LANE
            },
            dirX = t.aimX, dirY = t.aimY, angleDegrees = t.angleDegrees, halfWidth = t.halfWidth, hostile = t.hostile,
        )
    } + projectiles.map { p ->
        CombatMark(CombatMarkKind.PROJECTILE, p.position.x, p.position.y, p.position.z, p.radius, p.color, dirX = p.dx, dirY = p.dy, look = p.look)
    }
