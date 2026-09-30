package com.stratum.feature.studio

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.legacy.data.SpriteFileTransferManager
import com.stratum.feature.studio.di.AppContainer
import com.stratum.feature.studio.di.DefaultAppContainer
import com.stratum.legacy.domain.AiGameMechanicGeneratorService
import com.stratum.legacy.domain.AiModelItem
import com.stratum.legacy.domain.ArpgGameLoopEngine
import com.stratum.legacy.domain.ArpgWorldState
import com.stratum.legacy.domain.BaseWeaponType
import com.stratum.legacy.domain.CustomGameMechanic
import com.stratum.legacy.domain.DiceRollResult
import com.stratum.legacy.domain.DiceType
import com.stratum.legacy.domain.ExecutePnpCheckUseCase
import com.stratum.legacy.domain.ExportSpriteSheetToJsonUseCase
import com.stratum.legacy.domain.FetchAiModelsUseCase
import com.stratum.legacy.domain.GenerateSpriteWithAiUseCase
import com.stratum.legacy.domain.ImportSpriteSheetFromJsonUseCase
import com.stratum.legacy.domain.ImportSpriteSheetUseCase
import com.stratum.legacy.domain.InventGameMechanicUseCase
import com.stratum.legacy.domain.MechanicTriggerType
import com.stratum.legacy.domain.OpenRouterConfig
import com.stratum.legacy.domain.PnpRuleRepository
import com.stratum.legacy.domain.PnpRulebook
import com.stratum.legacy.domain.PnpSkillCheck
import com.stratum.legacy.domain.PoliceSpriteSheetUseCase
import com.stratum.legacy.domain.PreloadedSpritePacks
import com.stratum.legacy.domain.RollRandomWeaponUseCase
import com.stratum.legacy.domain.SimulateCombatDpsUseCase
import com.stratum.legacy.domain.SpritePoliceReport
import com.stratum.legacy.domain.CastType
import com.stratum.legacy.domain.DamageType
import com.stratum.legacy.domain.DungeonMapData
import com.stratum.legacy.domain.GameMode
import com.stratum.legacy.domain.HeroClass
import com.stratum.legacy.domain.MapBiome
import com.stratum.legacy.domain.PowerAttackSkill
import com.stratum.legacy.domain.PreloadedCatalog
import com.stratum.legacy.domain.PreloadedClasses
import com.stratum.legacy.domain.ProceduralMapGenerator
import com.stratum.legacy.domain.SpriteEntityCategory
import com.stratum.legacy.domain.DiabloEra
import com.stratum.legacy.domain.NamingSystem
import com.stratum.legacy.domain.GameThemeProfile
import com.stratum.legacy.domain.PreloadedThemes
import com.stratum.legacy.domain.CustomStyleOptions
import com.stratum.legacy.domain.GlobeRenderStyle

import com.stratum.legacy.domain.SpriteSheetData
import com.stratum.legacy.domain.SpriteSheetRepository
import com.stratum.legacy.domain.TriggerLogicNode
import com.stratum.legacy.domain.ConditionLogicNode
import com.stratum.legacy.domain.ActionLogicNode
import com.stratum.legacy.domain.ModifierLogicNode
import com.stratum.legacy.domain.VisualLogicScript
import com.stratum.legacy.domain.WeaponItem
import com.stratum.legacy.domain.WeaponRarity
import com.stratum.legacy.domain.WeaponRepository
import com.stratum.legacy.domain.GenerateRawImageSpriteSheetUseCase
import com.stratum.legacy.domain.ToggleGamePauseUseCase
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppScreen {
    START_MENU,
    ARENA,
    FORGE_LAB,
    CREATOR_STUDIO,
    PEN_AND_PAPER,
    LORE_CODEX
}

enum class CreatorSubTab(val label: String, val icon: String) {
    SPRITES("Sprites & AI", "🎨"),
    CLASSES("Classes & Powers", "🔮"),
    MAPS("Map Generator", "🗺️"),
    AI_MECHANICS("AI Lab", "✨")
}

enum class MainNavigationTab(val label: String, val iconSymbol: String) {
    ARENA("Arena", "⚔️"),
    CLASS_STUDIO("Classes & Powers", "🔮"),
    MAP_STUDIO("Map Generator", "🗺️"),
    FORGE_LAB("Forge & Lab", "🔨"),
    SPRITES("Sprites", "🎨"),
    PEN_AND_PAPER("P&P RPG", "🎲"),
    AI_STUDIO("AI Generator", "✨"),
    CODEX("Igbo Codex", "📜")
}

enum class AiModelFilterMode(val label: String) {
    ALL("All Models"),
    IMAGE_OUTPUT("Image Output"),
    VISION_INPUT("Vision Input"),
    TEXT_LOGIC("Text & Logic"),
    FREE_ONLY("Free Only")
}

data class PlayerHeaderStats(
    val level: Int = 1,
    val gold: Int = 50
)

data class CombatHudStats(
    val wave: Int = 1,
    val kills: Int = 0,
    val dps: Int = 0,
    val isDummy: Boolean = false,
    val currentHp: Int = 250,
    val maxHp: Int = 250,
    val currentSpirit: Int = 120,
    val maxSpirit: Int = 120,
    val attackCd: Long = 0L,
    val thunderCd: Long = 0L,
    val sunNovaCd: Long = 0L,
    val dashCd: Long = 0L,
    val weaponName: String = "Bronze Blade"
)

