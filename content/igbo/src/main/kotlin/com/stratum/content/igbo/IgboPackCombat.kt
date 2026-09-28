package com.stratum.content.igbo

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.BossPhase
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.MonsterSkill
import com.stratum.core.domain.actor.PackMember
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.DamageTypeDefinition
import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixStat
import com.stratum.core.domain.item.AffixTier
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.ModifierRange
import com.stratum.core.domain.item.RarityStyle
import com.stratum.core.domain.item.WeaponBase

/**
 * The action RPG half of the built-in pack: what you fight, what you fight with,
 * and what falls out when it dies.
 */
internal object IgboPackCombat {

    private const val NS = "igbo"

    // Declared before anything that reads them: an object initialises top to bottom.
    private val ARMOUR_SLOTS = setOf(ItemSlot.HELM, ItemSlot.CHEST, ItemSlot.GLOVES, ItemSlot.BOOTS, ItemSlot.BELT)
    private val JEWELLERY = setOf(ItemSlot.AMULET, ItemSlot.RING)

    // ---- damage types ----------------------------------------------------

    // Only bronze and bone are stopped by armour; the gods' damage goes around it. Each leaves its mark.
    val physical = DamageTypeDefinition("$NS:physical", "Physical", 0xFFCFD8DC, "⚔", ailmentStatusId = "$NS:bleed", ailmentChance = 0.1f)
    val thunder = DamageTypeDefinition("$NS:thunder", "Amadioha's Thunder", 0xFF00E5FF, "⚡", mitigatedByArmour = false, ailmentStatusId = "$NS:shock", ailmentChance = 0.2f)
    val solar = DamageTypeDefinition("$NS:solar", "Anyanwu's Fire", 0xFFFF6D00, "☀", mitigatedByArmour = false, ailmentStatusId = "$NS:scorch", ailmentChance = 0.25f)
    val venom = DamageTypeDefinition("$NS:venom", "Idemili's Venom", 0xFF00E676, "☣", mitigatedByArmour = false, ailmentStatusId = "$NS:poison", ailmentChance = 0.3f)
    val spirit = DamageTypeDefinition("$NS:spirit", "Ancestral Spirit", 0xFFB388FF, "✦", mitigatedByArmour = false, ailmentStatusId = "$NS:ancestral_grip", ailmentChance = 0.15f)

    val damageTypes = listOf(physical, thunder, solar, venom, spirit)

    // ---- rarity naming ---------------------------------------------------

    val rarityStyles = listOf(
        RarityStyle(ItemRarity.COMMON, "Plain", 0xFFB0BEC5),
        RarityStyle(ItemRarity.UNCOMMON, "Enchanted", 0xFF42A5F5),
        RarityStyle(ItemRarity.RARE, "Sacred", 0xFFFFCA28),
        RarityStyle(ItemRarity.EPIC, "Ozo Royal", 0xFFFF7043),
        RarityStyle(ItemRarity.RELIC, "Igbo-Ukwu Artifact", 0xFF26A69A),
        RarityStyle(ItemRarity.UNIQUE, "Named by the Elders", 0xFFE65100),
        RarityStyle(ItemRarity.SET, "Ozo Regalia", 0xFF7CB342),
    )

    // ---- weapons ---------------------------------------------------------

