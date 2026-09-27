package com.stratum.engine.worldgen

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.CarverRule
import com.stratum.core.domain.world.Chunk
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Cuts air out of one chunk. */
fun interface Carver {
    fun carve(chunk: ChunkContext)
}

/** Builds a carver from its rule. [slot] is its place in the list, so two carvers of one kind never cut the same holes. */
fun interface CarverFactory {
    fun create(rule: CarverRule, services: WorldServices, slot: Int): Carver
}

/**
 * The carvers this build knows, by kind.
 *
 * Open like the pass registry: register a kind and a pack can name it in its
 * `carvers` list.
 */
class CarverRegistry {
    private val factories = LinkedHashMap<String, CarverFactory>()

    val kinds: Set<String> get() = factories.keys

    fun register(kind: String, factory: CarverFactory): CarverRegistry {
        factories[kind] = factory
        return this
    }

    fun create(rule: CarverRule, services: WorldServices, slot: Int): Carver {
        val factory = factories[rule.kind]
            ?: throw IllegalArgumentException("No carver of kind '${rule.kind}'. Known carvers: ${factories.keys.joinToString()}")
        return factory.create(rule, services, slot)
    }

    companion object {
        fun standard(): CarverRegistry = CarverRegistry()
            .register(CAVES) { rule, services, slot -> NoiseCarver(rule, services, slot, tapered = false) }
            .register(CAVERNS) { rule, services, slot -> NoiseCarver(rule, services, slot, tapered = true) }
            .register(TUNNELS) { rule, services, slot -> WormCarver(rule, services, slot, WormCarver.Profile.TUNNEL) }
            .register(RAVINES) { rule, services, slot -> WormCarver(rule, services, slot, WormCarver.Profile.RAVINE) }

        const val CAVES = "caves"
        const val CAVERNS = "caverns"
        const val TUNNELS = "tunnels"
        const val RAVINES = "ravines"
    }
}

/**
 * Caves as a threshold of 3D noise: where the noise is above
 * [CarverRule.amount], the rock is gone.
 *
 * The noise is sampled on a coarse lattice -- every four blocks across, every
 * two up -- and interpolated between, the way Minecraft evaluates its density
 * field. Cave walls are smooth at that scale anyway, and it cuts the noise
 * work per chunk by more than twenty times, which is most of what a chunk
 * costs.
 *
 * A tapered carver fades out towards the edges of its band, so a cavern
 * layer ends in a floor and a ceiling rather than a flat cut: that is what
 * lets several cavern layers stack with rock between them, Terraria-fashion.
 */
internal class NoiseCarver(private val rule: CarverRule, services: WorldServices, slot: Int, private val tapered: Boolean) : Carver {

    private val noise = services.noise(CARVER_SALT + slot * 7_919L)
    private val frequency = rule.options["scale"]?.toFloatOrNull() ?: (BASE_FREQUENCY / rule.size)
    private val verticalFrequency = frequency * (rule.options["stretch"]?.toFloatOrNull() ?: 1.8f)
    private val minZ = rule.minZ.coerceAtLeast(1)
    private val maxZ = rule.maxZ.coerceAtMost(Chunk.HEIGHT - 1)
    private val levels = (maxZ - minZ) / STEP_Z + 2

