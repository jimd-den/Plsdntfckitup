package com.stratum.core.domain.attack

import com.stratum.core.domain.actor.EffectTarget
import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.MonsterSkill
import com.stratum.core.domain.actor.ProjectileSpec
import com.stratum.core.domain.actor.SkillArea
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.actor.TerrainChange
import com.stratum.core.domain.actor.ZoneSpec
import com.stratum.core.domain.combat.DamageTypeDefinition
import com.stratum.core.domain.combat.TriggerDefinition
import com.stratum.core.domain.combat.TriggerEvent
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.status.StackingRule
import com.stratum.core.domain.status.StatusBehaviour
import com.stratum.core.domain.status.StatusDefinition
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A content pack's names for the forge's elements: which of its damage types
 * an iron, burning, frozen or ancestral attack deals.
 */
data class AttackVocabulary(val damageTypes: Map<Element, String>) {
    fun damageType(e: Element): String = damageTypes[e] ?: damageTypes.getValue(Element.PHYSICAL)

    companion object {
        /**
         * Matches a pack's damage types to the elements by name: fire, solar
         * and flame to fire; thunder, storm and lightning to storm; and so on.
         * Elements a pack has no type for fall back to the nearest it has, and
         * at last to its first type.
         */
        fun from(types: List<DamageTypeDefinition>): AttackVocabulary {
            require(types.isNotEmpty()) { "a pack with no damage types has nothing to forge with" }
            fun find(vararg words: String) = types.firstOrNull { t -> words.any { w -> t.id.contains(w, true) || t.name.contains(w, true) } }?.id
            val physical = find("physical", "iron", "bronze", "blade") ?: types.first().id
            val fire = find("fire", "solar", "flame", "burn", "sun")
            val frost = find("frost", "cold", "ice", "chill")
            val storm = find("storm", "thunder", "lightning", "shock")
            val venom = find("venom", "poison", "toxic", "chaos")
            val spirit = find("spirit", "ancestral", "arcane", "soul")
            val earth = find("earth", "stone")
            val shadow = find("shadow", "void", "dark", "night")
            return AttackVocabulary(
                mapOf(
                    Element.PHYSICAL to physical, Element.FIRE to (fire ?: physical), Element.FROST to (frost ?: spirit ?: physical),
                    Element.STORM to (storm ?: fire ?: physical), Element.VENOM to (venom ?: physical), Element.SPIRIT to (spirit ?: physical),
                    Element.EARTH to (earth ?: physical), Element.SHADOW to (shadow ?: spirit ?: venom ?: physical),
                ),
            )
        }
    }
}

/**
 * Turns forged attacks into the engine's own skills, so they play in the
 * same fight as every pack's: projectiles, beams, novas, zones, statuses,
 * knockback and chained casts the combat core already knows.
 *
 * Each phase becomes a [SkillDefinition]. Shape and delivery choose the
 * engine's delivery and its projectile, area and zone; modulators become
 * pierce, fork, chain and extra projectiles, or helper skills (a bloom's
 * shells, an echo's replay, a lingering patch); the payload becomes damage of
 * the element's type, a status, knockback or a pull, or a [SkillEffect.Terrain]
 * that changes the blocks. Chained attacks are cast where the phase lands
 * (on hit, on striking a wall, as it ends) or granted as triggers its owner
 * carries (on crit, kill, block, dash).
 *
 * Everything is namespaced `forge:`, and a whole arsenal travels as one
 * [ContentPack] ([pack]) that joins the others.
 */
object AttackCompiler {
    const val NS = "forge"
    /** Raised walls are this block while they stand. */
    const val WALL_BLOCK = "$NS:raised_earth"
    /** Frozen water is this block until it thaws. */
    const val ICE_BLOCK = "$NS:rime_ice"

    /** The skill the player or a monster casts, then every helper and chained skill it needs, root first. */
    fun compile(skill: ProceduralSkill, vocabulary: AttackVocabulary, forMonster: Boolean = false): List<SkillDefinition> {
        val out = ArrayList<SkillDefinition>()
        val look = skill.look
        // Later phases in the sequence are cast from the caster as the first lands.
        val followers = skill.rootSequence.drop(1).mapIndexed { i, phase -> phaseSkill(skill, phase, "${skill.id}/then$i", vocabulary, look, forMonster, out, root = false) }
        out += followers
        val root = phaseSkill(skill, skill.lead, skill.id, vocabulary, look, forMonster, out, root = true)
        val withFollowers = root.copy(effects = root.effects + followers.map { SkillEffect.CastSkill(it.id, EffectTarget.SELF) })
        return listOf(withFollowers) + out.filter { it.id != root.id }
    }

