package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Which side of a fight something is on: the player and their followers, or the monsters. */
enum class CombatSide { PLAYER, MONSTERS }

/**
 * A projectile in flight: a real thing in the world, not a delayed hit.
 *
 * It moves every tick, stops at blocks it cannot pass, and touches whatever
 * it passes close to, so a wall is cover, a corridor funnels, and a roll
 * through a volley works because the volley was really there.
 */
data class Projectile(
    val id: Long,
    val skillId: String,
    val casterId: String,
    val side: CombatSide,
    val position: WorldPoint,
    val dx: Float,
    val dy: Float,
    val speed: Float,
    /** Blocks it may still travel. */
    val range: Float,
    val radius: Float,
    val pierce: Int,
    val chain: Int,
    val fork: Int,
    val collidesWithBlocks: Boolean,
    /** Everything it or its parent already hit: a fork or a chain never returns to the same body. */
    val hitIds: Set<String> = emptySet(),
    /** How many triggers deep the cast that fired it was. */
    val depth: Int = 0,
    val color: Long = 0xFFFFFFFF,
    /** A forged attack's look, drawn in full by renderers that can. */
    val look: com.stratum.core.domain.attack.AttackLook? = null,
)

/** A projectile touching a body. The projectile has already continued, split or stopped. */
internal data class ProjectileImpact(val projectile: Projectile, val targetId: String)

/**
 * Flies every projectile, and reports what each one touched.
 *
 * After a hit, in Path of Exile's order: pierce if it can, else fork into
 * two, else chain to the nearest body it has not hit, else stop. Damage is
 * not resolved here -- the combat system does that with the same rules as
 * every other hit -- this only decides where things fly.
 */
internal class ProjectileSystem(private val world: World) {

    private val flying = mutableListOf<Projectile>()
    private val landed = mutableListOf<Projectile>()
    private var nextId = 0L

    val active: List<Projectile> get() = flying.toList()

    fun launch(projectile: Projectile) {
        if (flying.size >= MAX_PROJECTILES) flying.removeAt(0)
        flying += projectile.copy(id = nextId++)
    }

    /** Moves everything by [deltaSeconds]; [targets] lists what each side's projectiles can touch. */
    fun advance(deltaSeconds: Float, targets: (CombatSide) -> List<Candidate>): List<ProjectileImpact> {
        if (flying.isEmpty()) return emptyList()
        val impacts = mutableListOf<ProjectileImpact>()
        val survivors = mutableListOf<Projectile>()
        val candidatesBySide = CombatSide.entries.associateWith { side -> lazy { targets(side) } }
        flying.forEach { projectile ->
            survivors += fly(projectile, deltaSeconds, candidatesBySide.getValue(projectile.side).value, impacts)
        }
        flying.clear()
        survivors.forEach { if (flying.size < MAX_PROJECTILES) flying += it.copy(id = if (it.id < 0) nextId++ else it.id) }
        return impacts
    }

    fun clear() { flying.clear(); landed.clear() }

    /**
     * Projectiles that came down this tick without striking a body: against a
     * wall, or at the end of their range. Where a voxel payload lands and an
     * on-voxel-hit attack begins. Each is handed out once.
     */
    fun drainLandings(): List<Projectile> = landed.toList().also { landed.clear() }

