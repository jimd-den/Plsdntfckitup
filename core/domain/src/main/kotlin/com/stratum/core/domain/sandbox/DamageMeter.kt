package com.stratum.core.domain.sandbox

import com.stratum.core.domain.combat.DamageDealt
import com.stratum.core.domain.combat.DamageSource

/**
 * Counts the player's damage the way a build is judged: damage per second
 * over a rolling window, the total since the last reset, the biggest hit, how
 * often hits crit, and who did what -- by skill, trigger, ailment and minion,
 * and by damage type.
 *
 * It keeps its own clock, advanced by the caller, rather than reading the
 * time: the same fight fed in replays to the same numbers, and a test can say
 * "ten hits over two seconds" without waiting for either. Totals since the
 * reset are kept as running sums, so a broken build landing hundreds of hits
 * a second costs the meter the rolling window and nothing more.
 */
class DamageMeter(
    /** Seconds the rolling damage per second looks back over. */
    val windowSeconds: Float = DEFAULT_WINDOW,
) {
    init {
        require(windowSeconds > 0f) { "a damage meter needs a window longer than nothing" }
    }

    private data class Stamped(val at: Float, val amount: Float)

    private class SourceTally(val source: DamageSource) {
        var total = 0.0
        var hits = 0
        var crits = 0
        var biggest = 0f
    }

    private val window = ArrayDeque<Stamped>()
    private val sources = LinkedHashMap<DamageSource, SourceTally>()
    private val byType = LinkedHashMap<String, Double>()
    private var total = 0.0
    private var hits = 0
    private var crits = 0
    private var evaded = 0
    private var blocked = 0
    private var biggest: DamageDealt? = null
    private var firstAt: Float? = null

    /** Seconds since the meter was made or last reset. */
    var clock: Float = 0f
        private set

    fun advance(deltaSeconds: Float) {
        if (deltaSeconds > 0f) clock += deltaSeconds
        trim()
    }

    fun record(dealt: DamageDealt) {
        if (firstAt == null) firstAt = clock
        val tally = sources.getOrPut(dealt.source) { SourceTally(dealt.source) }
        when {
            dealt.evaded -> evaded++
            dealt.blocked && dealt.amount <= 0f -> blocked++
        }
        if (!dealt.overTime && !dealt.evaded) {
            hits++
            tally.hits++
            if (dealt.critical) {
                crits++
                tally.crits++
            }
            if (dealt.amount > (biggest?.amount ?: 0f)) biggest = dealt
            if (dealt.amount > tally.biggest) tally.biggest = dealt.amount
        }
        if (dealt.amount <= 0f) return
        total += dealt.amount
        tally.total += dealt.amount
        dealt.packets.forEach { (type, amount) -> byType[type] = (byType[type] ?: 0.0) + amount }
        window.addLast(Stamped(clock, dealt.amount))
    }

    /** Forgets everything, and starts the clock again. */
    fun reset() {
        window.clear()
        sources.clear()
        byType.clear()
        total = 0.0
        hits = 0
        crits = 0
        evaded = 0
        blocked = 0
        biggest = null
        firstAt = null
        clock = 0f
    }

    fun report(): MeterReport {
        trim()
        val start = firstAt
        val fighting = if (start == null) 0f else clock - start
        val windowSpan = minOf(windowSeconds, fighting).coerceAtLeast(MIN_SPAN)
        val overallSpan = fighting.coerceAtLeast(MIN_SPAN)
        val sum = total.toFloat()
        return MeterReport(
            windowDps = if (start == null) 0f else window.sumOf { it.amount.toDouble() }.toFloat() / windowSpan,
            averageDps = if (start == null) 0f else sum / overallSpan,
            total = sum,
            seconds = fighting,
            hits = hits,
            critRate = if (hits == 0) 0f else crits.toFloat() / hits,
            evaded = evaded,
            blocked = blocked,
            biggestHit = biggest?.amount ?: 0f,
            biggestSource = biggest?.source,
            bySource = sources.values
                .map { SourceShare(it.source, it.total.toFloat(), share(it.total), it.hits, if (it.hits == 0) 0f else it.crits.toFloat() / it.hits, it.biggest) }
                .sortedByDescending { it.total },
            byType = byType.entries.map { (type, amount) -> TypeShare(type, amount.toFloat(), share(amount)) }.sortedByDescending { it.total },
        )
    }

    private fun share(amount: Double): Float = if (total <= 0.0) 0f else (amount / total).toFloat()

    private fun trim() {
        while (window.isNotEmpty() && clock - window.first().at > windowSeconds) window.removeFirst()
    }

    companion object {
        const val DEFAULT_WINDOW = 10f

        /**
         * The shortest span damage per second is measured over, so the first
         * hit of a fight reads as that hit's damage rather than as infinity.
         */
        const val MIN_SPAN = 1f
    }
}

/** A snapshot of the meter, for a panel to draw. */
data class MeterReport(
    /** Damage per second over the rolling window. */
    val windowDps: Float = 0f,
    /** Damage per second since the first hit after the last reset. */
    val averageDps: Float = 0f,
    val total: Float = 0f,
    /** Seconds since the first hit after the last reset. */
    val seconds: Float = 0f,
    /** Hits that connected or were blocked: evasions and damage over time are not hits. */
    val hits: Int = 0,
    val critRate: Float = 0f,
    val evaded: Int = 0,
    val blocked: Int = 0,
    val biggestHit: Float = 0f,
    val biggestSource: DamageSource? = null,
    /** Every source, biggest first. */
    val bySource: List<SourceShare> = emptyList(),
    /** Every damage type, biggest first. */
    val byType: List<TypeShare> = emptyList(),
) {
    val isEmpty: Boolean get() = total <= 0f && hits == 0 && evaded == 0
}

data class SourceShare(
    val source: DamageSource,
    val total: Float,
    /** 0..1 of everything dealt. */
    val share: Float,
    val hits: Int,
    val critRate: Float,
    val biggestHit: Float,
)

data class TypeShare(val damageTypeId: String, val total: Float, val share: Float)
