package com.stratum.core.domain.session

import com.stratum.core.domain.difficulty.Waystone
import com.stratum.core.domain.item.ItemInstance

/**
 * A character kept between worlds: who they became, not where they stood.
 *
 * Levels, passives, gear, and the pouch go with the hero into every new
 * world; the world itself does not, which is what lets a character carry an
 * hour's work into a fresh seed -- the Diablo loop, rather than a roguelike
 * one where every descent starts naked.
 */
data class HeroSave(
    val id: String,
    val heroClassId: String,
    val level: Int = 1,
    val experience: Int = 0,
    val passives: Set<String> = emptySet(),
    val equippedWeapon: ItemInstance? = null,
    val bag: List<ItemInstance> = emptyList(),
    val insertBag: Map<String, Int> = emptyMap(),
    /** Crafting currency by id. */
    val currency: Map<String, Int> = emptyMap(),
    /** Support gems held loose, by id. */
    val supportBag: Map<String, Int> = emptyMap(),
    /** Support gems linked to each skill, by skill id. */
    val supports: Map<String, List<String>> = emptyMap(),
    val waystones: List<Waystone> = emptyList(),
    /** The hardest world tier this hero has unlocked. */
    val highestTier: Int = 0,
    /** Epoch millis, for sorting a roster. */
    val savedAt: Long = 0L,
) {
    /**
     * [player] -- freshly spawned in a new world -- carrying this hero. The
     * starting weapon is kept only when the save held none.
     */
    fun restoreOnto(player: PlayerState): PlayerState = player.copy(
        level = level,
        experience = experience,
        passives = passives,
        equippedWeapon = equippedWeapon ?: player.equippedWeapon,
        bag = bag,
        insertBag = insertBag,
        currency = currency,
        supportBag = supportBag,
        supports = supports,
        waystones = waystones,
        highestTier = highestTier,
    )

    companion object {
        fun of(player: PlayerState, id: String = player.heroClassId, savedAt: Long = 0L) = HeroSave(
            id = id,
            heroClassId = player.heroClassId,
            level = player.level,
            experience = player.experience,
            passives = player.passives,
            equippedWeapon = player.equippedWeapon,
            bag = player.bag,
            insertBag = player.insertBag,
            currency = player.currency,
            supportBag = player.supportBag,
            supports = player.supports,
            waystones = player.waystones,
            highestTier = player.highestTier,
            savedAt = savedAt,
        )
    }
}

/** Where heroes are kept. Implemented over files in `:core:data`. */
interface HeroSaveRepository {
    fun all(): List<HeroSave>

    fun load(id: String): HeroSave?

    fun save(hero: HeroSave)

    fun delete(id: String)
}
