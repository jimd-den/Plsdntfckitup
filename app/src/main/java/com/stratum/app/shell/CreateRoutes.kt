package com.stratum.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.app.hub.CreateHubActions
import com.stratum.app.hub.CreateHubScreen
import com.stratum.app.hub.StudioStatus
import com.stratum.app.nav.BackStack
import com.stratum.app.nav.Route
import com.stratum.app.tools.ClassForgeRoute
import com.stratum.app.tools.SpriteMapperRoute

/** The studio and every tool behind it. */
@Composable
internal fun CreateRoutes(app: AppViewModel, route: Route.Create, stack: BackStack, modifier: Modifier = Modifier) {
    val back: () -> Unit = { stack.pop() }
    when (route) {
        Route.Create.Hub -> CreateHubRoute(app, stack, modifier)
        Route.Create.Classes -> ClassForgeRoute(app, onBack = back, modifier = modifier)
        Route.Create.Mapper -> SpriteMapperRoute(app, onBack = back, modifier = modifier)
        Route.Create.Voxels -> com.stratum.app.tools.VoxelStudioRoute(app, onBack = back, modifier = modifier)
        Route.Create.Masks -> com.stratum.app.tools.MaskMakerRoute(app, onBack = back, modifier = modifier)
        Route.Create.Carver -> com.stratum.app.tools.MaskCarverRoute(app, onBack = back, modifier = modifier)
        Route.Create.Attacks -> com.stratum.app.tools.AttackForgeRoute(app, onBack = back, modifier = modifier)
    }
}

@Composable
private fun CreateHubRoute(app: AppViewModel, stack: BackStack, modifier: Modifier) {
    val assets = app.graph.assets
    val content by app.game.content.collectAsStateWithLifecycle()
    val customClasses by app.game.customClasses.collectAsStateWithLifecycle()
    val sheets by assets.sprites.sheets.collectAsStateWithLifecycle()
    val heroMask = app.game.loadout.collectAsStateWithLifecycle().value.heroMask
    val status = StudioStatus(
        classCount = content.heroClasses.size,
        customClassCount = customClasses.size,
        sheetCount = sheets.size,
        microModelCount = app.game.microModels.collectAsStateWithLifecycle().value.size,
        wearsMakerMask = com.stratum.engine.model.mask.MaskMaker.isCode(heroMask),
        wearsCarvedMask = com.stratum.engine.model.mask.sculpt.MaskCarver.isCode(heroMask),
        carriedAttacks = app.game.forgedAttacks.collectAsStateWithLifecycle().value.equipped.size,
    )
    CreateHubScreen(
        status = status,
        actions = CreateHubActions(
            onBack = { stack.pop() },
            onClasses = { stack.push(Route.Create.Classes) },
            onMasks = { stack.push(Route.Create.Masks) },
            onCarver = { stack.push(Route.Create.Carver) },
            onAttacks = { stack.push(Route.Create.Attacks) },
            onVoxels = { stack.push(Route.Create.Voxels) },
            onMapper = { stack.push(Route.Create.Mapper) },
            onSettings = { stack.push(Route.Settings) },
        ),
        modifier = modifier,
    )
}
