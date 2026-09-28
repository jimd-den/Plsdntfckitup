package com.stratum.core.domain.sandbox

import com.stratum.core.domain.combat.DamageDealt
import com.stratum.core.domain.combat.DamageSource
import com.stratum.core.domain.combat.DamageSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DamageMeterTest {

    private val fireball = DamageSource(DamageSourceKind.SKILL, "t:fireball", "Fireball")
    private val nova = DamageSource(DamageSourceKind.TRIGGER, "t:nova", "Nova")
    private val burn = DamageSource(DamageSourceKind.AILMENT, "t:burn", "Burning")

    private fun hit(source: DamageSource, amount: Float, type: String = "t:fire", critical: Boolean = false) =
        DamageDealt(source, "dummy", amount, mapOf(type to amount), critical = critical)

    @Test
    fun `damage per second is measured over the window, and falls away after it`() {
        val meter = DamageMeter(windowSeconds = 4f)
        repeat(4) {
            meter.record(hit(fireball, 100f))
            meter.advance(1f)
        }
        assertEquals(100f, meter.report().windowDps, 0.01f)
        meter.advance(10f)
        val quiet = meter.report()
        assertEquals(0f, quiet.windowDps)
        assertEquals(400f, quiet.total)
    }

    @Test
    fun `the first hit reads as its own damage, not as infinity`() {
        val meter = DamageMeter()
        meter.record(hit(fireball, 250f))
        assertEquals(250f, meter.report().windowDps)
    }

    @Test
    fun `sources, types, crits and the biggest hit are all counted`() {
        val meter = DamageMeter()
        meter.record(hit(fireball, 100f, critical = true))
        meter.record(hit(fireball, 50f))
        meter.record(hit(nova, 300f, type = "t:cold"))
        meter.record(DamageDealt(burn, "dummy", 50f, mapOf("t:fire" to 50f), overTime = true))
        meter.record(DamageDealt(fireball, "dummy", 0f, evaded = true))
        val report = meter.report()

        assertEquals(500f, report.total)
        assertEquals(3, report.hits, "damage over time and evasions are not hits")
        assertEquals(1f / 3f, report.critRate, 0.001f)
        assertEquals(1, report.evaded)
        assertEquals(300f, report.biggestHit)
        assertEquals(nova, report.biggestSource)
        assertEquals(listOf(nova, fireball, burn), report.bySource.map { it.source })
        assertEquals(0.6f, report.bySource.first().share, 0.001f)
        assertEquals(listOf("t:cold", "t:fire"), report.byType.map { it.damageTypeId })
        assertEquals(0.4f, report.byType.last().share, 0.001f)
    }

    @Test
    fun `a reset forgets the fight and starts the clock again`() {
        val meter = DamageMeter()
        meter.record(hit(fireball, 100f))
        meter.advance(3f)
        meter.reset()
        assertTrue(meter.report().isEmpty)
        assertEquals(0f, meter.clock)
    }
}
