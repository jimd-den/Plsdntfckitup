package com.stratum.core.data.settings

import androidx.test.core.app.ApplicationProvider
import com.stratum.core.data.ai.model3d.ModelProvider
import com.stratum.core.data.ai.model3d.ModelProviderConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ModelProviderSettingsTest {

    private val store = ProviderSettingsStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `the 3D provider starts on its defaults and needs a key`() {
        val fresh = store.loadModelProvider()
        assertEquals(ModelProvider.MESHY, fresh.provider)
        assertEquals(ModelProvider.MESHY.defaultBaseUrl, fresh.baseUrl)
        assertFalse(store.isModelProviderConfigured)
    }

    @Test
    fun `each provider keeps its own key, and the last saved is the one in use`() {
        store.saveModelProvider(ModelProviderConfig(ModelProvider.MESHY, apiKey = "msy"))
        store.saveModelProvider(ModelProviderConfig(ModelProvider.FAL, apiKey = "fal", modelId = "fal-ai/hunyuan3d/v2"))

        assertEquals(ModelProvider.FAL, store.loadModelProvider().provider)
        assertEquals("fal-ai/hunyuan3d/v2", store.loadModelProvider().modelId)
        assertEquals("msy", store.loadModelProvider(ModelProvider.MESHY).apiKey)
        assertTrue(store.isModelProviderConfigured)
        // The text provider is untouched.
        assertEquals("", store.load().apiKey)
    }
}
