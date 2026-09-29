package com.stratum.engine.microvoxel.geo

import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.gen.Hash
import com.stratum.engine.microvoxel.gen.Noise
import com.stratum.engine.microvoxel.gen.StageOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One named step in making a province, with its settings as strings -- the
 * same shape as a stage spec, so a pack's JSON can list a province's steps
 * as easily as a world's stages. See [GeoProcesses] for the ids.
 */
data class Step(val id: String, val options: Map<String, String> = emptyMap())

/** A [Step] from Kotlin: `step("erosion", "strength" to 1.2)`. */
fun step(id: String, vararg options: Pair<String, Any>): Step = Step(id, options.associate { (k, v) -> k to v.toString() })

/**
 * The world-wide dials a player turns in the World panel (see the terrain
 * stage's options), each 0..2 with 1 as shipped. A province says *whether*
 * and *how much* a process runs there; the dial scales it everywhere.
 */
data class GeoDials(
    /** Valleys cut by running water, and the fans of what it carries out of the hills. */
    val erosion: Float = 1f,
    /** How much of the drainage becomes rivers with water in them. 0 is none. */
    val rivers: Float = 1f,
    /** The height of dunes and how many barchans roam the sand seas. */
    val dunes: Float = 1f,
    /** How much rubble collects at the foot of cliffs. */
    val scree: Float = 1f,
    /** How many dykes, veins, ore lenses and nodules cut the rock. */
    val rock: Float = 1f,
)

/** What a process is built with: the world's seed, its materials and the dials. */
class ProcessContext(val seed: Long, val palette: MaterialPalette, val dials: GeoDials = GeoDials()) {
    fun id(name: String): Short = palette.id(name)
}

/**
 * A piece of geology. A province is an ordered list of them (see
 * [Province.processes]), and there are three kinds, run at three moments:
 *
 * - [ReliefProcess]: shapes the land, once per lattice node -- a landform, or
 *   a modifier over what the steps before it made (barchans on a reg,
 *   sinkholes in a limestone plateau, fault scarps across a rift).
 * - [SimShare]: the province's part in a process the atlas *simulates* over
 *   the whole land on a coarse lattice -- erosion and deposition, rivers,
 *   scree -- because those need neighbours, not just a point.
 * - [RockProcess]: rewrites the rock of one column as it is laid down --
 *   dykes, veins, ore lenses, concretions, cross-bedding, unconformities,
 *   columnar joints.
 */
interface GeoProcess

/**
 * Relief in microvoxels above the province's floor at a column, given the
 * relief the earlier steps made ([below]; 0 for the first). Pure, and cheap:
 * it runs for every lattice node of every province region.
 */
fun interface ReliefProcess : GeoProcess {
    fun relief(x: Float, y: Float, grain: Float, below: Float, n: Noise): Float
}

/**
 * How strongly a province takes part in one of the simulated processes (see
 * [Sims]). The process itself lives in the atlas and runs across province
 * borders, as water does; the province only sets its rates.
 */
class SimShare(val sim: String, val options: StageOptions) : GeoProcess

/** The simulated processes a [SimShare] can name. */
object Sims {
    /** `strength` (cutting), `deposit` (fans): stream-power erosion and deposition along the drainage. */
    const val EROSION = "erosion"
    /** `rain`: how much water the province sheds; `wet` below which its rivers are dry wadis. */
    const val RIVERS = "rivers"
    /** `amount`: rubble aprons at the foot of cliffs. */
    const val SCREE = "scree"
}

/**
 * Rock in one column, bottom up, while it is being laid down. A
 * [RockProcess] rewrites it in place; the weathering profile and the surface
 * go on afterwards, over whatever the processes left.
 */
class RockColumn {
    var x = 0; var y = 0
    /** The ground's top voxel. */
    var top = 0
    /** The lowest voxel held, and how many are held upward from it. */
    var bottom = 0; var count = 0
    /** The province's floor and the fold offset here: bedding counts from them. */
    var floor = 0f; var fold = 0f
    /** Voxels under [top] that soil will cover; rock processes work below them. */
    var soil = 0
    var mat = ShortArray(512)

    /** The highest voxel a rock process may change. */
    val rockTop: Int get() = min(top - soil, bottom + count - 1)

