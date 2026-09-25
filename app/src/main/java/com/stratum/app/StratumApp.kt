package com.stratum.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stratum.core.data.hero.CustomClassStore
import com.stratum.core.data.sprite.GeneratedSheetPreparer
import com.stratum.core.data.sprite.PoseGuideRenderer
import com.stratum.core.data.sprite.PoseSheetComposer
import com.stratum.core.data.sprite.SpriteAtlasBaker
import com.stratum.core.data.sprite.SpriteExporter
import com.stratum.core.data.sprite.WeaponPreparer
import com.stratum.core.domain.ai.ImageReference
import com.stratum.core.domain.ai.PoseScript
import com.stratum.core.domain.ai.PoseStep
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.CustomClassPack
import com.stratum.core.domain.sprite.OpenPoseImageReader
import com.stratum.core.domain.sprite.OpenPoseImport
import com.stratum.core.domain.sprite.OpenPoseJson
import com.stratum.core.domain.sprite.SheetPreparation
import com.stratum.core.domain.sprite.SpriteFallback
import com.stratum.core.domain.sprite.SpriteMapper
import com.stratum.core.domain.sprite.SpriteNamespace
import com.stratum.core.domain.sprite.WeaponPosing
import com.stratum.core.domain.sprite.WeaponRig
import com.stratum.feature.forge.ForgeScreen
import com.stratum.feature.forge.ForgeViewModel
import com.stratum.feature.forge.PoseForgeScreen
import com.stratum.feature.forge.PoseForgeViewModel
import com.stratum.feature.forge.PoseRun
import com.stratum.feature.forge.SpriteForgeScreen
import com.stratum.feature.forge.SpriteForgeViewModel
import com.stratum.feature.forge.SpriteMapperScreen
import com.stratum.feature.forge.SpriteMapperViewModel
import com.stratum.feature.forge.WeaponForgeScreen
import com.stratum.feature.forge.WeaponForgeViewModel
import com.stratum.feature.hero.ClassForgeScreen
import com.stratum.feature.hero.ClassForgeViewModel
import com.stratum.feature.library.LibraryScreen
import com.stratum.feature.library.LibraryViewModel
import com.stratum.feature.play.DrawableSprite
import com.stratum.feature.play.DrawableWeapon
import com.stratum.feature.play.PlayScreen as PlayScreenRoute
import com.stratum.feature.play.PlayViewModel
import com.stratum.feature.play.SpriteKey

/** Top-level destinations. Deliberately few: the game is the app, not a tab in it. */
private enum class Destination { HOME, PLAY, CLASSES, FORGE, SPRITES, POSES, WEAPONS, MAPPER, SETTINGS, STUDIO, LIBRARY }

/**
 * The app shell.
 *
 * Navigation is a single state value rather than a nav graph: with three
 * destinations and no deep links, a graph would be ceremony. It becomes one when
 * the studio screens are broken into their own feature modules.
 */
