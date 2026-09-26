package com.stratum.core.domain.difficulty

import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.stats.StatSheet
import kotlin.random.Random

/**
 * One thing a waystone does to its world: something that makes the monsters
 * worse, and what the world pays for it. Every danger comes with a reward,
 * so a player reading a waystone is weighing a trade rather than a penalty.
 */
data class WaystoneMod(
    val id: String,
    val name: String,
    /** Applied to every monster in the world. */
    val monster: List<StatModifier> = emptyList(),
    /** Applied to what the player earns there: experience, and the quantity and rarity of loot. */
    val reward: List<StatModifier> = emptyList(),
) {
    fun describe(): String = (monster.map { "Monsters: ${it.describe()}" } + reward.map { it.describe() }).joinToString("\n")
}

/** A key to a harder world: its tier, and the mods rolled on it. Consumed when opened. */
data class Waystone(val id: String, val tier: Int, val mods: List<WaystoneMod> = emptyList()) {
    init {
        require(tier >= 1) { "A waystone opens tier 1 or harder, not $tier" }
    }

    val name: String get() = "Tier $tier waystone"
}

/**
 * How hard a world is. Open-ended: there is no last tier, only the point
 * where a build stops keeping up, which is the whole of the endgame.
 *
 * Each tier makes monsters tougher, stronger and higher level, and pays in
 * experience, quantity and rarity. A waystone's mods stack on top.
 */
data class Difficulty(val tier: Int = 0, val mods: List<WaystoneMod> = emptyList()) {
    init {
        require(tier >= 0) { "World tier $tier is below the base game" }
    }

    /** Every modifier monsters here are given. */
    val monsters: StatSheet = StatSheet(tierMonsterModifiers(tier) + mods.flatMap { it.monster })

    /** Every modifier on what the player earns here. */
    val rewards: StatSheet = StatSheet(tierRewardModifiers(tier) + mods.flatMap { it.reward })

    /** Added to the player's level when monsters and their loot are rolled. */
    val monsterLevelBonus: Int get() = tier * LEVELS_PER_TIER

    val isBase: Boolean get() = tier == 0 && mods.isEmpty()

    companion object {
        val BASE = Difficulty()

        fun of(waystone: Waystone) = Difficulty(waystone.tier, waystone.mods)

        const val LEVELS_PER_TIER = 5
        const val HEALTH_PER_TIER = 0.35f
        const val DAMAGE_PER_TIER = 0.2f
        const val RARITY_PER_TIER = 0.25f
        const val QUANTITY_PER_TIER = 0.1f
        const val EXPERIENCE_PER_TIER = 0.15f

        private fun tierMonsterModifiers(tier: Int): List<StatModifier> = if (tier == 0) emptyList() else listOf(
            StatModifier(Stat.MAX_HEALTH, ModifierKind.MORE, HEALTH_PER_TIER * tier),
            StatModifier(Stat.DAMAGE, ModifierKind.MORE, DAMAGE_PER_TIER * tier),
        )

        private fun tierRewardModifiers(tier: Int): List<StatModifier> = if (tier == 0) emptyList() else listOf(
            StatModifier(Stat.ITEM_RARITY, ModifierKind.INCREASED, RARITY_PER_TIER * tier),
            StatModifier(Stat.ITEM_QUANTITY, ModifierKind.INCREASED, QUANTITY_PER_TIER * tier),
            StatModifier(Stat.EXPERIENCE_GAIN, ModifierKind.INCREASED, EXPERIENCE_PER_TIER * tier),
        )
    }
}

/** The waystone mods the engine rolls from when a pack brings none. */
object WaystoneMods {
    private fun mod(id: String, name: String, monster: StatModifier, reward: StatModifier) =
        WaystoneMod("stratum:waystone/$id", name, listOf(monster), listOf(reward))

    val standard: List<WaystoneMod> = listOf(
        mod("brutal", "Brutal", StatModifier(Stat.DAMAGE, ModifierKind.MORE, 0.3f), StatModifier(Stat.ITEM_RARITY, ModifierKind.INCREASED, 0.2f)),
        mod("hulking", "Hulking", StatModifier(Stat.MAX_HEALTH, ModifierKind.MORE, 0.4f), StatModifier(Stat.ITEM_QUANTITY, ModifierKind.INCREASED, 0.1f)),
        mod("warded", "Warded", StatModifier(Stat.RESISTANCE, ModifierKind.FLAT, 0.25f), StatModifier(Stat.EXPERIENCE_GAIN, ModifierKind.INCREASED, 0.15f)),
        mod("frenzied", "Frenzied", StatModifier(Stat.ATTACK_SPEED, ModifierKind.MORE, 0.25f), StatModifier(Stat.ITEM_RARITY, ModifierKind.INCREASED, 0.15f)),
        mod("ironclad", "Ironclad", StatModifier(Stat.ARMOUR, ModifierKind.MORE, 0.5f), StatModifier(Stat.ITEM_QUANTITY, ModifierKind.INCREASED, 0.08f)),
        mod("deadly", "Deadly", StatModifier(Stat.CRIT_CHANCE, ModifierKind.FLAT, 0.1f), StatModifier(Stat.EXPERIENCE_GAIN, ModifierKind.INCREASED, 0.1f)),
    )

    /** Rolls a waystone of [tier] from [pool]: more mods the higher it goes, never the same one twice. */
    fun roll(tier: Int, pool: List<WaystoneMod>, random: Random): Waystone {
        val count = (1 + tier / MOD_EVERY_TIERS).coerceAtMost(MAX_MODS).coerceAtMost(pool.size)
        return Waystone("waystone_${random.nextLong().toULong().toString(16)}", tier, pool.shuffled(random).take(count))
    }

    private const val MOD_EVERY_TIERS = 3
    private const val MAX_MODS = 4
}
