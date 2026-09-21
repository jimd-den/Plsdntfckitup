package com.stratum.core.domain.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BasePosePromptTest {

    private val request = BasePoseRequest(subject = "an Igbo warrior assassin")

    /**
     * The default is something a person can read.
     *
     * It used to be a private builder, which made the one prompt everything
     * else inherits from the one prompt nobody could look at: a reference that
     * came back wrong could only be regenerated and hoped over, because the
     * words that produced it were not anywhere a person could see. Every frame
     * of every animation is an edit of that one image, so whatever it gets
     * wrong is inherited forty times.
     */
    @Test
    fun `the default prompt is readable and says what it asks for`() {
        val prompt = BasePosePrompt.of(request)

        assertTrue(prompt.contains("T-pose"), prompt)
        assertTrue(prompt.contains("an Igbo warrior assassin"), prompt)
        assertTrue(prompt.contains("#00FF00"), "the chroma backdrop is not asked for")
    }

    /** What is sent is the default until somebody changes it. */
    @Test
    fun `a request with no override sends the default`() {
        assertEquals(BasePosePrompt.of(request), request.prompt)
    }

    @Test
    fun `an override is what gets sent`() {
        val edited = request.copy(promptOverride = "draw it however you like")

        assertEquals("draw it however you like", edited.prompt)
    }

    /**
     * Clearing the field restores the default rather than sending nothing.
     *
     * Which is what makes the editor safe to open: the way out of an edit is
     * to delete it, and deleting it cannot leave the model with an empty
     * prompt and the character with no reference.
     */
    @Test
    fun `clearing an override falls back to the default`() {
        listOf("", "   ").forEach { cleared ->
            assertEquals(
                BasePosePrompt.of(request),
                request.copy(promptOverride = cleared).prompt,
                "a prompt of '$cleared' did not fall back",
            )
        }
    }
}
