package com.stratum.engine.crowd

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LongHeapTest {
    @Test
    fun `pops in ascending order however it was filled, and grows past its first size`() {
        val random = Random(7)
        val values = List(500) { random.nextLong(0, 1_000_000) }
        val heap = LongHeap(4)
        values.forEach(heap::push)
        val popped = List(values.size) { heap.pop() }
        assertEquals(values.sorted(), popped)
        assertFalse(heap.isNotEmpty)
    }
}