    fun reset(x: Int, y: Int, top: Int, bottom: Int, count: Int, floor: Float, fold: Float, soil: Int) {
        this.x = x; this.y = y; this.top = top; this.bottom = bottom; this.count = count
        this.floor = floor; this.fold = fold; this.soil = soil
        if (mat.size < count) mat = ShortArray(count * 2)
    }

    operator fun get(z: Int): Short = mat[z - bottom]

    /** Rock from [z0] to [z1] (inclusive), clipped to what a rock process may change. */
    fun fill(z0: Int, z1: Int, m: Short) {
        val a = max(z0, bottom); val b = min(z1, rockTop)
        for (z in a..b) mat[z - bottom] = m
    }

    /** Like [fill], but only over rock that [host] accepts: nodules grow in mudstone, not in the dyke beside it. */
    inline fun fillWhere(z0: Int, z1: Int, m: Short, host: (Short) -> Boolean) {
        val a = max(z0, bottom); val b = min(z1, rockTop)
        for (z in a..b) if (host(mat[z - bottom])) mat[z - bottom] = m
    }
}

/** Changes the rock of one column; see [RockColumn]. Must depend on world coordinates only. */
fun interface RockProcess : GeoProcess {
    fun apply(c: RockColumn)
}

/** Builds a process from a [Step]'s options. */
fun interface GeoProcessFactory {
    fun create(c: ProcessContext, o: StageOptions): GeoProcess
}

/**
 * Every geological process by id. Open, like the stage registry: a pack
 * registers `mypack:mesas` and any province listing that id gets it.
 *
 * Built in:
 *
 * - **Landforms** (relief; option `mode` = replace, max, add or min over what
 *   came before, and `weight`): laterite_plateau, inselbergs,
 *   rainforest_basin, forest_hills, sahel_plain, rift, traps, volcanic_necks,
 *   sandstone_escarpment, erg (`amplitude`, `wavelength`), reg_hamada,
 *   salt_pan, kalahari, namib, karoo, drakensberg, fold_belt (`wavelength`),
 *   tsingy, coral_coast, delta, canyon, dune_cordon, montane_plateau, shield.
 * - **Modifiers** (relief): barchans, sinkholes, faults.
 * - **Simulated** ([SimShare]): erosion, rivers, scree.
 * - **Rock**: dykes, veins, ore_lenses, concretions, cross_bedding,
 *   unconformity, columnar_joints.
 */
object GeoProcesses {
    private val factories = ConcurrentHashMap<String, GeoProcessFactory>()
    private val order = CopyOnWriteArrayList<String>()

    /** Every registered id, built-ins first. */
    val ids: List<String> get() = order.toList()

    fun register(id: String, factory: GeoProcessFactory): GeoProcesses = apply {
        if (factories.put(id, factory) == null) order += id
    }

    fun create(step: Step, c: ProcessContext): GeoProcess {
        val factory = factories[step.id] ?: throw IllegalArgumentException(
            "No geological process named '${step.id}'. Known: ${order.joinToString()}",
        )
        return factory.create(c, StageOptions("geology step '${step.id}'", step.options))
    }

    /** A landform as a relief step, combined with what came before as its `mode` says. */
    private fun landform(make: (ProcessContext, StageOptions) -> Landform) = GeoProcessFactory { c, o ->
        val shape = make(c, o)
        val weight = o.float("weight", 1f)
        when (val mode = o.string("mode", "replace")) {
            "replace" -> ReliefProcess { x, y, g, _, n -> shape.relief(x, y, g, n) * weight }
            "max" -> ReliefProcess { x, y, g, below, n -> max(below, shape.relief(x, y, g, n) * weight) }
            "add" -> ReliefProcess { x, y, g, below, n -> below + shape.relief(x, y, g, n) * weight }
            "min" -> ReliefProcess { x, y, g, below, n -> min(below, shape.relief(x, y, g, n) * weight) }
            else -> throw IllegalArgumentException("A landform's mode is replace, max, add or min, not '$mode'")
        }
    }

    private fun sim(id: String) = GeoProcessFactory { _, o -> SimShare(id, o) }

