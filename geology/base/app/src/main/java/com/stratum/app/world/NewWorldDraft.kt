package com.stratum.app.world

import com.stratum.core.domain.creation.InstantWorld
import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.world.RulesPreset
import com.stratum.core.domain.world.RulesPresets
import com.stratum.core.domain.world.WorldRules

/** The three steps of making a world, in order. */
enum class NewWorldStep(val label: String) { HERO("Hero"), WORLD("World"), GO("Go") }

/**
 * A world being set up, kept while the player steps through it -- and while
 * they detour to the class forge to quick-make a hero and come back.
 *
 * Plain data with pure transitions, so the flow is tested without a screen.
 */
data class NewWorldDraft(
    val step: NewWorldStep = NewWorldStep.HERO,
    /** Null until picked; the screen shows the first class as chosen. */
    val heroClassId: String? = null,
    val presetId: String = RulesPresets.adventure.id,
    val rules: WorldRules = RulesPresets.adventure.rules,
    val name: String = "",
    /** Typed by the player; blank means "surprise me". */
    val seedText: String = "",
    /**
     * "Describe a world", optional. When filled the world plays at once in
     * the look the words suggest, and the crew writes the rest while it does.
     */
    val prompt: String = "",
    /** A language model's reading of [prompt], when the player asked for one: its values win over the words'. */
    val modelScene: Map<String, String> = emptyMap(),
    val modelNotes: List<String> = emptyList(),
    /** For the prompt the model read, so an edited prompt drops a stale reading. */
    val modelSceneFor: String = "",
    val readingScene: Boolean = false,
) {
    /**
     * The scene the words describe: the land, its towns, its buildings and
     * its danger, read at once from the words and refined by a model's reading
     * when there is one for this exact prompt.
     */
    val scene: com.stratum.engine.microbridge.SceneSpec
        get() {
            val words = com.stratum.engine.microbridge.ScenePrompt.read(prompt)
            if (modelSceneFor != prompt.trim() || modelScene.isEmpty()) return words
            return com.stratum.engine.microbridge.ScenePrompt.merge(words, com.stratum.engine.microbridge.SceneSpec(modelScene, modelNotes))
        }

    /** The described world, read at once and without a model; null when nothing was described. */
    val described: InstantWorld? get() = prompt.trim().takeIf { it.isNotEmpty() }?.let { InstantWorld.from(it, name) }

    val preset: RulesPreset? get() = RulesPresets.all.firstOrNull { it.id == presetId }

    fun next(): NewWorldDraft = copy(step = NewWorldStep.entries.getOrElse(step.ordinal + 1) { step })

    fun back(): NewWorldDraft = copy(step = NewWorldStep.entries.getOrElse(step.ordinal - 1) { step })

    fun choosePreset(preset: RulesPreset): NewWorldDraft = copy(presetId = preset.id, rules = preset.rules)

    /**
     * The seed the world will be built from. A typed number is used as it is;
     * typed words are hashed, so "river" is always the same world; blank takes
     * [fallback], which the caller rolls.
     */
    fun seed(fallback: Long): Long {
        val text = seedText.trim()
        if (text.isEmpty()) return fallback
        return text.toLongOrNull() ?: text.fold(1125899906842597L) { hash, c -> 31 * hash + c.code }
    }

    /**
     * What the world is called: the player's name for it, or a numbered
     * default so two unnamed worlds never read the same in the list.
     */
    fun resolvedName(existingWorlds: Int): String = name.trim().ifEmpty { described?.name ?: "World ${existingWorlds + 1}" }

    /** The preset's name for the world's card, or empty when the dials were turned by hand. */
    fun presetLabel(): String = preset?.takeIf { it.rules == rules }?.name.orEmpty()

    /** The launch for this draft, into [identity]'s slot from the world library. */
    fun launch(identity: WorldIdentity, defaultHeroClassId: String?, fallbackSeed: Long): WorldLaunch.New = WorldLaunch.New(
        identity = identity,
        heroClassId = heroClassId ?: defaultHeroClassId,
        rules = rules,
        seed = seed(fallbackSeed),
    )

    companion object {
        /** What a world whose dials were turned by hand is filed under. */
        const val CUSTOM = "Custom"
    }
}
