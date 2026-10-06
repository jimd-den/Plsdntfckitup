package com.stratum.core.domain.settings

/**
 * Every procedural option the player sets once, in one place.
 *
 * World-by-world choices (seed, monster density, day length) stay with each
 * world's [com.stratum.core.domain.world.WorldRules]; these are the player's
 * standing preferences, read by every world they enter. Each field is plain
 * data with a safe default, so an older save of these settings simply picks
 * up the defaults for anything it lacks.
 */
data class GameSettings(
    // ---- towns and quests ----------------------------------------------------------------------
    /** Towns are safe ground: no monster spawns inside their bounds, and hostiles that follow you in turn back. */
    val safeTowns: Boolean = true,
    /** How busy towns are: residents per house, from a quiet hamlet (0) to a crowded market town (3). */
    val townLife: Int = 2,
    /** Quests each town's board offers at once (1-8). */
    val questsPerTown: Int = 4,
    /** How hard quests ask: 0 errands, 1 balanced, 2 heroic, 3 legendary. */
    val questDifficulty: Int = 1,
    /** Chains: one quest leading into the next from the same giver. */
    val questChains: Boolean = true,

    // ---- combat --------------------------------------------------------------------------------
    /** How hard monsters press the player: what their blows cost, how often they come, and how long they hold. */
    val challenge: Challenge = Challenge.NORMAL,
    /** Forged attacks each monster carries (1-3): more means fewer repeats in a fight. */
    val attacksPerMonster: Int = 2,
    /** No two monsters close together share an attack, so a fight never feels like the same move twice. */
    val distinctAttacks: Boolean = true,
    /** How much attacks reshape the ground. */
    val voxelDestruction: VoxelDestruction = VoxelDestruction.PHYSICS,
    /** Loose blocks simulated at once; past this the oldest settle at once. Lower is faster. */
    val maxDebris: Int = 160,

    // ---- characters ----------------------------------------------------------------------------
    /** Bodies fall and tumble as ragdolls when they die or are blasted, instead of fading in place. */
    val ragdolls: Boolean = false,
) {
    /** These settings with every number pulled back into its range: what a store hands out, whatever it read. */
    fun coerced(): GameSettings = copy(
        townLife = townLife.coerceIn(0, 3),
        questsPerTown = questsPerTown.coerceIn(1, 8),
        questDifficulty = questDifficulty.coerceIn(0, 3),
        attacksPerMonster = attacksPerMonster.coerceIn(1, 3),
        maxDebris = maxDebris.coerceIn(0, 1000),
    )
}

/**
 * How hard monsters press the player. Every level keeps the fight fair: a
 * stun is always followed by a moment no stun can land in, so a crowd can
 * knock the player about but never lock them in place, and only so many
 * monsters close in to attack at once -- the rest circle, waiting a turn.
 */
enum class Challenge(
    val label: String,
    val blurb: String,
    /** Share of monsters' damage, hits and burns alike, that reaches the player. */
    val damageTaken: Float,
    /** Monsters' time between swings and casts is multiplied by this: above 1, they attack less often. */
    val tempo: Float,
    /** Stuns and slows on the player last this share of their length. */
    val controlTime: Float,
    /** Seconds after a stun lets go in which no new stun can land on the player. */
    val stunGuard: Float,
    /** Monsters that may be attacking the player at once. */
    val attackers: Int,
) {
    STORY("Story", "For the world and its people: blows are light, stuns brief, and one monster attacks at a time.", 0.35f, 1.6f, 0.4f, 3f, 1),
    GENTLE("Gentle", "A forgiving fight: monsters hit softer and slower, and two at most attack at once.", 0.6f, 1.3f, 0.6f, 2f, 2),
    NORMAL("Normal", "A fair fight: crowds hurt, but a stun always leaves you a moment to act.", 0.8f, 1.15f, 0.8f, 1.5f, 3),
    HARD("Hard", "Monsters at full strength and pace; you still get a beat between stuns.", 1f, 1f, 1f, 1f, 4),
    BRUTAL("Brutal", "Heavier blows, quicker attacks, crowds that all join in.", 1.35f, 0.85f, 1f, 0.5f, 6),
}

/** How far an attack's voxel payload goes. */
enum class VoxelDestruction(val label: String, val blurb: String) {
    OFF("Off", "Attacks never change the ground."),
    SCARS("Scars", "Craters, walls, burnt brush and ice; nothing falls."),
    PHYSICS("Physics", "Big attacks tear blocks loose: they fly, fall, bounce and settle where they land."),
}
