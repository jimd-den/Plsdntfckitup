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
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.stats.StatSheet
import com.stratum.core.domain.status.StatusSet

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
    /** The player's stats with [extra] modifiers folded into the build: gear, inserts, boons and survival included. */
    private val statsWith: (PlayerState, List<StatModifier>) -> CombatStats,
    /** Modifiers from outside the build, such as survival's, for the extended stats. */
    private val environment: (PlayerState) -> List<StatModifier>,
    private val supports: (PlayerState, String) -> List<SupportDefinition>,
) {
    private var cachedKey: Any? = null
    private var cachedTraits: CombatTraits = CombatTraits.NONE

    /** Traits from the class and from every passive node taken. */
    fun traits(player: PlayerState): CombatTraits {
        val key = player.heroClassId to player.passives
        if (key == cachedKey) return cachedTraits
        val hero = content.heroClasses.firstOrNull { it.id == player.heroClassId }
        val nodes = content.passiveTree?.nodes.orEmpty().filter { it.id in player.passives }
        val ids = hero?.traitIds.orEmpty() + nodes.flatMap { it.traitIds }
        cachedTraits = CombatTraits.of(ids.distinct().mapNotNull(content::trait))
        cachedKey = key
        return cachedTraits
    }

    /** Every trigger the player carries: their traits', and each linked support that makes its skill cast itself. */
    fun triggers(player: PlayerState): List<KeyedTrigger> = traits(player).triggers + player.skillIds.flatMap { skillId ->
        supports(player, skillId).mapNotNull { support ->
            support.trigger?.let { KeyedTrigger("support:$skillId:${support.id}", it.copy(castSkillId = it.castSkillId ?: skillId)) }
        }
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

    fun sheet(player: PlayerState, extras: List<StatModifier>): StatSheet = player.build + environment(player) + extras

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

    fun maxHealth(player: PlayerState): Int = statsWith(player, emptyList()).maxHealth
}
