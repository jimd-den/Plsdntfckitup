package com.stratum.core.domain.survival

import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.SurvivalMode

/** How a need moves over time. */
enum class NeedKind {
    /** Runs down steadily and is refilled by eating or drinking: hunger, thirst. */
    DRAIN,

    /** Follows the surroundings toward a comfort level: warmth, which a fire, a roof or daylight restore. */
    CLIMATE,
}

/**
 * Something the body needs, as plugin data. A grimdark pack might add
 * "sanity" as a drain need refilled by prayer; a desert pack might make
 * thirst drain three times as fast.
 */
data class NeedDefinition(
    val id: String,
    val name: String,
    val glyph: String = "●",
    val color: Long = 0xFFD9A441,
    val kind: NeedKind = NeedKind.DRAIN,
    /** Points per real minute, out of [Survival.MAX]. For a climate need, how fast it moves toward comfort. */
    val drainPerMinute: Float = 3f,
    /** Below this, [lowModifiers] apply. */
    val lowBelow: Float = 30f,
    val lowModifiers: List<StatModifier> = emptyList(),
    /** In harsh worlds, the share of maximum health lost per minute while this need is empty. */
    val emptyHealthLossPerMinute: Float = 0.3f,
)

/** Something eaten or drunk: what it restores, heals and grants for a while. */
data class ConsumableDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val glyph: String = "🍖",
    val color: Long = 0xFFB0703A,
    /** Need id to points restored. */
    val restores: Map<String, Float> = emptyMap(),
    val heals: Int = 0,
    /** Granted for [durationSeconds]: a stew that warms, a draught that steadies the hand. */
    val modifiers: List<StatModifier> = emptyList(),
    val durationSeconds: Float = 0f,
)

/**
 * What harvesting a block can yield besides the block: berries from a bush,
 * roots from soil. Matches a block by id, or every block of a material.
 */
data class ForageRule(
    val itemId: String,
    val chance: Float,
    val blockId: String? = null,
    val material: BlockMaterial? = null,
) {
    init {
        require(blockId != null || material != null) { "A forage rule for '$itemId' must name a block or a material" }
        require(chance in 0f..1f) { "Forage chance $chance is not a share" }
    }

    fun matches(blockId: String, material: BlockMaterial): Boolean =
        (this.blockId == null || this.blockId == blockId) && (this.material == null || this.material == material)
}

/**
 * Turning things into other things: cooking, brewing, building kits.
 * [station] is where it can be done: null anywhere, [Recipes.FIRE] beside
 * anything burning, or a block id -- an anvil, an altar, a loom.
 */
data class RecipeDefinition(
    val id: String,
    val name: String,
    val inputs: Map<String, Int>,
    val outputId: String,
    val outputCount: Int = 1,
    val station: String? = null,
) {
    init {
        require(inputs.isNotEmpty() && inputs.values.all { it > 0 }) { "Recipe '$id' needs at least one input" }
        require(outputCount > 0) { "Recipe '$id' makes nothing" }
    }
}

object Recipes {
    /** A station meaning any fire, brazier or forge: any block giving off strong light. */
    const val FIRE = "stratum:fire"
}

/** What the surroundings are like, for a climate need. */
data class Environment(
    /** The region's temperature, 0 freezing to 1 hot. */
    val temperature: Float = 0.55f,
    val night: Boolean = false,
    val sheltered: Boolean = false,
    val nearFire: Boolean = false,
)

/**
 * The rules of the body, pure and small: how needs move, what running low
 * costs, what eating restores. The session owns the state and the clock;
 * this owns the arithmetic.
 */
object Survival {
    const val MAX = 100f

    /** Where warmth settles in [environment], 0..[MAX]. */
    fun comfort(environment: Environment): Float {
        var level = environment.temperature * MAX
        if (environment.night) level -= NIGHT_CHILL
        if (environment.sheltered) level += SHELTER_WARMTH
        if (environment.nearFire) level += FIRE_WARMTH
        return level.coerceIn(0f, MAX)
    }

    /** Every need after [seconds] in [environment]. Unknown needs are left alone; missing ones start full. */
    fun advanced(needs: Map<String, Float>, definitions: List<NeedDefinition>, environment: Environment, seconds: Float): Map<String, Float> =
        definitions.associate { need ->
            val current = needs[need.id] ?: MAX
            need.id to when (need.kind) {
                NeedKind.DRAIN -> (current - need.drainPerMinute * seconds / SECONDS_PER_MINUTE).coerceIn(0f, MAX)
                NeedKind.CLIMATE -> approach(current, comfort(environment), need.drainPerMinute * seconds / SECONDS_PER_MINUTE * if (environment.nearFire) FIRE_SPEED else 1f)
            }
        }

    /** What running low is costing right now. Nothing at all with survival off. */
    fun penalties(needs: Map<String, Float>, definitions: List<NeedDefinition>, mode: SurvivalMode): List<StatModifier> =
        if (mode == SurvivalMode.OFF) emptyList()
        else definitions.filter { (needs[it.id] ?: MAX) < it.lowBelow }.flatMap { it.lowModifiers }

