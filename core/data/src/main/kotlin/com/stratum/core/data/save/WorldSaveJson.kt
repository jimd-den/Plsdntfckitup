package com.stratum.core.data.save

import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.difficulty.Difficulty
import com.stratum.core.domain.session.RealmSave
import com.stratum.core.domain.session.SavedChunk
import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.session.WorldPlayer
import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.session.WorldSummary
import com.stratum.core.domain.strategy.FollowerOrder
import com.stratum.core.domain.strategy.Outpost
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.SurvivalMode
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import com.stratum.core.domain.world.WorldRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A world's state and summary as JSON: everything but the chunk cells,
 * which are binary (see [ChunkBlob]) because a chunk is twelve thousand
 * numbers and JSON would make each one a word.
 *
 * Its own schema, like [HeroSaveJson]'s, so the domain can be renamed
 * without breaking anybody's worlds; the hero inside a world is written
 * with the hero schema itself, so the two can never disagree about an item.
 */
internal object WorldSaveJson {

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encodeState(save: WorldSave, chunkFile: String?): String = json.encodeToString(WorldSchema.serializer(), WorldSchema.of(save, chunkFile))

    fun decodeState(text: String): WorldSchema = json.decodeFromString(WorldSchema.serializer(), text).also(::checkVersion)

    fun encodeState(schema: WorldSchema): String = json.encodeToString(WorldSchema.serializer(), schema)

    fun encodeSummary(summary: WorldSummary): String = json.encodeToString(SummarySchema.serializer(), SummarySchema.of(summary))

    fun decodeSummary(text: String): WorldSummary = json.decodeFromString(SummarySchema.serializer(), text).also { checkVersion(it.version) }.toDomain()

    private fun checkVersion(schema: WorldSchema) = checkVersion(schema.version)

    /** A save from a newer build is left alone rather than half-read and then written over with less. */
    private fun checkVersion(version: Int) {
        require(version in 1..WORLD_FORMAT_VERSION) { "world save format $version is newer than $WORLD_FORMAT_VERSION" }
    }
}

/** What is written in a world's small summary file: the menu's row and nothing else. */
@Serializable
internal data class SummarySchema(
    /** Missing reads as the first format: defaults are not written, and the first format is what wrote none. */
    val version: Int = 1,
    val id: String,
    val name: String,
    val heroName: String = "",
    val heroClassId: String,
    val level: Int = 1,
    val presetName: String = "",
    val seed: Long = 0L,
    val playSeconds: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val createdAt: Long = 0L,
) {
    fun toDomain() = WorldSummary(id, name, heroName, heroClassId, level, presetName, seed, playSeconds, lastPlayedAt, createdAt)

    companion object {
        fun of(s: WorldSummary) = SummarySchema(
            WORLD_FORMAT_VERSION, s.id, s.name, s.heroName, s.heroClassId, s.level, s.presetName, s.seed, s.playSeconds, s.lastPlayedAt, s.createdAt,
        )
    }
}

/**
 * A world's state, with a pointer to the chunk file that belongs to it.
 * The chunk file is named per save and this file is renamed into place
 * after it, so the pair on disk always matches: a save interrupted between
 * the two leaves the old state pointing at the old chunks.
 */
@Serializable
internal data class WorldSchema(
    /** Missing reads as the first format: defaults are not written, and the first format is what wrote none. */
    val version: Int = 1,
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
    val presetName: String = "",
    val packIds: List<String> = emptyList(),
    val heroName: String = "",
    val lastPlayedAt: Long = 0L,
    val playSeconds: Long = 0L,
    val config: ConfigSchema = ConfigSchema(),
    val tier: Int = 0,
    val mods: List<WaystoneModSchema> = emptyList(),
    val hero: HeroSchema,
    val player: PlayerSchema,
    val clockSeconds: Float = 0f,
    val blockIds: List<String> = emptyList(),
    /** The chunk file's name in the world's folder, or null when nothing was changed. */
    val chunkFile: String? = null,
    val realm: RealmSchema = RealmSchema(),
    val consumedMarkers: List<String> = emptyList(),
) {
    fun toDomain(chunks: List<SavedChunk>) = WorldSave(
        identity = WorldIdentity(id, name, createdAt, presetName, packIds, heroName),
        lastPlayedAt = lastPlayedAt,
        playSeconds = playSeconds,
        config = config.toDomain(),
        difficulty = Difficulty(tier, mods.map { it.toDomain() }),
        hero = hero.toDomain(),
        player = player.toDomain(),
        clockSeconds = clockSeconds,
        blockIds = blockIds,
        chunks = chunks,
        realm = realm.toDomain(),
        consumedMarkers = consumedMarkers.toSet(),
    )

    companion object {
        fun of(s: WorldSave, chunkFile: String?) = WorldSchema(
            WORLD_FORMAT_VERSION, s.id, s.name, s.createdAt, s.identity.presetName, s.identity.packIds, s.identity.heroName,
            s.lastPlayedAt, s.playSeconds, ConfigSchema.of(s.config), s.difficulty.tier, s.difficulty.mods.map(WaystoneModSchema::of),
            HeroSchema.of(s.hero), PlayerSchema.of(s.player), s.clockSeconds, s.blockIds, chunkFile, RealmSchema.of(s.realm),
            s.consumedMarkers.sorted(),
        )
    }
}

