package com.stratum.engine.microvoxel.gen

import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.MicroChunkPos
import java.util.concurrent.ConcurrentHashMap

/**
 * The microvoxel generator: an ordered list of stages run over each chunk.
 *
 * ## The contract every stage keeps
 *
 * 1. **Chunk-local and order-free.** What a stage writes into a chunk is a
 *    function of the seed and world coordinates only. A feature crossing a
 *    border (a tree canopy, a building, a road) is derived from the cell or
 *    region that owns it; every chunk it touches derives the same thing and
 *    rasterises its own part. So chunks can be generated in any order, on any
 *    thread, discarded and regenerated identically -- the property infinite
 *    roaming and diff-only saves both rest on.
 * 2. **Two phases.** At world creation each stage's factory runs in pipeline
 *    order and may *publish* services to [WorldFields] (a height field, a city
 *    planner). At generation time every stage reads the *final* services, so a
 *    later stage can reshape an earlier one: the city planner flattens the
 *    terrain the terrain stage then fills.
 * 3. **Cheap per voxel.** A stage should touch only the voxels it owns --
 *    bounding boxes, column caches, brick fills -- never all 262 144 voxels of
 *    a chunk "just in case". [com.stratum.engine.microvoxel.MicroBudgetTest]
 *    keeps generation of a surface chunk inside a phone's frame budget.
 *
 * ## Units
 *
 * One block of the block world is [MICRO_PER_BLOCK] microvoxels a side. All
 * coordinates here are in microvoxels, x/y horizontal, z up.
 */
class MicroGenerator internal constructor(
    val stageIds: List<String>,
    private val stages: List<MicroStage>,
    val fields: WorldFields,
) {
    val palette: MaterialPalette get() = fields.palette
    val seed: Long get() = fields.seed

    fun generate(pos: MicroChunkPos): MicroChunk {
        val chunk = MicroChunk(pos)
        val ctx = MicroGenContext(chunk, fields)
        for (stage in stages) stage.apply(ctx)
        chunk.compact()
        return chunk
    }

    companion object {
        const val MICRO_PER_BLOCK = 4
    }
}

/** One stage of generation. See [MicroGenerator] for the rules it keeps. */
fun interface MicroStage {
    fun apply(ctx: MicroGenContext)
}

/** Builds a stage when a world is created; may publish services for later stages. */
fun interface MicroStageFactory {
    fun create(setup: StageSetup): MicroStage
}

/** A stage in pack data: an id and string options, so JSON and sliders can describe a world. */
data class StageSpec(val id: String, val options: Map<String, String> = emptyMap())

class StageSetup(val fields: WorldFields, val options: StageOptions) {
    val seed: Long get() = fields.seed
    val palette: MaterialPalette get() = fields.palette
}

/** Stage options read with a default and a loud error, never a silent fallback. */
class StageOptions(private val stageId: String, private val values: Map<String, String>) {
    fun string(key: String, default: String) = values[key] ?: default
    fun int(key: String, default: Int) = values[key]?.let {
        it.trim().toIntOrNull() ?: throw IllegalArgumentException("Stage '$stageId' option '$key' is not a whole number: '$it'")
    } ?: default
    fun float(key: String, default: Float) = values[key]?.let {
        it.trim().toFloatOrNull() ?: throw IllegalArgumentException("Stage '$stageId' option '$key' is not a number: '$it'")
    } ?: default
    fun boolean(key: String, default: Boolean) = values[key]?.let {
        it.trim().lowercase().toBooleanStrictOrNull() ?: throw IllegalArgumentException("Stage '$stageId' option '$key' is not true or false: '$it'")
    } ?: default
}

/** A typed name for a service in [WorldFields]. */
class FieldKey<T : Any>(val name: String) {
    override fun toString() = name
}

/**
 * Services stages publish for each other: the terrain's height function, the
 * city planner, the climate. Typed keys instead of fields on a class, so a
 * third-party stage can publish a service nobody here has heard of and a
 * later third-party stage can read it.
 */
