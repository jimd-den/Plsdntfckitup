package com.stratum.engine.world

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.faction.Factions
import com.stratum.core.domain.stats.lootFind
import com.stratum.core.domain.world.MarkedWorld
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.floor
import kotlin.random.Random

/**
 * Who is in the world and what becomes of them: the director's wandering
 * monsters, a level author's and a dungeon's placed ones, the crowd that
 * moves them, and the spoils when they fall.
 *
 * The fight between spawning and dying is [CombatSystem]'s; this part hands
 * the bodies over and takes the dead back.
 */
internal class EncounterSystem(
    private val state: SessionState,
    private val content: AssembledContent,
    private val seed: Long,
    private val director: EnemyDirector,
    private val directorConfig: DirectorConfig,
    private val world: StreamingWorld,
    /** The terrain as its generator made it: a hand-made level or a generated world may say where it marked things. */
    landscape: TerrainGenerator,
    private val regionAt: (Int, Int) -> String?,
    private val combat: CombatSystem,
    private val crowd: CrowdControl,
    private val drops: LootDrops,
    private val ground: GroundItems,
    private val politics: PoliticsSystem,
    private val progression: ProgressionSystem,
    private val survival: SurvivalFacade,
    private val flashes: HitFlashes,
    private val impacts: ImpactField,
    private val random: Random,
    /** Deals each newcomer its own forged attacks. */
    private val spread: AttackSpread? = null,
) {
    private val level = landscape as? MarkedLevel

    /** A generated world's markers, peopled as the player comes near; null when the generator marks nothing. */
    private val markers = (landscape as? MarkedWorld)?.let(::MarkerPopulation)
    private val markerEncounters = MarkerEncounters(content, regionAt)

    /**
     * Places a monster deliberately, at [rank] -- the rank its definition
     * gives it unless the caller names one. The director fills the world on
     * its own; this is for when the world should contain something specific,
     * and it is the one road a placed monster takes, whether a level author,
     * a dungeon's boss room or the sandbox put it there.
     */
    fun spawn(definition: EnemyDefinition, position: WorldPoint, rank: EnemyRank = definition.rank): EnemyInstance =
        director.instantiate(definition, position, state.player.level, random, rank = rank).also { state.enemies = state.enemies + it }

    /**
     * A hand-authored level's enemies wait where its author put them, standing
     * on the ground there. Only markers in the loaded world around the spawn
     * are placed; the director lets anything farther away go anyway.
     */
    fun placeLevelEncounters(biomeId: String) {
        val level = level ?: return
        MapEncounters.plan(level.markers, content.enemies, content.enemiesFor(biomeId), random).forEach { encounter ->
            val surface = world.surfaceAt(floor(encounter.at.x).toInt(), floor(encounter.at.y).toInt())
            if (surface >= 0) spawn(encounter.definition, WorldPoint(encounter.at.x, encounter.at.y, surface + 1f))
        }
    }

    /**
     * Wakes the generated world's markers the player has come near: its
     * monsters at the rank the data gave them, its bosses as bosses, and its
     * chests as a drop on the floor. Each marker is peopled once a session;
     * its dice come from the seed and the marker, not the session's stream.
     */
    fun populateMarkers() {
        val population = markers ?: return
        population.follow(world.residency) { world.loadedChunks.map { it.pos } }
        val room = (directorConfig.maxAlive * MARKER_CROWD - state.enemies.count { !it.civilian }).coerceAtLeast(0)
        population.wake(state.player.position, room).forEach { marker ->
            val dice = MarkerEncounters.diceFor(seed, marker)
            when (val encounter = markerEncounters.resolve(marker, dice)) {
                is MarkerEncounter.Monster -> {
                    // A calmer world leaves some spawn points empty: the same ones every visit, by seed and marker.
                    val kept = directorConfig.markerShare >= 1f ||
                        kotlin.random.Random(seed xor marker.key.hashCode().toLong()).nextFloat() < directorConfig.markerShare
                    if (kept) state.enemies = state.enemies + director.instantiate(encounter.definition, marker.centre, state.player.level, dice, rank = encounter.rank)
                }
                is MarkerEncounter.Chest -> drops.chestAt(marker.centre, state.player.level, dice, progression.earnings.lootFind)?.let(ground::drop)
                null -> Unit
            }
        }
    }

    /** Generated-world markers already peopled, as a save keeps them. */
    val consumedMarkers: Set<String> get() = markers?.consumedKeys.orEmpty()

    /** Marks a save's peopled markers as spent, so a cleared room stays cleared after a reload. */
    fun restoreMarkers(keys: Collection<String>) {
        markers?.restore(keys)
    }

    /**
     * Tops up the wilds, turns out the garrisons of towns in sight, and moves
     * every body by the crowd brain, held back by whatever stuns or slows it.
     */
    fun advance(deltaSeconds: Float, biomeId: String) {
        val player = state.player
        val towns = politics.townsInSight()
        state.enemies = director.maintainPopulation(state.enemies, player.position, biomeId, player.level, random, politics.spawnAllowed(towns), facingOf(player.facing))
        state.enemies = state.enemies + politics.musterGarrisons(towns)
        spread?.let { state.enemies = it.deal(state.enemies) }
        val before = state.enemies
        state.enemies = combat.constrain(before, crowd.advance(before, player.position, politics::isHostile, deltaSeconds, politics.allyOrders()), deltaSeconds)
    }

    /**
     * Takes the fallen out of the world: loot, valuables, experience and
     * standing for a monster; a follower simply falls.
     */
    /** Told of every body buried, after the fact: what quests count kills from. */
    var onBuried: (List<EnemyInstance>) -> Unit = {}

    fun bury(slain: List<EnemyInstance>) {
        if (slain.isEmpty()) return
        onBuried(slain)
        val gone = slain.mapTo(HashSet()) { it.instanceId }
        state.enemies = state.enemies.filterNot { it.instanceId in gone }
        slain.forEach { forget(it.instanceId) }
        val foes = slain.filter { it.factionId != Factions.PLAYER }
        if (foes.isEmpty()) return
        val level = state.player.level
        foes.forEach { enemy ->
            val find = progression.earnings.lootFind
            drops.gearFor(enemy, level, random, find)?.let(ground::drop)
            drops.insertFor(enemy, level, random)?.let(ground::drop)
            drops.valuablesFor(enemy, level, random, find).forEach { progression.pocket(it, enemy.position) }
            survival.carcass(random)
            if (enemy.rank >= EnemyRank.CHAMPION) progression.conquer(enemy.position)
        }
        politics.onSlain(foes)
        progression.award(foes.sumOf { it.experience })
    }

    /** Monsters that had cornered the player do not get to greet them at the spawn point; the director refills the world soon enough. */
    fun clearAround(at: WorldPoint, radius: Float) {
        state.enemies = state.enemies.filter { it.position.horizontalDistanceTo(at) > radius }
    }

    /** How far a monster fights from: its swing, or for a ranged or support caster its longest offensive skill. */
    fun fightingReach(enemy: EnemyInstance): Float {
        val swing = enemy.stats.attackRange.toFloat()
        if (enemy.role != CombatRole.RANGED && enemy.role != CombatRole.SUPPORT) return swing
        val longest = director.definition(enemy.definitionId)?.allSkills.orEmpty()
            .mapNotNull { content.skill(it.skillId) }.filterNot { it.isBeneficial }.maxOfOrNull { it.range.toFloat() } ?: 0f
        return maxOf(swing, longest * RANGED_HOLD)
    }

    /** A facing as a bearing in radians, or null for straight up or down. */
    private fun facingOf(direction: com.stratum.core.domain.world.Direction): Float? =
        if (direction.dx == 0 && direction.dy == 0) null else kotlin.math.atan2(direction.dy.toFloat(), direction.dx.toFloat())

    private fun forget(actorId: String) {
        combat.forget(actorId)
        flashes.forget(actorId)
        impacts.forget(actorId)
    }

    private companion object {
        /** Marker monsters fill up to this many times the director's own cap, so a dungeon is fuller than the wilds but never a flood. */
        const val MARKER_CROWD = 2

        /** A ranged monster stands at this share of its skill's reach, so a step back does not put the player out of it. */
        const val RANGED_HOLD = 0.8f
    }
}
