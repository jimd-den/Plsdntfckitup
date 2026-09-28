package com.stratum.engine.microbridge

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.MarkedWorld
import com.stratum.core.domain.world.TerrainContext
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.TerrainGeneratorFactory
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldMarker
import com.stratum.core.domain.world.WorldMarkerKind
import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.MicroChunkPos
import com.stratum.engine.microvoxel.MicroTerrainSource
import com.stratum.engine.microvoxel.gen.CityPlanStage
import com.stratum.engine.microvoxel.gen.Fields
import com.stratum.engine.microvoxel.gen.Hash
import com.stratum.engine.microvoxel.gen.LotUse
import com.stratum.engine.microvoxel.gen.MicroGenerator
import com.stratum.engine.microvoxel.gen.MicroWorldgen
import com.stratum.engine.microvoxel.gen.Rect
import com.stratum.engine.microvoxel.gen.StageSpec
import com.stratum.engine.microvoxel.gen.TerrainStage
import kotlin.math.abs

/**
 * A microvoxel world, played on blocks.
 *
 * What the session builds when a recipe names [TerrainRecipe.MICROVOXEL].
 * The microvoxel pipeline generates the land at a quarter block; each block
 * of the game's world is then read off the 4 x 4 x 4 microvoxels it covers:
 *
 *  - solid when at least 3/8 of it is -- so half-block walls and roofs
 *    survive, and quarter-block kerbs, tufts and floor slabs (pure detail)
 *    do not clutter collision;
 *  - made of the block its *topmost* solid microvoxel maps to, so a hillside
 *    is turf and not the dirt under it;
 *  - water when mostly water;
 *  - a tree trunk's top gets the pack's tree sprite, so far-off forests look
 *    like the pack's forests.
 *
 * Collision, pathing, combat, digging and saves all see ordinary blocks.
 * Renderers that can draw the fine version ask [MicroTerrainSource] for it.
 *
 * ## Configuring it (what an AI world-builder writes)
 *
 * ```json
 * "terrain": {
 *   "generator": "stratum:microvoxel",
 *   "options": { "preset": "micro:arpg", "block.asphalt": "mypack:basalt" },
 *   "passes": [
 *     { "id": "micro:terrain",   "options": { "height": "0.3", "mountains": "0.5" } },
 *     { "id": "micro:city_plan", "options": { "density": "0.6", "styles": "terrace,villa" } },
 *     { "id": "micro:roads" }, { "id": "micro:buildings" }, { "id": "micro:trees" }
 *   ]
 * }
 * ```
 * `passes` empty uses the preset's stages. Options: `preset`, `spawns`
 * (monster spawn markers per 8 x 8 blocks of wild land, 0..1),
 * `block.<material>` to pin a material to a block. See
 * [MicroWorldgen.catalogue] for every stage and its options.
 */
class MicrovoxelTerrainGenerator(private val context: TerrainContext) : TerrainGenerator, BiomeSource, MarkedWorld, MicroTerrainSource {

    val micro: MicroGenerator = MicroWorldgen.build(context.config.seed, specsFor(context))

    override val palette: MaterialPalette get() = micro.palette

    private val spawnRate = context.recipe.options["spawns"]?.toFloatOrNull() ?: 0.35f
    private val overrides = context.recipe.options.filterKeys { it.startsWith("block.") }.mapKeys { it.key.removePrefix("block.") }

    private val micros = Lru<MicroChunkPos, MicroChunk>(MICRO_CACHE)
    private val converted = Lru<ChunkPos, ShortArray>(BLOCK_CACHE)

    @Volatile private var blocks: BlockPalette? = null
    @Volatile private var registry: BlockRegistry? = null

    private val waterId = palette.id(M.WATER)
    private val leafIds = setOf(palette.id(M.LEAVES), palette.id(M.LEAVES_AUTUMN))
    private val barkId = palette.id(M.BARK)

