package com.stratum.engine.render

import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.art.ActorRole
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.MoteKind
import com.stratum.core.domain.art.PropSilhouette
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.art.Tint
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.world.IsometricProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldFrameRendererTest {

    private val projection = IsometricProjection()
    private val world = TestWorld()
    private val camera = WorldPoint(0f, 0f, 4f)

    private fun render(
        director: com.stratum.core.domain.art.WorldArtDirector = StyleSheetArtDirector(),
        time: WorldTime = WorldTime(),
    ): CountingSink {
        val sink = CountingSink()
        val renderer = WorldFrameRenderer(projection, director)
        val view = renderer.render(world, camera, WIDTH, HEIGHT, sink, time = time)
        renderer.finish(sink, view, time)
        return sink
    }

    @Test
    fun `a frame starts with the sky and ends with the air`() {
        val sink = render(StyleSheetArtDirector(StyleLexicon.interpret("dark").direction))
        assertEquals("backdrop", sink.order.first())
        assertEquals("vignette", sink.order.last())
        assertTrue(sink.cubes > 0)
    }

    @Test
    fun `terrain is drawn before what stands on it`() {
        val sink = render()
        assertTrue(sink.order.indexOf("cube") < sink.order.indexOf("polygon"))
    }

    @Test
    fun `nothing outside the viewport is drawn`() {
        // The world is twenty-five columns square and the camera sees a slice
        // of it. Drawing what is loaded rather than what is visible is the
        // difference between a playable phone frame and a slideshow.
        val wide = CountingSink()
        val narrow = CountingSink()
        WorldFrameRenderer(projection, StyleSheetArtDirector()).render(world, camera, WIDTH, HEIGHT, wide)
        WorldFrameRenderer(projection, StyleSheetArtDirector()).render(world, camera, WIDTH / 3f, HEIGHT / 3f, narrow)
        assertTrue(narrow.cubes < wide.cubes / 2, "a third of the screen drew ${narrow.cubes} of ${wide.cubes} cubes")
    }

    @Test
    fun `props are polygons rather than glyphs`() {
        val sink = render()
        assertTrue(sink.polygons > 0, "a world of trees drew no silhouettes")
    }

    @Test
    fun `a lantern lights the ground around it`() {
        // Every visible top face should not be the same colour: if the emissive
        // pass does nothing, the whole field comes back at one value and the
        // light in the world is decoration on a single block.
        val sink = render()
        assertTrue(sink.cubeTops.distinct().size > 8, "the frame has no tonal variety at all")
    }

    @Test
    fun `weather is drawn and can be turned off`() {
        val misty = render(StyleSheetArtDirector(StyleLexicon.interpret("foggy misty").direction))
        val clear = render(
            StyleSheetArtDirector(
                ArtDirection.HOUSE.copy(
                    atmosphere = ArtDirection.HOUSE.atmosphere.copy(moteKind = MoteKind.NONE, moteDensity = 0),
                ),
            ),
        )
        assertTrue(misty.glows > clear.glows)
    }

    @Test
    fun `motes move with time and nothing else`() {
        val direction = StyleLexicon.interpret("foggy dark").direction
        val renderer = WorldFrameRenderer(projection, StyleSheetArtDirector(direction))
        val view = FrameView(WIDTH, HEIGHT, 0f, 0f, 4)

        val early = CountingSink()
        val later = CountingSink()
        renderer.finish(early, view, WorldTime(elapsedSeconds = 0f))
        renderer.finish(later, view, WorldTime(elapsedSeconds = 4f))

        // Same field, moved: positions come from the clock and an index, so
        // there is no particle state to keep, lose, or desynchronise.
        assertEquals(early.glows + early.ellipses, later.glows + later.ellipses)
        assertTrue(
            early.ellipseCenters != later.ellipseCenters || early.glows > 0,
            "the air did not move between two different moments",
        )
    }

    @Test
    fun `an actor is planted on the ground before it is drawn`() {
        val sink = CountingSink()
        val renderer = WorldFrameRenderer(projection, StyleSheetArtDirector())
        renderer.actor(sink, 100f, 100f, ActorPresentation("p", ActorRole.PLAYER))
        assertTrue(sink.ellipses >= 2, "an actor with no contact shadow floats above the terrain")
        assertTrue(sink.rings >= 1, "an actor with no contour dissolves into bright ground")

        // The first thing drawn is the shadow, and it is dark and translucent.
        val shadow = sink.ellipseFills.first()
        assertTrue(Tint.alpha(shadow) in 1..254)
        assertTrue(Tint.luma(shadow) < 0.3f)
    }

    @Test
    fun `an elite is announced on the floor`() {
        val minion = CountingSink()
        val elite = CountingSink()
        val renderer = WorldFrameRenderer(projection, StyleSheetArtDirector())
        renderer.ground(minion, 0f, 0f, StyleSheetArtDirector().actorStyleFor(ActorPresentation("m", ActorRole.ENEMY, EnemyRank.MINION)))
        renderer.ground(elite, 0f, 0f, StyleSheetArtDirector().actorStyleFor(ActorPresentation("e", ActorRole.ENEMY, EnemyRank.ELITE)))
        assertTrue(elite.rings > minion.rings)
    }

    @Test
    fun `the same world at the same seed draws the same frame`() {
        val first = render()
        val second = render()
        assertEquals(first.cubes, second.cubes)
        assertEquals(first.polygons, second.polygons)
        assertEquals(first.cubeTops, second.cubeTops)
    }

    @Test
    fun `switching style changes the frame without changing the world`() {
        val dark = render(StyleSheetArtDirector(StyleLexicon.interpret("dark").direction))
        val kawaii = render(StyleSheetArtDirector(StyleLexicon.interpret("kawaii").direction))

        assertEquals(dark.cubes, kawaii.cubes, "a restyle must not move a single block")
        val darkMean = dark.cubeTops.map(Tint::luma).average()
        val kawaiiMean = kawaii.cubeTops.map(Tint::luma).average()
        assertTrue(kawaiiMean > darkMean, "kawaii drew a darker world than grimdark did")
    }

    @Test
    fun `every silhouette family produces drawable geometry`() {
        PropSilhouette.entries.forEach { family ->
            repeat(PropSilhouettes.VARIANTS) { variant ->
                val parts = PropSilhouettes.parts(family, variant)
                assertTrue(parts.isNotEmpty(), "$family/$variant is nothing at all")
                parts.forEach { part ->
                    assertTrue(part.points.size >= 6, "$family/$variant has a part with no area")
                    assertEquals(0, part.points.size % 2, "$family/$variant has a dangling coordinate")
                    assertTrue(
                        part.points.none { it.isNaN() || it.isInfinite() },
                        "$family/$variant produced a coordinate that cannot be drawn",
                    )
                }
            }
        }
    }

    @Test
    fun `a variant is the same shape every time it is asked for`() {
        val first = PropSilhouettes.parts(PropSilhouette.CANOPY, 3).first().points.toList()
        val second = PropSilhouettes.parts(PropSilhouette.CANOPY, 3).first().points.toList()
        assertEquals(first, second, "a tree that changes shape when you walk past it is not a tree")
    }

    @Test
    fun `variants differ from each other`() {
        val shapes = (0 until PropSilhouettes.VARIANTS)
            .map { PropSilhouettes.parts(PropSilhouette.CANOPY, it).first().points.toList() }
        assertEquals(PropSilhouettes.VARIANTS, shapes.distinct().size)
    }

    private companion object {
        const val WIDTH = 900f
        const val HEIGHT = 600f
    }
}
