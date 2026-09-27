package com.stratum.engine.world

import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.survival.ConsumableDefinition
import com.stratum.core.domain.survival.Environment
import com.stratum.core.domain.survival.RecipeDefinition
import com.stratum.core.domain.survival.Recipes
import com.stratum.core.domain.survival.StandardSurvival
import com.stratum.core.domain.survival.Survival
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.SurvivalMode
import com.stratum.core.domain.world.World
import kotlin.math.floor
import kotlin.random.Random

/** Dawn to dawn. The session's one clock, so the light, the cold and the monsters agree on what time it is. */
class WorldClock(private val dayLengthSeconds: Float, startFraction: Float = MORNING) {
    var elapsedSeconds: Float = startFraction * dayLengthSeconds
        private set

    /** 0 at dawn, 0.5 at dusk, wrapping -- the convention the art director reads. */
    val dayFraction: Float get() = (elapsedSeconds / dayLengthSeconds) % 1f

    val isNight: Boolean get() = dayFraction >= DUSK

    val day: Int get() = (elapsedSeconds / dayLengthSeconds).toInt() + 1

    fun advance(deltaSeconds: Float) {
        elapsedSeconds += deltaSeconds
    }

    companion object {
        const val MORNING = 0.1f
        const val DUSK = 0.5f
    }
}

/** What happened when the player tried to eat, drink or make something. */
sealed interface SurvivalResult {
    data class Consumed(val food: ConsumableDefinition) : SurvivalResult
    data class Drank(val amount: Float) : SurvivalResult
    data class Made(val recipe: RecipeDefinition, val count: Int) : SurvivalResult
    data object NoneHeld : SurvivalResult
    data object NoWaterNear : SurvivalResult
    data object NeedsStation : SurvivalResult
    data object MissingIngredients : SurvivalResult
    data object Unknown : SurvivalResult
}

/** A recipe as the camp panel shows it: can it be made here, now. */
data class RecipeOption(val recipe: RecipeDefinition, val haveIngredients: Boolean, val atStation: Boolean) {
    val craftable: Boolean get() = haveIngredients && atStation
}

/**
 * The body in the world: needs running down, the weather, food, water and
 * fire. Rules come from [Survival]; this part owns the state that is not the
 * player's -- what the surroundings were when last looked at, and what the
 * last meal is still doing.
 */
