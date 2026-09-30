package com.stratum.engine.world

import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.difficulty.Difficulty
import com.stratum.core.domain.faction.Reputation
import com.stratum.core.domain.faction.Stance
import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.settlement.SettlementAtlas
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.settlement.SettlementTerrain
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One session's parts, built and joined: the composition root of a run.
 *
 * Kept apart from [WorldSession] so the session reads as what it is -- the
 * order things happen in and the face the game sees -- rather than a page of
 * wiring. Everything here is made once, in dependency order, and nothing
 * here decides anything about play.
 */
internal class SessionParts(
    val content: AssembledContent,
    /** Changes only when the player retunes the terrain; everything else about a world is fixed at its start. */
    var config: WorldConfig,
    heroClassId: String?,
    terrainGenerator: TerrainGenerator?,
    val difficulty: Difficulty,
    val hero: HeroSave?,
) {
    val state = SessionState()

    /** The terrain as its generator made it, before towns are laid over it: what can say where it marked things. */
    private val landscape: TerrainGenerator = terrainGenerator ?: StratumTerrain.create(content.terrainContext(config).copy(welcoming = ::welcoming))
    private val generator: TerrainGenerator = terrainGenerator ?: withTowns(landscape)

    /** The microvoxels behind the blocks, when the terrain was made of them; renderers draw the fine version from it. */
    val microTerrain: com.stratum.engine.microvoxel.MicroTerrainSource? = landscape as? com.stratum.engine.microvoxel.MicroTerrainSource

    /** The terrain, when it can be retuned while played; see [WorldSession.retuneTerrain]. */
    val hotTerrain: com.stratum.engine.microbridge.HotTerrain? = landscape as? com.stratum.engine.microbridge.HotTerrain

    /** Only generators that claim to know about biomes are asked; one that does not leaves the region unnamed. */
    val biomeSource: BiomeSource? = generator as? BiomeSource
    val world = StreamingWorld(content.registry, generator, config)

    /** One RNG for the whole run, seeded from the world seed, so a session replays identically given the same inputs. */
    val random = Random(config.seed)
    val clock = WorldClock(config.rules.dayLengthMinutes * 60f)
    val cues = SessionCues()
    val flashes = HitFlashes()
    val animator = ActorAnimator()
    val impacts = ImpactField(world)
    val table = TableState()
    val motion = PlayerMotion(world)
    private val roomScanner = RoomScanner(world)
    private val lootRoller = LootRoller.of(content)
    val ground = GroundItems()
    private val workbench = Workbench(content, ItemCrafter(lootRoller), config.rules.combat)
    private val directorConfig = DirectorConfig(
        maxAlive = (DirectorConfig().maxAlive * config.rules.monsterDensity).roundToInt().coerceAtLeast(1),
        alertness = config.rules.enemyAlertness,
        markerShare = config.rules.monsterDensity.coerceAtMost(1f),
    )
    private val director = EnemyDirector(world, content.enemies, config = directorConfig, difficulty = difficulty, packs = content.enemyPacks)
    private val survivalRules = SurvivalSystem(content, config.rules.survival, world, roomScanner)

    val heroClass = (hero?.heroClassId ?: heroClassId)?.let { id -> content.heroClasses.firstOrNull { it.id == id } } ?: content.heroClasses.firstOrNull()
    val profile = PlayerProfile(content, table, survivalRules::modifiers, workbench::linkedTo)
    val survival = SurvivalFacade(state, content, survivalRules, profile::maxHealth)
    val gear = GearSystem(state, content, workbench, ground, lootRoller, cues, random, profile)
    /** Models and carvings laid over the land, when it is made of microvoxels. */
    val stampSurface: com.stratum.core.domain.micro.MicroStampSurface? = landscape as? com.stratum.core.domain.micro.MicroStampSurface

    val building = BuildingSystem(state, world, content.registry, motion, survivalRules, roomScanner, cues, random, content::insert, microTerrain, stampSurface)
    val progression = ProgressionSystem(state, content, config.rules, difficulty, cues, content::insert, profile)
    val politics = PoliticsSystem(state, content, generator as? SettlementAtlas, director, RealmSystem(content, config.rules.raids), cues, random)

    /** The fight itself: casting, hits, statuses, projectiles, triggers, flasks. */
    val combat = CombatSystem(
        content, config.rules.combat, world, director, cues, flashes, impacts, random, profile,
        playerSkill = { id -> content.skill(id)?.let(gear::tuned) },
        incomingDamage = config.rules.enemyDamage,
    )
    val encounters: EncounterSystem = EncounterSystem(
        state, content, config.seed, director, directorConfig, world, landscape, { x, y -> biomeSource?.biomeAt(x, y)?.id },
        combat, CrowdControl(world, director, reachOf = { encounters.fightingReach(it) }), LootDrops(content, lootRoller, config.seaLevel, difficulty),
        ground, politics, progression, survival, flashes, impacts, random,
    )
    val inspector = BuildInspector(content, config.rules.combat, profile, survivalRules::modifiers, { table.boons }, combat.statuses::of, workbench::linkedTo)
    val fight = FightSystem(state, content, combat, profile, gear, encounters, politics, animator) { motion.isInvulnerable }

    /** The region under a column: the generator's answer, else the packs' first, else a placeholder. */
    fun biomeAt(x: Int, y: Int): BiomeDefinition = biomeSource?.biomeAt(x, y) ?: content.biomes.firstOrNull() ?: UNCHARTED

    /**
     * Drops the player onto the surface at the world origin. Searches outward if
     * the origin column happens to be unsuitable, so a spawn is never inside rock.
     */
    fun spawnPoint(): WorldPoint {
        // Standing ground first: a column topped with water or a sprite is a
        // surface, but not one to arrive on. Any surface at all is the fallback.
        var fallback: WorldPoint? = null
        for (radius in 0..SPAWN_SEARCH_RADIUS) {
            for (y in -radius..radius) {
                for (x in -radius..radius) {
                    if (maxOf(abs(x), abs(y)) != radius) continue
                    val surface = world.surfaceAt(x, y)
                    if (surface !in 1 until Chunk.HEIGHT - 2) continue
                    val point = WorldPoint(x + 0.5f, y + 0.5f, (surface + 1).toFloat())
                    if (world.isSolid(com.stratum.core.domain.world.BlockPos(x, y, surface))) return point
                    if (fallback == null) fallback = point
                }
            }
        }
        return fallback ?: WorldPoint(0.5f, 0.5f, (config.seaLevel + 1).toFloat())
    }

    /**
     * Hand-authored maps are left as their author drew them, and a pipeline
     * that builds its own towns keeps them; every other world gets the towns
     * its packs describe, laid over its terrain.
     */
    private fun withTowns(base: TerrainGenerator): TerrainGenerator =
        if (content.terrain.generatorId == TerrainRecipe.TILE_MAP || base is SettlementAtlas) base
        else SettlementTerrain.over(
            base, content.registry, config.seed, content.settlements,
            startingTown = config.rules.startInTown, density = config.rules.townDensity, welcoming = ::welcoming,
        )

    /** A town the player can begin in: one whose people are not hostile to a newcomer. */
    private fun welcoming(recipe: SettlementRecipe): Boolean =
        if (recipe.factionId != null) content.factionBook.stanceToPlayer(recipe.factionId, Reputation()) != Stance.HOSTILE
        else recipe.garrison.isEmpty()

    private companion object {
        const val SPAWN_SEARCH_RADIUS = 12

        /** Shown when a generator names no regions and the packs define none. */
        val UNCHARTED = BiomeDefinition(
            id = "stratum:uncharted",
            name = "Uncharted",
            surfaceBlockId = BlockType.BEDROCK.id,
            subsurfaceBlockId = BlockType.BEDROCK.id,
            bedrockFillerBlockId = BlockType.BEDROCK.id,
        )
    }
}
