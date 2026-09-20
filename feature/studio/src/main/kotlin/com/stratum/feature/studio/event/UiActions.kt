package com.stratum.feature.studio.event

import android.content.Context
import android.net.Uri
import com.stratum.legacy.domain.ActiveLootDrop
import com.stratum.legacy.domain.BaseWeaponType
import com.stratum.legacy.domain.CustomStyleOptions
import com.stratum.legacy.domain.DiabloEra
import com.stratum.legacy.domain.DiceType
import com.stratum.legacy.domain.GameMode
import com.stratum.legacy.domain.HeroClass
import com.stratum.legacy.domain.MapBiome
import com.stratum.legacy.domain.MechanicTriggerType
import com.stratum.legacy.domain.NamingSystem
import com.stratum.legacy.domain.PnpSkillCheck
import com.stratum.legacy.domain.PowerAttackSkill
import com.stratum.legacy.domain.SpriteSheetData
import com.stratum.legacy.domain.VisualLogicScript
import com.stratum.legacy.domain.WeaponRarity
import com.stratum.feature.studio.AppScreen
import com.stratum.feature.studio.CreatorSubTab
import com.stratum.feature.studio.MainNavigationTab

/**
 * PURE CLEAN ARCHITECTURE UI ACTIONS / USER INTENTS
 *
 * Sealed event hierarchies emitted by pure presentation composables.
 * Completely decouples UI event production from event consumption in the ViewModel.
 */

sealed interface CombatUiAction {
    data class JoystickMove(val x: Float, val y: Float) : CombatUiAction
    data object PrimaryAttack : CombatUiAction
    data object ThunderSkill : CombatUiAction
    data object SunNovaSkill : CombatUiAction
    data object Dash : CombatUiAction
    data class DrinkPotion(val slot: Int) : CombatUiAction
    data object TogglePause : CombatUiAction
    data object ResumeCombat : CombatUiAction
    data class ToggleDummyMode(val isDummy: Boolean) : CombatUiAction
    data object ResetCombatStats : CombatUiAction
    data object CycleEra : CombatUiAction
    data object CycleNaming : CombatUiAction
    data class SetEra(val era: DiabloEra) : CombatUiAction
    data class SetNaming(val system: NamingSystem) : CombatUiAction
    data object ToggleIsometric : CombatUiAction
    data class SetSpriteScale(val scale: Float) : CombatUiAction
    data object VacuumLoot : CombatUiAction
    data class PickupLoot(val dropId: String) : CombatUiAction
    data object DismissLoot : CombatUiAction
    data object RestartWave : CombatUiAction
    data object ResetGame : CombatUiAction
    data object OpenSettings : CombatUiAction
    data class NavigateTo(val screen: AppScreen) : CombatUiAction
}

sealed interface PauseOverlayUiAction {
    data object Resume : PauseOverlayUiAction
    data object RestartWave : PauseOverlayUiAction
    data class ToggleDummy(val enabled: Boolean) : PauseOverlayUiAction
    data object OpenSettings : PauseOverlayUiAction
    data object QuitToMenu : PauseOverlayUiAction
    data class SelectEra(val era: DiabloEra) : PauseOverlayUiAction
}

sealed interface MainMenuUiAction {
    data class NavigateTo(val screen: AppScreen) : MainMenuUiAction
    data object OpenSettings : MainMenuUiAction
    data object CycleEra : MainMenuUiAction
    data object CycleNaming : MainMenuUiAction
}

sealed interface WeaponForgeUiAction {
    data class RollWeapon(val base: BaseWeaponType, val rarity: WeaponRarity, val level: Int) : WeaponForgeUiAction
    data class InventMechanic(val name: String, val igboName: String = "", val trigger: MechanicTriggerType, val chance: Float, val desc: String) : WeaponForgeUiAction
    data class ToggleMechanic(val id: String, val enabled: Boolean) : WeaponForgeUiAction
    data class EquipWeapon(val id: String) : WeaponForgeUiAction
    data class DeleteWeapon(val id: String) : WeaponForgeUiAction
}

sealed interface CreatorUiAction {
    data class SelectSubTab(val tab: CreatorSubTab) : CreatorUiAction
    data object NavigateBack : CreatorUiAction
    data object OpenSettings : CreatorUiAction
}