    init {
        register("laterite_plateau", landform { c, _ -> Landforms.lateritePlateau(c.seed) })
        register("inselbergs", landform { c, _ -> Landforms.inselbergs(c.seed) })
        register("rainforest_basin", landform { c, _ -> Landforms.rainforestBasin(c.seed) })
        register("forest_hills", landform { c, _ -> Landforms.forestHills(c.seed) })
        register("sahel_plain", landform { c, _ -> Landforms.sahelPlain(c.seed) })
        register("rift", landform { c, _ -> Landforms.rift(c.seed) })
        register("traps", landform { c, _ -> Landforms.traps(c.seed) })
        register("volcanic_necks", landform { c, _ -> Landforms.volcanicNecks(c.seed) })
        register("sandstone_escarpment", landform { c, _ -> Landforms.sandstoneEscarpment(c.seed) })
        register("erg", landform { c, o -> Landforms.erg(c.seed, o.float("amplitude", 36f) * c.dials.dunes, o.float("wavelength", 130f)) })
        register("reg_hamada", landform { c, _ -> Landforms.regHamada(c.seed) })
        register("salt_pan", landform { c, _ -> Landforms.saltPan(c.seed) })
        register("kalahari", landform { c, _ -> Landforms.kalahari(c.seed, c.dials.dunes) })
        register("namib", landform { c, _ -> Landforms.namib(c.seed, c.dials.dunes) })
        register("karoo", landform { c, _ -> Landforms.karoo(c.seed) })
        register("drakensberg", landform { c, _ -> Landforms.drakensberg(c.seed) })
        register("fold_belt", landform { c, o -> Landforms.foldBelt(c.seed, o.float("wavelength", 360f)) })
        register("tsingy", landform { c, _ -> Landforms.tsingy(c.seed) })
        register("coral_coast", landform { c, _ -> Landforms.coralCoast(c.seed) })
        register("delta", landform { c, _ -> Landforms.delta(c.seed) })
        register("canyon", landform { c, _ -> Landforms.canyon(c.seed) })
        register("dune_cordon", landform { c, _ -> Landforms.duneCordon(c.seed, c.dials.dunes) })
        register("montane_plateau", landform { c, _ -> Landforms.montanePlateau(c.seed) })
        register("shield", landform { c, _ -> Landforms.shield(c.seed) })

        register("barchans", GeoProcessFactory { c, o -> Modifiers.barchans(c.seed, o.float("height", 12f) * c.dials.dunes, o.float("spacing", 170f), o.float("chance", 0.55f)) })
        register("sinkholes", GeoProcessFactory { c, o -> Modifiers.sinkholes(c.seed, o.float("depth", 10f), o.float("spacing", 110f), o.float("chance", 0.45f)) })
        register("faults", GeoProcessFactory { c, o -> Modifiers.faults(c.seed, o.float("throw", 10f), o.float("spacing", 900f)) })

        register(Sims.EROSION, sim(Sims.EROSION))
        register(Sims.RIVERS, sim(Sims.RIVERS))
        register(Sims.SCREE, sim(Sims.SCREE))

        register("dykes", GeoProcessFactory { c, o -> RockProcesses.dykes(c, o) })
        register("veins", GeoProcessFactory { c, o -> RockProcesses.veins(c, o) })
        register("ore_lenses", GeoProcessFactory { c, o -> RockProcesses.oreLenses(c, o) })
        register("concretions", GeoProcessFactory { c, o -> RockProcesses.concretions(c, o) })
        register("cross_bedding", GeoProcessFactory { c, o -> RockProcesses.crossBedding(c, o) })
        register("unconformity", GeoProcessFactory { c, o -> RockProcesses.unconformity(c, o) })
        register("columnar_joints", GeoProcessFactory { c, o -> RockProcesses.columnarJoints(c, o) })
    }
}

/** Relief steps that work over the land the steps before them made. */
object Modifiers {

    /**
     * Barchans: crescent dunes marching downwind across a hard floor, horns
     * pointing the way the wind blows, a gentle windward back and a steep
     * slip face in the hollow between the horns. They live where sand is
     * scarce -- the corridors between seif dunes, the gravel plains at an
     * erg's edge -- which is why they sit *on* the land rather than replace
     * it: where the dunes before them already stand taller, they vanish.
     * The Western Desert's barchan belts near Kharga, the Namib's coastal
     * barchans at Lüderitz, southern Morocco's around Tarfaya.
     */
    fun barchans(seed: Long, height: Float, spacing: Float, chance: Float) = ReliefProcess { x, y, grain, below, _ ->
        if (height <= 0f) return@ReliefProcess below
        val ca = cos(grain); val sa = sin(grain)
        val ci = floor(x / spacing).toInt(); val cj = floor(y / spacing).toInt()
        var best = 0f
        for (oj in -1..1) for (oi in -1..1) {
            val i = ci + oi; val j = cj + oj
            if (Hash.unit(seed, i, j, 0, 911) >= chance) continue
            val cx = (i + 0.25f + 0.5f * Hash.unit(seed, i, j, 0, 912)) * spacing
            val cy = (j + 0.25f + 0.5f * Hash.unit(seed, i, j, 0, 913)) * spacing
            val h = height * (0.55f + 0.45f * Hash.unit(seed, i, j, 0, 914))
            val dx = x - cx; val dy = y - cy
            // a: downwind, b: across the wind.
            val a = dx * ca + dy * sa; val b = -dx * sa + dy * ca
            best = max(best, barchan(a, b, h))
        }
        max(below, best)
    }

