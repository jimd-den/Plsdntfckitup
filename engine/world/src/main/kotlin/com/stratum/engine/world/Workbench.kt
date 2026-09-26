package com.stratum.engine.world

import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.crafting.CurrencyDefinition
import com.stratum.core.domain.crafting.StandardCrafting
import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.session.PlayerState.Companion.adding
import com.stratum.core.domain.stats.StatSheet
import com.stratum.core.domain.stats.tune
import kotlin.random.Random

/** What happened when the player linked or unlinked a support. */
sealed interface SupportResult {
    data class Linked(val skill: SkillDefinition, val support: SupportDefinition) : SupportResult

    data class Unlinked(val skill: SkillDefinition, val support: SupportDefinition) : SupportResult

    data object UnknownSkill : SupportResult

    data object UnknownSupport : SupportResult

    data object NoneHeld : SupportResult

    data object AlreadyLinked : SupportResult

    data object SkillFull : SupportResult

    data object NotLinked : SupportResult
}

/** A stack held in the pouch, resolved for display. */
data class Held<T>(val definition: T, val count: Int)

/**
 * The player's crafting: currency spent on gear, and supports linked to
 * skills. Rules only; the session owns the player it hands in and out.
 */
internal class Workbench(private val content: AssembledContent, private val crafter: ItemCrafter) {

    /** Spends one [currencyId] on the item. A currency that would do nothing is not spent. */
    fun craft(player: PlayerState, instanceId: String, currencyId: String, random: Random): Pair<PlayerState, CraftResult> {
        val currency = content.currency(currencyId) ?: return player to CraftResult.NoSuchCurrency
        if (player.currencyCount(currencyId) <= 0) return player to CraftResult.NoneHeld
        val item = player.itemById(instanceId) ?: return player to CraftResult.NoSuchItem
        val crafted = crafter.apply(item, currency.effect, random).getOrElse { return player to CraftResult.NoEffect(it.message.orEmpty()) }
        val updated = player.replacing(crafted).withCurrency(currencyId, -1)
        return updated.copy(health = updated.health.coerceAtMost(updated.maxHealthWithGear)) to CraftResult.Crafted(item, crafted, currency)
    }

    fun link(player: PlayerState, skillId: String, supportId: String): Pair<PlayerState, SupportResult> {
        val skill = content.skill(skillId)?.takeIf { skillId in player.skillIds } ?: return player to SupportResult.UnknownSkill
        val support = content.support(supportId) ?: return player to SupportResult.UnknownSupport
        if (player.supportCount(supportId) <= 0) return player to SupportResult.NoneHeld
        val linked = player.supports[skillId].orEmpty()
        if (supportId in linked) return player to SupportResult.AlreadyLinked
        if (linked.size >= StandardCrafting.MAX_SUPPORTS_PER_SKILL) return player to SupportResult.SkillFull
        val updated = player.copy(
            supports = player.supports + (skillId to linked + supportId),
            supportBag = player.supportBag.adding(supportId, -1),
        )
        return updated to SupportResult.Linked(skill, support)
    }

    /** Takes a support back out, intact, into the pouch. */
    fun unlink(player: PlayerState, skillId: String, supportId: String): Pair<PlayerState, SupportResult> {
        val skill = content.skill(skillId) ?: return player to SupportResult.UnknownSkill
        val linked = player.supports[skillId].orEmpty()
        if (supportId !in linked) return player to SupportResult.NotLinked
        val remaining = linked - supportId
        val updated = player.copy(
            supports = if (remaining.isEmpty()) player.supports - skillId else player.supports + (skillId to remaining),
            supportBag = player.supportBag.adding(supportId, 1),
        )
        val support = content.support(supportId) ?: SupportDefinition(supportId, supportId)
        return updated to SupportResult.Unlinked(skill, support)
    }

    /**
     * [skill] as [player] casts it: the build's modifiers and the skill's
     * own supports together, so a "more" from a support and an "increased"
     * from the tree combine exactly as they read.
     */
    fun tuned(player: PlayerState, skill: SkillDefinition): SkillDefinition {
        val supports = linkedTo(player, skill.id)
        val tuned = StatSheet(player.build.modifiers + supports.flatMap { it.modifiers }).tune(skill)
        val conversion = supports.lastOrNull { it.convertsToDamageTypeId != null }?.convertsToDamageTypeId
        return if (conversion != null) tuned.copy(damageTypeId = conversion) else tuned
    }

    fun linkedTo(player: PlayerState, skillId: String): List<SupportDefinition> =
        player.supports[skillId].orEmpty().mapNotNull(content::support)

    fun heldCurrency(player: PlayerState): List<Held<CurrencyDefinition>> =
        content.currencies.mapNotNull { currency -> player.currencyCount(currency.id).takeIf { it > 0 }?.let { Held(currency, it) } }

    fun heldSupports(player: PlayerState): List<Held<SupportDefinition>> =
        content.supports.mapNotNull { support -> player.supportCount(support.id).takeIf { it > 0 }?.let { Held(support, it) } }
}
