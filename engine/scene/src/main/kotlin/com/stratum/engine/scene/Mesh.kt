package com.stratum.engine.scene

/**
 * How a batch of triangles is drawn.
 *
 * Four kinds and no more, because each one is a pipeline state on the GPU and a
 * code path in the software rasteriser, and both have to agree on all of them.
 */
enum class MaterialKind {
    /** Lit, depth-written, shadow-casting. Terrain, walls, actors. */
    OPAQUE,

    /** Like opaque, but texels below half alpha are discarded. Props and sprites. */
    CUTOUT,

    /**
     * Unlit, alpha-blended, procedurally shaped, lying on the ground.
     *
     * Contact shadows, rank rings and pull-me halos. Procedural rather than
     * textured so they are resolution-free and cost no asset.
     */
    DECAL,

    /** Unlit, additive, soft. Light blooms, embers, fireflies. */
    GLOW,
}

/**
 * The one vertex layout every backend reads.
 *
 * Fourteen floats. Wasteful for a GPU that could pack normals into bytes, and
 * worth it: a single layout means a single mesher, and the rasteriser that
 * produces the preview images reads exactly the buffer the phone uploads.
 */
object Vertex {
    const val STRIDE = 14
    const val PX = 0
    const val NX = 3
    const val R = 6
    /**
     * Ambient occlusion for opaque geometry; opacity for everything else. For
     * cut-outs below one it is a screen-door fade: see [DITHER].
     */
    const val AO = 9
    const val U = 10
    const val V = 11
    /**
     * Texture layer, or a negative code: [FLAT] is untextured, [ACTOR] is
     * untextured and rim-lit. For decals it is the pattern: [DISC] or [RING].
     */
    const val LAYER = 12
    const val EMISSIVE = 13

    const val FLAT = -1f

    /**
     * The 4x4 ordered-dither thresholds both backends use for screen-door
     * fades, so a faded tree has the identical pattern on a phone and in a
     * preview image.
     */
    val DITHER = floatArrayOf(
        0f / 16, 8f / 16, 2f / 16, 10f / 16,
        12f / 16, 4f / 16, 14f / 16, 6f / 16,
        3f / 16, 11f / 16, 1f / 16, 9f / 16,
        15f / 16, 7f / 16, 13f / 16, 5f / 16,
    )
    const val ACTOR = -2f
    const val DISC = 0f
    const val RING = 1f
}

/** Triangles of one material kind, ready to upload or rasterise. */
class MeshBatch(
    val kind: MaterialKind,
    val vertices: FloatArray,
    val indices: IntArray,
) {
    val vertexCount: Int get() = vertices.size / Vertex.STRIDE
    val triangleCount: Int get() = indices.size / 3
}

/** A growable vertex and index buffer. Reused frame to frame by its owner. */
class MeshBuilder(private val kind: MaterialKind) {
    private var vertices = FloatArray(4096)
    private var indices = IntArray(4096)
    private var vertexFloats = 0
    private var indexCount = 0

    val vertexCount: Int get() = vertexFloats / Vertex.STRIDE
    val isEmpty: Boolean get() = indexCount == 0

    fun clear() {
        vertexFloats = 0
        indexCount = 0
    }

    fun vertex(
        x: Float, y: Float, z: Float,
        nx: Float, ny: Float, nz: Float,
        color: Long,
        ao: Float,
        u: Float, v: Float,
        layer: Float,
        emissive: Float = 0f,
    ): Int {
        if (vertexFloats + Vertex.STRIDE > vertices.size) vertices = vertices.copyOf(vertices.size * 2)
        val o = vertexFloats
        vertices[o] = x; vertices[o + 1] = y; vertices[o + 2] = z
        vertices[o + 3] = nx; vertices[o + 4] = ny; vertices[o + 5] = nz
        vertices[o + 6] = ((color ushr 16) and 0xFF) / 255f
        vertices[o + 7] = ((color ushr 8) and 0xFF) / 255f
        vertices[o + 8] = (color and 0xFF) / 255f
        vertices[o + 9] = ao
        vertices[o + 10] = u; vertices[o + 11] = v
        vertices[o + 12] = layer
        vertices[o + 13] = emissive
        vertexFloats += Vertex.STRIDE
        return o / Vertex.STRIDE
    }

    fun triangle(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > indices.size) indices = indices.copyOf(indices.size * 2)
        indices[indexCount] = a; indices[indexCount + 1] = b; indices[indexCount + 2] = c
        indexCount += 3
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        triangle(a, b, c)
        triangle(a, c, d)
    }

    fun build(): MeshBatch = MeshBatch(kind, vertices.copyOf(vertexFloats), indices.copyOf(indexCount))
}

/** A texture as plain pixels, so the domain side never touches a platform bitmap. */
class Texture(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height) { "A ${width}x$height texture needs ${width * height} pixels" }
    }
}

/**
 * Textures by key, each at a stable layer index.
 *
 * Keys come from the art director ("igbo:grove_turf/top", "prop:igbo:iroko_canopy")
 * and textures come from the asset forge; this is where the two meet. A key
 * with no texture answers -1 and the surface draws in its flat colour, which is
 * what lets a world be played while its art is still being generated.
 */
class TextureLibrary {
    private val layers = LinkedHashMap<String, Int>()
    private val textures = ArrayList<Texture>()

    val all: List<Texture> get() = textures
    val keys: Set<String> get() = layers.keys

    /** Adds or replaces a texture, keeping the layer index if the key existed. */
    fun put(key: String, texture: Texture): Int {
        val existing = layers[key]
        if (existing != null) {
            textures[existing] = texture
            return existing
        }
        textures += texture
        layers[key] = textures.lastIndex
        return textures.lastIndex
    }

    fun layerOf(key: String?): Int = key?.let(layers::get) ?: -1

    fun textureAt(layer: Int): Texture? = textures.getOrNull(layer)
}
