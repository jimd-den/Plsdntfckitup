package com.stratum.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.stratum.agents.forge.ForgeContext
import com.stratum.agents.forge.ForgeKind
import com.stratum.agents.forge.ForgePlaceholder
import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.designsystem.component.JobProgress
import com.stratum.core.designsystem.component.JobsPill
import com.stratum.core.designsystem.component.JobsSheet
import com.stratum.core.designsystem.component.JobsTrayActions
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.creation.CreationJob
import com.stratum.core.domain.creation.JobStatus
import com.stratum.core.domain.creation.JobStep
import com.stratum.core.domain.item.PowerTier
import com.stratum.feature.forge.ContentForgeActions
import com.stratum.feature.forge.ContentForgeContent
import com.stratum.feature.forge.ContentForgeUiState
import com.stratum.feature.forge.ForgeDraft
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fixed jobs at a fixed clock, so the elapsed times in the pictures never change. */
private const val NOW = 1_000_000L

private val running = CreationJob(
    id = "job-3", kind = "lore", title = "River Spirit", status = JobStatus.RUNNING, startedAt = NOW - 14_000,
    steps = listOf(
        JobStep("Writing the request", JobStatus.DONE),
        JobStep("Waiting for gemini-2.0-flash", JobStatus.DONE, "HTTP 200 · but 2 problems"),
        JobStep("Checking the reply", JobStatus.FAILED, "2 problems: lore entry 'The Toll' names unknown subject 'ferry' -- asking again"),
        JobStep("Writing the request again (try 2)", JobStatus.DONE),
        JobStep("Waiting for gemini-2.0-flash", JobStatus.RUNNING, "12s"),
        JobStep("Reading the reply", JobStatus.QUEUED),
        JobStep("Ready to read", JobStatus.QUEUED),
    ),
)

private val done = CreationJob(
    id = "job-2", kind = "gear", title = "Heavy Bronze Cleaver", status = JobStatus.DONE, startedAt = NOW - 61_000, finishedAt = NOW - 40_000,
    steps = listOf(
        JobStep("Writing the request", JobStatus.DONE),
        JobStep("Waiting for gemini-2.0-flash", JobStatus.DONE, "answered in 17s"),
        JobStep("Reading the reply", JobStatus.DONE),
        JobStep("Repairing 2 fields", JobStatus.DONE, "base 'bronze cleaver' read as igbo:bronze_axe; 300% read as 3"),
        JobStep("Checking the reply", JobStatus.DONE, "2 things, and it loads"),
        JobStep("Ready to read", JobStatus.DONE),
    ),
    summary = "Made Heavy Bronze Cleaver, The Executioner's Oath.",
)

private val failed = CreationJob(
    id = "job-1", kind = "hero-art", title = "A look for Adaeze, the Kiln Warden", status = JobStatus.FAILED, startedAt = NOW - 200_000, finishedAt = NOW - 128_000,
    steps = listOf(
        JobStep("Writing the request", JobStatus.DONE),
        JobStep("Waiting for meta/muse-image", JobStatus.DONE, "HTTP 402: this model needs credits on your account"),
        JobStep("Reading the reply", JobStatus.FAILED),
        JobStep("Decoding the image", JobStatus.CANCELLED),
        JobStep("Keying and saving the sheet", JobStatus.CANCELLED),
    ),
    summary = "The provider refused the request: this model needs credits on your account. Add credits or pick a free model in Settings.",
)

private val world = CreationJob(
    id = "job-4", kind = "world", title = "Hive Siege", status = JobStatus.RUNNING, startedAt = NOW - 95_000,
    steps = listOf(
        JobStep("🗺 Cartographer", JobStatus.DONE, "4 blocks, 3 biomes"),
        JobStep("📜 Loremaster", JobStatus.RUNNING, "waiting for your approval"),
        JobStep("🐺 Bestiary", JobStatus.QUEUED),
        JobStep("Checking the whole pack", JobStatus.QUEUED),
    ),
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class JobsScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            StratumTheme(palette = IgboContentPack.palette, darkTheme = true) {
                Box(Modifier.fillMaxSize().background(StratumTheme.colors.surface)) { content() }
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/$name.png")
    }

    @Test
    fun job_progress_running() = capture("job_progress_running") {
        JobProgress(running, Modifier.fillMaxWidth().padding(Space.large), onCancel = {}, now = NOW)
    }

    @Test
    fun job_progress_done() = capture("job_progress_done") {
        JobProgress(done, Modifier.fillMaxWidth().padding(Space.large), onDismiss = {}, now = NOW)
    }

    @Test
    fun job_progress_failed() = capture("job_progress_failed") {
        JobProgress(failed, Modifier.fillMaxWidth().padding(Space.large), onDismiss = {}, now = NOW)
    }

    /** The tray open: the pill where the shell puts it, and the sheet over the screen. */
    @Composable
    private fun TrayOpen() {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(Space.large), contentAlignment = Alignment.TopEnd) {
                JobsPill(listOf(world, running, done, failed), onClick = {})
            }
            // Inset from the screen's edges, so on a short landscape screen the
            // sheet ends at its own border and scrolls, rather than running off.
            Box(Modifier.fillMaxSize().padding(start = Space.large, end = Space.large, bottom = Space.large), contentAlignment = Alignment.BottomCenter) {
                StratumPanel(Modifier.widthIn(max = 640.dp).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    JobsSheet(listOf(world, running, done, failed), JobsTrayActions(), onClose = {}, now = NOW)
                }
            }
        }
    }

    @Test
    fun jobs_tray_expanded() = capture("jobs_tray") { TrayOpen() }

    @Test
    @Config(qualifiers = "+land")
    fun jobs_tray_expanded_landscape() = capture("jobs_tray_landscape") { TrayOpen() }

    @Test
    fun jobs_tray_job_opened() = capture("jobs_tray_job") {
        JobsSheet(listOf(world, running, done, failed), JobsTrayActions(), onClose = {}, now = NOW, initiallyOpen = world.id)
    }

    @Test
    fun content_forge_mid_job() {
        val prompt = "The river spirit who takes a toll from every ferry"
        val draft = ForgeDraft(
            key = "draft-2", kind = ForgeKind.LORE, order = ForgeKind.LORE.order(prompt), context = ForgeContext(),
            placeholder = ForgePlaceholder.of(ForgeKind.LORE, prompt), jobId = running.id, job = running,
        )
        val other = ForgeDraft(
            key = "draft-1", kind = ForgeKind.WEAPON, order = ForgeKind.WEAPON.order("A heavy bronze cleaver cast for executions"), context = ForgeContext(),
            placeholder = ForgePlaceholder.of(ForgeKind.WEAPON, "A heavy bronze cleaver cast for executions"), jobId = "job-5",
            job = running.copy(id = "job-5", title = "Heavy Bronze Cleaver"),
        )
        capture("content_forge_mid_job") {
            ContentForgeContent(
                state = ContentForgeUiState(
                    kind = ForgeKind.LORE, prompt = prompt, budget = PowerTier.BALANCED,
                    drafts = listOf(draft, other), providerConfigured = true, kept = 3,
                ),
                actions = ContentForgeActions(),
                modifier = Modifier.fillMaxSize(),
                now = NOW,
            )
        }
    }
}
