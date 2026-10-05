package com.stratum.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.stratum.app.hub.HeroChoice
import com.stratum.app.hub.NewWorldActions
import com.stratum.app.hub.NewWorldScreen
import com.stratum.app.world.NewWorldDraft
import com.stratum.app.world.NewWorldStep
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.micro.MicroModel
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.microbridge.ModelFactory
import com.stratum.engine.world.BuildTool
import com.stratum.engine.world.IsometricProjection
import com.stratum.engine.world.WorldSession
import com.stratum.feature.forge.VoxelStudioScreen
import com.stratum.feature.forge.VoxelStudioStorage
import com.stratum.feature.forge.VoxelStudioViewModel
import com.stratum.feature.play.BuildPanel
import com.stratum.feature.play.BuildTap
import com.stratum.feature.play.ModelChoice
import com.stratum.feature.play.PlayScreenContent
import com.stratum.feature.play.PlayUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The cozy builder: the build tray's new tools, the model studio, and a world described in words. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class CozyScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun build_tray_sculpting() {
        val content = GameSetup.assemble()
        val session = WorldSession(content, WorldConfig(seed = 99L, simulationRadius = 2))
        repeat(10) { session.tick(0.2f) }
        val feet = session.player.blockPos
        session.selectBuildTool(BuildTool.DOME)
        session.previewBuild(BlockPos(feet.x + 2, feet.y + 1, feet.z), BlockPos(feet.x + 8, feet.y + 7, feet.z))
        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                PlayScreenContent(
                    state = PlayUiState(
                        player = session.player, camera = session.player.position, projection = IsometricProjection(zoom = 1f),
                        palette = content.palette, biomeName = session.currentBiome.name, skills = session.skills,
                        buildMode = true, buildPreview = session.buildPreview, buildTool = BuildTool.DOME, buildAffordable = true,
                        build = BuildPanel(
                            height = 4, canUndo = true, canRedo = false, tap = BuildTap.HEAP, canSculpt = true, brushRadius = 3,
                            models = listOf(ModelChoice("a", "Lamu house", 5200, "8×7×6"), ModelChoice("b", "Baobab", 900, "3×3×5")),
                        ),
                    ),
                    world = session.world,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/build_tray_sculpting.png")
    }

    /** The play screen's view model publishes from `init`; with studio models to list it once crashed there, before the world loaded. */
    @Test
    fun play_starts_with_studio_models() {
        val content = GameSetup.assemble()
        val model = MicroModel("m", "Pillar", 4, 4, 8, listOf("#8A8A86"), IntArray(4 * 4 * 8) { 1 })
        val factory = com.stratum.feature.play.PlayViewModel.factory(content, WorldConfig(seed = 5L, simulationRadius = 1), microModels = listOf(model))
        val vm = factory.create(com.stratum.feature.play.PlayViewModel::class.java)
        org.junit.Assert.assertEquals(listOf("Pillar"), vm.state.value.build.models.map { it.name })
    }

    @Test
    fun model_studio() {
        val library = mutableListOf<MicroModel>()
        val storage = object : VoxelStudioStorage {
            override fun all() = library.toList()
            override fun save(model: MicroModel) { library += model }
            override fun delete(id: String) { library.removeAll { it.id == id } }
        }
        val (house, _) = ModelFactory.building(20260929L, tradition = "swahili", widthBlocks = 6, depthBlocks = 5)
        library += ModelFactory.building(7L, tradition = "hausa").first.copy(id = "h", name = "Hausa house")
        library += ModelFactory.building(8L, tradition = "great_zimbabwe").first.copy(id = "z", name = "Dry-stone hut")
        val vm = VoxelStudioViewModel(storage, { seed, t -> ModelFactory.building(seed, tradition = t).first }, listOf("swahili" to "Swahili", "hausa" to "Hausa"))
        vm.open(house.copy(name = "Lamu house"))
        vm.setLayer(6)
        composeTestRule.setContent {
            StratumTheme(darkTheme = true) {
                VoxelStudioScreen(viewModel = vm, onBack = {}, onPickImage = {}, modifier = Modifier.fillMaxSize())
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/model_studio.png")
    }

    @Test
    fun new_world_described() {
        val draft = NewWorldDraft(step = NewWorldStep.WORLD, prompt = "A peaceful walled kasbah town among the red dunes of the Sahara, with domed towers")
        composeTestRule.setContent {
            StratumTheme(darkTheme = true) {
                NewWorldScreen(
                    draft = draft,
                    heroes = listOf(HeroChoice(id = "h", name = "Dike", line = "260 health", level = 3)),
                    actions = NewWorldActions(),
                    modifier = Modifier.fillMaxSize(),
                    jobsTray = {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/new_world_described.png")
    }
}
