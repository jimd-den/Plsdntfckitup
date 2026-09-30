package com.stratum.core.data.model

import com.stratum.core.domain.ai.ModelAsset
import com.stratum.core.domain.ai.ModelFormat
import com.stratum.core.domain.ai.ModelSubjectKind
import com.stratum.core.domain.art.TextureKeys
import com.stratum.core.domain.content.VoxelBlueprint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Generated models on the device: the file the provider sent, what it was, a
 * baked preview, and the blueprint made from it.
 *
 * One folder per model, written file by file with the metadata last, like the
 * sprite and weapon libraries: a folder with a model and no metadata is
 * ignored, which is recoverable; metadata naming a file that is not there
 * would be a model that cannot be drawn.
 *
 * Takes a directory rather than a context, so it can be tested on the JVM and
 * so the composition root decides where models live.
 */
class ModelAssetStore(private val root: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val models: File get() = File(root, MODELS).apply { mkdirs() }

    /**
     * Sprites baked from bound models, named by texture key, for the 3D view
     * to lay over its kit exactly as it lays an imported pack's textures.
     */
    val textureDirectory: File get() = File(root, TEXTURES).apply { mkdirs() }

    /** Models on disk, newest first. */
    fun all(): List<ModelAsset> = models.listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { dir -> File(dir, META).takeIf(File::isFile)?.let { it to read(it) } }
        .sortedByDescending { it.first.lastModified() }
        .mapNotNull { it.second }

    fun find(id: String): ModelAsset? = read(File(folder(id), META))

    /** The model's bytes as the provider sent them. */
    fun bytesFor(id: String): ByteArray? {
        val asset = find(id) ?: return null
        return File(folder(id), "$MODEL.${asset.format.extension}").takeIf(File::isFile)?.readBytes()
    }

    fun previewFor(id: String): ByteArray? = File(folder(id), PREVIEW).takeIf(File::isFile)?.readBytes()

    fun save(asset: ModelAsset, modelBytes: ByteArray, previewPng: ByteArray? = null) {
        val dir = folder(asset.id).apply { mkdirs() }
        File(dir, "$MODEL.${asset.format.extension}").writeBytes(modelBytes)
        previewPng?.let { File(dir, PREVIEW).writeBytes(it) }
        writeMeta(asset)
    }

    /** Changes only what the asset is bound to, leaving its files alone. */
    fun update(asset: ModelAsset) {
        if (!File(folder(asset.id), META).isFile) return
        writeMeta(asset)
    }

    fun delete(id: String) {
        find(id)?.enemyId?.let { File(textureDirectory, TextureKeys.fileNameFor("actor:$it")).delete() }
        folder(id).deleteRecursively()
    }

    // ---- blueprints ----------------------------------------------------------

    fun saveBlueprint(blueprint: VoxelBlueprint) {
        File(File(root, BLUEPRINTS).apply { mkdirs() }, file(blueprint.id) + ".json")
            .writeText(json.encodeToString(BlueprintDto.serializer(), BlueprintDto.of(blueprint)))
    }

    fun blueprints(): List<VoxelBlueprint> = File(root, BLUEPRINTS).listFiles { f -> f.extension == "json" }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { f -> runCatching { json.decodeFromString(BlueprintDto.serializer(), f.readText()).toDomain() }.getOrNull() }

    fun deleteBlueprint(id: String) {
        File(File(root, BLUEPRINTS), file(id) + ".json").delete()
    }

    /** Writes a baked sprite under [key] where the 3D view will find it. */
    fun saveTexture(key: String, png: ByteArray) {
        File(textureDirectory, TextureKeys.fileNameFor(key)).writeBytes(png)
    }

    private fun writeMeta(asset: ModelAsset) {
        File(folder(asset.id), META).writeText(json.encodeToString(AssetDto.serializer(), AssetDto.of(asset)))
    }

    private fun read(file: File): ModelAsset? = runCatching {
        json.decodeFromString(AssetDto.serializer(), file.readText()).toDomain()
    }.getOrNull()

    private fun folder(id: String) = File(models, file(id))

    private fun file(id: String) = id.replace(NON_FILE_SAFE, "_")

    private companion object {
        const val MODELS = "models"
        const val TEXTURES = "textures"
        const val BLUEPRINTS = "blueprints"
        const val META = "model.json"
        const val MODEL = "model"
        const val PREVIEW = "preview.png"
        val NON_FILE_SAFE = Regex("[^A-Za-z0-9_.-]")
    }
}

@Serializable
private data class AssetDto(
    val id: String,
    val name: String,
    val prompt: String = "",
    val kind: String = ModelSubjectKind.PROP.name,
    val format: String = ModelFormat.GLB.name,
    val provider: String = "",
    val heightBlocks: Float = 1.5f,
    val triangleCount: Int = 0,
    val propBlockId: String? = null,
    val enemyId: String? = null,
    val blueprintId: String? = null,
) {
    fun toDomain() = ModelAsset(
        id, name, prompt,
        ModelSubjectKind.entries.firstOrNull { it.name == kind } ?: ModelSubjectKind.PROP,
        ModelFormat.entries.firstOrNull { it.name == format } ?: ModelFormat.GLB,
        provider, heightBlocks, triangleCount, propBlockId, enemyId, blueprintId,
    )

    companion object {
        fun of(a: ModelAsset) = AssetDto(
            a.id, a.name, a.prompt, a.kind.name, a.format.name, a.provider, a.heightBlocks, a.triangleCount,
            a.propBlockId, a.enemyId, a.blueprintId,
        )
    }
}

@Serializable
private data class BlueprintDto(
    val id: String,
    val name: String,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val palette: List<String>,
    val cells: List<Int>,
    val anchorX: Int,
    val anchorY: Int,
) {
    fun toDomain() = VoxelBlueprint(id, name, sizeX, sizeY, sizeZ, palette, cells.toIntArray(), anchorX, anchorY)

    companion object {
        fun of(b: VoxelBlueprint) = BlueprintDto(b.id, b.name, b.sizeX, b.sizeY, b.sizeZ, b.palette, b.cells.toList(), b.anchorX, b.anchorY)
    }
}
