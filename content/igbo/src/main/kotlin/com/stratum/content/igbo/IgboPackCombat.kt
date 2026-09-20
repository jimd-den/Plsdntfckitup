package com.stratum.content.igbo

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillShape
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.DamageTypeDefinition
import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixStat
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.RarityStyle
import com.stratum.core.domain.item.WeaponBase

/**
 * The action RPG half of the built-in pack: what you fight, what you fight with,
 * and what falls out when it dies.
 */
internal object IgboPackCombat {

    private const val NS = "igbo"

    // ---- damage types ----------------------------------------------------

    val physical = DamageTypeDefinition("$NS:physical", "Physical", 0xFFCFD8DC, "⚔")
    val thunder = DamageTypeDefinition("$NS:thunder", "Amadioha's Thunder", 0xFF00E5FF, "⚡")
    val solar = DamageTypeDefinition("$NS:solar", "Anyanwu's Fire", 0xFFFF6D00, "☀")
    val venom = DamageTypeDefinition("$NS:venom", "Idemili's Venom", 0xFF00E676, "☣")
    val spirit = DamageTypeDefinition("$NS:spirit", "Ancestral Spirit", 0xFFB388FF, "✦")

    val damageTypes = listOf(physical, thunder, solar, venom, spirit)

    // ---- rarity naming ---------------------------------------------------

    val rarityStyles = listOf(
        RarityStyle(ItemRarity.COMMON, "Plain", 0xFFB0BEC5),
        RarityStyle(ItemRarity.UNCOMMON, "Enchanted", 0xFF42A5F5),
        RarityStyle(ItemRarity.RARE, "Sacred", 0xFFFFCA28),
        RarityStyle(ItemRarity.EPIC, "Ozo Royal", 0xFFFF7043),
        RarityStyle(ItemRarity.RELIC, "Igbo-Ukwu Artifact", 0xFF26A69A),
    )

    // ---- weapons ---------------------------------------------------------

    val weapons = listOf(
        WeaponBase(
            id = "$NS:mma_nkwu",
            name = "Mma Nkwu",
            glyph = "🔪",
            description = "A curved bronze machete cast with spiral filigree. Fast, and it digs.",
            minDamage = 9,
            maxDamage = 15,
            attackSpeed = 1.6f,
            attackRange = 1,
            damageTypeId = physical.id,
            toolTier = 1,
            minItemLevel = 1,
            weight = 160,
        ),
        WeaponBase(
            id = "$NS:alo_staff",
            name = "Alo War Staff",
            glyph = "🔱",
            description = "The ringed bronze staff of a titled man. Slow, heavy, and long.",
            minDamage = 18,
            maxDamage = 30,
            attackSpeed = 0.9f,
            attackRange = 2,
            damageTypeId = physical.id,
            toolTier = 2,
            minItemLevel = 4,
            weight = 110,
        ),
        WeaponBase(
            id = "$NS:ofo_scepter",
            name = "Ofo Scepter",
            glyph = "🔮",
            description = "A staff of moral authority. It answers an argument with lightning.",
            minDamage = 12,
            maxDamage = 22,
            attackSpeed = 1.3f,
            attackRange = 2,
            damageTypeId = thunder.id,
            toolTier = 2,
            minItemLevel = 6,
            weight = 80,
        ),
        WeaponBase(
            id = "$NS:ikenga_cleaver",
            name = "Ikenga Cleaver",
            glyph = "⚔\uFE0F",
            description = "A two-handed ceremonial blade, horned like the shrine it came from.",
            minDamage = 26,
            maxDamage = 44,
            attackSpeed = 0.75f,
            attackRange = 1,
            damageTypeId = solar.id,
            toolTier = 3,
            minItemLevel = 10,
            weight = 55,
        ),
        WeaponBase(
            id = "$NS:nzu_wand",
            name = "Nzu Chalk Wand",
            glyph = "✨",
            description = "White chalk bound in raffia. What it marks, it can also unmake.",
            minDamage = 10,
            maxDamage = 18,
            attackSpeed = 1.45f,
            attackRange = 3,
            damageTypeId = venom.id,
            toolTier = 1,
            minItemLevel = 3,
            weight = 95,
        ),
    )

    // ---- affixes ---------------------------------------------------------

