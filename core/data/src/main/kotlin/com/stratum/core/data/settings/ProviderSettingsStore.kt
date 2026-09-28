package com.stratum.core.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.stratum.core.data.ai.ProviderConfig
import com.stratum.core.data.ai.model3d.ModelProvider
import com.stratum.core.data.ai.model3d.ModelProviderConfig

/**
 * Stores the player's model provider settings.
 *
 * The API key is the player's own credential for their own account, so it stays
 * on the device and is never sent anywhere except the provider they configured.
 */
class ProviderSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): ProviderConfig = ProviderConfig(
        apiKey = prefs.getString(KEY_API, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL },
        // Stored separately from [model]. It was not stored at all before, which
        // meant every sprite sheet was drawn by whichever model happened to be
        // the default no matter what the player had chosen.
        imageModel = prefs.getString(KEY_IMAGE_MODEL, DEFAULT_IMAGE_MODEL)
            .orEmpty().ifBlank { DEFAULT_IMAGE_MODEL },
        videoModel = prefs.getString(KEY_VIDEO_MODEL, DEFAULT_VIDEO_MODEL)
            .orEmpty().ifBlank { DEFAULT_VIDEO_MODEL },
        baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL },
    )

    fun save(config: ProviderConfig) {
        prefs.edit {
            putString(KEY_API, config.apiKey)
            putString(KEY_MODEL, config.model)
            putString(KEY_IMAGE_MODEL, config.imageModel)
            putString(KEY_VIDEO_MODEL, config.videoModel)
            putString(KEY_BASE_URL, config.baseUrl)
        }
    }

    val isConfigured: Boolean get() = load().apiKey.isNotBlank()

    /**
     * The 3D model provider, which is a different account from the text and
     * image one: none of the mesh services is behind the same endpoint.
     *
     * Key, endpoint and model are kept per provider, so trying another
     * provider and switching back does not lose the first one's key.
     */
    fun loadModelProvider(provider: ModelProvider? = null): ModelProviderConfig {
        val chosen = provider ?: ModelProvider.parse(prefs.getString(KEY_MODEL3D_PROVIDER, null))
        val prefix = KEY_MODEL3D_PREFIX + chosen.name.lowercase()
        return ModelProviderConfig(
            provider = chosen,
            apiKey = prefs.getString("$prefix.key", "").orEmpty(),
            baseUrl = prefs.getString("$prefix.base_url", null).orEmpty().ifBlank { chosen.defaultBaseUrl },
            modelId = prefs.getString("$prefix.model", null).orEmpty().ifBlank { chosen.defaultModel },
        )
    }

    /** Saves [config]'s fields for its provider and makes it the one in use. */
    fun saveModelProvider(config: ModelProviderConfig) {
        val prefix = KEY_MODEL3D_PREFIX + config.provider.name.lowercase()
        prefs.edit {
            putString(KEY_MODEL3D_PROVIDER, config.provider.name)
            putString("$prefix.key", config.apiKey)
            putString("$prefix.base_url", config.baseUrl)
            putString("$prefix.model", config.modelId)
        }
    }

    val isModelProviderConfigured: Boolean get() = loadModelProvider().apiKey.isNotBlank()

    private companion object {
        const val FILE = "stratum_provider_settings"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_IMAGE_MODEL = "image_model"
        const val KEY_VIDEO_MODEL = "video_model"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL3D_PROVIDER = "model3d_provider"
        const val KEY_MODEL3D_PREFIX = "model3d."
        const val DEFAULT_MODEL = "google/gemini-2.0-flash-exp:free"
        const val DEFAULT_IMAGE_MODEL = "meta/muse-image"

        /**
         * The cheapest video model that takes both ends of a movement.
         *
         * Measured rather than chosen from a listing, because it is not in one:
         * $0.0000012 a video token without audio, three times cheaper than the
         * next, and it accepts a first *and* a last frame, which is what lets a
         * clip be pinned to two drawings the pipeline already knows how to make.
         */
        const val DEFAULT_VIDEO_MODEL = "bytedance/seedance-1-5-pro"
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1/"
    }
}
