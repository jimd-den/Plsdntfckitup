package com.stratum.core.domain.strategy

import com.stratum.core.domain.world.BlockMaterial

/**
 * A material an outpost stockpiles: food, timber, stone, bronze -- or, in
 * someone's grimdark pack, promethium and faith. Filled by depositing what
 * the player carries and by what the outpost's structures produce.
 */
data class ResourceDefinition(
    val id: String,
    val name: String,
    val glyph: String = "▣",
    val color: Long = 0xFFB0BEC5,
    /** What depositing gives: item or block id to amount, e.g. a log is 2 timber. */
    val fromItems: Map<String, Int> = emptyMap(),
    /** Blocks of these materials count too, one resource each, so any world's stone is stone. */
    val fromMaterials: Set<BlockMaterial> = emptySet(),
)

/**
 * Something an outpost can build: a farm, a quarry, a barracks, a watchtower.
 * Built from the stockpile, running on workers, producing and consuming by
 * the minute. Abstract on purpose: the player builds walls with blocks and
 * builds an economy with a tap, and neither waits on the other.
 */
data class StructureDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val glyph: String = "⌂",
    val cost: Map<String, Int> = emptyMap(),
    /** Resource id to amount per minute, at full staffing. */
    val produces: Map<String, Float> = emptyMap(),
    /** Resource id to amount per minute, paid whatever the staffing. */
    val upkeep: Map<String, Float> = emptyMap(),
    /** People it houses. */
    val housing: Int = 0,
    /** People it needs to produce at full rate. */
    val workers: Int = 0,
    /** Added to the outpost's defence against raids. */
    val defense: Int = 0,
    /** Resource id to extra storage. */
    val storage: Map<String, Int> = emptyMap(),
    /** Structures that must already stand. */
    val requires: List<String> = emptyList(),
    val maxCount: Int = Int.MAX_VALUE,
)

/**
 * Someone an outpost can recruit: a soldier who garrisons it, or follows the
 * player into the field. Their body -- stats, speed, role -- is an actor
 * definition, the same kind monsters are made of.
 */
data class UnitDefinition(
    val id: String,
    val name: String,
    val actorId: String,
    val cost: Map<String, Int> = emptyMap(),
    /** Food eaten per minute while alive, wherever they are. */
    val upkeep: Map<String, Float> = emptyMap(),
    /** Structure that must stand to recruit them. */
    val requires: String? = null,
    /** Added to the outpost's defence while in its garrison. */
    val defense: Int = 5,
)

/** What a follower does when the player gives an order. */
enum class FollowerOrder(val label: String) {
    FOLLOW("Follow me"),
    HOLD("Hold here"),
    FIGHT("Fight freely"),
    RETURN("Return to the outpost"),
}

/** One outpost: where, what stands in it, what it holds, who guards it. */
data class Outpost(
    val id: String,
    val name: String,
    val centerX: Int,
    val centerY: Int,
    val radius: Int = DEFAULT_RADIUS,
    val structures: Map<String, Int> = emptyMap(),
    val stockpile: Map<String, Float> = emptyMap(),
    /** Recruited units waiting in the garrison, by unit id. */
    val garrison: Map<String, Int> = emptyMap(),
    /** Seconds until the next raid is due; negative when raids are off. */
    val raidIn: Float = FIRST_RAID_SECONDS,
    /** How many raids it has seen off, which makes the next one bigger. */
    val raidsSurvived: Int = 0,
) {
    fun count(structureId: String): Int = structures[structureId] ?: 0

    fun has(resourceId: String): Float = stockpile[resourceId] ?: 0f

    companion object {
        const val DEFAULT_RADIUS = 14
        const val FIRST_RAID_SECONDS = 420f
    }
}

/** How a raid on an unattended outpost went. */
data class RaidOutcome(
    val outpostId: String,
    val attackers: Int,
    val defended: Boolean,
    /** Resources the raiders carried off. */
    val lost: Map<String, Float>,
    /** Structures they burned. */
    val razed: List<String>,
)
