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
     * Unlit, alpha-blended, lying on the ground.
     *
     * Contact shadows, rank rings and pull-me halos, procedural so they are
     * resolution-free and cost no asset; and the shadows sprites cast, which
     * take their shape from the sprite's own alpha.
     */
    DECAL,

    /** Unlit, additive, soft. Light blooms, embers, fireflies. */
    GLOW,
}

/**
 * The one vertex layout every backend reads.
 *
 * Sixteen floats. Wasteful for a GPU that could pack normals into bytes, and
 * worth it: a single layout means a single mesher, and the rasteriser that
 * produces the preview images reads exactly the buffer the phone uploads.
 */
object Vertex {
    const val STRIDE = 16
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
     * untextured and rim-lit. At or above [TextureLibrary.MAP_BASE] it is a
     * ground map. For decals it is the pattern: [DISC], [RING] or
     * [SPRITE_SHADOW].
     */
    const val LAYER = 12
    const val EMISSIVE = 13

    /**
     * Two more paintings of the same surface, or -1. Opaque ground blends
     * towards them in slow patches (see [ShadingModel.variants]), so a field
     * is three paintings woven together rather than one repeated.
     */
    const val VARIANT_A = 14
    const val VARIANT_B = 15

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

    /**
     * A decal shaped by a sprite's own silhouette: the texture layer is in
     * [VARIANT_A] and u, v run 0..1 over the sprite. How props and characters
     * cast shadows — see SceneBuilder.spriteShadow.
     */
    const val SPRITE_SHADOW = 2f
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
        variantA: Float = -1f,
        variantB: Float = -1f,
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
        vertices[o + 14] = variantA
        vertices[o + 15] = variantB
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
    private val maps = ArrayList<Texture>()

    /** Tiles and sprites, by layer. */
    val all: List<Texture> get() = textures

    /**
     * Ground maps, by layer minus [MAP_BASE]. Kept apart because they are
     * several times larger than everything else: a GPU array holds layers of
     * one size, and resampling a map down to a tile's size would throw away
     * exactly the detail it exists to carry.
     */
    val allMaps: List<Texture> get() = maps
    val keys: Set<String> get() = layers.keys

    /** Adds or replaces a texture, keeping the layer index if the key existed. */
    fun put(key: String, texture: Texture): Int {
        val existing = layers[key]
        if (existing != null) {
            if (existing >= MAP_BASE) maps[existing - MAP_BASE] = texture else textures[existing] = texture
            return existing
        }
        val layer = if (com.stratum.core.domain.art.GroundMap.isMap(key)) {
            maps += texture
            MAP_BASE + maps.lastIndex
        } else {
            textures += texture
            textures.lastIndex
        }
        layers[key] = layer
        variantCache.clear()
        return layer
    }

    fun layerOf(key: String?): Int = key?.let(layers::get) ?: -1

    private val variantCache = HashMap<String, IntArray>()

    /**
     * Every painting of [key]: the key itself, then "key#1", "key#2" and on
     * while they exist. Empty when there is none. Callers pick one per
     * instance, so a grove is several trees rather than one tree many times.
     */
    fun variantsOf(key: String): IntArray = variantCache.getOrPut(key) {
        val base = layerOf(key)
        if (base < 0) return@getOrPut IntArray(0)
        val found = arrayListOf(base)
        var n = 1
        while (n < MAX_VARIANTS) {
            val next = layerOf("$key#$n")
            if (next < 0) break
            found += next
            n++
        }
        found.toIntArray()
    }

    companion object {
        private const val MAX_VARIANTS = 8

        /**
         * Layers from here up are ground maps. The shader reads them from a
         * second array; see Vertex.LAYER.
         */
        const val MAP_BASE = 1024
    }

    fun textureAt(layer: Int): Texture? =
        if (layer >= MAP_BASE) maps.getOrNull(layer - MAP_BASE) else textures.getOrNull(layer)
}
