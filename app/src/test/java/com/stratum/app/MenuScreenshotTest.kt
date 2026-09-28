package com.stratum.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.stratum.app.hub.CreateHubActions
import com.stratum.app.hub.CreateHubScreen
import com.stratum.app.hub.HeroChoice
import com.stratum.app.hub.ImportHubActions
import com.stratum.app.hub.ImportHubScreen
import com.stratum.app.hub.ImportHubState
import com.stratum.app.hub.NewWorldActions
import com.stratum.app.hub.NewWorldScreen
import com.stratum.app.hub.PlayHubActions
import com.stratum.app.hub.PlayHubScreen
import com.stratum.app.hub.StudioStatus
import com.stratum.app.title.TitleActions
import com.stratum.app.title.TitleScreen
import com.stratum.app.world.NewWorldDraft
import com.stratum.app.world.NewWorldStep
import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.session.WorldSummary
import com.stratum.core.domain.world.RulesPresets
import com.stratum.feature.library.ImportStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The menus, each with fixed data and a fixed clock, so a changed picture is
 * a changed screen. The world list is what the in-memory repository serves,
 * which is the same contract the store on disk answers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class MenuScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            StratumTheme(palette = IgboContentPack.palette, darkTheme = true) { content() }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/$name.png")
    }

    @Test
    fun title_no_save() = shoot("title") { TitleScreen(lastWorld = null, actions = TitleActions(), packLine = PACK_LINE, modifier = Modifier.fillMaxSize()) }

    @Test
    fun title_with_save() = shoot("title_continue") { TitleScreen(lastWorld = WORLDS.first(), actions = TitleActions(), packLine = PACK_LINE, modifier = Modifier.fillMaxSize()) }

    @Test
    @Config(qualifiers = "+land")
    fun title_landscape() = shoot("title_landscape") { TitleScreen(lastWorld = null, actions = TitleActions(), packLine = PACK_LINE, modifier = Modifier.fillMaxSize()) }

    @Test
    @Config(qualifiers = "+land")
    fun title_landscape_with_save() = shoot("title_continue_landscape") {
        TitleScreen(lastWorld = WORLDS.first(), actions = TitleActions(), packLine = PACK_LINE, modifier = Modifier.fillMaxSize())
    }

    /** The whole shell, wired for real: a fresh install lands on the title with no Continue. */
    @Test
    fun app_shell_first_launch() = shoot("app_first_launch") { StratumApp(modifier = Modifier.fillMaxSize()) }

    @Test
    fun play_hub() = shoot("play_hub") { PlayHubScreen(worlds = worlds(), actions = PlayHubActions(), now = NOW, modifier = Modifier.fillMaxSize()) }

    @Test
    @Config(qualifiers = "+land")
    fun play_hub_landscape() = shoot("play_hub_landscape") { PlayHubScreen(worlds = worlds(), actions = PlayHubActions(), now = NOW, modifier = Modifier.fillMaxSize()) }

    @Test
    fun play_hub_empty() = shoot("play_hub_empty") { PlayHubScreen(worlds = emptyList(), actions = PlayHubActions(), now = NOW, modifier = Modifier.fillMaxSize()) }

    @Test
    fun new_world_hero() = shoot("new_world_1_hero") {
        NewWorldScreen(draft = NewWorldDraft(heroClassId = "amadioha"), heroes = HEROES, actions = NewWorldActions(), existingWorlds = 3, modifier = Modifier.fillMaxSize())
    }

    @Test
    fun new_world_world() = shoot("new_world_2_world") {
        NewWorldScreen(
            draft = NewWorldDraft(step = NewWorldStep.WORLD, heroClassId = "amadioha").choosePreset(RulesPresets.survivor).copy(name = "Ash Hollow"),
            heroes = HEROES, actions = NewWorldActions(), existingWorlds = 3, modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun new_world_go() = shoot("new_world_3_go") {
        NewWorldScreen(
            draft = NewWorldDraft(step = NewWorldStep.GO, heroClassId = "amadioha", name = "Ash Hollow", seedText = "river").choosePreset(RulesPresets.survivor),
            heroes = HEROES, actions = NewWorldActions(), existingWorlds = 3, modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun create_hub() = shoot("create_hub") { CreateHubScreen(status = STUDIO, actions = CreateHubActions(), modifier = Modifier.fillMaxSize()) }

    @Test
    fun create_hub_no_key() = shoot("create_hub_no_key") {
        CreateHubScreen(status = StudioStatus(classCount = 4), actions = CreateHubActions(), modifier = Modifier.fillMaxSize())
    }

    @Test
    @Config(qualifiers = "+land")
    fun create_hub_landscape() = shoot("create_hub_landscape") { CreateHubScreen(status = STUDIO, actions = CreateHubActions(), modifier = Modifier.fillMaxSize()) }

    @Test
    fun import_hub() = shoot("import_hub") {
        ImportHubScreen(
            state = ImportHubState(installed = 3, active = 2, shareable = 5, status = ImportStatus.Imported("Harbour Town", "Tiled map", emptyList())),
            actions = ImportHubActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    @Config(qualifiers = "+land")
    fun import_hub_landscape() = shoot("import_hub_landscape") {
        ImportHubScreen(state = ImportHubState(), actions = ImportHubActions(), modifier = Modifier.fillMaxSize())
    }

    private fun worlds(): List<WorldSummary> = kotlinx.coroutines.runBlocking { InMemoryWorldSaveRepository(WORLDS).list() }

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val HOUR = 3_600_000L
        const val PACK_LINE = "Igbo-Ukwu Bronze · 23 blocks · 5 regions · 4 classes"

        val WORLDS = listOf(
            WorldSummary("w1", "Benue Marsh", "Dike Ozo", "dike", 14, "Adventure", 99L, 4 * 3600 + 12 * 60, NOW - 2 * HOUR, NOW - 400 * HOUR),
            WorldSummary("w2", "Ash Hollow", "Amadioha Invoker", "amadioha", 7, "Survivor", 7L, 35 * 60, NOW - 30 * HOUR, NOW - 90 * HOUR),
            WorldSummary("w3", "Breaker's Yard", "Dibia Nzu", "dibia", 60, "Sandbox", 3L, 20 * 3600, NOW - 24 * 9 * HOUR, NOW - 24 * 60 * HOUR),
        )

        val HEROES = listOf(
            HeroChoice("dike", "Dike Ozo", "Titled Bladesman · 260 health · 14 attack", level = 14),
            HeroChoice("amadioha", "Amadioha Invoker", "Storm caller · 180 health · 9 attack", level = 7),
            HeroChoice("dibia", "Dibia Nzu", "Chalk healer · 200 health · 8 attack"),
            HeroChoice("ikenga", "Ikenga Warden", "Shield bearer · 320 health · 11 attack"),
        )

        val STUDIO = StudioStatus(
            classCount = 5, customClassCount = 1, sheetCount = 6, keptCreations = 12, modelCount = 2,
            modelReady = true, meshReady = false, paintedStyle = "wet bronze at dusk", running = 2,
        )
    }
}
