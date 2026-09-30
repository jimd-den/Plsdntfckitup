package com.stratum.importer.tiled

import com.stratum.core.domain.importing.ImportResult
import com.stratum.core.domain.importing.ImportSource
import com.stratum.core.domain.importing.ProjectImporter

/** A folder or archive of Tiled maps with no game around them. */
class TiledProjectImporter : ProjectImporter {

    override val id: String = "tiled"

    override val displayName: String = "Tiled maps"

    override fun recognises(source: ImportSource): Boolean = TiledReader.mapPathsIn(source).isNotEmpty()

    override fun import(source: ImportSource): ImportResult {
        val read = TiledReader(source).readMaps(TiledReader.mapPathsIn(source))
        return TiledPackBuilder(PackIdentity(namespace = source.name, name = source.name)).build(read.maps, warnings = read.warnings)
    }
}
