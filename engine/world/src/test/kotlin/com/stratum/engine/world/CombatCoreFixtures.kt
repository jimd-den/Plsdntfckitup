package com.stratum.engine.world

import com.stratum.core.domain.actor.BossPhase
import com.stratum.core.domain.actor.EffectTarget
import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.MonsterSkill
import com.stratum.core.domain.actor.PackMember
import com.stratum.core.domain.actor.ProjectileSpec
import com.stratum.core.domain.actor.SkillArea
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.DamageTypeDefinition
import com.stratum.core.domain.combat.FlaskDefinition
import com.stratum.core.domain.combat.TraitDefinition
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.status.StatusBehaviour
import com.stratum.core.domain.status.StatusDefinition
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import com.stratum.core.domain.world.WorldRules

/**
 * A combat-core arena: flat stone to z = 4 everywhere, nothing that spawns
 * on its own, and small skills, statuses and monsters that each exercise one
 * rule, so a test controls every body in the fight.
 */
object CombatCoreFixtures {

    object FlatTerrain : TerrainGenerator {
        override fun generate(pos: ChunkPos, registry: BlockRegistry): Chunk {
            val chunk = Chunk(pos)
            val stone = registry.indexOf(TestContent.stone.id)
            for (x in 0 until Chunk.SIZE) for (y in 0 until Chunk.SIZE) for (z in 0..FLOOR) chunk.setBlock(x, y, z, stone)
            return chunk
        }
    }

    const val FLOOR = 4

    val blunt = DamageTypeDefinition("test:blunt", "Blunt", ailmentStatusId = "test:daze", ailmentChance = 1f)
    val burning = DamageTypeDefinition("test:fire", "Fire", mitigatedByArmour = false, ailmentStatusId = "test:burn", ailmentChance = 1f)

