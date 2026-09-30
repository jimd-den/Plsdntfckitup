package com.stratum.core.domain.sprite

import kotlin.math.absoluteValue

/**
 * Which art an actor falls back to when nothing was assigned to it.
 *
 * Content packs name enemies without naming art for them, because the art may
 * not exist yet -- that is the whole point of a generator. Every unassigned
 * actor then took the first sheet on the list, and since the list is the same
 * list for all of them, every enemy in the world was drawn with the same
 * picture. Four kinds of monster, one model, no clue that the other three
 * sheets were even being considered.
 *
 * Spreading is not assignment. The whole list is kept, in the same order after
 * the start point, so a sheet that turns out to have no usable pixels still
 * falls through to the next candidate exactly as before. All that changes is
 * where each actor starts looking.
 */
object SpriteFallback {

    /**
     * [candidates] rotated to a starting point decided by [actorId].
     *
     * Stable, because it has to be: the resolver is asked for a sprite on
     * every drawn frame, and a choice that varied between calls would make a
     * monster flicker between two bodies. `String.hashCode` is specified by
     * the language rather than left to the runtime, so the same actor gets the
     * same art on every device and across restarts.
     */
    fun <T> spread(candidates: List<T>, actorId: String): List<T> {
        if (candidates.size <= 1) return candidates
        val at = (actorId.hashCode().absoluteValue) % candidates.size
        return candidates.subList(at, candidates.size) + candidates.subList(0, at)
    }
}