    val weapons = listOf(
        WeaponBase(
            id = "$NS:mma_nkwu",
            tags = setOf("blade"),
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
            tags = setOf("staff", "heavy"),
            twoHanded = true,
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
            tags = setOf("staff", "caster"),
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
            tags = setOf("blade", "heavy"),
            twoHanded = true,
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
            tags = setOf("wand", "caster"),
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

    /**
     * Tiered, weakest first. The deeper tiers carry the names of deeper craft:
     * roped bronze at the surface, lost-wax casting further down, the Ozo's own
     * smiths at the bottom. Slots keep weapon numbers on weapons and plate on
     * armour, so a ring never rolls "Riveted".
     */
    val affixes = listOf(
        AffixDefinition(
            "$NS:roped", "Roped", AffixKind.PREFIX,
            listOf(
                tier(1, range(Stat.DAMAGE, 2f, 9f)),
                tier(8, range(Stat.DAMAGE, 8f, 20f), name = "Lost-Wax"),
                tier(20, range(Stat.DAMAGE, 18f, 34f), name = "Ozo-Cast"),
            ),
            group = "$NS:added_damage", slots = setOf(ItemSlot.WEAPON), local = true, weight = 160,
        ),
        AffixDefinition(
            "$NS:tempered", "Tempered", AffixKind.PREFIX,
            listOf(
                tier(3, range(Stat.DAMAGE, 0.15f, 0.35f, ModifierKind.INCREASED)),
                tier(14, range(Stat.DAMAGE, 0.35f, 0.7f, ModifierKind.INCREASED), name = "Smith-Blessed"),
            ),
            slots = setOf(ItemSlot.WEAPON), local = true, weight = 90,
        ),
        AffixDefinition(
            "$NS:ringed", "Ringed", AffixKind.PREFIX,
            listOf(
                tier(1, range(Stat.ARMOUR, 3f, 10f)),
                tier(10, range(Stat.ARMOUR, 10f, 24f), name = "Riveted"),
                tier(22, range(Stat.ARMOUR, 24f, 45f), name = "Bronze-Plated"),
            ),
            slots = ARMOUR_SLOTS + ItemSlot.OFFHAND, local = true, weight = 140,
        ),
        AffixDefinition(
            "$NS:heavy", "Weighted", AffixKind.PREFIX,
            listOf(
                tier(1, range(Stat.MAX_HEALTH, 10f, 25f)),
                tier(10, range(Stat.MAX_HEALTH, 25f, 45f), name = "Stout"),
                tier(22, range(Stat.MAX_HEALTH, 45f, 70f), name = "Titled"),
            ),
            weight = 130,
        ),
        AffixDefinition(
            "$NS:keen", "Keen", AffixKind.PREFIX,
            listOf(tier(1, range(Stat.CRIT_CHANCE, 0.02f, 0.05f)), tier(8, range(Stat.CRIT_CHANCE, 0.05f, 0.09f), name = "Honed")),
            slots = setOf(ItemSlot.WEAPON, ItemSlot.GLOVES, ItemSlot.RING), weight = 110,
        ),
        AffixDefinition(
            "$NS:quick", "Quickened", AffixKind.PREFIX,
            listOf(
                tier(1, range(Stat.ATTACK_SPEED, 0.08f, 0.15f, ModifierKind.INCREASED)),
                tier(10, range(Stat.ATTACK_SPEED, 0.15f, 0.25f, ModifierKind.INCREASED), name = "Hastened"),
            ),
            slots = setOf(ItemSlot.WEAPON, ItemSlot.GLOVES), weight = 90,
        ),
        AffixDefinition(
            "$NS:pitted", "Pitted", AffixKind.PREFIX,
            listOf(tier(1, range(Stat.MINING_SPEED, 0.15f, 0.6f, ModifierKind.INCREASED))),
            slots = setOf(ItemSlot.WEAPON, ItemSlot.GLOVES), weight = 100,
        ),
        AffixDefinition(
            "$NS:carved", "Carved", AffixKind.PREFIX,
            listOf(
                tier(4, range(Stat.DAMAGE, 0.06f, 0.12f, ModifierKind.INCREASED)),
                tier(15, range(Stat.DAMAGE, 0.12f, 0.2f, ModifierKind.INCREASED), name = "Nsibidi-Carved"),
            ),
            slots = JEWELLERY, weight = 80,
        ),
        AffixDefinition(
            "$NS:chalked", "Chalked", AffixKind.PREFIX,
            listOf(tier(2, range(Stat.MAX_RESOURCE, 8f, 15f)), tier(12, range(Stat.MAX_RESOURCE, 15f, 25f), name = "Nzu-Marked")),
            slots = JEWELLERY + ItemSlot.HELM + ItemSlot.OFFHAND, weight = 90,
        ),

        resistance("$NS:of_storms", "of Storms", thunder.id, 100),
        resistance("$NS:of_ash", "of Ash", solar.id, 100),
        resistance("$NS:of_the_grove", "of the Grove", venom.id, 100),
        resistance("$NS:of_ancestors", "of Ancestors", spirit.id, 90),
        AffixDefinition.single(
            "$NS:of_communion", "of Communion", AffixKind.SUFFIX, range(Stat.LIFE_STEAL, 0.03f, 0.12f), minItemLevel = 5, weight = 60,
            slots = setOf(ItemSlot.WEAPON, ItemSlot.RING, ItemSlot.AMULET),
        ),
        AffixDefinition.single(
            "$NS:of_judgement", "of Judgement", AffixKind.SUFFIX, range(Stat.CRIT_MULTIPLIER, 0.15f, 0.55f), minItemLevel = 7, weight = 55,
            slots = setOf(ItemSlot.WEAPON, ItemSlot.AMULET, ItemSlot.GLOVES),
        ),
        AffixDefinition.single("$NS:of_the_ozo", "of the Ozo", AffixKind.SUFFIX, range(Stat.MAX_HEALTH, 25f, 80f), minItemLevel = 9, weight = 45),
        AffixDefinition.single(
            "$NS:of_the_road", "of the Road", AffixKind.SUFFIX, range(Stat.MOVE_SPEED, 0.05f, 0.12f, ModifierKind.INCREASED), weight = 90,
            slots = setOf(ItemSlot.BOOTS),
        ),
        AffixDefinition.single(
            "$NS:of_the_market", "of the Market", AffixKind.SUFFIX, range(Stat.ITEM_RARITY, 0.08f, 0.2f, ModifierKind.INCREASED), weight = 70,
            slots = JEWELLERY + ItemSlot.BELT + ItemSlot.HELM,
        ),
        AffixDefinition.single(
            "$NS:of_the_dibia", "of the Dibia", AffixKind.SUFFIX, range(Stat.COOLDOWN_RECOVERY, 0.06f, 0.14f, ModifierKind.INCREASED),
            minItemLevel = 6, weight = 60, slots = setOf(ItemSlot.HELM, ItemSlot.AMULET, ItemSlot.OFFHAND),
        ),
    )

    private fun range(stat: Stat, min: Float, max: Float, kind: ModifierKind = ModifierKind.FLAT, damageTypeId: String? = null) =
        ModifierRange(stat, kind, min, max, damageTypeId)

    private fun tier(minItemLevel: Int, vararg ranges: ModifierRange, name: String? = null) = AffixTier(ranges.toList(), minItemLevel, name)

    private fun resistance(id: String, name: String, damageTypeId: String, weight: Int) = AffixDefinition(
        id, name, AffixKind.SUFFIX,
        listOf(
            tier(1, range(Stat.RESISTANCE, 0.08f, 0.18f, damageTypeId = damageTypeId)),
            tier(12, range(Stat.RESISTANCE, 0.18f, 0.3f, damageTypeId = damageTypeId)),
        ),
        weight = weight,
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
            modifiers = listOf(AffixStat.ATTACK_POWER.modifier(4f)),
            color = 0xFFD7A86E,
            weight = 170,
        ),
        InsertDefinition(
            id = "$NS:iron_stud",
            name = "Iron Stud",
            glyph = "🔩",
            description = "Hammered flat and set into the haft. It is not subtle.",
            modifiers = listOf(AffixStat.ARMOUR.modifier(5f)),
            color = 0xFF90A4AE,
            weight = 150,
        ),
        InsertDefinition(
            id = "$NS:palm_resin",
            name = "Palm Resin",
            glyph = "💧",
            description = "Sticky, and it makes a grip you do not have to think about.",
            modifiers = listOf(AffixStat.ATTACK_SPEED.modifier(0.12f)),
            color = 0xFFCDDC39,
            weight = 120,
        ),
        InsertDefinition(
            id = "$NS:whetted_flake",
            name = "Whetted Flake",
            glyph = "🔸",
            description = "A splinter of the edge, set back into the edge.",
            modifiers = listOf(AffixStat.CRIT_CHANCE.modifier(0.05f)),
            color = 0xFFE0E0E0,
            minItemLevel = 4,
            weight = 95,
        ),
        InsertDefinition(
            id = "$NS:mining_flint",
            name = "Mining Flint",
            glyph = "⛏\uFE0F",
            description = "For the ones who came down here to dig, not to fight.",
            modifiers = listOf(AffixStat.MINING_SPEED.modifier(0.35f)),
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
            modifiers = listOf(AffixStat.ATTACK_POWER.modifier(6f)),
            convertsToDamageTypeId = thunder.id,
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
            modifiers = listOf(AffixStat.ATTACK_POWER.modifier(6f)),
            convertsToDamageTypeId = solar.id,
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
            modifiers = listOf(AffixStat.ATTACK_POWER.modifier(5f)),
            convertsToDamageTypeId = venom.id,
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
            modifiers = listOf(AffixStat.LIFE_STEAL.modifier(0.06f)),
            convertsToDamageTypeId = spirit.id,
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
            modifiers = listOf(AffixStat.CRIT_MULTIPLIER.modifier(0.4f)),
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
            role = CombatRole.BRUTE,
            // A brute that is losing calls the others in.
            skills = listOf(MonsterSkill("$NS:war_cry", healthBelow = 0.6f)),
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
            role = CombatRole.SWARMER,
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
            role = CombatRole.RANGED,
            skills = listOf(MonsterSkill("$NS:wisp_spark")),
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
            role = CombatRole.MELEE,
            skills = listOf(MonsterSkill("$NS:revenant_spit", weight = 60)),
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
            role = CombatRole.BRUTE,
            skills = listOf(MonsterSkill("$NS:guardian_slam")),
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
            rank = EnemyRank.BOSS,
            role = CombatRole.SUPPORT,
            skills = listOf(MonsterSkill("$NS:priest_curse"), MonsterSkill("$NS:priest_mend", weight = 60)),
            phases = listOf(
                BossPhase(
                    name = "The Congregation", healthBelow = 0.6f,
                    skills = listOf(MonsterSkill("$NS:priest_curse"), MonsterSkill("$NS:agbara_judgement", weight = 80), MonsterSkill("$NS:priest_mend", weight = 40)),
                    adds = listOf(PackMember("$NS:marsh_revenant", 2)),
                    announcement = "The priest calls the drowned to his side",
                ),
                BossPhase(
                    name = "Wrath", healthBelow = 0.3f,
                    skills = listOf(MonsterSkill("$NS:agbara_judgement", weight = 120, cooldownSeconds = 5f), MonsterSkill("$NS:priest_curse")),
                    enrage = listOf(StatModifier(Stat.ATTACK_SPEED, ModifierKind.INCREASED, 0.3f)),
                    statusId = "$NS:agbara_wrath",
                    announcement = "The Agbara stops being patient",
                ),
            ),
        ),
    )
}
