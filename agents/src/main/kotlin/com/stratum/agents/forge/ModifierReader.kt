package com.stratum.agents.forge

import com.stratum.core.domain.item.ModifierRange
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads the numbers of a forged thing: its modifiers, levels and weights,
 * each clamped into what the game can hold. The clamps are sanity limits,
 * not balance -- a broken build is welcome, a thousand-fold multiplier or a
 * negative item level is a typo -- and balance is [com.stratum.core.domain.item.PowerBudget]'s.
 */
internal class ModifierReader(
    private val vocabulary: ForgeVocabulary,
    val namespace: String,
    private val levels: IntRange,
    private val log: RepairLog,
) {

    /** Every id handed out in this reply, so two entries never share one. */
    private val issued = HashSet<String>()

    /**
     * The id an entry keeps: always in this pack's namespace, since a reply
     * that reuses a loaded pack's id would quietly replace that pack's thing.
     */
    fun id(obj: JsonObject, what: String): String {
        val raw = Lenient.string(obj, "id")
        val local = Lenient.slug((raw ?: Lenient.string(obj, "name", "title").orEmpty()).substringAfterLast(':')).ifEmpty { what }
        var id = "$namespace:$local"
        var n = 2
        while (!issued.add(id)) id = "$namespace:${local}_${n++}"
        if (raw != null && raw != id) log.note("$what id '$raw' became '$id'")
        return id
    }

    fun level(obj: JsonObject, vararg names: String, fallback: Int = levels.first): Int {
        val raw = Lenient.number(obj, *names)?.value?.roundToInt() ?: return fallback
        val clamped = raw.coerceIn(levels)
        if (clamped != raw) log.note("level $raw moved into ${levels.first}..${levels.last}")
        return clamped
    }

    fun whole(obj: JsonObject, name: String, range: IntRange, fallback: Int): Int =
        (Lenient.number(obj, name)?.value?.roundToInt() ?: fallback).coerceIn(range)

    fun decimal(obj: JsonObject, name: String, min: Float, max: Float, fallback: Float): Float =
        (Lenient.number(obj, name)?.value ?: fallback).coerceIn(min, max)

    /** A loaded damage type, matched from what [raw] nearly said, or null. */
    fun damageType(raw: String?): String? =
        raw?.let { Lenient.match(it, vocabulary.damageTypes, namespace) { id -> id.substringAfterLast(':') } }

    /** A rolled modifier: `{ "stat", "kind", "min", "max" }`, or any near spelling of it. */
    fun range(obj: JsonObject, owner: String): ModifierRange? {
        val read = read(obj, owner) ?: return null
        val (stat, kind, low, high, type) = read
        return ModifierRange(stat, kind, minOf(low, high), maxOf(low, high), type)
    }

    /** A fixed modifier, as set bonuses carry. A range written here counts at its best end. */
    fun modifier(obj: JsonObject, owner: String): StatModifier? {
        val read = read(obj, owner) ?: return null
        val value = if (abs(read.high) >= abs(read.low)) read.high else read.low
        return StatModifier(read.stat, read.kind, value, read.damageTypeId)
    }

    private data class Read(val stat: Stat, val kind: ModifierKind, val low: Float, val high: Float, val damageTypeId: String?)

    private fun read(obj: JsonObject, owner: String): Read? {
        val statName = Lenient.string(obj, "stat", "attribute", "property")
        if (statName == null) {
            log.note("$owner: dropped a modifier that names no stat")
            return null
        }
        val word = ForgeWords.stat(statName, vocabulary.damageTypes)
        if (word == null) {
            log.note("$owner: dropped '$statName', which is not a stat")
            return null
        }
        if (word.stat.name.lowercase() != Lenient.key(statName)) log.note("$owner: stat '$statName' read as ${word.stat.name.lowercase()}")

        val span = Lenient.span(obj, "value", "range", "values")
        val low = Lenient.number(obj, "min", "low") ?: span?.first ?: Lenient.number(obj, "value", "amount")
        val high = Lenient.number(obj, "max", "high") ?: span?.second ?: Lenient.number(obj, "value", "amount") ?: low
        if (low == null || high == null) {
            log.note("$owner: dropped ${word.stat.name.lowercase()}, which has no number")
            return null
        }

        val kindName = Lenient.string(obj, "kind", "modifierKind", "mode", "op")
        val spelled = kindName?.let(ForgeWords::kind)
        val kind = spelled?.first ?: guessKind(word.stat, low).also {
            log.note("$owner: ${word.stat.name.lowercase()} ${if (kindName == null) "had no kind" else "kind '$kindName' is not a kind"}; read as ${it.name.lowercase()}")
        }
        val reduces = spelled?.second == true

        val shares = kind != ModifierKind.FLAT || word.stat.isPercent
        val threshold = if (kind == ModifierKind.FLAT) FLAT_SHARE_THRESHOLD else SHARE_THRESHOLD
        val asPercent = shares && (low.percent || high.percent || abs(low.value) >= threshold || abs(high.value) >= threshold)
        if (asPercent) log.note("$owner: ${word.stat.name.lowercase()} ${low.value}..${high.value} read as percentages")
        fun value(n: Lenient.Number): Float {
            val v = if (asPercent) n.value / 100f else n.value
            return if (reduces) -abs(v) else v
        }

        val limits = limits(word.stat, kind)
        val (a, b) = value(low) to value(high)
        val (ca, cb) = a.coerceIn(limits) to b.coerceIn(limits)
        if (ca != a || cb != b) log.note("$owner: ${word.stat.name.lowercase()} clamped into ${limits.start}..${limits.endInclusive}")
        if (a > b) log.note("$owner: ${word.stat.name.lowercase()} range ran backwards; turned round")

        val typeName = Lenient.string(obj, "damageType", "damage_type", "element")
        val type = word.damageTypeId ?: typeName?.let { raw ->
            damageType(raw).also { if (it == null) log.note("$owner: damage type '$raw' does not exist; the modifier covers every type") }
        }
        // Only a resistance is scoped by damage type; any other stat scoped to one would never apply.
        val scoped = type.takeIf { word.stat == Stat.RESISTANCE }
        if (type != null && scoped == null) log.note("$owner: ${word.stat.name.lowercase()} is not scoped by damage type; scope dropped")
        return Read(word.stat, kind, ca, cb, scoped)
    }

    /**
     * What a modifier with no kind meant. "+25 health" is flat; "0.2 damage"
     * is a share and so increased; crit chance and resistances are flat
     * shares, the way the tooltips read them.
     */
    private fun guessKind(stat: Stat, value: Lenient.Number): ModifierKind = when {
        stat.isPercent -> ModifierKind.FLAT
        value.percent -> ModifierKind.INCREASED
        abs(value.value) >= 1f -> ModifierKind.FLAT
        else -> ModifierKind.INCREASED
    }

    private fun limits(stat: Stat, kind: ModifierKind): ClosedFloatingPointRange<Float> = when {
        kind != ModifierKind.FLAT -> -MAX_REDUCTION..MAX_SHARE
        stat == Stat.CRIT_MULTIPLIER -> -MAX_CRIT_MULTIPLIER..MAX_CRIT_MULTIPLIER
        stat.isPercent -> -1f..1f
        stat in WHOLE -> -MAX_FLAT..MAX_FLAT
        else -> -MAX_SMALL_FLAT..MAX_SMALL_FLAT
    }

    companion object {
        /**
         * From this up an increased or more value was written as a
         * percentage: "10 increased damage" means 10%, not 1000%. A broken
         * build asking for five-fold writes 5 and is misread, but writes
         * "500%" and is not; the prompt asks for shares either way.
         */
        private const val SHARE_THRESHOLD = 5f

        /** Above this a flat share, crit chance or resistance, was written as a percentage. */
        private const val FLAT_SHARE_THRESHOLD = 1.0001f

        /** Tenfold is room for any build; beyond it is a typo. */
        const val MAX_SHARE = 10f

        /** A reduction of all of something would divide by nothing. */
        const val MAX_REDUCTION = 0.9f
        const val MAX_CRIT_MULTIPLIER = 10f
        const val MAX_FLAT = 10_000f
        const val MAX_SMALL_FLAT = 10f

        private val WHOLE = setOf(Stat.MAX_HEALTH, Stat.DAMAGE, Stat.ARMOUR, Stat.SKILL_DAMAGE, Stat.MAX_RESOURCE, Stat.RESOURCE_COST)
    }
}
