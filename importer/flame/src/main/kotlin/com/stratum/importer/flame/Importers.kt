package com.stratum.importer.flame

import com.stratum.core.domain.importing.ImporterRegistry
import com.stratum.importer.tiled.TiledProjectImporter

/** Every importer this build ships, most specific first, so a Flame game is not taken for a folder of maps. */
object Importers {
    fun standard(): ImporterRegistry = ImporterRegistry()
        .register(FlameProjectImporter())
        .register(TiledProjectImporter())
}
