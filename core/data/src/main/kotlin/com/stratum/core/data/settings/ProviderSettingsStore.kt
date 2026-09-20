package com.stratum.core.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.stratum.core.data.ai.ProviderConfig

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
        baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL },
    )

    fun save(config: ProviderConfig) {
        prefs.edit {
            putString(KEY_API, config.apiKey)
            putString(KEY_MODEL, config.model)
            putString(KEY_IMAGE_MODEL, config.imageModel)
            putString(KEY_BASE_URL, config.baseUrl)
        }
    }

    val isConfigured: Boolean get() = load().apiKey.isNotBlank()

    private companion object {
        const val FILE = "stratum_provider_settings"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_IMAGE_MODEL = "image_model"
        const val KEY_BASE_URL = "base_url"
        const val DEFAULT_MODEL = "google/gemini-2.0-flash-exp:free"
        const val DEFAULT_IMAGE_MODEL = "meta/muse-image"
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1/"
    }
}