@Serializable
internal data class ConfigSchema(
    val seed: Long = 0L,
    val simulationRadius: Int = DEFAULT_CONFIG.simulationRadius,
    val seaLevel: Int = DEFAULT_CONFIG.seaLevel,
    val surfaceVariation: Int = DEFAULT_CONFIG.surfaceVariation,
    val caveDensity: Float = DEFAULT_CONFIG.caveDensity,
    val oreRichness: Float = DEFAULT_CONFIG.oreRichness,
    val rules: RulesSchema = RulesSchema(),
    /** The terrain as tuned in the World panel; absent in saves from before it, and for untuned worlds. */
    val terrainPasses: List<PassSchema>? = null,
) {
    fun toDomain() = WorldConfig(
        seed, simulationRadius, seaLevel, surfaceVariation, caveDensity, oreRichness, rules.toDomain(),
        terrainPasses?.map { com.stratum.core.domain.world.PassSpec(it.id, it.options) },
    )

    companion object {
        fun of(c: WorldConfig) = ConfigSchema(
            c.seed, c.simulationRadius, c.seaLevel, c.surfaceVariation, c.caveDensity, c.oreRichness, RulesSchema.of(c.rules),
            c.terrainPasses?.map { PassSchema(it.id, it.options) },
        )
    }
}

@Serializable
internal data class PassSchema(val id: String, val options: Map<String, String> = emptyMap())

/** World rules by name and value; a survival mode a later build added and this one lacks reads as the default. */
@Serializable
internal data class RulesSchema(
    val survival: String = DEFAULT_RULES.survival.name,
    val townDensity: Float = DEFAULT_RULES.townDensity,
    val startInTown: Boolean = DEFAULT_RULES.startInTown,
    val monsterDensity: Float = DEFAULT_RULES.monsterDensity,
    val raids: Boolean = DEFAULT_RULES.raids,
    val dayLengthMinutes: Float = DEFAULT_RULES.dayLengthMinutes,
    val lootMultiplier: Float = DEFAULT_RULES.lootMultiplier,
    val experienceMultiplier: Float = DEFAULT_RULES.experienceMultiplier,
    val deathPenalty: Float = DEFAULT_RULES.deathPenalty,
    val combat: CombatRulesSchema = CombatRulesSchema(),
    val sandbox: Boolean = false,
    /** Absent in saves from before it: then it follows the monster dial, so an old calm world is calm. */
    val enemyDamage: Float? = null,
    val enemyAlertness: Float? = null,
) {
    fun toDomain(): WorldRules {
        val rules = WorldRules(
            survival = SurvivalMode.entries.firstOrNull { it.name == survival } ?: DEFAULT_RULES.survival,
            townDensity = townDensity, startInTown = startInTown, monsterDensity = monsterDensity, raids = raids,
            dayLengthMinutes = dayLengthMinutes, lootMultiplier = lootMultiplier, experienceMultiplier = experienceMultiplier,
            deathPenalty = deathPenalty, combat = combat.toDomain(), sandbox = sandbox,
        )
        val dialled = rules.withMonsters(monsterDensity)
        return rules.copy(enemyDamage = enemyDamage ?: dialled.enemyDamage, enemyAlertness = enemyAlertness ?: dialled.enemyAlertness)
    }

    companion object {
        fun of(r: WorldRules) = RulesSchema(
            r.survival.name, r.townDensity, r.startInTown, r.monsterDensity, r.raids, r.dayLengthMinutes, r.lootMultiplier,
            r.experienceMultiplier, r.deathPenalty, CombatRulesSchema.of(r.combat), r.sandbox, r.enemyDamage, r.enemyAlertness,
        )
    }
}

