package com.stratum.legacy.domain

import kotlin.random.Random

/**
 * CHAPTER 03: DOMAIN MODEL - PEN AND PAPER RPG SYSTEM
 *
 * This literate module models traditional tabletop pen-and-paper RPG mechanics
 * intertwined with the real-time ARPG action loop. Players can import custom
 * rulebooks or use the native ancient Igbo campaign:
 * "Ọfọ & Bronze: Chronicles of the Kingdom of Nri".
 *
 * It provides dice rolling (D4, D6, D8, D10, D12, D20, D100), ability checks,
 * campaign encounters, and cross-system combat blessings.
 */

enum class DiceType(val sides: Int, val label: String) {
    D4(4, "d4"),
    D6(6, "d6"),
    D8(8, "d8"),
    D10(10, "d10"),
    D12(12, "d12"),
    D20(20, "d20"),
    D100(100, "d100")
}

data class DiceRollResult(
    val diceType: DiceType,
    val rawValue: Int,
    val modifier: Int,
    val total: Int,
    val isCriticalSuccess: Boolean = false,
    val isCriticalFailure: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

data class PnpSkillCheck(
    val id: String,
    val name: String,
    val igboTerm: String,
    val governingAttribute: String, // Ike, Uche, Ndu, Mmuo
    val difficultyClass: Int,
    val description: String,
    val successBuffEffect: String,
    val arpgCombatModifierBonus: Float // E.g., +0.25f damage, +0.15f crit chance
)

data class PnpCharacterClass(
    val name: String,
    val igboTitle: String,
    val loreRole: String,
    val primarySkill: String,
    val startingBonusDescription: String
)

data class PnpRulebook(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val settingName: String,
    val authorNotes: String,
    val systemVersion: String = "1.0",
    val classes: List<PnpCharacterClass>,
    val skillChecks: List<PnpSkillCheck>,
    val rawMarkdownNotes: String = ""
)

object PnpRulebookFactory {

    fun createDefaultIgboRulebook(): PnpRulebook {
        val classes = listOf(
            PnpCharacterClass(
                name = "Eze Nri Diviner",
                igboTitle = "Eze Nri / Dibia Afa",
                loreRole = "Spiritual mediator who commands moral balance, cosmologic harmony, and ancestral Afa divination.",
                primarySkill = "Afa Divination",
                startingBonusDescription = "+4 Mmụọ, unlocks Spirit Nova and clairvoyant enemy tracking."
            ),
            PnpCharacterClass(
                name = "Ozo War Knight",
                igboTitle = "Dike Ozo",
                loreRole = "Bearer of the heavy bronze Alo staff and sacred Ichí facial scarification symbolizing nobility and fearlessness.",
                primarySkill = "Ogu War Cry",
                startingBonusDescription = "+4 Ike, +20 base armor and 15% bonus physical cleave damage."
            ),
            PnpCharacterClass(
                name = "Ekpe Leopard Stalker",
                igboTitle = "Onye Ekpe",
                loreRole = "Initiate of the ancient Aro and Cross River leopard society, master of stealth, speed, and Nsibidi cyphers.",
                primarySkill = "Ekpe Stalking",
                startingBonusDescription = "+4 Uche, +25% dash recovery and high evasion rate."
            ),
            PnpCharacterClass(
                name = "Igbo-Ukwu Bronze Smelter",
                igboTitle = "Oka Uzu",
                loreRole = "Master smith possessing the secrets of 9th-century lost-wax casting, infusing weapons with divine bronze patinas.",
                primarySkill = "Uzu Metallurgy",
                startingBonusDescription = "+3 Ndụ, +3 Ike, unlocks custom weapon forging affixes."
            )
        )

        val checks = listOf(
            PnpSkillCheck(
                id = "check_afa",
                name = "Afa Divination",
                igboTerm = "Ịgba Afa",
                governingAttribute = "Mmụọ",
                difficultyClass = 13,
                description = "Cast divination seeds on the sacred Akwali board to read cosmic omens.",
                successBuffEffect = "Vision of Chukwu: +30% Critical Strike Chance in ARPG combat for 60s.",
                arpgCombatModifierBonus = 0.30f
            ),
            PnpSkillCheck(
                id = "check_ogu",
                name = "Ogu War Cry",
                igboTerm = "Ọgụ Ndị Dike",
                governingAttribute = "Ike",
                difficultyClass = 12,
                description = "Strike your bronze shield and chant the deeds of ancestors to shatter enemy resolve.",
                successBuffEffect = "Ancestral Might: +25% All Weapon Damage in ARPG combat for 60s.",
                arpgCombatModifierBonus = 0.25f
            ),
            PnpSkillCheck(
                id = "check_ekpe",
                name = "Ekpe Leopard Evasion",
                igboTerm = "Agụ Ọhịa",
                governingAttribute = "Uche",
                difficultyClass = 14,
                description = "Step with silent footwork into the shadow of the iroko trees.",
                successBuffEffect = "Leopard Swiftness: +35% Movement and Attack Speed in ARPG arena for 60s.",
                arpgCombatModifierBonus = 0.35f
            ),
            PnpSkillCheck(
                id = "check_ikenga",
                name = "Ikenga Consecration",
                igboTerm = "Ịkwụ Ikenga",
                governingAttribute = "Ndụ",
                difficultyClass = 15,
                description = "Offer libation to your personal horned shrine of will, fortune, and enterprise.",
                successBuffEffect = "Horn of Conquest: 15% Life Leech and +50 Max Health in ARPG arena for 60s.",
                arpgCombatModifierBonus = 0.15f
            )
        )

        return PnpRulebook(
            title = "Ọfọ & Bronze: Legends of the Kingdom of Nri",
            settingName = "Ancient Ala Igbo (9th – 15th Century)",
            authorNotes = "Pure Clean Architecture Pen & Paper rule engine integrated with real-time ARPG loops.",
            classes = classes,
            skillChecks = checks,
            rawMarkdownNotes = """
                # Ọfọ & Bronze Pen and Paper RPG
                Welcome to the sacred highlands and riverine groves of ancient Igboland.
                - Use D20 for contested actions vs DC (Difficulty Class).
                - Critical 20 on an Afa check triggers Amadioha's celestial lightning strike in the live game.
                - Attribute modifiers: (Attribute - 10) / 2.
            """.trimIndent()
        )
    }

    fun rollDice(type: DiceType, modifier: Int = 0): DiceRollResult {
        val raw = Random.nextInt(1, type.sides + 1)
        val isCritSuccess = (type == DiceType.D20 && raw == 20) || (type == DiceType.D100 && raw >= 95)
        val isCritFailure = (type == DiceType.D20 && raw == 1) || (type == DiceType.D100 && raw <= 5)
        return DiceRollResult(
            diceType = type,
            rawValue = raw,
            modifier = modifier,
            total = raw + modifier,
            isCriticalSuccess = isCritSuccess,
            isCriticalFailure = isCritFailure
        )
    }
}
