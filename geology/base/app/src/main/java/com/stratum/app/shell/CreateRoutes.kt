package com.stratum.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.agents.CrewPresets
import com.stratum.app.hub.CreateHubActions
import com.stratum.app.hub.CreateHubScreen
import com.stratum.app.hub.StudioStatus
import com.stratum.app.nav.BackStack
import com.stratum.app.nav.Route
import com.stratum.app.tools.ClassForgeRoute
import com.stratum.app.tools.ContentForgeRoute
import com.stratum.app.tools.CrewRoute
import com.stratum.app.tools.ModelForgeRoute
import com.stratum.app.tools.PoseForgeRoute
import com.stratum.app.tools.SpriteForgeRoute
import com.stratum.app.tools.SpriteMapperRoute
import com.stratum.app.tools.TextureForgeRoute
import com.stratum.app.tools.WeaponForgeRoute
import com.stratum.app.tools.shareCreations
import com.stratum.core.domain.creation.settled

/** The studio and every tool behind it. */
@Composable
internal fun CreateRoutes(app: AppViewModel, route: Route.Create, stack: BackStack, modifier: Modifier = Modifier) {
    val back: () -> Unit = { stack.pop() }
    val settings: () -> Unit = { stack.push(Route.Settings) }
    when (route) {
        Route.Create.Hub -> CreateHubRoute(app, stack, modifier)
        Route.Create.Classes -> ClassForgeRoute(app, onBack = back, modifier = modifier)
        Route.Create.Lore -> {
            val context = LocalContext.current
            ContentForgeRoute(app, onBack = back, onOpenSettings = settings, onShare = { shareCreations(app, context) }, modifier = modifier)
        }
        Route.Create.Textures -> TextureForgeRoute(
            app,
            onBack = back,
            // Painting a style and playing it: into the new-world flow, which starts from what was just painted.
            onPlay = { stack.push(Route.Play.NewWorld) },
            onOpenSettings = settings,
            modifier = modifier,
        )
        Route.Create.Sprites -> SpriteForgeRoute(
            app,
            onBack = back,
            onOpenSettings = settings,
            onMapFrames = { stack.push(Route.Create.Mapper) },
            onPoseForge = { stack.push(Route.Create.Poses) },
            onWeaponForge = { stack.push(Route.Create.Weapons) },
            modifier = modifier,
        )
        Route.Create.Poses -> PoseForgeRoute(app.graph.ai, onBack = back, onOpenSettings = settings, modifier = modifier)
        Route.Create.Weapons -> WeaponForgeRoute(app, onBack = back, onOpenSettings = settings, modifier = modifier)
        Route.Create.Mapper -> SpriteMapperRoute(app, onBack = back, modifier = modifier)
        Route.Create.Models -> ModelForgeRoute(app, onBack = back, onOpenSettings = settings, modifier = modifier)
        Route.Create.Voxels -> com.stratum.app.tools.VoxelStudioRoute(app, onBack = back, modifier = modifier)
        is Route.Create.Crew -> CrewRoute(
            app,
            route.preset,
            onBack = back,
            onOpenSettings = settings,
            // Play now: the described world, at once, as a new saved world. The
            // crew keeps writing, and its pack is delivered through the inbox.
            onPlayNow = { world ->
                app.game.saveStyle(world.stylePrompt)
                startNewWorld(
                    app,
                    name = world.name,
                    heroClassId = null,
                    rules = app.game.content.value.suggestedRules,
                    seed = System.currentTimeMillis(),
                )
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun CreateHubRoute(app: AppViewModel, stack: BackStack, modifier: Modifier) {
    val ai = app.graph.ai
    val content by app.game.content.collectAsStateWithLifecycle()
    val customClasses by app.game.customClasses.collectAsStateWithLifecycle()
    val sheets by ai.sprites.sheets.collectAsStateWithLifecycle()
    val characters by ai.characterRepository.characters.collectAsStateWithLifecycle()
    val plugins by app.graph.plugins.repository.library.collectAsStateWithLifecycle()
    val style by app.game.stylePrompt.collectAsStateWithLifecycle()
    val jobs by app.jobs.jobs.collectAsStateWithLifecycle()
    val blueprints by app.game.blueprints.collectAsStateWithLifecycle()
    val status = StudioStatus(
        classCount = content.heroClasses.size,
        customClassCount = customClasses.size,
        sheetCount = sheets.size,
        unpackedCharacters = characters.count { !it.isPacked && it.posesDrawn.isNotEmpty() },
        keptCreations = remember(plugins) { app.graph.plugins.creationCount() },
        // Read when the models change; the store is a folder listing.
        modelCount = remember(blueprints) { ai.models.store.all().size },
        // Read on every visit, so coming back from settings shows the new state.
        modelReady = ai.isConfigured(),
        meshReady = ai.settings.isModelProviderConfigured,
        paintedStyle = style.ifBlank { null },
        running = jobs.count { !it.status.settled },
        microModelCount = app.game.microModels.collectAsStateWithLifecycle().value.size,
    )
    CreateHubScreen(
        status = status,
        actions = CreateHubActions(
            onBack = { stack.pop() },
            onClasses = { stack.push(Route.Create.Classes) },
            onPoses = { stack.push(Route.Create.Poses) },
            onWeapons = { stack.push(Route.Create.Weapons) },
            onWorldCrew = { stack.push(Route.Create.Crew(CrewPresets.WORLD)) },
            onTextures = { stack.push(Route.Create.Textures) },
            onLore = { stack.push(Route.Create.Lore) },
            onSprites = { stack.push(Route.Create.Sprites) },
            onModels = { stack.push(Route.Create.Models) },
            onVoxels = { stack.push(Route.Create.Voxels) },
            onCrew = { stack.push(Route.Create.Crew()) },
            onMapper = { stack.push(Route.Create.Mapper) },
            onSettings = { stack.push(Route.Settings) },
        ),
        modifier = modifier,
    )
}
