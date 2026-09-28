package com.stratum.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import com.stratum.agents.forge.Creations
import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.data.importing.AndroidImportedAssetWriter
import com.stratum.core.data.importing.ImportedPackStore
import com.stratum.core.data.importing.PluginRepositoryImpl
import com.stratum.core.data.sprite.SpriteLibrary
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.CustomClassPack
import com.stratum.core.domain.content.HeroClassDefinition
import com.stratum.core.domain.importing.ImportProjectUseCase
import com.stratum.core.domain.plugin.PluginDependency
import com.stratum.core.domain.plugin.PluginManifest
import com.stratum.core.domain.plugin.PluginRepository
import com.stratum.core.domain.plugin.Version
import com.stratum.core.domain.sprite.SheetArt
import com.stratum.core.domain.sprite.SpritePluginExport
import com.stratum.core.domain.plugin.VersionRange
import com.stratum.plugins.Importers
import com.stratum.plugins.PluginArchive
import java.io.ByteArrayOutputStream
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

    /** The player's own plugin -- what they kept from the content forge -- or null before anything was kept. */
    fun creations(): ContentPack? = repository.library.value.installed.firstOrNull { it.manifest.id == Creations.ID }?.pack

    /** How many things the player has kept, for the home screen's tile. */
    fun creationCount(): Int = creations()?.let { it.itemBases.size + it.affixes.size + it.uniques.size + it.itemSets.size + it.loreEntries.size } ?: 0

    /**
     * Installs the player's creations over the last version of themselves:
     * one plugin that grows, rather than one per thing kept. It depends on
     * the built-in pack whose bases and damage types its gear is made on.
     */
    suspend fun installCreations(pack: ContentPack) {
        val manifest = PluginManifest(
            id = Creations.ID,
            name = Creations.NAME,
            version = Version(1, 0, 0),
            author = pack.author.ifBlank { "A Stratum player" },
            description = pack.description,
            dependencies = listOf(builtInDependency()),
        )
        repository.install("${Creations.ID}.${PluginArchive.EXTENSION}", PluginArchive.write(manifest, pack.copy(id = Creations.ID)))
        repository.refresh()
    }

    /**
     * Packs the player's classes and their kept creations into one
     * `.stratum` plugin and opens the share sheet. The plugin names the
     * built-in pack as a dependency, because both use its skills, weapons
     * and bases, so it installs cleanly anywhere this game runs. The
     * creations move to the shared namespace, so a friend's copy sits
     * beside their own rather than replacing it. Returns false when there
     * is nothing to share.
     */
    fun shareCreations(classes: List<HeroClassDefinition>): Boolean {
        val creations = creations()?.let { Creations.renamespaced(it, CREATIONS_ID) }
        if (classes.isEmpty() && creations == null) return false
        val manifest = PluginManifest(
            id = CREATIONS_ID,
            name = "Shared creations",
            version = Version(1, 0, 0),
            author = "A Stratum player",
            license = "CC-BY-4.0",
            description = listOfNotNull(
                "${classes.size} classes built in the class forge".takeIf { classes.isNotEmpty() },
                creations?.let { "${it.uniques.size + it.itemBases.size + it.affixes.size + it.itemSets.size} pieces of gear and ${it.loreEntries.size} lore entries from the content forge" },
            ).joinToString(", ") + ".",
            dependencies = listOf(builtInDependency()),
        )
        val classPack = CustomClassPack.of(classes).copy(id = CREATIONS_ID, name = manifest.name, author = manifest.author)
        val pack = Creations.combine(classPack, creations)
        val file = File(File(context.cacheDir, "share").apply { mkdirs() }, "shared-creations.${PluginArchive.EXTENSION}")
        file.writeBytes(PluginArchive.write(manifest, pack))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share your creations").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /**
     * Packs generated character sheets into a `.stratum` plugin and opens the
     * share sheet. Each sheet goes under `art/sheets/` with its grid in
     * pack.json, so the other device installs a character it can draw rather
     * than receiving a picture. What could not be included is returned by id
     * with the reason; null when nothing could be shared at all.
     */
    fun shareSheets(art: List<SheetArt>): Map<String, String>? {
        val bundle = SpritePluginExport.bundle(art, toPng = ::reencodePng) ?: return null
        val file = File(File(context.cacheDir, "share").apply { mkdirs() }, "shared-sprites.${PluginArchive.EXTENSION}")
        file.writeBytes(PluginArchive.write(bundle.manifest, bundle.pack, sheets = bundle.images))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share your characters").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return bundle.skipped
    }

    /** Some providers answer in JPEG whatever was asked; a plugin's sheets must be PNG. */
    private fun reencodePng(bytes: ByteArray): ByteArray? = runCatching {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        if (ok) out.toByteArray() else null
    }.getOrNull()

    /**
     * Installs what the agent studio wrote as an ordinary plugin, depending on
     * the built-in pack whose ids it references -- so it can be shared,
     * disabled or removed like any other, and loads with the next world.
     */
    suspend fun installGenerated(pack: ContentPack) {
        val manifest = PluginManifest(
            id = pack.id,
            name = pack.name,
            version = Version(1, 0, 0),
            author = pack.author.ifBlank { "Agent studio" },
            description = pack.description,
            dependencies = listOf(builtInDependency()),
        )
        repository.install("${pack.id}.${PluginArchive.EXTENSION}", PluginArchive.write(manifest, pack))
    }

    private fun builtInDependency() = PluginDependency(builtIn.id, VersionRange.parse("^${builtIn.version.major}") ?: VersionRange.ANY)

    private companion object {
        /** Distinct from the player's own classes and creations, so an installed share never collides with either. */
        const val CREATIONS_ID = "shared.creations"
    }
}
