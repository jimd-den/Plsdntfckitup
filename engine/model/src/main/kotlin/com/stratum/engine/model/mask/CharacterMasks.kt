package com.stratum.engine.model.mask

import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.motion.MotionProfiles

/**
 * Which mask a character wears: the hero's chosen mask, and a mask for
 * every monster, derived from what the monster is.
 *
 * ## Monsters
 *
 * Every enemy definition maps to a genome deterministically from its id, so
 * the same monster is the same mask in every world, on every device, without
 * anything being stored. The tradition is read from the id where a word in
 * it says something (a brute is an Mgbedike, a guardian an Ijele, an
 * elephant an Ogbodo Enyi) and hashed otherwise; the rest of the genome is a
 * roll in that tradition, seeded by the id.
 *
 * Rank is menace: an elite's mask is more ornate and its crest taller, a
 * champion's more so, and a boss wears the full regalia -- tallest crest,
 * every ornament -- and stands far bigger ([heightFor]). A pack can pin any
 * monster's mask with a genome code or a preset name ([genomeFor]'s
 * override), which is how a hand-made boss gets its face.
 */
object CharacterMasks {

    /** The hero's mask when the player has not chosen one: the maiden, the first preset. */
    val DEFAULT_HERO: MaskGenome get() = MaskGenome.presets.first()

    /**
     * The mask for monster [definitionId] of [rank]. [override] is a genome
     * code ([MaskCodec]) or a preset's name; when it names nothing, the
     * derived mask is used.
     */
    fun genomeFor(definitionId: String, rank: EnemyRank = EnemyRank.MINION, override: String? = null): MaskGenome {
        override?.takeIf { it.isNotBlank() }?.let { text ->
            MaskGenome.presets.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }?.let { return it }
            MaskCodec.decode(text.trim().removePrefix(MaskCodec.TAG_PREFIX), definitionId)?.let { return it.normalised() }
        }
        val seed = stableSeed(definitionId)
        val tradition = traditionFor(definitionId, seed)
        val base = MaskGenome.random(seed, tradition)
        val menace = when (rank) {
            EnemyRank.MINION -> 0f
            EnemyRank.ELITE -> 0.35f
            EnemyRank.CHAMPION -> 0.65f
            EnemyRank.BOSS -> 1f
        }
        return base.copy(
            name = definitionId.substringAfter(':').replace('_', ' '),
            ornament = (base.ornament + 0.4f * menace).coerceAtMost(1f),
            crestHeight = (base.crestHeight + 0.35f * menace).coerceAtMost(1f),
            crest = if (rank == EnemyRank.BOSS && base.crest == CrestForm.NONE) CrestForm.TIERS else base.crest,
            brow = (base.brow + 0.3f * menace).coerceAtMost(1f),
            relief = (base.relief + 0.2f * menace).coerceAtMost(1f),
        ).normalised()
    }

    /** How tall a character's mask stands in the world, in blocks, by rank: size is the first thing a player reads. */
    fun heightFor(rank: EnemyRank?): Float = when (rank) {
        null -> HERO_HEIGHT
        EnemyRank.MINION -> 0.85f
        EnemyRank.ELITE -> 1.05f
        EnemyRank.CHAMPION -> 1.25f
        EnemyRank.BOSS -> 1.75f
    }

    /** The motion profile id for a mask: its tradition's temperament. */
    fun profileIdFor(genome: MaskGenome): String = MotionProfiles.forTradition(genome.tradition.name)

    const val HERO_HEIGHT = 0.95f

    private fun traditionFor(id: String, seed: Long): MaskTradition {
        val word = id.lowercase()
        return when {
            listOf("brute", "warrior", "ogu", "brave", "raider", "bandit").any { it in word } -> MaskTradition.MGBEDIKE
            listOf("elephant", "enyi", "tusk", "golem", "giant").any { it in word } -> MaskTradition.ELEPHANT
            listOf("guardian", "king", "ijele", "lord", "chief").any { it in word } -> MaskTradition.IJELE
            listOf("maiden", "mmuo", "spirit", "wraith", "ghost").any { it in word } -> MaskTradition.MAIDEN
            listOf("shadow", "night", "ojo", "dark").any { it in word } -> MaskTradition.OKOROSHI
            listOf("ram", "horn", "ikenga", "hunter").any { it in word } -> MaskTradition.IKENGA
            listOf("leopard", "beast", "sun", "fire", "bird").any { it in word } -> MaskTradition.MBARI
            else -> MaskTradition.entries[Math.floorMod(seed, MaskTradition.entries.size.toLong()).toInt()]
        }
    }

    /** A seed from an id that does not change between runs or JVMs (String.hashCode is specified, but mix it further). */
    private fun stableSeed(id: String): Long {
        var h = 1125899906842597L
        for (c in id) h = 31 * h + c.code
        return h xor (h ushr 29)
    }
}
