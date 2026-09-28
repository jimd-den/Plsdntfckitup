package com.stratum.content.igbo

import com.stratum.core.domain.actor.EffectTarget
import com.stratum.core.domain.actor.ProjectileSpec
import com.stratum.core.domain.actor.SkillArea
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.actor.ZoneSpec
import com.stratum.core.domain.combat.Attribute
import com.stratum.core.domain.combat.Condition
import com.stratum.core.domain.combat.ConditionalModifier
import com.stratum.core.domain.combat.FlaskDefinition
import com.stratum.core.domain.combat.Keystone
import com.stratum.core.domain.combat.TraitDefinition
import com.stratum.core.domain.combat.TriggerDefinition
import com.stratum.core.domain.combat.TriggerEvent
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.status.StackingRule
import com.stratum.core.domain.status.StatusBehaviour
import com.stratum.core.domain.status.StatusDefinition

/**
 * What the built-in pack's fights are made of beyond numbers: the statuses
 * its five damage types leave behind, the skills heroes and monsters cast,
 * the traits a build is named after, and the flasks on the belt.
 */
internal object IgboPackAbilities {

    private const val NS = "igbo"
    private val physical = IgboPackCombat.physical
    private val thunder = IgboPackCombat.thunder
    private val solar = IgboPackCombat.solar
    private val venom = IgboPackCombat.venom
    private val spirit = IgboPackCombat.spirit

    // ---- statuses ----------------------------------------------------------

