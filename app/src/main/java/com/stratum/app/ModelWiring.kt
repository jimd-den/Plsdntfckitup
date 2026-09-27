package com.stratum.app

import android.content.Context
import com.stratum.core.data.ai.model3d.HttpModelGeneration
import com.stratum.core.data.model.ModelAssetStore
import com.stratum.core.data.settings.ProviderSettingsStore
import com.stratum.core.domain.ai.GenerateModelUseCase
import com.stratum.core.domain.ai.ImageModelPort
import com.stratum.core.domain.ai.ModelAsset
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.ModelDefinition
import com.stratum.core.domain.content.VoxelBlueprint
import com.stratum.engine.model.ModelPipeline
import com.stratum.engine.scene.PropModel
import com.stratum.feature.forge.ModelForgeStorage
import com.stratum.feature.play.gl.AndroidImageCodec
import java.io.File

/**
 * Wires generated 3D models: the provider, the store, the pipeline, and the
 * step that turns stored models into things the world draws.
 *
 * Models forged on the device join the loaded packs' own model references as
 * ordinary [ModelDefinition]s, so from the renderer's side there is one road:
 * a definition names a block or a monster, its source is resolved to bytes,
 * and the pure pipeline makes a prop or a sprite of it.
 */
class ModelWiring(context: Context, settings: ProviderSettingsStore, images: ImageModelPort) {

    val store = ModelAssetStore(File(context.filesDir, "model-forge"))

    val generation = HttpModelGeneration(configProvider = settings::loadModelProvider)

    val generate = GenerateModelUseCase(generation, images)

    /** Decodes a GLB's embedded textures with the platform decoder. */
    val pipeline = ModelPipeline(decodeImage = AndroidImageCodec::decode)

    private val props = HashMap<String, PropModel?>()

    val storage: ModelForgeStorage = object : ModelForgeStorage {
        override fun all(): List<ModelAsset> = store.all()
        override fun save(asset: ModelAsset, bytes: ByteArray, previewPng: ByteArray?) = store.save(asset, bytes, previewPng)
        override fun update(asset: ModelAsset) = store.update(asset)
        override fun delete(id: String) = store.delete(id)
        override fun bytesFor(id: String): ByteArray? = store.bytesFor(id)
        override fun previewFor(id: String): ByteArray? = store.previewFor(id)
        override fun saveBlueprint(blueprint: VoxelBlueprint) = store.saveBlueprint(blueprint)
    }

    /**
     * [content] with the device's bound models added.
     *
     * Only bindings whose block or monster the loaded packs still define: a
     * model bound to a block from a plugin since disabled is kept on disk but
     * dresses nothing, rather than failing the whole world's assembly.
     */
    fun withForgedModels(content: AssembledContent): AssembledContent {
        val forged = store.all().mapNotNull(ModelAsset::toDefinition).mapNotNull { definition ->
            val block = definition.blockId?.takeIf(content.registry::contains)
            val enemy = definition.enemyId?.takeIf { id -> content.enemies.any { it.id == id } }
            if (block == null && enemy == null) null else definition.copy(blockId = block, enemyId = enemy)
        }
        if (forged.isEmpty()) return content
        return content.copy(models = (forged + content.models).distinctBy { it.id })
    }

    /**
     * The prop models the world draws, by block id. Slow on first call — each
     * model is parsed, decimated and cached — so it is called off the main
     * thread. Monsters bound to a model get a baked sprite written where the
     * 3D view looks for their art.
     */
    fun propModels(content: AssembledContent): Map<String, PropModel> {
        val out = HashMap<String, PropModel>()
        content.models.forEach { definition ->
            val prop = propFor(definition) ?: return@forEach
            definition.blockId?.let { out[it] = prop }
            definition.enemyId?.let { enemy -> bakeActor(enemy, prop) }
        }
        return out
    }

    fun blueprints(): List<VoxelBlueprint> = store.blueprints()

    /** Where baked model sprites are, for the 3D view to lay over its kit. */
    val textureDirectory: File get() = store.textureDirectory

    private fun propFor(definition: ModelDefinition): PropModel? = props.getOrPut("${definition.source}@${definition.height}") {
        // Models inside plugin archives are not unpacked yet; only forged assets resolve.
        val bytes = definition.assetId?.let(store::bytesFor) ?: return@getOrPut null
        pipeline.load(bytes).mapCatching { pipeline.prop(pipeline.normalize(it, definition.height)) }.getOrNull()
    }

    private fun bakeActor(enemyId: String, prop: PropModel) {
        val key = "actor:$enemyId"
        if (File(store.textureDirectory, com.stratum.core.domain.art.TextureKeys.fileNameFor(key)).isFile) return
        runCatching { store.saveTexture(key, AndroidImageCodec.encodePng(pipeline.sprites(prop).front)) }
    }
}
