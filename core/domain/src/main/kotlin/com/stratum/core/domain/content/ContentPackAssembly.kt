package com.stratum.core.domain.content

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.combat.DamageTypeDefinition
import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.RarityStyle
import com.stratum.core.domain.item.WeaponBase
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.core.domain.crafting.CurrencyDefinition
import com.stratum.core.domain.crafting.StandardCrafting
import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.difficulty.WaystoneMod
import com.stratum.core.domain.difficulty.WaystoneMods
import com.stratum.core.domain.actor.EnemyPackDefinition
import com.stratum.core.domain.faction.FactionBook
import com.stratum.core.domain.faction.FactionDefinition
import com.stratum.core.domain.map.TileMap
import com.stratum.core.domain.strategy.ResourceDefinition
import com.stratum.core.domain.strategy.StandardStrategy
import com.stratum.core.domain.strategy.StrategyBook
import com.stratum.core.domain.strategy.StructureDefinition
import com.stratum.core.domain.strategy.UnitDefinition
import com.stratum.core.domain.survival.ConsumableDefinition
import com.stratum.core.domain.survival.ForageRule
import com.stratum.core.domain.survival.NeedDefinition
import com.stratum.core.domain.survival.RecipeDefinition
import com.stratum.core.domain.survival.StandardSurvival
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.passive.PassiveTree
import com.stratum.core.domain.passive.PassiveTreeGenerator
import com.stratum.core.domain.tabletop.SkillCheck
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.TerrainContext
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.TerrainRecipe
import kotlinx.coroutines.flow.Flow

/**
 * Merges the packs the player has enabled into the single immutable view the
 * engine runs against.
 *
 * Later packs win on id collisions, which is what lets a player drop an
 * AI-generated pack on top of the built-in one and reskin a handful of blocks
 * without forking the whole thing.
 */
class ContentPackAssembler {

    fun assemble(packs: List<ContentPack>): AssembledContent {
        require(packs.isNotEmpty()) { "Cannot assemble an empty pack list" }
        val merger = PackMerger(packs)

        val content = AssembledContent(
            packs = packs,
            registry = BlockRegistry.build(merger.merge(ContentPack::blocks, BlockType::id, OverrideKind.BLOCK)),
            biomes = merger.merge(ContentPack::biomes, BiomeDefinition::id, OverrideKind.BIOME),
            heroClasses = merger.merge(ContentPack::heroClasses, HeroClassDefinition::id, OverrideKind.HERO_CLASS),
            lore = merger.merge(ContentPack::loreEntries, LoreEntry::id),
            palette = packs.last().palette,
            damageTypes = merger.merge(ContentPack::damageTypes, DamageTypeDefinition::id),
            affixes = merger.merge(ContentPack::affixes, AffixDefinition::id),
            inserts = merger.merge(ContentPack::inserts, InsertDefinition::id),
            // Last pack with an opinion wins, like every other override. A pack
            // that says nothing about terrain leaves the previous shape alone.
            terrain = packs.lastOrNull { it.terrain != null }?.terrain ?: TerrainRecipe(),
            weapons = merger.merge(ContentPack::weapons, WeaponBase::id),
            enemies = merger.merge(ContentPack::enemies, EnemyDefinition::id, OverrideKind.ENEMY),
            skills = merger.merge(ContentPack::skills, SkillDefinition::id),
            rarityStyles = merger.merge(ContentPack::rarityStyles, { it.rarity.name }).associateBy(RarityStyle::rarity),
            spriteSheets = merger.merge(ContentPack::spriteSheets, SpriteSheet::id),
            maps = merger.merge(ContentPack::maps, TileMap::id, OverrideKind.MAP),
            checks = merger.merge(ContentPack::checks, SkillCheck::id),
            currencies = merger.merge(ContentPack::currencies, CurrencyDefinition::id),
            supports = merger.merge(ContentPack::supports, SupportDefinition::id),
            waystoneMods = merger.merge(ContentPack::waystoneMods, WaystoneMod::id),
            factions = merger.merge(ContentPack::factions, FactionDefinition::id),
            enemyPacks = merger.merge(ContentPack::enemyPacks, EnemyPackDefinition::id),
            settlements = merger.merge(ContentPack::settlements, SettlementRecipe::id),
            needs = merger.merge(ContentPack::needs, NeedDefinition::id),
            consumables = merger.merge(ContentPack::consumables, ConsumableDefinition::id),
            forageRules = packs.flatMap { it.forageRules },
            recipes = merger.merge(ContentPack::recipes, RecipeDefinition::id),
            resources = merger.merge(ContentPack::resources, ResourceDefinition::id),
            structures = merger.merge(ContentPack::structures, StructureDefinition::id),
            units = merger.merge(ContentPack::units, UnitDefinition::id),
            suggestedRules = packs.lastOrNull { it.rules != null }?.rules ?: com.stratum.core.domain.world.WorldRules(),
            overrides = merger.overrides,
        ).let(::withEndgameDefaults)
        ContentValidation.requireValid(content)
        return content
    }

