package com.stratum.plugins

import com.stratum.core.domain.importing.ImporterRegistry
import com.stratum.importer.flame.FlameProjectImporter
import com.stratum.importer.tiled.TiledProjectImporter

/**
 * Every importer this build ships, most specific first: a Stratum plugin says
 * exactly what it is, a Flame game is more than a folder of maps, and plain
 * Tiled maps are the fallback.
 */
object Importers {
    fun standard(): ImporterRegistry = ImporterRegistry()
        .register(PluginImporter())
        .register(FlameProjectImporter())
        .register(TiledProjectImporter())
}
