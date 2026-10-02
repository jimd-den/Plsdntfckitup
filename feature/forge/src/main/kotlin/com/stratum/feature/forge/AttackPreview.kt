package com.stratum.feature.forge

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import com.stratum.core.domain.attack.AttackSketch
import com.stratum.core.domain.attack.ParticleShape
import com.stratum.core.domain.attack.ProceduralSkill
import com.stratum.core.domain.attack.SketchMark
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A forged attack playing on a little stage: the caster at the left, a
 * target ahead, and the attack flying, landing and scarring the ground in
 * its own look. Drawn from [AttackSketch], the same marks the scene uses.
 *
 * @param animate false holds it at [time] seconds, for screenshots.
 */
@Composable
fun AttackPreview(skill: ProceduralSkill, modifier: Modifier = Modifier, animate: Boolean = true, time: Float = AttackSketch.LOOP * 0.4f) {
    var t by remember { mutableFloatStateOf(time) }
    if (animate) LaunchedEffect(skill) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> t = (now - start) / 1e9f }
    }
    val marks = remember(skill, t) { AttackSketch.preview(skill, t) }
    Canvas(modifier.clipToBounds()) { drawSketch(marks) }
}

/** The stage's projection: a gentle three-quarter view, x to the right, y into the screen, z up. */
private class Stage(size: Size) {
    val unit = size.width / (AttackSketch.TARGET + 3f)
    private val ox = unit * 1.5f
    private val oy = size.height * 0.62f
    fun at(x: Float, y: Float, z: Float) = Offset(ox + x * unit, oy + y * unit * TILT - z * unit * RISE)

    companion object {
        const val TILT = 0.5f
        const val RISE = 0.85f
    }
}

/** Draws sketch marks: ground first, then what stands and flies, farthest first. */
fun DrawScope.drawSketch(marks: List<SketchMark>) {
    val stage = Stage(size)
    drawStage(stage)
    marks.filterIsInstance<SketchMark.Ground>().forEach { drawGround(stage, it) }
    marks.filterNot { it is SketchMark.Ground }
        .sortedBy { when (it) { is SketchMark.Glow -> it.y; is SketchMark.Streak -> (it.y0 + it.y1) / 2; else -> 0f } }
        .forEach { m ->
            when (m) {
                is SketchMark.Glow -> drawGlow(stage, m)
                is SketchMark.Streak -> drawStreak(stage, m)
                is SketchMark.Ground -> Unit
            }
        }
}

private fun colorOf(argb: Int, alpha: Float) = Color(argb).copy(alpha = alpha.coerceIn(0f, 1f))

/** A floor grid and the two figures, so a scale and a direction read at a glance. */
private fun DrawScope.drawStage(stage: Stage) {
    val line = Color.White.copy(alpha = 0.06f)
    for (i in -1..AttackSketch.TARGET.toInt() + 2) drawLine(line, stage.at(i.toFloat(), -3f, 0f), stage.at(i.toFloat(), 3f, 0f), 1f)
    for (j in -3..3) drawLine(line, stage.at(-1f, j.toFloat(), 0f), stage.at(AttackSketch.TARGET + 2f, j.toFloat(), 0f), 1f)
    fun figure(x: Float, color: Color) {
        val feet = stage.at(x, 0f, 0f); val head = stage.at(x, 0f, 1.6f)
        drawOval(Color.Black.copy(alpha = 0.35f), Offset(feet.x - stage.unit * 0.35f, feet.y - stage.unit * 0.12f), Size(stage.unit * 0.7f, stage.unit * 0.24f))
        drawLine(color, feet, head, stage.unit * 0.28f, StrokeCap.Round)
        drawCircle(color, stage.unit * 0.2f, head)
    }
    figure(0f, Color(0xFFB9A58A))
    figure(AttackSketch.TARGET, Color(0xFF6E5A6E))
}

