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

    /**
     * The carved mask a monster wears: one of the sculpted traditions, chosen
     * from what the monster is called (a "brute" is Mgbedike, a "maiden"
     * Agbogho Mmuo), else from a seed of its id, and rolled within that
     * tradition's grammar from the same seed, so every kind of monster has
     * its own mask and always the same one. Its rank makes it grander: a
     * boss's crown towers, its raffia hangs long. Bosses and champions with
     * no telling name are drawn from the great masquerades.
     *
     * Null when [override] names a genome or preset of its pack: an authored
     * mask wins. A carver share code as the override is worn as it is.
     */
    fun sculptedFor(definitionId: String, rank: EnemyRank = EnemyRank.MINION, override: String? = null): com.stratum.engine.model.mask.sculpt.MaskSpec? {
        val sculpt = com.stratum.engine.model.mask.sculpt.MaskCulture
        override?.trim()?.takeIf { it.isNotBlank() }?.let { text ->
            return com.stratum.engine.model.mask.sculpt.MaskCarver.decode(text)
        }
        val seed = stableSeed(definitionId)
        val word = definitionId.lowercase()
        fun one(vararg ids: String) = ids[Math.floorMod(seed, ids.size.toLong()).toInt()]
        val id = when {
            listOf("brute", "warrior", "ogu", "brave", "raider", "bandit", "war").any { it in word } -> one("mgbedike", "bugle")
            listOf("elephant", "enyi", "tusk", "golem", "giant").any { it in word } -> "ogbodo_enyi"
            listOf("guardian", "king", "ijele", "lord", "chief", "royal").any { it in word } -> one("ijele", "mwaash_ambooy")
            listOf("maiden", "mmuo", "spirit", "wraith", "ghost").any { it in word } -> one("agbogho_mmuo", "punu")
            listOf("shadow", "night", "ojo", "dark", "water", "river").any { it in word } -> "okoroshi"
            listOf("ram", "horn", "ikenga", "hunter").any { it in word } -> "ikenga"
            listOf("leopard", "beast", "storm", "lightning").any { it in word } -> "songye"
            listOf("judge", "witch", "sorcer", "hex").any { it in word } -> "fang"
            listOf("mother", "matron", "elder").any { it in word } -> one("gelede", "chokwe")
            listOf("bird", "sun", "fire").any { it in word } -> one("senufo", "dogon")
            listOf("bush", "forest", "wild").any { it in word } -> "bwa"
            rank == EnemyRank.BOSS || rank == EnemyRank.CHAMPION -> one("ijele", "gelede", "mwaash_ambooy", "mgbedike", "dogon", "bwa")
            else -> sculpt.traditions[Math.floorMod(seed, sculpt.traditions.size.toLong()).toInt()].id
        }
        val spec = sculpt.generate(sculpt.tradition(id), seed, name = definitionId)
        val menace = when (rank) { EnemyRank.MINION -> 0f; EnemyRank.ELITE -> 0.3f; EnemyRank.CHAMPION -> 0.6f; EnemyRank.BOSS -> 1f }
        return spec.copy(crownSize = maxOf(spec.crownSize, 0.35f + 0.6f * menace), raffia = maxOf(spec.raffia, 0.7f * menace))
    }

    /** How finely a monster's carved mask is cut: minions are small on screen, bosses fill it. */
    fun sculptDetailFor(rank: EnemyRank): com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail = when (rank) {
        EnemyRank.MINION, EnemyRank.ELITE -> com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail.FAR
        EnemyRank.CHAMPION, EnemyRank.BOSS -> com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail.GAME
    }

    /** A seed from an id that does not change between runs or JVMs (String.hashCode is specified, but mix it further). */
    private fun stableSeed(id: String): Long {
        var h = 1125899906842597L
        for (c in id) h = 31 * h + c.code
        return h xor (h ushr 29)
    }
}