    /** One projectile's flight this tick, in short steps so it cannot tunnel through a wall or a body. */
    private fun fly(start: Projectile, deltaSeconds: Float, candidates: List<Candidate>, impacts: MutableList<ProjectileImpact>): List<Projectile> {
        var projectile = start
        var travel = minOf(projectile.speed * deltaSeconds, projectile.range)
        while (travel > 0f) {
            val step = minOf(STEP, travel)
            travel -= step
            val from = projectile.position
            val to = WorldPoint(from.x + projectile.dx * step, from.y + projectile.dy * step, from.z)
            projectile = projectile.copy(position = to, range = projectile.range - step)
            if (projectile.collidesWithBlocks && world.isSolid(BlockPos(floor(to.x).toInt(), floor(to.y).toInt(), floor(to.z).toInt()))) {
                if (landed.size < MAX_PROJECTILES) landed += projectile
                return emptyList()
            }
            val struck = candidates.firstOrNull { it.id !in projectile.hitIds && touches(from, to, it.position, projectile) }
            if (struck != null) {
                impacts += ProjectileImpact(projectile, struck.id)
                val continued = afterHit(projectile.copy(hitIds = projectile.hitIds + struck.id), struck, candidates)
                // Whatever continues flies on next tick; a split mid-step would double-count this one's travel.
                return continued
            }
            if (projectile.range <= 0f) {
                if (landed.size < MAX_PROJECTILES) landed += projectile
                return emptyList()
            }
        }
        return listOf(projectile)
    }

    private fun afterHit(projectile: Projectile, struck: Candidate, candidates: List<Candidate>): List<Projectile> = when {
        projectile.pierce > 0 -> listOf(projectile.copy(pierce = projectile.pierce - 1))
        projectile.fork > 0 -> {
            val heading = atan2(projectile.dy, projectile.dx)
            listOf(-FORK_ANGLE, FORK_ANGLE).map { turn ->
                projectile.copy(id = -1, dx = cos(heading + turn), dy = sin(heading + turn), fork = projectile.fork - 1)
            }
        }
        projectile.chain > 0 -> {
            val next = candidates.filter { it.id !in projectile.hitIds && it.position.horizontalDistanceTo(struck.position) <= CHAIN_RANGE }
                .minByOrNull { it.position.horizontalDistanceTo(struck.position) }
            if (next == null) emptyList()
            else {
                val aim = Aim.toward(struck.position, next.position)
                // The leap starts from the body it bounced off and gets its full range back.
                listOf(projectile.copy(dx = aim.dx, dy = aim.dy, chain = projectile.chain - 1, range = maxOf(projectile.range, CHAIN_RANGE + 1f),
                    position = WorldPoint(struck.position.x, struck.position.y, projectile.position.z)))
            }
        }
        else -> emptyList()
    }

    /** Whether the segment [from]-[to] passes within reach of a body standing at [body]. */
    private fun touches(from: WorldPoint, to: WorldPoint, body: WorldPoint, projectile: Projectile): Boolean {
        if (kotlin.math.abs(projectile.position.z - (body.z + BODY_CENTRE)) > BODY_HEIGHT) return false
        val sx = to.x - from.x
        val sy = to.y - from.y
        val length = sx * sx + sy * sy
        val t = if (length < 1e-6f) 0f else (((body.x - from.x) * sx + (body.y - from.y) * sy) / length).coerceIn(0f, 1f)
        val px = from.x + sx * t - body.x
        val py = from.y + sy * t - body.y
        val reach = projectile.radius + BODY_RADIUS
        return px * px + py * py <= reach * reach
    }

    companion object {
        /** Height above the feet a projectile flies at, and the middle of a body. */
        const val BODY_CENTRE = 0.9f
        private const val BODY_HEIGHT = 1.2f
        private const val BODY_RADIUS = 0.35f
        private const val STEP = 0.25f
        private const val CHAIN_RANGE = 6f
        private const val FORK_ANGLE = 0.5f

        /** A screen can only hold so many; a runaway build drops its oldest rather than the frame rate. */
        const val MAX_PROJECTILES = 256

        /** Spreads [count] directions across [spreadDegrees] around [aim], evenly, centre first for odd counts. */
        fun fan(aim: Aim, count: Int, spreadDegrees: Float): List<Aim> {
            if (count <= 1) return listOf(aim)
            val heading = atan2(aim.dy, aim.dx)
            val spread = Math.toRadians(spreadDegrees.toDouble()).toFloat()
            return List(count) { i ->
                val angle = heading - spread / 2f + spread * i / (count - 1)
                Aim(cos(angle), sin(angle))
            }
        }
    }
}
