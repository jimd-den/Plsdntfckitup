package com.stratum.plugins.schema

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.PackOrigin
import com.stratum.core.domain.importing.ImportException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A whole content pack as plugin JSON: `pack.json` in a `.stratum` file.
 *
 * Its own schema, not the domain's classes serialised, because a plugin file
 * is a public format other people write by hand and keep for years: the
 * engine's internals can be renamed freely, and this must not change under
 * them. Every field but the ids has a default, so a pack that only adds
 * three monsters is three monsters long.
 */
@Serializable
internal data class PackSchema(
    val id: String,
    val name: String,
    val author: String = "",
    val version: String = "1.0.0",
    val description: String = "",
    val palette: PaletteSchema = PaletteSchema(),
    val blocks: List<BlockSchema> = emptyList(),
    val biomes: List<BiomeSchema> = emptyList(),
    val classes: List<HeroClassSchema> = emptyList(),
    val lore: List<LoreSchema> = emptyList(),
    val damageTypes: List<DamageTypeSchema> = emptyList(),
    val affixes: List<AffixSchema> = emptyList(),
    val inserts: List<InsertSchema> = emptyList(),
    val terrain: TerrainSchema? = null,
    val weapons: List<WeaponSchema> = emptyList(),
    val enemies: List<EnemySchema> = emptyList(),
    val skills: List<SkillSchema> = emptyList(),
    val rarities: List<RaritySchema> = emptyList(),
    val sheets: List<SheetSchema> = emptyList(),
    val maps: List<MapSchema> = emptyList(),
    val checks: List<CheckSchema> = emptyList(),
    val passiveTrees: List<PassiveTreeSchema> = emptyList(),
    val currencies: List<CurrencySchema> = emptyList(),
    val supports: List<SupportSchema> = emptyList(),
    val waystoneMods: List<WaystoneModSchema> = emptyList(),
    val factions: List<FactionSchema> = emptyList(),
    val enemyPacks: List<EnemyPackSchema> = emptyList(),
    val settlements: List<SettlementSchema> = emptyList(),
    val rules: RulesSchema? = null,
    val needs: List<NeedSchema> = emptyList(),
    val consumables: List<ConsumableSchema> = emptyList(),
    val forage: List<ForageSchema> = emptyList(),
    val recipes: List<RecipeSchema> = emptyList(),
) {
    fun toDomain() = ContentPack(
        id = id, name = name, author = author, version = version, description = description, origin = PackOrigin.IMPORTED,
        palette = palette.toDomain(), blocks = blocks.map { it.toDomain() }, biomes = biomes.map { it.toDomain() },
        heroClasses = classes.map { it.toDomain() }, loreEntries = lore.map { it.toDomain() }, damageTypes = damageTypes.map { it.toDomain() },
        affixes = affixes.map { it.toDomain() }, inserts = inserts.map { it.toDomain() }, terrain = terrain?.toDomain(),
        weapons = weapons.map { it.toDomain() }, enemies = enemies.map { it.toDomain() }, skills = skills.map { it.toDomain() },
        rarityStyles = rarities.map { it.toDomain() }, spriteSheets = sheets.map { it.toDomain() }, maps = maps.map { it.toDomain() },
        checks = checks.map { it.toDomain() }, passiveTrees = passiveTrees.map { it.toDomain() },
        currencies = currencies.map { it.toDomain() }, supports = supports.map { it.toDomain() }, waystoneMods = waystoneMods.map { it.toDomain() },
        factions = factions.map { it.toDomain() }, enemyPacks = enemyPacks.map { it.toDomain() }, settlements = settlements.map { it.toDomain() },
        rules = rules?.toDomain(), needs = needs.map { it.toDomain() }, consumables = consumables.map { it.toDomain() },
        forageRules = forage.map { it.toDomain() }, recipes = recipes.map { it.toDomain() },
    )

    companion object {
        fun of(p: ContentPack) = PackSchema(
            p.id, p.name, p.author, p.version, p.description, PaletteSchema.of(p.palette), p.blocks.map(BlockSchema::of),
            p.biomes.map(BiomeSchema::of), p.heroClasses.map(HeroClassSchema::of), p.loreEntries.map(LoreSchema::of),
            p.damageTypes.map(DamageTypeSchema::of), p.affixes.map(AffixSchema::of), p.inserts.map(InsertSchema::of),
            p.terrain?.let(TerrainSchema::of), p.weapons.map(WeaponSchema::of), p.enemies.map(EnemySchema::of),
            p.skills.map(SkillSchema::of), p.rarityStyles.map(RaritySchema::of), p.spriteSheets.map(SheetSchema::of),
            p.maps.map(MapSchema::of), p.checks.map(CheckSchema::of), p.passiveTrees.map(PassiveTreeSchema::of),
            p.currencies.map(CurrencySchema::of), p.supports.map(SupportSchema::of), p.waystoneMods.map(WaystoneModSchema::of),
            p.factions.map(FactionSchema::of), p.enemyPacks.map(EnemyPackSchema::of), p.settlements.map(SettlementSchema::of),
            p.rules?.let(RulesSchema::of), p.needs.map(NeedSchema::of), p.consumables.map(ConsumableSchema::of),
            p.forageRules.map(ForageSchema::of), p.recipes.map(RecipeSchema::of),
        )
    }
}

/** Reads and writes packs as plugin JSON. */
object PackJson {

    internal val json = Json {
        prettyPrint = true
        encodeDefaults = false
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(pack: ContentPack): String = json.encodeToString(PackSchema.serializer(), PackSchema.of(pack))

    /** @throws ImportException naming what is wrong, for the plugin's author to fix. */
    fun decode(text: String, source: String = "pack.json"): ContentPack = try {
        json.decodeFromString(PackSchema.serializer(), text).toDomain()
    } catch (failure: SerializationException) {
        throw ImportException("$source: ${failure.message?.lineSequence()?.firstOrNull()}", failure)
    } catch (failure: IllegalArgumentException) {
        // A domain rule the data breaks, such as a landmark bigger than its clearing.
        if (failure is ImportException) throw failure
        throw ImportException("$source: ${failure.message}", failure)
    }
}
