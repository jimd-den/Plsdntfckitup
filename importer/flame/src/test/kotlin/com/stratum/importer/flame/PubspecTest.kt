package com.stratum.importer.flame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PubspecTest {

    @Test
    fun `dependencies are the names directly under the dependency blocks`() {
        val pubspec = Pubspec.parse(FlameFixtures.pubspec)

        assertEquals(setOf("flutter", "flame", "flame_tiled"), pubspec.dependencies)
        assertTrue(pubspec.usesFlame)
    }

    @Test
    fun `a setting of a dependency is not itself a dependency`() {
        val pubspec = Pubspec.parse("name: x\ndependencies:\n  flutter:\n    sdk: flutter\n    flame: nope\n")

        assertEquals(setOf("flutter"), pubspec.dependencies)
        assertFalse(pubspec.usesFlame)
    }
}

class PubspecFrameworkTest {

    @Test
    fun `games built on a Flame framework or plugin count as Flame games`() {
        assertTrue(Pubspec.parse("name: x\ndependencies:\n  bonfire: ^3.0.0\n").usesFlame)
        assertTrue(Pubspec.parse("name: x\ndependencies:\n  flame_tiled: ^1.0.0\n").usesFlame)
    }
}
