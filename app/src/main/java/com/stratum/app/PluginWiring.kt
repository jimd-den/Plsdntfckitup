package com.stratum.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.data.importing.AndroidImportedAssetWriter
import com.stratum.core.data.importing.ImportedPackStore
import com.stratum.core.data.importing.PluginRepositoryImpl
import com.stratum.core.data.sprite.SpriteLibrary
import com.stratum.core.domain.content.CustomClassPack
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.importing.ImportProjectUseCase
import com.stratum.core.domain.plugin.PluginDependency
import com.stratum.core.domain.plugin.PluginManifest
import com.stratum.core.domain.plugin.PluginRepository
import com.stratum.core.domain.plugin.Version
import com.stratum.core.domain.plugin.VersionRange
import com.stratum.plugins.Importers
import com.stratum.plugins.PluginArchive
import java.io.File

/**
 * Connects the plugin ports to their adapters: the formats this build reads,
 * where installed plugins and their art are kept, what the build itself
 * ships for plugins to depend on, and how a player's own creations leave the
 * device as a plugin someone else can install.
 */
class PluginWiring(private val context: Context, sprites: SpriteLibrary) {

    private val registry = Importers.standard()

    private val store = ImportedPackStore(File(context.filesDir, "imported"), registry)

    private val builtIn = PluginManifest.of(IgboContentPack.pack)

    val repository: PluginRepository = PluginRepositoryImpl(
        store = store,
        installProject = ImportProjectUseCase(registry, AndroidImportedAssetWriter(store, sprites)),
        builtIn = listOf(builtIn),
    )

    /** Installed plugins' textures, for the 3D view to lay over its kit. Read fresh, since installs add folders. */
    fun textureDirectories(): List<File> = store.textureDirectories()

    /**
     * Packs the player's classes into a `.stratum` plugin and opens the share
     * sheet. The plugin names the built-in pack as a dependency, because the
     * classes use its skills and weapons, so it installs cleanly anywhere
     * this game runs. Returns false when there is nothing to share.
     */
    fun shareCreations(classes: List<HeroClassDefinition>): Boolean {
        if (classes.isEmpty()) return false
        val manifest = PluginManifest(
            id = CREATIONS_ID,
            name = "Shared classes",
            version = Version(1, 0, 0),
            author = "A Stratum player",
            license = "CC-BY-4.0",
            description = "${classes.size} classes built in the class forge.",
            dependencies = listOf(PluginDependency(builtIn.id, VersionRange.parse("^${builtIn.version.major}") ?: VersionRange.ANY)),
        )
        val pack = CustomClassPack.of(classes).copy(id = CREATIONS_ID, name = manifest.name, author = manifest.author)
        val file = File(File(context.cacheDir, "share").apply { mkdirs() }, "shared-classes.${PluginArchive.EXTENSION}")
        file.writeBytes(PluginArchive.write(manifest, pack))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share your classes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private companion object {
        /** Distinct from the local custom-class pack, so an installed share never collides with the player's own. */
        const val CREATIONS_ID = "shared.classes"
    }
}
