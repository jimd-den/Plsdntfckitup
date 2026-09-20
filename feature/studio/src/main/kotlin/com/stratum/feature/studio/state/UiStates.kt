package com.stratum.feature.studio.state

import com.stratum.legacy.domain.ActiveLootDrop
import com.stratum.legacy.domain.AiModelItem
import com.stratum.legacy.domain.ArpgWorldState
import com.stratum.legacy.domain.CustomStyleOptions
import com.stratum.legacy.domain.DiabloEra
import com.stratum.legacy.domain.DiceRollResult
import com.stratum.legacy.domain.DpsSimulationReport
import com.stratum.legacy.domain.DungeonMapData
import com.stratum.legacy.domain.GameMode
import com.stratum.legacy.domain.GameThemeProfile
import com.stratum.legacy.domain.HeroClass
import com.stratum.legacy.domain.MapBiome
import com.stratum.legacy.domain.NamingSystem
import com.stratum.legacy.domain.PnpRulebook
import com.stratum.legacy.domain.PowerAttackSkill
import com.stratum.legacy.domain.SpritePoliceReport
import com.stratum.legacy.domain.SpriteSheetData
import com.stratum.legacy.domain.VisualLogicScript
import com.stratum.legacy.domain.WeaponItem
import com.stratum.feature.studio.CombatHudStats
import com.stratum.feature.studio.CreatorSubTab
import com.stratum.feature.studio.PlayerHeaderStats

/**
 * PURE CLEAN ARCHITECTURE UI STATES
 *
 * Immutable data representations for each UI screen and component.
 * Decoupled entirely from ViewModel, Room, and Android Framework lifecycles.
 */

data class CombatArenaUiState(
    val hudStats: CombatHudStats = CombatHudStats(),
    val renderTick: Long = 0L,
    val activeSprite: SpriteSheetData,
    val isIsometricView: Boolean = false,
    val spriteScale: Float = 1.0f,
    val currentMap: DungeonMapData,
    val currentGameMode: GameMode = GameMode.SURVIVAL_ARENA,
    val isCombatPaused: Boolean = false,
    val diabloEra: DiabloEra = DiabloEra.DIABLO_2,
    val namingSystem: NamingSystem = NamingSystem.MYTHIC_ANCIENT,
    val activeTheme: GameThemeProfile,
    val customStyle: CustomStyleOptions = CustomStyleOptions(),
    val potionBeltCounts: List<Int> = listOf(4, 4, 1, 2),
    val engineState: ArpgWorldState,
    val selectedLootDrop: ActiveLootDrop? = null,
    val heroBitmap: android.graphics.Bitmap? = null,
    val monsterBitmaps: Map<String, android.graphics.Bitmap> = emptyMap()
)

data class PauseOverlayUiState(
    val hudStats: CombatHudStats,
    val activeSprite: SpriteSheetData,
    val diabloEra: DiabloEra,
    val namingSystem: NamingSystem,
    val activeTheme: GameThemeProfile,
    val equippedWeapon: WeaponItem? = null
)

data class MainMenuUiState(
    val currentEra: DiabloEra = DiabloEra.DIABLO_2,
    val namingSystem: NamingSystem = NamingSystem.MYTHIC_ANCIENT,
    val activeTheme: GameThemeProfile,
    val playerStats: PlayerHeaderStats = PlayerHeaderStats(),
    val activeSpriteName: String = "Warrior",
    val equippedWeaponName: String = "Bronze Blade",
    val equippedWeaponRarityColorArgb: Long = 0xFF9E9E9E
)

data class WeaponForgeUiState(
    val worldState: ArpgWorldState,
    val inventory: List<WeaponItem> = emptyList(),
    val dpsReport: DpsSimulationReport? = null
)

data class CreatorStudioUiState(
    val currentSubTab: CreatorSubTab = CreatorSubTab.SPRITES
)

data class SpriteStudioUiState(
    val spriteSheets: List<SpriteSheetData> = emptyList(),
    val activeSprite: SpriteSheetData,
    val isAiGenerating: Boolean = false,
    val statusMessage: String = "",
    val selectedModel: String = "",
    val availableAiModels: List<AiModelItem> = emptyList()
)

data class ClassesStudioUiState(
    val heroClasses: List<HeroClass> = emptyList(),
    val activeClass: HeroClass,
    val availableSkills: List<PowerAttackSkill> = emptyList(),
    val activeScript: VisualLogicScript = VisualLogicScript()
)

data class MapStudioUiState(
    val currentMap: DungeonMapData,
    val currentBiome: MapBiome = MapBiome.SACRED_GROVE_IDEMILI,
    val isIsometric: Boolean = false,
    val spriteScale: Float = 1.0f,
    val currentGameMode: GameMode = GameMode.SURVIVAL_ARENA
)

data class AiLabUiState(
    val isGenerating: Boolean = false,
    val statusMessage: String = "",
    val activeModel: String = "",
    val availableModels: List<AiModelItem> = emptyList()
)

data class PenAndPaperUiState(
    val activeRulebook: PnpRulebook? = null,
    val lastRoll: DiceRollResult? = null
)

data class DiabloThemeSettingsUiState(
    val currentEra: DiabloEra = DiabloEra.DIABLO_2,
    val currentNaming: NamingSystem = NamingSystem.MYTHIC_ANCIENT,
    val customStyle: CustomStyleOptions = CustomStyleOptions(),
    val activeTheme: GameThemeProfile,
    val isGeneratingTheme: Boolean = false,
    val themeStatusMsg: String = "",
    val activeSprite: SpriteSheetData,
    val selectedModel: String = "",
    val availableModels: List<AiModelItem> = emptyList(),
    val isGeneratingSprite: Boolean = false,
    val spriteStatusMsg: String = ""
)

data class ConsolidatedSettingsUiState(
    val diabloSettings: DiabloThemeSettingsUiState,
    val openRouterApiKey: String = "",
    val customBaseUrl: String = "",
    val isLoadingAiModels: Boolean = false,
    val autoChromaKey: Boolean = true,
    val chromaTolerance: Float = 0.15f,
    val spritePoliceReport: SpritePoliceReport? = null
)
