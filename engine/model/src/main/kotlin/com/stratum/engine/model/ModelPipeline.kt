package com.stratum.engine.model

import com.stratum.core.domain.ai.ModelFormat
import com.stratum.core.domain.content.VoxelBlueprint
import com.stratum.engine.model.gltf.GltfLoader
import com.stratum.engine.model.gltf.ModelLimits
import com.stratum.engine.scene.PropModel
import com.stratum.engine.scene.Texture
import kotlin.math.sqrt

/**
 * Everything a generated model goes through, from bytes to something the game
 * can use, in one place.
 *
 * Load, then normalise once to the size it will stand at; from there it can
 * become any of three things, and the forge usually wants all three:
 *
 * - a [PropModel], decimated for a phone, which the 3D view draws lit and
 *   shadowed like the terrain;
 * - a [VoxelBlueprint], the model rebuilt from the pack's own blocks;
 * - [BakedSprites], for everything that draws sprites.
 *
 * Every stage is pure and deterministic: the same bytes give the same blocks
 * and the same pixels on every device, which is what makes a model shared in
 * a plugin look the same to everyone who installs it.
 */
class ModelPipeline(
    /** Decodes the PNG or JPEG textures inside a GLB; null keeps base colours only. */
    decodeImage: ((ByteArray) -> Texture?)? = null,
    private val limits: ModelLimits = ModelLimits(),
) {
    private val gltf = GltfLoader(decodeImage, limits)
    private val obj = ObjLoader(limits)

    /** Parses [bytes] as whichever format they are. Failures carry a sentence for the player. */
    fun load(bytes: ByteArray): Result<ModelMesh> = runCatching {
        when (ModelFormat.sniff(bytes)) {
            ModelFormat.GLB -> gltf.loadGlb(bytes)
            ModelFormat.OBJ -> obj.load(bytes)
            null -> throw ModelFormatException("This is not a GLB or OBJ model")
        }
    }.recoverCatching { failure ->
        // Anything that escaped the loaders' own checks is still a bad file, not a crash.
        throw failure as? ModelFormatException ?: ModelFormatException("The model could not be read: ${failure.message}", failure)
    }

    /** Stands the model up at [heightBlocks] tall, centred on the origin, on the ground. */
    fun normalize(mesh: ModelMesh, heightBlocks: Float, fromYUp: Boolean = true): ModelMesh =
        ModelNormalizer.normalize(mesh, heightBlocks, fromYUp = fromYUp)

    /**
     * The model as a prop the 3D view can draw, at most [maxTriangles].
     *
     * Colours are taken from the full-detail mesh before it is decimated, so a
     * painted texture still shows as patches of colour on the simplified one.
     */
    fun prop(normalized: ModelMesh, maxTriangles: Int = DEFAULT_PROP_TRIANGLES): PropModel {
        val simplified = ModelDecimator.decimate(ColoredMesh.of(normalized), maxTriangles)
        return toProp(simplified)
    }

    /** The model as blocks from [palette]. */
    fun blueprint(
        normalized: ModelMesh,
        palette: BlockPalette,
        id: String,
        name: String,
        solid: Boolean = true,
    ): VoxelBlueprint = palette.toBlueprint(Voxelizer.voxelize(normalized, solid = solid), id, name)

    /** The model's colours as cells, for callers that keep per-voxel colour rather than blocks. */
    fun voxels(normalized: ModelMesh, cellsPerBlock: Int = 1, solid: Boolean = true): VoxelGrid =
        Voxelizer.voxelize(normalized, cellsPerBlock, solid)

    fun sprites(prop: PropModel, size: Int = SpriteBaker.DEFAULT_SIZE): BakedSprites = SpriteBaker.bake(prop, size)

    companion object {
        /**
         * Enough for a statue to keep its silhouette at prop size. A view holds
         * a few dozen props at most; at this budget they cost less together
         * than the terrain they stand on.
         */
        const val DEFAULT_PROP_TRIANGLES = 2_000

        /** Splits a coloured mesh into the flat triangle soup a prop is drawn from. */
        fun toProp(mesh: ColoredMesh): PropModel {
            val triangles = mesh.triangleCount
            val positions = FloatArray(triangles * 9)
            val normals = FloatArray(triangles * 3)
            for (t in 0 until triangles) {
                for (corner in 0 until 3) mesh.positions.copyInto(positions, t * 9 + corner * 3, mesh.indices[t * 3 + corner] * 3, mesh.indices[t * 3 + corner] * 3 + 3)
                val n = ModelNormalizer.faceNormal(mesh.positions, mesh.indices[t * 3], mesh.indices[t * 3 + 1], mesh.indices[t * 3 + 2])
                // The file's own normals say which side is out; a face wound the other way is turned round.
                val hx = mesh.hints[t * 3]; val hy = mesh.hints[t * 3 + 1]; val hz = mesh.hints[t * 3 + 2]
                val hinted = sqrt(hx * hx + hy * hy + hz * hz) > 1e-6f
                val flip = hinted && n[0] * hx + n[1] * hy + n[2] * hz < 0f
                normals[t * 3] = if (flip) -n[0] else n[0]
                normals[t * 3 + 1] = if (flip) -n[1] else n[1]
                normals[t * 3 + 2] = if (flip) -n[2] else n[2]
            }
            return PropModel(positions, normals, mesh.colors.copyOf())
        }
    }
}
