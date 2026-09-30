package com.stratum.content.igbo

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyPackDefinition
import com.stratum.core.domain.actor.PackMember
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.faction.FactionDefinition
import com.stratum.core.domain.faction.ReputationRank
import com.stratum.core.domain.faction.Stance
import com.stratum.core.domain.settlement.BuildingRole
import com.stratum.core.domain.settlement.BuildingTemplate
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockType

/**
 * The built-in pack's politics: who holds the land, where they live, and
 * who travels together.
 *
 * Two sides. The Kingdom of Nri, whose priest-kings kept the peace with the
 * ofo staff rather than the sword, and whose villages are safe ground. And
 * the Cult of the Masked Dead, who wear the ancestors' faces without their
 * leave, and whose war camps are strongholds to be taken.
 */
object IgboPackWorld {
    private const val NS = "igbo"

    // ---- the one new block: a roof ----------------------------------------------

    val raffiaThatch = BlockType(
        id = "$NS:raffia_thatch",
        displayName = "Raffia Thatch",
        material = BlockMaterial.FOLIAGE,
        hardness = 0.4f,
        topColor = 0xFFC9A55B,
        sideColor = 0xFFA07E3C,
        accentColor = 0xFFE3C98A,
    )

    // ---- factions ---------------------------------------------------------------

    val nri = FactionDefinition(
        id = "$NS:nri",
        name = "Kingdom of Nri",
        description = "Priest-kings who ruled by ritual, not by war. Their villages shelter anyone who keeps the peace.",
        color = 0xFFD9A441,
        glyph = "☀",
        relations = mapOf("$NS:mmuo_cult" to Stance.HOSTILE),
        startingStanding = 120,
        ranks = listOf(
            ReputationRank("Friend of Nri", 150, listOf(StatModifier(Stat.ITEM_RARITY, ModifierKind.INCREASED, 0.05f))),
            ReputationRank("Ozo Titled", 300, listOf(StatModifier(Stat.MAX_HEALTH, ModifierKind.INCREASED, 0.08f))),
            ReputationRank("Hand of the Eze", 600, listOf(StatModifier(Stat.DAMAGE, ModifierKind.INCREASED, 0.1f))),
        ),
    )

    val mmuoCult = FactionDefinition(
        id = "$NS:mmuo_cult",
        name = "Cult of the Masked Dead",
        description = "They wear the ancestors' faces without their leave, and the dead are not pleased about it.",
        color = 0xFF7E57C2,
        glyph = "☠",
        defaultStance = Stance.HOSTILE,
        startingStanding = -300,
    )

    val factions = listOf(nri, mmuoCult)

    // ---- people -----------------------------------------------------------------

    private val physical = IgboPackCombat.physical.id
    private val spirit = IgboPackCombat.spirit.id

    val nriWarden = EnemyDefinition(
        id = "$NS:nri_warden", name = "Nri Warden",
        description = "Carries an ofo staff and would rather not have to use it.",
        baseStats = CombatStats(maxHealth = 90, attackPower = 12, armour = 5, attackSpeed = 1f, attackRange = 1),
        damageTypeId = physical, moveSpeed = 2.4f, aggroRange = 10, experience = 30,
        spawnWeight = 0, factionId = nri.id, role = CombatRole.MELEE, bodyColor = 0xFFD9A441,
    )

    val nriArcher = EnemyDefinition(
        id = "$NS:nri_archer", name = "Nri Archer",
        baseStats = CombatStats(maxHealth = 55, attackPower = 10, attackSpeed = 0.9f, attackRange = 6),
        damageTypeId = physical, moveSpeed = 2.4f, aggroRange = 12, experience = 26,
        spawnWeight = 0, factionId = nri.id, role = CombatRole.RANGED, bodyColor = 0xFFC08A2E,
    )

    val cultMasker = EnemyDefinition(
        id = "$NS:cult_masker", name = "Masked Cultist",
        description = "Behind the mask, nobody. That is the point.",
        baseStats = CombatStats(maxHealth = 40, attackPower = 8, armour = 1, attackSpeed = 1.1f, attackRange = 1),
        damageTypeId = spirit, moveSpeed = 2.6f, aggroRange = 9, experience = 16,
        spawnWeight = 0, factionId = mmuoCult.id, role = CombatRole.MELEE, bodyColor = 0xFF7E57C2,
    )

