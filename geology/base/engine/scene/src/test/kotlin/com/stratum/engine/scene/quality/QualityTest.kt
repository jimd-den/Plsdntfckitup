package com.stratum.engine.scene.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QualityTest {

    private fun device(memoryMb: Int = 6000, cores: Int = 8, maxTexture: Int = 8192, halfFloat: Boolean = true, lowRam: Boolean = false, gpu: String = "Adreno (TM) 730") =
        DeviceProfile(memoryMb, cores, maxTexture, halfFloat, lowRam, gpu)

    @Test
    fun `each tier spends at least as much as the one below it`() {
        val tiers = QualityTier.entries.map(RenderSettings::of)
        tiers.zipWithNext().forEach { (lower, higher) ->
            assertTrue(higher.renderScale >= lower.renderScale)
            assertTrue(higher.shadowMapSize >= lower.shadowMapSize)
            assertTrue(higher.maxPointLights >= lower.maxPointLights)
            assertTrue(higher.viewRadius >= lower.viewRadius)
            assertTrue(higher.textureBudgetBytes >= lower.textureBudgetBytes)
        }
    }

    @Test
    fun `high is what the game drew before there were tiers`() {
        val high = RenderSettings.of(QualityTier.HIGH)
        assertEquals(1f, high.renderScale)
        assertEquals(2048, high.shadowMapSize)
        assertEquals(9, high.shadowTaps)
        assertEquals(56, high.viewRadius)
        assertEquals(4, high.streamingRadius)
    }

    @Test
    fun `a cheap phone gets the low tier, a flagship ultra, most phones in between`() {
        assertEquals(QualityTier.LOW, DeviceClassifier.tierFor(device(memoryMb = 2048, cores = 4)))
        assertEquals(QualityTier.LOW, DeviceClassifier.tierFor(device(lowRam = true)))
        assertEquals(QualityTier.LOW, DeviceClassifier.tierFor(device(gpu = "Mali-G52 MC2")), "plenty of memory, weak GPU")
        assertEquals(QualityTier.MEDIUM, DeviceClassifier.tierFor(device(memoryMb = 4096)))
        assertEquals(QualityTier.MEDIUM, DeviceClassifier.tierFor(device(halfFloat = false)))
        assertEquals(QualityTier.MEDIUM, DeviceClassifier.tierFor(device(gpu = "Adreno (TM) 610")))
        assertEquals(QualityTier.HIGH, DeviceClassifier.tierFor(device()))
        assertEquals(QualityTier.ULTRA, DeviceClassifier.tierFor(device(memoryMb = 12288)))
    }

    @Test
    fun `settings are fitted to what the device can actually do`() {
        val fitted = RenderSettings.of(QualityTier.ULTRA).fittedTo(device(maxTexture = 2048, halfFloat = false))

        assertEquals(2048, fitted.shadowMapSize)
        assertFalse(fitted.highRange)
    }

    @Test
    fun `a player's choice overrides the classifier`() {
        assertEquals(QualityTier.LOW, DeviceClassifier.settingsFor(device(memoryMb = 12288), chosen = QualityTier.LOW).tier)
    }

    @Test
    fun `slow frames lower the resolution, a single hitch does not`() {
        val settings = RenderSettings.of(QualityTier.HIGH)
        val governor = FrameGovernor(settings, window = 10)

        repeat(9) { governor.record(16f) }
        assertFalse(governor.record(200f), "one long frame in a window of quick ones is a hitch")
        assertEquals(1f, governor.renderScale)

        var changed = false
        repeat(10) { changed = governor.record(30f) || changed }
        assertTrue(changed)
        assertEquals(0.9f, governor.renderScale, 1e-4f)
    }

    @Test
    fun `resolution never drops below the tier's floor and comes back slowly with headroom`() {
        val settings = RenderSettings.of(QualityTier.HIGH)
        val governor = FrameGovernor(settings, window = 5)

        repeat(100) { governor.record(40f) }
        assertEquals(settings.minRenderScale, governor.renderScale, 1e-4f)

        repeat(5) { governor.record(8f) }
        assertEquals(settings.minRenderScale, governor.renderScale, 1e-4f, "one calm window is not enough")
        repeat(5) { governor.record(8f) }
        assertEquals(settings.minRenderScale + FrameGovernor.STEP_UP, governor.renderScale, 1e-4f)
    }

    @Test
    fun `a pause is not a slow frame`() {
        val governor = FrameGovernor(RenderSettings.of(QualityTier.HIGH), window = 2)
        governor.record(5000f)
        governor.record(5000f)
        assertEquals(1f, governor.renderScale)
    }
}
