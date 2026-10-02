package com.stratum.engine.model.mask

import com.stratum.core.domain.actor.EnemyRank
import com.stratum.engine.model.mask.sculpt.MaskCarver
import com.stratum.engine.model.mask.sculpt.MaskCulture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SculptedMonstersTest {

    @Test
    fun `a kind of monster always wears the same carved mask, and kinds differ`() {
        assertEquals(CharacterMasks.sculptedFor("forest_brute"), CharacterMasks.sculptedFor("forest_brute"))
        val masks = (1..40).map { CharacterMasks.sculptedFor("monster_$it")!! }
        assertTrue(masks.toSet().size == masks.size, "every kind its own mask")
        assertTrue(masks.map { it.tradition }.toSet().size >= 8, "drawn from many traditions")
    }

    @Test
    fun `a telling name chooses the tradition`() {
        assertTrue(CharacterMasks.sculptedFor("river_ghost_maiden")!!.tradition in setOf("agbogho_mmuo", "punu"))
        assertEquals("ogbodo_enyi", CharacterMasks.sculptedFor("stone_golem")!!.tradition)
    }

    @Test
    fun `rank makes the same face grander`() {
        val minion = CharacterMasks.sculptedFor("cave_thing", EnemyRank.MINION)!!
        val boss = CharacterMasks.sculptedFor("cave_thing", EnemyRank.BOSS)!!
        assertTrue(boss.crownSize >= minion.crownSize && boss.crownSize >= 0.9f)
        assertNotEquals(minion, boss)
    }

    @Test
    fun `an authored genome wins, and a carver code is worn as it is`() {
        assertNull(CharacterMasks.sculptedFor("x", override = MaskGenome.presets.first().name))
        val code = MaskCarver.encode(MaskCulture.generate(MaskCulture.tradition("dan"), 5L))
        assertEquals("dan", CharacterMasks.sculptedFor("x", override = code)!!.tradition)
    }

    @Test
    fun `monsters fly carved masks`() {
        val chars = MaskCharacters()
        chars.begin()
        assertNotNull(chars.monster("m1", "night_raider", EnemyRank.ELITE, 0f, 0f, 0f, 0f, 1f))
        chars.advance(1f / 30f)
        val mesh = chars.spirits.single().mesh
        assertTrue(mesh.triangleCount > 500 && mesh.channels.any { it == com.stratum.engine.scene.GlowChannel.EYES })
    }
}