    /** One barchan of height [h] at the origin, wind blowing towards +a. */
    internal fun barchan(a: Float, b: Float, h: Float): Float {
        val halfWidth = 5f * h
        val tb = b / halfWidth
        if (tb * tb >= 1f) return 0f
        val hb = h * (1f - tb * tb).pow(0.7f)
        // The brink bends downwind towards the horns.
        val crest = 3f * h * tb * tb - 0.5f * h
        val back = 4f * hb + 1f
        return if (a < crest) {
            val t = 1f - (crest - a) / back
            if (t <= 0f) 0f else hb * t * t * (3f - 2f * t)
        } else {
            (hb - (a - crest) * 0.8f).coerceAtLeast(0f)
        }
    }

    /**
     * Dolines: round, steep-sided hollows where limestone dissolved and the
     * roof fell in -- the sinkholes pitting Bemaraha's plateau and the
     * Mahafaly's, the dolines of the Middle Atlas.
     */
    fun sinkholes(seed: Long, depth: Float, spacing: Float, chance: Float) = ReliefProcess { x, y, _, below, _ ->
        val pit = Shape.features(seed, x, y, spacing, chance, 921) { t, size, _ -> depth * (0.5f + size) * (1f - t * t * t * t) }
        below - pit
    }

    /**
     * Normal faults along the land's grain: the ground is broken into long
     * blocks, each dropped or lifted against its neighbours, with a sharp
     * scarp where they meet that shows the bedding in section. The stepped
     * shoulders of the Kenyan and Ethiopian rifts, the Fish River graben.
     */
    fun faults(seed: Long, throwHeight: Float, spacing: Float) = ReliefProcess { x, y, grain, below, n ->
        val u = Shape.across(x, y, grain) + n.fbm(Shape.along(x, y, grain) * 0.0012f, 7f, 2) * spacing * 0.25f
        val k = floor(u / spacing).toInt()
        fun line(i: Int) = (i + 0.2f + 0.6f * Hash.unit(seed, i, 0, 0, 931)) * spacing
        fun offset(i: Int) = (Hash.unit(seed, i, 0, 0, 932) - 0.5f) * 2f * throwHeight
        // Each block takes its own offset; across a fault line the offset steps sharply.
        // The nearer line is the one this point's blend is about.
        val mine = line(k)
        val first = if (u < mine) k - 1 else k
        val at = if (u < mine) mine else line(k + 1)
        val from = offset(first); val to = offset(first + 1)
        below + from + (to - from) * Shape.smooth(-2.5f, 2.5f, u - at)
    }
}

/** Rock steps: each works on one column at a time, with no noise per voxel. */
object RockProcesses {

    private fun materials(c: ProcessContext, list: String): Set<Short> =
        list.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(c::id).toSet()

    /** A lookup by material id, so a per-voxel host test is one array read. */
    private fun hostTable(c: ProcessContext, list: String): BooleanArray {
        val ids = materials(c, list)
        val table = BooleanArray((ids.maxOrNull()?.toInt() ?: 0) + 1)
        for (id in ids) table[id.toInt()] = true
        return table
    }

    private val SANDSTONES = listOf(R.SANDSTONE_RED, R.SANDSTONE_BUFF, R.SANDSTONE_PALE, R.NANKA_SAND).joinToString(",")
    private val MUDROCKS = listOf(R.MUDSTONE, R.SHALE, R.SANDSTONE_BUFF, R.SANDSTONE_RED, R.MOTTLED_CLAY, R.NANKA_SAND).joinToString(",")