class ArpgEngineViewModel(
    application: Application,
    private val container: AppContainer
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        DefaultAppContainer.getInstance(application)
    )

    private val weaponRepo: WeaponRepository get() = container.weaponRepository
    private val spriteRepo: SpriteSheetRepository get() = container.spriteSheetRepository
    private val pnpRepo: PnpRuleRepository get() = container.pnpRuleRepository
    private val aiService: AiGameMechanicGeneratorService get() = container.aiService

    // Real-Time Engine (Initialized before UseCases)
    private val engine = ArpgGameLoopEngine()
    val engineState: ArpgWorldState get() = engine.state
    private var engineLoopJob: Job? = null

    // Domain Use Cases injected from container
    private val rollWeaponUseCase get() = container.rollRandomWeaponUseCase
    private val inventMechanicUseCase get() = container.inventGameMechanicUseCase
    private val importSpriteUseCase get() = container.importSpriteSheetUseCase
    private val executePnpCheckUseCase get() = container.executePnpCheckUseCase
    private val simulateDpsUseCase get() = container.simulateCombatDpsUseCase
    private val generateSpriteWithAiUseCase get() = container.generateSpriteWithAiUseCase
    private val exportSpriteJsonUseCase get() = container.exportSpriteSheetToJsonUseCase
    private val importSpriteJsonUseCase get() = container.importSpriteSheetFromJsonUseCase
    private val fetchAiModelsUseCase get() = container.fetchAiModelsUseCase
    private val policeSpriteSheetUseCase get() = container.policeSpriteSheetUseCase
    private val generateRawImageSpriteSheetUseCase get() = container.generateRawImageSpriteSheetUseCase
    private val toggleGamePauseUseCase by lazy { container.createToggleGamePauseUseCase(engine) }

    // Screens & Pause State for Organized Menus
    private val _currentScreen = MutableStateFlow(AppScreen.START_MENU)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _creatorSubTab = MutableStateFlow(CreatorSubTab.SPRITES)
    val creatorSubTab: StateFlow<CreatorSubTab> = _creatorSubTab.asStateFlow()

    private val _isCombatPaused = MutableStateFlow(false)
    val isCombatPaused: StateFlow<Boolean> = _isCombatPaused.asStateFlow()

    // OpenRouter & Open API Model Choice Configuration
    private val _openRouterApiKey = MutableStateFlow("")
    val openRouterApiKey: StateFlow<String> = _openRouterApiKey.asStateFlow()

    private val _selectedModel = MutableStateFlow("google/gemini-2.0-flash-exp:free")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _customBaseUrl = MutableStateFlow("https://openrouter.ai/api/v1/")
    val customBaseUrl: StateFlow<String> = _customBaseUrl.asStateFlow()

    private val _availableAiModels = MutableStateFlow<List<AiModelItem>>(emptyList())
    val availableAiModels: StateFlow<List<AiModelItem>> = _availableAiModels.asStateFlow()

    private val _isLoadingAiModels = MutableStateFlow(false)
    val isLoadingAiModels: StateFlow<Boolean> = _isLoadingAiModels.asStateFlow()

    // Sprite Transparency Police State
    private val _autoChromaKey = MutableStateFlow(true)
    val autoChromaKey: StateFlow<Boolean> = _autoChromaKey.asStateFlow()

    private val _chromaTolerance = MutableStateFlow(0.15f)
    val chromaTolerance: StateFlow<Float> = _chromaTolerance.asStateFlow()

    private val _spritePoliceReport = MutableStateFlow<SpritePoliceReport?>(null)
    val spritePoliceReport: StateFlow<SpritePoliceReport?> = _spritePoliceReport.asStateFlow()

    // Consolidated Menu & Settings Dialog State
    private val _isSettingsMenuOpen = MutableStateFlow(false)
    val isSettingsMenuOpen: StateFlow<Boolean> = _isSettingsMenuOpen.asStateFlow()

    // Navigation
    private val _currentTab = MutableStateFlow(MainNavigationTab.ARENA)
    val currentTab: StateFlow<MainNavigationTab> = _currentTab.asStateFlow()

    // Decoupled State Flows for Maximum Frame Rate Performance
    private val _playerHeaderStats = MutableStateFlow(PlayerHeaderStats())
    val playerHeaderStats: StateFlow<PlayerHeaderStats> = _playerHeaderStats.asStateFlow()

    private val _combatHudStats = MutableStateFlow(CombatHudStats())
    val combatHudStats: StateFlow<CombatHudStats> = _combatHudStats.asStateFlow()

    // Ultra-fast render tick for Canvas draw phase (does not trigger layout/measure passes)
    private val _renderTick = MutableStateFlow(0L)
    val renderTick: StateFlow<Long> = _renderTick.asStateFlow()

    private val _worldState = MutableStateFlow(engine.state)
    val worldState: StateFlow<ArpgWorldState> = _worldState.asStateFlow()

    private val _inventory = MutableStateFlow<List<WeaponItem>>(emptyList())
    val inventory: StateFlow<List<WeaponItem>> = _inventory.asStateFlow()

    private val _spriteSheets = MutableStateFlow<List<SpriteSheetData>>(emptyList())
    val spriteSheets: StateFlow<List<SpriteSheetData>> = _spriteSheets.asStateFlow()

    private val _activeSprite = MutableStateFlow(PreloadedSpritePacks.IGBO_OZO_WARRIOR)
    val activeSprite: StateFlow<SpriteSheetData> = _activeSprite.asStateFlow()

    private val _rulebooks = MutableStateFlow<List<PnpRulebook>>(emptyList())
    val rulebooks: StateFlow<List<PnpRulebook>> = _rulebooks.asStateFlow()

    private val _activeRulebook = MutableStateFlow<PnpRulebook?>(null)
    val activeRulebook: StateFlow<PnpRulebook?> = _activeRulebook.asStateFlow()

    private val _lastDiceRoll = MutableStateFlow<DiceRollResult?>(null)
    val lastDiceRoll: StateFlow<DiceRollResult?> = _lastDiceRoll.asStateFlow()

    private val _isAiGenerating = MutableStateFlow(false)
    val isAiGenerating: StateFlow<Boolean> = _isAiGenerating.asStateFlow()

    private val _aiStatusMessage = MutableStateFlow<String?>(null)
    val aiStatusMessage: StateFlow<String?> = _aiStatusMessage.asStateFlow()

    private val _dpsReport = MutableStateFlow<SimulateCombatDpsUseCase.SimulationReport?>(null)
    val dpsReport: StateFlow<SimulateCombatDpsUseCase.SimulationReport?> = _dpsReport.asStateFlow()

    // AI Model Filtering State
    private val _aiModelFilter = MutableStateFlow(AiModelFilterMode.ALL)
    val aiModelFilter: StateFlow<AiModelFilterMode> = _aiModelFilter.asStateFlow()

    val filteredAiModels: StateFlow<List<AiModelItem>> = combine(_availableAiModels, _aiModelFilter) { models, filter ->
        when (filter) {
            AiModelFilterMode.ALL -> models
            AiModelFilterMode.IMAGE_OUTPUT -> models.filter { it.isImageCapable }
            AiModelFilterMode.VISION_INPUT -> models.filter { it.isVisionInput }
            AiModelFilterMode.TEXT_LOGIC -> models.filter { !it.isImageCapable }
            AiModelFilterMode.FREE_ONLY -> models.filter { it.isFree }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 2.5D Isometric Projection & Scaling Controls
    private val _isIsometricView = MutableStateFlow(false)
    val isIsometricView: StateFlow<Boolean> = _isIsometricView.asStateFlow()

    private val _spriteScale = MutableStateFlow(1.5f)
    val spriteScale: StateFlow<Float> = _spriteScale.asStateFlow()

    // Map Engine & Procedural Dungeon Biomes
    private val _currentBiome = MutableStateFlow(MapBiome.SACRED_GROVE_IDEMILI)
    val currentBiome: StateFlow<MapBiome> = _currentBiome.asStateFlow()

    private val _currentMap = MutableStateFlow(ProceduralMapGenerator.generateDungeon(MapBiome.SACRED_GROVE_IDEMILI, 28, 28))
    val currentMap: StateFlow<DungeonMapData> = _currentMap.asStateFlow()

    // Game Modes
    private val _currentGameMode = MutableStateFlow(GameMode.SURVIVAL_ARENA)
    val currentGameMode: StateFlow<GameMode> = _currentGameMode.asStateFlow()

    // Hero Classes
    private val _heroClasses = MutableStateFlow<List<HeroClass>>(PreloadedClasses.ALL)
    val heroClasses: StateFlow<List<HeroClass>> = _heroClasses.asStateFlow()

    private val _activeHeroClass = MutableStateFlow<HeroClass>(PreloadedClasses.OZO_WARRIOR)
    val activeHeroClass: StateFlow<HeroClass> = _activeHeroClass.asStateFlow()

    // Diablo Era & Miyamoto Nintendo Design Architecture
    private val _diabloEra = MutableStateFlow(DiabloEra.DIABLO_2)
    val diabloEra: StateFlow<DiabloEra> = _diabloEra.asStateFlow()

    private val _namingSystem = MutableStateFlow(NamingSystem.GENERIC_RPG)
    val namingSystem: StateFlow<NamingSystem> = _namingSystem.asStateFlow()

    private val _activeTheme = MutableStateFlow(PreloadedThemes.CLASSIC_GENERIC)
    val activeTheme: StateFlow<GameThemeProfile> = _activeTheme.asStateFlow()

    private val _customStyle = MutableStateFlow(CustomStyleOptions())
    val customStyle: StateFlow<CustomStyleOptions> = _customStyle.asStateFlow()

    private val _isGeneratingTheme = MutableStateFlow(false)
    val isGeneratingTheme: StateFlow<Boolean> = _isGeneratingTheme.asStateFlow()

    // Diablo 2-Style Quick-Sip Belt Potions (1: Health, 2: Mana, 3: Rejuv, 4: Elixir)
    private val _potionBeltCounts = MutableStateFlow(listOf(4, 4, 1, 2))
    val potionBeltCounts: StateFlow<List<Int>> = _potionBeltCounts.asStateFlow()


    // Power Builder & Custom Attacks
    private val _availableSkills = MutableStateFlow<List<PowerAttackSkill>>(
        listOf(
            PreloadedCatalog.SKILL_OZO_SLASH,
            PreloadedCatalog.SKILL_AMADIOHA_BOLT,
            PreloadedCatalog.SKILL_ANYANWU_NOVA,
            PreloadedCatalog.SKILL_IDEMILI_SERPENT,
            PreloadedCatalog.SKILL_IKENGA_SHOCKWAVE
        )
    )
    val availableSkills: StateFlow<List<PowerAttackSkill>> = _availableSkills.asStateFlow()

    private val _primarySkill = MutableStateFlow<PowerAttackSkill>(PreloadedCatalog.SKILL_OZO_SLASH)
    val primarySkill: StateFlow<PowerAttackSkill> = _primarySkill.asStateFlow()

    private val _secondarySkill = MutableStateFlow<PowerAttackSkill>(PreloadedCatalog.SKILL_AMADIOHA_BOLT)
    val secondarySkill: StateFlow<PowerAttackSkill> = _secondarySkill.asStateFlow()

    // Visual Programming Logic Node Script
    private val _activeLogicScript = MutableStateFlow<VisualLogicScript>(
        VisualLogicScript(
            name = "Nsibidi Chain Lightning Protocol",
            trigger = TriggerLogicNode.ON_MELEE_HIT,
            condition = ConditionLogicNode.CHANCE_ROLL,
            action = ActionLogicNode.UNLEASH_CHAIN_LIGHTNING,
            modifier = ModifierLogicNode.ECHO_DOUBLE_CAST
        )
    )
    val activeLogicScript: StateFlow<VisualLogicScript> = _activeLogicScript.asStateFlow()

    // Virtual Input state
    private var joystickX = 0f
    private var joystickY = 0f
    private var attackTrigger = false
    private var thunderTrigger = false
    private var sunNovaTrigger = false
    private var dashTrigger = false

    init {
        startEngineLoop()
        observeDataSources()
        prepopulateDefaults()
        fetchAvailableAiModels()
    }

    private fun prepopulateDefaults() = viewModelScope.launch {
        // Equip default weapon if inventory is empty
        val existingWeapons = weaponRepo.getAllWeapons()
        if (existingWeapons.isEmpty()) {
            val starter1 = rollWeaponUseCase(BaseWeaponType.MMA_NKWU, WeaponRarity.MAGIC, itemLevel = 3)
            val starter2 = rollWeaponUseCase(BaseWeaponType.OFO_SCEPTER, WeaponRarity.RARE, itemLevel = 5)
            weaponRepo.equipWeapon(starter2.id)
            engine.state.hero.equippedWeapon = starter2.copy(isEquipped = true)
        } else {
            val eq = existingWeapons.find { it.isEquipped } ?: existingWeapons.first()
            engine.state.hero.equippedWeapon = eq
        }

        // Initialize active rulebook
        _activeRulebook.value = pnpRepo.getActiveRulebook()
        _playerHeaderStats.value = PlayerHeaderStats(level = engine.state.hero.level, gold = engine.state.hero.gold)
    }

    private fun observeDataSources() {
        viewModelScope.launch {
            weaponRepo.observeAllWeapons().collect { list ->
                _inventory.value = list
                val eq = list.find { it.isEquipped }
                if (eq != null) {
                    engine.state.hero.equippedWeapon = eq
                    _dpsReport.value = simulateDpsUseCase(eq, engine.state.hero.attributes)
                }
            }
        }
        viewModelScope.launch {
            spriteRepo.observeAllSpriteSheets().collect { list ->
                _spriteSheets.value = list
            }
        }
        viewModelScope.launch {
            pnpRepo.observeAllRulebooks().collect { list ->
                _rulebooks.value = list
                if (_activeRulebook.value == null && list.isNotEmpty()) {
                    _activeRulebook.value = list.first()
                }
            }
        }
    }

    private fun startEngineLoop() {
        engineLoopJob?.cancel()
        engineLoopJob = viewModelScope.launch {
            var lastTime = System.currentTimeMillis()
            var hudThrottleTimer = 0L
            var lastLevel = engine.state.hero.level
            var lastGold = engine.state.hero.gold

            while (isActive) {
                // When in another screen or paused, pause combat tick completely to free up 100% CPU
                if (_currentScreen.value != AppScreen.ARENA || _isCombatPaused.value || _currentTab.value != MainNavigationTab.ARENA) {
                    delay(150)
                    lastTime = System.currentTimeMillis()
                    continue
                }

                val now = System.currentTimeMillis()
                val dt = (now - lastTime).coerceIn(5, 45)
                lastTime = now

                engine.tick(
                    dtMs = dt,
                    joystickX = joystickX,
                    joystickY = joystickY,
                    isAttackPressed = attackTrigger,
                    isThunderSkillPressed = thunderTrigger,
                    isSunNovaSkillPressed = sunNovaTrigger,
                    isDashPressed = dashTrigger
                )

                // Clear single-frame triggers
                attackTrigger = false
                thunderTrigger = false
                sunNovaTrigger = false
                dashTrigger = false

                // Notify high-performance Canvas render tick
                _renderTick.value++

                // Throttle Compose UI layout passes to ~10 Hz (saves 85% of recomposition & measure work)
                hudThrottleTimer += dt
                if (hudThrottleTimer >= 90L) {
                    hudThrottleTimer = 0L
                    val hero = engine.state.hero
                    _combatHudStats.value = CombatHudStats(
                        wave = engine.state.currentWave,
                        kills = engine.state.monstersDefeated,
                        dps = engine.state.dpsInSession.toInt(),
                        isDummy = engine.state.isDummyTestMode,
                        currentHp = hero.currentHealth,
                        maxHp = hero.maxHealth,
                        currentSpirit = hero.currentSpirit,
                        maxSpirit = hero.maxSpirit,
                        attackCd = hero.attackCooldownMs,
                        thunderCd = hero.thunderCooldownMs,
                        sunNovaCd = hero.sunNovaCooldownMs,
                        dashCd = hero.dashCooldownMs,
                        weaponName = hero.equippedWeapon?.name ?: "Bronze Blade"
                    )

                    if (hero.level != lastLevel || hero.gold != lastGold) {
                        lastLevel = hero.level
                        lastGold = hero.gold
                        _playerHeaderStats.value = PlayerHeaderStats(level = hero.level, gold = hero.gold)
                    }
                }

                delay(22) // ~45 FPS target: silky smooth on streaming emulator
            }
        }
    }

    // Screen Navigation & Menu Organization
    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
        when (screen) {
            AppScreen.ARENA -> {
                _currentTab.value = MainNavigationTab.ARENA
                resumeCombat()
            }
            AppScreen.FORGE_LAB -> {
                _currentTab.value = MainNavigationTab.FORGE_LAB
                pauseCombat()
            }
            AppScreen.CREATOR_STUDIO -> {
                setCreatorSubTab(_creatorSubTab.value)
                pauseCombat()
            }
            AppScreen.PEN_AND_PAPER -> {
                _currentTab.value = MainNavigationTab.PEN_AND_PAPER
                pauseCombat()
            }
            AppScreen.LORE_CODEX -> {
                _currentTab.value = MainNavigationTab.CODEX
                pauseCombat()
            }
            AppScreen.START_MENU -> {
                pauseCombat()
            }
        }
    }

    fun setCreatorSubTab(subTab: CreatorSubTab) {
        _creatorSubTab.value = subTab
        when (subTab) {
            CreatorSubTab.SPRITES -> _currentTab.value = MainNavigationTab.SPRITES
            CreatorSubTab.CLASSES -> _currentTab.value = MainNavigationTab.CLASS_STUDIO
            CreatorSubTab.MAPS -> _currentTab.value = MainNavigationTab.MAP_STUDIO
            CreatorSubTab.AI_MECHANICS -> _currentTab.value = MainNavigationTab.AI_STUDIO
        }
    }

    fun pauseCombat() {
        toggleGamePauseUseCase.setPaused(true)
        _isCombatPaused.value = true
    }

    fun resumeCombat() {
        toggleGamePauseUseCase.setPaused(false)
        _isCombatPaused.value = false
    }

    fun toggleCombatPause(): Boolean {
        val paused = toggleGamePauseUseCase()
        _isCombatPaused.value = paused
        return paused
    }

    fun restartArenaWave() {
        engine.state.monsters.clear()
        engine.state.projectiles.clear()
        engine.state.hero.currentHealth = engine.state.hero.maxHealth
        engine.state.hero.currentSpirit = engine.state.hero.maxSpirit
        engine.spawnWave(engine.state.currentWave)
        resumeCombat()
    }

    // Input handlers
    fun setNavigationTab(tab: MainNavigationTab) {
        _currentTab.value = tab
    }

    fun selectMainTab(tab: MainNavigationTab) {
        _currentTab.value = tab
    }

    fun updateJoystick(x: Float, y: Float) {
        joystickX = x
        joystickY = y
    }

    fun triggerAttack() {
        attackTrigger = true
    }

    fun triggerThunder() {
        thunderTrigger = true
    }

    fun triggerSunNova() {
        sunNovaTrigger = true
    }

    fun triggerDash() {
        dashTrigger = true
    }

    fun pickUpLoot(dropId: String) = viewModelScope.launch {
        val weapon = engine.pickUpLoot(dropId)
        if (weapon != null) {
            weaponRepo.saveWeapon(weapon)
        }
    }

    fun pickUpAllNearbyLoot() = viewModelScope.launch {
        val items = engine.pickUpAllNearbyLoot()
        for (item in items) {
            weaponRepo.saveWeapon(item)
        }
        if (items.isNotEmpty()) {
            _aiStatusMessage.value = "Vacuumed ${items.size} item(s) into inventory!"
        }
    }

    fun resetWholeGame() = viewModelScope.launch {
        engine.resetWholeGame()
        _playerHeaderStats.value = PlayerHeaderStats(level = 1, gold = 50)
        engine.state.hero.equippedWeapon?.let { eq ->
            weaponRepo.saveWeapon(eq)
            weaponRepo.equipWeapon(eq.id)
        }
        _aiStatusMessage.value = "Whole game session reset to Wave 1 with full health!"
    }

    fun toggleIsometricView() {
        _isIsometricView.value = !_isIsometricView.value
    }

    fun setIsometricView(enabled: Boolean) {
        _isIsometricView.value = enabled
    }

    fun setSpriteScale(scale: Float) {
        _spriteScale.value = scale.coerceIn(1.0f, 3.5f)
    }

    fun generateNewMap(biome: MapBiome = _currentBiome.value, seed: Long = System.currentTimeMillis()) {
        _currentBiome.value = biome
        _currentMap.value = ProceduralMapGenerator.generateDungeon(biome = biome, seed = seed, width = 28, height = 28)
        _aiStatusMessage.value = "Generated new ${biome.displayName} procedural map!"
    }

    fun selectGameMode(mode: GameMode) {
        _currentGameMode.value = mode
        when (mode) {
            GameMode.SURVIVAL_ARENA -> {
                engine.setDummyTestMode(false)
                engine.spawnWave(1)
            }
            GameMode.DUNGEON_CRAWL -> {
                engine.setDummyTestMode(false)
                generateNewMap(MapBiome.LOST_BRONZE_CATACOMBS)
            }
            GameMode.HORDE_SIEGE -> {
                engine.setDummyTestMode(false)
                engine.spawnWave(5)
            }
            GameMode.SANDBOX_PLAYGROUND -> {
                engine.setDummyTestMode(true)
            }
        }
        _aiStatusMessage.value = "Switched game mode to ${mode.title}!"
    }

    fun selectHeroClass(heroClass: HeroClass) {
        _activeHeroClass.value = heroClass
        heroClass.spriteSheetData?.let {
            _activeSprite.value = it
        }
        _aiStatusMessage.value = "Selected Class: ${heroClass.name} (${heroClass.igboTitle})"
    }

    fun selectHeroClass(classId: String) {
        _heroClasses.value.firstOrNull { it.id == classId }?.let { selectHeroClass(it) }
    }

    fun createCustomHeroClass(newClass: HeroClass) {
        _heroClasses.value = _heroClasses.value + newClass
        selectHeroClass(newClass)
    }

    fun createCustomPower(skill: PowerAttackSkill) {
        _availableSkills.value = _availableSkills.value + skill
        _aiStatusMessage.value = "Created Custom Power: ${skill.name}!"
    }

    fun createCustomSkill(skill: PowerAttackSkill) {
        createCustomPower(skill)
    }

    fun setPrimarySkill(skill: PowerAttackSkill) {
        _primarySkill.value = skill
    }

    fun setSecondarySkill(skill: PowerAttackSkill) {
        _secondarySkill.value = skill
    }

    fun updateLogicScript(script: VisualLogicScript) {
        _activeLogicScript.value = script
        _aiStatusMessage.value = "Updated Visual Logic: ${script.name}!"
    }

    fun saveLogicScript(script: VisualLogicScript) {
        updateLogicScript(script)
    }

    fun selectBiome(biome: MapBiome) {
        generateNewMap(biome = biome)
    }

    fun setAiModelFilter(filter: AiModelFilterMode) {
        _aiModelFilter.value = filter
    }

    fun generateEnemySpriteSheet(archetypeId: String, name: String, desc: String) = viewModelScope.launch {
        val pack = when (archetypeId) {
            "arch_ogu_brute" -> PreloadedSpritePacks.ENEMY_OGU_BRUTE
            "arch_shadow_leopard" -> PreloadedSpritePacks.ENEMY_LEOPARD_ASSASSIN
            "arch_boss_priest" -> PreloadedSpritePacks.ENEMY_AGBARA_HIGH_PRIEST
            else -> PreloadedSpritePacks.ENEMY_FOREST_MMO
        }
        val customSheet = pack.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = name.ifEmpty { pack.name },
            description = desc.ifEmpty { pack.description },
            entityCategory = SpriteEntityCategory.ENEMY,
            enemyArchetypeId = archetypeId
        )
        spriteRepo.saveSpriteSheet(customSheet)
        _activeSprite.value = customSheet
        _aiStatusMessage.value = "Generated Enemy Sprite Sheet: ${customSheet.name}!"
    }

    fun toggleDummyMode(enabled: Boolean) {
        engine.setDummyTestMode(enabled)
    }

    fun resetCombatStats() {
        engine.resetCombatStats()
    }

    fun equipWeapon(weaponId: String) = viewModelScope.launch {
        weaponRepo.equipWeapon(weaponId)
        val w = weaponRepo.getWeaponById(weaponId)
        if (w != null) {
            engine.state.hero.equippedWeapon = w.copy(isEquipped = true)
            _dpsReport.value = simulateDpsUseCase(w, engine.state.hero.attributes)
        }
    }

    fun deleteWeapon(weaponId: String) = viewModelScope.launch {
        weaponRepo.deleteWeapon(weaponId)
    }

    fun rollRandomWeapon(
        baseType: BaseWeaponType? = null,
        rarity: WeaponRarity? = null,
        level: Int = 10
    ) = viewModelScope.launch {
        val weapon = rollWeaponUseCase(baseType, rarity, level)
        _dpsReport.value = simulateDpsUseCase(weapon, engine.state.hero.attributes)
    }

    fun inventNewMechanic(
        name: String,
        igboName: String,
        description: String,
        procChance: Float,
        triggerType: MechanicTriggerType
    ) {
        val mechanic = inventMechanicUseCase(name, igboName, description, procChance, triggerType)
        engine.state.activeMechanics.add(mechanic)
    }

    fun toggleMechanic(mechanicId: String, enabled: Boolean) {
        val mech = engine.state.activeMechanics.find { it.id == mechanicId } ?: return
        val idx = engine.state.activeMechanics.indexOf(mech)
        engine.state.activeMechanics[idx] = mech.copy(isEnabled = enabled)
    }

    fun setSettingsMenuOpen(open: Boolean) {
        _isSettingsMenuOpen.value = open
    }

    fun setAutoChromaKey(enabled: Boolean) {
        _autoChromaKey.value = enabled
    }

    fun setChromaTolerance(tolerance: Float) {
        _chromaTolerance.value = tolerance
    }

    fun fetchAvailableAiModels() = viewModelScope.launch {
        _isLoadingAiModels.value = true
        val result = fetchAiModelsUseCase(_customBaseUrl.value, _openRouterApiKey.value)
        result.onSuccess { models ->
            _availableAiModels.value = models
            if (models.isNotEmpty() && models.none { it.id == _selectedModel.value }) {
                val preferred = models.firstOrNull { it.isFree && it.isImageCapable } ?: models.first()
                _selectedModel.value = preferred.id
                aiService.setOpenRouterConfig(
                    OpenRouterConfig(
                        apiKey = _openRouterApiKey.value,
                        model = preferred.id,
                        baseUrl = _customBaseUrl.value
                    )
                )
            }
        }
        _isLoadingAiModels.value = false
    }

    fun policeCurrentSpriteSheet() = viewModelScope.launch {
        val current = _activeSprite.value ?: return@launch
        _aiStatusMessage.value = "Transparently policing sprite sheet..."
        val (policedSheet, report) = policeSpriteSheetUseCase(
            sheet = current,
            autoChromaKey = _autoChromaKey.value,
            tolerance = _chromaTolerance.value
        )
        _activeSprite.value = policedSheet
        _spritePoliceReport.value = report
        _aiStatusMessage.value = report.message
    }

    fun setOpenRouterSettings(apiKey: String, model: String, baseUrl: String) {
        _openRouterApiKey.value = apiKey
        _selectedModel.value = model.ifEmpty { "google/gemini-2.0-flash-exp:free" }
        _customBaseUrl.value = baseUrl.ifEmpty { "https://openrouter.ai/api/v1/" }
        aiService.setOpenRouterConfig(
            OpenRouterConfig(
                apiKey = apiKey,
                model = _selectedModel.value,
                baseUrl = _customBaseUrl.value
            )
        )
        fetchAvailableAiModels()
    }

    fun setSelectedModel(model: String) {
        _selectedModel.value = model
        aiService.setOpenRouterConfig(
            OpenRouterConfig(
                apiKey = _openRouterApiKey.value,
                model = model,
                baseUrl = _customBaseUrl.value
            )
        )
    }

    fun generateAiSprite(prompt: String) = viewModelScope.launch {
        _isAiGenerating.value = true
        _aiStatusMessage.value = "Synthesizing 4x4 Sprite Sheet with ${_selectedModel.value}..."
        val activeModelItem = _availableAiModels.value.firstOrNull { it.id == _selectedModel.value }
        val result = if (activeModelItem?.isImageCapable == true) {
            generateRawImageSpriteSheetUseCase(prompt, _selectedModel.value)
        } else {
            generateSpriteWithAiUseCase(prompt)
        }
        result.onSuccess { sheet ->
            _activeSprite.value = sheet
            spriteRepo.setActiveSpriteSheetId(sheet.id)
            _aiStatusMessage.value = "Generated Sprite: ${sheet.name}! Bound to hero."
            _spriteSheets.value = spriteRepo.getAllSpriteSheets()
        }.onFailure { err ->
            _aiStatusMessage.value = "Sprite generation error: ${err.message}"
        }
        _isAiGenerating.value = false
    }

    fun generateRawImageSprite(prompt: String) = viewModelScope.launch {
        _isAiGenerating.value = true
        _aiStatusMessage.value = "Querying OpenRouter image model (${_selectedModel.value}) for raw sprite sheet..."
        try {
            val result = generateRawImageSpriteSheetUseCase(prompt, _selectedModel.value)
            result.onSuccess { sheet ->
                _activeSprite.value = sheet
                spriteRepo.setActiveSpriteSheetId(sheet.id)
                _aiStatusMessage.value = "Successfully synthesized & policed raw sprite sheet: ${sheet.name}!"
                _spriteSheets.value = spriteRepo.getAllSpriteSheets()
            }.onFailure { err ->
                _aiStatusMessage.value = "Raw generation error: ${err.localizedMessage ?: "Failed"}"
            }
        } catch (e: Exception) {
            _aiStatusMessage.value = "Exception: ${e.localizedMessage}"
        } finally {
            _isAiGenerating.value = false
        }
    }

    fun importSpriteFromImage(context: Context, uri: Uri, name: String, desc: String, cols: Int, rows: Int) = viewModelScope.launch {
        val base64 = SpriteFileTransferManager.decodeUriToBase64(context, uri)
        val sheet = importSpriteUseCase(
            name = name.ifEmpty { "Custom Imported Sprite" },
            description = desc.ifEmpty { "Imported from device image" },
            frameWidth = 48,
            frameHeight = 48,
            columns = cols.coerceIn(1, 16),
            rows = rows.coerceIn(1, 16),
            imageUriOrBase64 = base64,
            igboRole = "Imported Hero"
        )
        _activeSprite.value = sheet
        spriteRepo.setActiveSpriteSheetId(sheet.id)
        _aiStatusMessage.value = "Imported ${sheet.name} and bound to live hero!"
    }

    fun importSpriteFromJson(json: String) = viewModelScope.launch {
        val result = importSpriteJsonUseCase(json)
        result.onSuccess { sheet ->
            _activeSprite.value = sheet
            spriteRepo.setActiveSpriteSheetId(sheet.id)
            _aiStatusMessage.value = "Imported JSON Sprite: ${sheet.name}!"
        }.onFailure { err ->
            _aiStatusMessage.value = "Import failed: ${err.message}"
        }
    }

    fun exportSpritePng(context: Context, sheet: SpriteSheetData): File? {
        val file = SpriteFileTransferManager.exportSpriteToDevice(context, sheet)
        if (file != null) {
            _aiStatusMessage.value = "Exported ${sheet.name} PNG to Downloads/IgboARPG_Sprites!"
        }
        return file
    }

    fun exportSpriteJson(context: Context, sheet: SpriteSheetData): File? {
        val file = SpriteFileTransferManager.exportSpriteJsonToDevice(context, sheet)
        if (file != null) {
            _aiStatusMessage.value = "Exported ${sheet.name} JSON file!"
        }
        return file
    }

    fun shareSprite(context: Context, sheet: SpriteSheetData, asPng: Boolean) {
        if (asPng) {
            val file = SpriteFileTransferManager.exportSpriteToDevice(context, sheet)
            if (file != null) {
                SpriteFileTransferManager.shareExportedFile(context, file, "image/png", sheet.name)
            }
        } else {
            val file = SpriteFileTransferManager.exportSpriteJsonToDevice(context, sheet)
            if (file != null) {
                SpriteFileTransferManager.shareExportedFile(context, file, "application/json", sheet.name)
            }
        }
    }

    fun importSpriteSheet(
        name: String,
        description: String,
        frameWidth: Int,
        frameHeight: Int,
        columns: Int,
        rows: Int,
        uri: String?
    ) = viewModelScope.launch {
        val sheet = importSpriteUseCase(name, description, frameWidth, frameHeight, columns, rows, uri)
        _activeSprite.value = sheet
        spriteRepo.setActiveSpriteSheetId(sheet.id)
    }

    fun setActiveSpriteSheet(sheet: SpriteSheetData) = viewModelScope.launch {
        _activeSprite.value = sheet
        spriteRepo.setActiveSpriteSheetId(sheet.id)
    }

    fun rollPnpDice(type: DiceType, modifier: Int = 0) {
        val roll = com.stratum.legacy.domain.PnpRulebookFactory.rollDice(type, modifier)
        _lastDiceRoll.value = roll
    }

    fun executeSkillCheck(check: PnpSkillCheck, modifier: Int) = viewModelScope.launch {
        val (roll, success) = executePnpCheckUseCase(check, modifier)
        _lastDiceRoll.value = roll
        if (success) {
            // Apply combat blessing to live ARPG world state
            engine.state.combatTexts.add(
                com.stratum.legacy.domain.FloatingCombatText(
                    text = "P&P BLESSING: ${check.name} Success!",
                    x = engine.state.hero.x,
                    y = engine.state.hero.y - 40f,
                    colorHex = "#76FF03",
                    isCrit = true,
                    lifetimeMs = 2500L
                )
            )
            // Add temporary active mechanic
            engine.state.activeMechanics.add(
                CustomGameMechanic(
                    id = "blessing_${check.id}",
                    name = check.name,
                    igboName = check.igboTerm,
                    description = check.successBuffEffect,
                    procChance = 0.5f,
                    triggerType = MechanicTriggerType.ON_HIT
                )
            )
        }
    }

    fun generateAiWeapon(prompt: String) = viewModelScope.launch {
        _isAiGenerating.value = true
        _aiStatusMessage.value = "Invoking Gemini AI & Ancient Igbo Forges..."
        val result = aiService.generateWeaponFromPrompt(prompt)
        result.onSuccess { weapon ->
            weaponRepo.saveWeapon(weapon)
            weaponRepo.equipWeapon(weapon.id)
            engine.state.hero.equippedWeapon = weapon.copy(isEquipped = true)
            _dpsReport.value = simulateDpsUseCase(weapon, engine.state.hero.attributes)
            _aiStatusMessage.value = "Forged: ${weapon.name}! Equipped in live engine."
        }.onFailure { err ->
            _aiStatusMessage.value = "Error: ${err.message}"
        }
        _isAiGenerating.value = false
    }

    fun inventAiMechanic(prompt: String) = viewModelScope.launch {
        _isAiGenerating.value = true
        _aiStatusMessage.value = "Inventing Action RPG combat loop mechanic..."
        val result = aiService.inventMechanicFromPrompt(prompt)
        result.onSuccess { mechanic ->
            engine.state.activeMechanics.add(mechanic)
            _aiStatusMessage.value = "Invented Mechanic: ${mechanic.name} (${mechanic.igboName}) added to engine!"
        }.onFailure { err ->
            _aiStatusMessage.value = "Error: ${err.message}"
        }
        _isAiGenerating.value = false
    }

    fun generateAiRulebook(prompt: String) = viewModelScope.launch {
        _isAiGenerating.value = true
        _aiStatusMessage.value = "Synthesizing Pen & Paper RPG module..."
        val result = aiService.generatePnpRulebookFromPrompt(prompt)
        result.onSuccess { rulebook ->
            pnpRepo.saveRulebook(rulebook)
            _activeRulebook.value = rulebook
            _aiStatusMessage.value = "Created Rulebook: ${rulebook.title}!"
        }.onFailure { err ->
            _aiStatusMessage.value = "Error: ${err.message}"
        }
        _isAiGenerating.value = false
    }

    fun generateAiMap(prompt: String) = viewModelScope.launch {
        _isAiGenerating.value = true
        _aiStatusMessage.value = "Generating Procedural Map for '$prompt'..."
        val lower = prompt.lowercase()
        val biome = when {
            lower.contains("storm") || lower.contains("thunder") || lower.contains("amadioha") || lower.contains("lightning") || lower.contains("crag") ->
                MapBiome.AMADIOHA_THUNDER_PEAK
            lower.contains("catacomb") || lower.contains("crypt") || lower.contains("tomb") || lower.contains("bronze") || lower.contains("dungeon") ->
                MapBiome.LOST_BRONZE_CATACOMBS
            lower.contains("sun") || lower.contains("fire") || lower.contains("palace") || lower.contains("royal") || lower.contains("ozo") ->
                MapBiome.OZO_ROYAL_PALACE
            lower.contains("river") || lower.contains("marsh") || lower.contains("water") || lower.contains("swamp") ->
                MapBiome.BENUE_RIVER_MARSH
            else ->
                MapBiome.SACRED_GROVE_IDEMILI
        }
        val seed = kotlin.math.abs(prompt.hashCode().toLong()) + System.currentTimeMillis() % 1000L
        _currentBiome.value = biome
        _currentMap.value = ProceduralMapGenerator.generateDungeon(biome = biome, seed = seed, width = 30, height = 30)
        _aiStatusMessage.value = "Generated Procedural Map: ${biome.displayName} for '$prompt'!"
        _isAiGenerating.value = false
    }

    fun generateAiAsset(prompt: String) = viewModelScope.launch {
        generateAiWeapon(prompt)
    }

    fun generateAgenticAssetBundle(prompt: String) = viewModelScope.launch {
        _isAiGenerating.value = true
        _aiStatusMessage.value = "Executing Agentic Multi-Asset Synthesis for '$prompt'..."

        // 1. Synthesize Sprite Sheet
        val spriteResult = generateSpriteWithAiUseCase(prompt)
        spriteResult.onSuccess { sheet ->
            _activeSprite.value = sheet
            spriteRepo.saveSpriteSheet(sheet)
            spriteRepo.setActiveSpriteSheetId(sheet.id)
        }

        // 2. Synthesize Map
        val lower = prompt.lowercase()
        val biome = when {
            lower.contains("storm") || lower.contains("thunder") || lower.contains("amadioha") || lower.contains("crag") ->
                MapBiome.AMADIOHA_THUNDER_PEAK
            lower.contains("catacomb") || lower.contains("crypt") || lower.contains("bronze") ->
                MapBiome.LOST_BRONZE_CATACOMBS
            lower.contains("sun") || lower.contains("fire") || lower.contains("palace") ->
                MapBiome.OZO_ROYAL_PALACE
            else ->
                MapBiome.SACRED_GROVE_IDEMILI
        }
        val seed = kotlin.math.abs(prompt.hashCode().toLong()) + System.currentTimeMillis() % 1000L
        _currentBiome.value = biome
        _currentMap.value = ProceduralMapGenerator.generateDungeon(biome = biome, seed = seed, width = 28, height = 28)

        // 3. Synthesize Weapon Asset
        val weaponResult = aiService.generateWeaponFromPrompt(prompt)
        weaponResult.onSuccess { weapon ->
            weaponRepo.saveWeapon(weapon)
            weaponRepo.equipWeapon(weapon.id)
            engine.state.hero.equippedWeapon = weapon.copy(isEquipped = true)
            _dpsReport.value = simulateDpsUseCase(weapon, engine.state.hero.attributes)
        }

        // 4. Synthesize Mechanic
        val mechResult = aiService.inventMechanicFromPrompt(prompt)
        mechResult.onSuccess { mech ->
            engine.state.activeMechanics.add(mech)
        }

        _aiStatusMessage.value = "✨ Agentic Bundle Complete! Synthesized Sprite, Map, Weapon & Mechanic for '$prompt'."
        _isAiGenerating.value = false
    }

    // ==================== DIABLO ERA & AI THEME METHODS ====================

    fun setDiabloEra(era: DiabloEra) {
        _diabloEra.value = era
        _aiStatusMessage.value = "Switched HUD to ${era.title} (${era.subtitle})"
    }

    fun setNamingSystem(system: NamingSystem) {
        _namingSystem.value = system
        _aiStatusMessage.value = "Active Naming Convention: ${system.title}"
    }

    fun applyTheme(theme: GameThemeProfile) {
        _activeTheme.value = theme
        _diabloEra.value = theme.recommendedEra
        _namingSystem.value = if (theme.isAiGenerated) NamingSystem.AI_THEMED else _namingSystem.value
        _aiStatusMessage.value = "Forged World: ${theme.themeName} (${theme.recommendedEra.title})"
    }

    fun updateCustomStyle(options: CustomStyleOptions) {
        _customStyle.value = options
    }

    fun generateAiTheme(prompt: String) = viewModelScope.launch {
        _isGeneratingTheme.value = true
        _aiStatusMessage.value = "Prompting AI Theme Weaver for '$prompt'..."
        try {
            val result = aiService.generateGameThemeFromPrompt(prompt)
            result.onSuccess { theme ->
                applyTheme(theme)
                _aiStatusMessage.value = "Universe '${theme.themeName}' dynamically generated and applied!"
            }.onFailure { err ->
                _aiStatusMessage.value = "Theme generation: ${err.message}"
            }
        } catch (e: Exception) {
            _aiStatusMessage.value = "Error: ${e.message}"
        } finally {
            _isGeneratingTheme.value = false
        }
    }

    fun pushForRawSpritesheet(prompt: String, modelId: String? = null) {
        generateRawImageSprite(prompt)
    }

    fun drinkBeltPotion(slotIndex: Int) {
        val currentCounts = _potionBeltCounts.value.toMutableList()
        if (slotIndex in currentCounts.indices && currentCounts[slotIndex] > 0) {
            currentCounts[slotIndex] -= 1
            _potionBeltCounts.value = currentCounts
            when (slotIndex) {
                0 -> {
                    engine.state.hero.currentHealth = (engine.state.hero.currentHealth + 90).coerceAtMost(engine.state.hero.maxHealth)
                    _aiStatusMessage.value = "Drank Health Potion (+90 Life)"
                }
                1 -> {
                    engine.state.hero.currentSpirit = (engine.state.hero.currentSpirit + 70).coerceAtMost(engine.state.hero.maxSpirit)
                    _aiStatusMessage.value = "Drank Mana Potion (+70 Mana)"
                }
                2 -> {
                    engine.state.hero.currentHealth = engine.state.hero.maxHealth
                    engine.state.hero.currentSpirit = engine.state.hero.maxSpirit
                    _aiStatusMessage.value = "Full Rejuvenation Potion Restored All Vitality!"
                }
                3 -> {
                    _aiStatusMessage.value = "Quaffed Swiftness Elixir! Combat speed boosted."
                }
            }
        } else {
            _aiStatusMessage.value = "Potion slot ${slotIndex + 1} is empty!"
        }
    }

    fun calculateDpsForWeapon(weapon: WeaponItem) {

        _dpsReport.value = simulateDpsUseCase(weapon, engine.state.hero.attributes)
    }

    fun getSpriteBitmap(sheet: SpriteSheetData): Bitmap? {
        return if (!sheet.rawImageUriOrBase64.isNullOrEmpty()) {
            SpriteFileTransferManager.decodeBase64ToBitmap(sheet.rawImageUriOrBase64!!)
                ?: SpriteFileTransferManager.generateProceduralSpriteSheetBitmap(sheet)
        } else {
            SpriteFileTransferManager.generateProceduralSpriteSheetBitmap(sheet)
        }
    }

    fun getMonsterBitmaps(): Map<String, Bitmap> {
        return mapOf(
            "arch_ogu_brute" to SpriteFileTransferManager.generateProceduralSpriteSheetBitmap(PreloadedSpritePacks.ENEMY_OGU_BRUTE),
            "arch_shadow_leopard" to SpriteFileTransferManager.generateProceduralSpriteSheetBitmap(PreloadedSpritePacks.ENEMY_LEOPARD_ASSASSIN),
            "arch_boss_priest" to SpriteFileTransferManager.generateProceduralSpriteSheetBitmap(PreloadedSpritePacks.ENEMY_AGBARA_HIGH_PRIEST),
            "arch_forest_mmo" to SpriteFileTransferManager.generateProceduralSpriteSheetBitmap(PreloadedSpritePacks.ENEMY_FOREST_MMO)
        )
    }

    companion object {
        fun provideFactory(
            application: Application,
            container: AppContainer = DefaultAppContainer.getInstance(application)
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(ArpgEngineViewModel::class.java)) {
                    return ArpgEngineViewModel(application, container) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
