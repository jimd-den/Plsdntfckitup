package com.stratum.content.igbo

import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.content.ContentPackAssembler
import kotlin.test.Test
import kotlin.test.assertTrue

class IgboCombatTest {

    private val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))

    @Test
    fun `every damage type leaves a status the pack defines`() {
        content.damageTypes.forEach { type ->
            val status = type.ailmentStatusId
            assertTrue(status != null && content.status(status) != null, "${type.id} has no ailment")
        }
    }

    @Test
    fun `heroes have projectile skills and monsters cast skills of their own`() {
        val heroSkills = content.heroClasses.flatMap { it.abilityIds }.mapNotNull(content::skill)
        assertTrue(heroSkills.any { it.delivery == SkillDelivery.PROJECTILE })
        assertTrue(content.enemies.count { it.skills.isNotEmpty() } >= 3)
        assertTrue(content.enemies.any { enemy -> enemy.skills.mapNotNull { content.skill(it.skillId) }.any { it.castTime > 0f } }, "no telegraphed monster skill")
        assertTrue(content.enemies.any { enemy -> enemy.skills.mapNotNull { content.skill(it.skillId) }.any { it.isBeneficial } }, "no support skill")
    }

    @Test
    fun `the high priest is a boss whose fight has phases`() {
        val priest = content.enemies.single { it.id == "igbo:agbara_priest" }
        assertTrue(priest.phases.size >= 2)
        assertTrue(priest.phases.zipWithNext().all { (a, b) -> a.healthBelow > b.healthBelow }, "phases must fall in order")
        assertTrue(priest.phases.any { it.adds.isNotEmpty() } && priest.phases.any { it.enrage.isNotEmpty() })
    }

    @Test
    fun `the pack brings flasks and traits a class is born with`() {
        assertTrue(content.flasks.isNotEmpty())
        assertTrue(content.heroClasses.any { hero -> hero.traitIds.any { content.trait(it) != null } })
    }
}
