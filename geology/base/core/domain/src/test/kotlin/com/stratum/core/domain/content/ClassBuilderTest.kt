package com.stratum.core.domain.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassBuilderTest {

    private val options = ClassOptions(
        skillIds = setOf("pack:cleave", "pack:bolt", "pack:ward", "pack:mend"),
        weaponIds = setOf("pack:club"),
        blockIds = setOf("pack:soil", "pack:stone"),
    )

    private fun draft(name: String = "Nsibidi Scribe") = ClassDraft(name = name)

    @Test
    fun `a fresh draft starts inside its budget with something left to spend`() {
        val fresh = draft()
        assertEquals(ClassDraft.BASE_ATTRIBUTE * 3, fresh.spent)
        assertTrue(fresh.remaining > 0, "a new build had nothing to spend, so there is no choice to make")
        assertTrue(fresh.isReady(options))
    }

    @Test
    fun `a raise that would overspend is refused rather than robbing another attribute`() {
        val maxed = draft().withAttribute(ClassDraft.Attribute.STRENGTH, ClassDraft.MAX_ATTRIBUTE)
        val agility = maxed.agility

        val overspent = maxed.withAttribute(ClassDraft.Attribute.AGILITY, ClassDraft.MAX_ATTRIBUTE)

        assertEquals(maxed, overspent, "the raise was accepted despite exceeding the budget")
        assertEquals(agility, overspent.agility, "a refused raise moved a different attribute")
        assertTrue(overspent.spent <= ClassDraft.BUDGET)
    }

    @Test
    fun `attributes are clamped to their range`() {
        val low = draft().withAttribute(ClassDraft.Attribute.INSIGHT, -50)
        assertEquals(ClassDraft.MIN_ATTRIBUTE, low.insight)

        // The clamp and the budget are two separate gates, and the budget is
        // the outer one: asking for 9999 from a fresh build cannot succeed even
        // clamped, because the other two attributes are still holding points.
        val greedy = draft().withAttribute(ClassDraft.Attribute.AGILITY, 9999)
        assertEquals(ClassDraft.BASE_ATTRIBUTE, greedy.agility, "an unaffordable raise was accepted")

        val freed = draft()
            .withAttribute(ClassDraft.Attribute.STRENGTH, ClassDraft.MIN_ATTRIBUTE)
            .withAttribute(ClassDraft.Attribute.INSIGHT, ClassDraft.MIN_ATTRIBUTE)
            .withAttribute(ClassDraft.Attribute.AGILITY, 9999)
        assertEquals(ClassDraft.MAX_ATTRIBUTE, freed.agility, "the ceiling did not hold once it was affordable")
        assertTrue(freed.spent <= ClassDraft.BUDGET)
    }

    @Test
    fun `every attribute buys something, so none of them is a dump stat`() {
        val base = draft()
        val strong = base.withAttribute(ClassDraft.Attribute.STRENGTH, ClassDraft.BASE_ATTRIBUTE + 4)
        val quick = base.withAttribute(ClassDraft.Attribute.AGILITY, ClassDraft.BASE_ATTRIBUTE + 4)
        val wise = base.withAttribute(ClassDraft.Attribute.INSIGHT, ClassDraft.BASE_ATTRIBUTE + 4)

        assertTrue(strong.stats.maxHealth > base.stats.maxHealth)
        assertTrue(strong.stats.attackPower > base.stats.attackPower)
        assertTrue(quick.stats.critChance > base.stats.critChance)
        assertTrue(quick.stats.attackSpeed > base.stats.attackSpeed)
        assertTrue(wise.resourcePool > base.resourcePool)
        assertTrue(wise.stats.critMultiplier > base.stats.critMultiplier)
    }

    @Test
    fun `insight buys the third skill slot`() {
        val plain = draft().withAttribute(ClassDraft.Attribute.INSIGHT, ClassDraft.SKILL_THRESHOLD - 1)
        val wise = draft().withAttribute(ClassDraft.Attribute.INSIGHT, ClassDraft.SKILL_THRESHOLD)

        assertEquals(ClassDraft.MAX_SKILLS - 1, plain.skillCapacity)
        assertEquals(ClassDraft.MAX_SKILLS, wise.skillCapacity)
    }

    @Test
    fun `toggling a skill adds it, and toggling it again takes it off`() {
        val picked = draft().toggling("pack:cleave")
        assertEquals(listOf("pack:cleave"), picked.skillIds)
        assertEquals(emptyList(), picked.toggling("pack:cleave").skillIds)
    }

    @Test
    fun `a full skill list drops the oldest rather than ignoring the tap`() {
        val wise = draft().withAttribute(ClassDraft.Attribute.INSIGHT, ClassDraft.SKILL_THRESHOLD)
        val full = wise.toggling("pack:cleave").toggling("pack:bolt").toggling("pack:ward")
        assertEquals(ClassDraft.MAX_SKILLS, full.skillIds.size)

        val replaced = full.toggling("pack:mend")

        assertEquals(ClassDraft.MAX_SKILLS, replaced.skillIds.size)
        assertEquals(listOf("pack:bolt", "pack:ward", "pack:mend"), replaced.skillIds)
    }

    @Test
    fun `an unnamed build says so rather than saving as an empty class`() {
        val problems = draft(name = "   ").problems(options)
        assertFalse(draft(name = "  ").isReady(options))
        assertTrue(problems.any { it.contains("name", ignoreCase = true) }, problems.toString())
    }

    @Test
    fun `a build referencing content the packs do not have is rejected by name`() {
        val stray = draft()
            .toggling("pack:nonexistent")
            .copy(startingWeaponId = "pack:ghost", startingBlockIds = listOf("pack:vapour"))

        val problems = stray.problems(options)

        assertTrue(problems.any { it.contains("pack:nonexistent") }, problems.toString())
        assertTrue(problems.any { it.contains("pack:ghost") }, problems.toString())
        assertTrue(problems.any { it.contains("pack:vapour") }, problems.toString())
    }

    @Test
    fun `the saved class carries the numbers the builder showed`() {
        val built = draft()
            .withAttribute(ClassDraft.Attribute.STRENGTH, 16)
            .copy(resourceName = "Ike", startingWeaponId = "pack:club")
            .toggling("pack:cleave")

        val definition = built.toDefinition()

        assertEquals(built.stats, definition.baseStats)
        assertEquals(built.stats.maxHealth, definition.resolvedStats.maxHealth)
        assertEquals(built.resourcePool, definition.baseResource)
        assertEquals("Ike", definition.resourceName)
        assertEquals(listOf("pack:cleave"), definition.abilityIds)
        assertEquals("pack:club", definition.startingWeaponId)
        assertEquals("custom:nsibidi_scribe", definition.id)
    }

    @Test
    fun `names become ids without colliding with punctuation`() {
        assertEquals("nsibidi_scribe", ClassDraft.slug("Nsibidi Scribe"))
        assertEquals("ogu_s_own", ClassDraft.slug("  Ogu's Own  "))
        assertEquals("class", ClassDraft.slug("!!!"), "a name of pure punctuation left an empty id")
    }

    @Test
    fun `a draft taken from an existing class round trips its choices`() {
        val original = ClassDraft(
            name = "Tidewright",
            strength = 14,
            agility = 12,
            insight = ClassDraft.SKILL_THRESHOLD,
            resourceName = "Brine",
            startingWeaponId = "pack:club",
        ).toggling("pack:cleave").toggling("pack:bolt")

        val again = ClassDraft.from(original.toDefinition())

        assertEquals(original.strength, again.strength)
        assertEquals(original.agility, again.agility)
        assertEquals(original.insight, again.insight)
        assertEquals(original.skillIds, again.skillIds)
        assertEquals(original.resourceName, again.resourceName)
        assertEquals(original.startingWeaponId, again.startingWeaponId)
    }

    @Test
    fun `the class carries the art it was given, and can give it back`() {
        val drawn = draft().copy(spriteSetId = "hero:nsibidi_scribe")

        val definition = drawn.toDefinition()

        assertEquals("hero:nsibidi_scribe", definition.spriteSetId)
        assertEquals("hero:nsibidi_scribe", ClassDraft.from(definition).spriteSetId)
    }

    @Test
    fun `no art chosen means shapes, not a broken reference`() {
        val definition = draft().toDefinition()

        assertNull(definition.spriteSetId)
    }

    @Test
    fun `player classes are wrapped as a pack, so nothing downstream needs a second path`() {
        val pack = CustomClassPack.of(listOf(draft().toDefinition()))
        assertEquals(ClassDraft.CUSTOM_PACK_ID, pack.id)
        assertEquals(1, pack.heroClasses.size)
    }
}
