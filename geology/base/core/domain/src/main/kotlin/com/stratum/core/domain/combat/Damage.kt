package com.stratum.core.domain.combat

/**
 * "X% of [fromDamageTypeId] damage converted to [toDamageTypeId]": the
 * damage stops being the old type and becomes the new one, so it is resisted
 * as the new type and inflicts the new type's ailment.
 *
 * A null [fromDamageTypeId] converts every other type, which is how a support
 * that turns a whole skill into fire is written.
 */
data class DamageConversion(
    val fromDamageTypeId: String?,
    val toDamageTypeId: String,
    /** 0..1 of the source damage. */
    val share: Float,
) {
    init {
        require(share >= 0f) { "a conversion of $share is negative" }
    }
}

/**
 * "Gain X% of [fromDamageTypeId] damage as extra [toDamageTypeId]": the
 * source is kept and the extra is added on top. Where conversion changes what
 * a hit is, this makes it more -- which is why it is the one builds stack.
 *
 * A null [fromDamageTypeId] gains from the whole hit.
 */
data class ExtraDamage(
    val fromDamageTypeId: String?,
    val toDamageTypeId: String,
    val share: Float,
) {
    init {
        require(share >= 0f) { "extra damage of $share is negative" }
    }
}

/**
 * Turns a hit's base damage into the damage it deals by type.
 *
 * One pass, in a fixed order: conversions first, then extra damage computed
 * from the converted hit. Converted damage is never converted again and extras
 * never feed each other, which is what keeps a pack that converts fire to cold
 * and cold to fire from looping -- and keeps the result independent of the
 * order modifiers were listed in.
 */
object DamageConversions {

    fun apply(
        base: Map<String, Float>,
        conversions: List<DamageConversion> = emptyList(),
        extras: List<ExtraDamage> = emptyList(),
    ): Map<String, Float> {
        if (conversions.isEmpty() && extras.isEmpty()) return base
        val converted = convert(base, conversions)
        return addExtras(converted, extras)
    }

    private fun convert(base: Map<String, Float>, conversions: List<DamageConversion>): Map<String, Float> {
        if (conversions.isEmpty()) return base
        val out = LinkedHashMap<String, Float>()
        base.keys.sorted().forEach { type ->
            val amount = base.getValue(type)
            val applicable = conversions.filter { (it.fromDamageTypeId == null || it.fromDamageTypeId == type) && it.toDamageTypeId != type }
            val total = applicable.sumOf { it.share.toDouble() }.toFloat()
            // More than all of it converts all of it, split in proportion; it never creates damage.
            val scale = if (total > 1f) 1f / total else 1f
            val kept = amount * (1f - (total * scale))
            out.add(type, kept)
            applicable.forEach { out.add(it.toDamageTypeId, amount * it.share * scale) }
        }
        return out.filterValues { it > 0f }
    }

    private fun addExtras(converted: Map<String, Float>, extras: List<ExtraDamage>): Map<String, Float> {
        if (extras.isEmpty()) return converted
        val out = LinkedHashMap(converted)
        extras.forEach { extra ->
            val source = if (extra.fromDamageTypeId == null) converted.filterKeys { it != extra.toDamageTypeId }.values.sum()
            else converted[extra.fromDamageTypeId] ?: 0f
            out.add(extra.toDamageTypeId, source * extra.share)
        }
        return out.filterValues { it > 0f }
    }

    private fun MutableMap<String, Float>.add(type: String, amount: Float) {
        if (amount > 0f) this[type] = (this[type] ?: 0f) + amount
    }
}
