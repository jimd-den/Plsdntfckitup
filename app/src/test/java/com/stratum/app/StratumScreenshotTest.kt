package com.stratum.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
