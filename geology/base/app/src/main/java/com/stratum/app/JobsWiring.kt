package com.stratum.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.data.sprite.GeneratedSheetPreparer
import com.stratum.core.designsystem.component.JobsTray
import com.stratum.core.designsystem.component.JobsTrayActions
import com.stratum.core.domain.ai.SpriteSheetRequest
import com.stratum.core.domain.creation.InMemoryJobCenter
import com.stratum.core.domain.creation.PackInbox
import com.stratum.core.domain.creation.observer
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.core.domain.sprite.SpriteTarget
import com.stratum.feature.forge.PoseRun
import com.stratum.feature.forge.SpriteStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * The app's one job centre, and the inbox where finished world packs wait.
 *
 * A process-wide object rather than something the shell remembers: a job must
 * outlive every screen, including the activity being recreated on a turn of the
 * phone, and the view models that start jobs survive that recreation too. The
 * jobs run on the main dispatcher, as the screens' own coroutines did; each
 * provider adapter moves its network work off it.
 */
object CreationJobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val center: InMemoryJobCenter by lazy {
        InMemoryJobCenter(scope).also { PoseRun.jobs = it }
    }

    /** Packs the world crew finished while a world was being played. */
    val inbox = PackInbox()

    /**
     * Draws a hero's look as a background job, for the class builder's "Make a
     * look": a walk sheet in the pixel style, saved exactly as the sprite forge
     * saves one. Returns false when no model is connected.
     */
    fun queueHeroLook(ai: AiWiring, subject: String, onDrawn: (SpriteSheet) -> Unit): Boolean {
        if (!ai.isConfigured()) return false
        val model = ai.settings.load().imageModel
        center.launch(
            kind = "hero-art",
            title = "A look for $subject".take(TITLE_LIMIT),
            steps = listOf("Writing the request", "Waiting for $model", "Reading the reply", "Decoding the image", SAVING),
        ) {
            val drawn = ai.generateSpriteSheet(
                SpriteSheetRequest(
                    subject = subject,
                    namespace = SpriteTarget.HERO.namespace,
                    styleDirection = SpriteStyle.PIXEL.direction,
                    layout = SpriteTarget.HERO.layoutFor(AnimationState.WALK),
                ),
                observer(model),
            ).getOrElse { fail(it.message ?: "The look could not be drawn.") }
            begin(SAVING)
            val prepared = withContext(Dispatchers.Default) { GeneratedSheetPreparer.prepare(drawn.sheet, drawn.image.bytes) }
            ai.sprites.save(prepared.sheet, prepared.bytes)
            onDrawn(prepared.sheet)
            "Drew ${prepared.sheet.name}; it is picked for the hero."
        }
        return true
    }

    private const val SAVING = "Keying and saving the sheet"
    private const val TITLE_LIMIT = 60
}

/**
 * The jobs tray as the shell places it: `jobsTray = { StratumJobsTray() }`.
 * Draws nothing while no job is running or recently finished.
 */
@Composable
fun StratumJobsTray(modifier: Modifier = Modifier) {
    val center = CreationJobs.center
    val jobs by center.jobs.collectAsStateWithLifecycle()
    JobsTray(
        jobs = jobs,
        actions = JobsTrayActions(onCancel = center::cancel, onDismiss = center::dismiss, onClearFinished = center::clearFinished),
        modifier = modifier,
    )
}