    val affixes = listOf(
        AffixDefinition("$NS:roped", "Roped", AffixKind.PREFIX, AffixStat.ATTACK_POWER, 2f, 9f, weight = 160),
        AffixDefinition("$NS:cast", "Lost-Wax", AffixKind.PREFIX, AffixStat.ATTACK_POWER, 8f, 20f, minItemLevel = 8, weight = 70),
        AffixDefinition("$NS:ringed", "Ringed", AffixKind.PREFIX, AffixStat.ARMOUR, 2f, 8f, weight = 140),
        AffixDefinition("$NS:heavy", "Weighted", AffixKind.PREFIX, AffixStat.MAX_HEALTH, 10f, 40f, weight = 130),
        AffixDefinition("$NS:keen", "Keen", AffixKind.PREFIX, AffixStat.CRIT_CHANCE, 0.02f, 0.09f, weight = 110),
        AffixDefinition("$NS:quick", "Quickened", AffixKind.PREFIX, AffixStat.ATTACK_SPEED, 0.08f, 0.25f, weight = 90),
        AffixDefinition("$NS:pitted", "Pitted", AffixKind.PREFIX, AffixStat.MINING_SPEED, 0.15f, 0.6f, weight = 100),

        AffixDefinition("$NS:of_storms", "of Storms", AffixKind.SUFFIX, AffixStat.RESISTANCE, 0.08f, 0.3f, damageTypeId = thunder.id, weight = 100),
        AffixDefinition("$NS:of_ash", "of Ash", AffixKind.SUFFIX, AffixStat.RESISTANCE, 0.08f, 0.3f, damageTypeId = solar.id, weight = 100),
        AffixDefinition("$NS:of_the_grove", "of the Grove", AffixKind.SUFFIX, AffixStat.RESISTANCE, 0.08f, 0.3f, damageTypeId = venom.id, weight = 100),
        AffixDefinition("$NS:of_ancestors", "of Ancestors", AffixKind.SUFFIX, AffixStat.RESISTANCE, 0.08f, 0.3f, damageTypeId = spirit.id, weight = 90),
        AffixDefinition("$NS:of_communion", "of Communion", AffixKind.SUFFIX, AffixStat.LIFE_STEAL, 0.03f, 0.12f, minItemLevel = 5, weight = 60),
        AffixDefinition("$NS:of_judgement", "of Judgement", AffixKind.SUFFIX, AffixStat.CRIT_MULTIPLIER, 0.15f, 0.55f, minItemLevel = 7, weight = 55),
        AffixDefinition("$NS:of_the_ozo", "of the Ozo", AffixKind.SUFFIX, AffixStat.MAX_HEALTH, 25f, 80f, minItemLevel = 9, weight = 45),
    )

    // ---- inserts ---------------------------------------------------------