    /**
     * Dykes: vertical sheets of dark rock that forced their way up through
     * everything as the continent stretched -- two families of straight,
     * narrow walls cutting every bed, seen in any cliff or cutting. The
     * Karoo's dolerite dykes, the Great Dyke of Zimbabwe, the dyke swarms
     * of the Ethiopian rift.
     */
    fun dykes(c: ProcessContext, o: StageOptions): RockProcess {
        val mat = c.id(o.string("material", R.DOLERITE))
        val spacing = o.float("spacing", 380f)
        val width = o.float("width", 3f)
        val chance = (o.float("chance", 0.5f) * c.dials.rock).coerceIn(0f, 1f)
        val length = o.float("length", 1400f)
        val seed = c.seed
        val a0 = Hash.unit(seed, 0, 0, 0, 941) * 3.1416f
        val angles = floatArrayOf(a0, a0 + 1.15f)
        val cs = FloatArray(2) { cos(angles[it]) }; val sn = FloatArray(2) { sin(angles[it]) }
        return RockProcess { col ->
            if (chance <= 0f) return@RockProcess
            for (f in 0 until 2) {
                val u = col.x * cs[f] + col.y * sn[f]
                val k = floor(u / spacing).toInt()
                val v = -col.x * sn[f] + col.y * cs[f]
                val seg = floor(v / length).toInt()
                if (Hash.unit(seed, k, seg, f, 942) >= chance) continue
                val at = (k + 0.2f + 0.6f * Hash.unit(seed, k, f, 0, 943)) * spacing
                val w = width * (0.7f + 0.6f * Hash.unit(seed, k, seg, f, 944))
                if (abs(u - at) < w * 0.5f) { col.fill(col.bottom, col.rockTop, mat); return@RockProcess }
            }
        }
    }

    /**
     * Mineral veins: thin, steep sheets of white quartz filling old cracks in
     * the basement, some of them rusty gold-bearing reef -- what prospectors
     * followed across the Ashanti belt and the Zimbabwe craton.
     */
    fun veins(c: ProcessContext, o: StageOptions): RockProcess {
        val mat = c.id(o.string("material", R.QUARTZ))
        val rich = c.id(o.string("rich", R.GOLD_REEF))
        val richShare = o.float("richShare", 0.15f)
        val spacing = o.float("spacing", 48f)
        val thickness = o.float("thickness", 1.3f)
        val chance = (o.float("chance", 0.35f) * c.dials.rock).coerceIn(0f, 1f)
        val seed = c.seed
        // Two families of planes, steeply dipping, each with its own strike.
        val nx = FloatArray(2); val ny = FloatArray(2); val nz = FloatArray(2)
        for (f in 0 until 2) {
            val strike = Hash.unit(seed, f, 0, 0, 951) * 6.2832f
            val dip = 0.95f + 0.45f * Hash.unit(seed, f, 0, 0, 952) // 55..80 degrees
            nx[f] = sin(dip) * cos(strike); ny[f] = sin(dip) * sin(strike); nz[f] = cos(dip)
        }
        return RockProcess { col ->
            if (chance <= 0f) return@RockProcess
            val zb = col.bottom; val zt = col.rockTop
            if (zt < zb) return@RockProcess
            for (f in 0 until 2) {
                val q = col.x * nx[f] + col.y * ny[f]
                val s0 = q + nz[f] * zb; val s1 = q + nz[f] * zt
                val patch = floor((col.x * ny[f] - col.y * nx[f]) / 260f).toInt()
                for (k in floor((min(s0, s1) - thickness) / spacing).toInt()..floor((max(s0, s1) + thickness) / spacing).toInt()) {
                    val roll = Hash.unit(seed, k, patch, f, 953)
                    if (roll >= chance) continue
                    val d = (k + 0.15f + 0.7f * Hash.unit(seed, k, f, 0, 954)) * spacing
                    // |q + nz z - d| < t/2, solved for z.
                    val za = (d - thickness * 0.5f - q) / nz[f]; val zc = (d + thickness * 0.5f - q) / nz[f]
                    col.fill(kotlin.math.ceil(min(za, zc)).toInt(), floor(max(za, zc)).toInt(), if (roll < chance * richShare) rich else mat)
                }
            }
        }
    }

