package com.stratum.core.domain.difficulty

import com.stratum.core.domain.actor.EnemyRank
import kotlin.math.pow

/** One rung of the world-tier ladder: its tier, its Igbo name, and what the name means. */
data class Rung(val tier: Int, val name: String, val meaning: String) {
    /** "Agụ · The leopard". */
    val title: String get() = "$name · $meaning"
}

/**
 * The world tiers, named in Igbo.
 *
 * The ladder climbs from the road underfoot and the earth, through the
 * elements and the great beasts, to strength and battle, the spirits and
 * the ancestors, the sky and its lights, and a person's own chi. Past the
 * last named rung it does not stop: Ebighi ebi -- without end -- counts on
 * for as long as a build keeps up.
 *
 * Every tier also presses harder on each blow a monster lands
 * ([pressure]): a curve that starts gently and steepens, so the first
 * handful of tiers ask for a better build and the far rungs ask for a great
 * one.
 */
object TierLadder {

    val named: List<Rung> = listOf(
        "Ụzọ" to "The road",
        "Ala" to "The earth",
        "Mmiri" to "Water",
        "Ọkụ" to "Fire",
        "Ikuku" to "Wind",
        "Egbe" to "The kite",
        "Agụ" to "The leopard",
        "Enyi" to "The elephant",
        "Odum" to "The lion",
        "Eke" to "The python",
        "Ike" to "Strength",
        "Ọgụ" to "Battle",
        "Ebube" to "Awe",
        "Mmụọ" to "The spirits",
        "Ndị Ichie" to "The ancestors",
        "Agwụ" to "The restless spirit",
        "Ikenga" to "The horned will",
        "Amadioha" to "Thunder",
        "Anyanwụ" to "The sun",
        "Ọnwa" to "The moon",
        "Kpakpando" to "The stars",
        "Igwe" to "The sky",
        "Ụwa" to "The whole world",
        "Chi" to "One's own god",
    ).mapIndexed { tier, (name, meaning) -> Rung(tier, name, meaning) }

    /** The first rung past the named ones; from here the ladder counts. */
    val endless: Int get() = named.size

    /** The rung at [tier]: a named one, or Ebighi ebi and its count past the last name. */
    fun rung(tier: Int): Rung {
        val t = tier.coerceAtLeast(0)
        return named.getOrNull(t) ?: Rung(t, "Ebighi ebi ${roman(t - endless + 1)}", "Without end")
    }

    /**
     * How much harder each monster blow may land at [tier], against the
     * first rung: 1 at the road, about 2.6 at the seventeenth rung, about
     * 4 at Chi, and climbing past it.
     */
    fun pressure(tier: Int): Float {
        val t = tier.coerceAtLeast(0).toFloat()
        return 1f + 0.06f * t + 0.0025f * t * t
    }

    private fun roman(n: Int): String {
        if (n <= 0) return ""
        val parts = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var left = n
        return buildString { for ((v, s) in parts) while (left >= v) { append(s); left -= v } }
    }
}

/**
 * The most one monster blow can take from the player, set by the player's
 * level.
 *
 * A blow lands as its numbers say, but never for more than a share of the
 * player's life -- and life is what levels buy -- so a level 3 hero and a
 * level 60 hero both meet monsters that hurt them, and neither is felled by
 * one forged attack rolling high. The share grows with the monster's rank,
 * with how far above the player it stands, and with the world tier.
 */
object BlowCeiling {
    /** Share of the player's life a monster of [rank] can take in one blow, level for level, on the first rung. */
    fun rankShare(rank: EnemyRank): Float = when (rank) {
        EnemyRank.MINION -> 0.08f
        EnemyRank.ELITE -> 0.12f
        EnemyRank.CHAMPION -> 0.18f
        EnemyRank.BOSS -> 0.25f
    }

    /** Each level a monster stands above the player lets it hit this much harder; below, softer. */
    const val PER_LEVEL = 1.08f

    /**
     * The share of the player's life one blow may take: [rank]'s share,
     * scaled by [levelGap] (the monster's level less the level it was made
     * for) and the [tier]'s pressure.
     */
    fun share(rank: EnemyRank, levelGap: Int, tier: Int): Float =
        rankShare(rank) * PER_LEVEL.pow(levelGap.coerceIn(-10, 10)) * TierLadder.pressure(tier)

    /** The ceiling in points for a player with [maxLife]. */
    fun of(maxLife: Int, rank: EnemyRank, levelGap: Int, tier: Int, scale: Float = 1f): Int =
        (maxLife * share(rank, levelGap, tier) * scale).toInt().coerceAtLeast(1)
}

/**
 * A monster's level: the level of the player it was made for, plus the
 * world tier's bonus, plus a step or few for its rank -- an elite stands a
 * level above its pack, a boss three.
 */
object MonsterLevel {
    fun rankStep(rank: EnemyRank): Int = when (rank) {
        EnemyRank.MINION -> 0
        EnemyRank.ELITE -> 1
        EnemyRank.CHAMPION -> 2
        EnemyRank.BOSS -> 3
    }

    fun of(playerLevel: Int, tierBonus: Int, rank: EnemyRank): Int = playerLevel.coerceAtLeast(1) + tierBonus + rankStep(rank)

    /**
     * How far above (or below) the player a monster of [level] now stands,
     * leaving out its rank, which [BlowCeiling.rankShare] already counts: 0
     * for one made at the player's level, negative once the player has
     * outgrown it. A [level] of 0 is a body made by hand at no level: it
     * stands level with the player.
     */
    fun gap(level: Int, rank: EnemyRank, playerLevel: Int, tierBonus: Int): Int =
        if (level <= 0) 0 else level - rankStep(rank) - tierBonus - playerLevel.coerceAtLeast(1)
}
