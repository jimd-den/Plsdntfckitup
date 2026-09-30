package com.stratum.core.domain.creation

import com.stratum.core.domain.content.ContentPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstantWorldTest {

    @Test
    fun `a prompt is a playable world at once, its look read by the lexicon`() {
        val world = InstantWorld.from("  dark kawaii woodblock river delta ")
        assertEquals("dark kawaii woodblock river delta", world.stylePrompt)
        assertEquals("Dark kawaii woodblock", world.name)
        assertTrue(world.recognised.isNotEmpty(), "the lexicon recognised some of it")
        assertTrue(world.styleSummary.isNotBlank())
        assertEquals("Delta Kings", InstantWorld.from("delta", name = " Delta Kings ").name)
        assertEquals("New world", InstantWorld.from("").name)
    }

    @Test
    fun `a finished pack joins at once before play, and waits while a world is running`() {
        val installed = mutableListOf<String>()
        val inbox = PackInbox()
        val pack = { id: String -> ContentPack(id = id, name = id, author = "") }

        assertEquals(PackDelivery.INSTALLED, inbox.arrive(pack("a"), sessionRunning = false) { installed += it.id })
        assertEquals(listOf("a"), installed)

        assertEquals(PackDelivery.HELD, inbox.arrive(pack("b"), sessionRunning = true) { installed += it.id })
        assertEquals(PackDelivery.HELD, inbox.arrive(pack("b"), sessionRunning = true) { installed += it.id })
        assertEquals(listOf("a"), installed, "nothing is swapped in under a running world")
        assertEquals(1, inbox.held.value.size, "a newer draft replaces the one waiting")

        inbox.release { installed += it.id }
        assertEquals(listOf("a", "b"), installed)
        assertTrue(inbox.held.value.isEmpty())
    }
}
