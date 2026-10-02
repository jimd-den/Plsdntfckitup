package com.stratum.app

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.attack.AttackCode
import com.stratum.core.domain.attack.AttackForge
import org.junit.Test
import kotlin.test.assertTrue

/** The forge's pack in the shipped content: enemies armed, carried attacks on every class. */
class ForgedContentTest {

    @Test
    fun `every enemy carries a forged attack, and carried attacks join every class`() {
        val attacks = List(3) { AttackForge.roll(it * 17L + 1, CombatRole.entries[it]) }
        val content = GameSetup.assemble(forged = attacks.map(AttackCode::encode) + "ak1:not-an-attack")
        assertTrue(content.enemies.isNotEmpty())
        assertTrue(content.enemies.all { e -> e.skills.any { it.skillId.startsWith("forge:") } }, "an enemy went unarmed")
        assertTrue(content.heroClasses.all { hero -> attacks.all { it.id in hero.abilityIds } }, "a class is missing a carried attack")
        attacks.forEach { a -> assertTrue(content.skills.any { it.id == a.id }, "${a.name} never compiled") }
    }

    @Test
    fun `nothing forged leaves the classes as they were`() {
        val plain = GameSetup.assemble()
        assertTrue(plain.heroClasses.all { hero -> hero.abilityIds.none { it.startsWith("forge:") } })
    }
}