private fun DrawScope.drawGround(stage: Stage, g: SketchMark.Ground) {
    val c = stage.at(g.x, g.y, 0f)
    val w = g.radius * stage.unit; val h = w * Stage.TILT
    val color = colorOf(g.color, g.alpha)
    if (g.ring) drawOval(color, Offset(c.x - w, c.y - h), Size(w * 2, h * 2), style = Stroke(width = (stage.unit * 0.06f).coerceAtLeast(1.5f)))
    else drawOval(color, Offset(c.x - w, c.y - h), Size(w * 2, h * 2))
}

private fun DrawScope.drawStreak(stage: Stage, s: SketchMark.Streak) {
    val a = stage.at(s.x0, s.y0, s.z0); val b = stage.at(s.x1, s.y1, s.z1)
    val w = (s.width * stage.unit).coerceAtLeast(1.2f)
    drawLine(colorOf(s.color, s.alpha * 0.35f), a, b, w * 2.2f, StrokeCap.Round, blendMode = BlendMode.Plus)
    drawLine(colorOf(s.color, s.alpha), a, b, w, StrokeCap.Round)
}

private fun DrawScope.drawGlow(stage: Stage, g: SketchMark.Glow) {
    val c = stage.at(g.x, g.y, g.z)
    val r = (g.radius * stage.unit).coerceAtLeast(1.2f)
    val color = colorOf(g.color, g.alpha)
    when (g.form) {
        ParticleShape.DOT -> drawCircle(
            Brush.radialGradient(listOf(color, color.copy(alpha = color.alpha * 0.5f), Color.Transparent), c, r), r, c, blendMode = BlendMode.Plus,
        )
        ParticleShape.RING -> drawCircle(color, r, c, style = Stroke(width = (r * 0.35f).coerceAtLeast(1f)))
        ParticleShape.STREAK -> drawLine(color, Offset(c.x - cos(g.angle) * r * 1.6f, c.y - sin(g.angle) * r * 0.8f), Offset(c.x + cos(g.angle) * r * 1.6f, c.y + sin(g.angle) * r * 0.8f), r * 0.6f, StrokeCap.Round)
        ParticleShape.VOXEL -> rotateRad(g.angle, c) { drawRect(color, Offset(c.x - r, c.y - r), Size(r * 2, r * 2)) }
        ParticleShape.TRIANGLE -> drawPath(polygon(c, r, 3, g.angle), color)
        ParticleShape.STAR -> drawPath(star(c, r, g.angle), color)
        ParticleShape.CROSS -> rotateRad(g.angle, c) {
            drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), r * 0.45f, StrokeCap.Round)
            drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y + r), r * 0.45f, StrokeCap.Round)
        }
        ParticleShape.FEATHER, ParticleShape.LEAF -> rotateRad(g.angle, c) {
            drawOval(color, Offset(c.x - r * 1.4f, c.y - r * (if (g.form == ParticleShape.LEAF) 0.6f else 0.35f)), Size(r * 2.8f, r * (if (g.form == ParticleShape.LEAF) 1.2f else 0.7f)))
        }
        ParticleShape.DROPLET -> {
            drawCircle(color, r * 0.75f, Offset(c.x, c.y + r * 0.25f))
            drawPath(polygon(Offset(c.x, c.y - r * 0.2f), r * 0.6f, 3, -PI.toFloat() / 2), color)
        }
    }
}

private fun polygon(c: Offset, r: Float, n: Int, angle: Float) = Path().apply {
    for (i in 0 until n) {
        val a = angle + (2 * PI * i / n).toFloat()
        val p = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun star(c: Offset, r: Float, angle: Float) = Path().apply {
    for (i in 0 until 10) {
        val a = angle + (PI * i / 5).toFloat() - PI.toFloat() / 2
        val d = if (i % 2 == 0) r else r * 0.42f
        val p = Offset(c.x + cos(a) * d, c.y + sin(a) * d)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}
