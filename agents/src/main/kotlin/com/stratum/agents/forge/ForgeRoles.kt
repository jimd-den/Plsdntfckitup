package com.stratum.agents.forge

import com.stratum.agents.StudioBrief
import com.stratum.core.domain.ai.AgentRoleDefinition
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.PowerTier

/**
 * The one-role crew each order is, and the brief it works from. The role
 * says which sections it writes and what it is for; the brief carries this
 * request's rules and the exact vocabulary it may use.
 */
object ForgeRoles {

    const val NS = "stratum.forge"

    fun role(order: ForgeOrder): AgentRoleDefinition = when (order) {
        is ForgeOrder.Lore -> AgentRoleDefinition(
            "$NS:chronicler", "Chronicler", "📜", "Writes codex entries about things this world already has.", listOf("lore"), temperature = 0.95f,
        )
        is ForgeOrder.Base -> AgentRoleDefinition(
            "$NS:smith", "Smith", if (order.slot == ItemSlot.WEAPON) "⚔" else "🛡",
            "Designs the base a piece of gear rolls on: its hit or its defences.", listOf("itemBases"),
        )
        is ForgeOrder.Affixes -> AgentRoleDefinition(
            "$NS:enchanter", "Enchanter", "✧", "Designs tiered affixes and where they may roll.", listOf("affixes"),
        )
        is ForgeOrder.Unique -> AgentRoleDefinition(
            "$NS:artificer", "Artificer", "✦", "Designs a named item a build is planned around, and the story behind it.", listOf("uniques", "lore"),
        )
        is ForgeOrder.ItemSet -> AgentRoleDefinition(
            "$NS:regalia", "Regalia maker", "❖", "Designs pieces that are stronger together, and the story that binds them.",
            listOf("itemSets", "uniques", "lore"),
        )
    }

    fun brief(order: ForgeOrder, context: ForgeContext, vocabulary: ForgeVocabulary, packId: String, packName: String): StudioBrief =
        StudioBrief(
            prompt = listOf(order.prompt.trim(), context.theme.trim().takeIf { it.isNotEmpty() }?.let { "(setting: $it)" }).filterNotNull().joinToString(" "),
            packId = packId,
            packName = packName,
            author = "Content forge",
            modelId = context.modelId,
            guidance = common(context) + specific(order, context),
            vocabulary = vocabularyFor(order, vocabulary),
        )

    private fun common(context: ForgeContext): List<String> = listOfNotNull(
        "Item levels ${context.levels.first} to ${context.levels.last}: every minItemLevel you write is inside that range.",
        "Write shares as decimals: 0.2 means 20%, 1.5 means 150%. Flat amounts of health, damage and armour are plain numbers.",
        when (context.budget) {
            PowerTier.BALANCED -> "Power: balanced. Numbers comparable to the examples; at most one rule-bending flag. Anything oversized is scaled down."
            PowerTier.STRONG -> "Power: strong. Clearly better than the examples, and anything that bends a rule pays for it with a real drawback."
            PowerTier.BROKEN -> "Power: broken. This is a sandbox and broken builds are welcome: stack flags, go big, bend every rule you like -- " +
                "but every number must still be one the format accepts (no share above 10, which is 1000%)."
        },
    )