    /**
     * What goes in the sockets. Three families on purpose: ogu beads add a
     * number, nzu chalk converts what the weapon deals, and Igbo-Ukwu bronze is
     * the late-game tier that does both jobs harder.
     *
     * The converting ones are the reason to keep a weapon you have outgrown:
     * the blade is a frame, and the element is the part you re-decide.
     */
    val inserts = listOf(
        InsertDefinition(
            id = "$NS:ogu_bead",
            name = "Ogu Bead",
            glyph = "📿",
            description = "Carved from a staff of truth. Quiet, and it hits harder than it looks.",
            stat = AffixStat.ATTACK_POWER,
            value = 4f,
            color = 0xFFD7A86E,
            weight = 170,
        ),
        InsertDefinition(
            id = "$NS:iron_stud",
            name = "Iron Stud",
            glyph = "🔩",
            description = "Hammered flat and set into the haft. It is not subtle.",
            stat = AffixStat.ARMOUR,
            value = 5f,
            color = 0xFF90A4AE,
            weight = 150,
        ),
        InsertDefinition(
            id = "$NS:palm_resin",
            name = "Palm Resin",
            glyph = "💧",
            description = "Sticky, and it makes a grip you do not have to think about.",
            stat = AffixStat.ATTACK_SPEED,
            value = 0.12f,
            color = 0xFFCDDC39,
            weight = 120,
        ),
        InsertDefinition(
            id = "$NS:whetted_flake",
            name = "Whetted Flake",
            glyph = "🔸",
            description = "A splinter of the edge, set back into the edge.",
            stat = AffixStat.CRIT_CHANCE,
            value = 0.05f,
            color = 0xFFE0E0E0,
            minItemLevel = 4,
            weight = 95,
        ),
        InsertDefinition(
            id = "$NS:mining_flint",
            name = "Mining Flint",
            glyph = "⛏\uFE0F",
            description = "For the ones who came down here to dig, not to fight.",
            stat = AffixStat.MINING_SPEED,
            value = 0.35f,
            color = 0xFFA1887F,
            weight = 110,
        ),

        // Converters. Each carries its own damage as attack power too, so
        // switching your element is never a straight downgrade.
        InsertDefinition(
            id = "$NS:thunder_shard",
            name = "Thunder Shard",
            glyph = "⚡",
            description = "Amadioha's ram struck a rock and this is what was left standing.",
            stat = AffixStat.ATTACK_POWER,
            value = 6f,
            damageTypeId = thunder.id,
            convertsDamageType = true,
            tier = 2,
            color = 0xFF00E5FF,
            minItemLevel = 3,
            weight = 80,
        ),
        InsertDefinition(
            id = "$NS:sun_ember",
            name = "Sun Ember",
            glyph = "☀\uFE0F",
            description = "Anyanwu's light, kept in a bead that has not cooled since.",
            stat = AffixStat.ATTACK_POWER,
            value = 6f,
            damageTypeId = solar.id,
            convertsDamageType = true,
            tier = 2,
            color = 0xFFFF6D00,
            minItemLevel = 3,
            weight = 80,
        ),
        InsertDefinition(
            id = "$NS:venom_pearl",
            name = "Venom Pearl",
            glyph = "🌿",
            description = "Idemili's river keeps what it swallows, and sometimes gives it back.",
            stat = AffixStat.ATTACK_POWER,
            value = 5f,
            damageTypeId = venom.id,
            convertsDamageType = true,
            tier = 2,
            color = 0xFF00E676,
            minItemLevel = 3,
            weight = 80,
        ),
        InsertDefinition(
            id = "$NS:ancestor_nzu",
            name = "Ancestor Nzu",
            glyph = "👻",
            description = "White chalk pressed into the socket. What you swing, they swing.",
            stat = AffixStat.LIFE_STEAL,
            value = 0.06f,
            damageTypeId = spirit.id,
            convertsDamageType = true,
            tier = 3,
            color = 0xFFB388FF,
            minItemLevel = 6,
            weight = 45,
        ),
        InsertDefinition(
            id = "$NS:igbo_ukwu_bronze",
            name = "Igbo-Ukwu Bronze",
            glyph = "🏺",
            description = "Cast a thousand years ago by someone who expected it to outlast you.",
            stat = AffixStat.CRIT_MULTIPLIER,
            value = 0.4f,
            tier = 3,
            color = 0xFF26A69A,
            minItemLevel = 9,
            weight = 30,
        ),
    )

    // ---- monsters --------------------------------------------------------

    val enemies = listOf(
        EnemyDefinition(
            id = "$NS:ogu_brute",
            name = "Ogu Brute",
            description = "A grudge that outlived the man who held it, and got heavier.",
            baseStats = CombatStats(maxHealth = 48, attackPower = 7, armour = 2, attackSpeed = 0.8f, attackRange = 1),
            damageTypeId = physical.id,
            moveSpeed = 2.0f,
            aggroRange = 9,
            experience = 14,
            spawnWeight = 160,
            bodyColor = 0xFF8C4A3A,
        ),
        EnemyDefinition(
            id = "$NS:shadow_leopard",
            name = "Shadow Leopard",
            description = "Fast, and it runs when it is losing. It comes back.",
            baseStats = CombatStats(maxHealth = 32, attackPower = 9, armour = 0, critChance = 0.15f, attackSpeed = 1.5f, attackRange = 1),
            damageTypeId = physical.id,
            moveSpeed = 3.6f,
            aggroRange = 12,
            fleeBelowHealth = 0.25f,
            canFlee = true,
            experience = 18,
            spawnWeight = 120,
            bonusDropChance = 0.08f,
            bodyColor = 0xFF37474F,
        ),
        EnemyDefinition(
            id = "$NS:storm_wisp",
            name = "Storm Wisp",
            description = "Charge with nowhere to go, looking for the shortest path to ground.",
            baseStats = CombatStats(maxHealth = 26, attackPower = 12, attackSpeed = 1.1f, attackRange = 3, resistances = mapOf("$NS:thunder" to 0.6f)),
            damageTypeId = thunder.id,
            moveSpeed = 2.8f,
            aggroRange = 11,
            experience = 20,
            spawnBiomeIds = listOf(IgboPackBiomes.thunderPeak.id, IgboPackBiomes.ozoCourtyard.id),
            spawnWeight = 110,
            bodyColor = 0xFF00E5FF,
        ),
        EnemyDefinition(
            id = "$NS:marsh_revenant",
            name = "Marsh Revenant",
            description = "Someone the river kept. It is not finished being angry about it.",
            baseStats = CombatStats(maxHealth = 60, attackPower = 8, armour = 4, attackSpeed = 0.7f, attackRange = 1, resistances = mapOf("$NS:venom" to 0.5f)),
            damageTypeId = venom.id,
            moveSpeed = 1.6f,
            aggroRange = 8,
            experience = 22,
            spawnBiomeIds = listOf(IgboPackBiomes.mistMarsh.id, IgboPackBiomes.sacredGrove.id),
            spawnWeight = 100,
            bodyColor = 0xFF33691E,
        ),
        EnemyDefinition(
            id = "$NS:catacomb_guardian",
            name = "Catacomb Guardian",
            description = "Bronze that was cast to stand watch and never told it could stop.",
            baseStats = CombatStats(maxHealth = 95, attackPower = 14, armour = 8, attackSpeed = 0.6f, attackRange = 2, resistances = mapOf("$NS:physical" to 0.35f)),
            damageTypeId = solar.id,
            moveSpeed = 1.4f,
            aggroRange = 7,
            experience = 45,
            spawnBiomeIds = listOf(IgboPackBiomes.bronzeCatacombs.id),
            spawnWeight = 70,
            bonusDropChance = 0.2f,
            bodyColor = 0xFFCD7F32,
        ),
        EnemyDefinition(
            id = "$NS:agbara_priest",
            name = "Agbara High Priest",
            description = "Speaks for something that does not need him, and knows it.",
            baseStats = CombatStats(maxHealth = 130, attackPower = 18, armour = 5, critChance = 0.2f, attackSpeed = 0.9f, attackRange = 4, resistances = mapOf("$NS:spirit" to 0.5f)),
            damageTypeId = spirit.id,
            moveSpeed = 1.8f,
            aggroRange = 13,
            experience = 90,
            spawnWeight = 25,
            bonusDropChance = 0.35f,
            bodyColor = 0xFFB388FF,
        ),
    )

