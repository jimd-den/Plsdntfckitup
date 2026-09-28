package com.stratum.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.world.WorldSession
import com.stratum.feature.play.ShaperStage
import com.stratum.feature.play.WorldShaperOverlay
import com.stratum.feature.play.WorldShaperPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/** The World panel, filled from a real microvoxel world's own stage descriptions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class WorldShaperScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun panel(): WorldShaperPanel {
        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack)).copy(terrain = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL))
        val hot = requireNotNull(WorldSession(content, WorldConfig(seed = 20260928L, simulationRadius = 1)).hotTerrain)
        val running = hot.passes.associateBy { it.id }
        return WorldShaperPanel(
            open = true,
            available = true,
            stages = hot.catalogue().map { ShaperStage(it.id, it.title, it.summary, it.id in running, it.params, running[it.id]?.options.orEmpty()) },
        )
    }

    @Test
    fun worldShaper() {
        val panel = panel()
        assertTrue(panel.stages.any { it.id == "micro:settlements" && it.enabled }, "the home town stage runs")
        composeTestRule.setContent {
            StratumTheme(palette = IgboContentPack.palette, darkTheme = true) {
                Box(Modifier.fillMaxSize().background(StratumTheme.colors.surface)) {
                    WorldShaperOverlay(panel = panel, onSet = { _, _, _ -> }, onToggleStage = { _, _ -> }, onResetStage = {}, onLandShape = {}, onClose = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/world_shaper.png")
    }
}
