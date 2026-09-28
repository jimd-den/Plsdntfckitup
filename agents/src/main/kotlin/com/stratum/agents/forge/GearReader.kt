package com.stratum.agents.forge

import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixTier
import com.stratum.core.domain.item.ItemBase
import com.stratum.core.domain.item.ItemSetDefinition
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.SetBonus
import com.stratum.core.domain.item.UniqueDefinition
import com.stratum.core.domain.item.WeaponProfile
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlinx.serialization.json.JsonObject

/**
 * Turns the gear in a reply into domain objects, one entry at a time, so
 * one entry the model got hopelessly wrong costs that entry and not the
 * reply. Every entry comes out valid by construction or not at all.
 */
internal class GearReader(
    private val vocabulary: ForgeVocabulary,
    private val namespace: String,
    private val levels: IntRange,
    private val log: RepairLog,
    private val numbers: ModifierReader = ModifierReader(vocabulary, namespace, levels, log),
) {

    fun id(obj: JsonObject, what: String): String = numbers.id(obj, what)

    /**
     * A weapon or armour base, kept to [slot] when the person asked for one.
     * A weapon with no damage type the packs know takes the first one they
     * define, since a weapon must hit with something.
     */
    fun base(obj: JsonObject, slot: ItemSlot?): ItemBase? {
        val id = numbers.id(obj, "base")
        val name = Lenient.string(obj, "name", "title") ?: id.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() }
        val written = Lenient.string(obj, "slot", "type", "kind")
        val parsed = written?.let(ForgeWords::slot)
        val kind = slot ?: parsed ?: run {
            log.note("base '$id': slot '${written ?: ""}' is not a slot; dropped")
            return null
        }
        if (slot != null && parsed != null && parsed != slot) log.note("base '$id': slot '$written' changed to ${slot.name.lowercase()}, as asked")

        val weapon = if (kind == ItemSlot.WEAPON) weaponProfile(obj, id) ?: return null else null
        val defences = Lenient.objects(obj, "defences", "defenses", "defence").mapNotNull { numbers.modifier(it, "base '$id' defence") }
            .map { if (it.kind == ModifierKind.FLAT) it else it.copy(kind = ModifierKind.FLAT).also { _ -> log.note("base '$id': defences are flat amounts; ${it.stat.name.lowercase()} made flat") } }
            .toMutableList()
        Lenient.number(obj, "armour", "armor")?.let { armour -> defences += StatModifier(Stat.ARMOUR, ModifierKind.FLAT, armour.value.coerceIn(0f, ModifierReader.MAX_FLAT)) }
        val minItemLevel = numbers.level(obj, "minItemLevel", "itemLevel", "level")
        return ItemBase(
            id = id,
            name = name,
            slot = kind,
            description = Lenient.string(obj, "description", "text").orEmpty(),
            tags = Lenient.strings(obj, "tags").map(Lenient::slug).filter { it.isNotEmpty() }.toSet(),
            defences = defences,
            implicits = Lenient.objects(obj, "implicits", "implicit").mapNotNull { numbers.range(it, "base '$id' implicit") },
            weapon = weapon,
            requiredLevel = numbers.whole(obj, "requiredLevel", 1..MAX_LEVEL, minItemLevel),
            minItemLevel = minItemLevel,
            weight = numbers.whole(obj, "weight", 1..MAX_WEIGHT, DEFAULT_WEIGHT),
            glyph = glyph(obj) ?: kind.glyph,
        )
    }

    private fun weaponProfile(obj: JsonObject, id: String): WeaponProfile? {
        val written = Lenient.string(obj, "damageType", "damage_type", "element")
        val type = numbers.damageType(written ?: "") ?: vocabulary.damageTypes.firstOrNull()?.also {
            log.note("weapon '$id': damage type '${written ?: ""}' does not exist; it hits with $it")
        }
        if (type == null) {
            log.reject("weapon '$id' needs a damage type, and the loaded packs define none")
            return null
        }
        val span = Lenient.span(obj, "damage")
        val min = (Lenient.number(obj, "minDamage", "min_damage")?.value ?: span?.first?.value ?: DEFAULT_MIN_DAMAGE).toInt().coerceIn(1, MAX_DAMAGE)
        val max = (Lenient.number(obj, "maxDamage", "max_damage")?.value ?: span?.second?.value ?: DEFAULT_MAX_DAMAGE).toInt().coerceIn(1, MAX_DAMAGE)
        if (min > max) log.note("weapon '$id': damage $min..$max ran backwards; turned round")
        return WeaponProfile(
            damageTypeId = type,
            minDamage = minOf(min, max),
            maxDamage = maxOf(min, max),
            attackSpeed = numbers.decimal(obj, "attackSpeed", MIN_SPEED, MAX_SPEED, DEFAULT_SPEED),
            attackRange = numbers.whole(obj, "attackRange", 1..MAX_RANGE, 1),
            toolTier = numbers.whole(obj, "toolTier", 0..MAX_TOOL_TIER, 1),
            twoHanded = Lenient.bool(obj, "twoHanded", "two_handed") ?: false,
        )
    }

    /**
     * A tiered affix. Tags no loaded base carries are dropped -- an affix
     * asking for one would never roll -- and when the person asked for
     * slots, an affix that names none is kept to them.
     */
    fun affix(obj: JsonObject, slots: Set<ItemSlot>): AffixDefinition? {
        val id = numbers.id(obj, "affix")
        val owner = "affix '$id'"
        val name = Lenient.string(obj, "name", "title") ?: id.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() }
        val kindWritten = Lenient.string(obj, "kind", "type", "affixType")
        val kind = ForgeWords.affixKind(kindWritten) ?: AffixKind.PREFIX.also { log.note("$owner: '${kindWritten ?: ""}' is not prefix or suffix; made a prefix") }

        val tierObjects = Lenient.objects(obj, "tiers", "levels")
        val tiers = if (tierObjects.isNotEmpty()) {
            tierObjects.mapIndexedNotNull { index, tier -> tier(tier, "$owner tier ${index + 1}") }
        } else {
            // The one-line form: the entry itself is its only tier.
            listOfNotNull(numbers.range(obj, owner)?.let { AffixTier(listOf(it), numbers.level(obj, "minItemLevel", "itemLevel")) })
        }.sortedBy { it.minItemLevel }
        if (tiers.isEmpty()) {
            log.note("$owner: no tier had a modifier the game knows; dropped")
            return null
        }

        val written = Lenient.strings(obj, "slots", "slot").mapNotNull { raw -> ForgeWords.slot(raw).also { if (it == null) log.note("$owner: '$raw' is not a slot") } }.toSet()
        val kept = when {
            slots.isEmpty() -> written
            written.isEmpty() -> slots
            else -> (written intersect slots).ifEmpty { slots.also { log.note("$owner: kept to ${slots.joinToString { it.name.lowercase() }}, as asked") } }
        }
        val tags = Lenient.strings(obj, "tags").map(Lenient::slug)
        val known = tags.filter { it in vocabulary.tags }
        if (known.size < tags.size) log.note("$owner: dropped tags ${(tags - known.toSet()).joinToString()}, which no base carries")
        return AffixDefinition(
            id = id,
            name = name,
            kind = kind,
            tiers = tiers,
            group = Lenient.string(obj, "group")?.let { "$namespace:${Lenient.slug(it.substringAfterLast(':'))}" } ?: id,
            tags = known.toSet(),
            slots = kept,
            local = Lenient.bool(obj, "local") ?: false,
            weight = numbers.whole(obj, "weight", 1..MAX_WEIGHT, DEFAULT_WEIGHT),
        )
    }

    private fun tier(obj: JsonObject, owner: String): AffixTier? {
        val modifiers = Lenient.objects(obj, "modifiers", "mods", "stats").mapNotNull { numbers.range(it, owner) }
            .ifEmpty { listOfNotNull(numbers.range(obj, owner)) }
        if (modifiers.isEmpty()) return null
        return AffixTier(
            modifiers = modifiers,
            minItemLevel = numbers.level(obj, "minItemLevel", "itemLevel", "level"),
            name = Lenient.string(obj, "name"),
            weight = numbers.whole(obj, "weight", 1..MAX_WEIGHT, DEFAULT_WEIGHT),
        )
    }

    /**
     * A unique on an existing base. A base that does not exist is matched
     * by its id or name, then by the kind of item asked for; a flag the
     * engine does not have is dropped, because a rule nothing reads would be
     * a promise the game breaks.
     */
    fun unique(obj: JsonObject, slot: ItemSlot?, extraBases: List<ItemBase>, setIds: Collection<String>): UniqueDefinition? {
        val id = numbers.id(obj, "unique")
        val owner = "unique '$id'"
        val name = Lenient.string(obj, "name", "title") ?: id.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() }
        val bases = extraBases + vocabulary.bases
        val eligible = if (slot == null) bases else bases.filter { it.slot == slot }
        val written = Lenient.string(obj, "base", "baseId", "baseItem", "itemBase")
        val matched = written?.let { raw -> Lenient.match(raw, eligible.map { it.id }, namespace) { id -> eligible.firstOrNull { it.id == id }?.name } }
        val baseId = matched ?: eligible.firstOrNull()?.id?.also { log.note("$owner: base '${written ?: ""}' does not exist${slot?.let { " as a ${it.name.lowercase()}" }.orEmpty()}; made on $it") }
        if (baseId == null) {
            log.reject("$owner: no loaded base is a ${slot?.name?.lowercase() ?: "piece of gear"}")
            return null
        }
        val flagNames = Lenient.strings(obj, "flags", "rules")
        val flags = flagNames.mapNotNull { raw -> ForgeWords.flag(raw).also { if (it == null) log.note("$owner: '$raw' is not a flag the engine has; dropped") } }.toSet()
        val set = Lenient.string(obj, "set", "setId")?.let { raw ->
            Lenient.match(raw, setIds, namespace).also { if (it == null) log.note("$owner: set '$raw' does not exist; it stands alone") }
        }
        return UniqueDefinition(
            id = id,
            name = name,
            baseId = baseId,
            modifiers = Lenient.objects(obj, "modifiers", "mods", "stats").mapNotNull { numbers.range(it, owner) },
            localModifiers = Lenient.objects(obj, "localModifiers", "local").mapNotNull { numbers.range(it, owner) },
            flags = flags,
            flavour = Lenient.string(obj, "flavour", "flavor", "flavourText", "quote").orEmpty(),
            setId = set,
            minItemLevel = numbers.level(obj, "minItemLevel", "itemLevel", "level"),
            weight = numbers.whole(obj, "weight", 1..MAX_WEIGHT, DEFAULT_WEIGHT),
            sockets = Lenient.number(obj, "sockets")?.value?.toInt()?.coerceIn(0, MAX_SOCKETS),
            glyph = glyph(obj),
        )
    }

    /** A set, its bonuses kept to the number of pieces it has once the pieces are known. */
    fun set(obj: JsonObject): ItemSetDefinition {
        val id = numbers.id(obj, "set")
        val owner = "set '$id'"
        val bonuses = Lenient.objects(obj, "bonuses", "setBonuses").mapNotNull { bonus ->
            val modifiers = Lenient.objects(bonus, "modifiers", "mods", "stats").mapNotNull { numbers.modifier(it, owner) }
            val flags = Lenient.strings(bonus, "flags").mapNotNull { raw -> ForgeWords.flag(raw).also { if (it == null) log.note("$owner: '$raw' is not a flag; dropped") } }.toSet()
            val pieces = Lenient.number(bonus, "pieces", "count", "items")?.value?.toInt() ?: 2
            if (modifiers.isEmpty() && flags.isEmpty()) null else SetBonus(pieces.coerceAtLeast(1), modifiers, flags)
        }
        return ItemSetDefinition(
            id = id,
            name = Lenient.string(obj, "name", "title") ?: id.substringAfter(':').replace('_', ' '),
            bonuses = bonuses,
            description = Lenient.string(obj, "description", "text").orEmpty(),
        )
    }

    /** [set] with bonuses asking for more pieces than it has brought down to what it has. */
    fun fitBonuses(set: ItemSetDefinition, pieces: Int): ItemSetDefinition {
        val fitted = set.bonuses.map { bonus ->
            if (bonus.pieces <= pieces) bonus else bonus.copy(pieces = pieces).also { log.note("set '${set.id}': a ${bonus.pieces}-piece bonus asks for more than its $pieces pieces; it needs $pieces") }
        }
        // Two bonuses at one count read as one; merge rather than lose either.
        val merged = fitted.groupBy { it.pieces }.map { (count, group) -> SetBonus(count, group.flatMap { it.modifiers }, group.flatMapTo(LinkedHashSet<BuildFlag>()) { it.flags }) }
        return set.copy(bonuses = merged.sortedBy { it.pieces })
    }

    private fun glyph(obj: JsonObject): String? = Lenient.string(obj, "glyph", "icon")?.takeIf { it.length <= MAX_GLYPH }

    private companion object {
        const val MAX_LEVEL = 100
        const val MAX_WEIGHT = 1000
        const val DEFAULT_WEIGHT = 100
        const val MAX_DAMAGE = 5000
        const val DEFAULT_MIN_DAMAGE = 8f
        const val DEFAULT_MAX_DAMAGE = 14f
        const val MIN_SPEED = 0.1f
        const val MAX_SPEED = 10f
        const val DEFAULT_SPEED = 1.2f
        const val MAX_RANGE = 12
        const val MAX_TOOL_TIER = 5
        const val MAX_SOCKETS = 6
        const val MAX_GLYPH = 4
    }
}
