package com.stratum.engine.world

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.DepositRule
import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillShape
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.DamageTypeDefinition
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixStat
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.WeaponBase
import com.stratum.core.domain.content.ScatterRule
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockType

/**
 * A deliberately small pack. Engine tests assert on behaviour, so the content
 * only needs one block per behaviour the engine is supposed to have.
 */
object TestContent {

    val soil = BlockType(id = "test:soil", displayName = "Soil", material = BlockMaterial.SOIL, hardness = 0.5f)
    val stone = BlockType(id = "test:stone", displayName = "Stone", hardness = 2f, requiredTier = 1)
    val sand = BlockType(id = "test:sand", displayName = "Sand", hardness = 0.4f, hasGravity = true)
    val torch = BlockType(
        id = "test:torch",
        displayName = "Torch",
        hardness = 0.1f,
        isSolid = false,
        isOpaque = false,
        needsSupport = true,
        lightEmission = 14,
    )
    val ore = BlockType(
        id = "test:ore",
        displayName = "Ore",
        material = BlockMaterial.ORE,
        hardness = 4f,
        requiredTier = 2,
        dropId = "test:ingot",
    )
    val leaves = BlockType(id = "test:leaves", displayName = "Leaves", hardness = 0.2f, isOpaque = false)

    val plains = BiomeDefinition(
        id = "test:plains",
        name = "Plains",
        surfaceBlockId = soil.id,
        subsurfaceBlockId = soil.id,
        bedrockFillerBlockId = stone.id,
        deposits = listOf(DepositRule(ore.id, minZ = 2, maxZ = 8, chance = 0.25f, clusterSize = 3)),
        scatter = listOf(ScatterRule(leaves.id, chance = 0.1f, height = 2)),
    )

    val highlands = plains.copy(
        id = "test:highlands",
        name = "Highlands",
        surfaceBlockId = stone.id,
        heightBias = 6,
        roughness = 1.5f,
        scatter = emptyList(),
    )

    // ---- combat fixtures -------------------------------------------------

    val physical = DamageTypeDefinition("test:physical", "Physical")
    val fire = DamageTypeDefinition("test:fire", "Fire")

    val club = WeaponBase(
        id = "test:club",
        name = "Club",
        minDamage = 8,
        maxDamage = 12,
        attackSpeed = 1.2f,
        attackRange = 1,
        damageTypeId = physical.id,
        toolTier = 1,
        minItemLevel = 1,
        weight = 100,
    )

    val pick = WeaponBase(
        id = "test:pick",
        name = "Pick",
        minDamage = 6,
        maxDamage = 10,
        attackSpeed = 1.1f,
        damageTypeId = physical.id,
        toolTier = 3,
        minItemLevel = 1,
        weight = 100,
    )

    /** Deliberately gated, so item-level rules have something to exclude. */
    val greatsword = WeaponBase(
        id = "test:greatsword",
        name = "Greatsword",
        minDamage = 25,
        maxDamage = 40,
        attackSpeed = 0.7f,
        damageTypeId = fire.id,
        toolTier = 2,
        minItemLevel = 15,
        weight = 100,
    )

    val lateAffix = AffixDefinition(
        "test:of_depths", "of Depths", AffixKind.SUFFIX, AffixStat.MAX_HEALTH,
        40f, 90f, minItemLevel = 20,
    )

    val affixes = listOf(
        AffixDefinition("test:sharp", "Sharp", AffixKind.PREFIX, AffixStat.ATTACK_POWER, 2f, 8f),
        AffixDefinition("test:heavy", "Heavy", AffixKind.PREFIX, AffixStat.MAX_HEALTH, 5f, 20f),
        AffixDefinition("test:keen", "Keen", AffixKind.PREFIX, AffixStat.CRIT_CHANCE, 0.02f, 0.08f),
        AffixDefinition("test:pitted", "Pitted", AffixKind.PREFIX, AffixStat.MINING_SPEED, 0.1f, 0.5f),
        AffixDefinition("test:plated", "Plated", AffixKind.PREFIX, AffixStat.ARMOUR, 1f, 6f),
        AffixDefinition("test:of_embers", "of Embers", AffixKind.SUFFIX, AffixStat.RESISTANCE, 0.1f, 0.3f, damageTypeId = fire.id),
        AffixDefinition("test:of_blood", "of Blood", AffixKind.SUFFIX, AffixStat.LIFE_STEAL, 0.02f, 0.1f),
        lateAffix,
    )