    /** A world with combat always has a tree, currency, supports and waystones to chase. */
    private fun withEndgameDefaults(content: AssembledContent): AssembledContent {
        val withTree = content.copy(passiveTree = passiveTreeFor(content.packs, content))
        if (!content.hasCombat) return withTree
        return withTree.copy(
            currencies = content.currencies.ifEmpty { StandardCrafting.currencies },
            supports = content.supports.ifEmpty { StandardCrafting.supports },
            waystoneMods = content.waystoneMods.ifEmpty { WaystoneMods.standard },
            needs = content.needs.ifEmpty { StandardSurvival.needs },
            // Standard food only when the pack brings no food of its own, so its recipes can name it.
            consumables = content.consumables.ifEmpty { StandardSurvival.consumables },
            forageRules = if (content.consumables.isEmpty()) content.forageRules + StandardSurvival.forage else content.forageRules,
            recipes = if (content.consumables.isEmpty()) content.recipes + StandardSurvival.recipes else content.recipes,
        ).let(::withStandardStrategy)
    }

    /**
     * The standard economy for a pack with none: resources, structures,
     * units, and the soldiers' bodies, hitting with the pack's own first
     * damage type so they fit its balance.
     */
    private fun withStandardStrategy(content: AssembledContent): AssembledContent {
        if (content.resources.isNotEmpty() || content.structures.isNotEmpty()) return content
        val damageType = content.damageTypes.firstOrNull()?.id ?: content.weapons.first().damageTypeId
        val actors = StandardStrategy.actors(damageType).filter { actor -> content.enemies.none { it.id == actor.id } }
        return content.copy(
            resources = StandardStrategy.resources,
            structures = StandardStrategy.structures,
            units = content.units.ifEmpty { StandardStrategy.units },
            enemies = content.enemies + actors,
        )
    }

    /**
     * The tree the last pack drew, or a generated one when there is combat
     * and nobody drew one. Trees are whole designs, so they replace rather
     * than merge: half of two trees is not a tree.
     */
    private fun passiveTreeFor(packs: List<ContentPack>, content: AssembledContent): PassiveTree? =
        packs.lastOrNull { it.passiveTrees.isNotEmpty() }?.passiveTrees?.last()
            ?: if (content.hasCombat) PassiveTreeGenerator.generate(content.heroClasses) else null
}

/**
 * Layers the same kind of definition from every pack by id, later packs
 * winning, and remembers the collisions worth telling the player about.
 */
private class PackMerger(private val packs: List<ContentPack>) {

    val overrides = mutableListOf<PackOverride>()

    fun <T> merge(select: (ContentPack) -> List<T>, idOf: (T) -> String, reportAs: OverrideKind? = null): List<T> {
        val merged = LinkedHashMap<String, T>()
        packs.forEach { pack ->
            select(pack).forEach { item ->
                val replaced = merged.put(idOf(item), item) != null
                if (replaced && reportAs != null) overrides += PackOverride(pack.id, idOf(item), reportAs)
            }
        }
        return merged.values.toList()
    }
}

/**
 * The checks that turn a bad reference into an error at load time rather
 * than a crash, or worse a silent imbalance, somewhere far from its cause.
 */
internal object ContentValidation {

    fun requireValid(content: AssembledContent) {
        val problems = worldProblems(content) + combatProblems(content) + tabletopProblems(content) +
            content.passiveTree?.problems().orEmpty() + WorldPoliticsValidation.problems(content)
        if (problems.isNotEmpty()) throw ContentPackException(problems.joinToString("; "))
    }

    /** A biome or map that names a block nobody defined would crash mid-generation. */
    private fun worldProblems(content: AssembledContent): List<String> {
        val known = content.registry::contains
        return content.biomes.flatMap { biomeProblems(it, known) } + content.maps.flatMap { mapProblems(it, known, content.biomes) }
    }

