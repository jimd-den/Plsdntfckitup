package com.stratum.core.domain.sandbox

import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixRoll
import com.stratum.core.domain.item.Equipment
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.ItemCatalogue
import com.stratum.core.domain.item.ItemGenerator
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.SocketSet
import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import java.util.Base64
import kotlin.random.Random
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A build as it is shared: the class, the level, the passive nodes, what is
 * worn and what is linked -- and nothing about where the hero stood, what
 * they carried or which world they were in.
 *
 * Gear is written as a recipe against the loaded packs -- a base id, an item
 * level, a rarity, the unique it is -- with its rolled numbers spelled out, so
 * the blob stays small, reads like a plugin (every id is a pack's id), and
 * rebuilds the exact item on any device with the same packs. What a device
 * does not have is left out and reported, never guessed.
 */
@Serializable
data class SharedBuild(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val name: String = "",
    val heroClassId: String,
    val level: Int = 1,
    val passives: List<String> = emptyList(),
    /** What is worn, by equipment slot name. */
    val gear: Map<String, SharedItem> = emptyMap(),
    /** Support ids linked to each skill id, in link order. */
    val supports: Map<String, List<String>> = emptyMap(),
) {
    companion object {
        const val FORMAT = "stratum.build"
        const val VERSION = 1
    }
}

@Serializable
data class SharedItem(
    val base: String,
    val itemLevel: Int,
    val rarity: String,
    val name: String = "",
    val unique: String? = null,
    val implicits: List<SharedModifier> = emptyList(),
    val affixes: List<SharedAffix> = emptyList(),
    /** Insert ids by socket; null is an empty socket. */
    val sockets: List<String?> = emptyList(),
)

@Serializable
data class SharedAffix(
    val id: String,
    val name: String = "",
    val kind: String = AffixKind.PREFIX.name,
    val tier: Int = 1,
    val local: Boolean = false,
    val group: String? = null,
    val modifiers: List<SharedModifier> = emptyList(),
)

/** A modifier the way `pack.json` spells one: `stat`, `kind`, `value`, `damageType`. */
@Serializable
data class SharedModifier(val stat: String, val kind: String = "increased", val value: Float, val damageType: String? = null)

/** A shared build turned back into a hero, and what could not come with it. */
data class ImportedBuild(val hero: HeroSave, val skipped: List<String>)

/**
 * Writes builds out and reads them back: as JSON, or as a one-line code for
 * pasting into a chat -- the same compact JSON in URL-safe base64, behind a
 * prefix that says what it is.
 */
object BuildCode {

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val pretty = Json(json) { prettyPrint = true }

    fun of(player: PlayerState, name: String = ""): SharedBuild = SharedBuild(
        name = name,
        heroClassId = player.heroClassId,
        level = player.level,
        passives = player.passives.sorted(),
        gear = player.equipment.items.entries.associate { (slot, item) -> slot.name to share(item) },
        supports = player.supports.filterValues { it.isNotEmpty() },
    )

    fun toJson(build: SharedBuild, readable: Boolean = true): String =
        (if (readable) pretty else json).encodeToString(SharedBuild.serializer(), build)

    /** The one-line form: [PREFIX] and URL-safe base64 of the compact JSON. */
    fun toCode(build: SharedBuild): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(toJson(build, readable = false).toByteArray(Charsets.UTF_8))

    /** Reads either form. A blob that is not a build fails with a reason a player can read. */
    fun read(text: String): Result<SharedBuild> {
        val trimmed = text.trim()
        val body = if (trimmed.startsWith(PREFIX)) {
            try {
                String(Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX).filterNot(Char::isWhitespace)), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return Result.failure(IllegalArgumentException("That build code is damaged"))
            }
        } else {
            trimmed
        }
        val build = try {
            json.decodeFromString(SharedBuild.serializer(), body)
        } catch (_: SerializationException) {
            return Result.failure(IllegalArgumentException("That is not a build"))
        } catch (_: IllegalArgumentException) {
            return Result.failure(IllegalArgumentException("That is not a build"))
        }
        if (build.format != SharedBuild.FORMAT) return Result.failure(IllegalArgumentException("That is a ${build.format}, not a build"))
        if (build.version > SharedBuild.VERSION) return Result.failure(IllegalArgumentException("That build was written by a newer version"))
        return Result.success(build)
    }

    /**
     * [build] as a hero, its gear rebuilt from [catalogue]. Items whose base
     * or unique is not loaded, and slots the item cannot be worn in, are
     * skipped and named in [ImportedBuild.skipped]. [random] only names the
     * rebuilt items; every number comes from the build.
     */
    fun toHero(build: SharedBuild, catalogue: ItemCatalogue, random: Random, id: String = build.heroClassId): ImportedBuild {
        val generator = ItemGenerator(catalogue)
        val skipped = mutableListOf<String>()
        val equipment = build.gear.entries.fold(Equipment.EMPTY) { worn, (slotName, shared) ->
            val slot = EquipmentSlot.entries.firstOrNull { it.name.equals(slotName, ignoreCase = true) }
            val item = rebuild(shared, generator, random)
            when {
                slot == null -> worn.also { skipped += "${shared.name.ifBlank { shared.base }}: no slot called $slotName" }
                item == null -> worn.also { skipped += "${shared.name.ifBlank { shared.base }}: not in the loaded packs" }
                else -> worn.equipping(item, slot)?.equipment ?: worn.also { skipped += "${item.name} cannot be worn in ${slot.label}" }
            }
        }
        val hero = HeroSave(
            id = id,
            heroClassId = build.heroClassId,
            level = build.level.coerceAtLeast(1),
            passives = build.passives.toSet(),
            equipment = equipment,
            supports = build.supports,
        )
        return ImportedBuild(hero, skipped)
    }

    private fun share(item: ItemInstance) = SharedItem(
        base = item.baseId,
        itemLevel = item.itemLevel,
        rarity = item.rarity.name.lowercase(),
        name = item.name,
        unique = item.uniqueId,
        implicits = item.implicits.map(::share),
        affixes = item.affixes.map { roll ->
            SharedAffix(roll.definitionId, roll.name, roll.kind.name.lowercase(), roll.tier, roll.local, roll.group.takeIf { it != roll.definitionId }, roll.modifiers.map(::share))
        },
        sockets = item.sockets.filled,
    )

    private fun share(modifier: StatModifier) =
        SharedModifier(modifier.stat.name.lowercase(), modifier.kind.name.lowercase(), modifier.value, modifier.damageTypeId)

    private fun rebuild(shared: SharedItem, generator: ItemGenerator, random: Random): ItemInstance? {
        val rarity = ItemRarity.entries.firstOrNull { it.name.equals(shared.rarity, ignoreCase = true) } ?: return null
        val affixes = shared.affixes.map { it.toRoll() ?: return null }
        val implicits = shared.implicits.map { it.toModifier() ?: return null }
        val sockets = SocketSet(shared.sockets.size, shared.sockets)
        if (shared.unique != null) {
            val unique = generator.catalogue.unique(shared.unique) ?: return null
            val made = generator.unique(unique, shared.itemLevel, random) ?: return null
            return made.copy(affixes = affixes.ifEmpty { made.affixes }, implicits = implicits, sockets = sockets)
        }
        if (!rarity.isRolled) return null
        val base = generator.catalogue.base(shared.base) ?: return null
        val blank = generator.craft(base, shared.itemLevel, ItemRarity.COMMON, random)
        return blank.copy(
            rarity = rarity,
            name = shared.name.ifBlank { generator.name(blank, rarity, affixes, random) },
            implicits = implicits,
            affixes = affixes,
            sockets = sockets,
        )
    }

    private fun SharedAffix.toRoll(): AffixRoll? {
        val affixKind = AffixKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: return null
        return AffixRoll(id, name, affixKind, modifiers.map { it.toModifier() ?: return null }, tier, group ?: id, local)
    }

    private fun SharedModifier.toModifier(): StatModifier? {
        val parsedStat = Stat.entries.firstOrNull { it.name.equals(stat, ignoreCase = true) } ?: return null
        val parsedKind = ModifierKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: return null
        return StatModifier(parsedStat, parsedKind, value, damageType)
    }

    /** Marks a one-line build code, and its format's version. */
    const val PREFIX = "STRATUM-BUILD-1:"
}