    val bleed = StatusDefinition(
        "$NS:bleed", "Bleeding", "The bronze opened something that does not close on its own.",
        listOf(StatusBehaviour.DamageOverTime(physical.id, hitShare = 0.15f)), durationSeconds = 4f, color = 0xFFB71C1C, symbol = "🩸",
        tags = setOf("bleeding"),
    )
    val shock = StatusDefinition(
        "$NS:shock", "Shocked", "Amadioha has marked it. Everything hurts it more.",
        listOf(StatusBehaviour.DamageTaken(0.08f)), durationSeconds = 3f, stacking = StackingRule.INTENSITY, maxStacks = 3,
        color = 0xFF00E5FF, symbol = "⚡", tags = setOf("shocked"),
    )
    val scorch = StatusDefinition(
        "$NS:scorch", "Scorched", "Anyanwu's light, still working after it has landed.",
        listOf(StatusBehaviour.DamageOverTime(solar.id, hitShare = 0.25f)), durationSeconds = 4f, color = 0xFFFF6D00, symbol = "☀",
        tags = setOf("burning"),
    )
    val poison = StatusDefinition(
        "$NS:poison", "Poisoned", "Idemili's river, in the blood. Every dose adds to the last.",
        listOf(StatusBehaviour.DamageOverTime(venom.id, hitShare = 0.1f)), durationSeconds = 3f, stacking = StackingRule.STACK, maxStacks = 8,
        color = 0xFF00E676, symbol = "☣", tags = setOf("poisoned"),
    )
    val grip = StatusDefinition(
        "$NS:ancestral_grip", "Ancestral Grip", "Hands from below hold its ankles.",
        listOf(StatusBehaviour.Slow(0.35f)), durationSeconds = 2f, color = 0xFFB388FF, symbol = "✋", tags = setOf("chilled"),
    )
    val dazed = StatusDefinition(
        "$NS:dazed", "Dazed", "The ground moved, and so did the world.",
        listOf(StatusBehaviour.Stun), durationSeconds = 1f, color = 0xFFFFD54F, symbol = "✺",
    )
    val warDrum = StatusDefinition(
        "$NS:war_drum", "War Drum", "The ikoro calls, and arms answer.",
        listOf(StatusBehaviour.Modifiers(listOf(StatModifier(Stat.DAMAGE, ModifierKind.INCREASED, 0.25f), StatModifier(Stat.ATTACK_SPEED, ModifierKind.INCREASED, 0.15f)))),
        durationSeconds = 6f, isDebuff = false, color = 0xFFFFB300, symbol = "🥁",
    )
    val ward = StatusDefinition(
        "$NS:ancestral_ward", "Ancestral Ward", "Those who came before stand close.",
        listOf(StatusBehaviour.Recover(maxShare = 0.03f)), durationSeconds = 5f, isDebuff = false, color = 0xFF7BC67E, symbol = "✦",
    )
    val wrath = StatusDefinition(
        "$NS:agbara_wrath", "Wrath of the Agbara", "The spirit the priest speaks for has stopped being patient.",
        listOf(StatusBehaviour.Modifiers(listOf(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 0.3f)))),
        durationSeconds = 600f, isDebuff = false, color = 0xFFD50000, symbol = "☠",
    )

    val statuses = listOf(bleed, shock, scorch, poison, grip, dazed, warDrum, ward, wrath)

    // ---- skills: the heroes' -----------------------------------------------

    val heroSkills = listOf(
        SkillDefinition(
            id = "$NS:mma_nkwu_cleave", name = "Mma Nkwu Cleave", description = "A wide arc that catches everything standing too close.",
            damageTypeId = physical.id, powerMultiplier = 1.4f, resourceCost = 15, cooldownSeconds = 3f,
            delivery = SkillDelivery.CONE, range = 3, area = SkillArea(angleDegrees = 150f), color = 0xFFCFD8DC,
            effects = listOf(SkillEffect.Damage(physical.id), SkillEffect.ApplyStatus(bleed.id, chance = 0.3f)),
        ),
        SkillDefinition(
            id = "$NS:ikenga_tremor", name = "Ikenga Tremor", description = "One downward strike. The ground carries the rest.",
            damageTypeId = solar.id, powerMultiplier = 2.6f, resourceCost = 30, cooldownSeconds = 7f,
            delivery = SkillDelivery.MELEE, range = 2, color = 0xFFFF6D00,
            effects = listOf(SkillEffect.Damage(physical.id, 0.4f), SkillEffect.Damage(solar.id, 0.6f), SkillEffect.ApplyStatus(dazed.id, chance = 0.4f), SkillEffect.Knockback(20f)),
        ),
        SkillDefinition(
            id = "$NS:thunder_spear", name = "Amadioha Thunder Spear", description = "A line of charge, delivered without appeal.",
            damageTypeId = thunder.id, powerMultiplier = 2.1f, resourceCost = 25, cooldownSeconds = 5f,
            delivery = SkillDelivery.BEAM, range = 7, color = 0xFF00E5FF,
            effects = listOf(SkillEffect.Damage(thunder.id), SkillEffect.ApplyStatus(shock.id, chance = 0.5f)),
        ),
        SkillDefinition(
            id = "$NS:shockwave_spark", name = "Shockwave Spark", description = "A short discharge that clears breathing room.",
            damageTypeId = thunder.id, powerMultiplier = 1.2f, resourceCost = 12, cooldownSeconds = 2.5f,
            delivery = SkillDelivery.NOVA, range = 4, color = 0xFF80D8FF,
            effects = listOf(SkillEffect.Damage(thunder.id), SkillEffect.Knockback(12f)),
        ),
        SkillDefinition(
            id = "$NS:ogene_bolt", name = "Ogene Bolt", description = "The gong's note, thrown. It leaps from one body to the next.",
            damageTypeId = thunder.id, powerMultiplier = 1.1f, resourceCost = 10, cooldownSeconds = 0.8f,
            delivery = SkillDelivery.PROJECTILE, range = 10, color = 0xFF40C4FF,
            projectile = ProjectileSpec(speed = 14f, chain = 2),
        ),
        SkillDefinition(
            id = "$NS:venom_geyser", name = "Idemili Venom Geyser", description = "The ground opens and returns what was poured into it.",
            damageTypeId = venom.id, powerMultiplier = 0.7f, resourceCost = 22, cooldownSeconds = 6f,
            delivery = SkillDelivery.ZONE, range = 5, area = SkillArea(radius = 2.5f), zone = ZoneSpec(durationSeconds = 4f, pulseSeconds = 0.8f),
            color = 0xFF00E676, effects = listOf(SkillEffect.Damage(venom.id), SkillEffect.ApplyStatus(poison.id)),
        ),
        SkillDefinition(
            id = "$NS:seed_volley", name = "Udara Seed Volley", description = "A fistful of poisoned seeds, thrown wide.",
            damageTypeId = venom.id, powerMultiplier = 0.8f, resourceCost = 14, cooldownSeconds = 1.5f,
            delivery = SkillDelivery.PROJECTILE, range = 8, color = 0xFF69F0AE,
            projectile = ProjectileSpec(count = 3, speed = 11f, pierce = 1, spreadDegrees = 35f),
            effects = listOf(SkillEffect.Damage(venom.id), SkillEffect.ApplyStatus(poison.id, chance = 0.6f)),
        ),
        SkillDefinition(
            id = "$NS:solar_supernova", name = "Anyanwu Supernova", description = "Everything within reach is judged at once.",
            damageTypeId = solar.id, powerMultiplier = 3.2f, resourceCost = 45, cooldownSeconds = 12f,
            delivery = SkillDelivery.NOVA, range = 6, color = 0xFFFFB300, castTime = 0.4f,
            effects = listOf(SkillEffect.Damage(solar.id), SkillEffect.ApplyStatus(scorch.id)),
        ),
    )

    // ---- skills: the monsters' ---------------------------------------------

    val wispSpark = SkillDefinition(
        id = "$NS:wisp_spark", name = "Wisp Spark", description = "A mote of charge, looking for ground.",
        damageTypeId = thunder.id, powerMultiplier = 0.9f, resourceCost = 0, cooldownSeconds = 2.2f, delivery = SkillDelivery.PROJECTILE,
        range = 9, color = 0xFF00E5FF, projectile = ProjectileSpec(speed = 9f), tags = setOf("monster"),
    )
    val revenantSpit = SkillDefinition(
        id = "$NS:revenant_spit", name = "River Spit", damageTypeId = venom.id, powerMultiplier = 0.8f, resourceCost = 0, cooldownSeconds = 4f,
        delivery = SkillDelivery.PROJECTILE, range = 6, color = 0xFF33691E, projectile = ProjectileSpec(speed = 7f),
        effects = listOf(SkillEffect.Damage(venom.id), SkillEffect.ApplyStatus(poison.id)), tags = setOf("monster"),
    )
    val guardianSlam = SkillDefinition(
        id = "$NS:guardian_slam", name = "Bronze Slam", description = "It raises both arms. Do not be there when they come down.",
        damageTypeId = solar.id, powerMultiplier = 2.2f, resourceCost = 0, cooldownSeconds = 6f, delivery = SkillDelivery.AREA,
        range = 3, area = SkillArea(radius = 2.2f), castTime = 1.2f, color = 0xFFCD7F32,
        effects = listOf(SkillEffect.Damage(solar.id), SkillEffect.ApplyStatus(dazed.id, chance = 0.5f)), tags = setOf("monster"),
    )
    val priestMend = SkillDefinition(
        id = "$NS:priest_mend", name = "Mend the Faithful", damageTypeId = spirit.id, powerMultiplier = 0f, resourceCost = 0, cooldownSeconds = 7f,
        delivery = SkillDelivery.SELF, range = 6, color = 0xFF7BC67E,
        effects = listOf(SkillEffect.Heal(maxShare = 0.2f, target = EffectTarget.ALLIES), SkillEffect.ApplyStatus(ward.id, target = EffectTarget.ALLIES)),
        tags = setOf("monster"),
    )
    val warCry = SkillDefinition(
        id = "$NS:war_cry", name = "Ikoro War Cry", damageTypeId = physical.id, powerMultiplier = 0f, resourceCost = 0, cooldownSeconds = 12f,
        delivery = SkillDelivery.SELF, range = 7, color = 0xFFFFB300,
        effects = listOf(SkillEffect.ApplyStatus(warDrum.id, target = EffectTarget.ALLIES)), tags = setOf("monster"),
    )
    val priestCurse = SkillDefinition(
        id = "$NS:priest_curse", name = "Hand of the Buried", damageTypeId = spirit.id, powerMultiplier = 1.1f, resourceCost = 0, cooldownSeconds = 3f,
        delivery = SkillDelivery.PROJECTILE, range = 10, color = 0xFFB388FF, projectile = ProjectileSpec(count = 3, speed = 8f, spreadDegrees = 40f),
        effects = listOf(SkillEffect.Damage(spirit.id), SkillEffect.ApplyStatus(grip.id)), tags = setOf("monster"),
    )
    val judgement = SkillDefinition(
        id = "$NS:agbara_judgement", name = "Judgement of the Agbara", description = "The priest kneels. The ground around him is about to answer.",
        damageTypeId = spirit.id, powerMultiplier = 2.6f, resourceCost = 0, cooldownSeconds = 8f, delivery = SkillDelivery.NOVA,
        range = 4, castTime = 1.5f, color = 0xFFD500F9, tags = setOf("monster"),
    )

    val monsterSkills = listOf(wispSpark, revenantSpit, guardianSlam, priestMend, warCry, priestCurse, judgement)

    val skills = heroSkills + monsterSkills

    // ---- traits ------------------------------------------------------------

    val stormCaller = TraitDefinition(
        "$NS:storm_caller", "Storm Caller", "Amadioha finishes what he marks. The more insight, the louder the thunder.",
        conditional = listOf(
            ConditionalModifier(Condition.TargetHas("shocked"), StatModifier(Stat.DAMAGE, ModifierKind.INCREASED, 0.2f)),
            ConditionalModifier(Condition.Per(Attribute.INSIGHT, 5), StatModifier(Stat.AILMENT_CHANCE, ModifierKind.FLAT, 0.02f)),
        ),
    )
    val bloodOfAla = TraitDefinition(
        "$NS:blood_of_ala", "Blood of Ala", "The earth drinks, and so do you. Nothing heals you but the fight.",
        modifiers = listOf(StatModifier(Stat.LIFE_STEAL, ModifierKind.FLAT, 0.06f)),
        keystones = setOf(Keystone.INSTANT_LEECH, Keystone.NO_REGENERATION),
        triggers = listOf(TriggerDefinition(TriggerEvent.ON_LOW_LIFE, cooldownSeconds = 20f, applyStatusId = warDrum.id, statusOnSelf = true)),
    )
    val ofoOath = TraitDefinition(
        "$NS:ofo_oath", "Oath of the Ofo", "Truth does not need luck. You never strike critically, and you never need to.",
        modifiers = listOf(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 0.35f)),
        keystones = setOf(Keystone.CANNOT_CRIT),
    )
    val sunEater = TraitDefinition(
        "$NS:sun_eater", "Sun Eater", "Half of what you strike with becomes Anyanwu's fire.",
        conversions = listOf(com.stratum.core.domain.combat.DamageConversion(physical.id, solar.id, 0.5f)),
        triggers = listOf(TriggerDefinition(TriggerEvent.ON_KILL, chance = 0.25f, cooldownSeconds = 2f, castSkillId = "$NS:shockwave_spark")),
    )

    val traits = listOf(stormCaller, bloodOfAla, ofoOath, sunEater)

    // ---- flasks ------------------------------------------------------------

    val flasks = listOf(
        FlaskDefinition(
            "$NS:palm_wine", "Palm Wine Gourd", "Tapped this morning. It will not last, and neither will you without it.",
            maxCharges = 30, chargesPerUse = 10, chargesPerKill = 2, lifeShare = 0.4f, recoverySeconds = 2f, color = 0xFFF5F5DC, glyph = "🍶",
        ),
        FlaskDefinition(
            "$NS:kola_nut", "Kola Nut", "Broken and shared. It clears the head and steadies the hand.",
            maxCharges = 20, chargesPerUse = 10, chargesPerKill = 1, resource = 40, statusId = ward.id, cleanses = true,
            color = 0xFFA0522D, glyph = "🌰",
        ),
    )
}