    private fun biomeProblems(biome: BiomeDefinition, known: (String) -> Boolean): List<String> =
        listOf(biome.surfaceBlockId, biome.subsurfaceBlockId, biome.bedrockFillerBlockId)
            .filterNot(known).map { "biome '${biome.id}' references unknown block '$it'" } +
            biome.scatter.map { it.blockId }.filterNot(known).map { "biome '${biome.id}' scatters unknown block '$it'" } +
            biome.deposits.map { it.blockId }.filterNot(known).map { "biome '${biome.id}' deposits unknown block '$it'" }

    private fun mapProblems(map: TileMap, known: (String) -> Boolean, biomes: List<BiomeDefinition>): List<String> =
        map.referencedBlockIds().filterNot(known).map { "map '${map.id}' places unknown block '$it'" } +
            listOfNotNull(map.biomeId).filter { id -> biomes.none { it.id == id } }
                .map { "map '${map.id}' belongs to unknown biome '$it'" }

    /** Dice that do not parse would fail the first time a player tried the check. */
    private fun tabletopProblems(content: AssembledContent): List<String> =
        content.checks.filter { it.parsedDice == null }.map { "check '${it.id}' has dice '${it.dice}' that are not dice notation" }

    /**
     * A weapon or monster naming a damage type nobody defined would resolve
     * every hit as unresisted and silently skew the whole game's balance, which
     * is far harder to notice than a crash.
     */
    private fun combatProblems(content: AssembledContent): List<String> {
        val types = content.damageTypes.mapTo(HashSet()) { it.id }
        if (types.isEmpty() && content.weapons.isEmpty() && content.enemies.isEmpty()) return emptyList()
        fun unknown(typeId: String?) = typeId != null && typeId !in types
        return content.weapons.filter { unknown(it.damageTypeId) }
            .map { "weapon '${it.id}' uses unknown damage type '${it.damageTypeId}'" } +
            content.enemies.filter { unknown(it.damageTypeId) }
                .map { "enemy '${it.id}' uses unknown damage type '${it.damageTypeId}'" } +
            content.skills.filter { unknown(it.damageTypeId) }
                .map { "skill '${it.id}' uses unknown damage type '${it.damageTypeId}'" } +
            content.affixes.filter { unknown(it.damageTypeId) }
                .map { "affix '${it.id}' resists unknown damage type '${it.damageTypeId}'" } +
            content.inserts.filter { unknown(it.damageTypeId) }
                .map { "insert '${it.id}' names unknown damage type '${it.damageTypeId}'" } +
            content.supports.filter { unknown(it.convertsToDamageTypeId) }
                .map { "support '${it.id}' converts to unknown damage type '${it.convertsToDamageTypeId}'" }
    }
}

/**
 * Checks for the parts of a world that refer to each other by id: factions,
 * the monsters that belong to them, the packs they travel in and the towns
 * they hold. A town naming a block or a garrison nobody defined fails at
 * load, where the message can name it, not mid-generation.
 */
internal object WorldPoliticsValidation {

    fun problems(content: AssembledContent): List<String> {
        val factionIds = content.factions.mapTo(HashSet()) { it.id }
        val enemyIds = content.enemies.mapTo(HashSet()) { it.id }
        fun unknownFaction(id: String?) = id != null && id != com.stratum.core.domain.faction.Factions.PLAYER && id !in factionIds
        return content.factionBook.problems() +
            content.enemies.filter { unknownFaction(it.factionId) }.map { "enemy '${it.id}' belongs to unknown faction '${it.factionId}'" } +
            content.enemyPacks.flatMap { pack -> packProblems(pack, enemyIds) } +
            content.settlements.flatMap { recipe -> settlementProblems(recipe, content, enemyIds, factionIds) } +
            survivalProblems(content) + strategyProblems(content)
    }

    /** Costs in resources nobody defined, requirements on structures that do not exist, soldiers with no body. */
    private fun strategyProblems(content: AssembledContent): List<String> {
        val resources = content.resources.mapTo(HashSet()) { it.id }
        val structures = content.structures.mapTo(HashSet()) { it.id }
        val actors = content.enemies.mapTo(HashSet()) { it.id }
        fun costs(owner: String, map: Map<String, *>) = map.keys.filter { it !in resources }.map { "$owner uses unknown resource '$it'" }
        return content.structures.flatMap { s ->
            costs("structure '${s.id}'", s.cost) + costs("structure '${s.id}'", s.produces) + costs("structure '${s.id}'", s.upkeep) +
                s.requires.filter { it !in structures }.map { "structure '${s.id}' requires unknown structure '$it'" }
        } + content.units.flatMap { u ->
            costs("unit '${u.id}'", u.cost) + costs("unit '${u.id}'", u.upkeep) +
                listOfNotNull(u.requires).filter { it !in structures }.map { "unit '${u.id}' requires unknown structure '$it'" } +
                listOf(u.actorId).filter { it !in actors }.map { "unit '${u.id}' has unknown body '$it'" }
        }
    }

