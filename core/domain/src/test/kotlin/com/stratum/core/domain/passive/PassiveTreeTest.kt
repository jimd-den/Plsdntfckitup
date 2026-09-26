package com.stratum.core.domain.passive

import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassiveBuildTest {

    private val health = StatModifier(Stat.MAX_HEALTH, ModifierKind.FLAT, 10f)

    /**
     * ```
     * start - a - b - notable
     *         |
     *         c - d
     * other(start of another class) - d
     * ```
     */
    private val tree = PassiveTree(
        id = "t:tree",
        name = "Tree",
        nodes = listOf(
            PassiveNode("start", "Start", PassiveKind.START, classIds = listOf("t:knight")),
            PassiveNode("other", "Other start", PassiveKind.START, classIds = listOf("t:mage")),
            PassiveNode("a", "A", modifiers = listOf(health)),
            PassiveNode("b", "B", modifiers = listOf(health)),
            PassiveNode("c", "C", modifiers = listOf(health)),
            PassiveNode("d", "D", modifiers = listOf(health)),
            PassiveNode("notable", "Notable", PassiveKind.NOTABLE, modifiers = listOf(StatModifier(Stat.DAMAGE, ModifierKind.INCREASED, 0.3f))),
        ),
        links = listOf(
            PassiveLink("start", "a"), PassiveLink("a", "b"), PassiveLink("b", "notable"),
            PassiveLink("a", "c"), PassiveLink("c", "d"), PassiveLink("other", "d"),
        ),
    )

    private val knight = PassiveBuild.startingOn(tree, "t:knight")!!

    @Test
    fun `each class starts on its own start node`() {
        assertEquals("start", knight.startId)
        assertEquals("other", PassiveBuild.startingOn(tree, "t:mage")!!.startId)
        assertEquals("start", PassiveBuild.startingOn(tree, "t:nobody")!!.startId, "anyone else takes the first")
    }

    @Test
    fun `only nodes touching the allocation can be taken`() {
        assertTrue(knight.canAllocate("a"))
        assertFalse(knight.canAllocate("b"))
        assertNull(knight.allocating("notable"))
        assertFalse(knight.allocating("a")!!.allocating("c")!!.allocating("d")!!.canAllocate("other"), "another class's start is never taken")
    }

    @Test
    fun `a node holding others to the start cannot be refunded, a leaf can`() {
        val build = knight.allocatingPath(listOf("a", "b", "notable"))!!

        assertFalse(build.canRefund("a"))
        assertFalse(build.canRefund("b"))
        assertEquals(setOf("a", "b"), build.refunding("notable")!!.allocated)
        assertFalse(build.canRefund("start"), "the start was never bought")
    }

    @Test
    fun `tapping a far node finds the shortest path to it`() {
        assertEquals(listOf("a", "b", "notable"), knight.pathTo("notable"))
        assertEquals(listOf("c", "d"), knight.allocating("a")!!.pathTo("d"))
        assertEquals(emptyList(), knight.pathTo("start"))
        assertNull(knight.pathTo("other"))
        assertNull(knight.pathTo("nowhere"))
    }

    @Test
    fun `the allocation's modifiers include only what was taken`() {
        val build = knight.allocatingPath(listOf("a", "b", "notable"))!!

        assertEquals(3, build.pointsSpent)
        assertEquals(20f, build.sheet.flat(Stat.MAX_HEALTH))
        assertEquals(0.3f, build.sheet.increased(Stat.DAMAGE))
    }

    @Test
    fun `a build saved against an older tree drops what no longer fits`() {
        val saved = PassiveBuild(tree, "start", setOf("a", "b", "notable", "gone"))
        val shrunk = tree.copy(links = tree.links - PassiveLink("a", "b"))

        assertEquals(setOf("a"), PassiveBuild(shrunk, "start", saved.allocated).pruned().allocated)
    }

    @Test
    fun `a broken tree says what is wrong with it`() {
        val broken = tree.copy(nodes = tree.nodes.filter { it.kind != PassiveKind.START }, links = tree.links + PassiveLink("a", "ghost"))

        val problems = broken.problems()
        assertTrue(problems.any { "ghost" in it })
        assertTrue(problems.any { "no start" in it })
    }
}

class PassiveTreeGeneratorTest {

    private val classes = listOf(
        HeroClassDefinition(id = "t:knight", name = "Knight"),
        HeroClassDefinition(id = "t:mage", name = "Mage"),
        HeroClassDefinition(id = "t:rogue", name = "Rogue"),
    )

    private val tree = PassiveTreeGenerator.generate(classes)

    @Test
    fun `the generated tree is vast and valid`() {
        assertTrue(tree.nodes.size > 800, "was ${tree.nodes.size}")
        assertEquals(emptyList(), tree.problems())
    }

    @Test
    fun `every class has a start, and every node can be reached from each`() {
        classes.forEach { hero ->
            val build = assertNotNull(PassiveBuild.startingOn(tree, hero.id))
            assertEquals(listOf(hero.id), tree.node(build.startId)!!.classIds)
            val unreachable = tree.nodes.filter { it.kind != PassiveKind.START && build.pathTo(it.id) == null }
            assertEquals(emptyList(), unreachable.map { it.id }, "from ${hero.name}")
        }
    }

    @Test
    fun `each theme has one keystone that trades one more for another`() {
        val keystones = tree.nodes.filter { it.kind == PassiveKind.KEYSTONE }

        assertEquals(PassiveThemes.all.size, keystones.size)
        keystones.forEach { keystone ->
            // A cost is either a "less" or a "more" of something bad, such as skill cost.
            assertTrue(keystone.modifiers.count { it.kind == ModifierKind.MORE } >= 2, keystone.name)
        }
    }

    @Test
    fun `notables are worth more than the small nodes around them`() {
        val notables = tree.nodes.filter { it.kind == PassiveKind.NOTABLE }
        assertTrue(notables.size >= 40, "was ${notables.size}")
        assertTrue(notables.all { it.modifiers.size == 2 })
    }

    @Test
    fun `the same classes and settings grow the same tree`() {
        assertEquals(tree, PassiveTreeGenerator.generate(classes))
    }

    @Test
    fun `a far keystone is a long walk from the start`() {
        val build = PassiveBuild.startingOn(tree, "t:knight")!!
        val keystone = tree.nodes.first { it.kind == PassiveKind.KEYSTONE }

        assertTrue(build.pathTo(keystone.id)!!.size >= 14)
    }
}
