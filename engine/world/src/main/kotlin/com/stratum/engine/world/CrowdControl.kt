package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyState
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
    private val fields = FlowFieldCache()
    private val grid = WorldNavGrid(world)

    fun advance(enemies: List<EnemyInstance>, target: WorldPoint, hostile: (EnemyInstance) -> Boolean, deltaSeconds: Float): List<EnemyInstance> {
        val living = enemies.filter { it.isAlive }
        val (fighters, bystanders) = living.partition(hostile)
        val goal = target.toBlockPos()
        val field = if (fighters.isEmpty()) null else fields.fieldFor(goal.x, goal.y, goal.z, grid, deltaSeconds)
        val intents = brain.think(fighters.map(::agentOf), CrowdTarget(Vec2(target.x, target.y), goal.z), field, fighting, deltaSeconds) +
            brain.think(bystanders.map(::agentOf), null, null, idle, deltaSeconds)
        return enemies.map { enemy -> if (enemy.isAlive) moved(enemy, intents[enemy.instanceId] ?: Intent.IDLE, target, deltaSeconds) else enemy }
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
    }
}
