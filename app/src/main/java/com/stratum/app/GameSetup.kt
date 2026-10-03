package com.stratum.app

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.attack.AttackCode
import com.stratum.core.domain.attack.AttackCompiler
import com.stratum.core.domain.attack.AttackVocabulary
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldRules

/**
 * Composition root for a run.
 *
 * This is the only place that knows the built-in pack exists. Everything
 * downstream receives an [AssembledContent] and cannot tell whether it came from
 * the shipped module, an AI generation run, or a file the player imported.
 */
object GameSetup {

    private val assembler = ContentPackAssembler()

    /**
     * Packs enabled for the next run, built-in first so later packs can override it.
     *
     * The forge's pack goes last: every enemy is armed with a forged attack
     * suited to its role, and the attacks the player equipped in the Forge
     * of Will ([forged], as attack codes) join every class's skills.
     */
    fun assemble(additionalPacks: List<ContentPack> = emptyList(), forged: List<String> = emptyList()): AssembledContent {
        val packs = listOf(IgboContentPack.pack) + additionalPacks
        val base = assembler.assemble(packs)
        val attacks = forged.mapNotNull(AttackCode::decode).distinctBy { it.id }
        val forge = AttackCompiler.pack(attacks, AttackVocabulary.from(base.damageTypes), enemies = base.enemies)
        val heroes = if (attacks.isEmpty()) emptyList() else base.heroClasses.map { hero ->
            hero.copy(abilityIds = hero.abilityIds + attacks.map { it.id }.filterNot(hero.abilityIds::contains))
        }
        return assembler.assemble(packs + forge.copy(heroClasses = heroes))
    }

    /**
     * @param streamingRadius chunks kept loaded each way from the player. Four
     *   (nine by nine chunks, 144 blocks across) keeps the loaded edge well past
     *   the fog; cheaper phones keep fewer and draw a shorter view to match.
     */
    fun worldConfig(
        seed: Long = System.currentTimeMillis(),
        streamingRadius: Int = 4,
        rules: WorldRules = WorldRules(),
    ): WorldConfig = WorldConfig(
        seed = seed,
        simulationRadius = streamingRadius,
        seaLevel = 12,
        surfaceVariation = 4,
        caveDensity = 0.44f,
        oreRichness = 1f,
        rules = rules,
    )
}
