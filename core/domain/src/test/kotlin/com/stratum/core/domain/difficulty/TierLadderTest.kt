package com.stratum.core.domain.difficulty

import com.stratum.core.domain.actor.EnemyRank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierLadderTest {

    @Test
    fun `the ladder is named well past sixteen rungs and then runs on without end`() {
        assertTrue(TierLadder.named.size > 16)
        assertEquals("Ụzọ", TierLadder.rung(0).name)
        assertEquals(TierLadder.named.map { it.name }.toSet().size, TierLadder.named.size, "two rungs share a name")
        val first = TierLadder.rung(TierLadder.endless)
        assertEquals("Ebighi ebi I", first.name)
        assertEquals("Ebighi ebi XIV", TierLadder.rung(TierLadder.endless + 13).name)
        assertTrue((0..200).map { TierLadder.rung(it).name }.toSet().size == 201, "a rung's name repeats")
    }

    @Test
    fun `each rung presses harder than the last, more steeply the higher it goes`() {
        val p = (0..60).map(TierLadder::pressure)
        assertEquals(1f, p[0])
        assertTrue(p.zipWithNext().all { (a, b) -> b > a })
        assertTrue(p[31] - p[30] > p[1] - p[0], "the curve does not steepen")
    }

    @Test
    fun `monster levels follow the player, the tier and the rank`() {
        assertEquals(10, MonsterLevel.of(10, 0, EnemyRank.MINION))
        assertEquals(10 + 15 + 3, MonsterLevel.of(10, 15, EnemyRank.BOSS))
        assertEquals(0, MonsterLevel.gap(MonsterLevel.of(10, 15, EnemyRank.BOSS), EnemyRank.BOSS, 10, 15))
        // The player has grown four levels since this elite was made.
        assertEquals(-4, MonsterLevel.gap(MonsterLevel.of(10, 0, EnemyRank.ELITE), EnemyRank.ELITE, 14, 0))
        assertEquals(0, MonsterLevel.gap(0, EnemyRank.MINION, 30, 0))
    }
}
