package com.stratum.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.JobsTray
import com.stratum.core.designsystem.component.JobsTrayActions
import com.stratum.core.domain.creation.InMemoryJobCenter
import com.stratum.core.domain.creation.PackInbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's one job centre, and the inbox where finished packs wait.
 *
 * A process-wide object rather than something the shell remembers: a job must
 * outlive every screen, including the activity being recreated on a turn of the
 * phone. Long procedural work -- importing a pack, baking a world -- runs here.
 */
object CreationJobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val center: InMemoryJobCenter by lazy { InMemoryJobCenter(scope) }

    /** Packs that arrived while a world was being played, installed when it ends. */
    val inbox = PackInbox()
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
