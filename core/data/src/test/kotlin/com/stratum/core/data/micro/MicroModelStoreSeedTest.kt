package com.stratum.core.data.micro

import com.stratum.core.domain.micro.MicroModel
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class MicroModelStoreSeedTest {

    private fun model(id: String) = MicroModel(id, id, 1, 1, 1, listOf("#FFFFFF"), intArrayOf(1))

    @Test
    fun `an empty library is seeded once, and deleted defaults stay deleted`() {
        val store = MicroModelStore(Files.createTempDirectory("models").toFile())
        assertEquals(2, store.seedOnce("defaults") { listOf(model("a"), model("b")) })
        assertEquals(2, store.all().size)
        store.delete("a"); store.delete("b")
        assertEquals(0, store.seedOnce("defaults") { listOf(model("a"), model("b")) })
        assertEquals(0, store.all().size)
    }

    @Test
    fun `a library that already has models is left alone`() {
        val store = MicroModelStore(Files.createTempDirectory("models").toFile())
        store.save(model("mine"))
        assertEquals(0, store.seedOnce("defaults") { listOf(model("a")) })
        assertEquals(listOf("mine"), store.all().map { it.id })
    }
}
