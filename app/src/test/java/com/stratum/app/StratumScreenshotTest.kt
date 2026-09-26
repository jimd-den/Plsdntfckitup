package com.stratum.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import com.stratum.core.designsystem.theme.LocalSafeAreaInsets
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.world.IsometricProjection
import com.stratum.engine.world.WorldSession
import com.stratum.core.domain.ai.GeneratedPackDto
import com.stratum.core.domain.ai.toDomain
import com.stratum.feature.forge.ForgeScreenContent
import com.stratum.feature.forge.ForgeStatus
import com.stratum.feature.forge.ForgeUiState
import com.stratum.feature.forge.SpriteForgeContent
import com.stratum.feature.forge.SpriteForgeUiState
import com.stratum.core.domain.content.ClassDraft
import com.stratum.core.domain.content.ClassOptions
import com.stratum.feature.hero.ClassForgeScreenContent
import com.stratum.feature.hero.ClassForgeUiState
import com.stratum.feature.play.PlayScreenContent
import com.stratum.feature.play.RealmOption
import com.stratum.feature.play.RealmPanel
import com.stratum.feature.play.PlayUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the new shell so a visual regression shows up as a changed file rather
 * than as a surprise on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class StratumScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun home_screen() {
        composeTestRule.setContent {
            StratumTheme(palette = IgboContentPack.palette, darkTheme = true) {
                StratumApp(modifier = Modifier.fillMaxSize())
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/home.png")
    }

    @Test
    @Config(qualifiers = "+land")
    fun home_screen_landscape() {
        composeTestRule.setContent {
            StratumTheme(palette = IgboContentPack.palette, darkTheme = true) {
                StratumApp(modifier = Modifier.fillMaxSize())
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/home_landscape.png")
    }

    @Test
    fun world_setup() {
        composeTestRule.setContent {
            StratumTheme(palette = IgboContentPack.palette, darkTheme = true) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(com.stratum.core.designsystem.theme.StratumTheme.colors.surface).padding(16.dp)) {
                    WorldSetupCard(
                        rules = com.stratum.core.domain.world.RulesPresets.survivor.rules.copy(raids = false),
                        suggested = com.stratum.core.domain.world.WorldRules(),
                        onRulesChange = {},
                        startExpanded = true,
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/world_setup.png")
    }

    /** A run part way through: a progress bar, a gallery filling in, one failure. */
    private fun forgeInProgress(): com.stratum.feature.play.TextureForgeUiState {
        val content = GameSetup.assemble()
        val orders = com.stratum.core.domain.art.ForgePlanner.plan(
            com.stratum.core.domain.art.StyleLexicon.interpret("painterly impasto").direction,
            com.stratum.feature.play.TextureForgeViewModel.mergedPack(content),
            includeActors = false,
        )
        var progress = com.stratum.engine.scene.forge.ForgeProgress(total = orders.size)
        orders.take(9).forEachIndexed { i, order ->
            val texture = if (i == 4) null else com.stratum.engine.scene.Texture(1, 1, IntArray(1))
            progress = progress.recording(com.stratum.engine.scene.forge.ForgedAsset(order, texture, "rejected: flat colour".takeIf { texture == null }))
        }
        val swatches = listOf(0xFF6B8E4E, 0xFF8C6A48, 0xFF5A5F66, 0xFFB08D57, 0xFF3F6B5A, 0xFF7A4E3A, 0xFF9DA38F, 0xFF4E5B3A)
        val gallery = progress.latest.mapIndexed { i, order ->
            val image = androidx.compose.ui.graphics.ImageBitmap(32, 32)
            androidx.compose.ui.graphics.Canvas(image).drawRect(0f, 0f, 32f, 32f, androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color(swatches[i % swatches.size]) })
            com.stratum.feature.play.ForgedThumb(order.key, order.subject, image)
        }
        return com.stratum.feature.play.TextureForgeUiState(
            prompt = "painterly impasto",
            summary = "thick brushwork, warm light, soft shadows",
            regions = content.biomes,
            planned = orders.size,
            planDescription = com.stratum.engine.scene.forge.ForgeProgress.describe(orders),
            progress = progress,
            gallery = gallery,
            hasModel = true,
        )
    }

    @Test
    fun texture_forge_screen() {
        val state = forgeInProgress()
        composeTestRule.setContent {
            StratumTheme(palette = IgboContentPack.palette, darkTheme = true) {
                com.stratum.feature.play.TextureForgeContent(state = state, actions = com.stratum.feature.play.TextureForgeActions())
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/texture_forge.png")
    }

    @Test
    @Config(qualifiers = "+land")
    fun texture_forge_screen_landscape() {
        val state = forgeInProgress()
        composeTestRule.setContent {
            StratumTheme(palette = IgboContentPack.palette, darkTheme = true) {
                com.stratum.feature.play.TextureForgeContent(state = state, actions = com.stratum.feature.play.TextureForgeActions())
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/texture_forge_landscape.png")
    }

    @Test
    fun play_screen() {
        val content = GameSetup.assemble()
        val session = WorldSession(content, WorldConfig(seed = 99L, simulationRadius = 2))

        // Run the world forward so the shot shows a live fight rather than an
        // empty field: monsters spawn, close in, and chip the player's health.
        repeat(40) { session.tick(0.25f) }
        // Put monsters in reach and land blows, so the shot shows the
        // feedback rather than an idle field.
        // The sturdiest monsters the pack defines, so they survive the blow
        // and the shot shows a fight rather than three corpses. Engine state is
        // internal to :engine:world, so this goes through the public spawn API.
        val sturdy = content.enemies.sortedByDescending { it.baseStats.maxHealth }
        repeat(3) { i ->
            session.spawn(
                sturdy[i % sturdy.size],
                session.player.position.translated(1f + i * 0.5f, -0.6f + i * 0.6f, 0f),
            )
        }
        session.attack()
        session.castSkill(content.skills.first().id)
        session.setMoveInput(1f, -0.4f)
        session.dodge()
        session.tick(0.05f)
        val slain = content.enemies.first()
        session.dropLoot(
            com.stratum.engine.world.LootRoller(content.weapons, content.affixes)
                .craft(
                    content.weapons.last(),
                    itemLevel = 24,
                    rarity = com.stratum.core.domain.item.ItemRarity.EPIC,
                    random = kotlin.random.Random(5),
                ),
            session.player.position.translated(2f, 1f, 0f),
        )
        session.spawn(slain, session.player.position.translated(3f, -1f, 0f))

        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(
                    state = PlayUiState(
                        player = session.player,
                        camera = session.player.position,
                        projection = IsometricProjection(zoom = 1f),
                        palette = content.palette,
                        biomeName = session.currentBiome.name,
                        enemies = session.enemies,
                        groundLoot = session.groundLoot,
                        skills = session.skills,
                        isRolling = session.isRolling,
                        isInvulnerable = session.isInvulnerable,
                        rollCooldownFraction = session.rollCooldownFraction,
                        feedback = session.feedback,
                        playerFlash = 0.7f,
                        flashFor = session::flashFor,
                    ),
                    world = session.world,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/play.png")
    }

    @Test
    fun build_mode() {
        val content = GameSetup.assemble()
        val session = WorldSession(content, WorldConfig(seed = 99L, simulationRadius = 2))
        repeat(20) { session.tick(0.2f) }

        // A room ghosted but not yet committed: the shape the player is about
        // to commit to is the whole point of the preview.
        val feet = session.player.blockPos
        session.selectBuildTool(com.stratum.engine.world.BuildTool.ROOM)
        session.previewBuild(
            com.stratum.core.domain.world.BlockPos(feet.x + 2, feet.y + 1, feet.z),
            com.stratum.core.domain.world.BlockPos(feet.x + 7, feet.y + 6, feet.z),
        )

        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(
                    state = PlayUiState(
                        player = session.player,
                        camera = session.player.position,
                        projection = IsometricProjection(zoom = 1f),
                        palette = content.palette,
                        biomeName = session.currentBiome.name,
                        enemies = session.enemies,
                        skills = session.skills,
                        buildMode = true,
                        buildPreview = session.buildPreview,
                        buildTool = com.stratum.engine.world.BuildTool.ROOM,
                        buildAffordable = true,
                    ),
                    world = session.world,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/build.png")
    }

    /**
     * A phone with a punch-hole camera and a gesture bar. Robolectric has no
     * cutout of its own, so the layout is rendered against a declared one: a
     * HUD that has never been drawn under a camera hole is a HUD nobody has
     * checked.
     */
    @Test
    fun play_screen_with_camera_cutout() {
        val content = GameSetup.assemble()
        val session = WorldSession(content, WorldConfig(seed = 99L, simulationRadius = 2))
        repeat(20) { session.tick(0.2f) }

        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                CompositionLocalProvider(
                    LocalSafeAreaInsets provides WindowInsets(
                        left = 0.dp, top = 54.dp, right = 0.dp, bottom = 32.dp,
                    ),
                ) {
                    PlayScreenContent(
                        state = PlayUiState(
                            player = session.player,
                            camera = session.player.position,
                            projection = IsometricProjection(zoom = 1f),
                            palette = content.palette,
                            biomeName = session.currentBiome.name,
                            enemies = session.enemies,
                            skills = session.skills,
                        ),
                        world = session.world,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/play_cutout.png")
    }

    @Test
    fun death_screen() {
        val content = GameSetup.assemble()
        val session = WorldSession(content, WorldConfig(seed = 99L, simulationRadius = 2))
        repeat(20) { session.tick(0.2f) }
        while (session.player.isAlive) session.hurtPlayer(50)

        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(
                    state = PlayUiState(
                        player = session.player,
                        camera = session.player.position,
                        projection = IsometricProjection(zoom = 1f),
                        palette = content.palette,
                        biomeName = session.currentBiome.name,
                        enemies = session.enemies,
                        skills = session.skills,
                    ),
                    world = session.world,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/death.png")
    }

    @Test
    fun provider_settings_screen() {
        composeTestRule.setContent {
            StratumTheme(palette = IgboContentPack.palette, darkTheme = true) {
                ProviderSettingsScreen(
                    initial = com.stratum.core.data.ai.ProviderConfig(
                        apiKey = "sk-or-v1-not-a-real-key",
                        model = "anthropic/claude-sonnet-4",
                        imageModel = "black-forest-labs/flux-1.1-pro",
                    ),
                    onSave = {},
                    onBack = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/settings.png")
    }

    @Test
    fun class_forge_screen() {
        val content = GameSetup.assemble()

        // A half-built class: named, points spent unevenly, two skills taken.
        // An empty form proves the fields exist; a build in progress proves the
        // readout keeps up with the choices.
        val draft = ClassDraft(
            name = "Nsibidi Scribe",
            title = "Keeper of the Marks",
            description = "Reads the marks left on bronze, and writes new ones in a fight.",
            resourceName = "Nsibidi",
        )
            .withAttribute(ClassDraft.Attribute.STRENGTH, 8)
            .withAttribute(ClassDraft.Attribute.INSIGHT, ClassDraft.SKILL_THRESHOLD)
            .toggling(content.skills.first().id)
            .toggling(content.skills[1].id)
            .copy(startingWeaponId = content.weapons.first().id)
            .togglingBlock(content.registry.all.first { !it.isAir && it.isBreakable }.id)
            .copy(spriteSetId = "hero:nsibidi_scribe")

        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                ClassForgeScreenContent(
                    state = ClassForgeUiState(
                        draft = draft,
                        options = ClassOptions.from(content),
                        skills = content.skills,
                        weapons = content.weapons,
                        blocks = content.registry.all.filter { !it.isAir && it.isBreakable },
                        // Art the sprite forge has drawn, one of it chosen: the
                        // picker only exists when there is something to pick.
                        sheets = listOf(
                            com.stratum.core.domain.sprite.SpriteSheet(
                                id = "hero:ancestral_warrior", name = "Ancestral Warrior",
                                columns = 4, rows = 4, frameWidth = 64, frameHeight = 64,
                            ),
                            com.stratum.core.domain.sprite.SpriteSheet(
                                id = "hero:nsibidi_scribe", name = "Nsibidi Scribe",
                                columns = 4, rows = 4, frameWidth = 64, frameHeight = 64,
                            ),
                        ),
                        saved = listOf(draft.copy(name = "Ogu Warden").toDefinition()),
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/class_forge.png")
    }

    @Test
    fun satchel_screen() {
        val content = GameSetup.assemble()
        val session = WorldSession(content, WorldConfig(seed = 99L, simulationRadius = 2))
        repeat(20) { session.tick(0.2f) }

        // A bag with a clear upgrade, a clear downgrade and a socketed relic, so
        // the compare lines have something to disagree about.
        val roller = com.stratum.engine.world.LootRoller(content.weapons, content.affixes, content.inserts)
        listOf(
            com.stratum.core.domain.item.ItemRarity.RELIC to 28,
            com.stratum.core.domain.item.ItemRarity.RARE to 14,
            com.stratum.core.domain.item.ItemRarity.COMMON to 2,
        ).forEachIndexed { index, (rarity, level) ->
            // Dropped and walked over rather than written straight into the bag:
            // the bag is internal to the engine, which is the boundary doing its
            // job, so the fixture takes the same route a player would.
            session.dropLoot(
                roller.craft(
                    content.weapons[index % content.weapons.size],
                    itemLevel = level,
                    rarity = rarity,
                    random = kotlin.random.Random(index.toLong() + 3),
                ),
                session.player.position,
            )
            session.tick(0.05f)
        }
        content.inserts.take(3).forEach { session.dropInsert(it.id, session.player.position) }
        session.tick(0.05f)

        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(
                    state = PlayUiState(
                        player = session.player,
                        camera = session.player.position,
                        projection = IsometricProjection(zoom = 1f),
                        palette = content.palette,
                        biomeName = session.currentBiome.name,
                        enemies = session.enemies,
                        skills = session.skills,
                        heldInserts = session.heldInserts,
                        insertFor = session::insertOrNull,
                        rarityColors = content::rarityColor,
                        satchelOpen = true,
                    ),
                    world = session.world,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/satchel.png")
    }

    /** A live fight in the 2D view, which renders off-device, for layout checks. */
    private fun fight(): Pair<com.stratum.core.domain.content.AssembledContent, WorldSession> {
        val content = GameSetup.assemble()
        val session = WorldSession(content, WorldConfig(seed = 99L, simulationRadius = 2))
        repeat(40) { session.tick(0.25f) }
        val sturdy = content.enemies.sortedByDescending { it.baseStats.maxHealth }
        repeat(3) { i -> session.spawn(sturdy[i % sturdy.size], session.player.position.translated(1f + i * 0.5f, -0.6f + i * 0.6f, 0f)) }
        session.attack()
        session.tick(0.05f)
        return content to session
    }

    private fun fightState(content: com.stratum.core.domain.content.AssembledContent, session: WorldSession) = PlayUiState(
        player = session.player,
        camera = session.player.position,
        projection = IsometricProjection(zoom = 1f),
        palette = content.palette,
        biomeName = session.currentBiome.name,
        enemies = session.enemies,
        skills = session.skills,
        feedback = session.feedback,
        flashFor = session::flashFor,
        use3D = false,
        settlementName = session.currentSettlement?.name,
        survival = com.stratum.feature.play.SurvivalPanel(
            active = true,
            needs = session.content.needs.mapIndexed { i, need -> com.stratum.feature.play.NeedView(need, listOf(72f, 22f, 55f).getOrElse(i) { 80f }) },
            night = true,
            day = 3,
            nearFire = true,
            canDrink = true,
            food = session.content.consumables.take(3).map { com.stratum.engine.world.Held(it, 2) },
            recipes = session.content.recipes.mapIndexed { i, r -> com.stratum.engine.world.RecipeOption(r, haveIngredients = i == 0, atStation = true) },
        ),
    )

    @Test
    @Config(qualifiers = "+land")
    fun play_screen_landscape() {
        val (content, session) = fight()
        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(state = fightState(content, session), world = session.world, modifier = Modifier.fillMaxSize())
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/play_landscape.png")
    }

    @Test
    @Config(qualifiers = "+land")
    fun build_mode_landscape() {
        val (content, session) = fight()
        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(state = fightState(content, session).copy(buildMode = true), world = session.world, modifier = Modifier.fillMaxSize())
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/build_landscape.png")
    }

    @Test
    fun camp_screen() {
        val (content, session) = fight()
        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(state = fightState(content, session).copy(campOpen = true), world = session.world, modifier = Modifier.fillMaxSize())
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/camp.png")
    }

    @Test
    fun realm_screen() {
        val (content, session) = fight()
        val strategy = com.stratum.core.domain.strategy.StandardStrategy
        val book = com.stratum.core.domain.strategy.StrategyBook(strategy.resources, strategy.structures, strategy.units)
        val outpost = com.stratum.core.domain.strategy.Outpost(
            id = "hold", name = "Umuaka Hold", centerX = 0, centerY = 0,
            structures = mapOf(strategy.hearth.id to 1, strategy.farm.id to 2, strategy.lumberCamp.id to 1, strategy.barracks.id to 1),
            stockpile = mapOf(strategy.food.id to 64f, strategy.timber.id to 41f, strategy.stone.id to 18f, strategy.metal.id to 3f),
            garrison = mapOf(strategy.militia.id to 2),
            raidIn = 214f,
        )
        val Colony = com.stratum.core.domain.strategy.Colony
        val panel = RealmPanel(
            active = true, here = outpost, outposts = listOf(outpost), resources = book.resources,
            netPerMinute = Colony.netPerMinute(outpost, book), population = Colony.population(outpost, book),
            workers = Colony.workersNeeded(outpost, book), defense = Colony.defense(outpost, book),
            structures = book.structures.map { RealmOption(it, Colony.canBuild(outpost, book, it.id), outpost.count(it.id)) },
            units = book.units.map { RealmOption(it, Colony.canRecruit(outpost, book, it.id), outpost.garrison[it.id] ?: 0) },
            followers = 2,
        )
        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(state = fightState(content, session).copy(realm = panel, realmOpen = true), world = session.world, modifier = Modifier.fillMaxSize())
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/realm.png")
    }

    @Test
    fun hero_tree_screen() {
        val content = GameSetup.assemble()
        val classId = content.heroClasses.first().id
        val session = WorldSession(
            content, WorldConfig(seed = 99L, simulationRadius = 2),
            hero = com.stratum.core.domain.session.HeroSave(id = classId, heroClassId = classId, level = 30),
        )
        val build = session.passiveBuild!!
        val tree = build.tree
        // Part of the way toward a notable, with another selected further out:
        // what the panel has to read clearly is a build in progress.
        val notables = tree.nodes.filter { it.kind == com.stratum.core.domain.passive.PassiveKind.NOTABLE }
            .sortedBy { build.pathTo(it.id)?.size ?: Int.MAX_VALUE }
        session.allocatePassive(notables.first().id)
        val target = notables[2].id

        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(
                    state = PlayUiState(
                        player = session.player,
                        camera = session.player.position,
                        projection = IsometricProjection(zoom = 1f),
                        palette = content.palette,
                        biomeName = session.currentBiome.name,
                        skills = session.skills,
                        hero = com.stratum.feature.play.HeroPanelState(
                            open = true,
                            tree = tree,
                            startId = build.startId,
                            selectedNode = target,
                            path = session.passiveBuild!!.pathTo(target).orEmpty(),
                        ),
                    ),
                    world = session.world,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/hero_tree.png")
    }

    @Test
    @Config(qualifiers = "+land")
    fun hero_tree_screen_landscape() {
        val content = GameSetup.assemble()
        val classId = content.heroClasses.first().id
        val session = WorldSession(
            content, WorldConfig(seed = 99L, simulationRadius = 2),
            hero = com.stratum.core.domain.session.HeroSave(id = classId, heroClassId = classId, level = 30),
        )
        val build = session.passiveBuild!!
        val tree = build.tree
        // Part of the way toward a notable, with another selected further out:
        // what the panel has to read clearly is a build in progress.
        val notables = tree.nodes.filter { it.kind == com.stratum.core.domain.passive.PassiveKind.NOTABLE }
            .sortedBy { build.pathTo(it.id)?.size ?: Int.MAX_VALUE }
        session.allocatePassive(notables.first().id)
        val target = notables[2].id

        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(
                    state = PlayUiState(
                        player = session.player,
                        camera = session.player.position,
                        projection = IsometricProjection(zoom = 1f),
                        palette = content.palette,
                        biomeName = session.currentBiome.name,
                        skills = session.skills,
                        hero = com.stratum.feature.play.HeroPanelState(
                            open = true,
                            tree = tree,
                            startId = build.startId,
                            selectedNode = target,
                            path = session.passiveBuild!!.pathTo(target).orEmpty(),
                        ),
                    ),
                    world = session.world,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/hero_tree_landscape.png")
    }

    @Test
    fun anvil_screen() {
        val content = GameSetup.assemble()
        val session = WorldSession(content, WorldConfig(seed = 99L, simulationRadius = 2))
        repeat(20) { session.tick(0.2f) }

        // A relic with four sockets, two of them already filled, and a pouch
        // with something to put in the rest: the state the panel has to read
        // clearly is the half-finished one, not the empty one.
        val relic = com.stratum.engine.world.LootRoller(content.weapons, content.affixes, content.inserts)
            .craft(
                content.weapons.last(),
                itemLevel = 24,
                rarity = com.stratum.core.domain.item.ItemRarity.RELIC,
                random = kotlin.random.Random(5),
            )
        content.inserts.take(5).forEach { session.dropInsert(it.id, session.player.position) }
        session.dropLoot(relic, session.player.position)
        session.tick(0.05f)

        val socketed = session.player.equippedWeapon!!
        content.inserts.take(2).forEach { session.slotInsert(socketed.instanceId, it.id) }

        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(
                    state = PlayUiState(
                        player = session.player,
                        camera = session.player.position,
                        projection = IsometricProjection(zoom = 1f),
                        palette = content.palette,
                        biomeName = session.currentBiome.name,
                        enemies = session.enemies,
                        skills = session.skills,
                        heldInserts = session.heldInserts,
                        insertFor = session::insertOrNull,
                        rarityColors = content::rarityColor,
                        anvilOpen = true,
                    ),
                    world = session.world,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/anvil.png")
    }

    @Test
    fun sprite_forge_rejection() {
        // The view that matters: the model said no, and the panel shows exactly
        // what was sent and exactly what came back.
        val attempt = com.stratum.core.domain.ai.GenerationAttempt(
            id = "img_1",
            label = "Image · 512×512",
            endpoint = "https://openrouter.ai/api/v1/images/generations",
            model = "anthropic/claude-sonnet-4",
            requestBody = "{\"model\":\"anthropic/claude-sonnet-4\"," +
                "\"prompt\":\"A 4x4 sprite sheet of an ancestral warrior…\"," +
                "\"n\":1,\"size\":\"512x512\",\"response_format\":\"b64_json\"}",
            redactedHeaders = mapOf(
                "Authorization" to "Bearer ****",
                "Content-Type" to "application/json",
            ),
            status = 404,
            responseBody = "{\"error\":{\"message\":\"No endpoints found for " +
                "anthropic/claude-sonnet-4 that support image generation.\"," +
                "\"code\":404}}",
            failure = "'anthropic/claude-sonnet-4' is not available on this provider.",
            durationMillis = 812,
        )

        composeTestRule.setContent {
            StratumTheme(palette = IgboContentPack.palette, darkTheme = true) {
                SpriteForgeContent(
                    state = SpriteForgeUiState(
                        subject = "ancestral warrior",
                        style = "bronze age, high contrast",
                        providerConfigured = true,
                        error = attempt.failure,
                        attempt = attempt,
                        detailsOpen = true,
                        stage = com.stratum.core.domain.ai.GenerationStage.FAILED,
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/sprite_rejection.png")
    }

    @Test
    fun forge_screen() {
        // Rendered with a result in hand, because the preview after generation
        // is the part of this screen worth guarding against regressions.
        val generated = GeneratedPackDto(
            id = "glasswake",
            name = "Glasswake",
            description = "A drowned city of glass beneath a frozen sea.",
            blocks = listOf(
                com.stratum.core.domain.ai.GeneratedBlockDto(
                    id = "glasswake:silt", name = "Black Silt", material = "SOIL",
                    hardness = 0.4f, topColor = "#2b2f3a", sideColor = "#1d2029",
                ),
                com.stratum.core.domain.ai.GeneratedBlockDto(
                    id = "glasswake:pane", name = "Cathedral Pane", material = "STONE",
                    hardness = 2.0f, opaque = false, topColor = "#5f8ea8", sideColor = "#3f6274",
                ),
                com.stratum.core.domain.ai.GeneratedBlockDto(
                    id = "glasswake:coldlight", name = "Coldlight Vein", material = "ORE",
                    hardness = 4.5f, requiredTier = 2, light = 10,
                    topColor = "#7fd4e0", sideColor = "#4a9aa6",
                ),
            ),
            biomes = listOf(
                com.stratum.core.domain.ai.GeneratedBiomeDto(
                    id = "glasswake:nave", name = "The Flooded Nave",
                    surfaceBlock = "glasswake:silt", subsurfaceBlock = "glasswake:silt",
                    fillerBlock = "glasswake:pane",
                ),
            ),
            heroClasses = listOf(
                com.stratum.core.domain.ai.GeneratedClassDto(
                    id = "glasswake:tidewright", name = "Tidewright", health = 220,
                ),
            ),
        ).toDomain("glasswake")

        composeTestRule.setContent {
            StratumTheme(palette = IgboContentPack.palette, darkTheme = true) {
                ForgeScreenContent(
                    state = ForgeUiState(
                        theme = "A drowned city of glass beneath a frozen sea",
                        status = ForgeStatus.READY,
                        result = generated,
                        providerConfigured = true,
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/forge.png")
    }
}
