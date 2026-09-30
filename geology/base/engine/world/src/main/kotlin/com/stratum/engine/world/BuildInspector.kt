package com.stratum.engine.world

import com.stratum.core.domain.actor.Progression
import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.CombatTraits
import com.stratum.core.domain.combat.Condition
import com.stratum.core.domain.combat.ConditionContext
import com.stratum.core.domain.combat.HitDefender
import com.stratum.core.domain.combat.HitResolver
import com.stratum.core.domain.combat.Keystone
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.passive.PassiveBuild
import com.stratum.core.domain.sandbox.BaseContribution
import com.stratum.core.domain.sandbox.BreakdownLayer
import com.stratum.core.domain.sandbox.BuildBreakdown
import com.stratum.core.domain.sandbox.Contribution
import com.stratum.core.domain.sandbox.ExplainedStat
import com.stratum.core.domain.sandbox.SourceKind
import com.stratum.core.domain.sandbox.StatBounds
import com.stratum.core.domain.sandbox.StatBreakdown
import com.stratum.core.domain.sandbox.StatQuery
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.stats.StatSheet
import com.stratum.core.domain.status.StatusSet
import com.stratum.core.domain.tabletop.ActiveBoon
import kotlin.math.roundToInt

/**
 * Explains the player's numbers: for one stat, every modifier that reached
 * it -- by passive node, gear slot, set, support, trait, status and the
 * conditions true right now -- and the formula that combined them.
 *
 * It walks the same paths combat does, in the same order: attack power,
 * life and the other fought-with stats through the build sheet, then
 * survival's, then any tabletop boon, exactly as [PlayerProfile] resolves a
 * defender; evasion, block and resistance caps through the one combined
 * sheet the hit resolver reads; a skill's power through its supports the way
 * [Workbench] tunes it. That is what lets a test hold the explanation to the
 * fight: the number at the bottom is the number combat uses.
 */
