package com.stratum.core.domain.crafting

import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier

/**
 * What a piece of crafting currency does to an item.
 *
 * The verbs are the engine's; a pack names and colours them. That keeps the
 * crafting system learnable -- a player who knows what a reforge does knows
 * it in every pack -- while letting a pack call it anything it likes.
 */
enum class CurrencyEffect(val verb: String) {
    /** A plain item becomes a rare one, with fresh affixes. */
    IMBUE("Imbue a common item with rare affixes"),

    /** Every affix is rolled again, keeping the rarity. */
    REFORGE("Reroll every affix"),

    /** One rarity higher, with the extra affixes that brings. */
    ASCEND("Raise the rarity, adding affixes"),

    /** The same affixes, with their values rolled again. */
    TEMPER("Reroll the values of every affix"),

    /** One affix, chosen at random, is removed. */
    ANNUL("Remove one random affix"),

    /** One more socket, up to the limit. */
    SOCKET("Add a socket"),

    /** Back to a plain item: every affix removed. Sockets and what is in them stay. */
    SCOUR("Remove every affix"),
}

data class CurrencyDefinition(
    val id: String,
    val name: String,
    val effect: CurrencyEffect,
    val description: String = effect.verb,
    val glyph: String = "◆",
    val color: Long = 0xFFE0C068,
    /** Relative drop weight: common verbs should be common. */
    val weight: Int = 100,
    val minItemLevel: Int = 1,
)

/**
 * A gem linked to a skill that changes how it is cast: bigger, faster,
 * cheaper, or a different element -- always at a price, so which supports go
 * on which skill is a decision rather than a checklist.
 *
 * Its modifiers apply to the linked skill only.
 */
data class SupportDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val modifiers: List<StatModifier> = emptyList(),
    /** Turns the skill's damage into this type, when set. */
    val convertsToDamageTypeId: String? = null,
    val glyph: String = "◈",
    val color: Long = 0xFF7FB8E0,
    val weight: Int = 100,
    val minItemLevel: Int = 1,
)

/**
 * The currency and supports the engine drops when no pack brings its own, so
 * any pack with monsters has a crafting game without having to design one.
 */
object StandardCrafting {

    private fun currency(id: String, name: String, effect: CurrencyEffect, weight: Int, glyph: String, color: Long, minItemLevel: Int = 1) =
        CurrencyDefinition("stratum:currency/$id", name, effect, glyph = glyph, color = color, weight = weight, minItemLevel = minItemLevel)

    val currencies: List<CurrencyDefinition> = listOf(
        currency("imbue", "Kindling Shard", CurrencyEffect.IMBUE, weight = 220, glyph = "✦", color = 0xFFE8B45A),
        currency("reforge", "Chaos Ember", CurrencyEffect.REFORGE, weight = 140, glyph = "✺", color = 0xFFD06A4A),
        currency("ascend", "Crown Sigil", CurrencyEffect.ASCEND, weight = 45, glyph = "♛", color = 0xFFF0D070, minItemLevel = 5),
        currency("temper", "Tempering Salt", CurrencyEffect.TEMPER, weight = 70, glyph = "❖", color = 0xFFB8D8E8),
        currency("annul", "Hollow Coin", CurrencyEffect.ANNUL, weight = 35, glyph = "◌", color = 0xFFA0A0B8, minItemLevel = 10),
        currency("socket", "Jeweller's Bead", CurrencyEffect.SOCKET, weight = 120, glyph = "◉", color = 0xFF70C8B0),
        currency("scour", "Scouring Ash", CurrencyEffect.SCOUR, weight = 90, glyph = "☁", color = 0xFF9A9088),
    )

    private fun more(stat: Stat, value: Float) = StatModifier(stat, ModifierKind.MORE, value)

    val supports: List<SupportDefinition> = listOf(
        SupportDefinition(
            "stratum:support/brutality", "Brutality", "Hits much harder, costs much more.",
            listOf(more(Stat.SKILL_DAMAGE, 0.35f), more(Stat.RESOURCE_COST, 0.3f)), color = 0xFFD05A4A,
        ),
        SupportDefinition(
            "stratum:support/wide-reach", "Wide Reach", "Reaches far wider, lands a little lighter.",
            listOf(more(Stat.AREA, 0.4f), more(Stat.SKILL_DAMAGE, -0.1f)), color = 0xFF5AB0D0,
        ),
        SupportDefinition(
            "stratum:support/swiftcast", "Swiftcast", "Comes back sooner, lands a little lighter.",
            listOf(more(Stat.COOLDOWN_RECOVERY, 0.4f), more(Stat.SKILL_DAMAGE, -0.1f)), color = 0xFF70D090, minItemLevel = 3,
        ),
        SupportDefinition(
            "stratum:support/efficiency", "Efficiency", "Costs far less, recovers a little slower.",
            listOf(more(Stat.RESOURCE_COST, -0.35f), more(Stat.COOLDOWN_RECOVERY, -0.1f)), color = 0xFFC0C070,
        ),
        SupportDefinition(
            "stratum:support/concentrate", "Concentrate", "A narrower blow that hits far harder.",
            listOf(more(Stat.SKILL_DAMAGE, 0.3f), more(Stat.AREA, -0.3f)), color = 0xFFB070D0, minItemLevel = 8,
        ),
    )

    /** How many supports one skill can hold. Three, not six: a phone screen, not a spreadsheet. */
    const val MAX_SUPPORTS_PER_SKILL = 3

    /** The most sockets currency can give an item. */
    const val MAX_SOCKETS = 6
}
