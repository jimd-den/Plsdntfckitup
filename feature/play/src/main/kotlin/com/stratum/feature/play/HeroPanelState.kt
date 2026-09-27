package com.stratum.feature.play

import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.difficulty.WaystoneMod
import com.stratum.core.domain.passive.PassiveTree
import com.stratum.engine.world.Held

/** The three pages of the hero panel: where the build grows, what it casts, and where it goes next. */
enum class HeroTab(val label: String) { TREE("Tree"), SKILLS("Skills"), WORLDS("Worlds") }

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
)
