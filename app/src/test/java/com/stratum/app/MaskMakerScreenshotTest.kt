package com.stratum.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.stratum.app.hub.CreateHubActions
import com.stratum.app.hub.CreateHubScreen
import com.stratum.app.hub.StudioStatus
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.engine.model.mask.MaskMaker
import com.stratum.feature.forge.MakerPage
import com.stratum.feature.forge.MaskMakerScreen
import com.stratum.feature.forge.MaskMakerStorage
import com.stratum.feature.forge.MaskMakerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mask maker: where a player makes their own Igbo mask, and the way in from the studio. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2300dp-normal-long-notround-any-420dpi-keyshidden-nonav", sdk = [36])
class MaskMakerScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    private class Memory : MaskMakerStorage {
        var codes = emptyList<String>()
        override fun kept() = codes
        override fun save(codes: List<String>) { this.codes = codes }
    }

    private fun shoot(name: String, setup: (MaskMakerViewModel) -> Unit = {}) {
        val content = GameSetup.assemble()
        val vm = MaskMakerViewModel(Memory(), onWear = {}, wornCode = null, seed = 42L)
        setup(vm)
        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                MaskMakerScreen(vm, onBack = {}, modifier = Modifier.fillMaxSize(), animate = false)
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/$name.png")
    }

    @Test fun mask_maker() = shoot("mask_maker")

    @Test fun mask_maker_colours() = shoot("mask_maker_colours") { vm ->
        vm.roll(); vm.setFeeling("Angry"); vm.setPage(MakerPage.COLOURS); vm.toggleLock(MaskMaker.Trait.EYES)
    }

    @Test fun mask_maker_crest() = shoot("mask_maker_crest") { vm ->
        vm.startFrom(com.stratum.engine.model.mask.EmojiMask.presets[3]); vm.setFeeling("Laugh"); vm.setPage(MakerPage.CREST)
    }

    @Test
    fun wearing_and_keeping() {
        val memory = Memory()
        var worn: String? = null
        val vm = MaskMakerViewModel(memory, onWear = { worn = it }, wornCode = null, seed = 7L)
        vm.roll(); vm.keep(); vm.wear()
        assertEquals(vm.state.value.code, worn)
        assertEquals(1, memory.codes.size)
        assertTrue(vm.state.value.worn)
        val again = MaskMakerViewModel(memory, onWear = {}, wornCode = worn, seed = 9L)
        assertEquals(vm.state.value.look, again.state.value.look)
        assertEquals(1, again.state.value.kept.size)
    }

    @Test
    fun create_hub_has_the_mask_maker() {
        val content = GameSetup.assemble()
        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                CreateHubScreen(status = StudioStatus(classCount = 6, wearsMakerMask = true), actions = CreateHubActions())
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/create_hub_masks.png")
    }
}
