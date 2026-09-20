package com.stratum.core.domain.actor

import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.world.WorldPoint

/** A monster archetype defined by a pack. */
data class EnemyDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val rank: EnemyRank = EnemyRank.MINION,
    val baseStats: CombatStats = CombatStats(),
    val damageTypeId: String,
    /** Blocks per second. */
    val moveSpeed: Float = 2.2f,
    /** How far it notices the player, in blocks. */
    val aggroRange: Int = 8,
    /** Below this fraction of health it runs, if [canFlee]. */
    val fleeBelowHealth: Float = 0f,
    val canFlee: Boolean = false,
    val experience: Int = 10,
    /** Biome ids it spawns in. Empty means anywhere. */
    val spawnBiomeIds: List<String> = emptyList(),
    /** Relative spawn frequency against other eligible enemies. */
    val spawnWeight: Int = 100,
    /** Extra chance of a loot drop beyond the base rate, 0..1. */
    val bonusDropChance: Float = 0f,
    val bodyColor: Long = 0xFF8A3B3B,
    val spriteSetId: String? = null,
)

/**
 * Rank scales an enemy without a pack having to define three copies of it.
 * The multipliers are engine policy, not content.
 */
enum class EnemyRank(
    val healthMultiplier: Float,
    val damageMultiplier: Float,
    val experienceMultiplier: Float,
    val extraAffixChance: Float,
) {
    MINION(1f, 1f, 1f, 0f),
    ELITE(2.6f, 1.5f, 3f, 0.35f),
    CHAMPION(5f, 2f, 7f, 0.7f),
    BOSS(12f, 2.8f, 20f, 1f),
}

/** What a monster is currently doing. */
enum class EnemyState { IDLE, CHASING, ATTACKING, FLEEING, DEAD }

/**
 * A live monster. Immutable, replaced each tick, so the renderer always reads a
 * coherent frame rather than a half-updated one.
 */
data class EnemyInstance(
    val instanceId: String,
    val definitionId: String,
    val name: String,
    val rank: EnemyRank,
    val position: WorldPoint,
    val health: Int,
    val stats: CombatStats,
    val damageTypeId: String,
    val state: EnemyState = EnemyState.IDLE,
    /** Counts down to the next swing; the attack lands when it reaches zero. */
    val attackCooldown: Float = 0f,
    val experience: Int = 10,
    val bodyColor: Long = 0xFF8A3B3B,
    /**
     * The way it last moved, as a unit-ish step.
     *
     * Kept because the renderer has to know which way a body is turned and
     * had no way to ask: it passed one fixed direction for every monster, so
     * a character with drawn back art still charged at you face-first while
     * running north. Held as the last movement rather than as a bearing to
     * the player, because a monster that is fleeing is facing away from what
     * it is running from.
     */
    val facingX: Float = 0f,
    val facingY: Float = 1f,
) {
    val isAlive: Boolean get() = health > 0 && state != EnemyState.DEAD

    val healthFraction: Float
        get() = if (stats.maxHealth <= 0) 0f else (health.toFloat() / stats.maxHealth).coerceIn(0f, 1f)

    val blockPos get() = position.toBlockPos()

    fun damaged(amount: Int): EnemyInstance {
        val remaining = (health - amount).coerceAtLeast(0)
        return copy(
            health = remaining,
            state = if (remaining == 0) EnemyState.DEAD else state,
        )
    }
}
