package com.stratum.engine.crowd

import com.stratum.core.domain.actor.CombatRole
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** One body in the crowd, as much as the brain needs to know about it. */
data class CrowdAgent(
    val id: String,
    val position: Vec2,
    val z: Int,
    val role: CombatRole = CombatRole.MELEE,
    val squadId: String? = null,
    val isLeader: Boolean = false,
    /** Blocks per second. */
    val speed: Float = 2.2f,
    /** How far its attack reaches. */
    val reach: Float = 1f,
    /** How far away it notices the target. */
    val aggroRange: Float = 8f,
    val healthFraction: Float = 1f,
    /** Below this share of health it runs; 0 never. */
    val fleeBelow: Float = 0f,
    /** Where it goes back to when it loses interest: a garrison's post. */
    val home: Vec2? = null,
)

/** What the crowd is after. */
data class CrowdTarget(val position: Vec2, val z: Int)

/** What a body is doing this tick, for animation and for the rules that decide who may swing. */
enum class CrowdStance { IDLE, ADVANCE, ATTACK, CIRCLE, KITE, SUPPORT, FLEE, RETURN }

/** Where a body wants to go and how fast, as a share of its speed. */
data class Intent(val direction: Vec2, val speedFactor: Float, val stance: CrowdStance) {
    companion object {
        val IDLE = Intent(Vec2.ZERO, 0f, CrowdStance.IDLE)
    }
}

data class CrowdConfig(
    /**
     * How many melee bodies may be swinging at the target at once. The rest
     * circle, waiting their turn. Readable fights on a small screen come from
     * this more than from anything else: twelve monsters stacked on one tile
     * is a smear, four swinging while eight prowl is a fight.
     */
    val maxAttackers: Int = 4,
    val separationRadius: Float = 0.9f,
    val separationWeight: Float = 1.4f,
    /** How far out the waiting ring stands, beyond reach. */
    val circleGap: Float = 2.2f,
    /** A squad whose numbers fall below this share of its peak breaks and runs. */
    val breakBelow: Float = 0.4f,
    val brokenSeconds: Float = 4f,
    /** How long a pack stays alert after one of it sees the target. */
    val alertSeconds: Float = 6f,
)

/**
 * The part of the crowd that has to be remembered between ticks: who holds
 * an attack token, which packs are alert, which have broken.
 */
class CrowdMemory {
    internal val peak = HashMap<String, Int>()
    internal val leaders = HashSet<String>()
    internal val alertFor = HashMap<String, Float>()
    internal val brokenFor = HashMap<String, Float>()
    internal val hasBroken = HashSet<String>()
    internal var attackers: Set<String> = emptySet()

    val alertSquads: Set<String> get() = alertFor.keys
    val brokenSquads: Set<String> get() = brokenFor.keys

    /** Ticks timers down and notices packs that have lost their leader or most of their number. */
    internal fun update(agents: List<CrowdAgent>, dt: Float, config: CrowdConfig) {
        decay(alertFor, dt)
        decay(brokenFor, dt)
        val bySquad = agents.filter { it.squadId != null }.groupBy { it.squadId!! }
        bySquad.forEach { (squad, members) ->
            peak[squad] = maxOf(peak[squad] ?: 0, members.size)
            if (members.any { it.isLeader }) leaders += squad
            val leaderLost = squad in leaders && members.none { it.isLeader }
            val thinned = members.size < (peak[squad] ?: 0) * config.breakBelow
            // A pack breaks once. After that it has already run; it fights to the end.
            if ((leaderLost || thinned) && squad !in hasBroken) {
                hasBroken += squad
                brokenFor[squad] = config.brokenSeconds
            }
        }
    }

    private fun decay(timers: HashMap<String, Float>, dt: Float) {
        timers.replaceAll { _, left -> left - dt }
        timers.values.removeAll { it <= 0f }
    }
}

/**
 * Decides what every body in a crowd does this tick.
 *
 * Pure apart from [CrowdMemory]: the same crowd, target and memory always
 * give the same intents, so a fight replays and a test can pin down a
 * behaviour. The engine owns movement; this only says where each body would
 * like to go.
 *
 * Behaviour by [CombatRole]:
 * - **Melee, brute:** take one of the attack tokens and a slot around the
 *   target, so a pack surrounds rather than stacks; without a token, circle
 *   at a distance and wait.
 * - **Swarmer:** ignore tokens and rush. Swarms are meant to overwhelm.
 * - **Ranged:** close to its reach, back off when crowded, strafe between.
 * - **Support:** stay behind its pack, between the pack and away from the target.
 * Packs alert together, and break and run when their leader falls or most of
 * them do.
 */
class CrowdBrain(private val config: CrowdConfig = CrowdConfig()) {

