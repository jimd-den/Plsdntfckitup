package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyPackDefinition
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.EnemyState
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.difficulty.Difficulty
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Spawns monsters around the player and moves them.
 *
 * Kept separate from combat: this decides where things are and what they want,
 * and hands the actual hits to [CombatResolver]. Both are pure given a [Random],
 * so a fight replays exactly from a seed.
 */
class EnemyDirector(
    private val world: World,
    private val definitions: List<EnemyDefinition>,
    private val config: DirectorConfig = DirectorConfig(),
    /** The world tier and waystone mods every monster here is made with. */
    private val difficulty: Difficulty = Difficulty.BASE,
    /** Groups that spawn together; see [EnemyPackDefinition]. */
    private val packs: List<EnemyPackDefinition> = emptyList(),
) {
    private val byId = definitions.associateBy { it.id }

    fun definition(id: String): EnemyDefinition? = byId[id]

    /**
     * Tops the population back up around [focus].
     *
     * Spawns land outside [DirectorConfig.safeRadius] so nothing materialises on
     * top of the player, and on a real surface with headroom so nothing spawns
     * buried in rock.
     */
    fun maintainPopulation(
        current: List<EnemyInstance>,
        focus: WorldPoint,
        biomeId: String,
        playerLevel: Int,
        random: Random,
        /** False where nothing may spawn: inside a friendly town's walls. */
        spawnAllowed: (WorldPoint) -> Boolean = { true },
    ): List<EnemyInstance> {
        val alive = current.filter { it.isAlive }
        val nearby = alive.filter { it.position.horizontalDistanceTo(focus) <= keepWithin(it) }
        if (nearby.size >= config.maxAlive) return nearby

        // A spawn weight of zero means "never on its own": garrison troops and pack-only followers.
        val eligible = definitions.filter { it.spawnWeight > 0 && (it.spawnBiomeIds.isEmpty() || biomeId in it.spawnBiomeIds) }
        if (eligible.isEmpty()) return nearby
        val eligiblePacks = packs.filter { it.spawnBiomeIds.isEmpty() || biomeId in it.spawnBiomeIds }

        val spawned = mutableListOf<EnemyInstance>()
        var attempts = 0
        while (nearby.size + spawned.size < config.maxAlive && attempts < config.maxSpawnAttempts) {
            attempts++
            val position = findSpawnPoint(focus, random)?.takeIf(spawnAllowed) ?: continue
            val room = config.maxAlive - nearby.size - spawned.size
            val pack = eligiblePacks.takeIf { random.nextFloat() < config.packChance }?.let { pickPack(it, room, random) }
            spawned += if (pack != null) spawnPack(pack, position, playerLevel, random) else {
                val definition = pickWeighted(eligible, random) ?: continue
                listOf(instantiate(definition, position, playerLevel, random))
            }
        }
        return nearby + spawned
    }

    /** A garrison holds its town while the player is anywhere near it, not just on screen. */
    private fun keepWithin(enemy: EnemyInstance): Float =
        if (enemy.home != null) config.despawnRadius + GARRISON_REACH else config.despawnRadius

    private fun pickPack(eligible: List<EnemyPackDefinition>, room: Int, random: Random): EnemyPackDefinition? {
        val fitting = eligible.filter { it.size in 1..room }
        val total = fitting.sumOf { it.weight.coerceAtLeast(0) }
        if (total <= 0) return null
        var roll = random.nextInt(total)
        return fitting.firstOrNull { roll -= it.weight.coerceAtLeast(0); roll < 0 }
    }

    /**
     * A pack arrives together: the leader in the middle, the rest in a loose
     * ring round it, all sharing a squad id so they alert, fight and break as
     * one.
     */
    fun spawnPack(pack: EnemyPackDefinition, at: WorldPoint, playerLevel: Int, random: Random, home: WorldPoint? = null, squadId: String? = null): List<EnemyInstance> {
        val squad = squadId ?: "squad_${random.nextLong().toULong().toString(16)}"
        val leader = pack.leaderId?.let(byId::get)?.let { instantiate(it, at, playerLevel, random).copy(squadId = squad, isLeader = true, home = home) }
        val members = pack.members.flatMap { member -> List(member.count) { member.enemyId } }.mapNotNull(byId::get)
        val ring = members.mapIndexed { i, definition ->
            val angle = i * TWO_PI / members.size.coerceAtLeast(1)
            val spot = WorldPoint(at.x + kotlin.math.cos(angle) * PACK_SPREAD, at.y + kotlin.math.sin(angle) * PACK_SPREAD, at.z)
            instantiate(definition, grounded(spot) ?: at, playerLevel, random).copy(squadId = squad, home = home?.let { spot })
        }
        return listOfNotNull(leader) + ring
    }

    /** [spot] dropped onto the ground beneath it, or null when there is none. */
    fun grounded(spot: WorldPoint): WorldPoint? {
        val x = kotlin.math.floor(spot.x).toInt()
        val y = kotlin.math.floor(spot.y).toInt()
        val surface = world.surfaceAt(x, y)
        if (surface < 0 || surface >= Chunk.HEIGHT - 2) return null
        return WorldPoint(spot.x, spot.y, (surface + 1).toFloat())
    }

    /**
     * Moves one step along [direction], resolving terrain the way a chase
     * does. The crowd decides where each body wants to go; this is how it
     * gets there without walking through walls.
     */
    internal fun stepAlong(from: WorldPoint, direction: com.stratum.engine.crowd.Vec2, distance: Float): WorldPoint {
        if (distance <= 0f || direction == com.stratum.engine.crowd.Vec2.ZERO) return from
        return step(from, WorldPoint(from.x + direction.x, from.y + direction.y, from.z), distance)
    }

    private fun findSpawnPoint(focus: WorldPoint, random: Random): WorldPoint? {
        val angle = random.nextFloat() * TWO_PI
        val distance = config.safeRadius + random.nextFloat() * (config.spawnRadius - config.safeRadius)
        val x = (focus.x + kotlin.math.cos(angle) * distance).roundToInt()
        val y = (focus.y + kotlin.math.sin(angle) * distance).roundToInt()

        val surface = world.surfaceAt(x, y)
        if (surface < 0 || surface >= Chunk.HEIGHT - 2) return null

        // Needs a clear block to stand in, or it spawns inside the terrain.
        val standing = BlockPos(x, y, surface + 1)
        if (world.isSolid(standing)) return null

        return WorldPoint(x + 0.5f, y + 0.5f, (surface + 1).toFloat())
    }

    /**
     * Builds a live monster, scaled to the player's level so a world stays
     * dangerous as they grow rather than becoming a walk.
     */
    fun instantiate(
        definition: EnemyDefinition,
        position: WorldPoint,
        playerLevel: Int,
        random: Random,
    ): EnemyInstance {
        val rank = rollRank(random)
        val levelScale = 1f + (playerLevel + difficulty.monsterLevelBonus - 1) * config.scalingPerLevel

        val stats = definition.baseStats.let { base ->
            base.copy(
                maxHealth = (base.maxHealth * rank.healthMultiplier * levelScale).roundToInt().coerceAtLeast(1),
                attackPower = (base.attackPower * rank.damageMultiplier * levelScale).roundToInt().coerceAtLeast(1),
            )
        }.let(difficulty.monsters::applyTo)

        return EnemyInstance(
            instanceId = "enemy_${random.nextLong().toULong().toString(16)}",
            definitionId = definition.id,
            name = if (rank == EnemyRank.MINION) definition.name else "${rank.name.lowercase().replaceFirstChar { it.uppercase() }} ${definition.name}",
            rank = rank,
            position = position,
            health = stats.maxHealth,
            stats = stats,
            damageTypeId = definition.damageTypeId,
            experience = (definition.experience * rank.experienceMultiplier * levelScale).roundToInt(),
            bodyColor = definition.bodyColor,
            factionId = definition.factionId,
            role = definition.role,
        )
    }

    private fun rollRank(random: Random): EnemyRank {
        val roll = random.nextFloat()
        return when {
            roll < config.bossChance -> EnemyRank.BOSS
            roll < config.bossChance + config.championChance -> EnemyRank.CHAMPION
            roll < config.bossChance + config.championChance + config.eliteChance -> EnemyRank.ELITE
            else -> EnemyRank.MINION
        }
    }

    /**
     * Advances one monster: decide what it wants, then move it there.
     *
     * Movement is voxel-aware in the same way the player's is -- it climbs one
     * block and refuses a wall -- so monsters cannot walk through terrain the
     * player has to dig through.
     */
    fun advance(
        enemy: EnemyInstance,
        target: WorldPoint,
        deltaSeconds: Float,
    ): EnemyInstance {
        if (!enemy.isAlive) return enemy

        val definition = byId[enemy.definitionId]
        val aggroRange = definition?.aggroRange ?: DEFAULT_AGGRO
        val distance = enemy.position.horizontalDistanceTo(target)
        val cooled = (enemy.attackCooldown - deltaSeconds).coerceAtLeast(0f)

        val shouldFlee = definition?.canFlee == true &&
            enemy.healthFraction < definition.fleeBelowHealth

        val state = when {
            shouldFlee -> EnemyState.FLEEING
            distance <= enemy.stats.attackRange -> EnemyState.ATTACKING
            distance <= aggroRange -> EnemyState.CHASING
            else -> EnemyState.IDLE
        }

        val speed = (definition?.moveSpeed ?: DEFAULT_SPEED) * deltaSeconds
        val moved = when (state) {
            // Never run past what it is running at. Without the clamp a long
            // frame carries a monster clean through the player and out the far
            // side, and the chase turns into an oscillation the player can only
            // watch. Closing to exactly its reach is the most it ever wants.
            EnemyState.CHASING ->
                step(enemy.position, target, speed.coerceAtMost(distance - enemy.stats.attackRange))
            EnemyState.FLEEING -> step(enemy.position, target, -speed)
            else -> enemy.position
        }

        // Kept from the last step that actually happened. A monster blocked
        // against a wall, or standing in reach and swinging, should hold the
        // way it was already turned rather than snapping back to a default.
        val dx = moved.x - enemy.position.x
        val dy = moved.y - enemy.position.y
        val turned = if (dx * dx + dy * dy > TURN_EPSILON) {
            enemy.copy(facingX = dx, facingY = dy)
        } else if (state == EnemyState.ATTACKING) {
            // Except when swinging: it is looking at what it is hitting.
            enemy.copy(facingX = target.x - enemy.position.x, facingY = target.y - enemy.position.y)
        } else {
            enemy
        }

        return turned.copy(position = moved, state = state, attackCooldown = cooled)
    }

    /**
     * Moves one step toward (or away from) a point, resolving terrain. Returns
     * the original position when the way is blocked, so a monster stuck against
     * a wall stays put rather than vibrating through it.
     */
    private fun step(from: WorldPoint, toward: WorldPoint, distance: Float): WorldPoint {
        val dx = toward.x - from.x
        val dy = toward.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-4f) return from
        if (distance == 0f) return from

        val nx = from.x + (dx / length) * distance
        val ny = from.y + (dy / length) * distance

        val column = BlockPos(kotlin.math.floor(nx).toInt(), kotlin.math.floor(ny).toInt(), 0)
        val currentZ = from.toBlockPos().z

        var highestSolid = -1
        for (z in (currentZ + STEP_UP) downTo 0) {
            if (world.isSolid(BlockPos(column.x, column.y, z))) {
                highestSolid = z
                break
            }
        }
        val standingZ = highestSolid + 1
        if (standingZ - currentZ > STEP_UP) return from
        if (standingZ >= Chunk.HEIGHT) return from

        return WorldPoint(nx, ny, standingZ.toFloat())
    }

    private fun pickWeighted(items: List<EnemyDefinition>, random: Random): EnemyDefinition? {
        if (items.isEmpty()) return null
        val total = items.sumOf { it.spawnWeight.coerceAtLeast(0) }
        if (total <= 0) return items[random.nextInt(items.size)]
        var roll = random.nextInt(total)
        for (item in items) {
            roll -= item.spawnWeight.coerceAtLeast(0)
            if (roll < 0) return item
        }
        return items.last()
    }

    private companion object {
        const val TWO_PI = (Math.PI * 2).toFloat()
        const val STEP_UP = 1
        const val DEFAULT_AGGRO = 8
        const val DEFAULT_SPEED = 2.2f
        const val PACK_SPREAD = 1.6f
        const val GARRISON_REACH = 60f

        /**
         * Below this a step is not a turn.
         *
         * A monster closing the last fraction of an inch towards its reach
         * moves by almost nothing, and dividing by that would make its facing
         * flap between frames -- which on a sheet with back art is a body
         * spinning on the spot.
         */
        private const val TURN_EPSILON = 1e-6f
    }
}

