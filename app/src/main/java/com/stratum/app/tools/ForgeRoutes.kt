package com.stratum.app.tools

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.agents.CrewPresets
import com.stratum.app.shell.AppViewModel
import com.stratum.core.data.sprite.GeneratedSheetPreparer
import com.stratum.core.data.sprite.SpriteAtlasBaker
import com.stratum.core.data.sprite.WeaponPreparer
import com.stratum.core.domain.sprite.SheetArt
import com.stratum.core.domain.sprite.SpriteMapper
import com.stratum.feature.forge.ContentForgeScreen
import com.stratum.feature.forge.ContentForgeViewModel
import com.stratum.feature.forge.CrewScreen
import com.stratum.feature.forge.CrewViewModel
import com.stratum.feature.forge.ModelForgeScreen
import com.stratum.feature.forge.ModelForgeViewModel
import com.stratum.feature.forge.SpriteForgeScreen
import com.stratum.feature.forge.SpriteForgeViewModel
import com.stratum.feature.forge.SpriteMapperScreen
import com.stratum.feature.forge.SpriteMapperViewModel
import com.stratum.feature.forge.WeaponForgeScreen
import com.stratum.feature.forge.WeaponForgeViewModel
import com.stratum.feature.hero.ClassForgeScreen
import com.stratum.feature.hero.ClassForgeViewModel
import com.stratum.feature.play.TextureForgeActions
import com.stratum.feature.play.TextureForgeScreen
import com.stratum.feature.play.TextureForgeViewModel
import com.stratum.feature.play.gl.AndroidImageCodec
import kotlinx.coroutines.launch

/*
 * The studio's tools, each wired to the stores it reads and writes. Every
 * route takes the app's state and two ways out -- up a level, and to settings
 * when a tool needs a model key -- so none of them knows where it sits in the
 * menu.
 */

@Composable
internal fun ClassForgeRoute(app: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val content by app.game.content.collectAsStateWithLifecycle()
    val customClasses by app.game.customClasses.collectAsStateWithLifecycle()
    val sheets by app.graph.ai.sprites.sheets.collectAsStateWithLifecycle()
    val classViewModel: ClassForgeViewModel = viewModel(
        // Keyed on the classes and sheets, so the picker rebuilds when either changes.
        key = "classes-${customClasses.hashCode()}-${sheets.size}",
        factory = ClassForgeViewModel.factory(
            content = content,
            saveClass = app.game::saveClass,
            deleteClass = app.game::deleteClass,
            loadClasses = app.graph.classes::all,
            loadSheets = app.graph.ai.sprites::all,
        ),
    )
    ClassForgeScreen(viewModel = classViewModel, modifier = modifier, onBack = onBack)
}

@Composable
internal fun TextureForgeRoute(app: AppViewModel, onBack: () -> Unit, onPlay: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val content by app.game.contentWithSprites.collectAsStateWithLifecycle()
    val ai = app.graph.ai
    val forge: TextureForgeViewModel = viewModel(
        key = "textures-${content.packs.size}",
        factory = TextureForgeViewModel.factory(
            content,
            model = ai.imageModel.takeIf { ai.isConfigured() },
            root = app.graph.forgeDirectory,
            initialPrompt = app.game.stylePrompt.value,
        ),
    )
    TextureForgeScreen(
        viewModel = forge,
        actions = TextureForgeActions(
            onBack = onBack,
            onPlay = { prompt ->
                app.game.saveStyle(prompt)
                onPlay()
            },
            onOpenSettings = onOpenSettings,
        ),
        modifier = modifier,
    )
}

@Composable
internal fun CrewRoute(app: AppViewModel, preset: String?, onBack: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val content by app.game.content.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ai = app.graph.ai
    val plugins = app.graph.plugins
    val crewViewModel: CrewViewModel = viewModel(
        factory = CrewViewModel.factory(
            model = ai.languageModel,
            base = { app.game.content.value.packs },
            presets = CrewPresets.available(content.agentRoles),
            isProviderConfigured = ai::isConfigured,
            onInstall = { pack -> scope.launch { plugins.installGenerated(pack); plugins.repository.refresh() } },
            initialPreset = preset,
        ),
    )
    // The view model outlives this screen, so a preset chosen on the way back in is applied here.
    LaunchedEffect(preset) { preset?.let(crewViewModel::selectPreset) }
    CrewScreen(viewModel = crewViewModel, modifier = modifier, onBack = onBack, onOpenSettings = onOpenSettings)
}

@Composable
internal fun ContentForgeRoute(app: AppViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    val ai = app.graph.ai
    val plugins = app.graph.plugins
    val armoury: ContentForgeViewModel = viewModel(
        factory = ContentForgeViewModel.factory(
            model = ai.languageModel,
            base = { app.game.content.value.packs },
            isProviderConfigured = ai::isConfigured,
            creations = plugins::creations,
            saveCreations = { pack -> plugins.installCreations(pack) },
        ),
    )
    LaunchedEffect(armoury) { armoury.refreshProvider() }
    ContentForgeScreen(viewModel = armoury, modifier = modifier, onBack = onBack, onOpenSettings = onOpenSettings, onShare = onShare)
}