sealed interface SpriteStudioUiAction {
    data class SelectSprite(val sheet: SpriteSheetData) : SpriteStudioUiAction
    data class GenerateAiSprite(val prompt: String) : SpriteStudioUiAction
    data class GenerateRawImageSprite(val prompt: String) : SpriteStudioUiAction
    data class ExportPng(val context: Context, val sheet: SpriteSheetData) : SpriteStudioUiAction
    data class ExportJson(val context: Context, val sheet: SpriteSheetData) : SpriteStudioUiAction
    data class ShareSprite(val context: Context, val sheet: SpriteSheetData, val asPng: Boolean) : SpriteStudioUiAction
    data class ImportFromJson(val json: String) : SpriteStudioUiAction
    data class ImportFromImage(val name: String, val uri: Uri, val role: String) : SpriteStudioUiAction
    data class ImportFromImageDetailed(
        val context: Context,
        val uri: Uri,
        val name: String,
        val desc: String,
        val cols: Int,
        val rows: Int
    ) : SpriteStudioUiAction
    data class ImportCustomSheet(
        val name: String,
        val desc: String,
        val frameWidth: Int,
        val frameHeight: Int,
        val cols: Int,
        val rows: Int,
        val uri: Uri? = null
    ) : SpriteStudioUiAction
    data class ImportProcedural(val sheet: SpriteSheetData) : SpriteStudioUiAction
    data object OpenSettings : SpriteStudioUiAction
}

sealed interface ClassesStudioUiAction {
    data class SelectClass(val heroClass: HeroClass) : ClassesStudioUiAction
    data class CreateClass(val heroClass: HeroClass) : ClassesStudioUiAction
    data class CreateSkill(val skill: PowerAttackSkill) : ClassesStudioUiAction
    data class SaveScript(val script: VisualLogicScript) : ClassesStudioUiAction
}

sealed interface MapStudioUiAction {
    data object GenerateNewMap : MapStudioUiAction
    data object NavigateToArena : MapStudioUiAction
    data object ToggleIsometric : MapStudioUiAction
    data class SetSpriteScale(val scale: Float) : MapStudioUiAction
    data class SelectGameMode(val mode: GameMode) : MapStudioUiAction
    data class SelectBiome(val biome: MapBiome) : MapStudioUiAction
    data object ResetWholeGame : MapStudioUiAction
}

sealed interface AiLabUiAction {
    data class GenerateBundle(val prompt: String) : AiLabUiAction
    data class GenerateSprite(val prompt: String) : AiLabUiAction
    data class GenerateMap(val prompt: String) : AiLabUiAction
    data class GenerateWeapon(val prompt: String) : AiLabUiAction
    data class InventMechanic(val prompt: String) : AiLabUiAction
}

sealed interface PnpUiAction {
    data class RollDice(val diceType: DiceType, val modifier: Int) : PnpUiAction
    data class ExecuteSkillCheck(val check: PnpSkillCheck, val modifier: Int) : PnpUiAction
}

sealed interface DiabloThemeUiAction {
    data class SetEra(val era: DiabloEra) : DiabloThemeUiAction
    data class SetNaming(val system: NamingSystem) : DiabloThemeUiAction
    data class UpdateCustomStyle(val style: CustomStyleOptions) : DiabloThemeUiAction
    data class GenerateAiTheme(val prompt: String) : DiabloThemeUiAction
    data class PushSpritesheet(val prompt: String) : DiabloThemeUiAction
}

sealed interface ConsolidatedSettingsUiAction {
    // Diablo & Theme
    data class SetEra(val era: DiabloEra) : ConsolidatedSettingsUiAction
    data class SetNaming(val system: NamingSystem) : ConsolidatedSettingsUiAction
    data class UpdateCustomStyle(val style: CustomStyleOptions) : ConsolidatedSettingsUiAction
    data class GenerateAiTheme(val prompt: String) : ConsolidatedSettingsUiAction
    data class PushSpritesheet(val prompt: String) : ConsolidatedSettingsUiAction

    // OpenRouter AI Connect
    data class SetOpenRouterSettings(val apiKey: String, val model: String, val baseUrl: String) : ConsolidatedSettingsUiAction
    data object FetchAiModels : ConsolidatedSettingsUiAction
    data class SelectModel(val modelId: String) : ConsolidatedSettingsUiAction

    // Sprite Transparency Police
    data class SetAutoChromaKey(val enabled: Boolean) : ConsolidatedSettingsUiAction
    data class SetChromaTolerance(val tolerance: Float) : ConsolidatedSettingsUiAction
    data object PoliceCurrentSpriteSheet : ConsolidatedSettingsUiAction

    // Menu Navigation & Dev Cheats
    data class NavigateToScreen(val screen: AppScreen, val subTab: CreatorSubTab? = null, val navTab: MainNavigationTab? = null) : ConsolidatedSettingsUiAction
    data object AddDevGold : ConsolidatedSettingsUiAction
    data object RestoreDevVitals : ConsolidatedSettingsUiAction
}
