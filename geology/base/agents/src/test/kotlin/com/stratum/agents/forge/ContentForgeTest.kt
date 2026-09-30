package com.stratum.agents.forge

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.ai.CompletionRequest
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.LoreCategory
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.PowerBudget
import com.stratum.core.domain.item.PowerTier
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val igbo = IgboContentPack.pack

/** Replies in turn, the last one forever, and remembers every request. */
private class FakeModel(vararg replies: String) : LanguageModelPort {
    private val queue = ArrayDeque(replies.toList())
    val asked = mutableListOf<CompletionRequest>()

    override suspend fun complete(request: CompletionRequest, observer: GenerationObserver): Result<String> {
        asked += request
        return Result.success(if (queue.size > 1) queue.removeFirst() else queue.first())
    }
}

private fun forge(model: LanguageModelPort) = ContentForge(model, listOf(igbo), clock = { 0L })

/** A reply the way models actually send one: prose, a fence, and JSON that is nearly right. */
private val messyUnique = """
    Here is your unique item!
    ```json
    {
      "uniques": [{
        "id": "ember_ring",
        "name": "Ember of the Last Kiln",
        "base": "Bronze Ring",
        "modifiers": [
          { "stat": "life", "value": 40 },
          { "stat": "damage", "kind": "more", "value": "300%" },
          { "stat": "fire_resistance", "kind": "flat", "value": 0.2 },
          { "stat": "luck", "kind": "flat", "value": 7 },
          { "stat": "movement speed", "kind": "reduced", "min": 10, "max": 5 },
          { "stat": "crit_chance", "value": 1 }
        ],
        "flags": ["hits_ignore_resistance", "god_mode", "Life steal is uncapped"]
      }],
      "lore": [{ "title": "The Last Kiln", "body": "When the kilns of Awka went cold, one ember was kept. It still remembers the smith.", "subject": "ember_ring", "category": "relic" }],
      "blocks": []
    }
    ```
    Let me know if you want changes!
""".trimIndent()

class ContentForgeTest {

    @Test
    fun `a messy unique is repaired into one that loads, broken on purpose`() = runTest {
        val model = FakeModel(messyUnique)
        val outcome = assertIs<ForgeOutcome.Forged>(forge(model).forge(ForgeOrder.Unique("a ring from a dead kiln"), ForgeContext(budget = PowerTier.BROKEN)))

        val unique = outcome.fragment.uniques.single()
        assertEquals("user.creations:ember_ring", unique.id)
        assertEquals("igbo:bronze_ring", unique.baseId, "the base was found by its name")
        assertEquals(setOf(BuildFlag.HITS_IGNORE_RESISTANCE, BuildFlag.LIFE_STEAL_UNCAPPED), unique.flags, "the invented flag is gone, the label read as its flag")
        val life = unique.modifiers.first { it.stat == Stat.MAX_HEALTH }
        assertEquals(ModifierKind.FLAT, life.kind, "+40 life with no kind is flat health")
        assertEquals(3f, unique.modifiers.first { it.stat == Stat.DAMAGE }.max, 1e-4f, "300% is a share of 3")
        assertNull(unique.modifiers.firstOrNull { it.stat == Stat.RESISTANCE }?.damageTypeId, "no damage type is called fire, so it covers every type")
        val slow = unique.modifiers.first { it.stat == Stat.MOVE_SPEED }
        assertTrue(slow.min < 0f && slow.max < 0f && slow.min <= slow.max, "'reduced' made it a penalty, the right way round: $slow")
        assertEquals(1f, unique.modifiers.first { it.stat == Stat.CRIT_CHANCE }.max, "a crit chance of 1 is certainty, not 1%")
        assertEquals(5, unique.modifiers.size, "luck is not a stat")

        val lore = outcome.fragment.loreEntries.single()
        assertEquals(unique.id, lore.subjectId, "the lore is about the unique")
        assertEquals(LoreCategory.ARTIFACT, lore.category)
        assertEquals("When the kilns of Awka went cold, one ember was kept.", unique.flavour, "the flavour quotes its lore")

        assertEquals(PowerTier.BROKEN, outcome.appraisals.single { it.id == unique.id }.estimate.tier)
        assertTrue(outcome.repairs.any { "god_mode" in it } && outcome.repairs.any { "luck" in it } && outcome.repairs.any { "blocks" in it }, "${outcome.repairs}")
        // What the game will load: the fragment assembles on the pack it was made for.
        ContentPackAssembler().assemble(listOf(igbo, outcome.fragment))
    }

