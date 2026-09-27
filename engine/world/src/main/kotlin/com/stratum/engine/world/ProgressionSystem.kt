package com.stratum.engine.world

import com.stratum.core.domain.actor.Progression
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.difficulty.Difficulty
import com.stratum.core.domain.difficulty.Waystone
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.passive.PassiveBuild
import com.stratum.core.domain.passive.PassiveTree
import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.stats.StatSheet
import com.stratum.core.domain.world.WorldPoint
import com.stratum.core.domain.world.WorldRules
import kotlin.math.roundToInt

/** The character's growth as a session offers it; see [ProgressionSystem]. */
interface SessionProgression {
    /** The tree characters grow on in this world, or null when it has no combat. */
    val passiveTree: PassiveTree?

    /** The player's allocation on [passiveTree]. */
    val passiveBuild: PassiveBuild?

    /** Takes a node, and the path to it when it is not adjacent, if the points are there. */
    fun allocatePassive(nodeId: String): PassiveResult

    /** Gives a node back for free, when nothing else taken depends on it. */
    fun refundPassive(nodeId: String): PassiveResult

    /** Takes a carried waystone out of the pouch to open its world, or null when it is not held. */
    fun takeWaystone(waystoneId: String): Waystone?
}

/**
 * How the character grows: experience and levels, the passive tree, world
 * tiers opened by felling champions, waystones, and what a kill here pays.
 *
 * Everything that outlives the world is decided here, which is why
 * [heroSave] lives here too: what this part changes is what a save keeps.
 */
internal class ProgressionSystem(
    private val state: SessionState,
    content: AssembledContent,
    rules: WorldRules,
    private val difficulty: Difficulty,
    private val cues: SessionCues,
    private val insertOf: (String) -> InsertDefinition?,
    /** Where the player's ceilings are worked out: refilled on a level, held under them when the tree changes. */
    private val profile: PlayerProfile,
) : SessionProgression {
    private val passives = PassiveProgress(content.passiveTree)
    private var player: PlayerState
        get() = state.player
        set(value) {
            state.player = value
        }

    /** The world rules' loot and experience dials, as the modifiers they are. */
    private val ruleRewards = listOfNotNull(
        StatModifier(Stat.ITEM_QUANTITY, ModifierKind.MORE, rules.lootMultiplier - 1f).takeIf { rules.lootMultiplier != 1f },
        StatModifier(Stat.EXPERIENCE_GAIN, ModifierKind.MORE, rules.experienceMultiplier - 1f).takeIf { rules.experienceMultiplier != 1f },
    )

    override val passiveTree: PassiveTree? = content.passiveTree

    override val passiveBuild: PassiveBuild? get() = passives.buildFor(player)

    /** The build and the world's rewards together: what a kill here pays this character. */
    val earnings: StatSheet get() = player.sheet(insertOf) + difficulty.rewards.modifiers + ruleRewards

    /** A character arriving in this world, checked against the tree loaded now. */
    fun settle(arriving: PlayerState): PlayerState = passives.settle(arriving)

    override fun allocatePassive(nodeId: String): PassiveResult {
        val (updated, result) = passives.allocate(player, nodeId)
        player = profile.carried(player, updated)
        if (result is PassiveResult.Allocated) cues.passiveTaken(result.nodes.last().name, player.position)
        return result
    }

    override fun refundPassive(nodeId: String): PassiveResult = passives.refund(player, nodeId).also { player = profile.carried(player, it.first) }.second

    /**
     * Experience for a kill. A level restores the character, which is what
     * makes pushing one more fight at low health a real decision rather than
     * a mistake.
     */
    fun award(amount: Int) {
        if (amount <= 0) return
        val result = Progression.apply(player.level, player.experience, (amount * earnings.multiplier(Stat.EXPERIENCE_GAIN)).roundToInt())
        player = player.copy(level = result.level, experience = result.experience)
        if (!result.leveledUp) return
        player = profile.restored(player)
        cues.levelUp(result.level, player.position)
    }

    /**
     * A champion or boss falling at the hardest tier this character has
     * reached opens the next one. The endgame is a ladder with no top rung.
     */
    fun conquer(at: WorldPoint) {
        if (difficulty.tier < player.highestTier) return
        player = player.copy(highestTier = difficulty.tier + 1)
        cues.tierOpened(player.highestTier, at)
    }

    override fun takeWaystone(waystoneId: String): Waystone? {
        val waystone = player.waystones.firstOrNull { it.id == waystoneId } ?: return null
        player = player.copy(waystones = player.waystones - waystone)
        return waystone
    }

    fun heroSave(id: String, savedAt: Long): HeroSave = HeroSave.of(player, id, savedAt)

    /** Currency, supports and waystones go straight into the pouch. */
    fun pocket(valuable: Valuable, at: WorldPoint) {
        player = when (valuable) {
            is Valuable.Currency -> player.withCurrency(valuable.definition.id)
            is Valuable.Support -> player.withSupport(valuable.definition.id)
            is Valuable.Key -> player.copy(waystones = player.waystones + valuable.waystone)
        }
        cues.valuableTaken(valuable.name, at)
    }
}
