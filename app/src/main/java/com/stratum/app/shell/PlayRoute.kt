package com.stratum.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.app.GameSetup
import com.stratum.app.ImmersiveMode
import com.stratum.app.ScopedViewModels
import com.stratum.app.world.WorldLaunch
import com.stratum.core.designsystem.component.EmptyState
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.session.WorldSave
import com.stratum.feature.play.HeroLooks
import com.stratum.feature.play.PlayScreen
import com.stratum.feature.play.PlayViewModel
import com.stratum.feature.play.SpriteKey

/** A resume's save as it loads: still reading, read, or unreadable. */
private sealed interface Loaded {
    data object Reading : Loaded
    data class Ready(val save: WorldSave?) : Loaded
    data object Missing : Loaded
}

/**
 * In a world: the play session for [launch], scoped to this screen so leaving
 * takes the exit save and frees the world, and the next launch starts fresh.
 *
 * A resumed world is read off the main thread first; its seed, rules, hero
 * class and hero come from the save. A new world plays into the slot the
 * library made for it, and the session writes its first save as it starts.
 */
@Composable
internal fun PlayRoute(
    app: AppViewModel,
    launch: WorldLaunch,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded by produceState<Loaded>(if (launch is WorldLaunch.New) Loaded.Ready(null) else Loaded.Reading, launch) {
        if (launch is WorldLaunch.Resume) value = app.graph.worlds.load(launch.worldId)?.let { Loaded.Ready(it) } ?: Loaded.Missing
    }
    when (val state = loaded) {
        Loaded.Reading -> Box(modifier.fillMaxSize().background(StratumTheme.colors.surface), contentAlignment = Alignment.Center) {
            Text("Loading world…", style = MaterialTheme.typography.titleMedium, color = StratumTheme.colors.inkMuted)
        }
        Loaded.Missing -> Box(modifier.fillMaxSize().background(StratumTheme.colors.surface).padding(Space.large), contentAlignment = Alignment.Center) {
            EmptyState(
                glyph = "⚠",
                title = "This world could not be read",
                body = "Its save is missing or damaged. Your hero is kept separately and is safe.",
                actionLabel = "Back",
                onAction = onExit,
            )
        }
        is Loaded.Ready -> Session(app, launch, state.save, onExit, modifier)
    }
}

@Composable
private fun Session(
    app: AppViewModel,
    launch: WorldLaunch,
    resume: WorldSave?,
    onExit: () -> Unit,
    modifier: Modifier,
) {
    val graph = app.graph
    val ai = graph.ai
    val liveContent by app.game.contentWithSprites.collectAsStateWithLifecycle()
    // Held for the whole visit: content arriving mid-play (a plugin finishing
    // loading) must not rebuild the session under the player and lose the
    // play since its last save. The next world picks it up.
    val content = remember(launch.worldId) { liveContent }
    val loadout by app.game.loadout.collectAsStateWithLifecycle()
    val characters by ai.characterRepository.characters.collectAsStateWithLifecycle()
    val propModels by app.game.propModels.collectAsStateWithLifecycle()
    val blueprints by app.game.blueprints.collectAsStateWithLifecycle()
    val microModels by app.game.microModels.collectAsStateWithLifecycle()
    val stylePrompt by app.game.stylePrompt.collectAsStateWithLifecycle()

    // The world plays as the class it was made with; the look and weapon are
    // the player's current picks, since those belong to them, not the world.
    val heroClassId = resume?.heroClassId ?: (launch as? WorldLaunch.New)?.heroClassId ?: content.heroClasses.firstOrNull()?.id
    val worldLoadout = loadout.copy(heroClassId = heroClassId)
    // Drawing reads the live sheets, not the visit's held content: a look
    // picked or drawn mid-play must show without rebuilding the session.
    val spriteResolver = remember(liveContent, worldLoadout, characters) { heroSpriteResolver(ai, liveContent, worldLoadout) }
    // The session is built once, so it is handed a lookup that always asks
    // the newest resolver; the hero changes look the frame after the pick.
    val latestResolver by rememberUpdatedState(spriteResolver)
    val drawSprite = remember { { key: SpriteKey -> latestResolver(key) } }
    val looks = rememberLookChoices(app, liveContent)
    val radius = remember { graph.graphics.startingSettings().streamingRadius }
    // How far the world streams is this device's choice, not the save's.
    val resumed = remember(resume) { resume?.let { it.copy(config = it.config.copy(simulationRadius = radius)) } }
    val config = remember(launch, resumed) {
        resumed?.config ?: (launch as WorldLaunch.New).let { GameSetup.worldConfig(it.seed, radius, it.rules) }
    }
    var seenHints by remember { mutableStateOf(graph.hints.seen()) }

    // A world that was left is the newest played; the list learns that on the way out.
    DisposableEffect(Unit) { onDispose { graph.worlds.refresh() } }

    ScopedViewModels(launch.worldId) {
        val viewModel: PlayViewModel = viewModel(
            factory = PlayViewModel.factory(
                content, config,
                heroClassId = heroClassId,
                spriteResolver = drawSprite,
                imageModel = ai.imageModel,
                kitDirectory = graph.forgeDirectory,
                kitOverlays = graph.plugins.textureDirectories() + ai.models.textureDirectory,
                quality = graph.graphics.chosen,
                saveQuality = graph.graphics::choose,
                loadHero = { heroClassId?.let(graph.heroes::load) },
                saveHero = graph.heroes::save,
                stylePrompt = stylePrompt,
                saveStyle = app.game::saveStyle,
                propModels = propModels,
                blueprints = blueprints,
                microModels = microModels,
                resume = resumed,
                worlds = graph.worlds.library.repository,
                slot = (launch as? WorldLaunch.New)?.identity,
            ),
        )
        ImmersiveMode()
        PlayScreen(
            viewModel = viewModel,
            modifier = modifier,
            onOpenMenu = onExit,
            seenHints = seenHints,
            onHintSeen = { id ->
                graph.hints.markSeen(id)
                seenHints = seenHints + id
            },
            looks = HeroLooks(
                looks = looks,
                wornId = loadout.heroSheetId,
                // Null is the class's own art; picking the worn look again also goes back to it.
                onPick = app.game::chooseHeroSheet,
            ),
        )
    }
}