    @Test
    fun `a balanced request scales the same unique into the budget`() = runTest {
        val outcome = assertIs<ForgeOutcome.Forged>(forge(FakeModel(messyUnique)).forge(ForgeOrder.Unique("a ring"), ForgeContext(budget = PowerTier.BALANCED)))
        val unique = outcome.fragment.uniques.single()
        assertTrue(PowerBudget.unique(unique).ratio <= PowerBudget.BALANCED_LIMIT, "${PowerBudget.unique(unique)}")
        assertTrue(unique.flags.size <= 1, "the strongest rules went: ${unique.flags}")
        assertTrue(outcome.repairs.any { "balanced request" in it })
    }

    @Test
    fun `the prompt names exactly what exists, so the model can only name real things`() = runTest {
        val model = FakeModel(messyUnique)
        forge(model).forge(ForgeOrder.Unique("a ring", slot = ItemSlot.RING), ForgeContext(theme = "kilns and ash", levels = 5..15))
        val prompt = model.asked.first().userPrompt
        listOf("max_health", "increased", "hits_ignore_resistance", "igbo:thunder", "igbo:bronze_ring", "bases (ring)", "5 to 15", "kilns and ash", "Amadioha")
            .forEach { assertTrue(it in prompt, "'$it' is missing from the prompt:\n$prompt") }
        assertTrue("bases (offhand)" !in prompt, "only ring bases are offered for a ring")
    }

    @Test
    fun `what cannot be repaired is retried with the reason, then rejected in words`() = runTest {
        val model = FakeModel("I'm sorry, I can't make items.", """{ "uniques": [{ "name": "Nothing", "base": "igbo:bronze_ring" }] }""")
        val outcome = assertIs<ForgeOutcome.Rejected>(forge(model).forge(ForgeOrder.Unique("a ring")))
        assertEquals(3, model.asked.size, "every try was used")
        assertTrue("REJECTED" in model.asked.last().userPrompt && "lore entry" in model.asked.last().userPrompt)
        assertTrue("lore entry" in outcome.reason, outcome.reason)
        assertTrue(outcome.journal.toMarkdown().contains("Attempt 3"))
    }

    @Test
    fun `a bad first reply and a good second one make a result`() = runTest {
        val model = FakeModel("```\nnot json\n```", messyUnique)
        val outcome = assertIs<ForgeOutcome.Forged>(forge(model).forge(ForgeOrder.Unique("a ring"), ForgeContext(budget = PowerTier.BROKEN)))
        assertEquals(2, outcome.journal.steps.single().attempts.size)
    }

    @Test
    fun `lore is tied to what exists, or to what was asked about`() = runTest {
        val reply = """
            [
              { "title": "Thunder's Debt", "body": "Amadioha lends and collects.", "subject": "amadioha", "category": "god" },
              { "title": "A Nameless Road", "body": "Nobody walks it twice.", "subject": "nowhere_at_all" },
              { "title": "No body" }
            ]
        """.trimIndent()
        val subject = igbo.biomes.first().id
        val outcome = assertIs<ForgeOutcome.Forged>(forge(FakeModel(reply)).forge(ForgeOrder.Lore("old debts", LoreCategory.DEITY, listOf(subject), count = 3)))
        val entries = outcome.fragment.loreEntries
        assertEquals(2, entries.size, "the entry with no body is dropped")
        assertTrue(entries.all { it.category == LoreCategory.DEITY }, "filed where it was asked")
        assertTrue(entries.all { it.subjectId != null && it.id.startsWith("user.creations:") })
        assertEquals(subject, entries[1].subjectId, "a subject that does not exist gives way to the one asked about")
    }

    @Test
    fun `a weapon with out-of-range numbers is clamped, typed and balanced`() = runTest {
        val reply = """{ "itemBases": [{ "name": "Kiln Maul", "slot": "sword", "damageType": "thunder", "minDamage": 900, "maxDamage": 40,
            "attackSpeed": 99, "attackRange": -3, "minItemLevel": 400, "tags": ["Heavy Blunt"] }] }"""
        val outcome = assertIs<ForgeOutcome.Forged>(forge(FakeModel(reply)).forge(ForgeOrder.Base("a maul"), ForgeContext(levels = 1..30)))
        val maul = outcome.fragment.itemBases.single()
        val weapon = maul.weapon!!
        assertEquals("igbo:thunder", weapon.damageTypeId)
        assertTrue(weapon.minDamage <= weapon.maxDamage && weapon.attackSpeed <= 10f && weapon.attackRange >= 1)
        assertEquals(30, maul.minItemLevel)
        assertEquals(setOf("heavy_blunt"), maul.tags)
        assertEquals(PowerTier.BALANCED, PowerBudget.base(maul).tier)
    }

