package com.stratum.engine.scene

import com.stratum.core.domain.world.Chunk
import com.stratum.engine.microvoxel.Material
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunkPos
import com.stratum.engine.microvoxel.mesh.Quad
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Surfels: tiny lit discs, an eighth of a block across, scattered over the
 * microvoxel surfaces nearest the camera.
 *
 * A quarter-block voxel is as fine as the world can afford to store and
 * mesh, and at the hero's feet it is still coarse: a lawn is a green lid, a
 * stony slope a grey one. Surfels add the next octave -- grass tufts, pebbles,
 * grit, thatch straws -- without a single extra voxel. They are points, not
 * geometry: one vertex each, drawn as round sprites, lit once per point, and
 * never seen by collision, picking or anything else that plays. Delete every
 * surfel and the game is unchanged; only the picture is poorer.
 *
 * Twelve bytes a surfel ([Surfel]), so a chunk's worth stays small enough to
 * keep beside its mesh, and ordered by a random rank so a renderer thins a
 * chunk by drawing a shorter prefix of it: level of detail with no index
 * buffer and no work per frame.
 */
class SurfelBatch(
    /** The block chunk's origin; positions inside [data] are relative to it. */
    val originX: Int,
    val originY: Int,
    /** [Surfel.INTS] ints a surfel, [count] surfels, sorted by rank ascending. */
    val data: IntArray,
    val count: Int,
    /** Lowest and highest z of any surfel, for culling against the view. */
    val minZ: Float,
    val maxZ: Float,
) {
    val bytes: Int get() = count * Surfel.INTS * 4
}

/**
 * One chunk's surfels as drawn this frame: the first [count] of the batch,
 * each faded out by rank where [keep] times its distance share falls below
 * it. See [SurfelLod].
 */
class SurfelDraw(val batch: SurfelBatch, val count: Int, val keep: Float)

/**
 * How far out surfels thin, shared by both backends.
 *
 * A surfel is kept where its rank is below [share] of the distance from the
 * focus times the frame's budget factor, and shrinks away over the last
 * [FADE] of rank rather than popping.
 */
object SurfelLod {
    /** Full density out to half the radius, then thinning smoothly to none at the radius. */
    fun share(distance: Float, radius: Float): Float = 1f - ShadingModel.smoothstep(radius * NEAR_SHARE, radius, distance)

    /** 0..1 size of a surfel of [rank] under a keep threshold of [threshold]. */
    fun size(rank: Float, threshold: Float): Float = ((threshold - rank) / FADE).coerceIn(0f, 1f)

    /**
     * The surfels to draw this frame: every chunk within [radius] blocks of
     * the focus and in view, each cut to the prefix its nearest point needs,
     * all thinned alike when together they would pass [budget].
     */
    fun select(
        batches: List<SurfelBatch>, focusX: Float, focusY: Float, radius: Int, budget: Int,
        visible: (SurfelBatch) -> Boolean,
    ): List<SurfelDraw> {
        if (radius <= 0 || budget <= 0 || batches.isEmpty()) return emptyList()
        var wanted = 0f
        for (b in batches) wanted += b.count * nearestShare(b, focusX, focusY, radius)
        if (wanted <= 0f) return emptyList()
        val keep = if (wanted > budget) budget / wanted else 1f
        val out = ArrayList<SurfelDraw>(4)
        for (b in batches) {
            val share = nearestShare(b, focusX, focusY, radius)
            if (share <= 0f || !visible(b)) continue
            // The prefix whose ranks can still show, plus the fading band.
            val n = minOf(b.count, ceil(b.count * minOf(1f, share * keep + FADE)).toInt())
            if (n > 0) out += SurfelDraw(b, n, keep)
        }
        return out
    }

    private fun nearestShare(b: SurfelBatch, fx: Float, fy: Float, radius: Int): Float {
        val dx = maxOf(b.originX - fx, fx - (b.originX + Chunk.SIZE), 0f)
        val dy = maxOf(b.originY - fy, fy - (b.originY + Chunk.SIZE), 0f)
        return share(sqrt(dx * dx + dy * dy), radius.toFloat())
    }

    const val NEAR_SHARE = 0.5f
    const val FADE = 0.08f
}

