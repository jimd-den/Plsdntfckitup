package com.stratum.engine.world

import com.stratum.core.domain.world.WorldPoint

/**
 * A short-lived visual: a damage number, a miss, a level-up.
 *
 * Feedback is engine state rather than a UI side effect, so it survives the
 * renderer being recomposed, replays identically from a seed, and can be tested
 * without drawing anything.
 */
data class FeedbackMark(
    val id: Long,
    val kind: FeedbackKind,
    val text: String,
    val origin: WorldPoint,
    /** Counts up. The renderer maps it to rise and fade. */
    val age: Float = 0f,
    val lifetime: Float = DEFAULT_LIFETIME,
    /** Packed ARGB, usually the damage type's colour. */
    val color: Long = 0xFFFFFFFF,
    /** Crits and big hits draw larger. */
    val emphasis: Float = 1f,
) {
    val isExpired: Boolean get() = age >= lifetime

    /** 0 at birth, 1 at death. */
    val progress: Float get() = (age / lifetime).coerceIn(0f, 1f)

    fun advanced(deltaSeconds: Float): FeedbackMark = copy(age = age + deltaSeconds)

    companion object {
        const val DEFAULT_LIFETIME = 0.9f
    }
}

enum class FeedbackKind {
    DAMAGE_DEALT,
    DAMAGE_TAKEN,
    CRITICAL,
    BLOCKED,
    DODGED,
    HEAL,
    KILL,
    LEVEL_UP,
    LOOT,
}

/**
 * Holds the marks currently on screen and ages them.
 *
 * Capped: a nova landing on a dozen monsters at once would otherwise bury the
 * screen in numbers at the exact moment the player most needs to see it.
 */
class FeedbackLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private val marks = ArrayDeque<FeedbackMark>()
    private var nextId = 0L

    val active: List<FeedbackMark> get() = marks.toList()

    fun add(
        kind: FeedbackKind,
        text: String,
        origin: WorldPoint,
        color: Long = 0xFFFFFFFF,
        emphasis: Float = 1f,
        lifetime: Float = FeedbackMark.DEFAULT_LIFETIME,
    ): FeedbackMark {
        val mark = FeedbackMark(
            id = nextId++,
            kind = kind,
            text = text,
            origin = origin,
            color = color,
            emphasis = emphasis,
            lifetime = lifetime,
        )
        marks.addLast(mark)
        // Drop the oldest rather than refusing the newest: the most recent hit
        // is the one the player is looking for.
        while (marks.size > capacity) marks.removeFirst()
        return mark
    }

    fun advance(deltaSeconds: Float) {
        val aged = marks.map { it.advanced(deltaSeconds) }
        marks.clear()
        aged.filterNot { it.isExpired }.forEach(marks::addLast)
    }

    fun clear() = marks.clear()

    private companion object {
        const val DEFAULT_CAPACITY = 24
    }
}

/**
 * How long an actor stays lit after being hit.
 *
 * Tracked per actor id so twenty monsters can flash independently without the
 * renderer holding any state of its own.
 */
class HitFlashes(private val duration: Float = DEFAULT_DURATION) {

    private val flashes = HashMap<String, Float>()

    fun strike(actorId: String) {
        flashes[actorId] = duration
    }

    /** 0 when not flashing, 1 at the instant of the hit. */
    fun intensity(actorId: String): Float =
        ((flashes[actorId] ?: 0f) / duration).coerceIn(0f, 1f)

    fun advance(deltaSeconds: Float) {
        val iterator = flashes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val remaining = entry.value - deltaSeconds
            if (remaining <= 0f) iterator.remove() else entry.setValue(remaining)
        }
    }

    fun forget(actorId: String) {
        flashes.remove(actorId)
    }

    fun clear() = flashes.clear()

    private companion object {
        /** Long enough to register, short enough not to smear into the next hit. */
        const val DEFAULT_DURATION = 0.18f
    }
}