    private fun phaseSkill(
        owner: ProceduralSkill, phase: SkillPhase, id: String, vocab: AttackVocabulary, look: AttackLook,
        forMonster: Boolean, out: MutableList<SkillDefinition>, root: Boolean,
    ): SkillDefinition {
        val d = phase.delivery; val g = phase.geometry
        val type = vocab.damageType(phase.element)
        val effects = ArrayList<SkillEffect>()
        effects += SkillEffect.Damage(type)
        effects += payloadEffects(phase.payload, phase.element, phase.delivery)
        phase.secondary?.let { effects += payloadEffects(it, phase.element, phase.delivery) }

        // ---- delivery and shape -------------------------------------------------------------------
        val travels = d.kind == DeliveryKind.BALLISTIC || d.kind == DeliveryKind.SURFACE_WAVE
        var delivery = when (d.kind) {
            DeliveryKind.INSTANT_RAY, DeliveryKind.TETHER -> SkillDelivery.BEAM
            DeliveryKind.BALLISTIC, DeliveryKind.SURFACE_WAVE -> SkillDelivery.PROJECTILE
            DeliveryKind.IMPACT_FIELD -> SkillDelivery.ZONE
            DeliveryKind.ORBITAL -> SkillDelivery.NOVA
        }
        var count = 1; var pierce = 0; var fork = 0; var chain = 0
        var spread = g.spreadDegrees
        var radius = 0f; var angle = 90f
        var halfWidth = if (d.kind == DeliveryKind.TETHER) 0.5f else stepped(d.b, 0.35f, 1.4f)
        when (g.shape) {
            EmitterShape.SINGLE -> Unit
            EmitterShape.PIERCING_LINE -> if (travels) pierce += 2 else halfWidth *= 0.7f
            EmitterShape.NOVA -> if (travels) {
                // The shell bursts into a ring where it lands.
                val nova = helper(owner, "$id/nova", phase, SkillDelivery.AREA, vocab, look, radius = 1.5f + 0.3f * g.count, share = 0.6f)
                out += nova; effects += SkillEffect.CastSkill(nova.id)
            } else { delivery = SkillDelivery.NOVA; radius = max(d.reach, 2f) }
            EmitterShape.CONE -> if (!travels) { delivery = SkillDelivery.CONE; angle = spread.coerceIn(30f, 180f); radius = d.reach } else { count = max(3, g.count); spread = spread.coerceIn(30f, 120f) }
            EmitterShape.FAN, EmitterShape.HELIX -> if (travels) { count = g.count; if (g.shape == EmitterShape.HELIX) spread = 360f / g.count * (g.count - 1) } else { delivery = SkillDelivery.CONE; angle = spread.coerceIn(30f, 180f) }
            EmitterShape.CHAIN -> { delivery = SkillDelivery.CHAIN; chain = g.count + 1 }
            EmitterShape.RUPTURE_GRID -> { delivery = SkillDelivery.ZONE; radius = max(1.5f, d.reach * 0.5f + 0.25f * g.count) }
        }
        if (d.kind == DeliveryKind.IMPACT_FIELD || d.kind == DeliveryKind.ORBITAL) radius = max(radius, d.reach)

        // ---- modulators ---------------------------------------------------------------------------
        var castTime = 0f; var speed = d.speed; var charges = 1
        for (m in phase.modulators) when (m.kind) {
            ModulatorKind.FORK -> fork += 1 + m.level / 3
            ModulatorKind.SPLIT -> fork += 1
            ModulatorKind.PIERCE -> pierce += 1 + m.level
            ModulatorKind.RICOCHET -> chain += 1 + m.level / 2
            ModulatorKind.MULTICAST -> if (delivery == SkillDelivery.PROJECTILE) count = min(12, count * (2 + m.level / 3)) else charges = 2 + m.level / 3
            ModulatorKind.ACCELERATE -> speed *= 1.35f
            ModulatorKind.DELAY -> castTime += 0.3f + 0.15f * m.level
            ModulatorKind.SIPHON -> effects += SkillEffect.Heal(maxShare = 0.004f * (2 + m.level), target = EffectTarget.SELF)
            ModulatorKind.GRAVITATE -> effects += SkillEffect.Knockback(-1.5f - 0.2f * m.level)
            ModulatorKind.VOXEL_DISRUPTION -> effects += SkillEffect.Terrain(TerrainChange.CRATER, 0.8f + 0.25f * m.level)
            ModulatorKind.BLOOM -> {
                val shells = helper(owner, "$id/bloom", phase, SkillDelivery.PROJECTILE, vocab, look, count = 2 + m.level / 2, spread = 360f, share = 0.35f)
                out += shells; effects += SkillEffect.CastSkill(shells.id)
            }
            ModulatorKind.SHATTER -> {
                val shards = helper(owner, "$id/shards", phase, SkillDelivery.PROJECTILE, vocab, look, count = 4, spread = 360f, share = 0.25f)
                out += shards; effects += SkillEffect.CastSkill(shards.id)
            }
            ModulatorKind.ECHO -> {
                val echo = helper(owner, "$id/echo", phase, SkillDelivery.AREA, vocab, look, radius = 1.8f, share = 0.5f, castTime = 0.4f + 0.1f * m.level)
                out += echo; effects += SkillEffect.CastSkill(echo.id)
            }
            ModulatorKind.LINGER -> {
                val patch = helper(owner, "$id/linger", phase, SkillDelivery.ZONE, vocab, look, radius = 1.4f, share = 0.25f)
                out += patch; effects += SkillEffect.CastSkill(patch.id)
            }
            // Pure flight: drawn, not counted.
            ModulatorKind.SINE_WAVE, ModulatorKind.HOMING, ModulatorKind.BOOMERANG, ModulatorKind.SPIRAL -> Unit
        }

        // ---- chained attacks ----------------------------------------------------------------------
        val grants = ArrayList<TriggerDefinition>()
        val tag = "$NS:${id.substringAfter(':')}"
        phase.subTriggers.entries.forEachIndexed { i, (event, sub) ->
            val subs = compile(sub.copy(id = "$id/on$i"), vocab, forMonster)
            out += subs
            val first = subs.first().id
            when (event) {
                SkillEvent.ON_HIT, SkillEvent.ON_VOXEL_HIT, SkillEvent.ON_EXPIRE -> effects += SkillEffect.CastSkill(first)
                SkillEvent.ON_CAST -> effects += SkillEffect.CastSkill(first, EffectTarget.SELF)
                SkillEvent.ON_CRIT -> grants += TriggerDefinition(TriggerEvent.ON_CRIT, cooldownSeconds = 0.5f, castSkillId = first, requiresTags = setOf(tag))
                SkillEvent.ON_KILL -> grants += TriggerDefinition(TriggerEvent.ON_KILL, cooldownSeconds = 0.5f, castSkillId = first, requiresTags = setOf(tag))
                SkillEvent.ON_BLOCK -> grants += TriggerDefinition(TriggerEvent.ON_BLOCK, cooldownSeconds = 1f, castSkillId = first)
                SkillEvent.ON_DASH -> grants += TriggerDefinition(TriggerEvent.ON_SKILL_USE, cooldownSeconds = 1f, castSkillId = first, requiresTags = setOf("movement"))
            }
        }

        // A monster's attack always gives the player time to see it coming.
        if (forMonster && root) castTime = max(castTime, 0.55f + 0.1f * phase.modulators.size)
        val zone = when {
            d.kind == DeliveryKind.IMPACT_FIELD -> ZoneSpec(durationSeconds = d.duration, pulseSeconds = (0.4f + 0.1f * d.b).coerceAtMost(d.duration), isTrap = phase.has(ModulatorKind.DELAY))
            g.shape == EmitterShape.RUPTURE_GRID -> ZoneSpec(durationSeconds = 0.4f * g.count + 0.4f, pulseSeconds = 0.4f)
            else -> ZoneSpec()
        }
        return SkillDefinition(
            id = id,
            name = if (root) owner.name else owner.name + " (" + id.substringAfterLast('/') + ")",
            description = if (root) AttackNaming.describe(owner).joinToString("\n") else "",
            damageTypeId = type,
            powerMultiplier = 1.5f * PowerBudget.shareOfPower(phase),
            resourceCost = if (root) owner.energyCost.roundToInt() else 0,
            cooldownSeconds = if (root) owner.cooldownSeconds else 0f,
            delivery = delivery,
            range = d.reach.roundToInt().coerceAtLeast(1),
            color = look.primary.toLong() and 0xFFFFFFFFL,
            area = SkillArea(radius = radius, angleDegrees = angle.coerceIn(0f, 360f), halfWidth = halfWidth),
            effects = effects,
            tags = setOf(tag, "$NS:attack"),
            castTime = castTime,
            charges = charges,
            projectile = ProjectileSpec(
                count = count.coerceIn(1, 12), speed = speed.coerceAtLeast(1f), pierce = pierce, chain = chain, fork = fork,
                spreadDegrees = spread.coerceIn(0f, 360f), radius = 0.35f + 0.08f * look.scale,
                // A ground wave flows over every step instead of breaking on it.
                collidesWithBlocks = d.kind != DeliveryKind.SURFACE_WAVE,
            ),
            zone = zone,
            grants = grants,
            look = look,
        )
    }

