package com.stratum.core.domain.faction

import com.stratum.core.domain.stats.StatModifier

/** How one side regards another. Ordered from worst to best. */
enum class Stance {
    /** Attacks on sight. */
    HOSTILE,

    /** Leaves you alone, and fights back if struck. */
    NEUTRAL,

    /** Fights beside you, trades with you, opens its gates. */
    ALLIED,
}

/**
 * A rank of standing with a faction, and what reaching it grants: the
 * Last Epoch idea of a faction as a progression track, not just a colour.
 */
data class ReputationRank(
    val name: String,
    /** Standing needed to hold this rank. */
    val threshold: Int,
    /** Granted while the rank is held. */
    val modifiers: List<StatModifier> = emptyList(),
)

/**
 * A side in the world: an empire, a cult, a warband, a merchant house.
 *
 * Factions are how a lore RPG becomes a place with politics rather than a
 * field of monsters. A Warhammer-style pack is a handful of these with the
 * right relations -- everyone hostile to the one faction the player joins,
 * two of them at war with each other -- and the engine does the rest: who
 * spawns where, who attacks whom, whose cities stand in which regions.
 */
data class FactionDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val color: Long = 0xFF9A96A8,
    val glyph: String = "⚑",
    /** How this faction regards others, by faction id. Unlisted is [defaultStance]. */
    val relations: Map<String, Stance> = emptyMap(),
    val defaultStance: Stance = Stance.NEUTRAL,
    /** Where the player's standing starts: below [Reputation.HOSTILE_BELOW] they are attacked on sight. */
    val startingStanding: Int = 0,
    /** Ranks from lowest to highest threshold. */
    val ranks: List<ReputationRank> = emptyList(),
) {
    fun stanceToward(otherId: String): Stance = if (otherId == id) Stance.ALLIED else relations[otherId] ?: defaultStance

    /** The highest rank [standing] reaches, or null below the first. */
    fun rankFor(standing: Int): ReputationRank? = ranks.filter { standing >= it.threshold }.maxByOrNull { it.threshold }
}

/**
 * The player's standing with every faction, and the rules that move it.
 *
 * Standing is one number per faction. Killing a faction's people costs
 * standing with it and earns a little with everyone who hates it, which is
 * how a war between two factions pulls the player onto a side without a
 * dialogue tree saying so.
 */
data class Reputation(val standing: Map<String, Int> = emptyMap()) {

    fun of(faction: FactionDefinition): Int = standing[faction.id] ?: faction.startingStanding

    fun stanceOf(faction: FactionDefinition): Stance = stanceFor(of(faction))

    fun adjusted(factionId: String, by: Int, factions: FactionBook): Reputation {
        val faction = factions.faction(factionId) ?: return this
        return copy(standing = standing + (factionId to (of(faction) + by).coerceIn(MIN, MAX)))
    }

    /**
     * Standing after killing one of [victimFactionId]'s people: a loss with
     * them, and a smaller gain with every faction hostile to them.
     */
    fun afterKilling(victimFactionId: String, factions: FactionBook): Reputation {
        val victim = factions.faction(victimFactionId) ?: return this
        val lost = adjusted(victim.id, -KILL_PENALTY, factions)
        return factions.all
            .filter { it.id != victim.id && it.stanceToward(victim.id) == Stance.HOSTILE }
            .fold(lost) { reputation, enemy -> reputation.adjusted(enemy.id, KILL_REWARD, factions) }
    }

    /** Every rank bonus the player holds, from every faction. */
    fun modifiers(factions: FactionBook): List<StatModifier> =
        factions.all.flatMap { faction -> faction.rankFor(of(faction))?.modifiers.orEmpty() }

    companion object {
        const val HOSTILE_BELOW = -100
        const val ALLIED_FROM = 100
        const val MIN = -1000
        const val MAX = 1000
        const val KILL_PENALTY = 6
        const val KILL_REWARD = 2

        fun stanceFor(standing: Int): Stance = when {
            standing < HOSTILE_BELOW -> Stance.HOSTILE
            standing >= ALLIED_FROM -> Stance.ALLIED
            else -> Stance.NEUTRAL
        }
    }
}

/** Faction ids the engine itself uses. */
object Factions {
    /** The player's own side: their followers, their garrisons. Always allied to the player. */
    const val PLAYER = "stratum:player"
}

/**
 * Every loaded faction, and the questions the engine asks about them. Wild
 * things -- anything with no faction -- are hostile to everyone.
 */
class FactionBook(val all: List<FactionDefinition>) {

    private val byId = all.associateBy { it.id }

    fun faction(id: String?): FactionDefinition? = id?.let(byId::get)

    /** How [factionId]'s people treat the player. Unaligned monsters are always hostile. */
    fun stanceToPlayer(factionId: String?, reputation: Reputation): Stance =
        if (factionId == Factions.PLAYER) Stance.ALLIED else faction(factionId)?.let(reputation::stanceOf) ?: Stance.HOSTILE

    /** Whether [a]'s people and [b]'s people fight when they meet. Unaligned fights everything. */
    fun hostile(a: String?, b: String?): Boolean {
        val first = faction(a) ?: return true
        val second = faction(b) ?: return true
        return first.stanceToward(second.id) == Stance.HOSTILE || second.stanceToward(first.id) == Stance.HOSTILE
    }

    /** What is wrong with the loaded factions, for load-time validation. */
    fun problems(): List<String> =
        all.flatMap { faction ->
            faction.relations.keys.filter { it !in byId }.map { "faction '${faction.id}' has relations with unknown faction '$it'" }
        }

    companion object {
        val EMPTY = FactionBook(emptyList())
    }
}
