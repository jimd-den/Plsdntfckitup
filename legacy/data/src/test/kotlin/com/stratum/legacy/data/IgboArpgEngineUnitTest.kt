package com.stratum.legacy.data

import com.stratum.legacy.domain.ArpgGameLoopEngine
import com.stratum.legacy.domain.BaseWeaponType
import com.stratum.legacy.domain.CombatAttributes
import com.stratum.legacy.domain.DiceType
import com.stratum.legacy.domain.InventGameMechanicUseCase
import com.stratum.legacy.domain.MechanicTriggerType
import com.stratum.legacy.domain.PnpRulebookFactory
import com.stratum.legacy.domain.SimulateCombatDpsUseCase
import com.stratum.legacy.domain.WeaponRarity
import com.stratum.legacy.domain.WeaponStatRandomizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IgboArpgEngineUnitTest {

    @Test
    fun testWeaponStatRandomizer_generatesValidAffixesAndRunes() {
        val weapon = WeaponStatRandomizer.rollRandomWeapon(
            baseType = BaseWeaponType.MMA_NKWU,
            targetRarity = WeaponRarity.LEGENDARY,
            itemLevel = 25
        )

        assertNotNull(weapon.id)
        assertTrue("Weapon min damage should be > 0", weapon.minDamage > 0)
        assertTrue("Weapon max damage should >= min damage", weapon.maxDamage >= weapon.minDamage)
        assertTrue("Attack speed should be positive", weapon.attackSpeed > 0f)
        assertEquals(WeaponRarity.LEGENDARY, weapon.rarity)
        assertEquals(BaseWeaponType.MMA_NKWU, weapon.baseType)
        assertTrue("Legendary weapon should possess affixes", weapon.affixes.isNotEmpty())
        assertNotNull("Legendary weapon should possess an engraved Nsibidi rune", weapon.nsibidiRune)
    }

    @Test
    fun testArpgGameLoopEngine_executesCombatTicksAndSkills() {
        val engine = ArpgGameLoopEngine()
        val initialHeroX = engine.state.hero.x
        val initialHeroY = engine.state.hero.y

        // Tick movement
        engine.tick(
            dtMs = 16L,
            joystickX = 1.0f,
            joystickY = 0.0f,
            isAttackPressed = false,
            isThunderSkillPressed = false,
            isSunNovaSkillPressed = false,
            isDashPressed = false
        )

        assertTrue("Hero should move horizontally on joystick input", engine.state.hero.x > initialHeroX)

        // Tick primary attack
        engine.tick(
            dtMs = 16L,
            joystickX = 0f,
            joystickY = 0f,
            isAttackPressed = true,
            isThunderSkillPressed = false,
            isSunNovaSkillPressed = false,
            isDashPressed = false
        )

        assertTrue("Hero attack cooldown should activate", engine.state.hero.attackCooldownMs > 0)

        // Tick thunder skill (Amadioha)
        engine.tick(
            dtMs = 16L,
            joystickX = 0f,
            joystickY = 0f,
            isAttackPressed = false,
            isThunderSkillPressed = true,
            isSunNovaSkillPressed = false,
            isDashPressed = false
        )

        assertTrue("Projectiles should be spawned by Amadioha skill", engine.state.projectiles.isNotEmpty())
    }

    @Test
    fun testPenAndPaperDiceRoller_rollsWithinExpectedBounds() {
        val rollD20 = PnpRulebookFactory.rollDice(DiceType.D20, modifier = 4)
        assertTrue("D20 raw roll must be between 1 and 20", rollD20.rawValue in 1..20)
        assertEquals(rollD20.rawValue + 4, rollD20.total)

        val defaultRulebook = PnpRulebookFactory.createDefaultIgboRulebook()
        assertEquals(4, defaultRulebook.classes.size)
        assertEquals(4, defaultRulebook.skillChecks.size)
        assertTrue("Setting should mention Ala Igbo", defaultRulebook.settingName.contains("Ala Igbo"))
    }

    @Test
    fun testCustomMechanicInvention_registersAndTriggers() {
        val useCase = InventGameMechanicUseCase()
        val mechanic = useCase(
            name = "Leopard Counter",
            igboName = "Mbo Agu",
            description = "Reflect 50% damage when struck",
            procChance = 0.40f,
            triggerType = MechanicTriggerType.ON_TAKE_DAMAGE
        )

        assertEquals("Leopard Counter", mechanic.name)
        assertEquals(MechanicTriggerType.ON_TAKE_DAMAGE, mechanic.triggerType)
        assertEquals(0.40f, mechanic.procChance, 0.001f)
    }

    @Test
    fun testCombatDpsSimulator_calculatesArmorMitigation() {
        val simulator = SimulateCombatDpsUseCase()
        val weapon = WeaponStatRandomizer.rollRandomWeapon(
            baseType = BaseWeaponType.IKENGA_CLEAVER,
            targetRarity = WeaponRarity.RARE,
            itemLevel = 20
        )
        val heroAttributes = CombatAttributes()

        val report = simulator(weapon, heroAttributes)

        assertTrue("Raw DPS must be > 0", report.rawDps > 0f)
        assertTrue("DPS vs medium armor must be less than raw DPS", report.dpsAgainstMediumArmor < report.rawDps)
        assertTrue("DPS vs heavy armor must be less than medium armor DPS", report.dpsAgainstHeavyArmor < report.dpsAgainstMediumArmor)
    }

    @Test
    fun testSpriteSheetData_toJsonAndFromJson_preservesFullIntegrity() {
        val original = com.stratum.legacy.domain.PreloadedSpritePacks.IGBO_OZO_WARRIOR
        val jsonString = original.toJson()

        assertNotNull("JSON string should not be null", jsonString)
        assertTrue("JSON should contain sprite name", jsonString.contains(original.name))
        assertTrue("JSON should contain animation frames", jsonString.contains("animations"))

        val deserialized = com.stratum.legacy.domain.SpriteSheetData.fromJson(jsonString)
        assertNotNull("Deserialized sprite sheet should not be null", deserialized)
        assertEquals(original.id, deserialized!!.id)
        assertEquals(original.name, deserialized.name)
        assertEquals(original.columns, deserialized.columns)
        assertEquals(original.rows, deserialized.rows)
        assertEquals(original.frameWidth, deserialized.frameWidth)
        assertEquals(original.frameHeight, deserialized.frameHeight)
        assertEquals(original.igboThemeRole, deserialized.igboThemeRole)
    }

    @Test
    fun testOpenRouterConfig_supportsAnyModelAndCustomEndpoint() {
        val config = com.stratum.legacy.domain.OpenRouterConfig(
            apiKey = "sk-or-test-key-12345",
            model = "deepseek/deepseek-chat",
            baseUrl = "https://openrouter.ai/api/v1/"
        )

        assertEquals("sk-or-test-key-12345", config.apiKey)
        assertEquals("deepseek/deepseek-chat", config.model)
        assertEquals("https://openrouter.ai/api/v1/", config.baseUrl)
    }

    @Test
    fun testArpgGameLoopEngine_hitAndStaggerMechanicsTriggerOnDamage() {
        val engine = ArpgGameLoopEngine()
        // Place a monster right in front of hero
        val hero = engine.state.hero
        hero.facingAngleRad = 0f
        val testMonster = com.stratum.legacy.domain.MonsterEntity(
            id = "test_target",
            name = "Test Target",
            archetype = com.stratum.legacy.domain.MonsterArchetype.FOREST_MMUO,
            x = hero.x + 40f,
            y = hero.y,
            maxHp = 500,
            currentHp = 500,
            attackPower = 10,
            defense = 2
        )
        engine.state.monsters.clear()
        engine.state.monsters.add(testMonster)

        // Hero attacks
        engine.tick(
            dtMs = 16L,
            joystickX = 0f,
            joystickY = 0f,
            isAttackPressed = true,
            isThunderSkillPressed = false,
            isSunNovaSkillPressed = false,
            isDashPressed = false
        )

        // Verify combat juice & hit sensations
        assertTrue("Monster HP should be reduced", testMonster.currentHp < 500)
        assertTrue("Monster hit flash should be active", testMonster.hitFlashMs > 0L)
        assertTrue("Monster stagger should be active", testMonster.staggerMs > 0L)
        assertTrue("Monster squash deform should be triggered", testMonster.scaleSquash < 1.0f)
        assertTrue("Monster knockback velocity should be applied", kotlin.math.hypot(testMonster.knockbackVx, testMonster.knockbackVy) > 0f)
        assertTrue("Screen shake should trigger on impact", engine.state.screenShakeIntensity > 0f)
        assertTrue("Hit freeze micro-pause should trigger", engine.state.hitFreezeMs > 0L)
        assertTrue("Impact sparks should be created", engine.state.impactSparks.isNotEmpty())
    }

    @Test
    fun testSpriteTransparencyPolice_removesBackgroundAndValidatesGrid() = kotlinx.coroutines.runBlocking {
        val policeService = com.stratum.legacy.data.SpriteTransparencyPoliceServiceImpl()
        val dummySheet = com.stratum.legacy.domain.SpriteSheetData(
            id = "test_sheet",
            name = "Test Sheet",
            description = "Test Description",
            frameWidth = 48,
            frameHeight = 48,
            columns = 4,
            rows = 4,
            rawImageUriOrBase64 = null,
            isCustomImport = false,
            igboThemeRole = "Ozo Bronze Warrior"
        )

        val (policedSheet, report) = policeService.policeSpriteSheet(
            sheet = dummySheet,
            autoChromaKey = true,
            tolerance = 0.15f
        )

        assertNotNull(policedSheet)
        assertNotNull(report)
        assertEquals(4, report.detectedGridCols)
        assertEquals(4, report.detectedGridRows)
        assertTrue(report.isTransparentPoliced)
    }

    @Test
    fun testFetchAiModels_sortsFreeModelsFirstAndPaidSecond() = kotlinx.coroutines.runBlocking {
        val geminiService = com.stratum.legacy.data.GeminiAiServiceImpl()
        val result = geminiService.fetchAvailableModels("https://openrouter.ai/api/v1/", "")
        assertTrue("Models result should be success", result.isSuccess)
        val models = result.getOrThrow()
        assertTrue("Models list should not be empty", models.isNotEmpty())

        val firstModel = models.first()
        assertTrue("First model in list should be free", firstModel.isFree)

        val freeCount = models.count { it.isFree }
        val paidCount = models.count { !it.isFree }
        assertTrue("Should include free models", freeCount > 0)
        assertTrue("Should include paid models", paidCount > 0)

        // Verify free models come strictly before paid models
        var encounteredPaid = false
        for (m in models) {
            if (!m.isFree) {
                encounteredPaid = true
            } else if (encounteredPaid) {
                org.junit.Assert.fail("Free model ${m.id} appeared after paid models in sorted list")
            }
        }
    }
}