    fun think(agents: List<CrowdAgent>, target: CrowdTarget?, field: FlowField?, memory: CrowdMemory, dt: Float): Map<String, Intent> {
        memory.update(agents, dt, config)
        if (target == null) return agents.associate { it.id to wander(it) }
        val engaged = engaged(agents, target, memory)
        memory.attackers = attackers(agents, engaged, target, memory)
        val slots = slots(agents.filter { it.id in memory.attackers }, target)
        val hash = SpatialHash(config.separationRadius * 2, agents) { it.position }
        val squads = agents.filter { it.squadId != null }.groupBy { it.squadId!! }
        return agents.associate { agent ->
            val intent = when {
                agent.id !in engaged -> wander(agent)
                fleeing(agent, memory) -> flee(agent, target, field)
                else -> fight(agent, target, field, slots, squads, memory)
            }
            agent.id to separated(agent, intent, hash)
        }
    }

    /** Who is in the fight: those who can see the target, and every member of a pack that one of them alerted. */
    private fun engaged(agents: List<CrowdAgent>, target: CrowdTarget, memory: CrowdMemory): Set<String> {
        val seeing = agents.filter { (it.position - target.position).length <= it.aggroRange }
        seeing.mapNotNull { it.squadId }.forEach { memory.alertFor[it] = config.alertSeconds }
        return agents.filter { it in seeing || it.squadId in memory.alertFor }.mapTo(HashSet()) { it.id }
    }

    /** The nearest token-takers, keeping those who already hold one so the front line does not churn. */
    private fun attackers(agents: List<CrowdAgent>, engaged: Set<String>, target: CrowdTarget, memory: CrowdMemory): Set<String> {
        val eligible = agents.filter { it.id in engaged && takesToken(it) && !fleeing(it, memory) }
        val kept = eligible.filter { it.id in memory.attackers }.take(config.maxAttackers)
        val rest = eligible.filter { it.id !in memory.attackers }.sortedBy { (it.position - target.position).length }
        return (kept + rest).take(config.maxAttackers).mapTo(HashSet()) { it.id }
    }

    private fun takesToken(agent: CrowdAgent) = agent.role == CombatRole.MELEE || agent.role == CombatRole.BRUTE

    /** Evenly spaced places around the target, handed out in the order the attackers already stand, so no two cross. */
    private fun slots(attackers: List<CrowdAgent>, target: CrowdTarget): Map<String, Vec2> {
        if (attackers.isEmpty()) return emptyMap()
        val byAngle = attackers.sortedBy { angleOf(it.position - target.position) }
        val start = angleOf(byAngle.first().position - target.position)
        return byAngle.mapIndexed { i, agent ->
            val angle = start + i * 2 * PI / byAngle.size
            val distance = (agent.reach * SLOT_SHARE).coerceAtLeast(MIN_SLOT)
            agent.id to target.position + Vec2(cos(angle).toFloat(), sin(angle).toFloat()) * distance
        }.toMap()
    }

    private fun fight(
        agent: CrowdAgent,
        target: CrowdTarget,
        field: FlowField?,
        slots: Map<String, Vec2>,
        squads: Map<String, List<CrowdAgent>>,
        memory: CrowdMemory,
    ): Intent = when (agent.role) {
        CombatRole.SWARMER -> rush(agent, target, field)
        CombatRole.RANGED -> keepRange(agent, target, field)
        CombatRole.SUPPORT -> support(agent, target, squads[agent.squadId].orEmpty())
        CombatRole.MELEE, CombatRole.BRUTE ->
            if (agent.id in memory.attackers) takeSlot(agent, target, field, slots.getValue(agent.id)) else circle(agent, target, field)
    }

    private fun rush(agent: CrowdAgent, target: CrowdTarget, field: FlowField?): Intent {
        val distance = (agent.position - target.position).length
        return if (distance <= agent.reach) Intent(Vec2.ZERO, 0f, CrowdStance.ATTACK)
        else Intent(seek(agent, target.position, target, field), 1f, CrowdStance.ADVANCE)
    }

    private fun takeSlot(agent: CrowdAgent, target: CrowdTarget, field: FlowField?, slot: Vec2): Intent {
        val toTarget = (target.position - agent.position).length
        if (toTarget <= agent.reach) return Intent(Vec2.ZERO, 0f, CrowdStance.ATTACK)
        // Far off, follow the field round obstacles; close in, walk to the slot so the pack spreads round.
        val goal = if (toTarget > FIELD_UNTIL) target.position else slot
        return Intent(seek(agent, goal, target, field), 1f, CrowdStance.ADVANCE)
    }

    /** Waiting its turn: hold a ring outside reach and prowl round it. */
    private fun circle(agent: CrowdAgent, target: CrowdTarget, field: FlowField?): Intent {
        val offset = agent.position - target.position
        val distance = offset.length
        val ring = agent.reach + config.circleGap
        return when {
            distance > ring + RING_SLACK -> Intent(seek(agent, target.position, target, field), 1f, CrowdStance.ADVANCE)
            distance < ring - RING_SLACK -> Intent(offset.normalized(), CIRCLE_SPEED, CrowdStance.CIRCLE)
            else -> Intent(offset.normalized().perpendicular() * orbitSign(agent), CIRCLE_SPEED, CrowdStance.CIRCLE)
        }
    }