    val burn = StatusDefinition("test:burn", "Burn", behaviours = listOf(StatusBehaviour.DamageOverTime(burning.id, perSecond = 10f)), durationSeconds = 3f, tags = setOf("burning"))
    val daze = StatusDefinition("test:daze", "Daze", behaviours = listOf(StatusBehaviour.Stun), durationSeconds = 1f)
    val rage = StatusDefinition(
        "test:rage", "Rage", behaviours = listOf(StatusBehaviour.Modifiers(listOf(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 1f)))),
        durationSeconds = 10f, isDebuff = false,
    )

    val bolt = SkillDefinition(
        "test:bolt", "Bolt", damageTypeId = TestContent.physical.id, powerMultiplier = 1f, resourceCost = 5, cooldownSeconds = 0f,
        delivery = SkillDelivery.PROJECTILE, range = 12, projectile = ProjectileSpec(speed = 10f),
    )
    val firebolt = bolt.copy(id = "test:firebolt", name = "Firebolt", damageTypeId = burning.id)
    val nova = SkillDefinition(
        "test:ring", "Ring", damageTypeId = TestContent.physical.id, powerMultiplier = 0.1f, resourceCost = 0, cooldownSeconds = 0f,
        delivery = SkillDelivery.NOVA, range = 6,
    )
    val slam = SkillDefinition(
        "test:slam", "Slam", damageTypeId = TestContent.physical.id, powerMultiplier = 3f, resourceCost = 0, cooldownSeconds = 5f,
        delivery = SkillDelivery.AREA, range = 2, area = SkillArea(radius = 1.5f), castTime = 1f,
    )
    val mend = SkillDefinition(
        "test:mend", "Mend", damageTypeId = TestContent.physical.id, powerMultiplier = 0f, resourceCost = 0, cooldownSeconds = 2f,
        delivery = SkillDelivery.SELF, range = 6, effects = listOf(SkillEffect.Heal(amount = 50, target = EffectTarget.ALLIES)),
    )
    val volley = SkillDefinition(
        "test:volley", "Volley", damageTypeId = TestContent.physical.id, powerMultiplier = 1f, resourceCost = 0, cooldownSeconds = 1f,
        delivery = SkillDelivery.PROJECTILE, range = 10, projectile = ProjectileSpec(speed = 8f),
    )

    val skills = listOf(bolt, firebolt, nova, slam, mend, volley)

    private fun still(definition: EnemyDefinition) = definition.copy(spawnWeight = 0, moveSpeed = 0.0001f, aggroRange = 30)

    val dummy = still(EnemyDefinition("test:dummy", "Dummy", baseStats = CombatStats(maxHealth = 1000, attackPower = 1, attackSpeed = 0.1f), damageTypeId = TestContent.physical.id))
    val brute = still(EnemyDefinition("test:brute", "Brute", baseStats = CombatStats(maxHealth = 500, attackPower = 10, attackSpeed = 0.2f), damageTypeId = TestContent.physical.id, skills = listOf(MonsterSkill(slam.id))))
    val mender = still(EnemyDefinition("test:mender", "Mender", baseStats = CombatStats(maxHealth = 100, attackPower = 1, attackSpeed = 0.1f), damageTypeId = TestContent.physical.id, skills = listOf(MonsterSkill(mend.id))))
    val archer = still(EnemyDefinition("test:archer", "Archer", baseStats = CombatStats(maxHealth = 100, attackPower = 10, attackSpeed = 0.1f), damageTypeId = TestContent.physical.id, skills = listOf(MonsterSkill(volley.id))))
    val clubber = still(EnemyDefinition("test:clubber", "Clubber", baseStats = CombatStats(maxHealth = 100, attackPower = 2, attackSpeed = 1f), damageTypeId = blunt.id))
    val overlord = still(
        EnemyDefinition(
            "test:overlord", "Overlord", baseStats = CombatStats(maxHealth = 1000, attackPower = 10, attackSpeed = 0.1f), damageTypeId = TestContent.physical.id,
            phases = listOf(
                BossPhase("Summons", healthBelow = 0.5f, adds = listOf(PackMember(dummy.id, 2)), announcement = "Rise"),
                BossPhase("Fury", healthBelow = 0.25f, enrage = listOf(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 1f)), statusId = rage.id),
            ),
        ),
    )

    val gourd = FlaskDefinition("test:gourd", "Gourd", maxCharges = 20, chargesPerUse = 10, chargesPerKill = 5, life = 60)

    fun pack(traits: List<TraitDefinition> = emptyList(), classTraits: List<String> = emptyList(), stats: CombatStats? = null) = TestContent.pack.copy(
        damageTypes = TestContent.pack.damageTypes + blunt + burning,
        enemies = TestContent.enemies.map { it.copy(spawnWeight = 0) } + listOf(dummy, brute, mender, archer, clubber, overlord),
        skills = TestContent.skills + skills,
        statuses = listOf(burn, daze, rage),
        traits = traits,
        flasks = listOf(gourd),
        heroClasses = TestContent.pack.heroClasses.map { hero ->
            hero.copy(abilityIds = hero.abilityIds + listOf(bolt.id, firebolt.id), traitIds = classTraits, baseStats = stats ?: hero.baseStats)
        },
    )

    fun session(pack: ContentPack = pack(), rules: WorldRules = WorldRules(), seed: Long = 5L) = WorldSession(
        ContentPackAssembler().assemble(listOf(pack)),
        WorldConfig(seed = seed, simulationRadius = 1, rules = rules),
        terrainGenerator = FlatTerrain,
    ).also { it.enemies = emptyList() }

    fun unbound() = WorldRules(combat = CombatRules.UNBOUND)

    /** A monster exactly where the test wants it, at minion rank, without the director's dice. */
    fun WorldSession.place(definition: EnemyDefinition, dx: Float, dy: Float = 0f, id: String = definition.id.substringAfter(':')): EnemyInstance {
        val enemy = EnemyInstance(
            instanceId = id, definitionId = definition.id, name = definition.name, rank = EnemyRank.MINION,
            position = WorldPoint(player.position.x + dx, player.position.y + dy, player.position.z), health = definition.baseStats.maxHealth,
            stats = definition.baseStats, damageTypeId = definition.damageTypeId, role = definition.role, experience = 1,
        )
        enemies = enemies + enemy
        return enemy
    }

    fun WorldSession.enemy(id: String): EnemyInstance? = enemies.firstOrNull { it.instanceId == id }

    /** Ticks in small steps, as a frame loop does, collecting every event. */
    fun WorldSession.run(seconds: Float, step: Float = 0.05f): List<CombatEvent> {
        val events = mutableListOf<CombatEvent>()
        var left = seconds
        while (left > 1e-4f) {
            events += tick(minOf(step, left))
            left -= step
        }
        return events
    }
}
