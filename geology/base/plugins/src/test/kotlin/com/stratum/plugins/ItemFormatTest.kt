package com.stratum.plugins

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.ContentPackException
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.plugins.schema.PackJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ItemFormatTest {

    /** Affixes and inserts the way packs wrote them before modifiers, and weapons in the old slot names. */
    private val oldStyle = """
        {
          "id": "old", "name": "Old Pack",
          "damageTypes": [{ "id": "old:fire", "name": "Fire" }],
          "weapons": [{ "id": "old:club", "name": "Club", "damageType": "old:fire" }],
          "affixes": [
            { "id": "old:sharp", "name": "Sharp", "kind": "prefix", "stat": "attack_power", "min": 2, "max": 8 },
            { "id": "old:quick", "name": "Quick", "kind": "prefix", "stat": "attack_speed", "min": 0.1, "max": 0.2 },
            { "id": "old:of_fire", "name": "of Fire", "kind": "suffix", "stat": "resistance", "min": 0.1, "max": 0.3, "damageType": "old:fire", "minItemLevel": 4 }
          ],
          "inserts": [{ "id": "old:ember", "name": "Ember", "stat": "attack_power", "value": 3, "damageType": "old:fire", "convertsDamageType": true }]
        }
    """.trimIndent()

    @Test
    fun `packs written before gear slots and modifiers still load and mean the same`() {
        val pack = PackJson.decode(oldStyle)

        val sharp = pack.affixes.first { it.id == "old:sharp" }.tiers.single().modifiers.single()
        assertEquals(Stat.DAMAGE to ModifierKind.FLAT, sharp.stat to sharp.kind)
        assertEquals(2f to 8f, sharp.min to sharp.max)
        val quick = pack.affixes.first { it.id == "old:quick" }.tiers.single().modifiers.single()
        assertEquals(ModifierKind.INCREASED, quick.kind)
        val fire = pack.affixes.first { it.id == "old:of_fire" }
        assertEquals("old:fire", fire.tiers.single().modifiers.single().damageTypeId)
        assertEquals(4, fire.minItemLevel)

        val ember = pack.inserts.single()
        assertEquals(listOf(StatModifier(Stat.DAMAGE, ModifierKind.FLAT, 3f)), ember.modifiers)
        assertEquals("old:fire", ember.convertsToDamageTypeId)

        ContentPackAssembler().assemble(listOf(IgboContentPack.pack, pack))
    }

    private val newStyle = """
        {
          "id": "new", "name": "New Pack",
          "itemBases": [
            { "id": "new:cap", "name": "Cap", "slot": "helm", "tags": ["light"], "defences": [{ "stat": "armour", "value": 8 }] },
            { "id": "new:maul", "name": "Maul", "slot": "weapon", "damageType": "igbo:physical", "minDamage": 20, "maxDamage": 30, "twoHanded": true },
            { "id": "new:band", "name": "Band", "slot": "ring", "implicits": [{ "stat": "max_health", "kind": "flat", "min": 5, "max": 10 }] }
          ],
          "affixes": [{ "id": "new:hale", "name": "Hale", "kind": "prefix", "slots": ["helm", "ring"], "tags": ["light"], "group": "new:life",
            "tiers": [
              { "modifiers": [{ "stat": "max_health", "kind": "flat", "min": 5, "max": 10 }] },
              { "minItemLevel": 20, "name": "Mighty", "modifiers": [{ "stat": "max_health", "kind": "flat", "min": 20, "max": 30 }] }
            ] }],
          "uniques": [
            { "id": "new:crown", "name": "Crown of Blood", "base": "new:cap", "flags": ["skills_cost_health"], "flavour": "It drinks.",
              "modifiers": [{ "stat": "skill_damage", "kind": "more", "value": 0.3 }] },
            { "id": "new:left", "name": "Left Band", "base": "new:band", "set": "new:pair" },
            { "id": "new:right", "name": "Right Band", "base": "new:band", "set": "new:pair" }
          ],
          "itemSets": [{ "id": "new:pair", "name": "The Pair", "bonuses": [{ "pieces": 2, "modifiers": [{ "stat": "damage", "value": 0.2 }], "flags": ["cannot_crit"] }] }],
          "itemNames": [{ "id": "new:names", "first": ["Grim"], "second": ["Band"] }],
          "baseTiers": [{ "name": "Great {base}", "levelsAbove": 10 }]
        }
    """.trimIndent()

    @Test
    fun `gear, uniques, sets and names read from plugin JSON and survive the trip`() {
        val pack = PackJson.decode(newStyle)

        assertEquals(ItemSlot.HELM, pack.itemBases.first().slot)
        assertTrue(pack.itemBases.first { it.id == "new:maul" }.twoHanded)
        assertEquals("Mighty", pack.affixes.single().tiers[1].name)
        assertEquals(setOf(BuildFlag.SKILLS_COST_HEALTH), pack.uniques.first().flags)
        assertEquals(setOf(BuildFlag.CANNOT_CRIT), pack.itemSets.single().bonuses.single().flags)
        assertEquals(pack, PackJson.decode(PackJson.encode(pack)))

        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack, pack))
        assertEquals("Great Cap", content.itemBase("new:cap~2")?.name)
    }

    @Test
    fun `a unique on a base nobody defined fails at load, by name`() {
        val broken = PackJson.decode(newStyle.replace("\"base\": \"new:cap\"", "\"base\": \"new:hat\""))
        val failure = assertFailsWith<ContentPackException> { ContentPackAssembler().assemble(listOf(IgboContentPack.pack, broken)) }
        assertTrue("unique 'new:crown' is made on unknown base 'new:hat'" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a flag the engine does not have is refused by name`() {
        val failure = assertFailsWith<ImportException> { PackJson.decode(newStyle.replace("skills_cost_health", "fly")) }
        assertTrue("unique 'new:crown' flag" in failure.message.orEmpty(), failure.message)
    }
}