    /** Food that restores a need nobody defined, or a recipe using an item that is neither a block nor food. */
    private fun survivalProblems(content: AssembledContent): List<String> {
        val needIds = content.needs.mapTo(HashSet()) { it.id }
        val items = content.consumables.mapTo(HashSet()) { it.id }
        fun known(id: String) = id in items || content.registry.contains(id)
        return content.consumables.flatMap { food -> food.restores.keys.filter { it !in needIds }.map { "food '${food.id}' restores unknown need '$it'" } } +
            content.recipes.flatMap { r -> (r.inputs.keys + r.outputId).filterNot(::known).map { "recipe '${r.id}' uses unknown item '$it'" } } +
            content.forageRules.filterNot { known(it.itemId) }.map { "forage rule yields unknown item '${it.itemId}'" }
    }

    private fun packProblems(pack: EnemyPackDefinition, enemyIds: Set<String>): List<String> =
        (listOfNotNull(pack.leaderId) + pack.members.map { it.enemyId }).filter { it !in enemyIds }
            .map { "pack '${pack.id}' names unknown enemy '$it'" }

    private fun settlementProblems(recipe: SettlementRecipe, content: AssembledContent, enemyIds: Set<String>, factionIds: Set<String>): List<String> =
        recipe.referencedBlockIds().filterNot(content.registry::contains).map { "settlement '${recipe.id}' uses unknown block '$it'" } +
            recipe.garrison.map { it.enemyId }.filter { it !in enemyIds }.map { "settlement '${recipe.id}' garrisons unknown enemy '$it'" } +
            listOfNotNull(recipe.factionId).filter { it !in factionIds }.map { "settlement '${recipe.id}' belongs to unknown faction '$it'" }
}