    @Test
    fun `a ladder is one family climbing the levels`() = runTest {
        val rung = { n: String -> """{ "name": "$n Helm", "slot": "helm", "defences": [{ "stat": "armor", "value": 18 }] }""" }
        val reply = """{ "bases": [${rung("Clay")}, ${rung("Kiln")}, ${rung("Glazed")}] }"""
        val outcome = assertIs<ForgeOutcome.Forged>(forge(FakeModel(reply)).forge(ForgeOrder.Base("helms", ItemSlot.HELM, ladder = true), ForgeContext(levels = 1..21)))
        val bases = outcome.fragment.itemBases
        assertEquals(listOf(1, 11, 21), bases.map { it.minItemLevel })
        assertTrue(bases.all { it.slot == ItemSlot.HELM && !it.grows })
    }

    @Test
    fun `affixes are tiered, slot-scoped and read from percentages`() = runTest {
        val reply = """{ "affixes": [
            { "name": "Glowing", "kind": "Prefix", "tags": ["weird_tag"], "slots": ["weapon"],
              "tiers": [ { "minItemLevel": 12, "modifiers": [{ "stat": "spell damage", "kind": "increased", "value": "20-30%" }] },
                         { "modifiers": [{ "stat": "spell damage", "kind": "increased", "min": 5, "max": 10 }] } ] },
            { "name": "of Ash", "kind": "sfx", "stat": "max_health", "modifierKind": "flat", "min": 10, "max": 20 }
        ] }"""
        val outcome = assertIs<ForgeOutcome.Forged>(forge(FakeModel(reply)).forge(ForgeOrder.Affixes("kiln magic", setOf(ItemSlot.RING, ItemSlot.AMULET))))
        val glowing = outcome.fragment.affixes.first()
        assertEquals(listOf(1, 12), glowing.tiers.map { it.minItemLevel }, "weakest first")
        assertEquals(0.3f, glowing.tiers[1].modifiers.single().max, 1e-4f)
        assertEquals(0.1f, glowing.tiers[0].modifiers.single().max, 1e-4f, "10 increased is read as 10%")
        assertEquals(setOf(ItemSlot.RING, ItemSlot.AMULET), glowing.slots, "kept to the slots asked for")
        assertTrue(glowing.tags.isEmpty(), "a tag no base carries would never roll")
        assertEquals(2, outcome.fragment.affixes.size, "the one-line affix is kept, as a prefix since 'sfx' is not a kind")
    }

    @Test
    fun `a set gathers its pieces and keeps its bonuses to what it has`() = runTest {
        val reply = """{
          "itemSets": [{ "id": "kiln_set", "name": "The Kiln Kept", "bonuses": [
              { "pieces": 2, "modifiers": [{ "stat": "armour", "kind": "increased", "value": 0.2 }] },
              { "pieces": 5, "modifiers": [], "flags": ["resource_shields_health"] } ] }],
          "uniques": [
              { "name": "Kiln Cap", "base": "igbo:raffia_okpu", "modifiers": [{ "stat": "max_health", "kind": "flat", "value": 20 }] },
              { "name": "Kiln Wrap", "base": "akwete_wrap", "set": "kiln_set", "modifiers": [{ "stat": "armour", "kind": "flat", "value": 10 }] }
          ],
          "lore": [{ "title": "Kept", "body": "Two things survived the kiln. They are never apart." }]
        }"""
        val outcome = assertIs<ForgeOutcome.Forged>(forge(FakeModel(reply)).forge(ForgeOrder.ItemSet("kiln regalia", 2), ForgeContext(budget = PowerTier.BROKEN)))
        val set = outcome.fragment.itemSets.single()
        assertEquals(listOf(2), set.bonuses.map { it.pieces }, "a five-piece bonus on two pieces needs two, and merges with the other")
        assertTrue(outcome.fragment.uniques.all { it.setId == set.id })
        assertEquals(set.id, outcome.fragment.loreEntries.single().subjectId)
    }

    @Test
    fun `the built-in pack's own gear reads as balanced or strong, never broken`() {
        igbo.uniques.forEach { assertTrue(PowerBudget.unique(it).tier != PowerTier.BROKEN, "${it.id}: ${PowerBudget.unique(it)}") }
        igbo.affixes.forEach { assertTrue(PowerBudget.affix(it).tier != PowerTier.BROKEN, "${it.id}: ${PowerBudget.affix(it)}") }
        (igbo.itemBases + igbo.weapons.map { it.toItemBase() }).forEach { assertTrue(PowerBudget.base(it).tier != PowerTier.BROKEN, "${it.id}: ${PowerBudget.base(it)}") }
        igbo.itemSets.forEach { assertTrue(PowerBudget.set(it, 10).tier != PowerTier.BROKEN, "${it.id}: ${PowerBudget.set(it, 10)}") }
    }
}
