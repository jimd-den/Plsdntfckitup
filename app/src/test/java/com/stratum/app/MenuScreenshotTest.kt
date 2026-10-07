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
import androidx.compose.ui.graphics.asImageBitmap
import com.stratum.app.hub.HeroChoice
import com.stratum.core.designsystem.component.LookChoice
import com.stratum.core.domain.sprite.FrameRect
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
        NewWorldScreen(
            draft = NewWorldDraft(heroClassId = "amadioha"), heroes = HEROES, actions = NewWorldActions(), existingWorlds = 3,
            looks = looks(), lookId = "hero:bronze_warden", modifier = Modifier.fillMaxSize(),
        )
    }

    /** Two drawn looks: a little figure in a single idle frame each, as a sheet from the forge would give. */
    private fun looks(): List<LookChoice> = listOf(
        "hero:bronze_warden" to ("Bronze Warden" to 0xFFC8872E.toInt()),
        "hero:storm_caller" to ("Storm Caller" to 0xFF2FB5A4.toInt()),
    ).map { (id, look) ->
        val bitmap = android.graphics.Bitmap.createBitmap(16, 24, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        paint.color = 0xFFE9D5B5.toInt(); canvas.drawRect(6f, 1f, 10f, 5f, paint)
        paint.color = look.second; canvas.drawRect(4f, 6f, 12f, 16f, paint)
        paint.color = 0xFF3A2A1C.toInt(); canvas.drawRect(5f, 16f, 7f, 23f, paint); canvas.drawRect(9f, 16f, 11f, 23f, paint)
        LookChoice(id, look.first, bitmap.asImageBitmap(), FrameRect(0, 0, 16, 24))
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

    /** A world described in words: it plays at once in the look they suggest, and the crew writes the rest. */
    @Test
    fun new_world_described() = shoot("new_world_3_go_described") {
        NewWorldScreen(
            draft = NewWorldDraft(step = NewWorldStep.GO, heroClassId = "dike", prompt = "A drowned bronze city under a red moon"),
            heroes = HEROES, actions = NewWorldActions(), existingWorlds = 3, modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun create_hub() = shoot("create_hub") { CreateHubScreen(status = STUDIO, actions = CreateHubActions(), modifier = Modifier.fillMaxSize()) }

    @Test
    fun create_hub_fresh() = shoot("create_hub_fresh") {
        CreateHubScreen(status = StudioStatus(classCount = 4), actions = CreateHubActions(), modifier = Modifier.fillMaxSize())
    }

    @Test
    fun quests() = shoot("quests") {
        val world = com.stratum.core.domain.quest.QuestWorld(
            monsters = listOf("igbo:mmuo_hunter" to "spirit hunters", "igbo:bush_cat" to "bush cats", "igbo:soldier_ant" to "soldier ants").map { com.stratum.core.domain.quest.QuestTarget(it.first, it.second) },
            gatherables = listOf(com.stratum.core.domain.quest.QuestTarget("igbo:kola", "kola nuts"), com.stratum.core.domain.quest.QuestTarget("igbo:red_earth", "red earth")),
            mineables = listOf(com.stratum.core.domain.quest.QuestTarget("igbo:iron", "iron ore")),
            buildables = listOf(com.stratum.core.domain.quest.QuestTarget("igbo:mud_brick", "mud bricks")),
            towns = listOf(com.stratum.core.domain.quest.QuestTarget("t:2", "Nri")),
        )
        val town = com.stratum.core.domain.settlement.culture.CityGenerator.roll(7L, "igbo", com.stratum.core.domain.settlement.culture.Form.VILLAGE_GROUP)
        val people = com.stratum.core.domain.quest.QuestGenerator.residents("t:1", town.name, 11L, 9)
        val board = com.stratum.core.domain.quest.QuestGenerator.board(people, world, 11L, day = 2, count = 4, difficulty = 1)
        val taken = com.stratum.core.domain.quest.QuestGenerator.board(people, world, 12L, day = 1, count = 2, difficulty = 2)
            .mapIndexed { i, q -> com.stratum.core.domain.quest.ActiveQuest(q, 0, 0, progress = i, status = if (i == 1) com.stratum.core.domain.quest.QuestStatus.READY else com.stratum.core.domain.quest.QuestStatus.ACTIVE) }
        com.stratum.feature.play.QuestOverlay(
            panel = com.stratum.feature.play.QuestPanel(townName = town.name, people = people.size, board = board, active = taken, playerX = 0f, playerY = 0f, handInHere = setOf(taken[1].quest.id), townStory = town.describe()),
            actions = com.stratum.feature.play.QuestActions(),
        )
    }

    @Test
    fun settings() = shoot("settings") {
        com.stratum.app.hub.SettingsScreen(
            settings = com.stratum.core.domain.settings.GameSettings(ragdolls = true),
            display = com.stratum.app.hub.DisplayChoices(),
            actions = com.stratum.app.hub.SettingsActions(),
            modifier = Modifier.fillMaxSize(),
        )
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

        val STUDIO = StudioStatus(classCount = 5, customClassCount = 1, sheetCount = 6, microModelCount = 3, carriedAttacks = 2)
    }
}
