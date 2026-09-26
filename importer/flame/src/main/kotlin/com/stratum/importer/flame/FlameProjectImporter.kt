package com.stratum.importer.flame

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.content.PackOrigin
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.importing.ImportResult
import com.stratum.core.domain.importing.ImportSource
import com.stratum.core.domain.importing.ImportedSpriteSheet
import com.stratum.core.domain.importing.ProjectImporter
import com.stratum.core.domain.importing.readText
import com.stratum.importer.common.ImportNaming
import com.stratum.importer.tiled.PackIdentity
import com.stratum.importer.tiled.TiledPackBuilder
import com.stratum.importer.tiled.TiledReader

/**
 * Imports a Flame game: its Tiled levels, and the characters it animates.
 *
 * Recognised by a `pubspec.yaml` that depends on `flame`. What Flame keeps
 * in Dart -- rules, stats, AI -- is not imported; the pack is a layer of
 * levels and art, and plays with whatever rules the packs under it bring.
 */
class FlameProjectImporter : ProjectImporter {

    override val id: String = "flame"

    override val displayName: String = "Flame game"

    override fun recognises(source: ImportSource): Boolean =
        source.readText(PUBSPEC)?.let { Pubspec.parse(it).usesFlame } ?: false

    override fun import(source: ImportSource): ImportResult {
        val pubspec = Pubspec.parse(source.readText(PUBSPEC).orEmpty())
        val identity = identityOf(pubspec, source)
        val characters = FlameCharacters(source, identity.namespace).collect()
        val sheets = characters.sheets.map(AssembledSheet::sheet)
        val read = TiledReader(source).readMaps(TiledReader.mapPathsIn(source).filter { it.startsWith("assets/") })
        val warnings = read.warnings + characters.warnings

        val result = when {
            read.maps.isNotEmpty() -> TiledPackBuilder(identity).build(read.maps, sheets, warnings)
            sheets.isNotEmpty() -> charactersOnly(identity, sheets, warnings)
            else -> throw ImportException("'${identity.name}' is a Flame game, but it has no Tiled maps or animations this build can read")
        }
        return result.copy(pack = result.pack.copy(heroClasses = heroesFor(identity, sheets)))
    }

    private fun identityOf(pubspec: Pubspec, source: ImportSource): PackIdentity {
        val namespace = pubspec.name ?: source.name
        return PackIdentity(
            namespace = namespace,
            name = ImportNaming.displayName(namespace),
            version = pubspec.version?.substringBefore('+') ?: "1.0.0",
            description = pubspec.description.orEmpty(),
        )
    }

    /** A game with characters but no levels still imports, as art for other packs' worlds. */
    private fun charactersOnly(identity: PackIdentity, sheets: List<ImportedSpriteSheet>, warnings: List<String>) = ImportResult(
        pack = ContentPack(
            id = ImportNaming.slug(identity.namespace),
            name = identity.name,
            author = identity.author,
            version = identity.version,
            description = identity.description,
            origin = PackOrigin.IMPORTED,
            spriteSheets = sheets.map { it.sheet },
        ),
        spriteSheets = sheets,
        warnings = warnings + "'${identity.name}' has no Tiled maps; it adds characters to the worlds of other packs",
    )

    /**
     * The game's player character becomes a class you can play, so importing
     * a game means playing as its hero. Stats are the engine's defaults, since
     * a Flame game's stats live in Dart.
     */
    private fun heroesFor(identity: PackIdentity, sheets: List<ImportedSpriteSheet>): List<HeroClassDefinition> =
        sheets.filter { sheet -> AnimationNames.wordsOf(sheet.sheet.name).any(PLAYER_WORDS::contains) }.map { sheet ->
            HeroClassDefinition(
                id = ImportNaming.id(identity.namespace, "hero", sheet.sheet.name),
                name = sheet.sheet.name,
                title = identity.name,
                description = "Imported from ${identity.name}.",
                spriteSetId = sheet.sheet.id,
            )
        }

    private companion object {
        const val PUBSPEC = "pubspec.yaml"
        val PLAYER_WORDS = setOf("player", "hero", "knight", "character", "adventurer")
    }
}
