package com.stratum.engine.world

import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.survival.ConsumableDefinition
import com.stratum.core.domain.survival.Environment
import kotlin.random.Random

/** The body's needs as a session offers them; see [SurvivalFacade]. */
interface SessionSurvival {
    /** Whether this world's rules and packs make the body's needs matter. */
    val survivalActive: Boolean

    /** The surroundings as the body last felt them: night, a roof, a fire. */
    val surroundings: Environment

    /** Food and drink the player is carrying, with counts. */
    val heldFood: List<Held<ConsumableDefinition>>

    /** Every recipe, and whether it can be made here and now. */
    val recipeOptions: List<RecipeOption>

    val canDrink: Boolean

    fun consume(itemId: String): SurvivalResult

    fun drink(): SurvivalResult

    fun make(recipeId: String): SurvivalResult
}

/**
 * The body's needs as the session applies them: eating, drinking, making
 * things at a station, and the penalties a hungry, cold body fights with.
 *
 * With survival off by the world's rules, or no needs in the loaded packs,
 * every call here is a check of one flag: [SurvivalSystem] returns at once,
 * and nothing is scanned or drained.
 */
internal class SurvivalFacade(
    private val state: SessionState,
    private val content: AssembledContent,
    private val survival: SurvivalSystem,
    /** The player's life ceiling, from the one place it is worked out. */
    private val maxHealth: (PlayerState) -> Int,
) : SessionSurvival {
    override val survivalActive: Boolean get() = survival.active

    override val surroundings: Environment get() = survival.surroundings

    override val heldFood: List<Held<ConsumableDefinition>>
        get() = content.consumables.mapNotNull { food -> state.player.countOf(food.id).takeIf { it > 0 }?.let { Held(food, it) } }

    override val recipeOptions: List<RecipeOption> get() = survival.options(state.player)

    override val canDrink: Boolean get() = survival.active && survival.waterNear(state.player.blockPos)

    override fun consume(itemId: String): SurvivalResult = survival.consume(state.player, itemId, maxHealth(state.player)).also { state.player = it.first }.second

    override fun drink(): SurvivalResult = survival.drink(state.player).also { state.player = it.first }.second

    override fun make(recipeId: String): SurvivalResult = survival.make(state.player, recipeId).also { state.player = it.first }.second

    fun modifiers(): List<StatModifier> = survival.modifiers(state.player)

    fun advance(deltaSeconds: Float, clock: WorldClock, biome: BiomeDefinition) {
        if (survival.active) state.player = survival.advance(state.player, deltaSeconds, clock, biome, maxHealth(state.player))
    }

    /** Meat from a kill, when the body has needs to feed. */
    fun carcass(random: Random) {
        survival.carcass(random)?.let { state.player = state.player.withItem(it) }
    }

    fun clear() = survival.clear()
}
