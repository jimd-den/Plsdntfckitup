package com.stratum.app.shell

import kotlinx.coroutines.launch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.app.hub.HeroChoice
import com.stratum.app.hub.ImportHubActions
import com.stratum.app.hub.ImportHubScreen
import com.stratum.app.hub.ImportHubState
import com.stratum.app.hub.NewWorldActions
import com.stratum.app.hub.NewWorldScreen
import com.stratum.app.hub.PlayHubActions
import com.stratum.app.hub.PlayHubScreen
import com.stratum.app.nav.BackStack
import com.stratum.app.nav.Route
import com.stratum.app.title.TitleActions
import com.stratum.app.title.TitleScreen
import com.stratum.app.tools.shareCreations
import com.stratum.app.world.NewWorldDraft
import com.stratum.app.world.WorldLaunch
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.session.WorldSummary
import com.stratum.feature.library.LibraryScreen
import com.stratum.feature.library.LibraryViewModel
import com.stratum.feature.library.rememberArchivePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The menus: the title, the play and share hubs, the new-world flow and settings. */
@Composable
internal fun MenuRoutes(app: AppViewModel, route: Route, stack: BackStack, modifier: Modifier = Modifier) {
    val content by app.game.content.collectAsStateWithLifecycle()
    val worlds by app.graph.worlds.worlds.collectAsStateWithLifecycle()
    val continueWorld = { world: WorldSummary ->
        app.game.chooseHeroClass(world.heroClassId.ifBlank { null })
        stack.push(Route.Play.World(WorldLaunch.Resume(world.id, world.heroClassId.ifBlank { null })))
    }
    // Read on arriving at any menu: a world just left may have saved since the list was last read.
    LaunchedEffect(route) { app.graph.worlds.refresh() }

    when (route) {
        Route.Title -> TitleScreen(
            lastWorld = worlds.firstOrNull(),
            actions = TitleActions(
                onContinue = continueWorld,
                onPlay = { stack.push(Route.Play.Hub) },
                onCreate = { stack.push(Route.Create.Hub) },
                onShare = { stack.push(Route.Share.Hub) },
                onSettings = { stack.push(Route.Settings) },
            ),
            packLine = packLine(content),
            modifier = modifier,
        )

        Route.Play.Hub -> PlayHubScreen(
            worlds = worlds,
            actions = PlayHubActions(
                onBack = { stack.pop() },
                onContinue = continueWorld,
                onNewWorld = { stack.push(Route.Play.NewWorld) },
                onRename = app.graph.worlds::rename,
                onDelete = app.graph.worlds::delete,
            ),
            modifier = modifier,
        )

        Route.Play.NewWorld -> NewWorldRoute(app, stack, content, worlds.size, modifier)

        Route.Share.Hub -> ShareHubRoute(app, stack, modifier)

        Route.Share.Plugins -> {
            val library: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(app.graph.plugins.repository))
            val context = LocalContext.current
            LibraryScreen(
                viewModel = library,
                onBack = { stack.pop() },
                onShareCreations = { shareCreations(app, context) },
                modifier = modifier,
            )
        }

        Route.Settings -> {
            val settings by app.game.settings.collectAsStateWithLifecycle()
            val loadout by app.game.loadout.collectAsStateWithLifecycle()
            var graphics by remember { mutableStateOf(app.graph.graphics.chosen) }
            var terrain by remember { mutableStateOf(app.graph.graphics.terrain) }
            com.stratum.app.hub.SettingsScreen(
                settings = settings,
                display = com.stratum.app.hub.DisplayChoices(graphics = graphics, terrain = terrain, maskCharacters = loadout.maskCharacters),
                actions = com.stratum.app.hub.SettingsActions(
                    onBack = { stack.pop() },
                    onChange = app.game::changeSettings,
                    onGraphics = { tier -> app.graph.graphics.choose(tier); graphics = tier },
                    onTerrain = { mode -> app.graph.graphics.chooseTerrain(mode); terrain = mode },
                    onMaskCharacters = app.game::chooseMaskCharacters,
                ),
                modifier = modifier,
            )
        }

        else -> Unit
    }
}

@Composable
private fun NewWorldRoute(app: AppViewModel, stack: BackStack, content: AssembledContent, existingWorlds: Int, modifier: Modifier) {
    val draft by app.newWorld.collectAsStateWithLifecycle()
    val heroes by produceState(emptyList<HeroChoice>(), content.heroClasses) {
        // Each class's kept hero is read from disk for its level, off the main thread.
        value = withContext(Dispatchers.IO) {
            content.heroClasses.map { hero ->
                HeroChoice(
                    id = hero.id,
                    name = hero.name,
                    line = listOfNotNull(
                        hero.title.takeIf { it.isNotBlank() },
                        "${hero.resolvedStats.maxHealth} health",
                        "${hero.resolvedStats.attackPower} attack",
                    ).joinToString(" · "),
                    level = app.graph.heroes.load(hero.id)?.level,
                )
            }
        }
    }
    val loadout by app.game.loadout.collectAsStateWithLifecycle()
    val looks = rememberLookChoices(app, content)
    NewWorldScreen(
        draft = draft.copy(heroClassId = draft.heroClassId ?: app.game.loadout.value.heroClassId),
        heroes = heroes,
        existingWorlds = existingWorlds,
        looks = looks,
        lookId = loadout.heroSheetId,
        actions = NewWorldActions(
            onBack = { stack.pop() },
            onChange = { app.newWorld.value = it },
            onQuickMake = { stack.push(Route.Create.Classes) },
            // Null is the class's own art; picking the worn look again also goes back to it.
            onPickLook = { id -> app.game.chooseHeroSheet(id) },
            onMakeLook = { stack.push(Route.Create.Mapper) },
            onGo = {
                startNewWorld(
                    app,
                    name = draft.resolvedName(existingWorlds),
                    heroClassId = draft.heroClassId ?: app.game.loadout.value.heroClassId,
                    rules = draft.rules,
                    seed = draft.seed(System.currentTimeMillis()),
                    described = draft.described,
                    scene = draft.scene.takeIf { draft.prompt.isNotBlank() },
                )
                app.newWorld.value = NewWorldDraft()
            },
        ),
        modifier = modifier,
    )
}

@Composable
private fun ShareHubRoute(app: AppViewModel, stack: BackStack, modifier: Modifier) {
    val context = LocalContext.current
    val library: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(app.graph.plugins.repository))
    val state by library.state.collectAsStateWithLifecycle()
    val plugins by app.graph.plugins.repository.library.collectAsStateWithLifecycle()
    val customClasses by app.game.customClasses.collectAsStateWithLifecycle()
    val pickArchive = rememberArchivePicker(library)
    ImportHubScreen(
        state = ImportHubState(
            installed = state.plugins.size,
            active = state.plugins.count { it.active },
            shareable = customClasses.size,
            status = state.status,
        ),
        actions = ImportHubActions(
            onBack = { stack.pop() },
            onInstall = pickArchive,
            onImportProject = pickArchive,
            onShare = { shareCreations(app, context) },
            onManage = { stack.push(Route.Share.Plugins) },
            onDismissStatus = library::dismissStatus,
        ),
        modifier = modifier,
    )
}

/** What is loaded, in one line: "Igbo-Ukwu Bronze · 23 blocks · 5 regions · 4 classes". */
private fun packLine(content: AssembledContent): String = listOf(
    content.packs.joinToString(" + ") { it.name },
    "${content.registry.size} blocks",
    "${content.biomes.size} regions",
    "${content.heroClasses.size} classes",
).joinToString(" · ")