    /**
     * Ore lenses: flattened pods of ore lying in the beds -- Guinea's
     * bauxite under its bowal, the Copperbelt's malachite, banded iron in the
     * greenstone belts. Rare, a few blocks across, found by digging or in a
     * gorge wall.
     */
    fun oreLenses(c: ProcessContext, o: StageOptions): RockProcess {
        val mat = c.id(o.string("material", R.MALACHITE))
        val cell = o.float("cell", 72f)
        val cz = o.float("layer", 24f)
        val chance = (o.float("chance", 0.14f) * c.dials.rock).coerceIn(0f, 1f)
        val seed = c.seed
        return RockProcess { col ->
            if (chance <= 0f) return@RockProcess
            val i = floor(col.x / cell).toInt(); val j = floor(col.y / cell).toInt()
            val px = (i + 0.3f + 0.4f * Hash.unit(seed, i, j, 0, 961)) * cell
            val py = (j + 0.3f + 0.4f * Hash.unit(seed, i, j, 0, 962)) * cell
            val a = Hash.unit(seed, i, j, 0, 963) * 3.1416f
            val dx = col.x - px; val dy = col.y - py
            val u = (dx * cos(a) + dy * sin(a)) / (cell * 0.28f)
            val v = (-dx * sin(a) + dy * cos(a)) / (cell * 0.12f)
            val e = u * u + v * v
            if (e >= 1f) return@RockProcess
            for (k in floor(col.bottom / cz).toInt()..floor(col.rockTop / cz).toInt()) {
                if (Hash.unit(seed, i, j, k, 964) >= chance) continue
                val zc = (k + 0.3f + 0.4f * Hash.unit(seed, i, j, k, 965)) * cz
                val half = (2f + 3f * Hash.unit(seed, i, j, k, 966)) * sqrt(1f - e)
                col.fill(kotlin.math.ceil(zc - half).toInt(), floor(zc + half).toInt(), mat)
            }
        }
    }

    /**
     * Concretions: hard round nodules grown inside soft beds -- ironstone
     * balls in the Nanka sands and the Karoo mudstones, lime nodules in
     * shale. Weathering leaves them standing proud of a cut face.
     */
    fun concretions(c: ProcessContext, o: StageOptions): RockProcess {
        val mat = c.id(o.string("material", R.IRONSTONE))
        val host = hostTable(c, o.string("hosts", MUDROCKS))
        val cell = o.float("cell", 10f)
        val chance = (o.float("chance", 0.3f) * c.dials.rock).coerceIn(0f, 1f)
        val seed = c.seed
        return RockProcess { col ->
            if (chance <= 0f) return@RockProcess
            val i = floor(col.x / cell).toInt(); val j = floor(col.y / cell).toInt()
            // One nodule column position per cell; only the columns near it do any more work.
            val px = (i + 0.3f + 0.4f * Hash.unit(seed, i, j, 0, 971)) * cell
            val py = (j + 0.3f + 0.4f * Hash.unit(seed, i, j, 0, 972)) * cell
            val dx = col.x + 0.5f - px; val dy = col.y + 0.5f - py
            val d2 = dx * dx + dy * dy
            if (d2 > 6.25f) return@RockProcess
            for (k in floor(col.bottom / cell).toInt()..floor(col.rockTop / cell).toInt()) {
                if (Hash.unit(seed, i, j, k, 973) >= chance) continue
                val r = 1.2f + 1.3f * Hash.unit(seed, i, j, k, 974)
                if (d2 >= r * r) continue
                val zc = (k + 0.5f) * cell
                val half = sqrt(r * r - d2)
                col.fillWhere(kotlin.math.ceil(zc - half).toInt(), floor(zc + half).toInt(), mat) { m -> m.toInt() < host.size && host[m.toInt()] }
            }
        }
    }

