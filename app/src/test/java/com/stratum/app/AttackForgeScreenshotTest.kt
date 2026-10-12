package com.stratum.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.attack.AttackCode
import com.stratum.core.domain.attack.AttackForge
import com.stratum.core.domain.attack.AttackSketch
import com.stratum.core.domain.attack.ProceduralSkill
import com.stratum.feature.forge.AttackForgeScreen
import com.stratum.feature.forge.AttackForgeStorage
import com.stratum.feature.forge.AttackForgeViewModel
import com.stratum.feature.forge.AttackPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Forge of Will, and a sheet of random attacks to show they all look different. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2600dp-normal-long-notround-any-420dpi-keyshidden-nonav", sdk = [36])
class AttackForgeScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    private class Memory : AttackForgeStorage {
        val codes = ArrayList<String>(); val carried = ArrayList<String>()
        override fun kept() = codes.toList()
        override fun equipped() = carried.toList()
        override fun keep(code: String) { codes.remove(code); codes.add(0, code) }
        override fun forget(code: String) { codes.remove(code); carried.remove(code) }
        override fun toggleEquipped(code: String) { if (!carried.remove(code)) carried.add(code) }
    }

    private fun shoot(name: String, setup: (AttackForgeViewModel) -> Unit = {}) {
        val content = GameSetup.assemble()
        val vm = AttackForgeViewModel(Memory(), seed = 42L)
        setup(vm)
        composeTestRule.setContent {
            StratumTheme(palette = content.palette, darkTheme = true) {
                AttackForgeScreen(vm, onBack = {}, modifier = Modifier.fillMaxSize(), animate = false)
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/$name.png")
    }

    @Test fun attack_forge() = shoot("attack_forge") { vm -> vm.keep(); vm.equip(); vm.roll(); vm.keep() }

    @Test fun attack_forge_chained() = shoot("attack_forge_chained") { vm ->
        vm.core(com.stratum.core.domain.attack.DeliveryKind.SURFACE_WAVE)
        vm.shape(com.stratum.core.domain.attack.EmitterShape.FAN)
        vm.essence(com.stratum.core.domain.attack.Element.FROST)
        vm.toggleModulator(com.stratum.core.domain.attack.ModulatorKind.FORK)
        vm.toggleChain(com.stratum.core.domain.attack.SkillEvent.ON_HIT)
    }

    /** Thirty random attacks, two to a row,, each at the same moment of its loop: no two alike. */
    private fun sheet(name: String, attacks: List<ProceduralSkill>, t: Float) {
        composeTestRule.setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF0E0C12)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                attacks.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { a ->
                            Column(Modifier.weight(1f)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1.6f).background(Color(0xFF17131D))) {
                                    AttackPreview(a, Modifier.fillMaxSize(), animate = false, time = t)
                                }
                                Text(a.name, color = Color(0xFFE8E0D0), fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/$name.png")
    }

    @Test fun attack_sheet_flight() = sheet("attack_sheet_flight", List(30) { AttackForge.roll(it * 7919L + 5) }, AttackSketch.LOOP * 0.3f)

    @Test fun attack_sheet_impact() = sheet("attack_sheet_impact", List(30) { AttackForge.roll(it * 104729L + 3, CombatRole.entries[it % 5]) }, AttackSketch.LOOP * 0.68f)

    /** One attack's mechanics in thirty styles: what it does stays, how it looks never repeats. */
    @Test fun attack_sheet_styles() {
        val one = AttackForge.roll(77L)
        sheet("attack_sheet_styles", List(30) { AttackForge.build(one.rootSequence, it * 31L + 1) }, AttackSketch.LOOP * 0.35f)
    }

    @Test
    fun keeping_carrying_and_sharing() {
        val memory = Memory()
        val vm = AttackForgeViewModel(memory, seed = 3L)
        vm.equip()
        val code = vm.state.value.code
        assertEquals(listOf(code), memory.carried)
        assertTrue(vm.state.value.isKept && vm.state.value.isEquipped)
        val again = AttackForgeViewModel(memory, seed = 9L)
        assertTrue(again.openCode(code))
        assertEquals(code, AttackCode.encode(again.state.value.attack))
        assertTrue(!again.openCode("not a code"))
    }
}
