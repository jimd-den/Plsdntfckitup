package com.stratum.content.igbo

import com.stratum.core.domain.item.BaseTier
import com.stratum.core.domain.item.ItemBase
import com.stratum.core.domain.item.ItemNamePool
import com.stratum.core.domain.item.ItemSetDefinition
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.ModifierRange
import com.stratum.core.domain.item.SetBonus
import com.stratum.core.domain.item.UniqueDefinition
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier

/**
 * What the built-in pack wears: raffia and hide at the surface, bronze further
 * down, and the regalia of the titled at the bottom of it. Plus the handful of
 * named things a build can be planned around, and the words rare finds are
 * called by.
 */
internal object IgboPackGear {

    private const val NS = "igbo"

    private fun armour(value: Float) = StatModifier(Stat.ARMOUR, ModifierKind.FLAT, value)

    private fun flat(stat: Stat, min: Float, max: Float = min, damageTypeId: String? = null) =
        ModifierRange(stat, ModifierKind.FLAT, min, max, damageTypeId)

    private fun increased(stat: Stat, min: Float, max: Float = min) = ModifierRange(stat, ModifierKind.INCREASED, min, max)

    private fun more(stat: Stat, value: Float) = ModifierRange(stat, ModifierKind.MORE, value)

    // ---- bases -------------------------------------------------------------

    val bases = listOf(
        // Heads
        ItemBase("$NS:raffia_okpu", "Raffia Okpu", ItemSlot.HELM, "A woven cap. It turns rain and not much else.", setOf("light"), listOf(armour(6f))),
        ItemBase(
            "$NS:ozo_cap", "Red Cap of Title", ItemSlot.HELM, "The red cap the Ozo wear. Nobody strikes it lightly.", setOf("caster"),
            listOf(armour(10f)), implicits = listOf(flat(Stat.MAX_RESOURCE, 5f, 10f)), requiredLevel = 8, minItemLevel = 8,
        ),
        ItemBase(
            "$NS:bronze_crest", "Bronze Crest Helm", ItemSlot.HELM, "Cast in one piece, crested like a hornbill.", setOf("heavy"),
            listOf(armour(26f)), requiredLevel = 12, minItemLevel = 14,
        ),
        // Bodies
        ItemBase(
            "$NS:akwete_wrap", "Akwete Wrap", ItemSlot.CHEST, "Loom-woven cloth, tight enough to stop a thorn.", setOf("light"),
            listOf(armour(12f)), implicits = listOf(flat(Stat.MAX_HEALTH, 10f, 20f)),
        ),
        ItemBase("$NS:raffia_war_coat", "Raffia War Coat", ItemSlot.CHEST, "Layered raffia over hide. It rustles, and it holds.", emptySet(), listOf(armour(22f)), minItemLevel = 6),
        ItemBase(
            "$NS:bronze_breastplate", "Cast Bronze Breastplate", ItemSlot.CHEST, "Lost-wax bronze shaped to a chest that is long gone.", setOf("heavy"),
            listOf(armour(45f)), requiredLevel = 14, minItemLevel = 16,
        ),
        // Hands
        ItemBase("$NS:iron_bracers", "Iron Bracers", ItemSlot.GLOVES, "Smith's iron, hammered round the forearm.", setOf("heavy"), listOf(armour(8f))),
        ItemBase(
            "$NS:beaded_cuffs", "Beaded Cuffs", ItemSlot.GLOVES, "Glass beads on a hide cuff. The wrist moves freely under them.", setOf("light"),
            listOf(armour(4f)), implicits = listOf(flat(Stat.CRIT_CHANCE, 0.01f, 0.03f)),
        ),
        // Feet
        ItemBase(
            "$NS:hide_sandals", "Hide Sandals", ItemSlot.BOOTS, "Market-road sandals. They know the way.", setOf("light"),
            listOf(armour(4f)), implicits = listOf(increased(Stat.MOVE_SPEED, 0.03f, 0.06f)),
        ),
        ItemBase("$NS:bronze_greaves", "Bronze Greaves", ItemSlot.BOOTS, "Heavy, and the ground notices.", setOf("heavy"), listOf(armour(14f)), minItemLevel = 10),
        // Waists
        ItemBase(
            "$NS:jigida", "Jigida Waist Beads", ItemSlot.BELT, "Strings of beads worn at the waist, counted by whoever made them.",
            implicits = listOf(flat(Stat.MAX_HEALTH, 8f, 16f)),
        ),
        ItemBase(
            "$NS:akwete_sash", "Akwete Sash", ItemSlot.BELT, "A sash from the same looms as the wrap.", setOf("caster"),
            listOf(armour(3f)), implicits = listOf(flat(Stat.MAX_RESOURCE, 5f, 10f)),
        ),
        // Off hands
        ItemBase("$NS:hide_shield", "Hide Shield", ItemSlot.OFFHAND, "Cowhide stretched on a cane frame.", setOf("shield"), listOf(armour(16f))),
        ItemBase(
            "$NS:bronze_boss_shield", "Bronze Boss Shield", ItemSlot.OFFHAND, "A hide shield with a cast bronze boss at its heart.", setOf("shield", "heavy"),
            listOf(armour(34f), StatModifier(Stat.RESISTANCE, ModifierKind.FLAT, 0.05f, IgboPackCombat.physical.id)),
            requiredLevel = 10, minItemLevel = 12,
        ),
        ItemBase(
            "$NS:carved_ikenga", "Carved Ikenga", ItemSlot.OFFHAND, "A small horned figure of the right hand's strength. Held, not worn.", setOf("caster"),
            implicits = listOf(increased(Stat.SKILL_DAMAGE, 0.08f, 0.14f)), glyph = "🗿",
        ),
        // Necks
        ItemBase(
            "$NS:cowrie_string", "Cowrie String", ItemSlot.AMULET, "Shell money on a cord. It remembers every trade.",
            implicits = listOf(increased(Stat.ITEM_RARITY, 0.05f, 0.1f)),
        ),
        ItemBase(
            "$NS:ivory_pendant", "Ivory Pendant", ItemSlot.AMULET, "A carved tusk tip, worn by those with a title to show.",
            implicits = listOf(flat(Stat.MAX_HEALTH, 12f, 20f)), minItemLevel = 6,
        ),
        ItemBase(
            "$NS:bronze_pendant", "Roped Bronze Pendant", ItemSlot.AMULET, "The roped casting Igbo-Ukwu is known for, small enough to wear.",
            implicits = listOf(increased(Stat.DAMAGE, 0.06f, 0.1f)), requiredLevel = 12, minItemLevel = 14,
        ),
        // Fingers
        ItemBase("$NS:bronze_ring", "Bronze Ring", ItemSlot.RING, "Plain bronze. It hits back.", implicits = listOf(flat(Stat.DAMAGE, 2f, 5f))),
        ItemBase(
            "$NS:ichi_ring", "Ichi-Marked Ring", ItemSlot.RING, "Scored with the ichi lines of a titled face.",
            implicits = listOf(flat(Stat.RESISTANCE, 0.05f, 0.1f, IgboPackCombat.spirit.id)), minItemLevel = 5,
        ),
        ItemBase(
            "$NS:cowrie_ring", "Cowrie Ring", ItemSlot.RING, "A single cowrie set in brass.",
            implicits = listOf(increased(Stat.ITEM_RARITY, 0.04f, 0.08f)),
        ),
    )