/** The flattened, validated result the engine actually runs on. */
data class AssembledContent(
    val packs: List<ContentPack>,
    val registry: BlockRegistry,
    val biomes: List<BiomeDefinition>,
    val heroClasses: List<HeroClassDefinition>,
    val lore: List<LoreEntry>,
    val palette: PackPalette,
    val damageTypes: List<DamageTypeDefinition> = emptyList(),
    val affixes: List<AffixDefinition> = emptyList(),
    val inserts: List<InsertDefinition> = emptyList(),
    /** The world shape the loaded packs settled on. */
    val terrain: TerrainRecipe = TerrainRecipe(),
    val weapons: List<WeaponBase> = emptyList(),
    val enemies: List<EnemyDefinition> = emptyList(),
    val skills: List<SkillDefinition> = emptyList(),
    val rarityStyles: Map<ItemRarity, RarityStyle> = emptyMap(),
    val spriteSheets: List<SpriteSheet> = emptyList(),
    /** Hand-authored levels, such as imported Tiled maps. */
    val maps: List<TileMap> = emptyList(),
    /** Tabletop checks from every loaded pack. */
    val checks: List<SkillCheck> = emptyList(),
    /** Reported to the player so a pack silently reskinning another is visible. */
    val overrides: List<PackOverride> = emptyList(),
    /** What characters spend passive points on; null for a world without combat. */
    val passiveTree: PassiveTree? = null,
    val currencies: List<CurrencyDefinition> = emptyList(),
    val supports: List<SupportDefinition> = emptyList(),
    val waystoneMods: List<WaystoneMod> = emptyList(),
    val factions: List<FactionDefinition> = emptyList(),
    val enemyPacks: List<EnemyPackDefinition> = emptyList(),
    val settlements: List<SettlementRecipe> = emptyList(),
    val needs: List<NeedDefinition> = emptyList(),
    val consumables: List<ConsumableDefinition> = emptyList(),
    val forageRules: List<ForageRule> = emptyList(),
    val recipes: List<RecipeDefinition> = emptyList(),
    val resources: List<ResourceDefinition> = emptyList(),
    val structures: List<StructureDefinition> = emptyList(),
    val units: List<UnitDefinition> = emptyList(),
    /** The rules the loaded packs suggest, before the player changes them. */
    val suggestedRules: com.stratum.core.domain.world.WorldRules = com.stratum.core.domain.world.WorldRules(),
) {
    /** Outposts' resources, structures and units, for the questions the engine asks of them. */
    val strategyBook: StrategyBook by lazy { StrategyBook(resources, structures, units) }

    /** The loaded factions, for the questions the engine asks of them. */
    val factionBook: FactionBook by lazy { FactionBook(factions) }

    fun biome(id: String): BiomeDefinition =
        biomes.firstOrNull { it.id == id } ?: throw ContentPackException("Unknown biome '$id'")

    fun heroClass(id: String): HeroClassDefinition =
        heroClasses.firstOrNull { it.id == id } ?: throw ContentPackException("Unknown class '$id'")

    fun map(id: String): TileMap? = maps.firstOrNull { it.id == id }

    fun check(id: String): SkillCheck? = checks.firstOrNull { it.id == id }

    fun currency(id: String): CurrencyDefinition? = currencies.firstOrNull { it.id == id }

    fun support(id: String): SupportDefinition? = supports.firstOrNull { it.id == id }

    fun consumable(id: String): ConsumableDefinition? = consumables.firstOrNull { it.id == id }

    fun recipe(id: String): RecipeDefinition? = recipes.firstOrNull { it.id == id }

    /** What a terrain generator is built from, for this content and [config]. */
    fun terrainContext(config: WorldConfig): TerrainContext = TerrainContext(config, biomes, terrain, maps, settlements)

    fun loreFor(subjectId: String): List<LoreEntry> = lore.filter { it.subjectId == subjectId }

    fun damageType(id: String): DamageTypeDefinition =
        damageTypes.firstOrNull { it.id == id }
            ?: DamageTypeDefinition(id = id, name = id.substringAfter(':'))

    fun skill(id: String): SkillDefinition? = skills.firstOrNull { it.id == id }

    fun weapon(id: String): WeaponBase? = weapons.firstOrNull { it.id == id }

    fun insert(id: String): InsertDefinition? = inserts.firstOrNull { it.id == id }

    fun spriteSheet(id: String?): SpriteSheet? =
        id?.let { wanted -> spriteSheets.firstOrNull { it.id == wanted } }

    /**
     * The sheet an actor should be drawn with, or null to fall back to the
     * shape renderer. Looked up by the definition's own sprite set id.
     */
    fun sheetForEnemy(definitionId: String): SpriteSheet? =
        spriteSheet(enemies.firstOrNull { it.id == definitionId }?.spriteSetId)

    fun sheetForHero(heroClassId: String): SpriteSheet? =
        spriteSheet(heroClasses.firstOrNull { it.id == heroClassId }?.spriteSetId)

    /**
     * A copy with extra sheets layered on, for sheets generated this session.
     *
     * The extras come first, because `distinctBy` keeps the first of each id
     * and layering means the new one wins. The other way round, regenerating a
     * sheet under an id a pack already uses silently kept the pack's version --
     * so the art changed on disk, the world went on drawing the old one, and
     * the only way to see the new one was to restart.
     */
    fun withSpriteSheets(extra: List<SpriteSheet>): AssembledContent =
        if (extra.isEmpty()) this
        else copy(spriteSheets = (extra + spriteSheets).distinctBy { it.id })

    fun rarityName(rarity: ItemRarity): String = rarityStyles[rarity]?.name ?: rarity.name.lowercase()

    fun rarityColor(rarity: ItemRarity): Long = rarityStyles[rarity]?.color ?: DEFAULT_RARITY_COLOR

    /** Enemies eligible to spawn in a region, honouring each one's biome list. */
    fun enemiesFor(biomeId: String): List<EnemyDefinition> =
        enemies.filter { it.spawnBiomeIds.isEmpty() || biomeId in it.spawnBiomeIds }

    val hasCombat: Boolean get() = enemies.isNotEmpty() && weapons.isNotEmpty()

    private companion object {
        const val DEFAULT_RARITY_COLOR = 0xFFCFD8DC
    }
}

data class PackOverride(val packId: String, val targetId: String, val kind: OverrideKind)

enum class OverrideKind { BLOCK, BIOME, HERO_CLASS, ENEMY, MAP }

class ContentPackException(message: String) : IllegalStateException(message)

/** Storage port. Implemented over Room in `:core:data`. */
interface ContentPackRepository {
    fun observePacks(): Flow<List<ContentPack>>

    suspend fun allPacks(): List<ContentPack>

    suspend fun packById(id: String): ContentPack?

    suspend fun save(pack: ContentPack)

    suspend fun delete(id: String)

    suspend fun enabledPackIds(): List<String>

    suspend fun setEnabledPackIds(ids: List<String>)
}