    override fun carve(chunk: ChunkContext) {
        val lattice = FloatArray(LATTICE * LATTICE * levels)
        for (k in 0 until levels) for (j in 0 until LATTICE) for (i in 0 until LATTICE) {
            val x = (chunk.originX + i * STEP_XY) * frequency
            val y = (chunk.originY + j * STEP_XY) * frequency
            val z = (minZ + k * STEP_Z) * verticalFrequency
            lattice[(k * LATTICE + j) * LATTICE + i] = noise.at(x, y, z) * 0.67f + noise.at(x * 2f + 31f, y * 2f - 17f, z * 2f) * 0.33f
        }
        val band = (maxZ - minZ).coerceAtLeast(1).toFloat()
        for (ly in 0 until Chunk.SIZE) for (lx in 0 until Chunk.SIZE) {
            val top = minOf(maxZ, chunk.surfaceAt(lx, ly) - rule.headroom)
            if (top < minZ) continue
            val i = lx / STEP_XY
            val j = ly / STEP_XY
            val fx = (lx % STEP_XY) / STEP_XY.toFloat()
            val fy = (ly % STEP_XY) / STEP_XY.toFloat()
            for (z in minZ..top) {
                val k = (z - minZ) / STEP_Z
                val fz = ((z - minZ) % STEP_Z) / STEP_Z.toFloat()
                var value = trilinear(lattice, i, j, k, fx, fy, fz)
                if (tapered) {
                    // Rises from nothing at the band's edges to full strength a third of the way in.
                    val edge = minOf(z - minZ, maxZ - z) / band * 3f
                    value -= (1f - edge.coerceIn(0f, 1f)) * TAPER
                }
                if (value > rule.amount && chunk.chunk.blockAt(lx, ly, z) != chunk.blocks.bedrock) {
                    chunk.chunk.setBlock(lx, ly, z, BlockRegistry.AIR_INDEX)
                }
            }
        }
    }

    private fun trilinear(l: FloatArray, i: Int, j: Int, k: Int, fx: Float, fy: Float, fz: Float): Float {
        fun at(di: Int, dj: Int, dk: Int) = l[((k + dk) * LATTICE + (j + dj)) * LATTICE + (i + di)]
        val x00 = at(0, 0, 0) + (at(1, 0, 0) - at(0, 0, 0)) * fx
        val x10 = at(0, 1, 0) + (at(1, 1, 0) - at(0, 1, 0)) * fx
        val x01 = at(0, 0, 1) + (at(1, 0, 1) - at(0, 0, 1)) * fx
        val x11 = at(0, 1, 1) + (at(1, 1, 1) - at(0, 1, 1)) * fx
        val y0 = x00 + (x10 - x00) * fy
        val y1 = x01 + (x11 - x01) * fy
        return y0 + (y1 - y0) * fz
    }

    private companion object {
        const val CARVER_SALT = 313_000L
        const val BASE_FREQUENCY = 0.24f
        const val STEP_XY = 4
        const val STEP_Z = 2
        const val LATTICE = Chunk.SIZE / STEP_XY + 1
        const val TAPER = 0.5f
    }
}

/**
 * Tunnels and ravines as worms: a path that wanders from a seeded start,
 * carving a sphere -- or for a ravine, a tall narrow slot -- at every step.
 *
 * A worm belongs to the region it starts in, and its whole path is a
 * function of the seed and that region. A chunk asks every region near
 * enough for a worm to reach it, and carves only the steps that fall inside
 * itself, so a tunnel crossing four chunks is cut identically whichever of
 * them is generated first.
 *
 * [CarverRule.amount] is worms per region, [CarverRule.size] the radius.
 * Options: `length` in steps, `depth` (a ravine's half height), and `open`,
 * which lets a worm break the surface -- ravines do by default, tunnels not.
 */
internal class WormCarver(private val rule: CarverRule, private val services: WorldServices, slot: Int, private val profile: Profile) : Carver {

    enum class Profile { TUNNEL, RAVINE }

    private val salt = WORM_SALT + slot * 104_729L
    private val length = rule.options["length"]?.toIntOrNull() ?: if (profile == Profile.RAVINE) 48 else 90
    private val halfHeight = rule.options["depth"]?.toFloatOrNull() ?: if (profile == Profile.RAVINE) 7f else rule.size
    private val open = rule.options["open"]?.toBooleanStrictOrNull() ?: (profile == Profile.RAVINE)
    private val reach = Math.floorDiv(length + rule.size.toInt() + REGION, REGION)

    /** Worm paths by region: x, y, z per step. Bounded, since they are cheap to rebuild. */
    private val paths = ConcurrentHashMap<Long, List<FloatArray>>()

