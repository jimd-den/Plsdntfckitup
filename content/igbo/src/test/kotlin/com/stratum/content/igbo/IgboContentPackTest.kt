package com.stratum.content.igbo

import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.PackOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IgboContentPackTest {

    private val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))

    @Test
    fun `the built-in pack assembles without dangling block references`() {
        // The assembler validates every biome reference, so reaching this line
        // at all is the assertion. The counts guard against a pack that silently
        // lost its content.
        assertTrue(content.registry.size > IgboPackBlocks.all.size)
        assertEquals(5, content.biomes.size)
        assertEquals(4, content.heroClasses.size)
    }

    @Test
    fun `the pack is playable`() {
        assertTrue(IgboContentPack.pack.isPlayable)
        assertEquals(PackOrigin.BUILT_IN, IgboContentPack.pack.origin)
    }

    @Test
    fun `every block id is namespaced so a second pack cannot collide by accident`() {
        IgboPackBlocks.all.forEach { block ->
            assertTrue(block.id.startsWith("igbo:"), "${block.id} is not namespaced")
        }
        IgboPackBiomes.all.forEach { biome ->
            assertTrue(biome.id.startsWith("igbo:"), "${biome.id} is not namespaced")
        }
    }

    @Test
    fun `every hero class starts with blocks that actually exist`() {
        content.heroClasses.forEach { hero ->
            hero.startingBlockIds.forEach { id ->
                assertTrue(content.registry.contains(id), "${hero.id} starts with unknown block $id")
            }
        }
    }

    @Test
    fun `lore points at real subjects`() {
        val knownSubjects = content.registry.all.map { it.id }.toSet() +
            content.biomes.map { it.id } +
            content.heroClasses.map { it.id }

        content.lore.forEach { entry ->
            val subject = entry.subjectId ?: return@forEach
            assertTrue(subject in knownSubjects, "lore '${entry.id}' points at unknown subject $subject")
        }
    }

    @Test
    fun `ore blocks drop refined goods rather than themselves`() {
        assertEquals("igbo:bronze_ingot", IgboPackBlocks.bronzeOre.drop)
        assertEquals("igbo:iron_ingot", IgboPackBlocks.ironOre.drop)
    }

    @Test
    fun `ritual seals cannot be mined away`() {
        assertTrue(!IgboPackBlocks.nsibidiSeal.isBreakable)
        assertTrue(!IgboPackBlocks.spiritWater.isBreakable)
    }

    @Test
    fun `light sources are the blocks that should glow`() {
        val emitters = IgboPackBlocks.all.filter { it.lightEmission > 0 }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "igbo:storm_crystal",
                "igbo:spirit_water",
                "igbo:bronze_brazier",
                "igbo:nsibidi_seal",
                "igbo:ofo_shrine",
            ),
            emitters,
        )
    }

    @Test
    fun `deposit bands stay inside the diggable part of the column`() {
        content.biomes.forEach { biome ->
            biome.deposits.forEach { rule ->
                assertTrue(rule.minZ >= 1, "${biome.id} deposits ${rule.blockId} into bedrock")
                assertTrue(rule.maxZ > rule.minZ, "${biome.id} has an inverted band for ${rule.blockId}")
            }
        }
    }
}
