package com.stratum.feature.studio.di

import android.content.Context
import com.stratum.legacy.data.LegacyDataProvider
import com.stratum.legacy.domain.AiGameMechanicGeneratorService
import com.stratum.legacy.domain.ArpgGameLoopEngine
import com.stratum.legacy.domain.ExecutePnpCheckUseCase
import com.stratum.legacy.domain.ExportSpriteSheetToJsonUseCase
import com.stratum.legacy.domain.FetchAiModelsUseCase
import com.stratum.legacy.domain.GenerateRawImageSpriteSheetUseCase
import com.stratum.legacy.domain.GenerateSpriteWithAiUseCase
import com.stratum.legacy.domain.ImportSpriteSheetFromJsonUseCase
import com.stratum.legacy.domain.ImportSpriteSheetUseCase
import com.stratum.legacy.domain.InventGameMechanicUseCase
import com.stratum.legacy.domain.PnpRuleRepository
import com.stratum.legacy.domain.PoliceSpriteSheetUseCase
import com.stratum.legacy.domain.RollRandomWeaponUseCase
import com.stratum.legacy.domain.SimulateCombatDpsUseCase
import com.stratum.legacy.domain.SpriteSheetRepository
import com.stratum.legacy.domain.SpriteTransparencyPoliceService
import com.stratum.legacy.domain.ToggleGamePauseUseCase
import com.stratum.legacy.domain.WeaponRepository

/**
 * CLEAN ARCHITECTURE DEPENDENCY CONTAINER
 *
 * Inverts dependencies so that the Presentation Layer (ViewModel) does NOT
 * directly instantiate Data Layer implementations (Room, DAOs, HTTP services).
 *
 * This enables modularity, testability, and clean separation between Domain, Data,
 * and Presentation layers.
 */
interface AppContainer {
    val weaponRepository: WeaponRepository
    val spriteSheetRepository: SpriteSheetRepository
    val pnpRuleRepository: PnpRuleRepository
    val aiService: AiGameMechanicGeneratorService
    val spritePoliceService: SpriteTransparencyPoliceService

    // Domain Use Cases
    val rollRandomWeaponUseCase: RollRandomWeaponUseCase
    val inventGameMechanicUseCase: InventGameMechanicUseCase
    val importSpriteSheetUseCase: ImportSpriteSheetUseCase
    val executePnpCheckUseCase: ExecutePnpCheckUseCase
    val simulateCombatDpsUseCase: SimulateCombatDpsUseCase
    val generateSpriteWithAiUseCase: GenerateSpriteWithAiUseCase
    val exportSpriteSheetToJsonUseCase: ExportSpriteSheetToJsonUseCase
    val importSpriteSheetFromJsonUseCase: ImportSpriteSheetFromJsonUseCase
    val fetchAiModelsUseCase: FetchAiModelsUseCase
    val policeSpriteSheetUseCase: PoliceSpriteSheetUseCase
    val generateRawImageSpriteSheetUseCase: GenerateRawImageSpriteSheetUseCase
    fun createToggleGamePauseUseCase(engine: ArpgGameLoopEngine): ToggleGamePauseUseCase
}

/**
 * Default production container implementation backed by SQLite Room Database
 * and real network / domain services.
 */
class DefaultAppContainer(
    private val context: Context,
    private val geminiApiKey: String = "",
) : AppContainer {

    private val data by lazy { LegacyDataProvider(context, geminiApiKey) }

    override val weaponRepository: WeaponRepository get() = data.weaponRepository

    override val spriteSheetRepository: SpriteSheetRepository get() = data.spriteSheetRepository

    override val pnpRuleRepository: PnpRuleRepository get() = data.pnpRuleRepository

    override val aiService: AiGameMechanicGeneratorService get() = data.aiService

    override val spritePoliceService: SpriteTransparencyPoliceService get() = data.spritePoliceService

    override val rollRandomWeaponUseCase: RollRandomWeaponUseCase by lazy {
        RollRandomWeaponUseCase(weaponRepository)
    }

    override val inventGameMechanicUseCase: InventGameMechanicUseCase by lazy {
        InventGameMechanicUseCase()
    }

    override val importSpriteSheetUseCase: ImportSpriteSheetUseCase by lazy {
        ImportSpriteSheetUseCase(spriteSheetRepository)
    }

    override val executePnpCheckUseCase: ExecutePnpCheckUseCase by lazy {
        ExecutePnpCheckUseCase(pnpRuleRepository)
    }

    override val simulateCombatDpsUseCase: SimulateCombatDpsUseCase by lazy {
        SimulateCombatDpsUseCase()
    }

    override val generateSpriteWithAiUseCase: GenerateSpriteWithAiUseCase by lazy {
        GenerateSpriteWithAiUseCase(aiService, spriteSheetRepository)
    }

    override val exportSpriteSheetToJsonUseCase: ExportSpriteSheetToJsonUseCase by lazy {
        ExportSpriteSheetToJsonUseCase()
    }

    override val importSpriteSheetFromJsonUseCase: ImportSpriteSheetFromJsonUseCase by lazy {
        ImportSpriteSheetFromJsonUseCase(spriteSheetRepository)
    }

    override val fetchAiModelsUseCase: FetchAiModelsUseCase by lazy {
        FetchAiModelsUseCase(aiService)
    }

    override val policeSpriteSheetUseCase: PoliceSpriteSheetUseCase by lazy {
        PoliceSpriteSheetUseCase(spritePoliceService, spriteSheetRepository)
    }

    override val generateRawImageSpriteSheetUseCase: GenerateRawImageSpriteSheetUseCase by lazy {
        GenerateRawImageSpriteSheetUseCase(aiService, spriteSheetRepository)
    }

    override fun createToggleGamePauseUseCase(engine: ArpgGameLoopEngine): ToggleGamePauseUseCase {
        return ToggleGamePauseUseCase(engine)
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun getInstance(context: Context, geminiApiKey: String = ""): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: DefaultAppContainer(context.applicationContext, geminiApiKey)
                    .also { instance = it }
            }
        }

        // For unit testing or swapping test containers
        fun setTestInstance(testContainer: AppContainer?) {
            instance = testContainer
        }
    }
}