@Serializable
internal data class CombatRulesSchema(
    val resistanceCap: Float = DEFAULT_COMBAT.resistanceCap,
    val resistanceHardCap: Float = DEFAULT_COMBAT.resistanceHardCap,
    val minResistance: Float = DEFAULT_COMBAT.minResistance,
    val maxEvadeChance: Float = DEFAULT_COMBAT.maxEvadeChance,
    val maxBlockChance: Float = DEFAULT_COMBAT.maxBlockChance,
    val maxArmourReduction: Float = DEFAULT_COMBAT.maxArmourReduction,
    val cooldownFloor: Float = DEFAULT_COMBAT.cooldownFloor,
    val maxLeechRate: Float = DEFAULT_COMBAT.maxLeechRate,
    val critChanceCap: Float = DEFAULT_COMBAT.critChanceCap,
    val triggerDepth: Int = DEFAULT_COMBAT.triggerDepth,
    val triggerCooldownFloor: Float = DEFAULT_COMBAT.triggerCooldownFloor,
    val triggerBudget: Int = DEFAULT_COMBAT.triggerBudget,
    val maxStatusStacks: Int = DEFAULT_COMBAT.maxStatusStacks,
) {
    fun toDomain() = CombatRules(
        resistanceCap, resistanceHardCap, minResistance, maxEvadeChance, maxBlockChance, maxArmourReduction, cooldownFloor,
        maxLeechRate, critChanceCap, triggerDepth, triggerCooldownFloor, triggerBudget, maxStatusStacks,
    )

    companion object {
        fun of(c: CombatRules) = CombatRulesSchema(
            c.resistanceCap, c.resistanceHardCap, c.minResistance, c.maxEvadeChance, c.maxBlockChance, c.maxArmourReduction,
            c.cooldownFloor, c.maxLeechRate, c.critChanceCap, c.triggerDepth, c.triggerCooldownFloor, c.triggerBudget, c.maxStatusStacks,
        )
    }
}

@Serializable
internal data class PointSchema(val x: Float, val y: Float, val z: Float) {
    fun toDomain() = WorldPoint(x, y, z)

    companion object {
        fun of(p: WorldPoint) = PointSchema(p.x, p.y, p.z)
    }
}

@Serializable
internal data class PlayerSchema(
    val position: PointSchema,
    val facing: String = Direction.SOUTH.name,
    val health: Int,
    val resource: Int,
    val needs: Map<String, Float> = emptyMap(),
    val inventory: Map<String, Int> = emptyMap(),
    val hotbar: List<String> = emptyList(),
    val selectedSlot: Int = 0,
    val toolTier: Int = 1,
) {
    fun toDomain() = WorldPlayer(
        position.toDomain(), Direction.entries.firstOrNull { it.name == facing } ?: Direction.SOUTH,
        health, resource, needs, inventory, hotbar, selectedSlot, toolTier,
    )

    companion object {
        fun of(p: WorldPlayer) = PlayerSchema(
            PointSchema.of(p.position), p.facing.name, p.health, p.resource, p.needs, p.inventory, p.hotbar, p.selectedSlot, p.toolTier,
        )
    }
}

@Serializable
internal data class OutpostSchema(
    val id: String,
    val name: String,
    val centerX: Int,
    val centerY: Int,
    val radius: Int = Outpost.DEFAULT_RADIUS,
    val structures: Map<String, Int> = emptyMap(),
    val stockpile: Map<String, Float> = emptyMap(),
    val garrison: Map<String, Int> = emptyMap(),
    val raidIn: Float = Outpost.FIRST_RAID_SECONDS,
    val raidsSurvived: Int = 0,
) {
    fun toDomain() = Outpost(id, name, centerX, centerY, radius, structures, stockpile, garrison, raidIn, raidsSurvived)

    companion object {
        fun of(o: Outpost) = OutpostSchema(o.id, o.name, o.centerX, o.centerY, o.radius, o.structures, o.stockpile, o.garrison, o.raidIn, o.raidsSurvived)
    }
}

@Serializable
internal data class RealmSchema(
    val outposts: List<OutpostSchema> = emptyList(),
    val order: String = FollowerOrder.FOLLOW.name,
    val holdAt: PointSchema? = null,
    val followers: List<String> = emptyList(),
    val liberatedTowns: List<String> = emptyList(),
) {
    fun toDomain() = RealmSave(
        outposts.map { it.toDomain() }, FollowerOrder.entries.firstOrNull { it.name == order } ?: FollowerOrder.FOLLOW,
        holdAt?.toDomain(), followers, liberatedTowns.toSet(),
    )

    companion object {
        fun of(r: RealmSave) = RealmSchema(
            r.outposts.map(OutpostSchema::of), r.followerOrder.name, r.holdAt?.let(PointSchema::of), r.followers, r.liberatedTowns.sorted(),
        )
    }
}

private val DEFAULT_CONFIG = WorldConfig()
private val DEFAULT_RULES = WorldRules()
private val DEFAULT_COMBAT = CombatRules()

/** Bumped when a change cannot be read by the older reader; older formats stay readable. */
internal const val WORLD_FORMAT_VERSION = 1
