package com.stratum.feature.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.passive.PassiveKind
import com.stratum.core.domain.passive.PassiveNode
import com.stratum.core.domain.passive.PassiveTree
import kotlin.math.hypot

/**
 * Where the tree is on screen: a pan and a zoom. Pure, so the arithmetic a
 * thumb depends on is testable without a device.
 */
internal data class TreeViewport(val centreX: Float = 0f, val centreY: Float = 0f, val scale: Float = DEFAULT_SCALE) {

    fun toScreen(x: Float, y: Float, width: Float, height: Float): Offset =
        Offset(width / 2f + (x - centreX) * scale, height / 2f + (y - centreY) * scale)

    fun panned(dx: Float, dy: Float): TreeViewport = copy(centreX = centreX - dx / scale, centreY = centreY - dy / scale)

    fun zoomed(by: Float): TreeViewport = copy(scale = (scale * by).coerceIn(MIN_SCALE, MAX_SCALE))

    /**
     * The node under a tap, or null. The reach is in screen pixels, not tree
     * units, so a fingertip finds a small node however far out the tree is
     * zoomed -- the smallest node is smaller than a finger at every zoom.
     */
    fun nodeAt(tree: PassiveTree, tap: Offset, width: Float, height: Float, reach: Float = TAP_REACH): PassiveNode? =
        tree.nodes
            .map { it to toScreen(it.x, it.y, width, height) }
            .map { (node, at) -> node to hypot(at.x - tap.x, at.y - tap.y) }
            .filter { (_, distance) -> distance <= reach }
            .minByOrNull { (_, distance) -> distance }
            ?.first

    companion object {
        const val DEFAULT_SCALE = 0.45f
        const val MIN_SCALE = 0.08f
        const val MAX_SCALE = 2.5f
        const val TAP_REACH = 44f

        fun centredOn(node: PassiveNode?): TreeViewport = TreeViewport(node?.x ?: 0f, node?.y ?: 0f)
    }
}

/**
 * The passive tree, drawn and touchable: drag to pan, pinch to zoom, tap to
 * select. Taken nodes are lit, the nodes that can be taken next are outlined,
 * and the path to the selected node glows, so the cost of a far notable is
 * visible before a point is spent.
 */
@Composable
fun PassiveTreeView(
    tree: PassiveTree,
    startId: String?,
    allocated: Set<String>,
    selected: String?,
    path: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    var viewport by remember(tree.id, startId) { mutableStateOf(TreeViewport.centredOn(startId?.let(tree::node))) }
    val held = remember(allocated, startId) { allocated + listOfNotNull(startId) }
    val onPath = remember(path) { path.toSet() }
    // The tap handler outlives recompositions; it reads the latest pan and zoom through this.
    val current by rememberUpdatedState(viewport)

    Canvas(
        modifier = modifier
            .pointerInput(tree.id) {
                detectTransformGestures { _, pan, zoom, _ -> viewport = viewport.zoomed(zoom).panned(pan.x, pan.y) }
            }
            .pointerInput(tree.id) {
                detectTapGestures { tap ->
                    current.nodeAt(tree, tap, size.width.toFloat(), size.height.toFloat())?.let { onSelect(it.id) }
                }
            },
    ) {
        val palette = TreePalette(
            taken = colors.accent,
            path = colors.accentAlt,
            open = colors.ink,
            idle = colors.inkMuted.copy(alpha = 0.45f),
            selected = colors.danger,
        )
        drawLinks(tree, viewport, held, onPath, palette)
        tree.nodes.forEach { node ->
            val at = viewport.toScreen(node.x, node.y, size.width, size.height)
            if (offscreen(at)) return@forEach
            val open = node.id !in held && tree.neighboursOf(node.id).any { it in held }
            drawNode(node, at, viewport.scale, palette.colourFor(node.id, held, onPath, open), node.id == selected, palette)
        }
    }
}

private data class TreePalette(val taken: Color, val path: Color, val open: Color, val idle: Color, val selected: Color) {
    fun colourFor(id: String, held: Set<String>, onPath: Set<String>, isOpen: Boolean): Color = when {
        id in held -> taken
        id in onPath -> path
        isOpen -> open
        else -> idle
    }
}

private fun DrawScope.drawLinks(tree: PassiveTree, viewport: TreeViewport, held: Set<String>, path: Set<String>, palette: TreePalette) {
    tree.links.forEach { link ->
        val a = tree.node(link.from) ?: return@forEach
        val b = tree.node(link.to) ?: return@forEach
        val from = viewport.toScreen(a.x, a.y, size.width, size.height)
        val to = viewport.toScreen(b.x, b.y, size.width, size.height)
        if (offscreen(from) && offscreen(to)) return@forEach
        val bothHeld = link.from in held && link.to in held
        val lit = (link.from in path || link.from in held) && (link.to in path || link.to in held)
        val colour = when {
            bothHeld -> palette.taken
            lit -> palette.path
            else -> palette.idle.copy(alpha = 0.25f)
        }
        drawLine(colour, from, to, strokeWidth = if (bothHeld || lit) 4f else 2f)
    }
}

private fun DrawScope.drawNode(node: PassiveNode, at: Offset, scale: Float, colour: Color, isSelected: Boolean, palette: TreePalette) {
    // Sized in tree units, with a floor so the smallest node stays a target.
    val radius = (sizeOf(node.kind) * scale).coerceAtLeast(MIN_RADIUS)
    drawCircle(colour, radius, at)
    if (node.kind == PassiveKind.KEYSTONE || node.kind == PassiveKind.NOTABLE) {
        drawCircle(colour.copy(alpha = 1f), radius + 3f, at, style = Stroke(width = 2f))
    }
    if (isSelected) drawCircle(palette.selected, radius + 7f, at, style = Stroke(width = 3f))
}

private fun DrawScope.offscreen(at: Offset): Boolean =
    at.x < -CULL_MARGIN || at.y < -CULL_MARGIN || at.x > size.width + CULL_MARGIN || at.y > size.height + CULL_MARGIN

private fun sizeOf(kind: PassiveKind): Float = when (kind) {
    PassiveKind.SMALL -> 12f
    PassiveKind.NOTABLE -> 22f
    PassiveKind.KEYSTONE -> 30f
    PassiveKind.START -> 26f
}

private const val MIN_RADIUS = 3f
private const val CULL_MARGIN = 40f
