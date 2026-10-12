package com.stratum.app.tools

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.app.shell.AppViewModel
import com.stratum.core.data.sprite.SpriteAtlasBaker
import com.stratum.core.domain.sprite.SpriteMapper
import com.stratum.feature.forge.SpriteMapperScreen
import com.stratum.feature.forge.SpriteMapperViewModel
import com.stratum.feature.hero.ClassForgeScreen
import com.stratum.feature.hero.ClassForgeViewModel

/*
 * The studio's tools, each wired to the stores it reads and writes. Every
 * route takes the app's state and a way out, so none of them knows where it
 * sits in the menu.
 */

@Composable
internal fun ClassForgeRoute(app: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val content by app.game.content.collectAsStateWithLifecycle()
    val customClasses by app.game.customClasses.collectAsStateWithLifecycle()
    val sheets by app.graph.assets.sprites.sheets.collectAsStateWithLifecycle()
    val classViewModel: ClassForgeViewModel = viewModel(
        // Keyed on the classes and sheets, so the picker rebuilds when either changes.
        key = "classes-${customClasses.hashCode()}-${sheets.size}",
        factory = ClassForgeViewModel.factory(
            content = content,
            saveClass = app.game::saveClass,
            deleteClass = app.game::deleteClass,
            loadClasses = app.graph.classes::all,
            loadSheets = app.graph.assets.sprites::all,
        ),
    )
    ClassForgeScreen(viewModel = classViewModel, modifier = modifier, onBack = onBack)
}

@Composable
internal fun SpriteMapperRoute(app: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ai = app.graph.assets
    val mapperViewModel: SpriteMapperViewModel = viewModel(
        factory = SpriteMapperViewModel.factory(
            openProject = { sheetId ->
                // Only resume a project cut from the art the library holds now.
                // A regenerated character keeps its id, so resuming blindly
                // reopened the old version's mapping with no way to the new one.
                ai.spriteProjects.load(sheetId)?.takeIf { ai.spriteProjects.matchesSource(sheetId, ai.sprites.bytesFor(sheetId)) }
            },
            sourcePixels = { atlas -> ai.spriteProjects.sourceFor(atlas.id)?.let(SpriteAtlasBaker::pixelsOf) },
            startProject = { sheet ->
                // Copied into a project of its own before anything is mapped,
                // so re-cutting a sheet never damages the only copy of its image.
                val bitmap = ai.sprites.bitmapFor(sheet.id)
                val bytes = ai.sprites.bytesFor(sheet.id)
                if (bitmap == null || bytes == null) {
                    null
                } else {
                    SpriteMapper.fromSheet(sheet = sheet, imageWidth = bitmap.width, imageHeight = bitmap.height)
                        .also { ai.spriteProjects.save(it, bytes) }
                }
            },
            saveProject = { atlas -> ai.spriteProjects.save(atlas) },
            bakeAtlas = { atlas ->
                // Saved under the atlas's own id, so re-baking replaces that
                // sheet rather than leaving the world two versions to pick from.
                ai.spriteProjects.sourceFor(atlas.id)?.let { SpriteAtlasBaker.bake(atlas, it) }?.let {
                    ai.sprites.save(it.sheet, it.bytes)
                    it.sheet
                }
            },
            loadSheets = ai.sprites::all,
        ),
    )
    SpriteMapperScreen(
        viewModel = mapperViewModel,
        modifier = modifier,
        onBack = onBack,
        sourceFor = { atlas -> ai.spriteProjects.sourceFor(atlas.id)?.asImageBitmap() },
    )
}

/**
 * Shares the player's classes and kept creations as a plugin, or says why
 * there is nothing to share. 
 */
internal fun shareCreations(app: AppViewModel, context: android.content.Context) {
    if (!app.graph.plugins.shareCreations(app.game.customClasses.value)) {
        Toast.makeText(context, "Build a class or keep something from the forge first", Toast.LENGTH_SHORT).show()
    }
}
