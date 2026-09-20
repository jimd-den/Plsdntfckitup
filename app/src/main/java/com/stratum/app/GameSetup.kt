package com.stratum.app

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.WorldConfig

/**
 * Composition root for a run.
 *
 * This is the only place that knows the built-in pack exists. Everything
 * downstream receives an [AssembledContent] and cannot tell whether it came from
 * the shipped module, an AI generation run, or a file the player imported.
 */
object GameSetup {

    private val assembler = ContentPackAssembler()

    /** Packs enabled for the next run, built-in first so later packs can override it. */
    fun assemble(additionalPacks: List<ContentPack> = emptyList()): AssembledContent =
        assembler.assemble(listOf(IgboContentPack.pack) + additionalPacks)

    fun worldConfig(seed: Long = System.currentTimeMillis()): WorldConfig = WorldConfig(
        seed = seed,
        simulationRadius = 2,
        seaLevel = 12,
        surfaceVariation = 4,
        caveDensity = 0.44f,
        oreRichness = 1f,
    )
}
