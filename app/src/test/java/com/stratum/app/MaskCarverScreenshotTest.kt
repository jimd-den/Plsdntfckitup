package com.stratum.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.engine.model.mask.sculpt.Anatomy.Part
import com.stratum.engine.model.mask.sculpt.MaskCarver
import com.stratum.engine.model.mask.sculpt.MaskCulture
import com.stratum.feature.forge.CarverPage
import com.stratum.feature.forge.MaskCarverScreen
import com.stratum.feature.forge.MaskCarverViewModel
import com.stratum.feature.forge.MaskMakerStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mask carver: sculpted masks with every part open, and wearing one. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2400dp-normal-long-notround-any-420dpi-keyshidden-nonav", sdk = [36])
class MaskCarverScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    private class Memory : MaskMakerStorage {
        var codes = emptyList<String>()
        override fun kept() = codes
        override fun save(codes: List<String>) { this.codes = codes }
    }

    private fun shoot(name: String, setup: (MaskCarverViewModel) -> Unit = {}) {
        val content = GameSetup.assemble()
        val vm = MaskCarverViewModel(Memory(), onWear = {}, wornCode = null, seed = 42L)
        setup(vm)
        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                MaskCarverScreen(vm, onBack = {}, modifier = Modifier.fillMaxSize(), animate = false)
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/$name.png")
    }

    @Test fun mask_carver() = shoot("mask_carver") { vm -> vm.chooseTradition(MaskCulture.tradition("mgbedike")) }

    @Test fun mask_carver_eyes() = shoot("mask_carver_eyes") { vm ->
        vm.chooseTradition(MaskCulture.tradition("dan")); vm.setPage(CarverPage.Of(Part.EYES)); vm.toggleLock(Part.EYES)
    }

    @Test fun mask_carver_horns() = shoot("mask_carver_horns") { vm ->
        vm.chooseTradition(MaskCulture.tradition("ikenga")); vm.setPage(CarverPage.Of(Part.HORNS))
    }

    @Test fun mask_carver_surface() = shoot("mask_carver_surface") { vm ->
        vm.chooseTradition(MaskCulture.tradition("punu")); vm.setPage(CarverPage.Of(Part.SURFACE))
    }

    @Test
    fun wearing_keeping_and_sharing() {
        val memory = Memory()
        var worn: String? = null
        val vm = MaskCarverViewModel(memory, onWear = { worn = it }, wornCode = null, seed = 7L)
        vm.roll(); vm.keep(); vm.wear()
        assertEquals(vm.state.value.code, worn)
        assertTrue(MaskCarver.isCode(worn))
        assertEquals(1, memory.codes.size)
        val again = MaskCarverViewModel(memory, onWear = {}, wornCode = worn, seed = 9L)
        assertEquals(vm.state.value.code, again.state.value.code)
        assertEquals(1, again.state.value.kept.size)
        assertTrue(again.openCode(vm.state.value.code))
    }
}