    val weapons = listOf(club, pick, greatsword)

    val sharpBead = InsertDefinition(
        id = "test:sharp_bead",
        name = "Sharp Bead",
        stat = AffixStat.ATTACK_POWER,
        value = 7f,
    )

    val emberShard = InsertDefinition(
        id = "test:ember_shard",
        name = "Ember Shard",
        stat = AffixStat.ATTACK_POWER,
        value = 3f,
        damageTypeId = fire.id,
        convertsDamageType = true,
    )

    val deepBead = InsertDefinition(
        id = "test:deep_bead",
        name = "Deep Bead",
        stat = AffixStat.MAX_HEALTH,
        value = 30f,
        minItemLevel = 20,
    )

    val inserts = listOf(sharpBead, emberShard, deepBead)

    val rat = EnemyDefinition(
        id = "test:rat",
        name = "Rat",
        baseStats = CombatStats(maxHealth = 20, attackPower = 4, attackSpeed = 1f, attackRange = 1),
        damageTypeId = physical.id,
        moveSpeed = 2f,
        aggroRange = 8,
        experience = 10,
        spawnWeight = 100,
    )

    val emberling = EnemyDefinition(
        id = "test:emberling",
        name = "Emberling",
        baseStats = CombatStats(maxHealth = 15, attackPower = 6, attackSpeed = 1f, attackRange = 2),
        damageTypeId = fire.id,
        moveSpeed = 3f,
        aggroRange = 10,
        fleeBelowHealth = 0.3f,
        canFlee = true,
        experience = 15,
        spawnBiomeIds = listOf("test:highlands"),
        spawnWeight = 100,
    )

    val enemies = listOf(rat, emberling)

    val strike = SkillDefinition(
        id = "test:strike",
        name = "Strike",
        damageTypeId = physical.id,
        powerMultiplier = 2f,
        resourceCost = 10,
        cooldownSeconds = 3f,
        shape = SkillShape.STRIKE,
        range = 3,
    )

    val nova = SkillDefinition(
        id = "test:nova",
        name = "Nova",
        damageTypeId = fire.id,
        powerMultiplier = 1.5f,
        resourceCost = 20,
        cooldownSeconds = 5f,
        shape = SkillShape.NOVA,
        range = 4,
    )

    val lance = SkillDefinition(
        id = "test:lance",
        name = "Lance",
        damageTypeId = fire.id,
        powerMultiplier = 1.8f,
        resourceCost = 15,
        cooldownSeconds = 4f,
        shape = SkillShape.LANCE,
        range = 6,
    )

    val skills = listOf(strike, nova, lance)

    val digger = HeroClassDefinition(
        id = "test:digger",
        name = "Digger",
        baseHealth = 120,
        baseResource = 40,
        startingBlockIds = listOf(soil.id, stone.id),
        abilityIds = listOf(strike.id, nova.id, lance.id),
        baseStats = CombatStats(
            maxHealth = 120,
            attackPower = 20,
            armour = 0,
            critChance = 0f,
            attackSpeed = 1f,
            attackRange = 1,
        ),
        startingWeaponId = club.id,
    )

    val pack = ContentPack(
        id = "test",
        name = "Engine Test Pack",
        author = "test",
        blocks = listOf(soil, stone, sand, torch, ore, leaves),
        biomes = listOf(plains, highlands),
        heroClasses = listOf(digger),
        damageTypes = listOf(physical, fire),
        affixes = affixes,
        inserts = inserts,
        weapons = weapons,
        enemies = enemies,
        skills = skills,
    )

    val assembled = ContentPackAssembler().assemble(listOf(pack))

    val registry get() = assembled.registry
}
