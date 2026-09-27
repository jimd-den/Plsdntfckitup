package com.stratum.core.domain.content

import com.stratum.core.domain.actor.BossPhase
import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.MonsterSkill
import com.stratum.core.domain.actor.PackMember
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.combat.DamageTypeDefinition
import com.stratum.core.domain.combat.FlaskDefinition
import com.stratum.core.domain.combat.TraitDefinition
import com.stratum.core.domain.combat.TriggerDefinition
import com.stratum.core.domain.combat.TriggerEvent
import com.stratum.core.domain.status.StatusBehaviour
import com.stratum.core.domain.status.StatusDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class CombatValidationTest {

    private val fire = DamageTypeDefinition("t:fire", "Fire", ailmentStatusId = "t:burn", ailmentChance = 0.2f)
    private val burn = StatusDefinition("t:burn", "Burn", behaviours = listOf(StatusBehaviour.DamageOverTime("t:fire", hitShare = 0.2f)))
    private val bolt = SkillDefinition("t:bolt", "Bolt", damageTypeId = fire.id, effects = listOf(SkillEffect.Damage(fire.id), SkillEffect.ApplyStatus(burn.id)))
    private val imp = EnemyDefinition("t:imp", "Imp", damageTypeId = fire.id, skills = listOf(MonsterSkill(bolt.id)))

    private fun pack(
        damageTypes: List<DamageTypeDefinition> = listOf(fire),
        statuses: List<StatusDefinition> = listOf(burn),
        skills: List<SkillDefinition> = listOf(bolt),
        enemies: List<EnemyDefinition> = listOf(imp),
        traits: List<TraitDefinition> = emptyList(),
        flasks: List<FlaskDefinition> = emptyList(),
    ) = ContentPack(id = "t", name = "T", author = "t", damageTypes = damageTypes, statuses = statuses, skills = skills, enemies = enemies, traits = traits, flasks = flasks)

    private fun failure(pack: ContentPack): String =
        assertFailsWith<ContentPackException> { ContentPackAssembler().assemble(listOf(pack)) }.message.orEmpty()

    @Test
    fun `a consistent combat pack assembles, loops and all`() {
        // A skill that casts itself is a legitimate broken build; the engine bounds it at run time.
        val echo = bolt.copy(id = "t:echo", effects = listOf(SkillEffect.CastSkill("t:echo")))
        ContentPackAssembler().assemble(listOf(pack(skills = listOf(bolt, echo))))
    }

    @Test
    fun `dangling ids anywhere in the combat core are refused by name`() {
        assertContains(failure(pack(statuses = emptyList())), "damage type 't:fire' inflicts unknown status 't:burn'")
        assertContains(failure(pack(skills = listOf(bolt.copy(effects = listOf(SkillEffect.CastSkill("t:none")))))), "skill 't:bolt' casts unknown skill 't:none'")
        assertContains(failure(pack(enemies = listOf(imp.copy(skills = listOf(MonsterSkill("t:none")))))), "enemy 't:imp' uses unknown skill 't:none'")
        val boss = imp.copy(phases = listOf(BossPhase("Rage", 0.5f, adds = listOf(PackMember("t:ghost")))))
        assertContains(failure(pack(enemies = listOf(boss))), "calls unknown enemy 't:ghost'")
        val trait = TraitDefinition("t:oath", "Oath", triggers = listOf(TriggerDefinition(TriggerEvent.ON_KILL, castSkillId = "t:none")))
        assertContains(failure(pack(traits = listOf(trait))), "trait 't:oath' triggers unknown skill 't:none'")
        assertContains(failure(pack(flasks = listOf(FlaskDefinition("t:gourd", "Gourd", statusId = "t:none")))), "flask 't:gourd' grants unknown status 't:none'")
        val rot = StatusDefinition("t:rot", "Rot", behaviours = listOf(StatusBehaviour.DamageOverTime("t:decay")))
        assertContains(failure(pack(statuses = listOf(burn, rot))), "status 't:rot' deals unknown damage type 't:decay'")
    }
}
