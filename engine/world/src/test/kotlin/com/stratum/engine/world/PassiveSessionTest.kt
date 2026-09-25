package com.stratum.engine.world

import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.passive.PassiveKind
import com.stratum.core.domain.passive.PassiveLink
import com.stratum.core.domain.passive.PassiveNode
import com.stratum.core.domain.passive.PassiveTree
import com.stratum.core.domain.passive.PassiveTreeGenerator
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PassiveSessionTest {

    /** start - life - quick - glass(keystone) */
    private val tree = PassiveTree(
        id = "test:tree",
        name = "Test tree",
        nodes = listOf(
            PassiveNode("start", "Start", PassiveKind.START),
            PassiveNode("life", "Life", modifiers = listOf(StatModifier(Stat.MAX_HEALTH, ModifierKind.FLAT, 50f))),
            PassiveNode(
                "quick", "Quick",
                modifiers = listOf(
                    StatModifier(Stat.COOLDOWN_RECOVERY, ModifierKind.INCREASED, 1f),
                    StatModifier(Stat.RESOURCE_COST, ModifierKind.INCREASED, -0.5f),
                    StatModifier(Stat.MOVE_SPEED, ModifierKind.INCREASED, 0.5f),
                ),
            ),
            PassiveNode("glass", "Glass", PassiveKind.KEYSTONE, modifiers = listOf(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 1f))),
        ),
        links = listOf(PassiveLink("start", "life"), PassiveLink("life", "quick"), PassiveLink("quick", "glass")),
    )

    private fun session(withTree: Boolean = true) = WorldSession(
        ContentPackAssembler().assemble(listOf(TestContent.pack.copy(passiveTrees = if (withTree) listOf(tree) else emptyList()))),
        WorldConfig(seed = 11L, simulationRadius = 1),
    )

    private fun levelled(level: Int) = session().apply { player = player.copy(level = level) }

    @Test
    fun `a fresh character has no points to spend`() {
        val session = session()

        assertEquals(0, session.player.unspentPassivePoints)
        assertEquals(PassiveResult.NotEnoughPoints(needed = 1, available = 0), session.allocatePassive("life"))
    }

    @Test
    fun `taking a node changes the stats every fight reads`() {
        val session = levelled(2)
        val before = session.playerStats.maxHealth

        assertIs<PassiveResult.Allocated>(session.allocatePassive("life"))
        assertEquals(before + 50, session.playerStats.maxHealth)
        assertEquals(1, session.player.unspentPassivePoints)
    }

    @Test
    fun `tapping a far keystone takes the whole path to it`() {
        val session = levelled(3)
        val before = session.playerStats.attackPower

        val taken = assertIs<PassiveResult.Allocated>(session.allocatePassive("glass"))

        assertEquals(listOf("life", "quick", "glass"), taken.nodes.map { it.id })
        assertEquals(before * 2, session.playerStats.attackPower)
        assertEquals(1, session.player.unspentPassivePoints, "two points a level: four, less three")
    }

    @Test
    fun `skills are cast as the build tunes them`() {
        val session = levelled(2)
        val plain = session.skills.first()
        session.allocatePassive("quick")

        val tuned = session.skills.first()
        assertEquals(plain.cooldownSeconds / 2f, tuned.cooldownSeconds)
        assertEquals(plain.resourceCost / 2, tuned.resourceCost)
        session.castSkill(tuned.id)
        assertEquals(tuned.cooldownSeconds, session.player.cooldowns.secondsLeft(tuned.id))
    }

    @Test
    fun `refunds are free, but only from the tips of the allocation`() {
        val session = levelled(2)
        session.allocatePassive("quick")

        assertEquals(PassiveResult.HoldsOthers, session.refundPassive("life"))
        assertIs<PassiveResult.Refunded>(session.refundPassive("quick"))
        assertIs<PassiveResult.Refunded>(session.refundPassive("life"))
        assertEquals(2, session.player.unspentPassivePoints)
    }

    @Test
    fun `refunding life takes the life with it`() {
        val session = levelled(2)
        session.allocatePassive("life")
        session.player = session.player.copy(health = session.player.maxHealthWithGear)

        session.refundPassive("life")

        assertEquals(session.player.maxHealthWithGear, session.player.health)
    }

    @Test
    fun `a pack with combat and no tree of its own gets a vast generated one`() {
        val tree = assertNotNull(session(withTree = false).passiveTree)

        assertEquals(PassiveTreeGenerator.TREE_ID, tree.id)
        assertTrue(tree.nodes.size > 800)
    }

    @Test
    fun `a saved allocation the tree no longer has is dropped on arrival`() {
        val progress = PassiveProgress(tree)
        val saved = session().player.copy(level = 5, passives = setOf("life", "gone", "glass"))

        val settled = progress.settle(saved)

        assertEquals(setOf("life"), settled.passives, "glass is cut off without quick")
        assertEquals(50f, settled.build.flat(Stat.MAX_HEALTH))
    }
}
