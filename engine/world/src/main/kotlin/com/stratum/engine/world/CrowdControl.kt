package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyState
import com.stratum.core.domain.faction.Factions
import com.stratum.core.domain.strategy.FollowerOrder
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.crowd.CrowdAgent
import com.stratum.engine.crowd.CrowdBrain
import com.stratum.engine.crowd.CrowdConfig
import com.stratum.engine.crowd.CrowdMemory
import com.stratum.engine.crowd.CrowdStance
import com.stratum.engine.crowd.CrowdTarget
import com.stratum.engine.crowd.FlowField
import com.stratum.engine.crowd.FlowFieldCache
import com.stratum.engine.crowd.Intent
import com.stratum.engine.crowd.NavGrid
import com.stratum.engine.crowd.Vec2
import kotlin.math.floor

/**
 * The voxel world as the crowd sees it: where a body stands in a column,
 * searching from the height it arrives at. Unloaded ground is nowhere.
 */
internal class WorldNavGrid(private val world: World) : NavGrid {
    override fun standingZ(x: Int, y: Int, nearZ: Int): Int? {
        for (z in nearZ + FlowField.STEP_UP downTo (nearZ - FlowField.STEP_DOWN).coerceAtLeast(1)) {
            if (!world.isSolid(BlockPos(x, y, z)) && world.isSolid(BlockPos(x, y, z - 1))) return z
        }
        return null
    }
}

/** What the player's followers are told, and where the orders are measured from. */
internal data class AllyOrders(
    val order: FollowerOrder,
    val leader: WorldPoint,
    val holdAt: WorldPoint? = null,
    val returnTo: WorldPoint? = null,
) {
    /** How far from its post a follower will go to fight. */
    val leash: Float get() = when (order) {
        FollowerOrder.FOLLOW -> 8f
        FollowerOrder.HOLD -> 6f
        FollowerOrder.FIGHT -> 18f
        FollowerOrder.RETURN -> 0f
    }

    /** Where follower [index] stands: in a loose ring behind the leader, at the held point, or home. */
    fun anchorFor(index: Int): WorldPoint = when (order) {
        FollowerOrder.FOLLOW, FollowerOrder.FIGHT -> {
            val angle = index * GOLDEN_ANGLE
            WorldPoint(leader.x + kotlin.math.cos(angle).toFloat() * FORMATION, leader.y + kotlin.math.sin(angle).toFloat() * FORMATION, leader.z)
        }
        FollowerOrder.HOLD -> holdAt ?: leader
        FollowerOrder.RETURN -> returnTo ?: leader
    }

    private companion object {
        const val FORMATION = 2.2f
        const val GOLDEN_ANGLE = 2.39996
    }
}

/**
 * Moves every monster as part of a crowd: asks the [CrowdBrain] what each
 * one wants, then walks it there through the voxel world.
 *
 * Monsters hostile to the player fight it; the rest -- a friendly town's
 * guards, a neutral caravan -- go about their business and keep to their
 * posts. The brain never sees a block and the world never sees a squad.
 */
