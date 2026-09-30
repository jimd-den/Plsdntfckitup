package com.stratum.core.domain.art

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StyleLexiconTest {

    @Test
    fun `dark makes a darker world`() {
        val house = ArtDirection.HOUSE.enforcePlayable()
        val dark = StyleLexicon.interpret("make it dark and grim").direction

        assertTrue(dark.contrast.terrainValueCeiling < house.contrast.terrainValueCeiling)
        assertTrue(dark.contrast.terrainSaturation < house.contrast.terrainSaturation)
        assertTrue(dark.atmosphere.vignette > house.atmosphere.vignette)
    }

    @Test
    fun `kawaii makes a brighter softer world`() {
        val house = ArtDirection.HOUSE.enforcePlayable()
        val kawaii = StyleLexicon.interpret("kawaii").direction

        assertTrue(kawaii.contrast.terrainValueFloor > house.contrast.terrainValueFloor)
        assertTrue(kawaii.light.ambientStrength > house.light.ambientStrength)
        assertEquals(MoteKind.PETALS, kawaii.atmosphere.moteKind)
    }

    @Test
    fun `word order does not change the world`() {
        // A prompt is a description, not a program. Players do not order
        // adjectives, and two people describing the same world should get it.
        val one = StyleLexicon.interpret("dark kawaii woodblock", seed = 7).direction
        val other = StyleLexicon.interpret("woodblock dark kawaii", seed = 7).direction
        assertEquals(one.palette, other.palette)
        assertEquals(one.light, other.light)
        assertEquals(one.contrast, other.contrast)
        assertEquals(one.atmosphere, other.atmosphere)
    }

    @Test
    fun `the same words with a different seed are a different world`() {
        val first = StyleLexicon.interpret("dark", seed = 1).direction
        val second = StyleLexicon.interpret("dark", seed = 2).direction
        assertNotEquals(first.light.sunAzimuth, second.light.sunAzimuth)
        // ...but recognisably the same style, or a reroll would be a reskin.
        assertEquals(first.contrast, second.contrast)
    }

    @Test
    fun `the same words with the same seed are the same world`() {
        assertEquals(
            StyleLexicon.interpret("toxic volcanic", seed = 99).direction,
            StyleLexicon.interpret("toxic volcanic", seed = 99).direction,
        )
    }

    @Test
    fun `words it cannot serve are reported rather than swallowed`() {
        val reading = StyleLexicon.interpret("dark but in the manner of an obscure lithographer")
        assertTrue(reading.matched.any { it.id == "dark" })
        assertTrue("lithographer" in reading.unmatched)
        // Filler the player wrote to be polite is not a failure to understand.
        assertTrue("the" !in reading.unmatched)
        assertTrue("but" !in reading.unmatched)
    }

    @Test
    fun `an empty prompt leaves the house style alone`() {
        val reading = StyleLexicon.interpret("")
        assertTrue(reading.matched.isEmpty())
        assertEquals(ArtDirection.HOUSE.id, reading.direction.id)
    }

    @Test
    fun `every style in the lexicon is still playable`() {
        // The guard that matters. Traits are written independently and combine
        // freely, so the only way to know that none of them — or no pair of
        // them — hides the monsters is to check all of them.
        StyleLexicon.traits.forEach { trait ->
            StyleLexicon.traits.forEach { other ->
                val direction = StyleLexicon.interpret("${trait.words.first()} ${other.words.first()}").direction
                val contrast = direction.contrast
                assertTrue(
                    contrast.terrainValueCeiling <= ArtDirection.MAX_TERRAIN_CEILING + TOLERANCE,
                    "${trait.id}+${other.id} lets the ground reach ${contrast.terrainValueCeiling}",
                )
                assertTrue(
                    contrast.featureSaturation > contrast.terrainSaturation,
                    "${trait.id}+${other.id} makes the ground as loud as the loot",
                )
                assertTrue(
                    contrast.outlineWidth >= ArtDirection.MIN_OUTLINE,
                    "${trait.id}+${other.id} leaves actors without a contour",
                )
                assertTrue(
                    direction.light.ambientStrength >= ArtDirection.MIN_AMBIENT,
                    "${trait.id}+${other.id} turns the lights off entirely",
                )
            }
        }
    }

    @Test
    fun `a style carries prompt words for the image models`() {
        val direction = StyleLexicon.interpret("woodblock kawaii").direction
        assertTrue(direction.diction.flavour.isNotBlank())
        assertTrue(direction.diction.forbidden.isNotBlank())
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
