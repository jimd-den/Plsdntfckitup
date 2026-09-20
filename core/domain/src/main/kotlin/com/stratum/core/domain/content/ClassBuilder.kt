package com.stratum.core.domain.content

import com.stratum.core.domain.combat.CombatStats
import kotlin.math.roundToInt

/**
 * A class the player is building.
 *
 * Point buy rather than free entry: a class you can give 999 strength is not a
 * class, it is a cheat menu, and nothing else in the game would mean anything
 * afterwards. The budget is what makes "what kind of character is this" a real
 * question.
 *
 * Pure data with pure derivation, so the numbers the builder screen shows while
 * you drag a slider are the same numbers the session will spawn you with.
 */
data class ClassDraft(
    val name: String = "",
    val title: String = "",
    val description: String = "",
    val resourceName: String = "Focus",
    val strength: Int = BASE_ATTRIBUTE,
    val agility: Int = BASE_ATTRIBUTE,
    val insight: Int = BASE_ATTRIBUTE,
    val skillIds: List<String> = emptyList(),
    val startingWeaponId: String? = null,
    val startingBlockIds: List<String> = emptyList(),
    val spriteSetId: String? = null,
) {
    val spent: Int get() = strength + agility + insight

    val remaining: Int get() = BUDGET - spent

    /**
     * What this build would actually fight like.
     *
     * Each attribute buys one obvious thing and one less obvious thing, so no
     * attribute is a dump stat and no single one is the whole answer: strength
     * is health and damage, agility is speed and crits, insight is resource and
     * a little of everything through skills.
     */
    val stats: CombatStats
        get() = CombatStats(
            maxHealth = HEALTH_FLOOR + strength * HEALTH_PER_STRENGTH,
            attackPower = (strength * ATTACK_PER_STRENGTH).roundToInt(),
            armour = (strength * ARMOUR_PER_STRENGTH).roundToInt(),
            critChance = agility * CRIT_PER_AGILITY,
            critMultiplier = BASE_CRIT_MULTIPLIER + insight * CRIT_DAMAGE_PER_INSIGHT,
            attackSpeed = agility * SPEED_PER_AGILITY,
            attackRange = 1,
        )

    val resourcePool: Int get() = RESOURCE_FLOOR + insight * RESOURCE_PER_INSIGHT

    /** How many skills this build has room for: insight buys the third one. */
    val skillCapacity: Int
        get() = if (insight >= SKILL_THRESHOLD) MAX_SKILLS else MAX_SKILLS - 1

    fun withAttribute(attribute: Attribute, value: Int): ClassDraft {
        val clamped = value.coerceIn(MIN_ATTRIBUTE, MAX_ATTRIBUTE)
        val updated = when (attribute) {
            Attribute.STRENGTH -> copy(strength = clamped)
            Attribute.AGILITY -> copy(agility = clamped)
            Attribute.INSIGHT -> copy(insight = clamped)
        }
        // Refuse the raise rather than silently taking points from elsewhere:
        // a slider that moves two bars at once is a slider nobody trusts.
        return if (updated.spent > BUDGET) this else updated
    }

    /** Toggles a skill, dropping the oldest when the build is already full. */
    fun toggling(skillId: String): ClassDraft = when {
        skillId in skillIds -> copy(skillIds = skillIds - skillId)
        skillIds.size < skillCapacity -> copy(skillIds = skillIds + skillId)
        // Full: the newest choice wins, because a tap that does nothing reads
        // as a broken button rather than as a rule.
        else -> copy(skillIds = skillIds.drop(1) + skillId)
    }

    fun togglingBlock(blockId: String): ClassDraft = when {
        blockId in startingBlockIds -> copy(startingBlockIds = startingBlockIds - blockId)
        startingBlockIds.size < MAX_HOTBAR -> copy(startingBlockIds = startingBlockIds + blockId)
        else -> copy(startingBlockIds = startingBlockIds.drop(1) + blockId)
    }

    /**
     * What is stopping this build from being saved. Empty means it is ready.
     *
     * Reported as a list rather than a boolean so the screen can say which part
     * is wrong instead of greying out a button for reasons of its own.
     */
    fun problems(available: ClassOptions): List<String> = buildList {
        if (name.isBlank()) add("Give the class a name")
        if (spent > BUDGET) add("Spend at most $BUDGET points; this build spends $spent")
        if (resourceName.isBlank()) add("Name what this class spends to use skills")
        skillIds.filterNot { it in available.skillIds }
            .forEach { add("Unknown skill '$it'") }
        if (skillIds.size > skillCapacity) {
            add("This build has room for $skillCapacity skills")
        }
        startingWeaponId?.takeIf { it !in available.weaponIds }
            ?.let { add("Unknown starting weapon '$it'") }
        startingBlockIds.filterNot { it in available.blockIds }
            .forEach { add("Unknown starting block '$it'") }
    }

    fun isReady(available: ClassOptions): Boolean = problems(available).isEmpty()

    /**
     * Turns the draft into the same kind of definition a pack ships, so a class
     * the player built and a class the pack shipped are indistinguishable
     * everywhere downstream.
     */
    fun toDefinition(packId: String = CUSTOM_PACK_ID): HeroClassDefinition = HeroClassDefinition(
        id = "$packId:${slug(name)}",
        name = name.trim(),
        title = title.trim(),
        description = description.trim(),
        baseHealth = stats.maxHealth,
        baseResource = resourcePool,
        resourceName = resourceName.trim().ifBlank { "Focus" },
        strength = strength,
        agility = agility,
        insight = insight,
        startingBlockIds = startingBlockIds,
        abilityIds = skillIds,
        spriteSetId = spriteSetId,
        baseStats = stats,
        startingWeaponId = startingWeaponId,
    )

    enum class Attribute { STRENGTH, AGILITY, INSIGHT }

    companion object {
        const val BASE_ATTRIBUTE = 10
        const val MIN_ATTRIBUTE = 4
        const val MAX_ATTRIBUTE = 24
        /** Three attributes at the baseline leave a little to spend from the start. */
        const val BUDGET = 36
        const val MAX_SKILLS = 3
        const val MAX_HOTBAR = 4
        /** Insight at or above this buys the third skill slot. */
        const val SKILL_THRESHOLD = 14

        const val CUSTOM_PACK_ID = "custom"

        private const val HEALTH_FLOOR = 40
        private const val HEALTH_PER_STRENGTH = 9
        private const val ATTACK_PER_STRENGTH = 0.8f
        private const val ARMOUR_PER_STRENGTH = 0.35f
        private const val CRIT_PER_AGILITY = 0.006f
        private const val SPEED_PER_AGILITY = 0.03f
        private const val RESOURCE_FLOOR = 20
        private const val RESOURCE_PER_INSIGHT = 4
        private const val BASE_CRIT_MULTIPLIER = 1.5f
        private const val CRIT_DAMAGE_PER_INSIGHT = 0.02f

        /** Starts a draft from an existing class, for "make one like this". */
        fun from(hero: HeroClassDefinition): ClassDraft = ClassDraft(
            name = hero.name,
            title = hero.title,
            description = hero.description,
            resourceName = hero.resourceName,
            strength = hero.strength.coerceIn(MIN_ATTRIBUTE, MAX_ATTRIBUTE),
            agility = hero.agility.coerceIn(MIN_ATTRIBUTE, MAX_ATTRIBUTE),
            insight = hero.insight.coerceIn(MIN_ATTRIBUTE, MAX_ATTRIBUTE),
            skillIds = hero.abilityIds.take(MAX_SKILLS),
            startingWeaponId = hero.startingWeaponId,
            startingBlockIds = hero.startingBlockIds.take(MAX_HOTBAR),
            spriteSetId = hero.spriteSetId,
        )

        /** "Nsibidi Scribe" -> "nsibidi_scribe". */
        fun slug(name: String): String = name.trim().lowercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .trim('_')
            .replace(Regex("_+"), "_")
            .ifBlank { "class" }
    }
}

/**
 * What the loaded packs allow a draft to reference.
 *
 * Passed in rather than reached for, so the builder stays pure and a class built
 * against one pack can be validated against another before it is loaded.
 */
data class ClassOptions(
    val skillIds: Set<String> = emptySet(),
    val weaponIds: Set<String> = emptySet(),
    val blockIds: Set<String> = emptySet(),
) {
    companion object {
        fun from(content: AssembledContent) = ClassOptions(
            skillIds = content.skills.map { it.id }.toSet(),
            weaponIds = content.weapons.map { it.id }.toSet(),
            blockIds = content.registry.all.map { it.id }.toSet(),
        )
    }
}

/**
 * Wraps player-made classes as a content pack.
 *
 * Classes are pack data, so a class the player built has to become pack data or
 * it would need a second loading path that behaves almost the same — which is
 * how the two quietly drift apart.
 */
object CustomClassPack {

    fun of(classes: List<HeroClassDefinition>): ContentPack = ContentPack(
        id = ClassDraft.CUSTOM_PACK_ID,
        name = "Your classes",
        author = "you",
        description = "Classes built in the class forge.",
        heroClasses = classes,
    )
}