    private fun specific(order: ForgeOrder, context: ForgeContext): List<String> = when (order) {
        is ForgeOrder.Lore -> listOfNotNull(
            "Write ${order.count} lore entries${order.category?.let { " of category ${it.name.lowercase()}" }.orEmpty()}, each a title and one or two paragraphs.",
            "Each entry's \"subject\" is one of the ids under SUBJECTS, so it surfaces beside that thing in the game.",
            order.subjects.takeIf { it.isNotEmpty() }?.let { "Write about these, in order: ${it.joinToString()}." },
            "Name things. Echo names from the existing lore where they fit. No generic fantasy filler and no describing the player.",
        )
        is ForgeOrder.Base -> listOfNotNull(
            if (order.ladder) {
                "Write 3 to 5 itemBases of one family: the same kind of ${order.slot.name.lowercase()}, each rung stronger than the last " +
                    "and dropping deeper, with minItemLevel climbing from ${context.levels.first} to ${context.levels.last}."
            } else {
                "Write exactly one itemBase with slot ${order.slot.name.lowercase()}."
            },
            if (order.slot == ItemSlot.WEAPON) {
                "A weapon has a damageType from the list, minDamage, maxDamage and attackSpeed (about 0.7 to 1.8); twoHanded if it takes both hands."
            } else {
                "Armour has defences, such as { \"stat\": \"armour\", \"value\": 20 }; implicits are optional."
            },
            "Tags are words affixes match on; reuse tags from the list where they fit.",
        )
        is ForgeOrder.Affixes -> listOfNotNull(
            "Write ${order.count} affixes, each with 2 to 4 tiers, weakest first, deeper tiers at a higher minItemLevel and with their own name.",
            "kind is prefix or suffix. Each tier has \"modifiers\": [{ \"stat\", \"kind\", \"min\", \"max\" }].",
            order.slots.takeIf { it.isNotEmpty() }?.let { "Every affix has \"slots\": [${it.joinToString { s -> "\"${s.name.lowercase()}\"" }}]." }
                ?: "Give each affix the \"slots\" it suits.",
        )
        is ForgeOrder.Unique -> listOfNotNull(
            "Write exactly one unique, on a base from the bases list${order.slot?.let { " for ${it.name.lowercase()}" }.orEmpty()}.",
            "Give it modifiers, and flags from the list if the idea bends a rule. Drawbacks are welcome: a unique that is only an upgrade is a rare with a name.",
            "Its name and flavour come from this world: write one lore entry (category artifact) whose \"subject\" is the unique's id, " +
                "telling where it came from, and let the flavour quote or echo it.",
        )
        is ForgeOrder.ItemSet -> listOf(
            "Write one itemSet with bonuses at 2 up to ${order.pieces} pieces, and ${order.pieces} uniques whose \"set\" is the set's id, each on a different base.",
            "Set bonuses carry fixed \"modifiers\" ({ \"stat\", \"kind\", \"value\" }) and may carry flags.",
            "Write one lore entry (category artifact) whose \"subject\" is the set's id, telling what binds the pieces; echo it in their flavour.",
        )
    }

    private fun vocabularyFor(order: ForgeOrder, vocabulary: ForgeVocabulary): Map<String, List<String>> {
        val words = LinkedHashMap<String, List<String>>()
        when (order) {
            is ForgeOrder.Lore -> {
                words["category"] = vocabulary.loreCategories
                vocabulary.subjects.forEach { (kind, ids) -> words["SUBJECTS: $kind"] = ids.take(MAX_LISTED) }
                vocabulary.loreLines().takeIf { it.isNotEmpty() }?.let { words["existing lore"] = it }
            }
            is ForgeOrder.Base -> words += vocabulary.gearWords()
            is ForgeOrder.Affixes -> {
                words += vocabulary.gearWords()
                words["kind (of an affix)"] = vocabulary.affixKinds
            }
            is ForgeOrder.Unique, is ForgeOrder.ItemSet -> {
                words += vocabulary.gearWords()
                words["flags (rules gear may break)"] = vocabulary.flags
                val slot = (order as? ForgeOrder.Unique)?.slot
                vocabulary.basesFor(slot).groupBy { it.slot }.forEach { (s, bases) -> words["bases (${s.name.lowercase()})"] = bases.take(MAX_LISTED).map { it.id } }
                words["category"] = vocabulary.loreCategories
                vocabulary.loreLines().takeIf { it.isNotEmpty() }?.let { words["existing lore"] = it }
            }
        }
        return words
    }

    private const val MAX_LISTED = 30
}
