package com.stratum.core.data.save

import com.stratum.core.domain.difficulty.Waystone
import com.stratum.core.domain.difficulty.WaystoneMod
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixRoll
import com.stratum.core.domain.item.AffixStat
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.SocketSet
import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.HeroSaveRepository
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Heroes as one JSON file each, in [directory].
 *
 * Written to a temporary file and renamed over the old one, so a phone that
 * dies mid-save keeps the last good hero rather than half of a new one. A
 * file that will not read is skipped, not fatal: one corrupt save must not
 * lock a player out of every other character.
 */
class FileHeroSaveStore(private val directory: File) : HeroSaveRepository {

    override fun all(): List<HeroSave> =
        directory.listFiles { file -> file.extension == EXTENSION }.orEmpty()
            .mapNotNull { read(it) }
            .sortedByDescending { it.savedAt }

    override fun load(id: String): HeroSave? = read(fileFor(id))

    override fun save(hero: HeroSave) {
        directory.mkdirs()
        val target = fileFor(hero.id)
        val staging = File(directory, "${target.name}.tmp")
        staging.writeText(HeroSaveJson.encode(hero))
        if (!staging.renameTo(target)) {
            target.delete()
            staging.renameTo(target)
        }
    }

    override fun delete(id: String) {
        fileFor(id).delete()
    }

    private fun read(file: File): HeroSave? =
        if (!file.isFile) null else try {
            HeroSaveJson.decode(file.readText())
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Ids are pack-namespaced, like `igbo:dibia`; only the safe characters reach the file system. */
    private fun fileFor(id: String) = File(directory, "${id.replace(UNSAFE, "_")}.$EXTENSION")

    private companion object {
        const val EXTENSION = "hero"
        val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}

/** Heroes as JSON. Its own schema, so the domain can be renamed without breaking anybody's saves. */
object HeroSaveJson {

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(hero: HeroSave): String = json.encodeToString(HeroSchema.serializer(), HeroSchema.of(hero))

    fun decode(text: String): HeroSave = json.decodeFromString(HeroSchema.serializer(), text).toDomain()
}

@Serializable
private data class HeroSchema(
    val version: Int = FORMAT_VERSION,
    val id: String,
    val heroClassId: String,
    val level: Int = 1,
    val experience: Int = 0,
    val passives: List<String> = emptyList(),
    val weapon: ItemSchema? = null,
    val bag: List<ItemSchema> = emptyList(),
    val inserts: Map<String, Int> = emptyMap(),
    val currency: Map<String, Int> = emptyMap(),
    val supportBag: Map<String, Int> = emptyMap(),
    val supports: Map<String, List<String>> = emptyMap(),
    val waystones: List<WaystoneSchema> = emptyList(),
    val highestTier: Int = 0,
    val reputation: Map<String, Int> = emptyMap(),
    val savedAt: Long = 0L,
) {
    fun toDomain() = HeroSave(
        id, heroClassId, level, experience, passives.toSet(), weapon?.toDomain(), bag.map { it.toDomain() },
        inserts, currency, supportBag, supports, waystones.map { it.toDomain() }, highestTier, reputation, savedAt,
    )

    companion object {
        fun of(h: HeroSave) = HeroSchema(
            FORMAT_VERSION, h.id, h.heroClassId, h.level, h.experience, h.passives.sorted(), h.equippedWeapon?.let(ItemSchema::of),
            h.bag.map(ItemSchema::of), h.insertBag, h.currency, h.supportBag, h.supports, h.waystones.map(WaystoneSchema::of), h.highestTier,
            h.reputation, h.savedAt,
        )
    }
}

@Serializable
private data class ItemSchema(
    val instanceId: String,
    val baseId: String,
    val name: String,
    val rarity: String,
    val itemLevel: Int,
    val slot: String,
    val damageTypeId: String,
    val minDamage: Int,
    val maxDamage: Int,
    val attackSpeed: Float,
    val attackRange: Int,
    val toolTier: Int,
    val armour: Int,
    val affixes: List<AffixSchema> = emptyList(),
    val sockets: List<String?> = emptyList(),
    val glyph: String = "⚔",
) {
    fun toDomain() = ItemInstance(
        instanceId, baseId, name, ItemRarity.valueOf(rarity), itemLevel, EquipmentSlot.valueOf(slot), damageTypeId,
        minDamage, maxDamage, attackSpeed, attackRange, toolTier, armour, affixes.map { it.toDomain() },
        SocketSet(sockets.size, sockets), glyph,
    )

    companion object {
        fun of(i: ItemInstance) = ItemSchema(
            i.instanceId, i.baseId, i.name, i.rarity.name, i.itemLevel, i.slot.name, i.damageTypeId, i.minDamage, i.maxDamage,
            i.baseAttackSpeed, i.attackRange, i.toolTier, i.baseArmour, i.affixes.map(AffixSchema::of), i.sockets.filled, i.glyph,
        )
    }
}

@Serializable
private data class AffixSchema(
    val id: String,
    val name: String,
    val kind: String,
    val stat: String,
    val value: Float,
    val damageTypeId: String? = null,
) {
    fun toDomain() = AffixRoll(id, name, AffixKind.valueOf(kind), AffixStat.valueOf(stat), value, damageTypeId)

    companion object {
        fun of(a: AffixRoll) = AffixSchema(a.definitionId, a.name, a.kind.name, a.stat.name, a.value, a.damageTypeId)
    }
}

@Serializable
private data class ModifierSchema(val stat: String, val kind: String, val value: Float, val damageTypeId: String? = null) {
    fun toDomain() = StatModifier(Stat.valueOf(stat), ModifierKind.valueOf(kind), value, damageTypeId)

    companion object {
        fun of(m: StatModifier) = ModifierSchema(m.stat.name, m.kind.name, m.value, m.damageTypeId)
    }
}

/**
 * A waystone carries its mods whole rather than by id, so a waystone found
 * under one version of a plugin opens the same world after an update.
 */
@Serializable
private data class WaystoneSchema(val id: String, val tier: Int, val mods: List<WaystoneModSchema> = emptyList()) {
    fun toDomain() = Waystone(id, tier, mods.map { it.toDomain() })

    companion object {
        fun of(w: Waystone) = WaystoneSchema(w.id, w.tier, w.mods.map(WaystoneModSchema::of))
    }
}

@Serializable
private data class WaystoneModSchema(
    val id: String,
    val name: String,
    val monster: List<ModifierSchema> = emptyList(),
    val reward: List<ModifierSchema> = emptyList(),
) {
    fun toDomain() = WaystoneMod(id, name, monster.map { it.toDomain() }, reward.map { it.toDomain() })

    companion object {
        fun of(m: WaystoneMod) = WaystoneModSchema(m.id, m.name, m.monster.map(ModifierSchema::of), m.reward.map(ModifierSchema::of))
    }
}

private const val FORMAT_VERSION = 1