    /** A small skill a phase casts as part of itself: a bloom's shells, an echo, a lingering patch. */
    private fun helper(
        owner: ProceduralSkill, id: String, phase: SkillPhase, delivery: SkillDelivery, vocab: AttackVocabulary, look: AttackLook,
        radius: Float = 0f, count: Int = 1, spread: Float = 30f, share: Float, castTime: Float = 0f,
    ) = SkillDefinition(
        id = id, name = owner.name + " (" + id.substringAfterLast('/') + ")",
        damageTypeId = vocab.damageType(phase.element), powerMultiplier = 1.5f * PowerBudget.shareOfPower(phase) * share,
        resourceCost = 0, cooldownSeconds = 0f, delivery = delivery, range = 4,
        color = look.secondary.toLong() and 0xFFFFFFFFL,
        area = SkillArea(radius = radius),
        effects = listOf(SkillEffect.Damage(vocab.damageType(phase.element))) + payloadEffects(phase.payload, phase.element, phase.delivery).filterNot { it is SkillEffect.Terrain },
        tags = setOf("$NS:attack"), castTime = castTime,
        projectile = ProjectileSpec(count = count.coerceIn(1, 12), speed = 10f, spreadDegrees = spread.coerceIn(0f, 360f)),
        zone = ZoneSpec(durationSeconds = 2.5f, pulseSeconds = 0.5f),
        look = look,
    )

