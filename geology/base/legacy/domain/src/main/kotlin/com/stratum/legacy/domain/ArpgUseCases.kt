package com.stratum.legacy.domain

/**
 * CHAPTER 07: DOMAIN USE CASES - ARPG CRAFTING, MECHANIC INVENTIONS & CHECKS
 *
 * This literate module provides business logic interactors adhering strictly
 * to the Clean Architecture Single Responsibility Principle.
 */

class RollRandomWeaponUseCase(
    private val weaponRepository: WeaponRepository
) {
    suspend operator fun invoke(
        baseType: BaseWeaponType? = null,
        rarity: WeaponRarity? = null,
        itemLevel: Int = 10,
        autoSaveToInventory: Boolean = true
    ): WeaponItem {
        val weapon = WeaponStatRandomizer.rollRandomWeapon(
            baseType = baseType ?: BaseWeaponType.values().random(),
            targetRarity = rarity ?: WeaponRarity.values().random(),
            itemLevel = itemLevel
        )
        if (autoSaveToInventory) {
            weaponRepository.saveWeapon(weapon)
        }
        return weapon
    }
}

class InventGameMechanicUseCase {
    operator fun invoke(
        name: String,
        igboName: String,
        description: String,
        procChance: Float,
        triggerType: MechanicTriggerType,
        numericParameter: Float = 1.0f
    ): CustomGameMechanic {
        return CustomGameMechanic(
            id = "custom_mech_${System.currentTimeMillis()}",
            name = name.trim().ifEmpty { "Ancestral Echo" },
            igboName = igboName.trim().ifEmpty { "Olu Ndi Gboo" },
            description = description.trim().ifEmpty { "Deals 25% bonus holy damage on impact." },
            isEnabled = true,
            procChance = procChance.coerceIn(0.05f, 1.0f),
            triggerType = triggerType,
            numericParameter = numericParameter
        )
    }
}

class ImportSpriteSheetUseCase(
    private val spriteSheetRepository: SpriteSheetRepository
) {
    suspend operator fun invoke(
        name: String,
        description: String,
        frameWidth: Int,
        frameHeight: Int,
        columns: Int,
        rows: Int,
        imageUriOrBase64: String?,
        igboRole: String = "Custom Warrior"
    ): SpriteSheetData {
        val sheet = SpriteSheetData(
            name = name.trim().ifEmpty { "Imported Sprite" },
            description = description.trim().ifEmpty { "Custom player sprite sheet" },
            frameWidth = frameWidth.coerceIn(16, 256),
            frameHeight = frameHeight.coerceIn(16, 256),
            columns = columns.coerceIn(1, 16),
            rows = rows.coerceIn(1, 16),
            isCustomImport = true,
            rawImageUriOrBase64 = imageUriOrBase64,
            igboThemeRole = igboRole
        )
        spriteSheetRepository.saveSpriteSheet(sheet)
        return sheet
    }
}

class ExecutePnpCheckUseCase(
    private val pnpRuleRepository: PnpRuleRepository
) {
    suspend operator fun invoke(
        skillCheck: PnpSkillCheck,
        attributeModifier: Int
    ): Pair<DiceRollResult, Boolean> {
        val roll = PnpRulebookFactory.rollDice(DiceType.D20, attributeModifier)
        val isSuccess = roll.isCriticalSuccess || (!roll.isCriticalFailure && roll.total >= skillCheck.difficultyClass)
        return Pair(roll, isSuccess)
    }
}

typealias DpsSimulationReport = SimulateCombatDpsUseCase.SimulationReport

class SimulateCombatDpsUseCase {
    data class SimulationReport(
        val baseDamageAvg: Float,
        val attacksPerSecond: Float,
        val critChancePercent: Float,
        val critDamageMultiplier: Float,
        val rawDps: Float,
        val dpsAgainstMediumArmor: Float,
        val dpsAgainstHeavyArmor: Float,
        val lifeLeechPerSecond: Float,
        val formulaExplanation: String
    )