/**
 * The twelve-byte surfel, and the one decoder both backends follow.
 *
 * ```
 *  int 0  x (10 bits), y (10 bits), z (12 bits): 1/32 block, x and y from chunk origin - 8
 *  int 1  red, green, blue (8 bits each, high to low), radius (8 bits, 0..[MAX_RADIUS] blocks)
 *  int 2  normal as an octahedral pair (8 + 8 bits), occlusion (8 bits), rank (8 bits, high)
 * ```
 * The GLSL twin is the top of `SceneShaders.SURFEL_VERTEX`.
 */
object Surfel {
    const val INTS = 3
    const val POSITION_STEP = 1f / 32f
    const val XY_OFFSET = 8f
    const val MAX_RADIUS = 0.2f

    fun pack(out: IntArray, at: Int, lx: Float, ly: Float, z: Float, rgb: Int, radius: Float, nx: Float, ny: Float, nz: Float, ao: Float, rank: Float) {
        val px = ((lx + XY_OFFSET) / POSITION_STEP + 0.5f).toInt().coerceIn(0, 1023)
        val py = ((ly + XY_OFFSET) / POSITION_STEP + 0.5f).toInt().coerceIn(0, 1023)
        val pz = (z / POSITION_STEP + 0.5f).toInt().coerceIn(0, 4095)
        out[at] = px or (py shl 10) or (pz shl 20)
        out[at + 1] = ((rgb and 0xFFFFFF) shl 8) or (radius / MAX_RADIUS * 255f + 0.5f).toInt().coerceIn(0, 255)
        // Octahedral normal: the unit sphere folded onto a square, two bytes.
        val l1 = abs(nx) + abs(ny) + abs(nz)
        var ox = nx / l1; var oy = ny / l1
        if (nz < 0f) {
            val fx = (1f - abs(oy)) * (if (ox >= 0f) 1f else -1f)
            val fy = (1f - abs(ox)) * (if (oy >= 0f) 1f else -1f)
            ox = fx; oy = fy
        }
        val ex = ((ox * 0.5f + 0.5f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val ey = ((oy * 0.5f + 0.5f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        out[at + 2] = ex or (ey shl 8) or ((ao * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((rank * 255f).toInt().coerceIn(0, 255) shl 24)
    }

    fun x(b: SurfelBatch, i: Int): Float = (b.data[i * INTS] and 1023) * POSITION_STEP - XY_OFFSET + b.originX
    fun y(b: SurfelBatch, i: Int): Float = ((b.data[i * INTS] ushr 10) and 1023) * POSITION_STEP - XY_OFFSET + b.originY
    fun z(b: SurfelBatch, i: Int): Float = (b.data[i * INTS] ushr 20) * POSITION_STEP
    fun red(b: SurfelBatch, i: Int): Float = ((b.data[i * INTS + 1] ushr 24) and 255) / 255f
    fun green(b: SurfelBatch, i: Int): Float = ((b.data[i * INTS + 1] ushr 16) and 255) / 255f
    fun blue(b: SurfelBatch, i: Int): Float = ((b.data[i * INTS + 1] ushr 8) and 255) / 255f
    fun radius(b: SurfelBatch, i: Int): Float = (b.data[i * INTS + 1] and 255) / 255f * MAX_RADIUS
    fun occlusion(b: SurfelBatch, i: Int): Float = ((b.data[i * INTS + 2] ushr 16) and 255) / 255f
    fun rank(b: SurfelBatch, i: Int): Float = ((b.data[i * INTS + 2] ushr 24) and 255) / 255f

    /** The normal into [out] (x, y, z), unfolded from its octahedral pair. */
    fun normal(b: SurfelBatch, i: Int, out: FloatArray) {
        val q = b.data[i * INTS + 2]
        var ex = (q and 255) / 255f * 2f - 1f
        var ey = ((q ushr 8) and 255) / 255f * 2f - 1f
        val ez = 1f - abs(ex) - abs(ey)
        if (ez < 0f) {
            val fx = (1f - abs(ey)) * (if (ex >= 0f) 1f else -1f)
            val fy = (1f - abs(ex)) * (if (ey >= 0f) 1f else -1f)
            ex = fx; ey = fy
        }
        val l = sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(1e-5f)
        out[0] = ex / l; out[1] = ey / l; out[2] = ez / l
    }
}

/**
 * Scatters surfels over one chunk's meshed microvoxel faces.
 *
 * Driven by the greedy mesher's own quads, so surfels land only on faces
 * that are really there -- after the player's digging and building have been
 * patched in -- and never inside a wall. Each voxel face is split into a 2x2
 * grid of eighth-block cells, and every cell rolls, from a hash of its
 * coordinates, its face and its material, whether it grows something and
 * what: the same place always grows the same tuft, on every device and in
 * every preview.
 *
 * What grows depends on the material ([Kind]): grass and leaves grow tufts
 * of two or three stacked discs, lighter and yellower at the tip; rock grows
 * pebbles, lifted proud of the face with tilted normals; earth and sand grow
 * fine grit; thatch grows straws; built surfaces only a little wear. Water,
 * glass, metal, paint lines and anything that glows grow nothing.
 *
 * One per meshing thread: it keeps its scratch arrays between chunks.
 */
class SurfelScatter(private val palette: MaterialPalette, private val density: Float = 1f) {

    /** What a material grows, by density on top faces and on sides, disc size, lift and colour spread. */
    enum class Kind(
        val top: Float, val side: Float,
        val radius: Float, val radiusSpread: Float,
        val lift: Float, val stack: Int,
        val tone: Float, val tilt: Float,
    ) {
        TUFT(top = 0.42f, side = 0.12f, radius = 0.062f, radiusSpread = 0.02f, lift = 0.05f, stack = 2, tone = 0.16f, tilt = 0.35f),
        FOLIAGE(top = 0.3f, side = 0.22f, radius = 0.07f, radiusSpread = 0.02f, lift = 0.035f, stack = 1, tone = 0.2f, tilt = 0.5f),
        PEBBLE(top = 0.22f, side = 0.1f, radius = 0.058f, radiusSpread = 0.025f, lift = 0.02f, stack = 1, tone = 0.22f, tilt = 0.7f),
        GRIT(top = 0.3f, side = 0.08f, radius = 0.042f, radiusSpread = 0.012f, lift = 0.008f, stack = 1, tone = 0.12f, tilt = 0.3f),
        STRAW(top = 0.36f, side = 0.3f, radius = 0.05f, radiusSpread = 0.012f, lift = 0.02f, stack = 1, tone = 0.2f, tilt = 0.45f),
        WEAR(top = 0.07f, side = 0.05f, radius = 0.045f, radiusSpread = 0.012f, lift = 0.006f, stack = 1, tone = 0.1f, tilt = 0.2f),
        NONE(0f, 0f, 0f, 0f, 0f, 0, 0f, 0f),
    }

    private val kinds = HashMap<Short, Kind>()

    private var originX = 0
    private var originY = 0
    private var raw = IntArray(Surfel.INTS * 4096)
    private var ranks = ByteArray(4096)
    private var count = 0
    private var minZ = Float.MAX_VALUE
    private var maxZ = -Float.MAX_VALUE
    private val corner = FloatArray(4)

    /** Starts a new chunk. */
    fun begin(chunkOriginX: Int, chunkOriginY: Int) {
        originX = chunkOriginX; originY = chunkOriginY
        count = 0
        minZ = Float.MAX_VALUE; maxZ = -Float.MAX_VALUE
    }

    /**
     * Scatters over one micro chunk's full-detail quads. [microPerBlock] is
     * the world's voxels per block (4): surfel cells are half a voxel.
     */
    fun scatter(quads: LongArray, quadCount: Int, mpos: MicroChunkPos, microPerBlock: Int) {
        val s = 1f / microPerBlock
        for (i in 0 until quadCount) {
            if (count >= MAX_PER_CHUNK) return
            val q = quads[i]
            val face = Quad.face(q)
            if (face == 5) continue
            val kind = kindOf(Quad.material(q))
            val chance = (if (face == 4) kind.top else kind.side) * density
            if (chance <= 0f) continue
            val material = palette[Quad.material(q)]
            val x = Quad.x(q); val y = Quad.y(q); val z = Quad.z(q); val w = Quad.w(q); val h = Quad.h(q)
            for (c in 0 until 4) corner[c] = TerrainMesher.AO_LEVELS[Quad.ao(q, c)]
            val nx = NORMALS[face * 3]; val ny = NORMALS[face * 3 + 1]; val nz = NORMALS[face * 3 + 2]
            for (v in 0 until h) for (u in 0 until w) for (cell in 0 until 4) {
                // The voxel this face belongs to, in world microvoxels, and which quarter of its face.
                val vx: Int; val vy: Int; val vz: Int
                when (face) {
                    0, 1 -> { vx = x; vy = y + u; vz = z + v }
                    2, 3 -> { vx = x + u; vy = y; vz = z + v }
                    else -> { vx = x + u; vy = y + v; vz = z }
                }
                val wx = mpos.originX + vx; val wy = mpos.originY + vy; val wz = mpos.originZ + vz
                val h0 = hash(wx, wy, wz, face * 4 + cell, material.id.toInt())
                // Patchy, not even: gravel gathers in drifts and lawns go bald in places,
                // which is what makes a scatter look placed by hand rather than sprinkled.
                if ((h0 and 0xFFFF) / 65536f >= chance * patch(wx, wy, wz)) continue
                val h1 = mix(h0); val h2 = mix(h1); val h3 = mix(h2)
                // Where in the face: the cell's centre, jittered within it.
                val fu = ((cell and 1) + 0.5f + (unit(h1) - 0.5f) * 0.8f) * 0.5f
                val fv = ((cell shr 1) + 0.5f + (unit(h1 ushr 16) - 0.5f) * 0.8f) * 0.5f
                // Face plane in world microvoxels: + faces sit on the voxel's far side.
                val pu = u + fu; val pv = v + fv
                val px: Float; val py: Float; val pz: Float
                when (face) {
                    0, 1 -> { px = (vx + if (face == 0) 1 else 0).toFloat(); py = y + pu; pz = z + pv }
                    2, 3 -> { px = x + pu; py = (vy + if (face == 2) 1 else 0).toFloat(); pz = z + pv }
                    else -> { px = x + pu; py = y + pv; pz = (vz + 1).toFloat() }
                }
                // Occlusion where it lands, from the quad's four corners.
                val au = pu / w; val av = pv / h
                val ao = (corner[0] * (1 - au) + corner[1] * au) * (1 - av) + (corner[3] * (1 - au) + corner[2] * au) * av
                val rank = unit(h2 ushr 8)
                val baseRadius = kind.radius + (unit(h2) - 0.5f) * 2f * kind.radiusSpread
                val stack = if (kind.stack > 1) kind.stack - (h3 and 1) + (if (unit(h3 ushr 4) < 0.25f) 1 else 0) else 1
                val tiltU = (unit(h3 ushr 8) - 0.5f) * 2f * kind.tilt
                val tiltV = (unit(h3 ushr 16) - 0.5f) * 2f * kind.tilt
                val tone = 1f + (unit(h3 ushr 20) - 0.5f) * 2f * kind.tone
                for (k in 0 until stack) {
                    if (count >= MAX_PER_CHUNK) return
                    val up = k.toFloat() / maxOf(1, stack - 1)
                    // Tufts stand up and lean a little; each disc above the last, smaller and lighter.
                    val lift = kind.lift * (0.4f + k * 1.3f) + if (kind == Kind.TUFT) k * 0.035f else 0f
                    val lean = if (kind == Kind.TUFT) k * 0.02f else 0f
                    val ox = (mpos.originX + px) * s + nx * lift + (if (face >= 4) tiltU * lean * 2f else 0f)
                    val oy = (mpos.originY + py) * s + ny * lift + (if (face >= 4) tiltV * lean * 2f else 0f)
                    val oz = (mpos.originZ + pz) * s + nz * lift
                    // Normal: the face's, tipped by the cell's tilt. Along each face's u and v axes.
                    var mx = nx; var my = ny; var mz = nz
                    when (face) {
                        0, 1 -> { my += tiltU; mz += tiltV }
                        2, 3 -> { mx += tiltU; mz += tiltV }
                        else -> { mx += tiltU; my += tiltV }
                    }
                    val ml = sqrt(mx * mx + my * my + mz * mz)
                    val rgb = colour(material, kind, tone, up, h3)
                    val radius = baseRadius * (1f - up * 0.3f)
                    // Tips of tufts stand in the open: less occluded than their roots.
                    val occ = ao + (1f - ao) * up * 0.6f
                    add(ox - originX, oy - originY, oz, rgb, radius, mx / ml, my / ml, mz / ml, occ, rank)
                }
            }
        }
    }

    /** The chunk's surfels, sorted by rank, or null when it grew none. */
    fun build(): SurfelBatch? {
        if (count == 0) return null
        // Counting sort on the rank byte: the draw-a-prefix level of detail needs ascending rank.
        val starts = IntArray(257)
        for (i in 0 until count) starts[(ranks[i].toInt() and 255) + 1]++
        for (r in 1..256) starts[r] += starts[r - 1]
        val out = IntArray(count * Surfel.INTS)
        for (i in 0 until count) {
            val at = starts[ranks[i].toInt() and 255]++
            System.arraycopy(raw, i * Surfel.INTS, out, at * Surfel.INTS, Surfel.INTS)
        }
        // Padded by a step: positions are quantised, and the bounds must hold what is drawn.
        return SurfelBatch(originX, originY, out, count, minZ - Surfel.POSITION_STEP, maxZ + Surfel.POSITION_STEP)
    }

    private fun add(lx: Float, ly: Float, z: Float, rgb: Int, radius: Float, nx: Float, ny: Float, nz: Float, ao: Float, rank: Float) {
        if ((count + 1) * Surfel.INTS > raw.size) {
            raw = raw.copyOf(raw.size * 2)
            ranks = ranks.copyOf(ranks.size * 2)
        }
        Surfel.pack(raw, count * Surfel.INTS, lx, ly, z, rgb, radius, nx, ny, nz, ao, rank)
        ranks[count] = (rank * 255f).toInt().coerceIn(0, 255).toByte()
        if (z < minZ) minZ = z
        if (z > maxZ) maxZ = z
        count++
    }

    /** The material's colour, varied by [tone], and for tufts warmed towards straw at the tip ([up] 0 root, 1 tip). */
    private fun colour(m: Material, kind: Kind, tone: Float, up: Float, h: Int): Int {
        var r = ((m.color shr 16) and 255) / 255f
        var g = ((m.color shr 8) and 255) / 255f
        var b = (m.color and 255) / 255f
        when (kind) {
            Kind.TUFT, Kind.FOLIAGE -> {
                // Sun-bleached tips and the odd dry blade: towards straw.
                val dry = up * 0.3f + (if (unit(h ushr 24) < 0.18f) 0.35f else 0f)
                r += (0.78f - r) * dry; g += (0.74f - g) * dry; b += (0.36f - b) * dry
                val lift = 1f + up * 0.14f
                r *= lift; g *= lift; b *= lift
            }
            Kind.PEBBLE -> {
                // Stones a little greyer than the rock they lie on and sun-bleached on top, so
                // they read as pale pebbles catching the light rather than dark holes.
                val luma = 0.3f * r + 0.59f * g + 0.11f * b
                r += (luma - r) * 0.12f; g += (luma - g) * 0.12f; b += (luma - b) * 0.12f
                r *= PEBBLE_BLEACH; g *= PEBBLE_BLEACH; b *= PEBBLE_BLEACH
            }
            Kind.GRIT -> { r *= GRIT_BLEACH; g *= GRIT_BLEACH; b *= GRIT_BLEACH }
            else -> Unit
        }
        // A whisper of warm or cool, so neighbours differ in hue as well as value.
        val warm = (unit(h ushr 12) - 0.5f) * 0.06f
        r *= tone * (1f + warm); g *= tone; b *= tone * (1f - warm)
        fun ch(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /**
     * 0..[PATCH_MAX] multiplier on the scatter chance: smooth value noise
     * over [PATCH_CELL]-voxel cells, so density drifts across the ground.
     */
    private fun patch(wx: Int, wy: Int, wz: Int): Float {
        val gx = Math.floorDiv(wx, PATCH_CELL); val gy = Math.floorDiv(wy, PATCH_CELL)
        val fx = (wx - gx * PATCH_CELL + 0.5f) / PATCH_CELL; val fy = (wy - gy * PATCH_CELL + 0.5f) / PATCH_CELL
        val gz = Math.floorDiv(wz, PATCH_CELL * 2)
        fun corner(i: Int, j: Int) = unit(hash(gx + i, gy + j, gz, 99, 7))
        val sx = fx * fx * (3f - 2f * fx); val sy = fy * fy * (3f - 2f * fy)
        val top = corner(0, 0) + (corner(1, 0) - corner(0, 0)) * sx
        val bottom = corner(0, 1) + (corner(1, 1) - corner(0, 1)) * sx
        val n = top + (bottom - top) * sy
        return ShadingModel.smoothstep(PATCH_LOW, PATCH_HIGH, n) * PATCH_MAX
    }

    /** What a material grows, decided once per material from its name and properties. */
    fun kindOf(id: Short): Kind = kinds.getOrPut(id) { classify(palette[id]) }

    companion object {
        /** Surfels one chunk may hold: 384 KB, a third of what its mesh typically takes. */
        const val MAX_PER_CHUNK = 32_768

        /** Drifts of density are about this many voxels (three blocks) across. */
        const val PATCH_CELL = 12
        const val PATCH_LOW = 0.15f
        const val PATCH_HIGH = 0.85f
        /** Densest drifts grow this many times the base chance; the mean stays near 1. */
        const val PATCH_MAX = 2f
        const val PEBBLE_BLEACH = 1.1f
        const val GRIT_BLEACH = 1.06f

        private val NORMALS = floatArrayOf(1f, 0f, 0f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, -1f)

        /**
         * By name, because a material is a palette entry and packs add their
         * own: the built-ins by their exact names, the rest by the words in them.
         */
        fun classify(m: Material): Kind {
            if (!m.opaque || m.emission > 0f || m.gloss >= 0.35f) return Kind.NONE
            val n = m.name.lowercase()
            fun has(vararg words: String) = words.any { it in n }
            return when {
                has("lane", "glass", "lamp", "metal", "water", "lava", "paint_") -> Kind.NONE
                has("leaves", "leaf", "palm", "canopy", "foliage", "moss", "fern", "bush") -> Kind.FOLIAGE
                has("grass_weave") -> Kind.STRAW
                has("grass", "meadow", "turf", "savanna", "reed_bed") -> Kind.TUFT
                has("thatch", "straw", "reed") -> Kind.STRAW
                has("gravel", "scree", "pebble", "cobble", "boulder", "talus", "rubble") -> Kind.PEBBLE
                has("stone", "rock", "granite", "basalt", "gneiss", "schist", "quartz", "laterite", "ironstone",
                    "limestone", "sandstone", "shale", "slate", "obsidian", "tuff", "dolerite", "marble", "kopje", "inselberg") -> Kind.PEBBLE
                has("sand", "dirt", "soil", "earth", "clay", "mud", "loam", "dust", "ash", "silt", "snow", "regolith", "dune", "salt", "crust") -> Kind.GRIT
                has("fruit", "flower", "bark", "timber", "pole", "door", "wood") -> Kind.WEAR
                else -> Kind.WEAR
            }
        }

        /** A deterministic hash of a place, a slot and a material. */
        fun hash(x: Int, y: Int, z: Int, slot: Int, material: Int): Int {
            var h = x * -0x61c88647 + y * 0x27d4eb2f + z * 0x165667b1 + slot * 0x3c6ef372 + material * 0x7f4a7c15
            h = (h xor (h ushr 15)) * -0x7a143589
            h = (h xor (h ushr 13)) * -0x3d4d51cb
            return h xor (h ushr 16)
        }

        private fun mix(h: Int): Int {
            var v = h * -0x61c88647 + 0x6a09e667
            v = (v xor (v ushr 16)) * -0x7a143589
            return v xor (v ushr 13)
        }

        /** The low 16 bits of [h] as 0..1. */
        private fun unit(h: Int): Float = (h and 0xFFFF) / 65536f
    }
}
