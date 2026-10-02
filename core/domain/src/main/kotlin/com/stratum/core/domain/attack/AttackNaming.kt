package com.stratum.core.domain.attack

import kotlin.random.Random

/**
 * Names and descriptions for attacks, made from what they are.
 *
 * A name is read off the attack's parts through a small grammar: a
 * modulator's adjective, a payload's or element's noun, and the delivery's own
 * noun ("Forked Ground-Lightning", "Echoing Cataclysm Volley", "Lance of the
 * Burning Drum"). Its seed only picks among the grammar's patterns and the
 * images it may borrow, so the same attack always has the same name.
 */
object AttackNaming {

    fun name(skill: ProceduralSkill): String = name(skill.lead, skill.seed)

    fun name(phase: SkillPhase, seed: Long): String {
        val r = Random(seed * 31 + 0x2F)
        val element = phase.element
        val adjective = phase.modulators.firstOrNull()?.kind?.adjective
        val noun = payloadNoun(phase.payload, element, r)
        val delivery = deliveryNoun(phase.delivery.kind, element, r)
        return when (r.nextInt(4)) {
            0 -> listOfNotNull(adjective, delivery).joinToString(" ")
            1 -> listOfNotNull(adjective, noun, deliveryCarrier(phase.delivery.kind, r)).joinToString(" ")
            2 -> "${deliveryCarrier(phase.delivery.kind, r)} of the ${element.adjective} ${IMAGES[r.nextInt(IMAGES.size)]}"
            else -> listOfNotNull(adjective ?: element.adjective, noun, geometryNoun(phase.geometry.shape, r)).joinToString(" ")
        }
    }

    /** The forge's permutation preview: one line per thing the attack does. */
    fun describe(skill: ProceduralSkill): List<String> = buildList {
        skill.rootSequence.forEachIndexed { i, phase ->
            val lead = if (skill.rootSequence.size > 1) "${ordinal(i)}: " else ""
            addAll(describe(phase, lead, depth = 0))
        }
    }

    private fun describe(phase: SkillPhase, lead: String, depth: Int): List<String> = buildList {
        val indent = "  ".repeat(depth)
        add("$indent$lead${phase.element.adjective} energy ${phase.delivery.kind.verb}, ${phase.geometry.shape.phrase}" +
            (if (phase.geometry.count > 1) " (${phase.geometry.count} at once)" else ""))
        phase.modulators.forEach { add("$indent• ${it.phrase.replaceFirstChar(Char::uppercase)}") }
        add("$indent• ${phase.payload.phrase.replaceFirstChar(Char::uppercase)}" + (phase.secondary?.let { "; also ${it.phrase}" } ?: ""))
        phase.subTriggers.forEach { (event, sub) ->
            add("$indent• ${event.label}: launches ${name(sub)}")
            sub.rootSequence.forEach { addAll(describe(it, "", depth + 1)) }
        }
    }

    private fun ordinal(i: Int) = listOf("First", "Then", "Last").getOrElse(i) { "Then" }

    private fun deliveryNoun(k: DeliveryKind, e: Element, r: Random): String = when (k) {
        DeliveryKind.SURFACE_WAVE -> "Ground-${e.noun}"
        DeliveryKind.INSTANT_RAY -> "${e.noun} ${pick(r, "Lance", "Ray", "Spear-light")}"
        DeliveryKind.BALLISTIC -> "${e.noun} ${pick(r, "Volley", "Mortar", "Shell")}"
        DeliveryKind.IMPACT_FIELD -> "${e.noun} ${pick(r, "Rune", "Circle", "Ward")}"
        DeliveryKind.TETHER -> "${e.noun} ${pick(r, "Tether", "Bond", "Leash")}"
        DeliveryKind.ORBITAL -> "${e.noun} ${pick(r, "Halo", "Orbit", "Swarm")}"
    }

    private fun deliveryCarrier(k: DeliveryKind, r: Random): String = when (k) {
        DeliveryKind.SURFACE_WAVE -> pick(r, "Tremor", "Groundswell", "Furrow")
        DeliveryKind.INSTANT_RAY -> pick(r, "Lance", "Sightline", "Ray")
        DeliveryKind.BALLISTIC -> pick(r, "Volley", "Barrage", "Throw")
        DeliveryKind.IMPACT_FIELD -> pick(r, "Rune", "Ring", "Ground")
        DeliveryKind.TETHER -> pick(r, "Bond", "Tether", "Cord")
        DeliveryKind.ORBITAL -> pick(r, "Halo", "Crown", "Orbit")
    }

    private fun payloadNoun(p: Payload, e: Element, r: Random): String = when (p) {
        Payload.STAGGER -> pick(r, "Hammer", "Concussion")
        Payload.KNOCKBACK -> pick(r, "Gale", "Ram")
        Payload.VACUUM -> pick(r, "Hollow", "Undertow")
        Payload.PULL -> pick(r, "Snare", "Grasp")
        Payload.BURN -> pick(r, "Pyre", "Cinder")
        Payload.BRITTLE -> pick(r, "Frostbite", "Fracture")
        Payload.CONDUCTIVE -> pick(r, "Static", "Current")
        Payload.FLASH_FREEZE -> pick(r, "Glacier", "Stillness")
        Payload.CRATER -> pick(r, "Cataclysm", "Quake")
        Payload.RAISE_WALL -> pick(r, "Bulwark", "Rampart")
        Payload.IGNITE_BRUSH -> pick(r, "Wildfire", "Bushfire")
        Payload.FREEZE_WATER -> pick(r, "Icebridge", "Rime")
    }.let { if (r.nextInt(5) == 0) "${e.noun}-$it" else it }

    private fun geometryNoun(g: EmitterShape, r: Random): String = when (g) {
        EmitterShape.SINGLE -> pick(r, "Strike", "Spike")
        EmitterShape.PIERCING_LINE -> pick(r, "Lance", "Skewer")
        EmitterShape.NOVA -> pick(r, "Nova", "Ring")
        EmitterShape.CONE -> pick(r, "Cleave", "Fan")
        EmitterShape.FAN -> pick(r, "Volley", "Spray")
        EmitterShape.HELIX -> pick(r, "Helix", "Coil")
        EmitterShape.CHAIN -> pick(r, "Chain", "Arc")
        EmitterShape.RUPTURE_GRID -> pick(r, "Eruption", "Upheaval")
    }

    private fun pick(r: Random, vararg xs: String) = xs[r.nextInt(xs.size)]

    /** Images a name may borrow: things of the land, the river and the masquerade. */
    private val IMAGES = listOf(
        "Drum", "Iroko", "Harmattan", "Leopard", "Python", "Egret", "Crocodile", "Baobab", "Kola", "Cowrie",
        "Ancestor", "Masquerade", "Floodplain", "Termite Hill", "Hornbill", "River", "Anvil", "Bronze", "Raffia", "Moon",
    )
}
