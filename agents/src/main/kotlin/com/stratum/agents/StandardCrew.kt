package com.stratum.agents

import com.stratum.core.domain.ai.AgentRoleDefinition

/**
 * The crew a world gets when its packs bring none: seven roles that between
 * them write a whole world -- its land, its peoples, its monsters, its
 * towns, what keeps a body alive in it and what an outpost builds there.
 *
 * Order comes from the dependencies, not this list: the bestiary waits for
 * the factions, the architect for the monsters that will garrison its towns.
 */
object StandardCrew {

    const val NS = "stratum.crew"

    val loremaster = AgentRoleDefinition(
        id = "$NS:loremaster", name = "Loremaster", glyph = "📜",
        description = "Sets the tone: the factions that contend for this world, and the lore that explains them.",
        sections = listOf("factions", "lore"),
        brief = "Write 3 to 5 factions with distinct colours and relations to each other (HOSTILE, NEUTRAL or ALLIED), " +
            "at least one the player can befriend and one that is hostile from the start, and 4 to 6 lore entries.",
        requiresApproval = true,
    )

    val cartographer = AgentRoleDefinition(
        id = "$NS:cartographer", name = "Cartographer", glyph = "🗺",
        description = "Makes the land: the blocks it is built of and the regions they form.",
        sections = listOf("blocks", "biomes"),
        brief = "Write 10 to 16 blocks (soil, stone, ore, wood, foliage, and at least one liquid) and 3 to 5 regions using them, " +
            "each region with a temperature from -1 (freezing) to 1 (scorching).",
    )

    val bestiary = AgentRoleDefinition(
        id = "$NS:bestiary", name = "Bestiary", glyph = "🐺",
        description = "Populates it: monsters and soldiers for each faction, and the packs they hunt in.",
        sections = listOf("enemies", "enemyPacks"),
        dependsOn = listOf(loremaster.id, cartographer.id),
        brief = "Write 6 to 10 enemies, most belonging to a faction, with a mix of roles (MELEE, RANGED, SUPPORT, SWARMER, BRUTE), " +
            "spawning in the regions that exist; and 2 to 4 packs led by one of them. Guards meant only for towns have spawnWeight 0.",
    )

    val architect = AgentRoleDefinition(
        id = "$NS:architect", name = "Architect", glyph = "🏛",
        description = "Builds the towns, camps and fortresses each faction holds.",
        sections = listOf("settlements"),
        dependsOn = listOf(loremaster.id, cartographer.id, bestiary.id),
        brief = "Write one settlement per major faction, with a layout of GRID, ORGANIC, FORTRESS or CAMP, built from existing blocks, " +
            "garrisoned by that faction's enemies.",
    )

    val steward = AgentRoleDefinition(
        id = "$NS:steward", name = "Steward", glyph = "🍖",
        description = "Decides what keeps a body alive here: food, drink, and what the land yields when harvested.",
        sections = listOf("consumables", "recipes", "forage"),
        dependsOn = listOf(cartographer.id),
        brief = "Write 4 to 6 foods true to this world, 2 or 3 recipes (station stratum:fire for cooking), and forage rules on existing blocks.",
    )

    val warlord = AgentRoleDefinition(
        id = "$NS:warlord", name = "Warlord", glyph = "🏰",
        description = "Designs what an outpost builds and whom it trains.",
        sections = listOf("structures", "units"),
        dependsOn = listOf(bestiary.id),
        brief = "Write 3 to 5 structures and 1 to 3 units in this world's idiom, costed in stratum:food, stratum:timber, stratum:stone and " +
            "stratum:metal. Each unit's actor must be an existing enemy id belonging to the player's allies.",
    )

    val arbiter = AgentRoleDefinition(
        id = "$NS:arbiter", name = "Arbiter", glyph = "⚖",
        description = "Suggests how this world is best played.",
        sections = listOf("rules"),
        dependsOn = listOf(loremaster.id, bestiary.id, architect.id, steward.id),
        brief = "Write one rules object: survival OFF, GENTLE or HARSH; townDensity and monsterDensity 0.25 to 3; raids true or false; " +
            "dayLengthMinutes 2 to 120. Choose what suits the world's tone.",
        maxAttempts = 2,
    )

    val all = listOf(loremaster, cartographer, bestiary, architect, steward, warlord, arbiter)
}