    private fun payloadEffects(p: Payload, element: Element, delivery: Delivery): List<SkillEffect> = when (p) {
        Payload.STAGGER -> listOf(SkillEffect.ApplyStatus(STAGGER))
        Payload.KNOCKBACK -> listOf(SkillEffect.Knockback(3.5f))
        Payload.VACUUM -> listOf(SkillEffect.Knockback(-2.5f), SkillEffect.ApplyStatus(SLOWED))
        Payload.PULL -> listOf(SkillEffect.Knockback(-4f))
        Payload.BURN -> listOf(SkillEffect.ApplyStatus(burnOf(element)))
        Payload.BRITTLE -> listOf(SkillEffect.ApplyStatus(BRITTLE))
        Payload.CONDUCTIVE -> listOf(SkillEffect.ApplyStatus(CONDUCTIVE))
        Payload.FLASH_FREEZE -> listOf(SkillEffect.ApplyStatus(FROZEN, chance = 0.6f), SkillEffect.ApplyStatus(SLOWED))
        Payload.CRATER -> listOf(SkillEffect.Terrain(TerrainChange.CRATER, 1.2f), SkillEffect.Knockback(2f))
        Payload.RAISE_WALL -> listOf(SkillEffect.Terrain(TerrainChange.WALL, 1.5f + 0.15f * delivery.a, seconds = 6f), SkillEffect.ApplyStatus(STAGGER))
        Payload.IGNITE_BRUSH -> listOf(SkillEffect.Terrain(TerrainChange.IGNITE, 2f), SkillEffect.ApplyStatus(burnOf(Element.FIRE)))
        Payload.FREEZE_WATER -> listOf(SkillEffect.Terrain(TerrainChange.FREEZE, 2.5f, seconds = 10f), SkillEffect.ApplyStatus(SLOWED))
    }

    // ---- statuses and blocks ---------------------------------------------------------------------

    const val STAGGER = "$NS:staggered"
    const val SLOWED = "$NS:slowed"
    const val BRITTLE = "$NS:brittle"
    const val CONDUCTIVE = "$NS:conductive"
    const val FROZEN = "$NS:frozen"
    fun burnOf(e: Element) = "$NS:burn_${e.name.lowercase()}"