    /** The rungs every base grows on: better casting, then the title-holders' own smiths. */
    val baseTiers = listOf(
        BaseTier("Riveted {base}", levelsAbove = 12),
        BaseTier("Lost-Wax {base}", levelsAbove = 24),
        BaseTier("Titled {base}", levelsAbove = 36, bonus = 1.15f),
    )

    // ---- uniques -------------------------------------------------------------

    /** The builds these ask for are the point: each one breaks a rule and charges for it. */
    val uniques = listOf(
        UniqueDefinition(
            "$NS:amadiohas_verdict", "Amadioha's Verdict", IgboPackCombat.weapons[2].id,
            modifiers = listOf(more(Stat.DAMAGE, 0.4f), increased(Stat.ATTACK_SPEED, 0.1f, 0.2f)),
            flags = setOf(BuildFlag.CANNOT_CRIT, BuildFlag.SKILLS_USE_WEAPON_TYPE),
            flavour = "Thunder does not miss, and it does not boast.", minItemLevel = 8, weight = 60,
        ),
        UniqueDefinition(
            "$NS:ogbanje_return", "The Ogbanje's Return", "$NS:ivory_pendant",
            modifiers = listOf(increased(Stat.MAX_HEALTH, 0.25f, 0.35f), more(Stat.SKILL_DAMAGE, 0.2f), more(Stat.MAX_RESOURCE, -0.5f)),
            flags = setOf(BuildFlag.SKILLS_COST_HEALTH),
            flavour = "It keeps coming back, and every time it costs someone.", minItemLevel = 10, weight = 50,
        ),
        UniqueDefinition(
            "$NS:idemilis_mirror", "Idemili's Mirror", "$NS:hide_shield",
            modifiers = listOf(flat(Stat.MAX_RESOURCE, 30f, 50f)),
            localModifiers = listOf(increased(Stat.ARMOUR, 0.5f, 0.8f)),
            flags = setOf(BuildFlag.RESOURCE_SHIELDS_HEALTH),
            flavour = "The river shows you your face, and keeps the blow meant for it.", minItemLevel = 6, weight = 60,
        ),
        UniqueDefinition(
            "$NS:anyanwus_open_hand", "Anyanwu's Open Hand", "$NS:beaded_cuffs",
            modifiers = listOf(more(Stat.ATTACK_SPEED, -0.15f), flat(Stat.DAMAGE, 6f, 12f)),
            flags = setOf(BuildFlag.HITS_IGNORE_RESISTANCE),
            flavour = "The sun does not ask what you are made of.", minItemLevel = 12, weight = 40,
        ),
        UniqueDefinition(
            "$NS:agbaras_hunger", "Agbara's Hunger", "$NS:bronze_ring",
            modifiers = listOf(flat(Stat.LIFE_STEAL, 0.05f, 0.1f), more(Stat.MAX_HEALTH, -0.2f)),
            flags = setOf(BuildFlag.LIFE_STEAL_UNCAPPED),
            flavour = "Whatever speaks through the priest is never full.", minItemLevel = 14, weight = 40,
        ),
    ) + regalia

