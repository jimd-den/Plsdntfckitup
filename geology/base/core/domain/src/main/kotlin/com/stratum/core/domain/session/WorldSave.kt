package com.stratum.core.domain.session

import com.stratum.core.domain.difficulty.Difficulty
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldRules

/**
 * Which world a session is: the slot it saves into and the labels a menu
 * shows for it. Everything here is chosen when the world is made and does
 * not change with play, which is why it is kept apart from [WorldSave]:
 * the session carries one of these and stamps it onto every save it takes.
 */
data class WorldIdentity(
    val id: String,
    val name: String,
    /** Epoch millis. Read from a clock by the app, never by the simulation. */
    val createdAt: Long = 0L,
    /** The rules preset the world was made with, for the menu; empty when the rules were custom. */
    val presetName: String = "",
    /** The packs and plugins that were enabled, so a load can say what is missing. */
    val packIds: List<String> = emptyList(),
    /** What the menu calls the hero; the class name when the player gave none. */
    val heroName: String = "",
)

/**
 * One world, kept: the ground as the player left it, where they stood, the
 * realm they built, the markers they cleared, and the hero as they were in
 * this world.
 *
 * The terrain itself is not stored. It is a function of [config]'s seed, so
 * only the chunks the player changed are kept, in [chunks]; everything else
 * regenerates byte for byte. Block cells are stored against [blockIds] --
 * the ids at the time of the save, by index -- rather than the registry's
 * indices, so a pack that adds a block between two sessions does not turn
 * every saved wall into something else.
 *
 * The [hero] is this world's copy. A hero carried between worlds is also
 * kept on its own, as a [HeroSave]; a new world starts from that, and a
 * saved world resumes from this.
 */
data class WorldSave(
    val identity: WorldIdentity,
    /** Epoch millis of the last save. */
    val lastPlayedAt: Long = 0L,
    /** Seconds of play in this world, summed from the simulation's own steps. */
    val playSeconds: Long = 0L,
    /** The seed, the terrain dials and the [WorldRules]. */
    val config: WorldConfig,
    val difficulty: Difficulty = Difficulty.BASE,
    val hero: HeroSave,
    val player: WorldPlayer,
    /** The world clock's running total, which is what time of day and the day count are read from. */
    val clockSeconds: Float = 0f,
    /** Block ids by the index [chunks] store them under. */
    val blockIds: List<String> = emptyList(),
    /** Every chunk the player changed, resident or not when the save was taken. */
    val chunks: List<SavedChunk> = emptyList(),
    val realm: RealmSave = RealmSave(),
    /** Generated-world markers already peopled, so a cleared dungeon stays cleared. */
    val consumedMarkers: Set<String> = emptySet(),
    /** The microvoxel models this world's stamps name: kept with the world, so a statue outlives its library entry. */
    val microModels: List<com.stratum.core.domain.micro.MicroModel> = emptyList(),
    /** Models, sculpting and chiselling laid over the land, oldest first. */
    val stamps: List<com.stratum.core.domain.micro.MicroStamp> = emptyList(),
) {
    val id: String get() = identity.id
    val name: String get() = identity.name
    val createdAt: Long get() = identity.createdAt
    val seed: Long get() = config.seed
    val rules: WorldRules get() = config.rules
    val heroClassId: String get() = hero.heroClassId

    /** A build sandbox: saved in its own slot, and never written over the real hero. */
    val sandbox: Boolean get() = rules.sandbox

    fun summary(): WorldSummary = WorldSummary(
        id = id, name = name,
        heroName = identity.heroName, heroClassId = heroClassId, level = hero.level,
        presetName = identity.presetName, seed = seed,
        playSeconds = playSeconds, lastPlayedAt = lastPlayedAt, createdAt = createdAt,
    )
}

/**
 * What a list of saved worlds shows, and nothing more: small enough that a
 * menu can read every slot's without touching a single chunk.
 */
data class WorldSummary(
    val id: String,
    val name: String,
    val heroName: String,
    val heroClassId: String,
    val level: Int,
    val presetName: String,
    val seed: Long,
    val playSeconds: Long,
    val lastPlayedAt: Long,
    val createdAt: Long,
)

/**
 * Where worlds are kept. Implemented over files in `:core:data`.
 *
 * Suspending because a world is megabytes of chunks rather than a hero's
 * few kilobytes: every call is expected to leave the main thread.
 */
interface WorldSaveRepository {
    /** Every readable world, newest played first. A save that will not read is left out, not fatal. */
    suspend fun list(): List<WorldSummary>

    suspend fun load(id: String): WorldSave?

    suspend fun save(save: WorldSave)

    suspend fun delete(id: String)

    suspend fun rename(id: String, name: String)
}
