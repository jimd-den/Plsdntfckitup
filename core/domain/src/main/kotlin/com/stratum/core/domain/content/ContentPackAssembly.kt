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
import com.stratum.core.domain.map.TileMap
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
            overrides = merger.overrides,
        )
        ContentValidation.requireValid(content)
        return content
    }
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
        val problems = worldProblems(content) + combatProblems(content) + tabletopProblems(content)
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
                .map { "insert '${it.id}' names unknown damage type '${it.damageTypeId}'" }
    }
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
) {
    fun biome(id: String): BiomeDefinition =
        biomes.firstOrNull { it.id == id } ?: throw ContentPackException("Unknown biome '$id'")

    fun heroClass(id: String): HeroClassDefinition =
        heroClasses.firstOrNull { it.id == id } ?: throw ContentPackException("Unknown class '$id'")

    fun map(id: String): TileMap? = maps.firstOrNull { it.id == id }

    fun check(id: String): SkillCheck? = checks.firstOrNull { it.id == id }

    /** What a terrain generator is built from, for this content and [config]. */
    fun terrainContext(config: WorldConfig): TerrainContext = TerrainContext(config, biomes, terrain, maps)

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