    operator fun invoke(weapon: WeaponItem, heroAttributes: CombatAttributes): SimulationReport {
        val totalCritChance = (heroAttributes.critChance + weapon.critBonusChance).coerceIn(0f, 1f)
        val totalCritMultiplier = heroAttributes.critMultiplier + weapon.critDamageBonus
        val avgDmg = weapon.averageDamage
        val aps = weapon.attackSpeed

        val rawDps = avgDmg * aps * (1f + (totalCritChance * (totalCritMultiplier - 1f)))

        // Medium armor mitigation (25 armor ~ 20% reduction)
        val dpsMed = rawDps * 0.80f
        // Heavy armor mitigation (60 armor ~ 45% reduction)
        val dpsHeavy = rawDps * 0.55f

        val leechPerSecond = rawDps * weapon.lifeLeechPercent

        val explanation = "ARPG combat formula: (MinDmg + MaxDmg)/2 * APS * (1 + (CritChance * (CritMult - 1)))"

        return SimulationReport(
            baseDamageAvg = avgDmg,
            attacksPerSecond = aps,
            critChancePercent = totalCritChance * 100f,
            critDamageMultiplier = totalCritMultiplier,
            rawDps = rawDps,
            dpsAgainstMediumArmor = dpsMed,
            dpsAgainstHeavyArmor = dpsHeavy,
            lifeLeechPerSecond = leechPerSecond,
            formulaExplanation = explanation
        )
    }
}

class GenerateSpriteWithAiUseCase(
    private val aiService: AiGameMechanicGeneratorService,
    private val spriteSheetRepository: SpriteSheetRepository
) {
    suspend operator fun invoke(prompt: String): Result<SpriteSheetData> {
        val result = aiService.generateSpriteFromPrompt(prompt)
        result.onSuccess { sheet ->
            spriteSheetRepository.saveSpriteSheet(sheet)
        }
        return result
    }
}

class ExportSpriteSheetToJsonUseCase {
    operator fun invoke(sheet: SpriteSheetData): String {
        return sheet.toJson()
    }
}

class ImportSpriteSheetFromJsonUseCase(
    private val spriteSheetRepository: SpriteSheetRepository
) {
    suspend operator fun invoke(json: String): Result<SpriteSheetData> {
        val sheet = SpriteSheetData.fromJson(json)
            ?: return Result.failure(IllegalArgumentException("Invalid Sprite Sheet JSON format"))
        spriteSheetRepository.saveSpriteSheet(sheet)
        return Result.success(sheet)
    }
}

class FetchAiModelsUseCase(
    private val aiService: AiGameMechanicGeneratorService
) {
    suspend operator fun invoke(baseUrl: String, apiKey: String): Result<List<AiModelItem>> {
        return aiService.fetchAvailableModels(baseUrl, apiKey)
    }
}

class PoliceSpriteSheetUseCase(
    private val policeService: SpriteTransparencyPoliceService,
    private val spriteSheetRepository: SpriteSheetRepository
) {
    suspend operator fun invoke(
        sheet: SpriteSheetData,
        autoChromaKey: Boolean = true,
        tolerance: Float = 0.15f
    ): Pair<SpriteSheetData, SpritePoliceReport> {
        val (policedSheet, report) = policeService.policeSpriteSheet(sheet, autoChromaKey, tolerance)
        spriteSheetRepository.saveSpriteSheet(policedSheet)
        return Pair(policedSheet, report)
    }
}

class GenerateRawImageSpriteSheetUseCase(
    private val aiService: AiGameMechanicGeneratorService,
    private val spriteSheetRepository: SpriteSheetRepository
) {
    suspend operator fun invoke(prompt: String, modelId: String? = null): Result<SpriteSheetData> {
        val result = aiService.generateRawImageSpriteSheet(prompt, modelId)
        result.onSuccess { sheet ->
            spriteSheetRepository.saveSpriteSheet(sheet)
        }
        return result
    }
}

class ToggleGamePauseUseCase(
    private val engine: ArpgGameLoopEngine
) {
    operator fun invoke(): Boolean {
        return engine.togglePause()
    }

    fun setPaused(paused: Boolean) {
        if (paused) engine.pause() else engine.resume()
    }
}

