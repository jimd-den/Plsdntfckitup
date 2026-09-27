package com.stratum.agents.forge

import com.stratum.agents.ApprovalGate
import com.stratum.agents.StepStatus
import com.stratum.agents.StudioJournal
import com.stratum.agents.StudioPipeline
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.item.PowerBudget
import com.stratum.core.domain.item.PowerEstimate

/** How strong one forged thing reads, for a card's label. */
data class Appraisal(val section: String, val id: String, val name: String, val estimate: PowerEstimate)

/** What a forge request came to: a fragment that loads, or the reason it could not be made. */
sealed interface ForgeOutcome {
    val journal: StudioJournal

    data class Forged(
        /** A content pack holding only what was made, validated against the loaded packs. */
        val fragment: ContentPack,
        val appraisals: List<Appraisal>,
        /** Everything the repair changed, for a person who wants to know what the model actually wrote. */
        val repairs: List<String>,
        override val journal: StudioJournal,
    ) : ForgeOutcome

    data class Rejected(val reason: String, override val journal: StudioJournal) : ForgeOutcome
}

/**
 * The armoury and the chronicle: one short prompt in, one validated
 * fragment out -- lore, a weapon or armour base or a ladder of them,
 * affixes, a unique, a set.
 *
 * Not a generator of its own. Each order is a one-role crew on the same
 * [StudioPipeline] the agent studio runs: the same prompt composer, the
 * same plugin checks, the same retries with problems quoted back, the same
 * journal. What the forge adds is the request's vocabulary in the prompt, a
 * [ForgeRepair] that mends and clamps the reply, and the power budget.
 */
class ContentForge(
    private val model: LanguageModelPort,
    private val base: List<ContentPack>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(base.isNotEmpty()) { "The forge builds on loaded packs, and none are loaded" }
    }

    val vocabulary: ForgeVocabulary by lazy { ForgeVocabulary(ContentPackAssembler().assemble(base)) }

    suspend fun forge(
        order: ForgeOrder,
        context: ForgeContext = ForgeContext(),
        packId: String = Creations.ID,
        packName: String = Creations.NAME,
        onProgress: (StudioJournal) -> Unit = {},
    ): ForgeOutcome {
        if (order.prompt.isBlank()) {
            return ForgeOutcome.Rejected("Describe what to make first.", StudioJournal(order.prompt, emptyList()))
        }
        val role = ForgeRoles.role(order)
        val brief = ForgeRoles.brief(order, context, vocabulary, packId, packName)
        val pipeline = StudioPipeline(model, base, clock, ForgeRepair(vocabulary, order, context))
        val outcome = pipeline.run(brief, listOf(role), ApprovalGate.ApproveAll, onProgress)
        // The crew keeps whatever loads; the forge was asked for one thing, and an empty pack is not it.
        val done = outcome.journal.steps.all { it.status == StepStatus.DONE }
        val pack = outcome.pack?.takeIf { done } ?: return ForgeOutcome.Rejected(reasonFor(outcome.journal), outcome.journal)
        val repairs = outcome.journal.steps.flatMap { step -> step.attempts.lastOrNull()?.repairs.orEmpty() }
        return ForgeOutcome.Forged(pack, appraise(pack, context.levels.first), repairs, outcome.journal)
    }

    /**
     * Why nothing came of it, in one line a person can act on: the last
     * attempt's problems, which are also what the model was last told.
     */
    private fun reasonFor(journal: StudioJournal): String {
        val step = journal.steps.firstOrNull()
        val problems = step?.latest?.problems.orEmpty().filterNot { it.startsWith("(while reading") }
        val why = problems.take(MAX_REASONS).joinToString("; ").ifBlank { step?.reason ?: journal.problems.firstOrNull() ?: "nothing usable came back" }
        return "The forge could not make that: $why."
    }

    companion object {
        private const val MAX_REASONS = 3

        /** How strong each thing in [pack] reads at [itemLevel] and above. */
        fun appraise(pack: ContentPack, itemLevel: Int): List<Appraisal> =
            pack.itemBases.map { Appraisal("itemBases", it.id, it.name, PowerBudget.base(it)) } +
                pack.affixes.map { Appraisal("affixes", it.id, it.name, PowerBudget.affix(it)) } +
                pack.uniques.map { Appraisal("uniques", it.id, it.name, PowerBudget.unique(it)) } +
                pack.itemSets.map { Appraisal("itemSets", it.id, it.name, PowerBudget.set(it, itemLevel)) }
    }
}