internal class BuildInspector(
    private val content: AssembledContent,
    private val rules: CombatRules,
    private val profile: PlayerProfile,
    /** Survival's modifiers for a player: needs and food. */
    private val environment: (PlayerState) -> List<StatModifier>,
    private val boons: () -> List<ActiveBoon>,
    private val statuses: (String) -> StatusSet,
    private val supports: (PlayerState, String) -> List<SupportDefinition>,
) {
    private val damageTypeIds = content.damageTypes.map { it.id }

    /** The player as combat resolves them when struck now: what every explanation must end on. */
    fun defender(player: PlayerState): HitDefender = profile.defender(player, own())

    fun combatStats(player: PlayerState): CombatStats = defender(player).stats

    fun explain(player: PlayerState, query: StatQuery): StatBreakdown {
        val type = query.damageTypeId
        return when (query.stat) {
            ExplainedStat.DAMAGE -> fought(player, query, Stat.DAMAGE, StatBounds.WHOLE, damageBase(player), CombatStats::attackPower.float()) + damageNotes(player)
            ExplainedStat.LIFE -> fought(player, query, Stat.MAX_HEALTH, StatBounds(rounded = true, atLeast = 1f), lifeBase(player), CombatStats::maxHealth.float())
            ExplainedStat.ARMOUR -> fought(player, query, Stat.ARMOUR, StatBounds.WHOLE, classBase(player, player.baseStats.armour.toFloat()), CombatStats::armour.float())
                .let { it + "Takes ${percent(HitResolver.armourReduction(it.value.roundToInt(), ARMOUR_PROBE, rules))} off a hit of ${ARMOUR_PROBE.toInt()}" }
            ExplainedStat.ATTACK_SPEED -> fought(player, query, Stat.ATTACK_SPEED, StatBounds(atLeast = MIN_ATTACK_SPEED), speedBase(player)) { it.attackSpeed }
            ExplainedStat.CRIT_CHANCE -> critChance(player, query)
            ExplainedStat.CRIT_MULTIPLIER -> fought(player, query, Stat.CRIT_MULTIPLIER, StatBounds.PLAIN, classBase(player, player.baseStats.critMultiplier)) { it.critMultiplier }
            ExplainedStat.LIFE_STEAL -> fought(player, query, Stat.LIFE_STEAL, StatBounds(atMost = 1f), classBase(player, player.baseStats.lifeSteal)) { it.lifeSteal }
                .let { if (profile.traits(player).has(Keystone.INSTANT_LEECH)) it + "Leech is instant" else it + "Leech is paid out at up to ${percent(rules.maxLeechRate)} of life a second" }
            ExplainedStat.RESISTANCE -> resistance(player, query, type ?: damageTypeIds.firstOrNull().orEmpty())
            ExplainedStat.EVASION -> evasion(player, query)
            ExplainedStat.BLOCK -> block(player, query)
            ExplainedStat.MAX_RESOURCE -> single(
                query, Stat.MAX_RESOURCE, StatBounds.WHOLE, classBase(player, player.maxResource.toFloat()),
                passives(player) + gear(player, withInserts = false),
            )
            ExplainedStat.MOVE_SPEED -> single(
                query, Stat.MOVE_SPEED, StatBounds.PLAIN, listOf(BaseContribution(SourceKind.CLASS, "Walking", 1f)),
                passives(player) + gear(player) + survival(player),
            ) + "Stuns stop you and slows take their share off, while they last"
            ExplainedStat.SKILL_POWER -> skillPower(player, query)
        }
    }

    // ---- the paths combat takes ------------------------------------------------

    /**
     * A stat fought with: the build sheet (passives, what holds now, gear),
     * then survival's sheet, then any tabletop boon -- the three stages
     * [PlayerProfile.defender] resolves.
     */
    private fun fought(
        player: PlayerState,
        query: StatQuery,
        stat: Stat,
        bounds: StatBounds,
        base: List<BaseContribution>,
        field: (CombatStats) -> Float,
    ): StatBreakdown {
        val type = query.damageTypeId
        val built = BuildBreakdown.layer("Build", stat, base, passives(player) + extras(player) + gear(player), type, bounds)
        val layers = mutableListOf(built)
        val needs = survival(player)
        if (needs.isNotEmpty()) layers += BuildBreakdown.carried(layers.last(), "Survival", stat, needs, type, bounds)
        boonLayer(player, layers.last(), field)?.let(layers::add)
        return breakdown(query, layers)
    }

    /** One sheet, one pass: a stat read straight from the modifiers rather than from the fought-with numbers. */
    private fun single(query: StatQuery, stat: Stat, bounds: StatBounds, base: List<BaseContribution>, sources: List<Contribution>): StatBreakdown =
        breakdown(query, listOf(BuildBreakdown.layer("Build", stat, base, sources, query.damageTypeId, bounds)))

    /**
     * A boon changes the finished numbers by its own rule, not by the
     * formula: shown as a step from what it was handed to what it gave.
     */
    private fun boonLayer(player: PlayerState, previous: BreakdownLayer, field: (CombatStats) -> Float): BreakdownLayer? {
        val running = boons()
        if (running.isEmpty()) return null
        val after = field(combatStats(player))
        return BreakdownLayer(
            title = "Boons: " + running.joinToString { it.boon.name },
            base = listOf(BaseContribution(SourceKind.CARRIED, previous.title, previous.result)),
            flat = emptyList(), increased = emptyList(), more = emptyList(),
            raw = after, result = after,
        )
    }

    private fun critChance(player: PlayerState, query: StatQuery): StatBreakdown {
        val fought = fought(player, query, Stat.CRIT_CHANCE, StatBounds(atMost = 1f), classBase(player, player.baseStats.critChance)) { it.critChance }
        val layers = fought.layers.toMutableList()
        layers += BuildBreakdown.carried(layers.last(), "World cap", Stat.CRIT_CHANCE, emptyList(), bounds = StatBounds(atMost = rules.critChanceCap))
        if (profile.traits(player).has(Keystone.CANNOT_CRIT)) {
            layers += BuildBreakdown.carried(layers.last(), "Cannot crit", Stat.CRIT_CHANCE, emptyList(), bounds = StatBounds(atMost = 0f))
        }
        return breakdown(query, layers, fought.notes)
    }

    private fun resistance(player: PlayerState, query: StatQuery, type: String): StatBreakdown {
        val scoped = query.copy(damageTypeId = type)
        val fought = fought(player, scoped, Stat.RESISTANCE, StatBounds.SIGNED, classBase(player, player.baseStats.resistances[type] ?: 0f)) { it.rawResistanceTo(type) }
        // The cap is raised by "maximum resistance" on the one combined sheet the hit resolver reads.
        val raise = combinedSheet(player).let { sheet ->
            sheet.flat(Stat.MAX_RESISTANCE, type) * (1f + sheet.increased(Stat.MAX_RESISTANCE, type)) * sheet.more(Stat.MAX_RESISTANCE, type)
        }
        val cap = (rules.resistanceCap + raise).coerceAtMost(rules.resistanceHardCap).coerceAtLeast(rules.minResistance)
        val capped = BuildBreakdown.carried(
            fought.layers.last(), "World caps", Stat.RESISTANCE, emptyList(), type,
            StatBounds(floorAtZero = false, atLeast = rules.minResistance, atMost = cap),
        )
        val notes = listOf("Capped at ${percent(cap)}: the world's ${percent(rules.resistanceCap)}, raised by ${percent(raise)}, never past ${percent(rules.resistanceHardCap)}")
        return breakdown(scoped, fought.layers + capped, notes, title = "${content.damageType(type).name} resistance")
    }

    private fun evasion(player: PlayerState, query: StatQuery): StatBreakdown {
        val rating = single(query, Stat.EVASION, StatBounds.PLAIN, classBase(player, player.baseStats.evasion.toFloat()), combined(player))
        val chance = if (rating.value <= 0f) 0f else (rating.value / (rating.value + ACCURACY_PROBE)).coerceIn(0f, rules.maxEvadeChance)
        return rating + "Evades ${percent(chance)} of attacks with ${ACCURACY_PROBE.toInt()} accuracy (the world allows ${percent(rules.maxEvadeChance)})"
    }

    private fun block(player: PlayerState, query: StatQuery): StatBreakdown =
        single(query, Stat.BLOCK_CHANCE, StatBounds(atMost = rules.maxBlockChance), classBase(player, player.baseStats.blockChance), combined(player)) +
            "The world allows ${percent(rules.maxBlockChance)}"

    /** A skill's power as the build and its linked supports tune it. */
    private fun skillPower(player: PlayerState, query: StatQuery): StatBreakdown {
        val skill = query.skillId?.let(content::skill) ?: return breakdown(query, emptyList(), listOf("Choose a skill"))
        val linked = supports(player, skill.id).filter { it.fits(skill) }
        val sources = passives(player) + gear(player) + linked.flatMap { support -> support.modifiers.map { Contribution(SourceKind.SUPPORT, support.name, it) } }
        val layer = BuildBreakdown.layer("Build", Stat.SKILL_DAMAGE, listOf(BaseContribution(SourceKind.SKILL, skill.name, skill.powerMultiplier)), sources, skill.damageTypeId)
        return breakdown(query, listOf(layer), listOf("Each hit is attack power × this"), title = "${skill.name} power")
    }

    // ---- where modifiers come from ---------------------------------------------

    /** The passive tree, node by node, in the order the build sheet holds them. */
    private fun passives(player: PlayerState): List<Contribution> {
        val nodes = content.passiveTree?.let { PassiveBuild.startingOn(it, player.heroClassId) }?.copy(allocated = player.passives)?.nodes.orEmpty()
        val labelled = nodes.flatMap { node -> node.modifiers.map { Contribution(SourceKind.PASSIVE, node.name, it) } }
        // A build sheet that no longer matches the tree -- a test, an old save
        // mid-settle -- is still shown whole rather than half attributed.
        return if (labelled.map { it.modifier } == player.build.modifiers) labelled
        else player.build.modifiers.map { Contribution(SourceKind.PASSIVE, "Passive tree", it) }
    }

    /** Everything worn, slot by slot, then the set bonuses the worn pieces unlock. */
    private fun gear(player: PlayerState, withInserts: Boolean = true): List<Contribution> {
        val equipment = player.equipment
        val worn = equipment.items.entries.sortedBy { it.key.ordinal }.flatMap { (slot, item) ->
            item.modifiers(if (withInserts) content::insert else { _ -> null }).map { Contribution(SourceKind.GEAR, "${slot.label}: ${item.name}", it) }
        }
        val sets = equipment.setPieces.flatMap { (setId, pieces) ->
            val name = content.itemCatalogue.set(setId)?.name ?: setId
            equipment.all.first { it.setId == setId }.setBonuses.filter { it.pieces <= pieces }.flatMap { bonus ->
                bonus.modifiers.map { Contribution(SourceKind.SET_BONUS, "$name (${bonus.pieces} pieces)", it) }
            }
        }
        return worn + sets
    }

    private fun survival(player: PlayerState): List<Contribution> = environment(player).map { Contribution(SourceKind.SURVIVAL, "Needs and food", it) }

    /** What holds beyond the stored build: traits, statuses carried, and conditions true now -- [PlayerProfile.extras], attributed. */
    private fun extras(player: PlayerState): List<Contribution> {
        val traits = traitDefinitions(player)
        val own = own()
        val fromTraits = traits.flatMap { trait -> trait.modifiers.map { Contribution(if (trait.keystones.isEmpty()) SourceKind.TRAIT else SourceKind.KEYSTONE, trait.name, it) } } +
            CombatTraits.of(player.buildFlags).modifiers.map { Contribution(SourceKind.KEYSTONE, BuildFlag.HITS_IGNORE_RESISTANCE.label, it) }
        val fromStatuses = own.instances.flatMap { instance ->
            val status = content.statusBook[instance.statusId]
            status?.behaviours.orEmpty().filterIsInstance<com.stratum.core.domain.status.StatusBehaviour.Modifiers>().flatMap { behaviour ->
                behaviour.modifiers.map { Contribution(SourceKind.STATUS, status?.name ?: instance.statusId, it.copy(value = it.value * instance.stacks)) }
            }
        }
        val maxHealth = profile.maxHealth(player)
        val context = ConditionContext(
            lifeFraction = if (maxHealth <= 0) 0f else player.health.toFloat() / maxHealth,
            selfStatuses = own,
            attributes = profile.attributes(player),
            book = content.statusBook,
        )
        val conditional = traits.flatMap { trait ->
            trait.conditional.mapNotNull { rule -> rule.resolve(context)?.let { Contribution(SourceKind.CONDITIONAL, "${trait.name}: ${describe(rule.condition)}", it) } }
        }
        return fromTraits + fromStatuses + conditional
    }

    /** The one sheet the hit resolver reads for evasion, block and the resistance cap: [PlayerProfile.sheet]. */
    private fun combined(player: PlayerState): List<Contribution> = passives(player) + gear(player) + survival(player) + extras(player)

    private fun combinedSheet(player: PlayerState): StatSheet = StatSheet(combined(player).map { it.modifier })

    /** The same traits, in the same order, [PlayerProfile.traits] merges. */
    private fun traitDefinitions(player: PlayerState) = run {
        val hero = content.heroClasses.firstOrNull { it.id == player.heroClassId }
        val nodes = content.passiveTree?.nodes.orEmpty().filter { it.id in player.passives }
        (hero?.traitIds.orEmpty() + nodes.flatMap { it.traitIds }).distinct().mapNotNull(content::trait)
    }

    private fun own(): StatusSet = statuses(WorldSession.PLAYER_ACTOR_ID)

    // ---- bases -------------------------------------------------------------------

    private fun className(player: PlayerState) = content.heroClasses.firstOrNull { it.id == player.heroClassId }?.name ?: "Class"

    private fun classBase(player: PlayerState, value: Float) = listOf(BaseContribution(SourceKind.CLASS, className(player), value))

    private fun damageBase(player: PlayerState) = listOfNotNull(
        BaseContribution(SourceKind.CLASS, className(player), player.baseStats.attackPower.toFloat()),
        BaseContribution(SourceKind.LEVEL, "Level ${player.level}", Progression.attackBonusFor(player.level).toFloat()),
        player.equippedWeapon?.let { BaseContribution(SourceKind.WEAPON, it.name, it.averageDamage.toFloat()) },
    )

    private fun lifeBase(player: PlayerState) = listOf(
        BaseContribution(SourceKind.CLASS, className(player), player.baseStats.maxHealth.toFloat()),
        BaseContribution(SourceKind.LEVEL, "Level ${player.level}", Progression.healthBonusFor(player.level).toFloat()),
    )

    /** A weapon's speed replaces the fists' rather than adding to it. */
    private fun speedBase(player: PlayerState) = player.equippedWeapon
        ?.let { listOf(BaseContribution(SourceKind.WEAPON, it.name, it.attackSpeed)) }
        ?: classBase(player, player.baseStats.attackSpeed)

    // ---- notes ---------------------------------------------------------------------

    private fun damageNotes(player: PlayerState): List<String> {
        val typed = combined(player).filter { it.modifier.stat == Stat.DAMAGE && it.modifier.damageTypeId != null }
        val conversions = profile.traits(player).conversions
        return typed.map { "At the hit: ${it.modifier.describe()} (${it.label})" } +
            conversions.map { "Converts ${percent(it.share)} of ${it.fromDamageTypeId?.let { id -> content.damageType(id).name } ?: "all damage"} to ${content.damageType(it.toDamageTypeId).name}" } +
            "Skills hit for attack power × their power"
    }

    private fun describe(condition: Condition): String = when (condition) {
        Condition.FullLife -> "at full life"
        is Condition.LowLife -> "at or below ${percent(condition.threshold)} life"
        is Condition.TargetHas -> "against ${condition.statusOrTag}"
        is Condition.SelfHas -> "while ${condition.statusOrTag}"
        is Condition.SkillTagged -> "with ${condition.tag} skills"
        is Condition.Per -> "per ${condition.per} ${condition.attribute.name.lowercase()}"
    }

    private fun breakdown(query: StatQuery, layers: List<BreakdownLayer>, notes: List<String> = emptyList(), title: String = query.stat.label): StatBreakdown {
        val value = layers.lastOrNull()?.result ?: 0f
        return StatBreakdown(title, layers, value, display(query.stat, value), notes)
    }

    private operator fun StatBreakdown.plus(note: String) = copy(notes = notes + note)

    private operator fun StatBreakdown.plus(more: List<String>) = copy(notes = notes + more)

    private fun display(stat: ExplainedStat, value: Float): String = when (stat) {
        ExplainedStat.CRIT_CHANCE, ExplainedStat.BLOCK, ExplainedStat.LIFE_STEAL, ExplainedStat.RESISTANCE -> percent(value)
        ExplainedStat.MOVE_SPEED -> percent(value)
        ExplainedStat.CRIT_MULTIPLIER, ExplainedStat.SKILL_POWER -> "×" + "%.2f".format(value)
        ExplainedStat.ATTACK_SPEED -> "%.2f/s".format(value)
        else -> value.roundToInt().toString()
    }

    private fun percent(share: Float): String {
        val tenths = kotlin.math.abs((share * 1000f).roundToInt())
        val sign = if (share < 0f && tenths != 0) "-" else ""
        return if (tenths % 10 == 0) "$sign${tenths / 10}%" else "$sign${tenths / 10}.${tenths % 10}%"
    }

    private fun kotlin.reflect.KProperty1<CombatStats, Int>.float(): (CombatStats) -> Float = { get(it).toFloat() }

    private companion object {
        /** Matches the sheet's own floor under attack speed. */
        const val MIN_ATTACK_SPEED = 0.1f

        /** What armour is measured against in the note, and evasion's attacker. */
        const val ARMOUR_PROBE = 100f
        const val ACCURACY_PROBE = CombatStats.DEFAULT_ACCURACY.toFloat()
    }
}
