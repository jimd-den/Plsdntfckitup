package com.stratum.legacy.domain

import kotlinx.coroutines.flow.Flow

/**
 * CHAPTER 06: DOMAIN REPOSITORY & SERVICE INTERFACES
 *
 * This literate module defines the pure Clean Architecture boundary contracts.
 * The domain layer depends strictly on these abstractions; data layer provides
 * concrete implementations (Room, Retrofit, SharedPreferences).
 */

interface WeaponRepository {
    fun observeAllWeapons(): Flow<List<WeaponItem>>
    suspend fun getAllWeapons(): List<WeaponItem>
    suspend fun getWeaponById(id: String): WeaponItem?
    suspend fun saveWeapon(weapon: WeaponItem)
    suspend fun deleteWeapon(id: String)
    suspend fun equipWeapon(id: String)
    suspend fun getEquippedWeapon(): WeaponItem?
}

interface SpriteSheetRepository {
    fun observeAllSpriteSheets(): Flow<List<SpriteSheetData>>
    suspend fun getAllSpriteSheets(): List<SpriteSheetData>
    suspend fun getSpriteSheetById(id: String): SpriteSheetData?
    suspend fun saveSpriteSheet(spriteSheet: SpriteSheetData)
    suspend fun getActiveSpriteSheetId(): String
    suspend fun setActiveSpriteSheetId(id: String)
}

interface PnpRuleRepository {
    fun observeAllRulebooks(): Flow<List<PnpRulebook>>
    suspend fun getAllRulebooks(): List<PnpRulebook>
    suspend fun getRulebookById(id: String): PnpRulebook?
    suspend fun saveRulebook(rulebook: PnpRulebook)
    suspend fun getActiveRulebook(): PnpRulebook
}

data class OpenRouterConfig(
    val apiKey: String = "",
    val model: String = "google/gemini-2.0-flash-exp:free",
    val baseUrl: String = "https://openrouter.ai/api/v1/",
    val isCustomEndpoint: Boolean = false
)

data class AiModelItem(
    val id: String,
    val name: String,
    val description: String = "",
    val isFree: Boolean = false,
    val isImageCapable: Boolean = false, // True ONLY if it outputs images!
    val isVisionInput: Boolean = false, // True if it takes images as input only
    val pricingDisplay: String = "Free",
    val outputModalities: List<String> = listOf("text")
)

data class SpritePoliceReport(
    val isTransparentPoliced: Boolean,
    val detectedGridCols: Int = 4,
    val detectedGridRows: Int = 4,
    val frameWidth: Int = 48,
    val frameHeight: Int = 48,
    val totalFrames: Int = 16,
    val message: String
)

interface SpriteTransparencyPoliceService {
    suspend fun policeSpriteSheet(
        sheet: SpriteSheetData,
        autoChromaKey: Boolean = true,
        tolerance: Float = 0.15f
    ): Pair<SpriteSheetData, SpritePoliceReport>
}

interface AiGameMechanicGeneratorService {
    fun setOpenRouterConfig(config: OpenRouterConfig)
    fun getOpenRouterConfig(): OpenRouterConfig
    suspend fun fetchAvailableModels(baseUrl: String, apiKey: String): Result<List<AiModelItem>>
    suspend fun generateWeaponFromPrompt(userPrompt: String): Result<WeaponItem>
    suspend fun inventMechanicFromPrompt(userPrompt: String): Result<CustomGameMechanic>
    suspend fun generatePnpRulebookFromPrompt(userPrompt: String): Result<PnpRulebook>
    suspend fun generateSpriteFromPrompt(userPrompt: String): Result<SpriteSheetData>
    suspend fun generateRawImageSpriteSheet(userPrompt: String, modelId: String? = null): Result<SpriteSheetData>
    suspend fun generateGameThemeFromPrompt(userPrompt: String): Result<GameThemeProfile>
}
