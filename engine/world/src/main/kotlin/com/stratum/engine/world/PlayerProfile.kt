package com.stratum.engine.world

import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillTags
import com.stratum.core.domain.combat.Attribute
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.CombatTraits
import com.stratum.core.domain.combat.ConditionContext
import com.stratum.core.domain.combat.HitAttacker
import com.stratum.core.domain.combat.HitDefender
import com.stratum.core.domain.combat.KeyedTrigger
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.stats.StatSheet
import com.stratum.core.domain.status.StatusSet
import kotlin.math.roundToInt

/**
 * The player as combat sees them at one instant: stats with every source
 * counted in -- gear, passives, traits, statuses, and the conditional
 * modifiers that hold right now against this target.
 *
 * Stats are recomputed from the build rather than layered on top of the
 * resolved numbers, so a conditional "20% increased damage" adds into the
 * same increased sum as a passive's, exactly as its tooltip reads.
 */
internal class PlayerProfile(
    private val content: AssembledContent,
    /** Boons and banes from tabletop checks, which change the resolved stats for a while. */
    private val table: TableState,
    /** Modifiers from outside the build, such as survival's. */
    private val environment: (PlayerState) -> List<StatModifier>,
    private val supports: (PlayerState, String) -> List<SupportDefinition>,
) {
    private val damageTypeIds = content.damageTypes.map { it.id }

    /** The player's stats with [extra] modifiers folded into the build: gear, inserts, needs and boons included. */
    fun statsWith(player: PlayerState, extra: List<StatModifier>): CombatStats {
        val built = if (extra.isEmpty()) player else player.copy(build = player.build + extra)
        return table.applyTo(StatSheet(environment(player)).applyTo(built.combatStatsWith(content::insert, damageTypeIds), damageTypeIds))
    }
    private var cachedKey: Any? = null
    private var cachedTraits: CombatTraits = CombatTraits.NONE

    /** Traits from the class, every passive node taken, and the rules the gear worn breaks. */
    fun traits(player: PlayerState): CombatTraits {
        val flags = player.buildFlags
        val key = Triple(player.heroClassId, player.passives, flags)
        if (key == cachedKey) return cachedTraits
        val hero = content.heroClasses.firstOrNull { it.id == player.heroClassId }
        val nodes = content.passiveTree?.nodes.orEmpty().filter { it.id in player.passives }
        val ids = hero?.traitIds.orEmpty() + nodes.flatMap { it.traitIds }
        cachedTraits = CombatTraits.of(ids.distinct().mapNotNull(content::trait)) + CombatTraits.of(flags)
        cachedKey = key
        return cachedTraits
    }

    /** Every trigger the player carries: their traits', and each linked support that makes its skill cast itself. */
    fun triggers(player: PlayerState): List<KeyedTrigger> = traits(player).triggers + player.skillIds.flatMap { skillId ->
        supports(player, skillId).mapNotNull { support ->
            support.trigger?.let { KeyedTrigger("support:$skillId:${support.id}", it.copy(castSkillId = it.castSkillId ?: skillId)) }
        } + content.skill(skillId)?.grants.orEmpty().mapIndexed { i, granted -> KeyedTrigger("grant:$skillId:$i", granted) }
    }

    fun attributes(player: PlayerState): Map<Attribute, Int> {
        val hero = content.heroClasses.firstOrNull { it.id == player.heroClassId }
        return mapOf(
            Attribute.STRENGTH to (hero?.strength ?: 0),
            Attribute.AGILITY to (hero?.agility ?: 0),
            Attribute.INSIGHT to (hero?.insight ?: 0),
            Attribute.LEVEL to player.level,
        )
    }

    /** The modifiers that hold now, beyond the stored build. */
    fun extras(player: PlayerState, own: StatusSet, target: StatusSet?, skill: SkillDefinition?): List<StatModifier> {
        val traits = traits(player)
        val context = ConditionContext(
            lifeFraction = if (maxHealth(player) <= 0) 0f else player.health.toFloat() / maxHealth(player),
            selfStatuses = own,
            targetStatuses = target,
            attributes = attributes(player),
            skillTags = skill?.allTags.orEmpty(),
            book = content.statusBook,
        )
        return traits.modifiers + own.modifiers(content.statusBook) + traits.conditionalIn(context)
    }

    /** Passives, gear and set bonuses, then the environment and what holds right now. */
    fun sheet(player: PlayerState, extras: List<StatModifier>): StatSheet = baseSheet(player) + environment(player) + extras

    // Passives and gear change a few times a minute; recovery and every hit
    // asked for them every tick, rebuilding the sheet from every item worn.
    // Both are immutable, so a new object is the only way either changes.
    private var sheetBuild: StatSheet? = null
    private var sheetEquipment: Any? = null
    private var cachedSheet: StatSheet = StatSheet.EMPTY

    private fun baseSheet(player: PlayerState): StatSheet {
        if (player.build !== sheetBuild || player.equipment !== sheetEquipment) {
            cachedSheet = player.sheet(content::insert)
            sheetBuild = player.build
            sheetEquipment = player.equipment
        }
        return cachedSheet
    }

    fun attacker(player: PlayerState, own: StatusSet, target: StatusSet?, skill: SkillDefinition): HitAttacker {
        val extras = extras(player, own, target, skill)
        val traits = traits(player)
        return HitAttacker(
            stats = statsWith(player, extras),
            sheet = sheet(player, extras),
            conversions = traits.conversions + skill.conversions,
            extraDamage = traits.extraDamage,
            keystones = traits.keystones,
            evadable = skill.hasTag(SkillTags.ATTACK),
            id = WorldSession.PLAYER_ACTOR_ID,
        )
    }

    fun defender(player: PlayerState, own: StatusSet): HitDefender {
        val extras = extras(player, own, null, null)
        return HitDefender(statsWith(player, extras), sheet(player, extras), own)
    }

    /**
     * How much life the player can have: gear, inserts, passives, traits,
     * needs and boons, counted exactly as a hit counts them. The one answer
     * every part of the session asks -- regeneration, flasks, a level's
     * refill, the bar on screen -- so a keystone's extra life is never in the
     * fight but missing from the bar.
     */
    fun maxHealth(player: PlayerState): Int = statsWith(player, traits(player).modifiers).maxHealth

    /** The resource pool, from the same sources as [maxHealth]. */
    fun maxResource(player: PlayerState): Int =
        sheet(player, traits(player).modifiers).apply(Stat.MAX_RESOURCE, player.maxResource.toFloat()).roundToInt()

    /** The player refilled to both ceilings: a new character, a level gained, a revival. */
    fun restored(player: PlayerState): PlayerState = player.copy(health = maxHealth(player), resource = maxResource(player))

    /**
     * [after] with the life and resource [before] had, held under the new
     * ceilings. What changing gear or the build does to the bars: taking off
     * a life ring takes the life with it, putting one on does not heal.
     */
    fun carried(before: PlayerState, after: PlayerState): PlayerState =
        after.copy(health = before.health.coerceAtMost(maxHealth(after)), resource = before.resource.coerceAtMost(maxResource(after)))
}
