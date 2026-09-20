package com.stratum.core.data.settings

import androidx.test.core.app.ApplicationProvider
import com.stratum.core.data.ai.ProviderConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ProviderSettingsStoreTest {

    private val store = ProviderSettingsStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `the image model survives a save, separately from the text model`() {
        store.save(
            ProviderConfig(
                apiKey = "sk-test",
                model = "anthropic/claude-sonnet-4",
                imageModel = "black-forest-labs/flux-1.1-pro",
                baseUrl = "https://example.test/v1/",
            ),
        )

        val loaded = store.load()

        assertEquals("anthropic/claude-sonnet-4", loaded.model)
        assertEquals("black-forest-labs/flux-1.1-pro", loaded.imageModel)
        assertEquals("sk-test", loaded.apiKey)
        assertEquals("https://example.test/v1/", loaded.baseUrl)
    }

    @Test
    fun `changing the text model leaves the image model alone`() {
        store.save(ProviderConfig(apiKey = "k", imageModel = "my/painter"))
        val first = store.load()

        store.save(first.copy(model = "some/writer"))
        val second = store.load()

        assertEquals("some/writer", second.model)
        assertEquals("my/painter", second.imageModel, "the image model was reset by a text model change")
    }

    @Test
    fun `a blank stored value falls back to the default rather than to nothing`() {
        store.save(ProviderConfig(apiKey = "k", model = "", imageModel = "", baseUrl = ""))

        val loaded = store.load()

        assertTrue(loaded.model.isNotBlank(), "an empty text model was stored as empty")
        assertTrue(loaded.imageModel.isNotBlank(), "an empty image model was stored as empty")
        assertTrue(loaded.baseUrl.isNotBlank(), "an empty endpoint was stored as empty")
    }

    @Test
    fun `configured means a key is present`() {
        store.save(ProviderConfig(apiKey = ""))
        assertFalse(store.isConfigured)

        store.save(ProviderConfig(apiKey = "sk-test"))
        assertTrue(store.isConfigured, "a saved key was not seen by the very next read")
    }
}
