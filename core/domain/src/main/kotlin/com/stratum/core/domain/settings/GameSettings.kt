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

/** How far an attack's voxel payload goes. */
enum class VoxelDestruction(val label: String, val blurb: String) {
    OFF("Off", "Attacks never change the ground."),
    SCARS("Scars", "Craters, walls, burnt brush and ice; nothing falls."),
    PHYSICS("Physics", "Big attacks tear blocks loose: they fly, fall, bounce and settle where they land."),
}
