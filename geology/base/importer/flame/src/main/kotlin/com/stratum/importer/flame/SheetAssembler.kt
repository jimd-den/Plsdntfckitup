package com.stratum.importer.flame

import com.stratum.core.domain.importing.ImageRegion
import com.stratum.core.domain.importing.ImportedSpriteSheet
import com.stratum.core.domain.sprite.AnimationClip
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SpriteOrigin
import com.stratum.core.domain.sprite.SpriteSheet
import kotlin.math.ceil

/** A sheet built from a character's animations, and what was left out of it. */
data class AssembledSheet(val sheet: ImportedSpriteSheet, val warnings: List<String>)

/**
 * Lays one character's animations into a single grid sheet.
 *
 * The engine's sheets are one regular grid with a clip per state; source
 * projects keep a file per animation, or pack frames of different sizes
 * together. Every frame gets a cell the size of the largest, clips run in
 * the engine's state order, and the grid is wrapped so it stays a sensible
 * texture size.
 */
object SheetAssembler {

    const val MAX_COLUMNS = 16

    fun assemble(id: String, name: String, animations: List<SourceAnimation>): AssembledSheet? {
        val warnings = mutableListOf<String>()
        val byState = statesOf(animations, name, warnings)
        if (byState.isEmpty()) return null

        val frames = mutableListOf<ImageRegion>()
        val clips = byState.map { (state, animation) ->
            AnimationClip(
                state = state,
                firstFrame = frames.size,
                frameCount = animation.frames.size,
                frameDurationMs = animation.frameDurationMs.coerceAtLeast(1),
                loops = state !in AnimationState.oneShot,
            ).also { frames += animation.frames }
        }
        return AssembledSheet(gridSheet(id, name, frames, clips), warnings)
    }

    private fun statesOf(animations: List<SourceAnimation>, name: String, warnings: MutableList<String>): List<Pair<AnimationState, SourceAnimation>> {
        val grouped = animations.groupBy { AnimationNames.stateFor(it.name) }
        grouped[null]?.forEach { warnings += "'$name' animation '${it.name}' matches no engine state and was left out" }
        return AnimationState.generatedRowOrder.mapNotNull { state ->
            val candidates = grouped[state] ?: return@mapNotNull null
            if (candidates.size > 1) warnings += "'$name' has ${candidates.size} ${state.name.lowercase()} animations; using '${AnimationNames.preferred(candidates).name}'"
            state to AnimationNames.preferred(candidates)
        }
    }

    private fun gridSheet(id: String, name: String, frames: List<ImageRegion>, clips: List<AnimationClip>): ImportedSpriteSheet {
        val columns = frames.size.coerceAtMost(MAX_COLUMNS)
        val rows = ceil(frames.size / columns.toFloat()).toInt()
        // Unused trailing cells repeat the last frame, so the grid is whole and nothing is blank.
        val padded = frames + List(columns * rows - frames.size) { frames.last() }
        val sheet = SpriteSheet(
            id = id,
            name = name,
            columns = columns,
            rows = rows,
            frameWidth = frames.maxOf { it.width },
            frameHeight = frames.maxOf { it.height },
            clips = clips,
            origin = SpriteOrigin.IMPORTED,
        )
        return ImportedSpriteSheet(sheet, padded)
    }
}