@Composable
fun StratumApp(
    modifier: Modifier = Modifier,
    studioContent: @Composable (onBack: () -> Unit) -> Unit = {},
) {
    var destination by remember { mutableStateOf(Destination.HOME) }

    val context = LocalContext.current

    // Packs the player has forged this session. Adding one rebuilds the
    // assembled content, so the next world is made of the new material.
    var forgedPacks by remember { mutableStateOf(emptyList<ContentPack>()) }

    // Classes the player built, loaded as a pack of their own so a class they
    // made and a class the pack shipped travel the same road.
    val classStore = remember(context) { CustomClassStore(context) }
    var classRevision by remember { mutableStateOf(0) }
    val customClasses = remember(classRevision) { classStore.all() }
    val ai = remember(context) { AiWiring(context) }

    // Games and maps the player imported. Loaded after the first frame, since
    // it re-reads every archive; the world assembles again when they arrive.
    val imports = remember(context, ai) { ImportWiring(context, ai.sprites) }
    val importedPacks by imports.repository.packs.collectAsStateWithLifecycle()
    LaunchedEffect(imports) { imports.repository.refresh() }

    val content = remember(forgedPacks, importedPacks, customClasses) {
        GameSetup.assemble(
            importedPacks + forgedPacks + if (customClasses.isEmpty()) emptyList()
            else listOf(CustomClassPack.of(customClasses)),
        )
    }

    // Persisted player art choices (hero class, sprite sheet, weapon)
    val initialLoadout = remember(ai) { ai.playerPreferences.load() }

    // Null means "whatever the packs list first", which is what a player who has
    // never opened the class forge should get.
    var heroClassId by remember { mutableStateOf<String?>(initialLoadout.heroClassId) }

    /**
     * The art the player picked, independent of which class they are playing.
     * Persisted across app restarts.
     */
    var heroSheetId by remember { mutableStateOf<String?>(initialLoadout.heroSheetId) }

    // What the player is holding. Persisted across app restarts.
    var equippedWeaponId by remember { mutableStateOf<String?>(initialLoadout.equippedWeaponId) }

    // Observable stores: sprite sheets and unified character aggregates
    val spriteSheets by ai.sprites.sheets.collectAsStateWithLifecycle()
    val characters by ai.characterRepository.characters.collectAsStateWithLifecycle()

    // Validate loadout against available content and clear gracefully if deleted
    LaunchedEffect(spriteSheets, heroSheetId) {
        if (heroSheetId != null && spriteSheets.none { it.id == heroSheetId }) {
            heroSheetId = null
            ai.playerPreferences.saveHeroSheet(null)
        }
    }
    LaunchedEffect(content, heroClassId) {
        if (heroClassId != null && content.heroClasses.none { it.id == heroClassId }) {
            heroClassId = null
            ai.playerPreferences.saveHeroClass(null)
        }
    }
    LaunchedEffect(heroClassId) {
        ai.playerPreferences.saveHeroClass(heroClassId)
    }
    LaunchedEffect(heroSheetId) {
        ai.playerPreferences.saveHeroSheet(heroSheetId)
    }
    LaunchedEffect(equippedWeaponId) {
        ai.playerPreferences.saveEquippedWeapon(equippedWeaponId)
    }

    // A new seed per run, but stable across recomposition so walking around does
    // not regenerate the world under the player.
    var seed by remember { mutableStateOf(System.currentTimeMillis()) }
    val graphics = remember(context) { GraphicsWiring(context) }
    val config = remember(seed) { GameSetup.worldConfig(seed, graphics.startingSettings().streamingRadius) }

    // The service is started by the run beginning, not by the forge opening:
    // it exists to protect work in flight, and one that started with the
    // screen would be a permanent notification about nothing.
    val generating by PoseRun.running.collectAsStateWithLifecycle()
    LaunchedEffect(generating) {
        if (generating) PoseGenerationService.start(context)
    }

    val contentWithSprites = remember(content, spriteSheets) {
        content.withSpriteSheets(spriteSheets)
    }

    // Keyed on the chosen class: the player's art is a property of who they are
    // playing, and resolving it without that was the whole bug — every class
    // was drawn with whichever hero sheet happened to be newest.
    val spriteResolver = remember(
        contentWithSprites, heroClassId, heroSheetId, equippedWeaponId, characters,
    ) {
        // The resolver is asked for a sprite on every drawn frame, for every
        // actor, so anything built here has to be built once and kept. A rig is
        // a map the size of the sheet's frame count; rebuilding it per frame
        // would allocate one per actor per frame inside the draw loop.
        val rigs = HashMap<String, WeaponRig>()
        val held = equippedWeaponId?.let { id ->
            val weapon = ai.weapons.find(id)
            val bitmap = ai.weapons.bitmapFor(id)
            if (weapon != null && bitmap != null) weapon to bitmap.asImageBitmap() else null
        }

        // Named rather than left as a bare trailing lambda: with a statement
        // above it, the compiler reads `{ ... }` as an argument to that
        // statement instead of as the value being remembered.
        val resolve: (SpriteKey) -> DrawableSprite? = { key: SpriteKey ->
            // Candidates in order of preference rather than one guess. The
            // first choice can resolve to a sheet with no usable image — one
            // that came back blank, or a pack sheet with no pixels on this
            // device — and picking it and then drawing nothing is how a
            // character ends up with no skin and no explanation.
            val candidates = when (key) {
                SpriteKey.Player -> {
                    val chosen = heroClassId ?: contentWithSprites.heroClasses.firstOrNull()?.id
                    // What the player picked outright comes first: it is the
                    // most explicit thing anybody has said about how they want
                    // to look, and it should not be overridden by what a class
                    // happens to carry.
                    listOfNotNull(
                        heroSheetId?.let { id ->
                            contentWithSprites.spriteSheets.firstOrNull { it.id == id }
                        },
                    ) +
                    // A player who has drawn art but assigned none still gets
                    // to see it, rather than art they made sitting unused
                    // because they missed a picker.
                    listOfNotNull(chosen?.let(contentWithSprites::sheetForHero)) +
                        SpriteFallback.spread(
                            contentWithSprites.spriteSheets
                                .filter { SpriteNamespace.servesHero(it.id) },
                            actorId = chosen.orEmpty(),
                        )
                }
                is SpriteKey.Monster ->
                    listOfNotNull(contentWithSprites.sheetForEnemy(key.definitionId)) +
                        // Spread by the enemy's id. Packs name enemies without
                        // naming art for them -- the art is the thing being
                        // generated -- so without this every kind of monster
                        // in the world is drawn with the same picture.
                        SpriteFallback.spread(
                            contentWithSprites.spriteSheets
                                .filter { SpriteNamespace.servesMonster(it.id) },
                            actorId = key.definitionId,
                        )
            }

            candidates.distinctBy { it.id }.firstNotNullOfOrNull { found ->
                ai.sprites.drawableBitmapFor(found.id)?.let { bitmap ->
                    DrawableSprite(
                        sheet = found,
                        image = bitmap.asImageBitmap(),
                        // Only the player carries one for now. Giving monsters
                        // weapons is the same mechanism plus a decision about
                        // which monster holds what, which is pack data.
                        weapon = if (key == SpriteKey.Player && held != null) {
                            DrawableWeapon(
                                sprite = held.first,
                                image = held.second,
                                rig = rigs.getOrPut(found.id) {
                                    WeaponPosing.rigFor(
                                        sheet = found,
                                        fit = ai.weaponFits.fitFor(found.id),
                                        // The poses the art was drawn against.
                                        // Rigging against anything else hangs
                                        // the sword off a hand that is not there.
                                        guides = ai.poseGuides.guidesFor(found.id),
                                    )
                                },
                            )
                        } else {
                            null
                        },
                    )
                }
            }
        }
        resolve
    }

    // Remembered on the resolver rather than rebuilt each recomposition, so
    // the portrait below can be keyed on it: a lambda made fresh every frame
    // would make `remember` re-decode the sheet on every recomposition, and a
    // lambda that never changed would never notice new art.
    val heroPortrait: (String) -> DrawableSprite? = remember(spriteResolver) {
        // Resolved through the same path the world uses, so what is shown is
        // what will actually be drawn -- a preview from somewhere else would
        // be a promise the run need not keep.
        //
        // No guard on which class was asked for. There used to be one,
        // comparing against the raw chosen id, and that id is null until
        // somebody taps a class while the screen falls back to the first one
        // for display -- so on a fresh launch the comparison always failed and
        // the main screen showed no character at all. It was redundant as well
        // as wrong: this is only ever asked about the class already selected.
        { _: String -> spriteResolver(SpriteKey.Player) }
    }

    when (destination) {
        Destination.HOME -> {
            val unpackedCharacters = remember(characters) {
                characters.filter { !it.isPacked && it.posesDrawn.isNotEmpty() }
            }
            HomeScreen(
                packName = content.packs.joinToString(" + ") { it.name },
                blockCount = content.registry.size,
                biomeCount = content.biomes.size,
                classCount = content.heroClasses.size,
                heroClasses = content.heroClasses,
                selectedClassId = heroClassId ?: content.heroClasses.firstOrNull()?.id,
                onSelectClass = { heroClassId = it },
                characterSheets = remember(spriteSheets) { spriteSheets },
                selectedSheetId = heroSheetId,
                onSelectSheet = { id -> heroSheetId = if (heroSheetId == id) null else id },
                idleFrameFor = heroPortrait,
                unpackedCharacterCount = unpackedCharacters.size,
                onPoseForge = { destination = Destination.POSES },
                onBuildClass = { destination = Destination.CLASSES },
                onDescend = {
                    seed = System.currentTimeMillis()
                    destination = Destination.PLAY
                },
                onForge = { destination = Destination.FORGE },
                onSprites = { destination = Destination.SPRITES },
                spriteCount = spriteSheets.size,
                onSettings = { destination = Destination.SETTINGS },
                onStudio = { destination = Destination.STUDIO },
                onLibrary = { destination = Destination.LIBRARY },
                importedCount = importedPacks.size,
                modifier = modifier,
            )
        }

        Destination.LIBRARY -> {
            val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(imports.repository))
            LibraryScreen(
                viewModel = viewModel,
                onBack = { destination = Destination.HOME },
                modifier = modifier,
            )
        }

        Destination.PLAY -> {
            // Keyed so forging a pack or starting a new run builds a fresh
            // session rather than reusing the previous world.
            key(contentWithSprites, config, heroClassId) {
                val viewModel: PlayViewModel = viewModel(
                    factory = PlayViewModel.factory(
                        contentWithSprites, config,
                        heroClassId = heroClassId,
                        spriteResolver = spriteResolver,
                        imageModel = ai.imageModel,
                        kitDirectory = java.io.File(context.filesDir, "forge"),
                        kitOverlays = imports.textureDirectories(),
                        quality = graphics.chosen,
                        saveQuality = graphics::choose,
                    ),
                )
                PlayScreenRoute(
                    viewModel = viewModel,
                    modifier = modifier,
                    onOpenMenu = { destination = Destination.HOME },
                )
            }
        }

        Destination.CLASSES -> {
            val classViewModel: ClassForgeViewModel = viewModel(
                // Keyed on class revision and sheet count to rebuild picker
                // when classes or sheets change.
                key = "classes-$classRevision-${spriteSheets.size}",
                factory = ClassForgeViewModel.factory(
                    content = content,
                    saveClass = { hero ->
                        classStore.save(hero)
                        classRevision++
                    },
                    deleteClass = { id ->
                        classStore.delete(id)
                        // Playing as a class that no longer exists would silently
                        // fall back to another one; forget the choice instead.
                        if (heroClassId == id) heroClassId = null
                        classRevision++
                    },
                    loadClasses = classStore::all,
                    loadSheets = ai.sprites::all,
                ),
            )
            ClassForgeScreen(
                viewModel = classViewModel,
                modifier = modifier,
                onBack = { destination = Destination.HOME },
            )
        }

        Destination.FORGE -> {
            val forgeViewModel: ForgeViewModel = viewModel(
                factory = ForgeViewModel.factory(
                    generatePack = ai.generateContentPack,
                    generateLore = ai.generateLore,
                    isProviderConfigured = ai::isConfigured,
                    onPackAccepted = { pack -> forgedPacks = forgedPacks + pack },
                ),
            )
            ForgeScreen(
                viewModel = forgeViewModel,
                modifier = modifier,
                onBack = { destination = Destination.HOME },
                onOpenSettings = { destination = Destination.SETTINGS },
            )
        }

        Destination.SPRITES -> {
            val spriteViewModel: SpriteForgeViewModel = viewModel(
                factory = SpriteForgeViewModel.factory(
                    generateSheet = ai.generateSpriteSheet,
                    saveSheet = { sheet, bytes ->
                        // Keyed and checked before it is stored, so a sheet on
                        // disk is always one the world can draw, cut on the grid
                        // the model actually drew. Doing either at draw time
                        // would pay the cost every frame.
                        val prepared = GeneratedSheetPreparer.prepare(sheet, bytes)
                        ai.sprites.save(prepared.sheet, prepared.bytes)
                        SheetPreparation(
                            prepared.sheet,
                            prepared.keyStrategy,
                            prepared.grid,
                            prepared.looksEmpty,
                        )
                    },
                    loadSheets = ai.sprites::all,
                    deleteSheet = { id ->
                        ai.sprites.delete(id)
                    },
                    isProviderConfigured = ai::isConfigured,
                ),
            )
            SpriteForgeScreen(
                viewModel = spriteViewModel,
                modifier = modifier,
                onBack = { destination = Destination.HOME },
                onOpenSettings = { destination = Destination.SETTINGS },
                // The drawable check, so a blank sheet reads as blank here
                // rather than as an empty rectangle the player has to
                // interpret.
                previewFor = { id -> ai.sprites.drawableBitmapFor(id)?.asImageBitmap() },
                onMapFrames = { destination = Destination.MAPPER },
                onPoseForge = { destination = Destination.POSES },
                onWeaponForge = { destination = Destination.WEAPONS },
            )
        }

        Destination.POSES -> {
            val poseViewModel: PoseForgeViewModel = viewModel(
                factory = PoseForgeViewModel.factory(
                    drawReference = { request, observer ->
                        ai.generateBasePose(request, observer)
                    },
                    drawPose = { request, observer -> ai.generatePoseFrame(request, observer) },
                    guideFor = { step, guides ->
                        // The same skeleton the weapon rig reads, drawn. One
                        // source of truth for where the body is, so the art and
                        // the sword can never disagree about it.
                        guides.poseFor(
                            state = step.state,
                            index = step.index,
                            frameCount = PoseScript.posesFor(step.state).size,
                        )?.let { pose ->
                            PoseGuideRenderer.render(pose, style = guides.style)
                                ?.let { ImageReference(it) }
                        }
                    },
                    readGuideImage = { bytes ->
                        // Pose libraries ship the rendered skeleton, not its
                        // keypoints, so the picture is read back to find the
                        // joints the weapon needs.
                        val bitmap = SpriteAtlasBaker.decode(bytes)
                        val pose = bitmap?.let {
                            val found = OpenPoseImageReader.read(
                                pixels = SpriteAtlasBaker.pixelsOf(it),
                                width = it.width,
                                height = it.height,
                            )
                            it.recycle()
                            found?.let(OpenPoseImport::toPose)
                        }
                        pose?.let(OpenPoseImport::normalised)
                    },
                    readGuideJson = { text ->
                        OpenPoseJson.parse(text)
                            ?.let(OpenPoseImport::toPose)
                            ?.let(OpenPoseImport::normalised)
                    },
                    loadGuides = ai.poseGuides::guidesFor,
                    saveGuides = { setId, guides ->
                        ai.poseGuides.save(setId, guides)
                    },
                    saveReference = ai.poses::saveReference,
                    loadReference = ai.poses::reference,
                    hasReference = ai.poses::hasReference,
                    savePose = ai.poses::savePose,
                    dropPose = ai.poses::deletePose,
                    posesDrawn = ai.poses::keysIn,
                    loadPose = ai.poses::pose,
                    composeSheet = { setId, plan ->
                        // Loaded by key rather than all at once: a full
                        // character is forty 1024-pixel images, which is more
                        // than a phone will hold decoded at the same time.
                        val composed = PoseSheetComposer.compose(plan) { key ->
                            ai.poses.pose(setId, key)
                        }
                        composed?.let {
                            ai.sprites.save(it.sheet, it.bytes)
                            it.packed()
                        }
                    },
                    savedCharacters = {
                        ai.characterRepository.all().map { it.toSavedCharacter() }
                    },
                    deleteCharacter = { setId ->
                        ai.characterRepository.delete(setId)
                    },
                    exportSheet = { sheetId, name ->
                        val bytes = ai.sprites.bytesFor(sheetId)
                        val file = bytes?.let {
                            SpriteExporter.exportBytes(
                                context = context,
                                bytes = it,
                                fileName = "${SpriteExporter.slug(name)}_sheet.png",
                                mimeType = "image/png",
                            )
                        }
                        file?.let {
                            SpriteExporter.share(context, it, "image/png", name)
                        }
                        file != null
                    },
                    exportReference = { setId, name ->
                        val file = ai.poses.reference(setId)?.let {
                            SpriteExporter.exportReference(context, name, it)
                        }
                        file?.let {
                            SpriteExporter.share(context, it, "image/png", name)
                        }
                        file != null
                    },
                    exportClip = { setId, name, key ->
                        val file = ai.poses.clip(setId, key)?.let {
                            SpriteExporter.exportClip(context, name, key, it)
                        }
                        file?.let {
                            SpriteExporter.share(context, it, SpriteExporter.MIME_VIDEO, name)
                        }
                        file != null
                    },
                    clipsDrawn = ai.poses::clipKeysIn,
                    drawClipRow = { request, observer -> ai.generateClipRow(request, observer) },
                    saveClip = ai.poses::saveClip,
                    exportPoses = { setId, name ->
                        val poses = ai.poses.keysIn(setId)
                            .mapNotNull { key -> ai.poses.pose(setId, key)?.let { key to it } }
                            .toMap()
                        val file = SpriteExporter.exportPoses(context, name, poses)
                        file?.let {
                            SpriteExporter.share(context, it, "application/zip", name)
                        }
                        file != null
                    },
                    isProviderConfigured = ai::isConfigured,
                ),
            )
            // Which frame an imported pose is destined for. The picker hands
            // back a Uri and nothing else, so the step has to be remembered
            // across the trip out to the system and back.
            var importingStep by remember { mutableStateOf<PoseStep?>(null) }
            val poseFilePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia(),
            ) { uri ->
                val step = importingStep
                importingStep = null
                if (uri != null && step != null) {
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                    if (bytes != null) poseViewModel.importGuideImage(step, bytes)
                }
            }

            PoseForgeScreen(
                viewModel = poseViewModel,
                modifier = modifier,
                onImportPose = { step ->
                    importingStep = step
                    poseFilePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onBack = { destination = Destination.SPRITES },
                onOpenSettings = { destination = Destination.SETTINGS },
                referenceFor = { setId ->
                    ai.poses.reference(setId)?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                },
                sheetPreviewFor = { id -> ai.sprites.drawableBitmapFor(id)?.asImageBitmap() },
            )
        }

        Destination.WEAPONS -> {
            val weaponViewModel: WeaponForgeViewModel = viewModel(
                factory = WeaponForgeViewModel.factory(
                    drawWeapon = { request, observer -> ai.generateWeapon(request, observer) },
                    storeWeapon = { request, bytes ->
                        // Keyed and trimmed before it is stored, because the
                        // grip is a fraction of the weapon's box and a weapon
                        // adrift on an empty canvas would be held by the air
                        // beside it.
                        WeaponPreparer.prepare(
                            id = "weapon:${request.slug()}",
                            name = request.subject.trim(),
                            kind = request.kind,
                            bytes = bytes,
                        )?.let { prepared ->
                            ai.weapons.save(prepared.weapon, prepared.bytes)
                            prepared.weapon
                        }
                    },
                    loadWeapons = ai.weapons::all,
                    loadSheets = { spriteSheets },
                    fitFor = ai.weaponFits::fitFor,
                    saveFit = { sheetId, fit ->
                        ai.weaponFits.save(sheetId, fit)
                    },
                    deleteWeapon = { id ->
                        ai.weapons.delete(id)
                        if (equippedWeaponId == id) equippedWeaponId = null
                    },
                    isProviderConfigured = ai::isConfigured,
                ),
            )
            WeaponForgeScreen(
                viewModel = weaponViewModel,
                modifier = modifier,
                onBack = { destination = Destination.SPRITES },
                onOpenSettings = { destination = Destination.SETTINGS },
                onEquip = { id -> equippedWeaponId = id },
                equippedId = equippedWeaponId,
                previewFor = { id -> ai.weapons.bitmapFor(id)?.asImageBitmap() },
                sheetImageFor = { id -> ai.sprites.drawableBitmapFor(id)?.asImageBitmap() },
            )
        }

        Destination.MAPPER -> {
            val mapperViewModel: SpriteMapperViewModel = viewModel(
                factory = SpriteMapperViewModel.factory(
                    openProject = { sheetId ->
                        // Only resume a project that was cut from the art the
                        // library holds now. A regenerated character keeps its
                        // id, so resuming blindly reopened the mapping made
                        // from the previous version's pixels and showed the
                        // old picture with no way to reach the new one.
                        ai.spriteProjects.load(sheetId)?.takeIf {
                            ai.spriteProjects.matchesSource(sheetId, ai.sprites.bytesFor(sheetId))
                        }
                    },
                    sourcePixels = { atlas ->
                        ai.spriteProjects.sourceFor(atlas.id)?.let(SpriteAtlasBaker::pixelsOf)
                    },
                    startProject = { sheet ->
                        // The art is copied into a project of its own before
                        // anything is mapped, so re-cutting a sheet can never
                        // damage the only copy of the image it was cut from.
                        val bitmap = ai.sprites.bitmapFor(sheet.id)
                        val bytes = ai.sprites.bytesFor(sheet.id)
                        if (bitmap == null || bytes == null) {
                            null
                        } else {
                            SpriteMapper.fromSheet(
                                sheet = sheet,
                                imageWidth = bitmap.width,
                                imageHeight = bitmap.height,
                            ).also { ai.spriteProjects.save(it, bytes) }
                        }
                    },
                    saveProject = { atlas -> ai.spriteProjects.save(atlas) },
                    bakeAtlas = { atlas ->
                        val source = ai.spriteProjects.sourceFor(atlas.id)
                        val baked = source?.let { SpriteAtlasBaker.bake(atlas, it) }
                        baked?.let {
                            // Saved under the atlas's own id, so re-baking a
                            // mapping replaces that sheet rather than leaving
                            // the world to choose between two versions of it.
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
                onBack = { destination = Destination.SPRITES },
                sourceFor = { atlas ->
                    ai.spriteProjects.sourceFor(atlas.id)?.asImageBitmap()
                },
            )
        }

        Destination.SETTINGS -> ProviderSettingsScreen(
            initial = ai.settings.load(),
            onSave = ai.settings::save,
            onBack = { destination = Destination.HOME },
            modifier = modifier,
        )

        Destination.STUDIO -> studioContent { destination = Destination.HOME }
    }
}
