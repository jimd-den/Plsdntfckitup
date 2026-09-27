package com.stratum.feature.play

import androidx.compose.ui.geometry.Offset
import com.stratum.core.domain.passive.PassiveKind
import com.stratum.core.domain.passive.PassiveLink
import com.stratum.core.domain.passive.PassiveNode
import com.stratum.core.domain.passive.PassiveTree
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TreeViewportTest {

    private val tree = PassiveTree(
        id = "t", name = "t",
        nodes = listOf(PassiveNode("start", "Start", PassiveKind.START, x = 0f, y = 0f), PassiveNode("far", "Far", x = 400f, y = 0f)),
        links = listOf(PassiveLink("start", "far")),
    )

    @Test
    fun `the centred node sits in the middle of the screen`() {
        val viewport = TreeViewport(centreX = 400f, centreY = 0f, scale = 0.5f)

        assertEquals(Offset(500f, 300f), viewport.toScreen(400f, 0f, width = 1000f, height = 600f))
        assertEquals(Offset(300f, 300f), viewport.toScreen(0f, 0f, width = 1000f, height = 600f))
    }

    @Test
    fun `dragging moves the tree with the finger, and zoom is clamped`() {
        val panned = TreeViewport(scale = 0.5f).panned(dx = 100f, dy = 0f)

        assertEquals(-200f, panned.centreX, "a 100px drag right shows what was 200 tree units to the left")
        assertEquals(TreeViewport.MAX_SCALE, TreeViewport().zoomed(100f).scale)
        assertEquals(TreeViewport.MIN_SCALE, TreeViewport().zoomed(0.0001f).scale)
    }

    @Test
    fun `a tap finds the node under the finger even when zoomed far out`() {
        val far = TreeViewport(scale = TreeViewport.MIN_SCALE)
        val farNode = far.toScreen(400f, 0f, 1000f, 600f)

        assertEquals("far", far.nodeAt(tree, farNode + Offset(10f, 10f), 1000f, 600f)?.id)
        assertNull(TreeViewport().nodeAt(tree, Offset(10f, 10f), 1000f, 600f))
    }
}