    // ---- the set ---------------------------------------------------------------

    private const val REGALIA = "$NS:ozo_regalia"

    /** Six pieces of the title, each worth wearing alone and much more together. */
    private val regalia: List<UniqueDefinition>
        get() = listOf(
            piece("red_cap", "Red Cap of the Ozo", "$NS:ozo_cap", flat(Stat.MAX_RESOURCE, 10f, 15f)),
            piece("ceremonial_wrap", "Ozo Ceremonial Wrap", "$NS:akwete_wrap", flat(Stat.MAX_HEALTH, 20f, 30f)),
            piece("counted_beads", "Counted Waist Beads", "$NS:jigida", increased(Stat.ITEM_RARITY, 0.1f, 0.15f)),
            piece("title_ivory", "Ivory of the Title", "$NS:ivory_pendant", increased(Stat.SKILL_DAMAGE, 0.1f, 0.15f)),
            piece("anklet_sandals", "Sandals of the Procession", "$NS:hide_sandals", increased(Stat.MOVE_SPEED, 0.05f, 0.08f)),
            piece("ofo_ring", "Ring of the Ofo", "$NS:ichi_ring", flat(Stat.CRIT_CHANCE, 0.02f, 0.04f)),
        )

    private fun piece(id: String, name: String, baseId: String, range: ModifierRange) = UniqueDefinition(
        "$NS:ozo_$id", name, baseId, modifiers = listOf(range), setId = REGALIA, minItemLevel = 10, weight = 30,
        flavour = "Paid for in cows, yams and years.",
    )

    val sets = listOf(
        ItemSetDefinition(
            REGALIA, "Regalia of the Ozo",
            listOf(
                SetBonus(2, listOf(StatModifier(Stat.MAX_HEALTH, ModifierKind.INCREASED, 0.15f))),
                SetBonus(4, listOf(StatModifier(Stat.SKILL_DAMAGE, ModifierKind.MORE, 0.25f), StatModifier(Stat.ITEM_RARITY, ModifierKind.INCREASED, 0.25f))),
                SetBonus(6, listOf(StatModifier(Stat.MAX_RESOURCE, ModifierKind.MORE, 0.4f)), setOf(BuildFlag.RESOURCE_SHIELDS_HEALTH)),
            ),
            description = "What a man wears once he has paid for his title, and what the title pays him back.",
        ),
    )

    // ---- names -----------------------------------------------------------------

    private val firstWords = listOf("Storm", "Ash", "Leopard", "Python", "Kola", "Cowrie", "Thunder", "Chalk", "Bronze", "Ancestor", "River", "Masquerade")

    val namePools = listOf(
        ItemNamePool("$NS:weapon_names", firstWords, listOf("Oath", "Verdict", "Bite", "Wrath", "Song", "Tusk", "Edge", "Reckoning"), setOf(ItemSlot.WEAPON)),
        ItemNamePool(
            "$NS:armour_names", firstWords, listOf("Shelter", "Hide", "Vow", "Shroud", "Ward", "Covenant"),
            setOf(ItemSlot.OFFHAND, ItemSlot.HELM, ItemSlot.CHEST, ItemSlot.GLOVES, ItemSlot.BOOTS, ItemSlot.BELT),
        ),
        ItemNamePool("$NS:jewellery_names", firstWords, listOf("Whisper", "Promise", "Coil", "Eye", "Knot"), setOf(ItemSlot.AMULET, ItemSlot.RING)),
    )
}