/** How crowded and how dangerous the world is. */
data class DirectorConfig(
    val maxAlive: Int = 12,
    /** Nothing spawns closer than this, in blocks. */
    val safeRadius: Float = 9f,
    val spawnRadius: Float = 22f,
    /** Monsters beyond this are forgotten rather than simulated forever. */
    val despawnRadius: Float = 38f,
    val maxSpawnAttempts: Int = 24,
    val eliteChance: Float = 0.12f,
    val championChance: Float = 0.04f,
    val bossChance: Float = 0.01f,
    val scalingPerLevel: Float = 0.12f,
    /** Share of spawns that are a whole pack rather than one monster, when packs are defined. */
    val packChance: Float = 0.35f,
) {
    init {
        require(safeRadius < spawnRadius) { "Spawn ring is inverted" }
        require(spawnRadius < despawnRadius) { "Monsters would despawn as they spawn" }
    }
}

/** Horizontal distance, which is what aggro and reach are measured in. */
fun WorldPoint.horizontalDistanceTo(other: WorldPoint): Float {
    val dx = x - other.x
    val dy = y - other.y
    return sqrt(dx * dx + dy * dy)
}

/** Chebyshev distance in blocks, used where reach is an integer. */
fun WorldPoint.blockDistanceTo(other: WorldPoint): Int =
    maxOf(abs(x - other.x), abs(y - other.y)).roundToInt()