    private fun keepRange(agent: CrowdAgent, target: CrowdTarget, field: FlowField?): Intent {
        val offset = agent.position - target.position
        val distance = offset.length
        return when {
            distance > agent.reach -> Intent(seek(agent, target.position, target, field), 1f, CrowdStance.ADVANCE)
            distance < agent.reach * KITE_BELOW -> Intent(offset.normalized(), 1f, CrowdStance.KITE)
            else -> Intent(offset.normalized().perpendicular() * orbitSign(agent), STRAFE_SPEED, CrowdStance.ATTACK)
        }
    }

    /** Behind its pack: at the pack's centre, pushed back from the target. */
    private fun support(agent: CrowdAgent, target: CrowdTarget, squad: List<CrowdAgent>): Intent {
        val others = squad.filter { it.id != agent.id }
        if (others.isEmpty()) return keepRange(agent.copy(reach = agent.reach.coerceAtLeast(SUPPORT_DISTANCE)), target, null)
        val centre = others.fold(Vec2.ZERO) { sum, it -> sum + it.position } * (1f / others.size)
        val away = (centre - target.position).normalized()
        val post = centre + away * SUPPORT_BEHIND
        val toPost = post - agent.position
        return if (toPost.length < ARRIVED) Intent(Vec2.ZERO, 0f, CrowdStance.SUPPORT)
        else Intent(toPost.normalized(), 1f, CrowdStance.SUPPORT)
    }

    private fun flee(agent: CrowdAgent, target: CrowdTarget, field: FlowField?): Intent {
        val downhill = field?.directionAt(cell(agent.position.x), cell(agent.position.y))
        val away = (agent.position - target.position).normalized()
        // Running from the target means running up its field: the reverse of the way toward it.
        val direction = if (downhill != null && downhill != Vec2.ZERO) (downhill * -1f + away).normalized() else away
        return Intent(direction, 1f, CrowdStance.FLEE)
    }

    /** Out of the fight: back to its post if it has one, else stay. */
    private fun wander(agent: CrowdAgent): Intent {
        val home = agent.home ?: return Intent.IDLE
        val toHome = home - agent.position
        return if (toHome.length < ARRIVED) Intent.IDLE else Intent(toHome.normalized(), RETURN_SPEED, CrowdStance.RETURN)
    }

    private fun fleeing(agent: CrowdAgent, memory: CrowdMemory): Boolean =
        (agent.fleeBelow > 0f && agent.healthFraction < agent.fleeBelow) || agent.squadId in memory.brokenFor

    /** The way toward [goal]: along the field when the goal is the field's target and the field covers here, straight otherwise. */
    private fun seek(agent: CrowdAgent, goal: Vec2, target: CrowdTarget, field: FlowField?): Vec2 {
        if (field != null && goal == target.position) {
            field.directionAt(cell(agent.position.x), cell(agent.position.y))?.takeIf { it != Vec2.ZERO }?.let { return it }
        }
        return (goal - agent.position).normalized()
    }

    /** Pushes bodies apart so a crowd holds a shape instead of collapsing onto one point. */
    private fun separated(agent: CrowdAgent, intent: Intent, hash: SpatialHash<CrowdAgent>): Intent {
        val push = hash.near(agent.position, config.separationRadius)
            .filter { it.id != agent.id }
            .fold(Vec2.ZERO) { sum, other ->
                val away = agent.position - other.position
                val overlap = (config.separationRadius - away.length) / config.separationRadius
                sum + (if (away.length < TIE) tieBreak(agent, other) else away.normalized()) * overlap
            }
        if (push == Vec2.ZERO) return intent
        val direction = (intent.direction + push * config.separationWeight).normalized()
        val speed = maxOf(intent.speedFactor, (push.length * config.separationWeight).coerceAtMost(1f) * NUDGE_SPEED)
        return intent.copy(direction = direction, speedFactor = speed)
    }

    /** Two bodies on exactly the same spot need a deterministic way apart. */
    private fun tieBreak(a: CrowdAgent, b: CrowdAgent): Vec2 = if (a.id < b.id) Vec2(1f, 0f) else Vec2(-1f, 0f)

    /** Half the pack circles one way and half the other, decided by id so it holds from tick to tick. */
    private fun orbitSign(agent: CrowdAgent): Float = if (agent.id.hashCode() and 1 == 0) 1f else -1f

    private fun angleOf(v: Vec2): Double = atan2(v.y.toDouble(), v.x.toDouble())

    private fun cell(v: Float): Int = floor(v).toInt()

    private companion object {
        const val SLOT_SHARE = 0.8f
        const val MIN_SLOT = 0.8f
        const val FIELD_UNTIL = 3f
        const val RING_SLACK = 0.6f
        const val CIRCLE_SPEED = 0.45f
        const val STRAFE_SPEED = 0.35f
        const val KITE_BELOW = 0.5f
        const val SUPPORT_DISTANCE = 6f
        const val SUPPORT_BEHIND = 2.5f
        const val ARRIVED = 0.6f
        const val RETURN_SPEED = 0.6f
        const val NUDGE_SPEED = 0.5f
        const val TIE = 1e-3f
    }
}
