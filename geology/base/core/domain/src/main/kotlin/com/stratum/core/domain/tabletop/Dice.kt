package com.stratum.core.domain.tabletop

import kotlin.random.Random

/**
 * A dice expression in the notation tabletop players already write:
 * `d20`, `2d6+3`, `1d8+1d4-1`, and `4d6kh3` (roll four, keep the highest
 * three) or `2d20kl1` (disadvantage).
 *
 * Parsed once and rolled many times against whatever [Random] the caller
 * passes, so a session's seed decides every roll and a test can pin one.
 */
class DiceExpression private constructor(val text: String, private val terms: List<Term>) {

    private sealed interface Term {
        val sign: Int

        data class Dice(val count: Int, val sides: Int, val keep: Keep?, override val sign: Int) : Term
        data class Flat(val value: Int, override val sign: Int) : Term
    }

    /** Keep only the [count] highest or lowest dice of a group. */
    private data class Keep(val highest: Boolean, val count: Int)

    /** The smallest and largest totals this expression can produce. */
    val range: IntRange
        get() = terms.sumOf { it.sign * lowest(it) }..terms.sumOf { it.sign * highest(it) }

    fun roll(random: Random): DiceRoll {
        val groups = terms.filterIsInstance<Term.Dice>().map { term -> term to List(term.count) { random.nextInt(1, term.sides + 1) } }
        val total = terms.sumOf { term ->
            when (term) {
                is Term.Flat -> term.sign * term.value
                is Term.Dice -> term.sign * kept(groups.first { it.first === term }.second, term.keep).sum()
            }
        }
        // A "natural" roll is the face of a lone die -- what decides a critical in d20 games.
        val lone = groups.singleOrNull()?.takeIf { (term, _) -> term.count == 1 || term.keep?.count == 1 }
        val natural = lone?.let { (term, faces) -> kept(faces, term.keep).single() }
        val sides = lone?.first?.sides
        return DiceRoll(this, total, groups.flatMap { it.second }, natural, isMaxNatural = natural != null && natural == sides, isMinNatural = natural == 1)
    }

    override fun toString(): String = text

    private fun kept(faces: List<Int>, keep: Keep?): List<Int> = when {
        keep == null -> faces
        keep.highest -> faces.sortedDescending().take(keep.count)
        else -> faces.sorted().take(keep.count)
    }

    private fun lowest(term: Term): Int = when (term) {
        is Term.Flat -> term.value
        is Term.Dice -> if (term.sign > 0) (term.keep?.count ?: term.count) else (term.keep?.count ?: term.count) * term.sides
    }

    private fun highest(term: Term): Int = when (term) {
        is Term.Flat -> term.value
        is Term.Dice -> if (term.sign > 0) (term.keep?.count ?: term.count) * term.sides else (term.keep?.count ?: term.count)
    }

    companion object {
        private val SPLIT_TERM = Regex("[\\dhlkd]\\s+[\\dkd]")
        private val TERM = Regex("([+-])?\\s*(?:(\\d*)d(\\d+)(?:(kh|kl)(\\d+))?|(\\d+))")

        /** @return null for anything that is not valid dice notation. */
        fun parse(text: String): DiceExpression? {
            val lower = text.lowercase().trim()
            // Spaces may sit around + and -, never inside a term: "2d6 3" is a mistake, not 2d63.
            if (SPLIT_TERM.containsMatchIn(lower)) return null
            val compact = lower.replace(" ", "")
            if (compact.isEmpty()) return null
            val terms = mutableListOf<Term>()
            var at = 0
            while (at < compact.length) {
                val match = TERM.matchAt(compact, at) ?: return null
                if (match.value.isEmpty()) return null
                if (terms.isNotEmpty() && match.groupValues[1].isEmpty()) return null
                terms += termOf(match.groupValues) ?: return null
                at = match.range.last + 1
            }
            return DiceExpression(text.trim(), terms)
        }

        private fun termOf(groups: List<String>): Term? {
            val sign = if (groups[1] == "-") -1 else 1
            if (groups[6].isNotEmpty()) return Term.Flat(groups[6].toInt(), sign)
            val count = groups[2].ifEmpty { "1" }.toInt()
            val sides = groups[3].toInt()
            if (count !in 1..MAX_DICE || sides !in 2..MAX_SIDES) return null
            val keep = groups[4].takeIf(String::isNotEmpty)?.let { Keep(highest = it == "kh", count = groups[5].toInt()) }
            if (keep != null && keep.count !in 1..count) return null
            return Term.Dice(count, sides, keep, sign)
        }

        /** Enough for any real game; a cap so "999999d6" cannot stall the frame. */
        const val MAX_DICE = 100
        const val MAX_SIDES = 1000
    }
}

/** One throw of a [DiceExpression]. */
data class DiceRoll(
    val expression: DiceExpression,
    val total: Int,
    /** Every face that came up, kept or not, in the order thrown. */
    val faces: List<Int>,
    /** The kept face when the expression is one die; null otherwise. */
    val natural: Int?,
    val isMaxNatural: Boolean,
    val isMinNatural: Boolean,
)
