package com.stratum.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.app.GameSetup
import com.stratum.app.ImmersiveMode
import com.stratum.app.ScopedViewModels
import com.stratum.app.world.WorldLaunch
import com.stratum.feature.play.PlayScreen
import com.stratum.feature.play.PlayViewModel

/**
 * In a world: the play session for [launch], scoped to this screen so leaving
 * saves the hero and frees the world, and the next launch starts fresh.
 */
@Composable
internal fun PlayRoute(
    app: AppViewModel,
    launch: WorldLaunch,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = app.graph
    val ai = graph.ai
    val content by app.game.contentWithSprites.collectAsStateWithLifecycle()
    val loadout by app.game.loadout.collectAsStateWithLifecycle()
    val characters by ai.characterRepository.characters.collectAsStateWithLifecycle()
    val propModels by app.game.propModels.collectAsStateWithLifecycle()
    val blueprints by app.game.blueprints.collectAsStateWithLifecycle()
    val stylePrompt by app.game.stylePrompt.collectAsStateWithLifecycle()

    // The world plays as the class it was launched with; the look and weapon
    // are the player's current picks, since those belong to them, not the world.
    val heroClassId = launch.heroClassId ?: content.heroClasses.firstOrNull()?.id
    val worldLoadout = loadout.copy(heroClassId = heroClassId)
    val spriteResolver = remember(content, worldLoadout, characters) { heroSpriteResolver(ai, content, worldLoadout) }
    val config = remember(launch) { GameSetup.worldConfig(launch.seed, graph.graphics.startingSettings().streamingRadius, launch.rules) }
    var seenHints by remember { mutableStateOf(graph.hints.seen()) }

    // A world that was left is the newest played; the list learns that on the way out.
    DisposableEffect(Unit) { onDispose { graph.worlds.refresh() } }

    // Keyed so new content or a new launch builds a fresh session rather than reusing the previous world.
    ScopedViewModels(listOf(content, config, heroClassId, launch.worldId)) {
        val viewModel: PlayViewModel = viewModel(
            factory = PlayViewModel.factory(
                content, config,
                heroClassId = heroClassId,
                spriteResolver = spriteResolver,
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
        )
    }
}
