package com.stratum.core.domain.status

import com.stratum.core.domain.stats.StatModifier

/** The loaded statuses by id, for the questions a [StatusSet] asks of them. */
class StatusBook(statuses: List<StatusDefinition>) {
    private val byId = statuses.associateBy { it.id }

    operator fun get(id: String): StatusDefinition? = byId[id]

    val all: Collection<StatusDefinition> get() = byId.values

    companion object {
        val EMPTY = StatusBook(emptyList())
    }
}

/** Damage a status dealt over one slice of time, before the defender's resistances. */
data class DotTick(val damageTypeId: String, val amount: Float, val sourceId: String?, val statusId: String)

/**
 * Every status on one body. Immutable: applying, ticking and expiring each
 * return a new set, so the body that carries it stays an immutable snapshot
 * like everything else the renderer reads.
 */
data class StatusSet(val instances: List<StatusInstance> = emptyList()) {

    val isEmpty: Boolean get() = instances.isEmpty()

    fun has(statusId: String): Boolean = instances.any { it.statusId == statusId }

    fun stacksOf(statusId: String): Int = instances.filter { it.statusId == statusId }.sumOf { it.stacks }

    /** Whether any status carried has [tag], or is itself called [tag]. */
    fun hasTag(tag: String, book: StatusBook): Boolean =
        instances.any { it.statusId == tag || book[it.statusId]?.tags?.contains(tag) == true }

    /**
     * Adds [application] following the status's stacking rule, never beyond
     * [stackCap] -- the world's ceiling -- whatever the status itself allows.
     */
    fun applying(definition: StatusDefinition, application: StatusApplication, stackCap: Int = Int.MAX_VALUE): StatusSet {
        val duration = definition.durationSeconds * application.durationScale.coerceAtLeast(0f)
        if (duration <= 0f) return this
        val limit = definition.maxStacks.coerceAtMost(stackCap).coerceAtLeast(1)
        val existing = instances.filter { it.statusId == definition.id }
        val others = instances.filter { it.statusId != definition.id }
        val fresh = StatusInstance(definition.id, duration, duration, application.stacks.coerceIn(1, limit), application.potency, application.sourceId)
        val updated = when (definition.stacking) {
            StackingRule.REFRESH -> {
                val current = existing.firstOrNull()
                listOf(
                    if (current == null) fresh.copy(stacks = 1)
                    else current.copy(
                        remaining = maxOf(current.remaining, duration),
                        duration = maxOf(current.remaining, duration),
                        potency = maxOf(current.potency, application.potency),
                        sourceId = if (application.potency >= current.potency) application.sourceId else current.sourceId,
                    ),
                )
            }
            // The oldest instance is the one with the least time left, and it is the one that falls off.
            StackingRule.STACK -> (existing + List(application.stacks.coerceAtLeast(1)) { fresh.copy(stacks = 1) })
                .sortedByDescending { it.remaining }.take(limit)
            StackingRule.INTENSITY -> {
                val current = existing.firstOrNull()
                listOf(
                    if (current == null) fresh
                    else current.copy(
                        stacks = (current.stacks + application.stacks).coerceAtMost(limit),
                        remaining = duration,
                        duration = duration,
                        potency = maxOf(current.potency, application.potency),
                    ),
                )
            }
        }
        return StatusSet(others + updated)
    }

    fun removing(statusId: String): StatusSet = StatusSet(instances.filter { it.statusId != statusId })

    /** The same set [deltaSeconds] later, with anything expired gone. */
    fun advanced(deltaSeconds: Float): StatusSet {
        if (instances.isEmpty()) return this
        return StatusSet(instances.map { it.copy(remaining = it.remaining - deltaSeconds) }.filter { it.remaining > 0f })
    }

    /** What damage over time deals across the next [deltaSeconds], clipped to each status's remaining time. */
    fun dotTicks(deltaSeconds: Float, book: StatusBook): List<DotTick> = instances.flatMap { instance ->
        val window = minOf(deltaSeconds, instance.remaining).coerceAtLeast(0f)
        book[instance.statusId]?.behaviours.orEmpty().filterIsInstance<StatusBehaviour.DamageOverTime>().map { dot ->
            DotTick(dot.damageTypeId, (dot.perSecond + dot.hitShare * instance.potency) * instance.stacks * window, instance.sourceId, instance.statusId)
        }
    }.filter { it.amount > 0f }

    /** How much slower this body moves and acts, 0..1: the strongest slow, not the sum of them. */
    fun slow(book: StatusBook): Float = instances.maxOfOrNull { instance ->
        book[instance.statusId]?.behaviours.orEmpty().filterIsInstance<StatusBehaviour.Slow>().maxOfOrNull { it.amount * instance.stacks } ?: 0f
    }?.coerceIn(0f, 1f) ?: 0f

    fun isStunned(book: StatusBook): Boolean = instances.any { instance ->
        book[instance.statusId]?.behaviours.orEmpty().any { it is StatusBehaviour.Stun }
    }

    /** Extra damage taken from [damageTypeId], as a share: 0.3 is 30% increased. */
    fun damageTakenShare(damageTypeId: String, book: StatusBook): Float = instances.sumOf { instance ->
        book[instance.statusId]?.behaviours.orEmpty().filterIsInstance<StatusBehaviour.DamageTaken>()
            .filter { it.damageTypeId == null || it.damageTypeId == damageTypeId }
            .sumOf { (it.amount * instance.stacks).toDouble() }
    }.toFloat()

    /** Every stat change carried, each counted once per stack. */
    fun modifiers(book: StatusBook): List<StatModifier> = instances.flatMap { instance ->
        book[instance.statusId]?.behaviours.orEmpty().filterIsInstance<StatusBehaviour.Modifiers>().flatMap { behaviour ->
            behaviour.modifiers.map { it.copy(value = it.value * instance.stacks) }
        }
    }

    /** Life restored per second against [maxHealth]. */
    fun recoveryPerSecond(maxHealth: Int, book: StatusBook): Float = instances.sumOf { instance ->
        book[instance.statusId]?.behaviours.orEmpty().filterIsInstance<StatusBehaviour.Recover>()
            .sumOf { ((it.perSecond + it.maxShare * maxHealth) * instance.stacks).toDouble() }
    }.toFloat()

    companion object {
        val EMPTY = StatusSet()
    }
}