internal class CrowdControl(
    world: World,
    private val director: EnemyDirector,
    config: CrowdConfig = CrowdConfig(),
) {
    private val brain = CrowdBrain(config)
    private val fighting = CrowdMemory()
    private val idle = CrowdMemory()
    private val allies = CrowdMemory()
    private val fields = FlowFieldCache()
    private val grid = WorldNavGrid(world)

    fun advance(
        enemies: List<EnemyInstance>,
        target: WorldPoint,
        hostile: (EnemyInstance) -> Boolean,
        deltaSeconds: Float,
        orders: AllyOrders = AllyOrders(FollowerOrder.FOLLOW, target),
    ): List<EnemyInstance> {
        val living = enemies.filter { it.isAlive }
        val (friends, others) = living.partition { it.factionId == Factions.PLAYER }
        val (fighters, bystanders) = others.partition(hostile)
        val goal = target.toBlockPos()
        val field = if (fighters.isEmpty()) null else fields.fieldFor(goal.x, goal.y, goal.z, grid, deltaSeconds)
        val intents = brain.think(fighters.map(::agentOf), CrowdTarget(Vec2(target.x, target.y), goal.z), field, fighting, deltaSeconds) +
            brain.think(bystanders.map(::agentOf), null, null, idle, deltaSeconds)
        val allied = friends.mapIndexed { i, friend -> friend.instanceId to allyMove(friend, i, fighters, orders, deltaSeconds) }.toMap()
        return enemies.map { enemy ->
            when {
                !enemy.isAlive -> enemy
                enemy.instanceId in allied -> allied.getValue(enemy.instanceId)
                else -> moved(enemy, intents[enemy.instanceId] ?: Intent.IDLE, target, deltaSeconds)
            }
        }
    }

    /**
     * A follower's move: hold its place in the order -- a loose file behind
     * the player, a point to hold, the way home -- and break off to fight the
     * nearest foe that comes within its leash.
     */
    private fun allyMove(friend: EnemyInstance, index: Int, foes: List<EnemyInstance>, orders: AllyOrders, deltaSeconds: Float): EnemyInstance {
        // A garrison turned out to defend has a post of its own; followers take theirs from the order.
        val anchor = friend.home ?: orders.anchorFor(index)
        val leash = if (friend.home != null) DEFENDER_LEASH else orders.leash
        val foe = foes.filter { it.position.horizontalDistanceTo(anchor) <= leash }
            .minByOrNull { it.position.horizontalDistanceTo(friend.position) }
        val agent = agentOf(friend).copy(home = Vec2(anchor.x, anchor.y), aggroRange = leash + LEASH_SLACK)
        return if (foe != null) {
            val intent = brain.think(listOf(agent), CrowdTarget(Vec2(foe.position.x, foe.position.y), floor(foe.position.z).toInt()), null, allies, deltaSeconds)
            moved(friend, intent[friend.instanceId] ?: Intent.IDLE, foe.position, deltaSeconds)
        } else {
            val intent = brain.think(listOf(agent), null, null, allies, deltaSeconds)
            // Walking home is a jog, not an amble: followers keep up.
            moved(friend, (intent[friend.instanceId] ?: Intent.IDLE).let { it.copy(speedFactor = if (it.stance == CrowdStance.RETURN) 1f else it.speedFactor) }, anchor, deltaSeconds)
        }
    }

    fun clear() = fields.clear()

    private fun agentOf(enemy: EnemyInstance): CrowdAgent {
        val definition = director.definition(enemy.definitionId)
        return CrowdAgent(
            id = enemy.instanceId,
            position = Vec2(enemy.position.x, enemy.position.y),
            z = floor(enemy.position.z).toInt(),
            role = enemy.role,
            squadId = enemy.squadId,
            isLeader = enemy.isLeader,
            speed = definition?.moveSpeed ?: DEFAULT_SPEED,
            reach = enemy.stats.attackRange.toFloat(),
            aggroRange = (definition?.aggroRange ?: DEFAULT_AGGRO).toFloat(),
            healthFraction = enemy.healthFraction,
            fleeBelow = if (definition?.canFlee == true) definition.fleeBelowHealth else 0f,
            home = enemy.home?.let { Vec2(it.x, it.y) },
        )
    }

    private fun moved(enemy: EnemyInstance, intent: Intent, target: WorldPoint, deltaSeconds: Float): EnemyInstance {
        val speed = (director.definition(enemy.definitionId)?.moveSpeed ?: DEFAULT_SPEED) * intent.speedFactor * deltaSeconds
        val toTarget = enemy.position.horizontalDistanceTo(target)
        // Never run past what it is running at; closing to its reach is the most it wants.
        val step = if (intent.stance == CrowdStance.ADVANCE) speed.coerceAtMost((toTarget - enemy.stats.attackRange).coerceAtLeast(0f)) else speed
        val position = director.stepAlong(enemy.position, intent.direction, step)
        val dx = position.x - enemy.position.x
        val dy = position.y - enemy.position.y
        val state = stateOf(intent.stance, toTarget <= enemy.stats.attackRange)
        val facing = when {
            dx * dx + dy * dy > TURN_EPSILON -> dx to dy
            state == EnemyState.ATTACKING -> (target.x - enemy.position.x) to (target.y - enemy.position.y)
            else -> enemy.facingX to enemy.facingY
        }
        return enemy.copy(
            position = position,
            state = state,
            facingX = facing.first,
            facingY = facing.second,
            attackCooldown = (enemy.attackCooldown - deltaSeconds).coerceAtLeast(0f),
        )
    }

    private fun stateOf(stance: CrowdStance, inReach: Boolean): EnemyState = when (stance) {
        CrowdStance.IDLE -> EnemyState.IDLE
        CrowdStance.FLEE -> EnemyState.FLEEING
        CrowdStance.ATTACK -> if (inReach) EnemyState.ATTACKING else EnemyState.CHASING
        else -> EnemyState.CHASING
    }

    private companion object {
        const val DEFAULT_SPEED = 2.2f
        const val DEFAULT_AGGRO = 8
        const val TURN_EPSILON = 1e-6f
        const val LEASH_SLACK = 4f
        const val DEFENDER_LEASH = 20f
    }
}