    // ---- skills ----------------------------------------------------------

    val skills = listOf(
        SkillDefinition(
            id = "$NS:mma_nkwu_cleave",
            name = "Mma Nkwu Cleave",
            description = "A wide arc that catches everything standing too close.",
            damageTypeId = physical.id,
            powerMultiplier = 1.4f,
            resourceCost = 15,
            cooldownSeconds = 3f,
            shape = SkillShape.NOVA,
            range = 3,
            color = 0xFFCFD8DC,
        ),
        SkillDefinition(
            id = "$NS:ikenga_tremor",
            name = "Ikenga Tremor",
            description = "One downward strike. The ground carries the rest.",
            damageTypeId = solar.id,
            powerMultiplier = 2.6f,
            resourceCost = 30,
            cooldownSeconds = 7f,
            shape = SkillShape.STRIKE,
            range = 2,
            color = 0xFFFF6D00,
        ),
        SkillDefinition(
            id = "$NS:thunder_spear",
            name = "Amadioha Thunder Spear",
            description = "A line of charge, delivered without appeal.",
            damageTypeId = thunder.id,
            powerMultiplier = 2.1f,
            resourceCost = 25,
            cooldownSeconds = 5f,
            shape = SkillShape.LANCE,
            range = 7,
            color = 0xFF00E5FF,
        ),
        SkillDefinition(
            id = "$NS:shockwave_spark",
            name = "Shockwave Spark",
            description = "A short discharge that clears breathing room.",
            damageTypeId = thunder.id,
            powerMultiplier = 1.2f,
            resourceCost = 12,
            cooldownSeconds = 2.5f,
            shape = SkillShape.NOVA,
            range = 4,
            color = 0xFF80D8FF,
        ),
        SkillDefinition(
            id = "$NS:venom_geyser",
            name = "Idemili Venom Geyser",
            description = "The ground opens and returns what was poured into it.",
            damageTypeId = venom.id,
            powerMultiplier = 1.9f,
            resourceCost = 22,
            cooldownSeconds = 6f,
            shape = SkillShape.NOVA,
            range = 5,
            color = 0xFF00E676,
        ),
        SkillDefinition(
            id = "$NS:solar_supernova",
            name = "Anyanwu Supernova",
            description = "Everything within reach is judged at once.",
            damageTypeId = solar.id,
            powerMultiplier = 3.2f,
            resourceCost = 45,
            cooldownSeconds = 12f,
            shape = SkillShape.NOVA,
            range = 6,
            color = 0xFFFFB300,
        ),
    )
}
