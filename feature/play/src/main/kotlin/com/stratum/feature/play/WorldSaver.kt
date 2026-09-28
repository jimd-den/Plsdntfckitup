package com.stratum.feature.play

import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.session.WorldSaveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Why a save was taken: the cue a player sees can differ, and a test can tell them apart. */
enum class SaveReason { AUTOSAVE, LEVEL_UP, BUILD_CHANGE, DEATH, REVIVE, BACKGROUND, EXIT, NEW_WORLD }

/** The last save that finished, for a small "Saved" cue. [serial] changes on every save, so a repeat is still news. */
data class SaveNotice(val serial: Int, val reason: SaveReason, val message: String = "Saved")

/**
 * When the play session writes itself down, and the writing.
 *
 * The caller takes the snapshot -- [WorldSave] and [HeroSave] are immutable
 * copies made on the thread that ticks the world -- and this runs the slow
 * part, the files, in [scope], each write waiting for the one taken before
 * it, so an autosave and an exit save can never interleave or land out of
 * order and put an older world over a newer one. [scope] should outlive the screen: a
 * save started as the player leaves must still land.
 *
 * Kept apart from the view model so the rules -- every minute, on a level,
 * a sandbox never touching the real hero -- are tested without Android.
 */
internal class WorldSaver(
    /** Where the world goes, or null when this run has no slot (the hero is still kept). */
    private val repository: WorldSaveRepository?,
    private val saveHero: (HeroSave) -> Unit,
    private val scope: CoroutineScope,
    /** Called from [scope] after a save has landed. */
    private val onSaved: (SaveNotice) -> Unit = {},
    private val onFailed: (Throwable) -> Unit = {},
    private val interval: Float = AUTOSAVE_SECONDS,
) {
    /** The write taken last; the next one starts when it is done. Only touched from the thread that takes saves. */
    private var previous: Job? = null
    private var savedAt = 0f
    private var savedLevel = 0
    private var serial = 0

    /** Records that the run starts in a saved state, so the first autosave waits its minute. */
    fun mark(elapsed: Float, level: Int) {
        savedAt = elapsed
        savedLevel = level
    }

    /** Why an autosave is due -- every [interval] seconds of play, and on a new level -- or null when it is not. */
    fun due(elapsed: Float, level: Int): SaveReason? = when {
        level != savedLevel -> SaveReason.LEVEL_UP
        elapsed - savedAt >= interval -> SaveReason.AUTOSAVE
        else -> null
    }

    /**
     * Writes [world] to its slot and [hero] to the roster, in that order,
     * off the calling thread. A [sandbox] world is written to its own slot
     * but its hero is never kept: a conjured character must not replace an
     * earned one.
     */
    fun write(world: WorldSave?, hero: HeroSave?, sandbox: Boolean, reason: SaveReason, elapsed: Float, level: Int): Job {
        mark(elapsed, level)
        val keptHero = hero?.takeUnless { sandbox }
        val keptWorld = world?.takeIf { repository != null }
        val before = previous
        return scope.launch {
            before?.join()
            try {
                keptWorld?.let { repository?.save(it) }
                keptHero?.let(saveHero)
                if (keptWorld != null || keptHero != null) onSaved(SaveNotice(++serial, reason))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailed(error)
            }
        }.also { previous = it }
    }

    companion object {
        const val AUTOSAVE_SECONDS = 60f
    }
}
