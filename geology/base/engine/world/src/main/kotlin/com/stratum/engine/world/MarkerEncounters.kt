package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.world.WorldMarker
import com.stratum.core.domain.world.WorldMarkerKind
import kotlin.random.Random

/** What a woken marker becomes. */
internal sealed interface MarkerEncounter {
    val marker: WorldMarker

    /** A monster at a rank the data chose: its definition's own, or boss for a boss marker. */
    data class Monster(override val marker: WorldMarker, val definition: EnemyDefinition, val rank: EnemyRank) : MarkerEncounter

    /** A chest: one item rolled at chest quality. */
    data class Chest(override val marker: WorldMarker) : MarkerEncounter {
        val floor: ItemRarity get() = CHEST_FLOOR
    }

    companion object {
        /** The least a chest holds. A chest that can be a common is not worth the corridor. */
        val CHEST_FLOOR = ItemRarity.RARE

        /** A chest's rarity bonus on top of its floor, as an elite's kill would give. */
        const val CHEST_RARITY_BONUS = 0.5f
    }
}

/**
 * Who waits at a generated world's markers.
 *
 * A marker naming a monster gets it, matched as loosely as a level author's
 * markers are. One naming nothing, or something no loaded pack has, gets one
 * of its structure's own monsters, and failing that one that lives in the
 * region -- so a dungeon a pack described with a monster list is peopled by
 * that list even where the generator left the choice open. A boss marker
 * names its structure's boss when it names nothing itself, and whatever it
 * spawns fights at boss rank with its phases. Entrances and points of
 * interest are not encounters.
 */
internal class MarkerEncounters(private val content: AssembledContent, private val regionAt: (Int, Int) -> String?) {

    private val dungeons = content.structureTemplates.mapNotNull { t -> t.dungeon?.let { t.id to it } }.toMap()

    fun resolve(marker: WorldMarker, random: Random): MarkerEncounter? = when (marker.kind) {
        WorldMarkerKind.ENEMY_SPAWN -> monsterFor(marker, random)?.let { MarkerEncounter.Monster(marker, it, it.rank) }
        WorldMarkerKind.BOSS -> bossFor(marker)?.let { MarkerEncounter.Monster(marker, it, EnemyRank.BOSS) }
        WorldMarkerKind.LOOT -> MarkerEncounter.Chest(marker)
        WorldMarkerKind.ENTRANCE, WorldMarkerKind.POINT_OF_INTEREST -> null
    }

    private fun monsterFor(marker: WorldMarker, random: Random): EnemyDefinition? =
        MapEncounters.named(marker.refId, content.enemies)
            ?: dungeons[marker.sourceId]?.enemyIds.orEmpty().mapNotNull { MapEncounters.named(it, content.enemies) }.randomOrNull(random)
            ?: weighted(local(marker).filterNot(::isBoss), random)

    /**
     * One of [candidates] by its spawn weight, as the director picks: a rare
     * monster stays rare at a spawn point too. Picking uniformly made a
     * region's one rare beast as common as its wolves.
     */
    private fun weighted(candidates: List<EnemyDefinition>, random: Random): EnemyDefinition? {
        val total = candidates.sumOf { it.spawnWeight.coerceAtLeast(0) }
        if (total <= 0) return candidates.randomOrNull(random)
        var roll = random.nextInt(total)
        return candidates.firstOrNull { roll -= it.spawnWeight.coerceAtLeast(0); roll < 0 }
    }

    /** Bosses wait at boss markers; an ordinary spawn point in the open never holds one. */
    private fun isBoss(definition: EnemyDefinition): Boolean = definition.rank == EnemyRank.BOSS || definition.phases.isNotEmpty()

    private fun bossFor(marker: WorldMarker): EnemyDefinition? =
        MapEncounters.named(marker.refId, content.enemies)
            ?: MapEncounters.named(dungeons[marker.sourceId]?.bossId, content.enemies)
            // With no boss named anywhere, the toughest thing living here takes the room.
            ?: local(marker).maxByOrNull { it.baseStats.maxHealth }

    /** Monsters of the marker's region that may appear on their own; garrison troops and followers are not. */
    private fun local(marker: WorldMarker): List<EnemyDefinition> {
        val region = regionAt(marker.x, marker.y)
        return content.enemies.filter { it.spawnWeight > 0 && (region == null || it.spawnBiomeIds.isEmpty() || region in it.spawnBiomeIds) }
    }

    companion object {
        /**
         * The dice for one marker, from the seed and the marker alone, so the
         * same dungeon holds the same monsters whichever way the player came.
         */
        fun diceFor(seed: Long, marker: WorldMarker): Random {
            var h = seed
            h = h * 31 + marker.x
            h = h * 31 + marker.y
            h = h * 31 + marker.z
            h = h * 31 + marker.kind.ordinal
            h = h * 31 + marker.sourceId.hashCode()
            return Random(h)
        }
    }
}
