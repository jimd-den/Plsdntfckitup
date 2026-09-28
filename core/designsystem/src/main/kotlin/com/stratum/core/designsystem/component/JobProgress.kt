package com.stratum.core.designsystem.component

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.Stroke
import com.stratum.core.designsystem.theme.StratumColors
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.creation.CreationJob
import com.stratum.core.domain.creation.JobStatus
import com.stratum.core.domain.creation.JobStep
import com.stratum.core.domain.creation.formatElapsed
import kotlinx.coroutines.delay

/**
 * The wall-clock time, rewritten every second while [ticking], so an elapsed
 * readout counts up without the job itself having to change.
 */
@Composable
fun rememberNow(ticking: Boolean): Long {
    val now by produceState(System.currentTimeMillis(), ticking) {
        value = System.currentTimeMillis()
        while (ticking) {
            delay(TICK_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * One creation job, whole: what it is, how long it has taken, every step with
 * what it is doing now, and -- when it settles -- what came back or why it did
 * not, in plain words.
 *
 * The timeline is the point. A spinner says "wait"; this says "it is waiting
 * for the model, 12 seconds so far, and the reply will be checked and saved
 * after", which is the difference between patience and wondering whether it
 * hung.
 */
@Composable
fun JobProgress(
    job: CreationJob,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    now: Long = rememberNow(job.isActive),
    /** Drop the header, for a place that already names the job. */
    showHeader: Boolean = true,
) {
    val colors = StratumTheme.colors
    StratumPanel(modifier = modifier.animateContentSize()) {
        if (showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                JobStatusMark(job.status, size = 22.dp)
                Spacer(Modifier.width(Space.medium))
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${job.kind.uppercase()} · ${statusWord(job.status)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusTint(job.status, colors),
                    )
                }
                Spacer(Modifier.width(Space.small))
                Text(formatElapsed(job.elapsedMillis(now)), style = MaterialTheme.typography.labelLarge, color = colors.inkMuted)
            }
            Spacer(Modifier.height(Space.medium))
        }
        JobTimeline(job.steps)
        job.summary?.takeIf { !job.isActive }?.let { summary ->
            Spacer(Modifier.height(Space.medium))
            JobSummary(job.status, summary)
        }
        if ((onCancel != null && job.isActive) || (onDismiss != null && !job.isActive)) {
            Spacer(Modifier.height(Space.medium))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (job.isActive && onCancel != null) {
                    StratumAction(label = "Stop", onClick = onCancel, emphasis = ActionEmphasis.QUIET)
                } else if (onDismiss != null) {
                    StratumAction(label = "Dismiss", onClick = onDismiss, emphasis = ActionEmphasis.QUIET)
                }
            }
        }
    }
}

/** The steps as a rail: a mark per step, a line joining them, the live detail under each. */
@Composable
fun JobTimeline(steps: List<JobStep>, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    if (steps.isEmpty()) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            JobSpinner(size = 14.dp)
            Spacer(Modifier.width(Space.small))
            Text("Starting…", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        }
        return
    }
    Column(modifier) {
        steps.forEachIndexed { index, step ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(RAIL_WIDTH).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(Space.hair))
                    JobStatusMark(step.status, size = 16.dp)
                    if (index < steps.lastIndex) {
                        Box(
                            Modifier
                                .weight(1f)
                                .width(Stroke.edge)
                                .background(if (step.status == JobStatus.DONE) colors.accent.copy(alpha = 0.5f) else colors.hairline),
                        )
                    }
                }
                Spacer(Modifier.width(Space.small))
                Column(Modifier.weight(1f).padding(bottom = if (index < steps.lastIndex) Space.medium else 0.dp)) {
                    Text(
                        step.label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (step.status == JobStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
                        color = when (step.status) {
                            JobStatus.QUEUED, JobStatus.CANCELLED -> colors.inkMuted
                            else -> colors.ink
                        },
                    )
                    step.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (step.status == JobStatus.FAILED) colors.danger else colors.inkMuted,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** What came back, or why it did not: the one line a person reads when the job is over. */
@Composable
private fun JobSummary(status: JobStatus, summary: String) {
    val colors = StratumTheme.colors
    val tint = statusTint(status, colors)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Cut.small)
            .background(tint.copy(alpha = 0.12f))
            .border(Stroke.hairline, tint.copy(alpha = 0.5f), Cut.small)
            .padding(Space.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(statusGlyph(status), color = tint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(Space.small))
        Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.ink)
    }
}

/** A step's or a job's state at a glance: a spinner while it runs, a mark once it settles. */
@Composable
fun JobStatusMark(status: JobStatus, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val colors = StratumTheme.colors
    val description = statusWord(status)
    if (status == JobStatus.RUNNING) {
        JobSpinner(modifier.semantics { contentDescription = description }, size = size)
        return
    }
    val tint = statusTint(status, colors)
    val filled = status == JobStatus.DONE || status == JobStatus.FAILED
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) tint else Color.Transparent)
            .border(Stroke.edge, tint.copy(alpha = if (status == JobStatus.QUEUED) 0.45f else 1f), CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (status != JobStatus.QUEUED) {
            Text(
                statusGlyph(status),
                color = if (filled) colors.surface else tint,
                fontSize = (size.value * 0.6f).sp,
                lineHeight = (size.value * 0.6f).sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The game's spinner: an accent arc turning in a sunken ring. Drawn here rather
 * than borrowed from Material so it sits in the same line weight as the rest.
 */
@Composable
fun JobSpinner(modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = StratumTheme.colors.accent) {
    val colors = StratumTheme.colors
    val turn by rememberInfiniteTransition(label = "job-spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart),
        label = "job-spinner-turn",
    )
    Canvas(modifier.size(size).rotate(turn)) {
        val width = (this.size.minDimension * 0.16f).coerceAtLeast(2f)
        val inset = width / 2
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - width, this.size.height - width)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(colors.hairline, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = DrawStroke(width))
        drawArc(tint, -90f, 110f, useCenter = false, topLeft = topLeft, size = arcSize, style = DrawStroke(width, cap = StrokeCap.Round))
    }
}

internal fun statusWord(status: JobStatus): String = when (status) {
    JobStatus.QUEUED -> "waiting"
    JobStatus.RUNNING -> "working"
    JobStatus.DONE -> "done"
    JobStatus.FAILED -> "failed"
    JobStatus.CANCELLED -> "stopped"
}

internal fun statusGlyph(status: JobStatus): String = when (status) {
    JobStatus.QUEUED -> "·"
    JobStatus.RUNNING -> "…"
    JobStatus.DONE -> "✓"
    JobStatus.FAILED -> "!"
    JobStatus.CANCELLED -> "–"
}

internal fun statusTint(status: JobStatus, colors: StratumColors): Color = when (status) {
    JobStatus.QUEUED, JobStatus.CANCELLED -> colors.inkMuted
    JobStatus.RUNNING -> colors.accent
    JobStatus.DONE -> colors.accentAlt
    JobStatus.FAILED -> colors.danger
}

private val RAIL_WIDTH = 20.dp
private const val TICK_MILLIS = 1000L