    override fun carve(chunk: ChunkContext) {
        val rx0 = Math.floorDiv(chunk.originX, REGION)
        val ry0 = Math.floorDiv(chunk.originY, REGION)
        for (ry in ry0 - reach..ry0 + reach) for (rx in rx0 - reach..rx0 + reach) {
            wormsIn(rx, ry).forEach { carveWorm(chunk, it) }
        }
    }

    private fun wormsIn(rx: Int, ry: Int): List<FloatArray> {
        val key = (rx.toLong() shl 32) xor (ry.toLong() and 0xFFFFFFFFL)
        paths[key]?.let { return it }
        if (paths.size > CACHE) paths.clear()
        return plan(rx, ry).also { paths[key] = it }
    }

    private fun plan(rx: Int, ry: Int): List<FloatArray> {
        val random = Random(services.seed * 31 + salt + rx * 73_856_093L + ry * 19_349_663L)
        val whole = rule.amount.toInt()
        val count = whole + if (random.nextFloat() < rule.amount - whole) 1 else 0
        return List(count) {
            var x = rx * REGION + random.nextFloat() * REGION
            var y = ry * REGION + random.nextFloat() * REGION
            var z = rule.minZ + random.nextFloat() * (rule.maxZ - rule.minZ)
            var yaw = random.nextFloat() * TAU
            var pitch = 0f
            val path = FloatArray(length * 3)
            for (step in 0 until length) {
                path[step * 3] = x
                path[step * 3 + 1] = y
                path[step * 3 + 2] = z
                yaw += (random.nextFloat() - 0.5f) * 0.6f
                pitch = (pitch * 0.7f + (random.nextFloat() - 0.5f) * 0.4f).coerceIn(-0.5f, 0.5f)
                x += cos(yaw)
                y += sin(yaw)
                z = (z + pitch).coerceIn(rule.minZ.toFloat(), rule.maxZ.toFloat())
            }
            path
        }
    }

    private fun carveWorm(chunk: ChunkContext, path: FloatArray) {
        val r = rule.size
        val minX = chunk.originX - r - 1
        val maxX = chunk.originX + Chunk.SIZE + r + 1
        val minY = chunk.originY - r - 1
        val maxY = chunk.originY + Chunk.SIZE + r + 1
        val ri = kotlin.math.ceil(r).toInt()
        val hi = kotlin.math.ceil(halfHeight).toInt()
        for (step in 0 until path.size / 3) {
            val px = path[step * 3]
            val py = path[step * 3 + 1]
            if (px < minX || px > maxX || py < minY || py > maxY) continue
            val pz = path[step * 3 + 2]
            val cx = kotlin.math.floor(px).toInt()
            val cy = kotlin.math.floor(py).toInt()
            val cz = kotlin.math.floor(pz).toInt()
            for (dy in -ri..ri) for (dx in -ri..ri) {
                val wx = cx + dx
                val wy = cy + dy
                if (!chunk.containsWorld(wx, wy)) continue
                val horizontal = (dx * dx + dy * dy) / (r * r)
                if (horizontal > 1f) continue
                val lx = wx - chunk.originX
                val ly = wy - chunk.originY
                val surface = chunk.surfaceAt(lx, ly)
                val top = if (open) Chunk.HEIGHT - 1 else surface - rule.headroom
                var carvedTop = false
                for (dz in -hi..hi) {
                    val z = cz + dz
                    if (z < rule.minZ.coerceAtLeast(1) || z > top) continue
                    val vertical = (dz * dz) / (halfHeight * halfHeight)
                    if (horizontal + vertical > 1f) continue
                    if (chunk.chunk.blockAt(lx, ly, z) == chunk.blocks.bedrock) continue
                    chunk.chunk.setBlock(lx, ly, z, BlockRegistry.AIR_INDEX)
                    if (z == surface) carvedTop = true
                }
                if (carvedTop) chunk.lowerSurface(lx, ly)
            }
        }
    }

    private companion object {
        const val REGION = 64
        const val WORM_SALT = 577_000L
        const val TAU = (Math.PI * 2).toFloat()
        const val CACHE = 1_024
    }
}
