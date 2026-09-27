package com.stratum.core.data.save

import com.stratum.core.domain.difficulty.Waystone
import com.stratum.core.domain.difficulty.WaystoneMod
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixRoll
import com.stratum.core.domain.item.AffixStat
import com.stratum.core.domain.item.Equipment
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.SetBonus
import com.stratum.core.domain.item.SocketSet
import com.stratum.core.domain.session.HeroSave
import com.stratum.core.domain.session.HeroSaveRepository
import com.stratum.core.domain.stats.BuildFlag
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
    /** Version 1 saves held one weapon and nothing else; read, never written. */
    val weapon: ItemSchema? = null,
    /** Everything worn, by equipment slot name. */
    val equipment: Map<String, ItemSchema> = emptyMap(),
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
    fun toDomain(): HeroSave {
        val (worn, spilled) = wornGear()
        return HeroSave(
            id, heroClassId, level, experience, passives.toSet(), worn, bag.map { it.toDomain() } + spilled,
            inserts, currency, supportBag, supports, waystones.map { it.toDomain() }, highestTier, reputation, savedAt,
        )
    }

    /**
     * The saved gear, put back where it was worn. A piece that no longer fits
     * where the save says -- a slot renamed, or an old weapon that was really
     * armour -- goes where it does fit, and failing that into the bag: a save
     * must never lose an item because the rules moved under it.
     */
    private fun wornGear(): Pair<Equipment, List<ItemInstance>> {
        val saved = equipment.map { (slot, item) -> EquipmentSlot.entries.firstOrNull { it.name.equals(slot, ignoreCase = true) } to item.toDomain() } +
            listOfNotNull(weapon?.let { EquipmentSlot.WEAPON to it.toDomain() }.takeIf { equipment.isEmpty() })
        val spilled = mutableListOf<ItemInstance>()
        val worn = saved.fold(Equipment.EMPTY) { gear, (slot, item) ->
            val change = gear.equipping(item, slot?.takeIf { it in item.slot.fits })
            if (change == null) {
                spilled += item
                gear
            } else {
                spilled += change.removed
                change.equipment
            }
        }
        return worn to spilled
    }

    companion object {
        fun of(h: HeroSave) = HeroSchema(
            FORMAT_VERSION, h.id, h.heroClassId, h.level, h.experience, h.passives.sorted(), null,
            h.equipment.items.entries.associate { (slot, item) -> slot.name to ItemSchema.of(item) },
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
    val damageTypeId: String? = null,
    val minDamage: Int = 0,
    val maxDamage: Int = 0,
    val attackSpeed: Float = 0f,
    val attackRange: Int = 0,
    val toolTier: Int = 0,
    /** Version 1's only defence; read into [defences] when that is empty. */
    val armour: Int = 0,
    val defences: List<ModifierSchema> = emptyList(),
    val affixes: List<AffixSchema> = emptyList(),
    val sockets: List<String?> = emptyList(),
    val glyph: String? = null,
    val implicits: List<ModifierSchema> = emptyList(),
    val baseName: String = "",
    val tags: List<String> = emptyList(),
    val twoHanded: Boolean = false,
    val requiredLevel: Int = 1,
    val uniqueId: String? = null,
    val setId: String? = null,
    val flags: List<String> = emptyList(),
    val flavour: String = "",
    val setBonuses: List<SetBonusSchema> = emptyList(),
) {
    fun toDomain(): ItemInstance {
        val kind = ItemSlot.parse(slot) ?: throw IllegalArgumentException("unknown item slot '$slot'")
        val legacyArmour = listOf(StatModifier(Stat.ARMOUR, ModifierKind.FLAT, armour.toFloat())).takeIf { armour != 0 && defences.isEmpty() }
        return ItemInstance(
            instanceId = instanceId, baseId = baseId, name = name, rarity = ItemRarity.valueOf(rarity), itemLevel = itemLevel, slot = kind,
            damageTypeId = damageTypeId, minDamage = minDamage, maxDamage = maxDamage, baseAttackSpeed = attackSpeed,
            attackRange = attackRange, toolTier = toolTier, defences = legacyArmour ?: defences.map { it.toDomain() },
            affixes = affixes.map { it.toDomain() }, sockets = SocketSet(sockets.size, sockets), glyph = glyph ?: kind.glyph,
            implicits = implicits.map { it.toDomain() }, baseName = baseName, tags = tags.toSet(), twoHanded = twoHanded,
            requiredLevel = requiredLevel, uniqueId = uniqueId, setId = setId, flags = flagsOf(flags), flavour = flavour,
            setBonuses = setBonuses.map { it.toDomain() },
        )
    }

    companion object {
        fun of(i: ItemInstance) = ItemSchema(
            i.instanceId, i.baseId, i.name, i.rarity.name, i.itemLevel, i.slot.name, i.damageTypeId, i.minDamage, i.maxDamage,
            i.baseAttackSpeed, i.attackRange, i.toolTier, 0, i.defences.map(ModifierSchema::of), i.affixes.map(AffixSchema::of),
            i.sockets.filled, i.glyph, i.implicits.map(ModifierSchema::of), i.baseName, i.tags.sorted(), i.twoHanded, i.requiredLevel,
            i.uniqueId, i.setId, i.flags.map { it.name }.sorted(), i.flavour, i.setBonuses.map(SetBonusSchema::of),
        )
    }
}

/**
 * A rolled affix. Version 1 wrote one of the old nine stats and a value;
 * those still read, as the modifier the stat has become.
 */
@Serializable
private data class AffixSchema(
    val id: String,
    val name: String,
    val kind: String,
    val stat: String? = null,
    val value: Float = 0f,
    val damageTypeId: String? = null,
    val modifiers: List<ModifierSchema> = emptyList(),
    val tier: Int = 1,
    val group: String? = null,
    val local: Boolean = false,
) {
    fun toDomain(): AffixRoll {
        val rolled = modifiers.map { it.toDomain() }.ifEmpty {
            listOf(AffixStat.valueOf(stat ?: throw IllegalArgumentException("affix '$id' has no modifiers")).modifier(value, damageTypeId))
        }
        return AffixRoll(id, name, AffixKind.valueOf(kind), rolled, tier, group ?: id, local)
    }

    companion object {
        fun of(a: AffixRoll) = AffixSchema(
            a.definitionId, a.name, a.kind.name, modifiers = a.modifiers.map(ModifierSchema::of), tier = a.tier,
            group = a.group.takeIf { it != a.definitionId }, local = a.local,
        )
    }
}

@Serializable
private data class SetBonusSchema(val pieces: Int, val modifiers: List<ModifierSchema> = emptyList(), val flags: List<String> = emptyList()) {
    fun toDomain() = SetBonus(pieces, modifiers.map { it.toDomain() }, flagsOf(flags))

    companion object {
        fun of(b: SetBonus) = SetBonusSchema(b.pieces, b.modifiers.map(ModifierSchema::of), b.flags.map { it.name }.sorted())
    }
}

/** Flags a later version added and this one does not know are dropped rather than failing the whole hero. */
private fun flagsOf(names: List<String>): Set<BuildFlag> = names.mapNotNullTo(HashSet()) { name -> BuildFlag.entries.firstOrNull { it.name == name } }

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

private const val FORMAT_VERSION = 2
