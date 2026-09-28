package com.stratum.feature.play

import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.difficulty.WaystoneMod
import com.stratum.core.domain.passive.PassiveTree
import com.stratum.engine.world.Held

/** The pages of the hero panel: where the build grows, what it casts, its numbers, where it goes next, and how it looks. */
enum class HeroTab(val label: String) { TREE("Tree"), SKILLS("Skills"), STATS("Stats"), WORLDS("Worlds"), LOOK("Look") }

/**
 * The looks the player owns and which one is worn. They belong to the player,
 * not the world, so the app hands them in and hears the pick; the world
 * redraws the hero in the new look as soon as it is chosen.
 */
data class HeroLooks(
    val looks: List<com.stratum.core.designsystem.component.LookChoice> = emptyList(),
    /** Null is the class's own art. */
    val wornId: String? = null,
    val onPick: (String?) -> Unit = {},
    /** Leaves for the sprite forge to draw a new one; null hides the way there. */
    val onMake: (() -> Unit)? = null,
)

/** Everything the hero panel draws. Grouped so the play screen gains one field, not twelve. */
data class HeroPanelState(
    val open: Boolean = false,
    val tab: HeroTab = HeroTab.TREE,
    val tree: PassiveTree? = null,
    val startId: String? = null,
    /** The node the player last tapped, and the path taking it would buy. */
    val selectedNode: String? = null,
    val path: List<String> = emptyList(),
    /** Which skill a tapped support links to. */
    val selectedSkill: String? = null,
    val supportsBySkill: Map<String, List<SupportDefinition>> = emptyMap(),
    val heldSupports: List<Held<SupportDefinition>> = emptyList(),
    /** The world tier this world runs at, and the waystone mods it opened with. */
    val tier: Int = 0,
    val worldMods: List<WaystoneMod> = emptyList(),
    /** The number the stats page is explaining, and its explanation. */
    val query: com.stratum.core.domain.sandbox.StatQuery = com.stratum.core.domain.sandbox.StatQuery(com.stratum.core.domain.sandbox.ExplainedStat.DAMAGE),
    val breakdown: com.stratum.core.domain.sandbox.StatBreakdown? = null,
    val damageTypes: List<NamedChoice> = emptyList(),
)
