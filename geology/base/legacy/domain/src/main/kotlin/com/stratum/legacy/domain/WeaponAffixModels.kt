package com.stratum.legacy.domain

import kotlin.random.Random

/**
 * CHAPTER 02: DOMAIN MODEL - ARPG WEAPON & AFFIX GENERATION SYSTEM
 *
 * This module implements an ARPG itemization engine paired with ancient
 * Igbo metallurgy and regalia.
 *
 * Historic context:
 * - Igbo-Ukwu (9th century AD) was one of the world's most sophisticated bronze casting
 *   civilizations, centuries ahead of contemporary European metallurgy, utilizing lost-wax
 *   technique with distinctive roped and concentric filigree.
 * - The Ofo is a sacred staff of moral authority and cosmic justice.
 * - The Alo is a towering bronze war staff used by titleholders (Ozo).
 */

enum class WeaponRarity(val displayName: String, val colorHex: String, val affixCount: Int) {
    NORMAL("Plain", "#B0BEC5", 0),
    MAGIC("Enchanted", "#42A5F5", 2),
    RARE("Sacred", "#FFCA28", 4),
    LEGENDARY("Ozo Royal", "#FF7043", 5),
    ANCIENT_RELIC("Igbo-Ukwu Artifact", "#26A69A", 6);

    val colorArgb: Int = parseHexColor(colorHex, 0xFFFFCA28.toInt())
}

enum class BaseWeaponType(
    val title: String,
    val igboName: String,
    val description: String,
    val minDmgBase: Int,
    val maxDmgBase: Int,
    val baseAttackSpeed: Float, // Attacks per second
    val preferredDamageType: DamageType
) {
    OFO_SCEPTER(
        "Ofo Sacred Staff",
        "Ofo Ndu",
        "Scepter of divine truth and righteous lightning. Channels Amadioha's thunder.",
        18, 32, 1.3f, DamageType.THUNDER
    ),
    ALO_WAR_STAFF(
        "Alo Great Staff",
        "Alo Ozo",
        "Heavy bronze-ringed staff of high-ranking Ozo nobles. Powerful sweeping cleave.",
        25, 45, 1.0f, DamageType.PHYSICAL
    ),
    MMA_NKWU(
        "Bronze Palm Blade",
        "Mma Nkwu",
        "Curved bronze machete cast with intricate Igbo-Ukwu spiral filigree. Fast slashes.",
        15, 26, 1.6f, DamageType.PHYSICAL
    ),
    IKENGA_CLEAVER(
        "Ikenga Horned Cleaver",
        "Mma Ikenga",
        "Massive two-handed ceremonial executioner blade, invoking the spirit of personal conquest.",
        35, 60, 0.85f, DamageType.BRONZE_FIRE
    ),
    ASA_JAVELIN(
        "Asa Bronze Spear",
        "Asa Igbo",
        "Long thrusting spear tipped with roped bronze barb. Pierces enemy armor.",
        22, 38, 1.2f, DamageType.PHYSICAL
    ),
    ULI_SACRED_BOW(
        "Uli Painted Bow",
        "Uta Uli",
        "Recurve bow inscribed with Uli geometric dye, unleashing swift radiant arrows.",
        16, 30, 1.5f, DamageType.SACRED_NSIBIDI
    )
}

data class WeaponAffix(
    val name: String,
    val statKey: String,
    val statBonusValue: Float,
    val isPercentage: Boolean = false,
    val loreDescription: String
)

data class NsibidiRune(
    val symbolCharacter: String,
    val igboName: String,
    val meaning: String,
    val elementalBonus: String
)

data class WeaponItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val baseType: BaseWeaponType,
    val rarity: WeaponRarity,
    val itemLevel: Int,
    val minDamage: Int,
    val maxDamage: Int,
    val attackSpeed: Float,
    val critBonusChance: Float = 0.0f,
    val critDamageBonus: Float = 0.0f,
    val primaryDamageType: DamageType = BaseWeaponType.MMA_NKWU.preferredDamageType,
    val lifeLeechPercent: Float = 0.0f,
    val affixes: List<WeaponAffix> = emptyList(),
    val specialMechanic: String = "",
    val nsibidiRune: NsibidiRune? = null,
    val isEquipped: Boolean = false,
    val loreNotes: String = ""
) {
    val averageDamage: Float get() = (minDamage + maxDamage) / 2f
    val theoreticalDps: Float get() = averageDamage * attackSpeed * (1f + (critBonusChance * (1.5f + critDamageBonus)))
}

/**
 * Procedural Action RPG Randomizer for weapon stats and affixes.
 */
object WeaponStatRandomizer {

    private val PREFIXES = listOf(
        Pair("Thunder-Forged", "Amadioha's spark electrifies each strike."),
        Pair("Sun-Tempered", "Anyanwu's heat burns flesh and armor."),
        Pair("Ikenga's Fierce", "The right hand of personal triumph empowers the blade."),
        Pair("Roped Bronze", "Mastercraft casting from 9th-century Igbo-Ukwu."),
        Pair("Eze Nri's Blessed", "Conferred by the sacred peace-king of Nri."),
        Pair("Ekpe Stalker's", "Shadowed predatory agility strikes from blindness."),
        Pair("Ancestral", "Echoes of the ancients vibrate through the hilt."),
        Pair("Blood-Drinker", "Leeches vital essence on wound.")
    )