class WorldFields(val seed: Long, val palette: MaterialPalette) {
    private val services = ConcurrentHashMap<FieldKey<*>, Any>()

    fun <T : Any> publish(key: FieldKey<T>, value: T) { services[key] = value }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: FieldKey<T>): T? = services[key] as T?

    fun <T : Any> require(key: FieldKey<T>): T = get(key) ?: throw IllegalStateException(
        "No stage published '$key'. Put the stage that provides it earlier in the pipeline.",
    )
}

/** What a stage sees while filling one chunk. */
class MicroGenContext(val chunk: MicroChunk, val fields: WorldFields) {
    val pos: MicroChunkPos get() = chunk.pos
    val x0: Int = chunk.pos.originX
    val y0: Int = chunk.pos.originY
    val z0: Int = chunk.pos.originZ
    val x1: Int = x0 + MicroChunk.SIZE - 1
    val y1: Int = y0 + MicroChunk.SIZE - 1
    val z1: Int = z0 + MicroChunk.SIZE - 1
    val palette: MaterialPalette get() = fields.palette

    /** Scratch shared by stages within one chunk, e.g. a column cache. */
    val scratch = HashMap<String, Any>()

    fun get(wx: Int, wy: Int, wz: Int): Short = chunk[wx - x0, wy - y0, wz - z0]

    fun set(wx: Int, wy: Int, wz: Int, material: Short) { chunk.set(wx - x0, wy - y0, wz - z0, material) }

    /** Writes only where the voxel is currently air: decoration never overwrites structure. */
    fun place(wx: Int, wy: Int, wz: Int, material: Short) {
        if (wz < z0 || wz > z1 || wx < x0 || wx > x1 || wy < y0 || wy > y1) return
        if (get(wx, wy, wz) == MaterialPalette.AIR) set(wx, wy, wz, material)
    }

    fun fill(ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int, material: Short) =
        chunk.fill(ax - x0, ay - y0, az - z0, bx - x0, by - y0, bz - z0, material)

    fun overlaps(ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int): Boolean =
        ax <= x1 && bx >= x0 && ay <= y1 && by >= y0 && az <= z1 && bz >= z0

    inline fun <reified T : Any> cached(key: String, build: () -> T): T =
        (scratch[key] as? T) ?: build().also { scratch[key] = it }
}

/** Stages by id. Open: register a factory and any pipeline naming that id gets it. */
class StageRegistry {
    private val factories = LinkedHashMap<String, MicroStageFactory>()
    val ids: Set<String> get() = factories.keys

    fun register(id: String, factory: MicroStageFactory): StageRegistry = apply { factories[id] = factory }

    fun copy(): StageRegistry = StageRegistry().also { it.factories.putAll(factories) }

    fun build(seed: Long, specs: List<StageSpec>, palette: MaterialPalette = MaterialPalette.standard()): MicroGenerator {
        require(specs.isNotEmpty()) { "A microvoxel pipeline needs at least one stage" }
        val fields = WorldFields(seed, palette)
        val stages = specs.map { spec ->
            val factory = factories[spec.id] ?: throw IllegalArgumentException(
                "No microvoxel stage named '${spec.id}'. Known stages: ${factories.keys.joinToString()}",
            )
            factory.create(StageSetup(fields, StageOptions(spec.id, spec.options)))
        }
        return MicroGenerator(specs.map { it.id }, stages, fields)
    }
}

/**
 * Per-column data cached across the vertical chunks of one column. Bounded,
 * because an infinite world would otherwise remember every column it ever saw.
 */
class ColumnCache<T : Any>(private val capacity: Int = 256) {
    private val map = ConcurrentHashMap<Long, T>()
    fun get(cx: Int, cy: Int, build: () -> T): T {
        val key = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
        map[key]?.let { return it }
        if (map.size > capacity) map.clear()
        return build().also { map[key] = it }
    }
}
