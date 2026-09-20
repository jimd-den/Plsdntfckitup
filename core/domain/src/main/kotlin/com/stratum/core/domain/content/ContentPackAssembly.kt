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
import com.stratum.core.domain.world.BlockRegistry
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

        val blocks = LinkedHashMap<String, com.stratum.core.domain.world.BlockType>()
        val biomes = LinkedHashMap<String, BiomeDefinition>()
        val classes = LinkedHashMap<String, HeroClassDefinition>()
        val lore = LinkedHashMap<String, LoreEntry>()
        val damageTypes = LinkedHashMap<String, DamageTypeDefinition>()
        val affixes = LinkedHashMap<String, AffixDefinition>()
        val inserts = LinkedHashMap<String, InsertDefinition>()
        val weapons = LinkedHashMap<String, WeaponBase>()
        val enemies = LinkedHashMap<String, EnemyDefinition>()
        val skills = LinkedHashMap<String, SkillDefinition>()
        val rarityStyles = LinkedHashMap<ItemRarity, RarityStyle>()
        val spriteSheets = LinkedHashMap<String, SpriteSheet>()
        val overrides = mutableListOf<PackOverride>()

        packs.forEach { pack ->
            pack.blocks.forEach { block ->
                blocks.put(block.id, block)?.let {
                    overrides += PackOverride(pack.id, block.id, OverrideKind.BLOCK)
                }
            }
            pack.biomes.forEach { biome ->
                biomes.put(biome.id, biome)?.let {
                    overrides += PackOverride(pack.id, biome.id, OverrideKind.BIOME)
                }
            }
            pack.heroClasses.forEach { hero ->
                classes.put(hero.id, hero)?.let {
                    overrides += PackOverride(pack.id, hero.id, OverrideKind.HERO_CLASS)
                }
            }
            pack.loreEntries.forEach { entry -> lore[entry.id] = entry }
            pack.damageTypes.forEach { type -> damageTypes[type.id] = type }
            pack.affixes.forEach { affix -> affixes[affix.id] = affix }
            pack.inserts.forEach { insert -> inserts[insert.id] = insert }
            pack.weapons.forEach { weapon -> weapons[weapon.id] = weapon }
            pack.skills.forEach { skill -> skills[skill.id] = skill }
            pack.rarityStyles.forEach { style -> rarityStyles[style.rarity] = style }
            pack.spriteSheets.forEach { sheet -> spriteSheets[sheet.id] = sheet }
            pack.enemies.forEach { enemy ->
                enemies.put(enemy.id, enemy)?.let {
                    overrides += PackOverride(pack.id, enemy.id, OverrideKind.ENEMY)
                }
            }
        }

        val registry = BlockRegistry.build(blocks.values.toList())
        val resolvedBiomes = biomes.values.toList()
        validate(registry, resolvedBiomes)
        validateCombat(
            damageTypes.keys, weapons.values, enemies.values,
            skills.values, affixes.values, inserts.values,
        )

        return AssembledContent(
            packs = packs,
            registry = registry,
            biomes = resolvedBiomes,
            heroClasses = classes.values.toList(),
            lore = lore.values.toList(),
            palette = packs.last().palette,
            damageTypes = damageTypes.values.toList(),
            affixes = affixes.values.toList(),
            inserts = inserts.values.toList(),
            // Last pack with an opinion wins, like every other override. A pack
            // that says nothing about terrain leaves the previous shape alone.
            terrain = packs.lastOrNull { it.terrain != null }?.terrain ?: TerrainRecipe(),
            weapons = weapons.values.toList(),
            enemies = enemies.values.toList(),
            skills = skills.values.toList(),
            rarityStyles = rarityStyles,
            spriteSheets = spriteSheets.values.toList(),
            overrides = overrides,
        )
    }

    /**
     * A biome that names a block nobody defined would crash mid-generation, far
     * from the pack that caused it. Catch it at load time instead.
     */
    private fun validate(registry: BlockRegistry, biomes: List<BiomeDefinition>) {
        val missing = mutableListOf<String>()
        biomes.forEach { biome ->
            listOf(biome.surfaceBlockId, biome.subsurfaceBlockId, biome.bedrockFillerBlockId)
                .filterNot(registry::contains)
                .forEach { missing += "biome '${biome.id}' references unknown block '$it'" }
            biome.scatter.filterNot { registry.contains(it.blockId) }
                .forEach { missing += "biome '${biome.id}' scatters unknown block '${it.blockId}'" }
            biome.deposits.filterNot { registry.contains(it.blockId) }
                .forEach { missing += "biome '${biome.id}' deposits unknown block '${it.blockId}'" }
        }
        if (missing.isNotEmpty()) {
            throw ContentPackException(missing.joinToString("; "))
        }
    }

    /**
     * A weapon or monster naming a damage type nobody defined would resolve
     * every hit as unresisted and silently skew the whole game's balance, which
     * is far harder to notice than a crash.
     */
    private fun validateCombat(
        damageTypeIds: Set<String>,
        weapons: Collection<WeaponBase>,
        enemies: Collection<EnemyDefinition>,
        skills: Collection<SkillDefinition>,
        affixes: Collection<AffixDefinition>,
        insertList: Collection<InsertDefinition>,
    ) {
        if (damageTypeIds.isEmpty() && weapons.isEmpty() && enemies.isEmpty()) return

        val missing = mutableListOf<String>()
        weapons.filterNot { it.damageTypeId in damageTypeIds }
            .forEach { missing += "weapon '${it.id}' uses unknown damage type '${it.damageTypeId}'" }
        enemies.filterNot { it.damageTypeId in damageTypeIds }
            .forEach { missing += "enemy '${it.id}' uses unknown damage type '${it.damageTypeId}'" }
        skills.filterNot { it.damageTypeId in damageTypeIds }
            .forEach { missing += "skill '${it.id}' uses unknown damage type '${it.damageTypeId}'" }
        affixes.filter { it.damageTypeId != null && it.damageTypeId !in damageTypeIds }
            .forEach { missing += "affix '${it.id}' resists unknown damage type '${it.damageTypeId}'" }
        insertList.filter { it.damageTypeId != null && it.damageTypeId !in damageTypeIds }
            .forEach { missing += "insert '${it.id}' names unknown damage type '${it.damageTypeId}'" }

        if (missing.isNotEmpty()) {
            throw ContentPackException(missing.joinToString("; "))
        }
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
    /** Reported to the player so a pack silently reskinning another is visible. */
    val overrides: List<PackOverride> = emptyList(),
) {
    fun biome(id: String): BiomeDefinition =
        biomes.firstOrNull { it.id == id } ?: throw ContentPackException("Unknown biome '$id'")

    fun heroClass(id: String): HeroClassDefinition =
        heroClasses.firstOrNull { it.id == id } ?: throw ContentPackException("Unknown class '$id'")

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

enum class OverrideKind { BLOCK, BIOME, HERO_CLASS, ENEMY }

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