    /** Health lost per second: only in harsh worlds, only for needs that have run out. */
    fun healthLossPerSecond(needs: Map<String, Float>, definitions: List<NeedDefinition>, mode: SurvivalMode, maxHealth: Int): Float =
        if (mode != SurvivalMode.HARSH) 0f
        else definitions.filter { (needs[it.id] ?: MAX) <= 0f }.sumOf { it.emptyHealthLossPerMinute.toDouble() }.toFloat() * maxHealth / SECONDS_PER_MINUTE

    /** Needs after eating or drinking [consumable]. */
    fun consumed(needs: Map<String, Float>, consumable: ConsumableDefinition): Map<String, Float> =
        needs + consumable.restores.mapValues { (id, amount) -> ((needs[id] ?: MAX) + amount).coerceIn(0f, MAX) }

    /** Whether [held] covers every input of [recipe]. */
    fun canCraft(recipe: RecipeDefinition, held: (String) -> Int): Boolean = recipe.inputs.all { (id, count) -> held(id) >= count }

    private fun approach(current: Float, target: Float, step: Float): Float =
        if (current < target) (current + step).coerceAtMost(target) else (current - step).coerceAtLeast(target)

    const val SECONDS_PER_MINUTE = 60f
    const val NIGHT_CHILL = 30f
    const val SHELTER_WARMTH = 25f
    const val FIRE_WARMTH = 50f
    /** Warming by a fire is quicker than cooling in the dark. */
    const val FIRE_SPEED = 3f
    /** Light at or above this makes a block a fire, for warmth and for [Recipes.FIRE]. */
    const val FIRE_LIGHT = 10
}

/**
 * The needs, food and recipes the engine uses when a pack brings none of its
 * own and the world's rules turn survival on. Built from things every world
 * has -- plants, water, fire, and monsters -- so it works in any pack.
 */
object StandardSurvival {
    private fun less(stat: Stat, share: Float) = StatModifier(stat, ModifierKind.MORE, -share)

    val hunger = NeedDefinition(
        "stratum:hunger", "Hunger", "🍖", 0xFFD98841, drainPerMinute = 2.5f,
        lowModifiers = listOf(less(Stat.DAMAGE, 0.15f), less(Stat.ATTACK_SPEED, 0.1f)),
    )
    val thirst = NeedDefinition(
        "stratum:thirst", "Thirst", "💧", 0xFF4FC3F7, drainPerMinute = 4f,
        lowModifiers = listOf(less(Stat.COOLDOWN_RECOVERY, 0.2f), less(Stat.MAX_RESOURCE, 0.2f)),
    )
    val warmth = NeedDefinition(
        "stratum:warmth", "Warmth", "🔥", 0xFFFF7043, kind = NeedKind.CLIMATE, drainPerMinute = 12f,
        lowModifiers = listOf(less(Stat.MOVE_SPEED, 0.2f), less(Stat.ATTACK_SPEED, 0.1f)), emptyHealthLossPerMinute = 0.2f,
    )
    val needs = listOf(hunger, thirst, warmth)

    val rawMeat = ConsumableDefinition("stratum:raw_meat", "Raw Meat", "Better cooked.", "🥩", 0xFFC2554A, mapOf(hunger.id to 12f))
    val cookedMeat = ConsumableDefinition(
        "stratum:cooked_meat", "Roast Meat", "Hot, and a meal.", "🍖", 0xFFB0703A,
        mapOf(hunger.id to 40f, warmth.id to 10f), heals = 15,
    )
    val berries = ConsumableDefinition("stratum:berries", "Wild Berries", "A handful.", "🍇", 0xFF7E57C2, mapOf(hunger.id to 8f, thirst.id to 5f))
    val tea = ConsumableDefinition(
        "stratum:herb_tea", "Herb Tea", "Warms from the inside and sharpens the eye.", "🍵", 0xFF8BC34A,
        mapOf(thirst.id to 35f, warmth.id to 25f), modifiers = listOf(StatModifier(Stat.CRIT_CHANCE, ModifierKind.FLAT, 0.03f)), durationSeconds = 120f,
    )
    val consumables = listOf(rawMeat, cookedMeat, berries, tea)

    val forage = listOf(ForageRule(berries.id, 0.3f, material = BlockMaterial.FOLIAGE))

    val recipes = listOf(
        RecipeDefinition("stratum:cook_meat", "Roast meat", mapOf(rawMeat.id to 1), cookedMeat.id, station = Recipes.FIRE),
        RecipeDefinition("stratum:brew_tea", "Brew herb tea", mapOf(berries.id to 2), tea.id, station = Recipes.FIRE),
    )

    /** Share of kills that leave something to eat. */
    const val MEAT_CHANCE = 0.3f
    /** Thirst restored by one drink from open water. */
    const val DRINK = 30f
}
