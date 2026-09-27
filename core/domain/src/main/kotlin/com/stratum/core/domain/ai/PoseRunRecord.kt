package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.PoseSheetPlanner
import com.stratum.core.domain.sprite.ClipSampling
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything about a character's run that is not the pictures, kept on disk.
 *
 * The pictures were always safe: each pose is written the moment it arrives.
 * What was not safe was everything that said *what the pictures were for* —
 * twelve frames of walk rather than six, the away angle on, a cell size, a
 * style line, which frames were thrown away to be redrawn. All of it lived in
 * the screen's state, so a process the system reclaimed mid-run came back
 * with the defaults: the resumed run planned six walk frames instead of twelve,
 * skipped the away angle it had been half-way through, and forgot that walk_3
 * had been rejected and must not be served from the cache again.
 *
 * [active] is the part that makes resuming possible at all. It is set when a
 * run starts and cleared when one ends in any way a person saw — finished,
 * stopped, abandoned. A record still marked active when the app starts again
 * is a run that was killed, and the forge offers to carry on with it.
 */
@Serializable
data class PoseRunRecord(
    val setId: String,
    val subject: String,
    val style: String = "",
    val scope: PoseScope = PoseScope.ENEMY,
    val frames: Map<AnimationState, Int> = emptyMap(),
    val drawsAwayView: Boolean = false,
    val drawsFromClip: Boolean = false,
    val cellSize: Int = PoseSheetPlanner.DEFAULT_CELL,
    val frameRate: Int = ClipSampling.DEFAULT_FPS,
    val promptOverride: String? = null,
    /** Deliberate redraws spent on each key; see [ImageRequest.take]. */
    val takes: Map<String, Int> = emptyMap(),
    /** Keys that failed last time, and why. */
    val failures: Map<String, String> = emptyMap(),
    /** Keys kept despite defects, and what the defects were, in words. */
    val flagged: Map<String, String> = emptyMap(),
    val active: Boolean = false,
) {
    val views: List<PoseView>
        get() = if (drawsAwayView) listOf(PoseView.FRONT, PoseView.AWAY) else listOf(PoseView.FRONT)

    val script: PoseScript get() = scope.scriptFor(frames, views)

    /** The next take for [key], after a person threw its current picture away. */
    fun redrawn(key: String): PoseRunRecord =
        copy(takes = takes + (key to ((takes[key] ?: 0) + 1)), failures = failures - key, flagged = flagged - key)

    /** The record once a run has ended, whatever the way. */
    fun finished(outcome: RunOutcome): PoseRunRecord = copy(
        takes = takes + outcome.takes,
        failures = outcome.reasons.ifEmpty { outcome.failed.associateWith { "failed" } },
        flagged = outcome.flagged.mapValues { (_, defects) -> defects.joinToString(" and ") { it.label } },
        active = false,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(record: PoseRunRecord): String = json.encodeToString(serializer(), record)

        /** Null for anything unreadable: a damaged record is a lost convenience, never a crash. */
        fun decode(text: String): PoseRunRecord? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        /**
         * The run to offer to resume, if any: the newest record still marked active.
         *
         * Newest because only one run is ever in flight, so if two claim to be,
         * the older one was superseded and simply never cleared.
         */
        fun interrupted(records: List<PoseRunRecord>): PoseRunRecord? = records.firstOrNull { it.active }
    }
}
