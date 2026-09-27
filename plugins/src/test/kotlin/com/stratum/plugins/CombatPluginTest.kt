package com.stratum.plugins

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.actor.EffectTarget
import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.combat.Attribute
import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.combat.Condition
import com.stratum.core.domain.combat.Keystone
import com.stratum.core.domain.combat.TriggerEvent
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.status.StackingRule
import com.stratum.core.domain.status.StatusBehaviour
import com.stratum.core.domain.world.WorldRules
import com.stratum.plugins.schema.PackJson
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CombatPluginTest {

    private val legacy = """
        {
          "id": "old", "name": "Old Pack",
          "damageTypes": [{ "id": "old:fire", "name": "Fire" }],
          "skills": [
            { "id": "old:jab", "name": "Jab", "damageType": "old:fire", "shape": "strike" },
            { "id": "old:ring", "name": "Ring", "damageType": "old:fire", "shape": "nova", "range": 4 },
            { "id": "old:spear", "name": "Spear", "damageType": "old:fire", "shape": "lance" },
            { "id": "old:plain", "name": "Plain", "damageType": "old:fire" }
          ]
        }
    """.trimIndent()

    @Test
    fun `packs written with the old skill shapes still load as the same skills`() {
        val skills = PackJson.decode(legacy).skills.associateBy { it.id }
        assertEquals(SkillDelivery.MELEE, skills.getValue("old:jab").delivery)
        assertEquals(SkillDelivery.NOVA, skills.getValue("old:ring").delivery)
        assertEquals(SkillDelivery.BEAM, skills.getValue("old:spear").delivery)
        assertEquals(SkillDelivery.MELEE, skills.getValue("old:plain").delivery)
        assertEquals(listOf<SkillEffect>(SkillEffect.Damage("old:fire")), skills.getValue("old:ring").resolvedEffects)
    }

    private val broken = """
        {
          "id": "brk", "name": "Broken Builds",
          "damageTypes": [
            { "id": "brk:fire", "name": "Fire", "armour": false, "ailment": "brk:burn", "ailmentChance": 0.25 },
            { "id": "brk:phys", "name": "Physical" }
          ],
          "statuses": [
            { "id": "brk:burn", "name": "Burn", "tags": ["burning"], "behaviours": [{ "type": "dot", "damageType": "brk:fire", "hitShare": 0.2 }] },
            { "id": "brk:shock", "name": "Shock", "stacking": "intensity", "maxStacks": 3, "behaviours": [{ "type": "damage_taken", "amount": 0.1 }] },
            { "id": "brk:frenzy", "name": "Frenzy", "debuff": false,
              "behaviours": [{ "type": "modifiers", "modifiers": [{ "stat": "attack_speed", "value": 0.1 }] }] }
          ],
          "skills": [
            { "id": "brk:fireball", "name": "Fireball", "damageType": "brk:fire", "delivery": "projectile", "range": 10,
              "projectile": { "count": 3, "pierce": 1, "chain": 2 }, "tags": ["spell"],
              "effects": [{ "type": "damage", "damageType": "brk:fire" }, { "type": "cast", "skill": "brk:burst" }] },
            { "id": "brk:burst", "name": "Burst", "damageType": "brk:fire", "delivery": "nova", "range": 2 },
            { "id": "brk:cry", "name": "Cry", "damageType": "brk:phys", "delivery": "self",
              "effects": [{ "type": "status", "status": "brk:frenzy", "target": "allies" }] }
          ],
          "enemies": [{
            "id": "brk:tyrant", "name": "Tyrant", "damageType": "brk:phys", "rank": "boss",
            "skills": [{ "skill": "brk:fireball", "weight": 50 }],
            "phases": [{ "name": "Rage", "healthBelow": 0.5, "adds": [{ "enemy": "brk:tyrant", "count": 1 }],
                         "enrage": [{ "stat": "damage", "kind": "more", "value": 0.5 }], "status": "brk:frenzy" }]
          }],
          "traits": [{
            "id": "brk:oath", "name": "Oath", "keystones": ["cannot_crit", "instant_leech"],
            "modifiers": [{ "stat": "damage", "kind": "more", "value": 0.4 }],
            "conditional": [
              { "when": "target_has", "status": "burning", "stat": "damage", "value": 0.3 },
              { "when": "per", "attribute": "strength", "per": 10, "stat": "armour", "kind": "flat", "value": 5 }
            ],
            "conversions": [{ "from": "brk:phys", "to": "brk:fire", "share": 0.5 }, { "to": "brk:fire", "share": 0.1, "extra": true }],
            "triggers": [{ "on": "crit", "cast": "brk:burst", "cooldownSeconds": 0.5 }, { "on": "low_life", "status": "brk:frenzy", "statusOnSelf": true }]
          }],
          "flasks": [{ "id": "brk:gourd", "name": "Gourd", "lifeShare": 0.3, "recoverySeconds": 2, "status": "brk:frenzy" }],
          "supports": [{ "id": "brk:echo", "name": "Echo", "requiresTags": ["spell"], "trigger": { "on": "kill" },
                         "effects": [{ "type": "status", "status": "brk:shock", "chance": 0.5 }] }],
          "rules": { "combat": { "resistanceCap": 1.0, "resistanceHardCap": 1.0, "triggerDepth": 6, "cooldownFloor": 0 } }
        }
    """.trimIndent()

    @Test
    fun `a pack can write every part of the combat core by hand, and it plays`() {
        val pack = PackJson.decode(broken)
        val fireball = pack.skills.first()
        assertEquals(3, fireball.projectile.count)
        assertEquals(SkillEffect.CastSkill("brk:burst"), fireball.effects[1])
        assertEquals(EffectTarget.ALLIES, (pack.skills[2].effects.single() as SkillEffect.ApplyStatus).target)
        assertEquals(StackingRule.INTENSITY, pack.statuses[1].stacking)
        assertEquals(StatusBehaviour.DamageOverTime("brk:fire", hitShare = 0.2f), pack.statuses[0].behaviours.single())
        val trait = pack.traits.single()
        assertEquals(setOf(Keystone.CANNOT_CRIT, Keystone.INSTANT_LEECH), trait.keystones)
        assertEquals(Condition.Per(Attribute.STRENGTH, 10), trait.conditional[1].condition)
        assertEquals(1, trait.conversions.size)
        assertEquals(1, trait.extraDamage.size)
        assertEquals(listOf(TriggerEvent.ON_CRIT, TriggerEvent.ON_LOW_LIFE), trait.triggers.map { it.event })
        assertEquals(0.5f, pack.enemies.single().phases.single().healthBelow)
        assertEquals(TriggerEvent.ON_KILL, pack.supports.single().trigger!!.event)
        assertEquals(CombatRules(resistanceCap = 1f, resistanceHardCap = 1f, triggerDepth = 6, cooldownFloor = 0f), pack.rules!!.combat)
        ContentPackAssembler().assemble(listOf(IgboContentPack.pack, pack))
    }

    @Test
    fun `the combat core survives a trip through plugin JSON unchanged`() {
        val pack = PackJson.decode(broken)
        assertEquals(pack, PackJson.decode(PackJson.encode(pack)))
        val defaults = pack.copy(rules = WorldRules())
        assertEquals(defaults, PackJson.decode(PackJson.encode(defaults)))
    }

    @Test
    fun `combat mistakes are named by field`() {
        val badDelivery = assertFailsWith<ImportException> { PackJson.decode(broken.replace("\"delivery\": \"projectile\"", "\"delivery\": \"catapult\"")) }
        assertContains(badDelivery.message.orEmpty(), "skill 'brk:fireball' delivery")
        val badEffect = assertFailsWith<ImportException> { PackJson.decode(broken.replace("\"type\": \"cast\"", "\"type\": \"explode\"")) }
        assertContains(badEffect.message.orEmpty(), "skill 'brk:fireball' effect")
        val badShape = assertFailsWith<ImportException> { PackJson.decode(legacy.replace("\"strike\"", "\"spiral\"")) }
        assertContains(badShape.message.orEmpty(), "skill 'old:jab' shape")
        val badTrigger = assertFailsWith<ImportException> { PackJson.decode(broken.replace("\"on\": \"crit\"", "\"on\": \"sneeze\"")) }
        assertContains(badTrigger.message.orEmpty(), "trigger 'on'")
    }
}
