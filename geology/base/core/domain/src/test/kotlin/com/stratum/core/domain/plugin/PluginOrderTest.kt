package com.stratum.core.domain.plugin

import com.stratum.core.domain.content.ContentPack
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginOrderTest {

    private val order = PluginOrder.decode("+a\n-b\n+c")

    @Test
    fun `the order reads back what it wrote`() {
        assertEquals(order, PluginOrder.decode(order.encode()))
        assertEquals(listOf("a", "c"), order.enabledIds)
    }

    @Test
    fun `removed plugins drop out and new ones join at the end, switched on`() {
        val reconciled = order.reconciledWith(listOf("c", "a", "d"))
        assertEquals(listOf("a", "c", "d"), reconciled.ids)
        assertEquals(listOf("a", "c", "d"), reconciled.enabledIds)
    }

    @Test
    fun `moving is clamped to the ends and switching keeps the place`() {
        assertEquals(listOf("b", "c", "a"), order.moved("a", 5).ids)
        assertEquals(listOf("c", "a", "b"), order.moved("c", -9).ids)
        assertEquals(listOf("a", "b", "c"), order.withEnabled("b", true).ids)
        assertEquals(listOf("a", "b", "c"), order.withEnabled("b", true).enabledIds)
    }

    @Test
    fun `junk lines in the file are skipped rather than fatal`() {
        assertEquals(listOf("x"), PluginOrder.decode("\n  +x \nnonsense\n+\n-x\n").ids)
    }

    @Test
    fun `the library assembles only what resolved, in load order`() {
        fun installed(id: String, vararg needs: String) = InstalledPlugin(
            PluginManifest(id, id, Version(1, 0, 0), dependencies = needs.map { PluginDependency(it) }),
            ContentPack(id = id, name = id, author = ""),
            enabled = true,
        )
        val plugins = listOf(installed("campaign", "monsters"), installed("monsters"), installed("broken", "nothing"))
        val library = PluginLibrary(plugins, PluginResolver.resolve(plugins.map { it.manifest }, plugins.map { it.manifest.id }))

        assertEquals(listOf("monsters", "campaign"), library.activePacks.map { it.id })
        assertEquals("broken", library.problemFor("broken")?.pluginId)
    }
}
