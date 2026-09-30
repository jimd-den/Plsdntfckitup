package com.stratum.legacy.data

import android.content.Context
import com.stratum.legacy.domain.AiGameMechanicGeneratorService
import com.stratum.legacy.domain.PnpRuleRepository
import com.stratum.legacy.domain.SpriteSheetRepository
import com.stratum.legacy.domain.SpriteTransparencyPoliceService
import com.stratum.legacy.domain.WeaponRepository

/**
 * Builds the data layer's implementations.
 *
 * Exists so callers can obtain repositories without naming Room, Retrofit or any
 * other storage detail. The database is an implementation choice of this module,
 * and nothing outside it should have to see the type to get a repository.
 */
class LegacyDataProvider(
    context: Context,
    private val geminiApiKey: String = "",
) {
    private val appContext = context.applicationContext

    private val database by lazy { AppDatabase.getInstance(appContext) }

    val weaponRepository: WeaponRepository by lazy { WeaponRepositoryImpl(database.weaponDao()) }

    val spriteSheetRepository: SpriteSheetRepository by lazy {
        SpriteSheetRepositoryImpl(database.spriteSheetDao())
    }

    val pnpRuleRepository: PnpRuleRepository by lazy {
        PnpRuleRepositoryImpl(database.pnpRulebookDao())
    }

    val aiService: AiGameMechanicGeneratorService by lazy { GeminiAiServiceImpl(geminiApiKey) }

    val spritePoliceService: SpriteTransparencyPoliceService by lazy {
        SpriteTransparencyPoliceServiceImpl()
    }
}
