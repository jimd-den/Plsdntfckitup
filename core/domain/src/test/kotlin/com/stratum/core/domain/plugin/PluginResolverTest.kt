package com.stratum.core.domain.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginResolverTest {

    private fun plugin(id: String, version: String = "1.0.0", api: Int = StratumApi.LEVEL, vararg needs: PluginDependency) =
        PluginManifest(id, id, Version.parse(version)!!, apiLevel = api, dependencies = needs.toList())

    private fun needs(id: String, range: String = "*", optional: Boolean = false) = PluginDependency(id, VersionRange.parse(range)!!, optional)

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(Version.parse("1.10.0")!! > Version.parse("1.9.3")!!)
        assertEquals(Version(2, 0, 0), Version.parse("2"))
        assertEquals("-beta", Version.parse("1.2.3-beta")!!.label)
        assertNull(Version.parse("one"))
    }

    @Test
    fun `ranges accept what package managers mean by them`() {
        val v = { text: String -> Version.parse(text)!! }
        assertTrue(v("1.4.0") in VersionRange.parse("^1.2")!!)
        assertTrue(v("2.0.0") !in VersionRange.parse("^1.2")!!)
        assertTrue(v("1.2.9") in VersionRange.parse("~1.2")!!)
        assertTrue(v("1.3.0") !in VersionRange.parse("~1.2")!!)
        assertTrue(v("3.0.0") in VersionRange.parse(">=1.2")!!)
        assertTrue(v("1.2.0") in VersionRange.parse("1.2.0")!!)
        assertTrue(v("9.9.9") in VersionRange.parse("*")!!)
        assertNull(VersionRange.parse("<>1"))
    }

    @Test
    fun `the player's order stands, except that a dependency loads before what needs it`() {
        val base = plugin("monsters")
        val campaign = plugin("campaign", needs = arrayOf(needs("monsters", "^1.0")))
        val skin = plugin("skin")

        val resolution = PluginResolver.resolve(listOf(base, campaign, skin), listOf("skin", "campaign", "monsters"))

        assertEquals(listOf("skin", "monsters", "campaign"), resolution.loadOrder)
        assertTrue(resolution.problems.isEmpty())
    }

    @Test
    fun `a missing or wrong-version dependency leaves the plugin out, and says why`() {
        val campaign = plugin("campaign", needs = arrayOf(needs("monsters", "^2.0")))
        val oldMonsters = plugin("monsters", version = "1.4.0")

        val missing = PluginResolver.resolve(listOf(campaign), listOf("campaign"))
        assertIs<PluginProblem.MissingDependency>(missing.problems.single())
        assertTrue(missing.loadOrder.isEmpty())

        val wrong = PluginResolver.resolve(listOf(campaign, oldMonsters), listOf("monsters", "campaign"))
        assertEquals(listOf("monsters"), wrong.loadOrder)
        assertTrue("1.4.0" in assertIs<PluginProblem.WrongVersion>(wrong.problems.single()).message)
    }

    @Test
    fun `a plugin needing a disabled plugin is left out rather than loading half`() {
        val campaign = plugin("campaign", needs = arrayOf(needs("monsters")))
        val resolution = PluginResolver.resolve(listOf(campaign, plugin("monsters")), listOf("campaign"))

        assertTrue(resolution.loadOrder.isEmpty())
        assertIs<PluginProblem.MissingDependency>(resolution.problems.single())
    }

    @Test
    fun `whatever builds on a refused plugin is refused too, and everything else loads`() {
        val future = plugin("future", api = StratumApi.LEVEL + 1)
        val addon = plugin("addon", needs = arrayOf(needs("future")))
        val fine = plugin("fine")

        val resolution = PluginResolver.resolve(listOf(future, addon, fine), listOf("future", "addon", "fine"))

        assertEquals(listOf("fine"), resolution.loadOrder)
        assertNotNull(resolution.problems.singleOrNull { it is PluginProblem.NewerApi })
        assertNotNull(resolution.problems.singleOrNull { it.pluginId == "addon" })
    }

    @Test
    fun `an optional dependency is ordered first when present and ignored when absent`() {
        val extra = plugin("extra", needs = arrayOf(needs("maps", optional = true)))

        assertEquals(listOf("extra"), PluginResolver.resolve(listOf(extra), listOf("extra")).loadOrder)
        assertEquals(listOf("maps", "extra"), PluginResolver.resolve(listOf(extra, plugin("maps")), listOf("extra", "maps")).loadOrder)
    }

    @Test
    fun `plugins that depend on each other in a circle are refused with the circle named`() {
        val a = plugin("a", needs = arrayOf(needs("b")))
        val b = plugin("b", needs = arrayOf(needs("a")))

        val resolution = PluginResolver.resolve(listOf(a, b), listOf("a", "b"))

        assertTrue(resolution.loadOrder.isEmpty())
        assertTrue(resolution.problems.any { it is PluginProblem.Cycle })
        assertEquals(setOf("a", "b"), resolution.problems.map { it.pluginId }.toSet())
    }
}