    override fun generate(pos: ChunkPos, registry: BlockRegistry): Chunk {
        val cells = convert(pos, paletteFor(registry))
        val chunk = Chunk(pos)
        for (i in cells.indices) {
            val b = cells[i].toInt()
            if (b != 0) chunk.setBlock(i % Chunk.SIZE, (i / Chunk.SIZE) % Chunk.SIZE, i / (Chunk.SIZE * Chunk.SIZE), b)
        }
        return chunk
    }

    /** The block mapping for a registry, built once; a session keeps one registry for its lifetime. */
    fun paletteFor(registry: BlockRegistry): BlockPalette {
        blocks?.takeIf { this.registry === registry }?.let { return it }
        synchronized(this) {
            blocks?.takeIf { this.registry === registry }?.let { return it }
            converted.clear()
            return BlockPalette(registry, palette, overrides).also { blocks = it; this.registry = registry }
        }
    }

    // ---- MicroTerrainSource ------------------------------------------------------------

    override fun microChunk(pos: MicroChunkPos): MicroChunk = micros.getOrPut(pos) { micro.generate(pos) }

    override fun generatedBlock(x: Int, y: Int, z: Int): Int {
        if (z !in 0 until Chunk.HEIGHT) return BlockRegistry.AIR_INDEX
        val b = blocks ?: return BlockRegistry.AIR_INDEX
        val pos = ChunkPos.containing(x, y)
        val cells = convert(pos, b)
        return cells[Chunk.indexOf(x - pos.originX, y - pos.originY, z)].toInt()
    }

    override fun materialForBlock(blockIndex: Int): Short = blocks?.materialForBlock(blockIndex) ?: MaterialPalette.AIR

    // ---- Conversion --------------------------------------------------------------------

