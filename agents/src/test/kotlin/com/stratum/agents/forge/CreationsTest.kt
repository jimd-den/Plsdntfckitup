package com.stratum.agents.forge

import com.stratum.agents.CrewPresets
import com.stratum.agents.PackSections
import com.stratum.agents.StandardCrew
import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.ai.CrewPlan
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.LoreEntry
import com.stratum.core.domain.item.ItemBase
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.ModifierRange
import com.stratum.core.domain.item.UniqueDefinition
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.plugins.schema.PackJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val igbo = IgboContentPack.pack
private const val NS = Creations.ID

private val ring = UniqueDefinition(
    "$NS:ember", "Ember", "igbo:bronze_ring",
    listOf(ModifierRange(Stat.MAX_HEALTH, ModifierKind.FLAT, 10f, 25f)), flags = setOf(BuildFlag.SKILLS_COST_HEALTH), flavour = "Still warm.",
)
private val story = LoreEntry("$NS:ember_story", "The Ember", "It was kept.", subjectId = ring.id)
private val fragment = ContentPack(NS, Creations.NAME, "forge", uniques = listOf(ring), loreEntries = listOf(story))

class CreationsTest {

    @Test
    fun `kept things grow one plugin, and a second thing of the same name is a second thing`() {
        val first = Creations.append(null, fragment, listOf(igbo)).getOrThrow()
        assertEquals(listOf(ring.id, story.id), first.added)

        val second = Creations.append(first.pack, fragment, listOf(igbo)).getOrThrow()
        assertEquals(2, second.pack.uniques.size)
        val renamed = second.pack.uniques.last()
        assertEquals("$NS:ember_2", renamed.id)
        assertEquals(renamed.id, second.pack.loreEntries.last().subjectId, "the lore follows the rename")
        // It is a plugin like any other: it round-trips through the format.
        assertEquals(second.pack.uniques, PackJson.decode(PackJson.encode(second.pack)).uniques)
    }

    @Test
    fun `something that would not load is not kept`() {
        val orphan = fragment.copy(uniques = listOf(ring.copy(baseId = "igbo:nothing")))
        assertTrue(Creations.append(null, orphan, listOf(igbo)).isFailure)
    }

    @Test
    fun `removing a thing takes its lore with it`() {
        val kept = Creations.append(null, fragment, listOf(igbo)).getOrThrow().pack
        val gone = Creations.remove(kept, ring.id)
        assertTrue(gone.uniques.isEmpty() && gone.loreEntries.isEmpty())
    }

    @Test
    fun `cards read like the satchel's tooltips`() {
        val sword = ItemBase("$NS:kiln_blade", "Kiln Blade", ItemSlot.WEAPON, weapon = com.stratum.core.domain.item.WeaponProfile("igbo:physical", 10, 16, 1.25f))
        val cards = ForgeCards.of(fragment.copy(itemBases = listOf(sword)), listOf(igbo))
        val unique = cards.first { it.id == ring.id }
        assertTrue(unique.lines.any { it == "+(10–25) maximum health" }, "${unique.lines}")
        assertTrue(unique.lines.any { BuildFlag.SKILLS_COST_HEALTH.label in it })
        assertTrue("Bronze Ring" in unique.subtitle)
        assertEquals("Still warm.", unique.text)
        val blade = cards.first { it.id == sword.id }
        assertTrue(blade.lines.first().startsWith("10–16 damage"), blade.lines.first())
        val lore = cards.first { it.id == story.id }
        assertTrue("about Ember" in lore.subtitle, lore.subtitle)
    }

    @Test
    fun `a card's name and text can be rewritten, and nothing else`() {
        val renamed = ForgeCards.edit(fragment, ring.id, ForgeField.NAME, "Last Ember")
        val reworded = ForgeCards.edit(renamed, story.id, ForgeField.TEXT, "It was kept, and it kept.")
        assertEquals("Last Ember", reworded.uniques.single().name)
        assertEquals("It was kept, and it kept.", reworded.loreEntries.single().body)
        assertEquals(ring.modifiers, reworded.uniques.single().modifiers)
    }

    @Test
    fun `sharing folds the creations into the shared pack`() {
        val shared = Creations.combine(ContentPack("shared", "Shared", "me"), fragment)
        assertEquals(listOf(ring), shared.uniques)
    }

    @Test
    fun `the whole-world preset is the standard crew, and a pack's own crew comes first`() {
        assertIs<CrewPlan.Ordered>(CrewPlan.of(CrewPresets.world.roles))
        assertEquals(StandardCrew.all, CrewPresets.world.roles)
        assertEquals(listOf(CrewPresets.WORLD), CrewPresets.available(emptyList()).map { it.id })
        assertEquals(CrewPresets.PACK, CrewPresets.available(listOf(StandardCrew.arbiter)).first().id)
        assertTrue(ForgeKind.entries.all { kind -> ForgeRoles.role(kind.order("x")).sections.all { it in PackSections.known } })
    }
}
