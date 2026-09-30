package com.stratum.core.domain.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockRegistryTest {

    private fun block(id: String, gravity: Boolean = false) =
        BlockType(id = id, displayName = id, hasGravity = gravity)

    @Test
    fun `air always occupies index zero regardless of pack order`() {
        val registry = BlockRegistry.build(listOf(block("pack:stone"), BlockType.AIR))
        assertEquals(BlockRegistry.AIR_INDEX, registry.indexOf(BlockType.AIR_ID))
        assertTrue(registry.typeOf(0).isAir)
    }

    @Test
    fun `bedrock is always present so the world has a floor`() {
        val registry = BlockRegistry.build(listOf(block("pack:stone")))
        assertTrue(registry.contains(BlockType.BEDROCK.id))
        assertFalse(registry.typeOf(BlockType.BEDROCK.id).isBreakable)
    }

    @Test
    fun `a pack redefining air or bedrock does not create a duplicate`() {
        val registry = BlockRegistry.build(
            listOf(BlockType.AIR, BlockType.BEDROCK, block("pack:stone")),
        )
        assertEquals(3, registry.size)
    }

    @Test
    fun `duplicate ids are rejected at build time rather than silently shadowing`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            BlockRegistry.build(listOf(block("pack:stone"), block("pack:stone")))
        }
        assertTrue(failure.message!!.contains("Duplicate block id"))
    }

    @Test
    fun `unknown lookups name the id that was missing`() {
        val registry = BlockRegistry.build(listOf(block("pack:stone")))
        val failure = assertFailsWith<NoSuchBlockException> { registry.indexOf("pack:ghost") }
        assertTrue(failure.message!!.contains("pack:ghost"))
        assertEquals(null, registry.indexOrNull("pack:ghost"))
    }

    @Test
    fun `gravity indices are precomputed for the settle pass`() {
        val registry = BlockRegistry.build(
            listOf(block("pack:stone"), block("pack:sand", gravity = true)),
        )
        assertEquals(setOf(registry.indexOf("pack:sand")), registry.gravityIndices)
    }

    @Test
    fun `a block with no explicit drop yields itself`() {
        val plain = block("pack:stone")
        assertEquals("pack:stone", plain.drop)
        assertEquals("pack:gem", plain.copy(dropId = "pack:gem").drop)
    }
}