    private fun convert(pos: ChunkPos, bp: BlockPalette): ShortArray = converted.getOrPut(pos) {
        val out = ShortArray(Chunk.VOLUME)
        val r = MICRO_PER_BLOCK
        val blocksPerMicroChunk = MicroChunk.SIZE / r
        val need = (r * r * r * SOLID_SHARE).toInt()
        for (cz in 0 until Chunk.HEIGHT / blocksPerMicroChunk) {
            val mc = microChunk(MicroChunkPos(pos.x, pos.y, cz))
            if (mc.isEmpty()) continue
            for (bz in 0 until blocksPerMicroChunk) for (by in 0 until Chunk.SIZE) for (bx in 0 until Chunk.SIZE) {
                val z = cz * blocksPerMicroChunk + bz
                val uniform = mc.brickUniform(bx * r / MicroChunk.BRICK, by * r / MicroChunk.BRICK, bz * r / MicroChunk.BRICK)
                val block = if (uniform != null) {
                    when {
                        uniform == MaterialPalette.AIR -> 0
                        uniform in leafIds -> 0
                        else -> bp.block(uniform).coerceAtLeast(0)
                    }
                } else {
                    var solid = 0; var water = 0; var leaves = 0; var bark = 0; var top: Short = MaterialPalette.AIR
                    for (dz in r - 1 downTo 0) for (dy in 0 until r) for (dx in 0 until r) {
                        val m = mc[bx * r + dx, by * r + dy, bz * r + dz]
                        when {
                            m == MaterialPalette.AIR -> Unit
                            m == waterId -> water++
                            m in leafIds -> leaves++
                            palette.isOpaque(m) && bp.block(m) >= 0 -> {
                                solid++
                                if (m == barkId) bark++
                                if (top == MaterialPalette.AIR) top = m
                            }
                        }
                    }
                    when {
                        solid >= need -> bp.block(top)
                        // A trunk is thinner than a block; keep it anyway, or forests lose their trees at block scale.
                        bark >= need / 2 -> bp.block(barkId)
                        water >= r * r * r / 2 -> bp.block(waterId).coerceAtLeast(0)
                        leaves >= need && bp.treeCrown != null -> CROWN
                        else -> 0
                    }
                }
                out[Chunk.indexOf(bx, by, z)] = block.toShort()
            }
        }
        placeCrowns(out, bp)
        for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) out[Chunk.indexOf(x, y, 0)] = BEDROCK
        out
    }

    /**
     * Canopy cells are placeholders until here. A pack's tree sprite is a
     * whole tree, trunk and all, so where a trunk carries a canopy the sprite
     * replaces the trunk, standing on the ground at its foot; the rest of the
     * canopy is air -- a crown of glyphs would be a hedge of sprites, not a
     * tree. Without a tree sprite the trunk stays and the canopy goes.
     */
    private fun placeCrowns(out: ShortArray, bp: BlockPalette) {
        val crown = bp.treeCrown
        val wood = bp.block(barkId)
        for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
            var leafy = false
            for (z in 1 until Chunk.HEIGHT) {
                val i = Chunk.indexOf(x, y, z)
                if (out[i].toInt() != CROWN) continue
                out[i] = 0
                if (out[Chunk.indexOf(x, y, z - 1)].toInt() == wood) leafy = true
            }
            if (!leafy || crown == null || wood <= 0) continue
            // The trunk's foot: the lowest wood block standing on something that is not wood.
            var foot = -1
            for (z in 1 until Chunk.HEIGHT) {
                val here = out[Chunk.indexOf(x, y, z)].toInt()
                if (here == wood && out[Chunk.indexOf(x, y, z - 1)].toInt() != wood) { foot = z; break }
            }
            if (foot < 0) continue
            out[Chunk.indexOf(x, y, foot)] = crown.toShort()
            var z = foot + 1
            while (z < Chunk.HEIGHT && out[Chunk.indexOf(x, y, z)].toInt() == wood) { out[Chunk.indexOf(x, y, z)] = 0; z++ }
        }
    }

    // ---- Biomes and markers ------------------------------------------------------------

    private val biomesByWarmth = context.biomes.sortedBy { it.temperature }

    override fun biomeAt(worldX: Int, worldY: Int): BiomeDefinition {
        // A pipeline without a climate (no terrain stage) has one region.
        val climate = micro.fields.get(Fields.CLIMATE) ?: return context.biomes.first()
        val mx = worldX * MICRO_PER_BLOCK; val my = worldY * MICRO_PER_BLOCK
        val t = climate.temperature(mx, my)
        val nearest = biomesByWarmth.minByOrNull { abs(it.temperature - t) } ?: context.biomes.first()
        // Biomes of about the same warmth take turns by moisture, so a pack with three temperate biomes shows all three.
        val peers = biomesByWarmth.filter { abs(it.temperature - nearest.temperature) < 0.08f }
        if (peers.size <= 1) return nearest
        val w = climate.moisture(mx, my)
        return peers[(w * peers.size).toInt().coerceIn(0, peers.size - 1)]
    }

    /**
     * Monsters wait in the wild, not in town: one candidate per 8 x 8 blocks,
     * kept by [spawnRate] where the land is not urban. Each town region marks
     * its centre as a point of interest, and some parks hold a cache.
     */
    override fun markersIn(pos: ChunkPos): List<WorldMarker> {
        val b = blocks ?: return emptyList()
        val cells = convert(pos, b)
        val footprint = micro.fields.get(Fields.FOOTPRINT)
        val sea = context.config.seaLevel
        val out = ArrayList<WorldMarker>()
        fun surface(lx: Int, ly: Int): Int {
            for (z in Chunk.HEIGHT - 1 downTo 1) if (cells[Chunk.indexOf(lx, ly, z)].toInt() != 0) return z
            return -1
        }
        for (gy in 0 until Chunk.SIZE / CELL) for (gx in 0 until Chunk.SIZE / CELL) {
            val seed = context.config.seed
            val lx = gx * CELL + Hash.int(seed, pos.x * 2 + gx, pos.y * 2 + gy, 91, CELL)
            val ly = gy * CELL + Hash.int(seed, pos.x * 2 + gx, pos.y * 2 + gy, 92, CELL)
            if (Hash.unit(seed, pos.x * 2 + gx, pos.y * 2 + gy, 0, 93) >= spawnRate) continue
            val wx = pos.originX + lx; val wy = pos.originY + ly
            val urban = footprint?.urban(wx * MICRO_PER_BLOCK, wy * MICRO_PER_BLOCK) ?: 0f
            val z = surface(lx, ly)
            if (urban > 0.3f || z < sea || z >= Chunk.HEIGHT - 3) continue
            out += WorldMarker(WorldMarkerKind.ENEMY_SPAWN, wx, wy, z + 1, SOURCE)
        }
        micro.fields.get(CityPlanStage.KEY)?.let { city ->
            val area = Rect(pos.originX * MICRO_PER_BLOCK, pos.originY * MICRO_PER_BLOCK, (pos.originX + Chunk.SIZE) * MICRO_PER_BLOCK - 1, (pos.originY + Chunk.SIZE) * MICRO_PER_BLOCK - 1)
            for (region in city.regionsTouching(area)) {
                if (!region.urban) continue
                val cx = (region.bounds.x0 + region.bounds.x1) / 2; val cy = (region.bounds.y0 + region.bounds.y1) / 2
                if (area.contains(cx, cy)) {
                    val lx = cx / MICRO_PER_BLOCK - pos.originX; val ly = cy / MICRO_PER_BLOCK - pos.originY
                    out += WorldMarker(WorldMarkerKind.POINT_OF_INTEREST, cx / MICRO_PER_BLOCK, cy / MICRO_PER_BLOCK, surface(lx, ly) + 1, SOURCE, "town")
                }
                for (lot in region.lots) {
                    if (lot.use != LotUse.PARK || (lot.seed and 3L) != 0L) continue
                    val px = (lot.rect.x0 + lot.rect.x1) / 2; val py = (lot.rect.y0 + lot.rect.y1) / 2
                    if (!area.contains(px, py)) continue
                    val lx = px / MICRO_PER_BLOCK - pos.originX; val ly = py / MICRO_PER_BLOCK - pos.originY
                    out += WorldMarker(WorldMarkerKind.LOOT, px / MICRO_PER_BLOCK, py / MICRO_PER_BLOCK, surface(lx, ly) + 1, SOURCE)
                }
            }
        }
        return out
    }

    companion object {
        const val MICRO_PER_BLOCK = 4

        /** Share of a block's microvoxels that must be solid for the block to be. */
        const val SOLID_SHARE = 0.375f

        const val SOURCE = "micro"

        private const val CELL = 8
        private const val CROWN = -2
        private val BEDROCK = 1.toShort() // BlockRegistry always puts bedrock at index 1
        private const val MICRO_CACHE = 160
        private const val BLOCK_CACHE = 96

        val factory = TerrainGeneratorFactory { context -> MicrovoxelTerrainGenerator(context) }

        /**
         * The stages a recipe asks for: its own `passes` when it lists any,
         * else its `preset`'s. The terrain stage gets the world's sea level and
         * a ceiling under the block world's top unless the recipe set them.
         */
        fun specsFor(context: TerrainContext): List<StageSpec> {
            val recipe = context.recipe
            val preset = recipe.options["preset"] ?: MicroWorldgen.ARPG
            val specs = if (recipe.passes.isNotEmpty()) recipe.passes.map { StageSpec(it.id, it.options) }
            else MicroWorldgen.presets[preset] ?: throw IllegalArgumentException(
                "No microvoxel preset '$preset'. Known: ${MicroWorldgen.presets.keys.joinToString()}",
            )
            return specs.map { spec ->
                if (spec.id != TerrainStage.ID) spec
                else spec.copy(options = mapOf(
                    "seaLevel" to (context.config.seaLevel * MICRO_PER_BLOCK).toString(),
                    "maxHeight" to ((Chunk.HEIGHT - 8) * MICRO_PER_BLOCK).toString(),
                ) + spec.options)
            }
        }
    }
}

/** A small thread-safe least-recently-used cache; values are computed outside the lock. */
internal class Lru<K, V>(private val capacity: Int) {
    private val map = object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > capacity
    }

    fun getOrPut(key: K, build: () -> V): V {
        synchronized(map) { map[key]?.let { return it } }
        val value = build()
        synchronized(map) { return map[key] ?: value.also { map[key] = it } }
    }

    fun clear() = synchronized(map) { map.clear() }
}