@Composable
internal fun SpriteForgeRoute(
    app: AppViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onMapFrames: () -> Unit,
    onPoseForge: () -> Unit,
    onWeaponForge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ai = app.graph.ai
    val spriteViewModel: SpriteForgeViewModel = viewModel(
        factory = SpriteForgeViewModel.factory(
            generateSheet = ai.generateSpriteSheet,
            saveSheet = { sheet, bytes ->
                // Keyed and checked before it is stored, so a sheet on disk is
                // always one the world can draw, cut on the grid the model
                // actually drew. Doing either at draw time would pay every frame.
                val prepared = GeneratedSheetPreparer.prepare(sheet, bytes)
                ai.sprites.save(prepared.sheet, prepared.bytes)
                prepared.preparation()
            },
            loadSheets = ai.sprites::all,
            deleteSheet = { id -> ai.sprites.delete(id) },
            isProviderConfigured = ai::isConfigured,
            shareSheets = { ids ->
                // As a plugin, so what arrives on another device is a
                // character the game can draw, not a loose PNG.
                app.graph.plugins.shareSheets(
                    ids.mapNotNull { id ->
                        val sheet = ai.sprites.all().firstOrNull { it.id == id }
                        val bytes = ai.sprites.bytesFor(id)
                        if (sheet != null && bytes != null) SheetArt(sheet, bytes) else null
                    },
                )
            },
        ),
    )
    SpriteForgeScreen(
        viewModel = spriteViewModel,
        modifier = modifier,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        // The drawable check, so a blank sheet reads as blank here rather
        // than as an empty rectangle the player has to interpret.
        previewFor = { id -> ai.sprites.drawableBitmapFor(id)?.asImageBitmap() },
        onMapFrames = onMapFrames,
        onPoseForge = onPoseForge,
        onWeaponForge = onWeaponForge,
    )
}

@Composable
internal fun WeaponForgeRoute(app: AppViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val ai = app.graph.ai
    val sheets by ai.sprites.sheets.collectAsStateWithLifecycle()
    val loadout by app.game.loadout.collectAsStateWithLifecycle()
    val weaponViewModel: WeaponForgeViewModel = viewModel(
        factory = WeaponForgeViewModel.factory(
            drawWeapon = { request, observer -> ai.generateWeapon(request, observer) },
            storeWeapon = { request, bytes ->
                // Keyed and trimmed before it is stored: the grip is a fraction
                // of the weapon's box, and a weapon adrift on an empty canvas
                // would be held by the air beside it.
                WeaponPreparer.prepare(id = "weapon:${request.slug()}", name = request.subject.trim(), kind = request.kind, bytes = bytes)
                    ?.let { prepared ->
                        ai.weapons.save(prepared.weapon, prepared.bytes)
                        prepared.weapon
                    }
            },
            loadWeapons = ai.weapons::all,
            loadSheets = { sheets },
            fitFor = ai.weaponFits::fitFor,
            saveFit = { sheetId, fit -> ai.weaponFits.save(sheetId, fit) },
            deleteWeapon = { id ->
                ai.weapons.delete(id)
                if (app.game.loadout.value.equippedWeaponId == id) app.game.equipWeapon(null)
            },
            isProviderConfigured = ai::isConfigured,
        ),
    )
    WeaponForgeScreen(
        viewModel = weaponViewModel,
        modifier = modifier,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onEquip = app.game::equipWeapon,
        equippedId = loadout.equippedWeaponId,
        previewFor = { id -> ai.weapons.bitmapFor(id)?.asImageBitmap() },
        sheetImageFor = { id -> ai.sprites.drawableBitmapFor(id)?.asImageBitmap() },
    )
}

@Composable
internal fun SpriteMapperRoute(app: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ai = app.graph.ai
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

@Composable
internal fun ModelForgeRoute(app: AppViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ai = app.graph.ai
    val modelViewModel: ModelForgeViewModel = viewModel(
        factory = ModelForgeViewModel.factory(
            generate = { brief, observer -> ai.models.generate(brief, observer) },
            pipeline = ai.models.pipeline,
            storage = ai.models.storage,
            propBlocks = { app.game.content.value.registry.all.filter { it.glyph != null } },
            allBlocks = { app.game.content.value.registry.all },
            providerLabel = { ai.settings.loadModelProvider().provider.displayName },
            isProviderConfigured = { ai.settings.isModelProviderConfigured },
            encodePng = AndroidImageCodec::encodePng,
            onContentChanged = app.game::modelsChanged,
        ),
    )
    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val mime = context.contentResolver.getType(uri) ?: "image/png"
            if (bytes != null) modelViewModel.setReference(bytes, mime)
        }
    }
    ModelForgeScreen(
        viewModel = modelViewModel,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onPickReference = { referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        modifier = modifier,
    )
}

/**
 * Shares the player's classes and kept creations as a plugin, or says why
 * there is nothing to share. One place, because the content forge and the
 * share hub both offer it.
 */
internal fun shareCreations(app: AppViewModel, context: android.content.Context) {
    if (!app.graph.plugins.shareCreations(app.game.customClasses.value)) {
        Toast.makeText(context, "Build a class or keep something from the forge first", Toast.LENGTH_SHORT).show()
    }
}