    val cultSpearman = EnemyDefinition(
        id = "$NS:cult_spearman", name = "Cult Spear-Thrower",
        baseStats = CombatStats(maxHealth = 30, attackPower = 11, attackSpeed = 0.8f, attackRange = 5),
        damageTypeId = physical, moveSpeed = 2.5f, aggroRange = 12, experience = 18,
        spawnWeight = 0, factionId = mmuoCult.id, role = CombatRole.RANGED, bodyColor = 0xFF5E35B1,
    )

    val cultDiviner = EnemyDefinition(
        id = "$NS:cult_diviner", name = "False Diviner",
        description = "Reads the cowries upside down and gets answers anyway.",
        baseStats = CombatStats(maxHealth = 34, attackPower = 9, attackSpeed = 0.9f, attackRange = 4),
        damageTypeId = spirit, moveSpeed = 2.2f, aggroRange = 10, experience = 22,
        spawnWeight = 0, factionId = mmuoCult.id, role = CombatRole.SUPPORT, bodyColor = 0xFFB39DDB,
    )

    val cultWarlord = EnemyDefinition(
        id = "$NS:cult_warlord", name = "Mask-Lord",
        description = "Wears the biggest face. Leads from the front, which is why the rest follow.",
        baseStats = CombatStats(maxHealth = 160, attackPower = 16, armour = 6, attackSpeed = 0.7f, attackRange = 2),
        damageTypeId = spirit, moveSpeed = 2.1f, aggroRange = 11, experience = 70,
        spawnWeight = 0, factionId = mmuoCult.id, role = CombatRole.BRUTE, bonusDropChance = 0.3f, bodyColor = 0xFF4527A0,
    )

    val people = listOf(nriWarden, nriArcher, cultMasker, cultSpearman, cultDiviner, cultWarlord)

    // ---- who travels together -----------------------------------------------------

    val packs = listOf(
        EnemyPackDefinition(
            "$NS:leopard_pride", "Leopard Pride",
            members = listOf(PackMember("$NS:shadow_leopard", 3)), weight = 70,
        ),
        EnemyPackDefinition(
            "$NS:revenant_host", "Revenant Host",
            leaderId = "$NS:ogu_brute", members = listOf(PackMember("$NS:marsh_revenant", 3)),
            spawnBiomeIds = listOf(IgboPackBiomes.mistMarsh.id, IgboPackBiomes.sacredGrove.id), weight = 60,
        ),
        EnemyPackDefinition(
            "$NS:cult_warband", "Cult Warband",
            leaderId = cultWarlord.id,
            members = listOf(PackMember(cultMasker.id, 3), PackMember(cultSpearman.id, 2), PackMember(cultDiviner.id, 1)),
            weight = 45,
        ),
    )

    // ---- towns ------------------------------------------------------------------

    private val obi = BuildingTemplate(
        "$NS:obi", "Obi", BuildingRole.HOUSE, width = 5, depth = 5, height = 3,
        wallBlockId = IgboPackBlocks.mudWall.id, roofBlockId = raffiaThatch.id, weight = 120,
    )
    private val smithy = BuildingTemplate(
        "$NS:bronze_smithy", "Bronze Smithy", BuildingRole.SMITHY, width = 6, depth = 5, height = 3,
        wallBlockId = IgboPackBlocks.graniteStone.id, roofBlockId = raffiaThatch.id,
        furnitureBlockId = IgboPackBlocks.bronzeBrazier.id, minCount = 1, maxCount = 1,
    )
    private val shrine = BuildingTemplate(
        "$NS:ancestor_shrine", "Ancestor Shrine", BuildingRole.TEMPLE, width = 5, depth = 5, height = 3,
        wallBlockId = IgboPackBlocks.catacombMasonry.id, furnitureBlockId = IgboPackBlocks.ofoShrine.id, minCount = 1, maxCount = 1,
    )
    private val market = BuildingTemplate(
        "$NS:market_stall", "Market Stall", BuildingRole.SHOP, width = 4, depth = 3, height = 2,
        wallBlockId = IgboPackBlocks.plankWall.id, roofBlockId = raffiaThatch.id, weight = 60, maxCount = 4,
    )
    private val palace = BuildingTemplate(
        "$NS:obi_eze", "Obi of the Eze", BuildingRole.HALL, width = 9, depth = 7, height = 4,
        wallBlockId = IgboPackBlocks.mudWall.id, floorBlockId = IgboPackBlocks.lateritePaving.id, roofBlockId = raffiaThatch.id,
        windowBlockId = IgboPackBlocks.bronzeBrazier.id, furnitureBlockId = IgboPackBlocks.nsibidiSeal.id,
    )
    private val tent = BuildingTemplate(
        "$NS:cult_tent", "Masked Tent", BuildingRole.HOUSE, width = 4, depth = 4, height = 2,
        wallBlockId = IgboPackBlocks.plankWall.id, roofBlockId = IgboPackBlocks.obsidianCrag.id,
    )
    private val maskHall = BuildingTemplate(
        "$NS:mask_hall", "Hall of Masks", BuildingRole.HALL, width = 8, depth = 7, height = 4,
        wallBlockId = IgboPackBlocks.obsidianCrag.id, roofBlockId = IgboPackBlocks.obsidianCrag.id,
        furnitureBlockId = IgboPackBlocks.bronzeBrazier.id,
    )

