package com.stratum.app

import android.content.Context
import com.stratum.core.data.importing.AndroidImportedAssetWriter
import com.stratum.core.data.importing.ImportedPackRepositoryImpl
import com.stratum.core.data.importing.ImportedPackStore
import com.stratum.core.data.sprite.SpriteLibrary
import com.stratum.core.domain.importing.ImportProjectUseCase
import com.stratum.core.domain.importing.ImportedPackRepository
import com.stratum.plugins.Importers
import java.io.File

/**
 * Connects the import ports to their adapters: the formats this build reads,
 * where imported archives and art are kept, and the sprite library imported
 * characters join.
 */
class ImportWiring(context: Context, sprites: SpriteLibrary) {

    private val registry = Importers.standard()

    private val store = ImportedPackStore(File(context.filesDir, "imported"), registry)

    val repository: ImportedPackRepository = ImportedPackRepositoryImpl(
        store = store,
        importProject = ImportProjectUseCase(registry, AndroidImportedAssetWriter(store, sprites)),
    )

    /** Imported packs' textures, for the 3D view to lay over its kit. Read fresh, since imports add folders. */
    fun textureDirectories(): List<File> = store.textureDirectories()
}
