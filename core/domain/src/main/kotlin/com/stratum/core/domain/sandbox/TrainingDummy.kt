package com.stratum.core.domain.sandbox

import com.stratum.core.domain.combat.CombatStats

/**
 * A target that stands still, never answers back, and has exactly the
 * defences asked for: how a build is measured against armour, evasion, a
 * shield or a wall of resistance without anything else in the fight moving.
 */
data class DummySpec(
    val life: Int = STURDY_LIFE,
    val armour: Int = 0,
    val evasion: Int = 0,
    /** 0..1. */
    val blockChance: Float = 0f,
    /** Resistance to every damage type, before any type named in [resistances]. */
    val resistance: Float = 0f,
    val resistances: Map<String, Float> = emptyMap(),
) {
    init {
        require(life >= 1) { "a training dummy needs life to lose" }
        require(armour >= 0 && evasion >= 0) { "a training dummy's defences cannot be negative" }
        require(blockChance in 0f..1f) { "block chance $blockChance is not a share" }
    }

    /** The dummy's numbers, with [resistance] spread over every type in [damageTypeIds]. */
    fun stats(damageTypeIds: Collection<String>): CombatStats = CombatStats(
        maxHealth = life,
        attackPower = 0,
        armour = armour,
        critChance = 0f,
        attackSpeed = 0f,
        attackRange = 0,
        resistances = (damageTypeIds.associateWith { resistance } + resistances).filterValues { it != 0f },
        evasion = evasion,
        blockChance = blockChance,
    )

    /** What its tag says: "10k life · 500 armour · 40% res". */
    val summary: String
        get() = listOfNotNull(
            "${lifeLabel(life)} life",
            "$armour armour".takeIf { armour > 0 },
            "$evasion evasion".takeIf { evasion > 0 },
            "${(blockChance * 100).toInt()}% block".takeIf { blockChance > 0f },
            "${(resistance * 100).toInt()}% res".takeIf { resistance != 0f },
        ).joinToString(" · ")

    companion object {
        /** Long enough to watch a rotation play out. */
        const val STURDY_LIFE = 10_000

        /** Enough that nothing a phone can simulate in an evening kills it. */
        const val HUGE_LIFE = 1_000_000_000

        private fun lifeLabel(life: Int): String = when {
            life >= 1_000_000_000 -> "${life / 1_000_000_000}b"
            life >= 1_000_000 -> "${life / 1_000_000}m"
            life >= 10_000 -> "${life / 1_000}k"
            else -> life.toString()
        }
    }
}