    val nriVillage = SettlementRecipe(
        id = "$NS:nri_village",
        name = "Nri Village",
        layoutId = SettlementRecipe.ORGANIC,
        factionId = nri.id,
        minRadius = 18, maxRadius = 26, chance = 0.35f,
        roadBlockId = IgboPackBlocks.lateritePaving.id,
        foundationBlockId = IgboPackBlocks.redEarth.id,
        wallBlockId = IgboPackBlocks.plankWall.id, wallHeight = 3,
        buildings = listOf(obi, smithy, shrine, market, palace),
        names = listOf("Agukwu", "Oreri", "Enugwu-Ukwu", "Nri-Ukwu", "Igbo-Ukwu", "Amobia"),
        garrison = listOf(PackMember(nriWarden.id, 3), PackMember(nriArcher.id, 2)),
        weight = 140,
    )

    val cultCamp = SettlementRecipe(
        id = "$NS:cult_camp",
        name = "Masked War Camp",
        layoutId = SettlementRecipe.CAMP,
        factionId = mmuoCult.id,
        minRadius = 16, maxRadius = 22, chance = 0.4f,
        roadBlockId = IgboPackBlocks.ashSand.id,
        foundationBlockId = IgboPackBlocks.ashSand.id,
        groundBlockId = IgboPackBlocks.ashSand.id,
        wallBlockId = IgboPackBlocks.plankWall.id, wallHeight = 3,
        buildings = listOf(tent, maskHall),
        names = listOf("Camp of the Faceless", "The Mask Pit", "Shrine of False Faces"),
        garrison = listOf(PackMember(cultWarlord.id, 1), PackMember(cultMasker.id, 4), PackMember(cultSpearman.id, 2), PackMember(cultDiviner.id, 1)),
        weight = 100,
    )

    val bronzeCitadel = SettlementRecipe(
        id = "$NS:bronze_citadel",
        name = "Bronze Citadel",
        layoutId = SettlementRecipe.FORTRESS,
        factionId = mmuoCult.id,
        biomeIds = listOf(IgboPackBiomes.ozoCourtyard.id, IgboPackBiomes.bronzeCatacombs.id),
        minRadius = 22, maxRadius = 28, chance = 0.5f,
        roadBlockId = IgboPackBlocks.catacombMasonry.id,
        foundationBlockId = IgboPackBlocks.graniteStone.id,
        groundBlockId = IgboPackBlocks.graniteStone.id,
        wallBlockId = IgboPackBlocks.catacombMasonry.id, wallHeight = 5,
        buildings = listOf(maskHall.copy(id = "$NS:citadel_keep", name = "Citadel Keep", width = 11, depth = 9, height = 6), tent.copy(id = "$NS:barracks", name = "Barracks", role = BuildingRole.BARRACKS, width = 7, depth = 4, height = 3)),
        names = listOf("The Bronze Citadel", "Fort of Stolen Faces"),
        garrison = listOf(PackMember(cultWarlord.id, 2), PackMember(cultMasker.id, 6), PackMember(cultSpearman.id, 3), PackMember(cultDiviner.id, 2)),
        weight = 50,
    )

    val settlements = listOf(nriVillage, cultCamp, bronzeCitadel)
}
