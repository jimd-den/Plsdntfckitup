package com.stratum.core.domain.ai

import com.stratum.core.domain.character.CharacterRole
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.FrameDefect
import com.stratum.core.domain.sprite.SpriteFacing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosePlanningTest {

    @Test
    fun `a set id is the subject slugged under its role`() {
        assertEquals("hero:bronze_warrior", CharacterSetId.of("  Bronze Warrior! ", CharacterRole.HERO))
        assertTrue(CharacterSetId.of("Rat", CharacterRole.ENEMY)!!.startsWith(CharacterRole.ENEMY.namespace))
        assertNull(CharacterSetId.of("  !! ", CharacterRole.HERO))
    }

    @Test
    fun `packing is planned from what is on disk, away frames included`() {
        val drawn = setOf("idle_0", "idle_1", "walk_0", "walk_1", "walk_2", "idle_0_away", "idle_1_away")
        val plan = assertNotNull(PosePacking.planFor("hero:t", " ", drawn))
        assertEquals(3, plan.columns)
        assertEquals(4, plan.rows, "two rows a view, two views")
        assertEquals("Character", plan.sheet.name)
        assertEquals(2, plan.sheet.facingRows[SpriteFacing.NORTH_EAST])
        assertNull(PosePacking.planFor("hero:t", "T", emptySet()))
    }

    @Test
    fun `a finished run packs itself unless it would replace a sheet with a holey one`() {
        val clean = RunOutcome(drawn = 4, failed = emptyList())
        val holey = RunOutcome(drawn = 3, failed = listOf("walk_1"))
        val abandoned = RunOutcome(drawn = 1, failed = emptyList(), abandonedBecause = "key")
        val drawn = setOf("walk_0")
        assertTrue(PosePacking.shouldAutoPack(clean, drawn, hasSheet = true))
        assertTrue(PosePacking.shouldAutoPack(holey, drawn, hasSheet = false))
        assertFalse(PosePacking.shouldAutoPack(holey, drawn, hasSheet = true))
        assertFalse(PosePacking.shouldAutoPack(abandoned, drawn, hasSheet = false))
        assertFalse(PosePacking.shouldAutoPack(clean, emptySet(), hasSheet = false))
    }

    @Test
    fun `a run record survives the trip to disk and back`() {
        val record = PoseRunRecord(
            setId = "hero:t",
            subject = "T",
            scope = PoseScope.FULL,
            frames = mapOf(AnimationState.WALK to 12),
            drawsAwayView = true,
            cellSize = 256,
            takes = mapOf("walk_3" to 2),
            active = true,
        )
        val back = assertNotNull(PoseRunRecord.decode(PoseRunRecord.encode(record)))
        assertEquals(record, back)
        assertEquals(12, back.script.stepsFor(AnimationState.WALK, PoseView.AWAY).size)
        assertNull(PoseRunRecord.decode("{ not json"))
    }

    @Test
    fun `a record says which run was interrupted and remembers redraws`() {
        val idle = PoseRunRecord("hero:a", "A")
        val killed = PoseRunRecord("hero:b", "B", active = true)
        assertEquals(killed, PoseRunRecord.interrupted(listOf(idle, killed)))
        assertNull(PoseRunRecord.interrupted(listOf(idle)))

        val redrawn = idle.redrawn("walk_1").redrawn("walk_1")
        assertEquals(2, redrawn.takes["walk_1"])

        val finished = killed.finished(
            RunOutcome(3, listOf("walk_2"), reasons = mapOf("walk_2" to "429"), flagged = mapOf("walk_0" to listOf(FrameDefect.CLIPPED)), takes = mapOf("walk_0" to 1)),
        )
        assertFalse(finished.active)
        assertEquals(mapOf("walk_2" to "429"), finished.failures)
        assertEquals(1, finished.takes["walk_0"])
        assertTrue(finished.flagged.getValue("walk_0").contains("cut off"))
    }
}
