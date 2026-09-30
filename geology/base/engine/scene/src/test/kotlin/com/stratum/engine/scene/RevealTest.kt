package com.stratum.engine.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cut that keeps the hero in view: what it opens and what it leaves alone. */
class RevealTest {

    private val camera = SceneCamera(target = Vec3(10.5f, 10.5f, 12f))
    private val eye = camera.eye
    private val hero = Reveal(10.5f, 10.5f, 12f, Reveal.DEFAULT_RADIUS)

    private fun cut(x: Float, y: Float, z: Float) = ShadingModel.revealCut(hero, eye.x, eye.y, eye.z, x, y, z)

    /** A point [ahead] blocks from the body's centre towards the eye, [up] blocks higher. */
    private fun towardsEye(ahead: Float, up: Float = 0f): Vec3 {
        val centre = Vec3(hero.x, hero.y, hero.z + Reveal.BODY_CENTRE)
        return centre + (eye - centre).normalized() * ahead + Vec3(0f, 0f, up)
    }

    @Test
    fun `a roof or wall between the eye and the hero is cut away`() {
        val p = towardsEye(3f)
        assertEquals(1f, cut(p.x, p.y, p.z))
        val roof = Vec3(hero.x, hero.y, hero.z + 3f)
        assertTrue(cut(roof.x, roof.y, roof.z) > 0.9f, "the roof over the hero stays")
    }

    @Test
    fun `the floor, what stands behind the hero and what is off to the side all stay`() {
        assertEquals(0f, cut(hero.x + 1f, hero.y + 1f, hero.z), "the floor underfoot")
        val behind = Vec3(hero.x, hero.y, hero.z + 1f) - (eye - Vec3(hero.x, hero.y, hero.z + 1f)).normalized() * 2f
        assertEquals(0f, cut(behind.x, behind.y, behind.z), "the wall behind the hero")
        val aside = towardsEye(3f) + Vec3(-4f, 4f, 0f)
        assertEquals(0f, cut(aside.x, aside.y, aside.z), "scenery well to the side")
    }

    @Test
    fun `the rim is soft, not a hard hole`() {
        val p = towardsEye(3f) + Vec3(-1f, 1f, 0f).normalized() * (Reveal.DEFAULT_RADIUS - Reveal.FEATHER / 2f)
        val c = cut(p.x, p.y, p.z)
        assertTrue(c in 0.05f..0.95f, "rim cut $c")
    }
}