    /**
     * Cross-bedding: the tilted laminae of old dunes and river bars, set upon
     * set, each set leaning its own way and cut off flat by the next. The
     * Clarens sandstone under the Drakensberg's basalt is a fossil erg; the
     * Bandiagara and Tassili sandstones were laid by braided rivers.
     */
    fun crossBedding(c: ProcessContext, o: StageOptions): RockProcess {
        val host = hostTable(c, o.string("hosts", SANDSTONES))
        val with = c.id(o.string("with", R.SANDSTONE_PALE))
        val set = o.float("set", 7f)
        val lamina = o.float("lamina", 2.2f)
        val tan = o.float("dip", 0.6f)
        val seed = c.seed
        // Each set's lean, fixed per set index: a golden-angle walk with a seeded start, no hashing per voxel.
        val start = Hash.unit(seed, 0, 0, 0, 981) * 6.2832f
        val lx = FloatArray(64) { cos(start + it * 2.39996f) * tan }
        val ly = FloatArray(64) { sin(start + it * 2.39996f) * tan }
        return RockProcess { col ->
            val zt = col.rockTop
            if (zt < col.bottom) return@RockProcess
            val base = col.fold - col.floor
            for (z in col.bottom..zt) {
                val m = col.mat[z - col.bottom].toInt()
                if (m >= host.size || !host[m]) continue
                val bz = z + base
                val s = floor(bz / set).toInt()
                val k = Math.floorMod(s, 64)
                val phase = (bz - s * set) + col.x * lx[k] + col.y * ly[k]
                if (Math.floorMod(floor(phase / lamina).toInt(), 3) == 0) col.mat[z - col.bottom] = with
            }
        }
    }

    /**
     * An angular unconformity: flat-lying beds resting on the bevelled edges
     * of far older, steeply tilted rock, with a pebbly bed on the contact --
     * a gap of hundreds of millions of years in one line across a canyon
     * wall. The Fish River Canyon's Nama beds over Namaqua gneiss; the
     * Table Mountain sandstone over Malmesbury shale at Sea Point.
     */
    fun unconformity(c: ProcessContext, o: StageOptions): RockProcess {
        val depth = o.float("depth", 18f)
        val wave = o.float("relief", 4f)
        val tilt = o.float("tilt", 1.3f)
        val contact = c.id(o.string("contact", R.CONGLOMERATE))
        val bands = o.string("bands", "${R.GNEISS}*6,${R.SCHIST}*3,${R.GNEISS_DARK}*2,${R.QUARTZITE}*2").split(',').flatMap { entry ->
            val parts = entry.trim().split('*')
            val n = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
            List(n) { c.id(parts[0].trim()) }
        }.toShortArray()
        require(bands.isNotEmpty()) { "an unconformity needs older rock under it" }
        val strike = Hash.unit(c.seed, 0, 0, 0, 991) * 3.1416f
        val cs = cos(strike); val sn = sin(strike)
        return RockProcess { col ->
            val surface = col.floor - depth + wave * (sin(col.x * 0.021f + 1.3f) * sin(col.y * 0.017f - 0.4f) + 0.5f * sin((col.x + col.y) * 0.047f))
            val zu = floor(surface).toInt()
            if (zu < col.bottom) return@RockProcess
            val u = (col.x * cs + col.y * sn) * tilt
            val zt = min(zu, col.rockTop)
            for (z in col.bottom..zt) {
                col.mat[z - col.bottom] = if (z == zu) contact else bands[Math.floorMod(floor(z + u).toInt(), bands.size)]
            }
        }
    }

    /**
     * Columnar jointing: lava that cooled slowly cracked into tall
     * hexagonal columns, whose joints show as dark vertical seams down a
     * cliff -- the Blue Nile gorge's basalt, the phonolite of Rhumsiki, the
     * Drakensberg's lava wall.
     */
    fun columnarJoints(c: ProcessContext, o: StageOptions): RockProcess {
        val host = hostTable(c, o.string("hosts", listOf(R.BASALT, R.DOLERITE, R.PHONOLITE).joinToString(",")))
        val seam = c.id(o.string("seam", R.BASALT_WEATHERED))
        val size = o.float("size", 3.2f)
        return RockProcess { col ->
            // Axial hex coordinates, rounded to the nearest centre; the seam is the cells' rim.
            val qf = (0.57735f * col.x - 0.33333f * col.y) / size
            val rf = (0.66667f * col.y) / size
            val sf = -qf - rf
            var q = Math.round(qf).toFloat(); var r = Math.round(rf).toFloat(); val s = Math.round(sf).toFloat()
            val dq = abs(q - qf); val dr = abs(r - rf); val ds = abs(s - sf)
            if (dq > dr && dq > ds) q = -r - s else if (dr > ds) r = -q - s
            val d = max(abs(q - qf), max(abs(r - rf), abs(-q - r - sf)))
            if (d < 0.36f) return@RockProcess
            col.fillWhere(col.bottom, col.rockTop, seam) { m -> m.toInt() < host.size && host[m.toInt()] }
        }
    }
}
