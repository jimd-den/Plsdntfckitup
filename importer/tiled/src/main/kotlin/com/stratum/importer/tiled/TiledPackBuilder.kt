package com.stratum.importer.tiled

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.PackOrigin
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.importing.ImportResult
import com.stratum.core.domain.importing.ImportedSpriteSheet
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.importer.common.ImportNaming

/** Who a pack says made it, and what to call it. */
data class PackIdentity(
    val namespace: String,
    val name: String,
    val author: String = "Imported",
    val version: String = "1.0.0",
    val description: String = "",
)

/**
 * Builds one content pack from a project's maps.
 *
 * The pack is a *layer*: blocks, one region and the maps, with terrain set to
 * play on the first map. Everything it does not say -- classes, monsters,
 * weapons -- comes from whatever packs it is loaded on top of, which is how
 * a level imported from a game with no combat still has something to fight.
 */
class TiledPackBuilder(private val identity: PackIdentity) {

    private val converter = TiledMapConverter(identity.namespace)
    private val biomeId = ImportNaming.id(identity.namespace, "realm")

    fun build(maps: List<TiledMap>, spriteSheets: List<ImportedSpriteSheet> = emptyList(), warnings: List<String> = emptyList()): ImportResult {
        if (maps.isEmpty()) throw ImportException("'${identity.name}' has no Tiled maps to play on")
        val converted = PrimaryMap.first(maps).map { converter.convert(it, biomeId) }
        val primary = converted.first().map
        val pack = ContentPack(
            id = ImportNaming.slug(identity.namespace),
            name = identity.name,
            author = identity.author,
            version = identity.version,
            description = identity.description,
            origin = PackOrigin.IMPORTED,
            blocks = converted.flatMap { it.blocks }.distinctBy { it.id },
            biomes = listOf(biomeFor(primary.groundBlockId)),
            maps = converted.map { it.map },
            terrain = TerrainRecipe.tileMap(primary.id),
            spriteSheets = spriteSheets.map { it.sheet },
        )
        return ImportResult(
            pack = pack,
            textures = converted.flatMap { it.textures }.distinctBy { it.key },
            spriteSheets = spriteSheets,
            warnings = warnings + converted.flatMap { it.warnings },
        )
    }

    private fun biomeFor(groundBlockId: String) = BiomeDefinition(
        id = biomeId,
        name = identity.name,
        surfaceBlockId = groundBlockId,
        subsurfaceBlockId = groundBlockId,
        bedrockFillerBlockId = groundBlockId,
    )
}

/** Which map a player starts on: one the author marked, else the conventional names, else the first. */
object PrimaryMap {
    private val STARTING_NAMES = listOf("main", "start", "world", "level1", "level_1", "map", "town", "overworld")

    fun first(maps: List<TiledMap>): List<TiledMap> {
        val sorted = maps.sortedBy { it.path }
        val primary = sorted.firstOrNull { it.properties.bool("start") == true }
            ?: STARTING_NAMES.firstNotNullOfOrNull { name -> sorted.firstOrNull { baseName(it) == name } }
            ?: sorted.first()
        return listOf(primary) + (sorted - primary)
    }

    private fun baseName(map: TiledMap) = map.path.substringAfterLast('/').substringBeforeLast('.').lowercase()
}