    private val SUFFIXES = listOf(
        Pair("of the Leopard (Agu)", "Increases attack speed and evasion."),
        Pair("of Thunder (Amadioha)", "Adds 20% chance to chain lightning to nearby foes."),
        Pair("of the Sun (Anyanwu)", "Inflicts fire burn over time."),
        Pair("of the Sacred Grove", "Restores spirit and health upon defeating an enemy."),
        Pair("of the Ozo Title", "Increases critical strike damage dramatically."),
        Pair("of Ala's Earth", "Grants thorns reflecting damage to attackers."),
        Pair("of Conquest", "Deals bonus damage against high-health champions.")
    )

    val NSIBIDI_RUNES = listOf(
        NsibidiRune("☩", "Ọkụ Anyanwu", "Solar Fire & Creation", "+18 Bronze Fire Damage"),
        NsibidiRune("⚡", "Egbe Amadioha", "Thunder & Celestial Judgment", "+25% Shock Chain Chance"),
        NsibidiRune("☥", "Ndụ Chukwu", "Life & Eternity", "+12% Life Leech on Hit"),
        NsibidiRune("◈", "Ala Nso", "Sacred Earth & Strength", "+20 Armor & Thorn Reflection"),
        NsibidiRune("✦", "Ikenga Ike", "Personal Power & Horned Will", "+15% Critical Strike Chance"),
        NsibidiRune("✧", "Uli Mara", "Geometric Harmony & Speed", "+25% Attack Speed")
    )

    fun rollRandomWeapon(
        baseType: BaseWeaponType = BaseWeaponType.values().random(),
        targetRarity: WeaponRarity = WeaponRarity.values().random(),
        itemLevel: Int = Random.nextInt(1, 60)
    ): WeaponItem {
        val levelFactor = 1.0f + (itemLevel * 0.08f)
        val rarityMultiplier = when (targetRarity) {
            WeaponRarity.NORMAL -> 1.0f
            WeaponRarity.MAGIC -> 1.25f
            WeaponRarity.RARE -> 1.55f
            WeaponRarity.LEGENDARY -> 1.95f
            WeaponRarity.ANCIENT_RELIC -> 2.5f
        }

        val minDmg = (baseType.minDmgBase * levelFactor * rarityMultiplier * Random.nextDouble(0.9, 1.15)).toInt()
        val maxDmg = (baseType.maxDmgBase * levelFactor * rarityMultiplier * Random.nextDouble(1.0, 1.25)).toInt().coerceAtLeast(minDmg + 5)
        val speed = (baseType.baseAttackSpeed * Random.nextDouble(0.95, 1.25)).toFloat()

        val prefix = if (targetRarity != WeaponRarity.NORMAL) PREFIXES.random() else null
        val suffix = if (targetRarity >= WeaponRarity.RARE) SUFFIXES.random() else null

        val name = buildString {
            if (prefix != null) append("${prefix.first} ")
            append(baseType.title)
            if (suffix != null) append(" ${suffix.first}")
        }

        val affixes = mutableListOf<WeaponAffix>()
        if (targetRarity >= WeaponRarity.MAGIC) {
            affixes.add(
                WeaponAffix(
                    name = prefix?.first ?: "Honed Edge",
                    statKey = "Extra Damage",
                    statBonusValue = Random.nextInt(5, 25).toFloat(),
                    isPercentage = false,
                    loreDescription = prefix?.second ?: "Sharp bronze hone."
                )
            )
        }
        if (targetRarity >= WeaponRarity.RARE) {
            affixes.add(
                WeaponAffix(
                    name = "Uche Reflex",
                    statKey = "Critical Strike Chance",
                    statBonusValue = Random.nextInt(4, 18).toFloat(),
                    isPercentage = true,
                    loreDescription = "Honed by ancient Igbo hunters."
                )
            )
            affixes.add(
                WeaponAffix(
                    name = "Ndu Leech",
                    statKey = "Life Leech",
                    statBonusValue = Random.nextInt(3, 12).toFloat(),
                    isPercentage = true,
                    loreDescription = "Converts physical harm into restorative vitality."
                )
            )
        }
        if (targetRarity >= WeaponRarity.LEGENDARY) {
            affixes.add(
                WeaponAffix(
                    name = suffix?.first ?: "Ozo Nobility",
                    statKey = "Amadioha Shockwave",
                    statBonusValue = Random.nextInt(15, 40).toFloat(),
                    isPercentage = true,
                    loreDescription = "Critical strikes summon an ancestral bolt from above."
                )
            )
        }

        val specialMechanic = when (targetRarity) {
            WeaponRarity.ANCIENT_RELIC -> "Igbo-Ukwu Consecration: Striking enemies spins a vortex of roped bronze coils, stunning targets for 1.5s."
            WeaponRarity.LEGENDARY -> "Amadioha's Verdict: Critical strikes release an arc of blue lightning to 3 surrounding enemies."
            WeaponRarity.RARE -> "Ekpe Prowess: Dashing leaves behind a spectral decoy that explodes after 1 second."
            else -> ""
        }

        val rune = if (targetRarity >= WeaponRarity.RARE) NSIBIDI_RUNES.random() else null

        return WeaponItem(
            name = name,
            baseType = baseType,
            rarity = targetRarity,
            itemLevel = itemLevel,
            minDamage = minDmg,
            maxDamage = maxDmg,
            attackSpeed = speed,
            critBonusChance = if (targetRarity >= WeaponRarity.RARE) 0.12f else 0.04f,
            critDamageBonus = if (targetRarity >= WeaponRarity.LEGENDARY) 0.5f else 0.2f,
            primaryDamageType = baseType.preferredDamageType,
            lifeLeechPercent = if (targetRarity >= WeaponRarity.RARE) 0.08f else 0.0f,
            affixes = affixes,
            specialMechanic = specialMechanic,
            nsibidiRune = rune,
            loreNotes = "Unearthed near the royal archaeological strata of ancient Nri and Igbo-Ukwu."
        )
    }
}