    /** The statuses forged attacks inflict, dealing the vocabulary's damage types. */
    fun statuses(vocab: AttackVocabulary): List<StatusDefinition> = listOf(
        StatusDefinition(STAGGER, "Staggered", "Knocked off balance.", listOf(StatusBehaviour.Stun), durationSeconds = 0.35f, color = 0xFFFFE082, symbol = "✺"),
        StatusDefinition(SLOWED, "Slowed", "Dragged at, or rimed over.", listOf(StatusBehaviour.Slow(0.35f)), durationSeconds = 2.5f, color = 0xFF90CAF9, symbol = "❄", tags = setOf("chilled")),
        StatusDefinition(BRITTLE, "Brittle", "Cold has made it brittle: every blow lands harder.", listOf(StatusBehaviour.DamageTaken(0.15f)), durationSeconds = 4f, color = 0xFFB3E5FC, symbol = "❅", tags = setOf("chilled", "brittle")),
        StatusDefinition(CONDUCTIVE, "Conductive", "Charged: lightning finds it first.", listOf(StatusBehaviour.DamageTaken(0.25f, vocab.damageType(Element.STORM))),
            durationSeconds = 4f, stacking = StackingRule.INTENSITY, maxStacks = 3, color = 0xFF80D8FF, symbol = "⚡", tags = setOf("shocked")),
        StatusDefinition(FROZEN, "Frozen", "Held fast in ice.", listOf(StatusBehaviour.Stun), durationSeconds = 0.9f, color = 0xFFE1F5FE, symbol = "🧊", tags = setOf("frozen", "chilled")),
    ) + Element.entries.map { e ->
        StatusDefinition(
            burnOf(e), "${e.adjective} burn", "${e.label} still working after it has landed.",
            listOf(StatusBehaviour.DamageOverTime(vocab.damageType(e), hitShare = 0.2f)), durationSeconds = 4f,
            stacking = if (e == Element.VENOM) StackingRule.STACK else StackingRule.REFRESH, maxStacks = if (e == Element.VENOM) 6 else 1,
            color = AttackLook.of(SkillPhase(Delivery(DeliveryKind.BALLISTIC), Emitter(EmitterShape.SINGLE), payload = Payload.BURN, element = e), 0L).primary.toLong() and 0xFFFFFFFFL,
            symbol = "🔥", tags = setOf(if (e == Element.VENOM) "poisoned" else "burning"),
        )
    }

    /** The two blocks forged attacks make: raised earth and ice. */
    val blocks: List<BlockType> = listOf(
        BlockType(WALL_BLOCK, "Raised earth", material = BlockMaterial.SOIL, hardness = 0.6f, topColor = 0xFF8D5A3B, sideColor = 0xFF6D4228, dropId = BlockType.AIR_ID),
        BlockType(ICE_BLOCK, "Rime ice", material = BlockMaterial.STONE, hardness = 0.4f, isOpaque = false, topColor = 0xFFD8F1FF, sideColor = 0xFFA9D8F2, dropId = BlockType.AIR_ID),
    )

    /**
     * An arsenal as a pack: every attack compiled, the statuses and blocks
     * they need, and (when [enemies] are given) each monster armed with
     * forged attacks suited to its role, seeded by its id so every device
     * arms it the same.
     */
    fun pack(
        attacks: List<ProceduralSkill>, vocab: AttackVocabulary,
        enemies: List<EnemyDefinition> = emptyList(), worldSeed: Long = 0L, perEnemy: Int = 1,
    ): ContentPack {
        val playerSkills = attacks.flatMap { compile(it, vocab) }
        val armed = enemies.map { e ->
            val forged = List(perEnemy) { k -> AttackForge.roll(e.id.hashCode().toLong() * 7919L + worldSeed + k, e.role) }
            e to forged
        }
        val monsterSkills = armed.flatMap { (_, forged) -> forged.flatMap { compile(it, vocab, forMonster = true) } }
        return ContentPack(
            id = NS, name = "Forge of Will", author = "Stratum",
            description = "Procedural attacks forged from orthogonal parts.",
            blocks = blocks,
            statuses = statuses(vocab),
            skills = (playerSkills + monsterSkills).distinctBy { it.id },
            enemies = armed.map { (e, forged) -> e.copy(skills = e.skills + forged.map { MonsterSkill(it.id, weight = 60) }) },
        )
    }
}