internal class SurvivalSystem(
    private val content: AssembledContent,
    private val mode: SurvivalMode,
    private val world: World,
    private val roomScanner: RoomScanner,
) {
    private var environment = Environment()
    private var sinceSensed = Float.MAX_VALUE
    private var healthDebt = 0f
    private val effects = mutableListOf<Pair<List<StatModifier>, Float>>()

    val active: Boolean get() = mode != SurvivalMode.OFF && content.needs.isNotEmpty()

    /** The surroundings as last sensed, for the HUD: is it night, is there a roof, is there a fire. */
    val surroundings: Environment get() = environment

    /** Needs run down, the weather is felt, a harsh world takes its toll. */
    fun advance(player: PlayerState, deltaSeconds: Float, clock: WorldClock, biome: BiomeDefinition): PlayerState {
        if (!active || !player.isAlive) return player
        sinceSensed += deltaSeconds
        if (sinceSensed >= SENSE_EVERY) {
            environment = Environment(biome.temperature, clock.isNight, sheltered(player.blockPos), nearFire(player.blockPos))
            sinceSensed = 0f
        }
        effects.replaceAll { (modifiers, left) -> modifiers to left - deltaSeconds }
        effects.removeAll { it.second <= 0f }
        val needs = Survival.advanced(player.needs, content.needs, environment, deltaSeconds)
        healthDebt += Survival.healthLossPerSecond(needs, content.needs, mode, player.maxHealthWithGear) * deltaSeconds
        val loss = floor(healthDebt).toInt()
        healthDebt -= loss
        return player.copy(needs = needs).let { if (loss > 0) it.damaged(loss) else it }
    }

    /** Everything the body is doing to the stats: hunger's weakness, the tea's steadiness. */
    fun modifiers(player: PlayerState): List<StatModifier> =
        if (!active) emptyList() else Survival.penalties(player.needs, content.needs, mode) + effects.flatMap { it.first }

    fun consume(player: PlayerState, itemId: String): Pair<PlayerState, SurvivalResult> {
        val food = content.consumable(itemId) ?: return player to SurvivalResult.Unknown
        val spent = player.consuming(itemId) ?: return player to SurvivalResult.NoneHeld
        if (food.modifiers.isNotEmpty() && food.durationSeconds > 0f) effects += food.modifiers to food.durationSeconds
        return spent.copy(needs = Survival.consumed(spent.needs, food)).healed(food.heals) to SurvivalResult.Consumed(food)
    }

    /** A drink from open water within reach. */
    fun drink(player: PlayerState): Pair<PlayerState, SurvivalResult> {
        if (!waterNear(player.blockPos)) return player to SurvivalResult.NoWaterNear
        val thirst = content.needs.firstOrNull { it.id == StandardSurvival.thirst.id } ?: content.needs.firstOrNull { it.name.equals("thirst", ignoreCase = true) }
            ?: return player to SurvivalResult.Unknown
        val needs = player.needs + (thirst.id to ((player.needs[thirst.id] ?: Survival.MAX) + StandardSurvival.DRINK).coerceAtMost(Survival.MAX))
        return player.copy(needs = needs) to SurvivalResult.Drank(StandardSurvival.DRINK)
    }

    fun make(player: PlayerState, recipeId: String): Pair<PlayerState, SurvivalResult> {
        val recipe = content.recipe(recipeId) ?: return player to SurvivalResult.Unknown
        if (!Survival.canCraft(recipe, player::countOf)) return player to SurvivalResult.MissingIngredients
        if (!atStation(recipe, player.blockPos)) return player to SurvivalResult.NeedsStation
        val spent = recipe.inputs.entries.fold(player) { held, (id, count) -> (1..count).fold(held) { p, _ -> p.consuming(id) ?: p } }
        return spent.withItem(recipe.outputId, recipe.outputCount) to SurvivalResult.Made(recipe, recipe.outputCount)
    }

    fun options(player: PlayerState): List<RecipeOption> =
        content.recipes.map { RecipeOption(it, Survival.canCraft(it, player::countOf), atStation(it, player.blockPos)) }

    /** What harvesting [blockId] yields besides the block, rolled per rule. */
    fun forage(blockId: String, material: BlockMaterial, random: Random): List<String> =
        if (!active) emptyList()
        else content.forageRules.filter { it.matches(blockId, material) && random.nextFloat() < it.chance }.map { it.itemId }

    /** A kill that leaves something to eat, when the standard food is loaded. */
    fun carcass(random: Random): String? =
        StandardSurvival.rawMeat.id.takeIf { active && content.consumable(it) != null && random.nextFloat() < StandardSurvival.MEAT_CHANCE }

    fun waterNear(at: BlockPos): Boolean = anyNear(at, WATER_REACH) { it.material == BlockMaterial.LIQUID }

    fun nearFire(at: BlockPos): Boolean = anyNear(at, FIRE_REACH) { it.lightEmission >= Survival.FIRE_LIGHT }

    private fun atStation(recipe: RecipeDefinition, at: BlockPos): Boolean = when (val station = recipe.station) {
        null -> true
        Recipes.FIRE -> nearFire(at)
        else -> anyNear(at, FIRE_REACH) { it.id == station }
    }

    private fun sheltered(at: BlockPos): Boolean = roomScanner.scan(at) is RoomScan.Enclosed

    private fun anyNear(at: BlockPos, reach: Int, test: (com.stratum.core.domain.world.BlockType) -> Boolean): Boolean {
        for (z in at.z - 1..at.z + 2) for (y in at.y - reach..at.y + reach) for (x in at.x - reach..at.x + reach) {
            if (test(world.blockAt(BlockPos(x, y, z)))) return true
        }
        return false
    }

    fun clear() {
        effects.clear()
        healthDebt = 0f
        sinceSensed = Float.MAX_VALUE
    }

    private companion object {
        const val SENSE_EVERY = 1.5f
        const val FIRE_REACH = 3
        const val WATER_REACH = 2
    }
}
