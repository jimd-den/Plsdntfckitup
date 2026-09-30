package com.stratum.engine.microbridge

import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockType
import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.arch.A
import com.stratum.engine.microvoxel.geo.R

/**
 * Which of a pack's blocks each microvoxel material becomes.
 *
 * Any pack works, including one an AI wrote a minute ago: each material asks
 * for a kind of block (soil, stone, wood...) and takes the one of that kind
 * whose colour is nearest its own, so an ash-grey pack gets ash-grey roads
 * and a red-earth pack red-earth hills. A recipe can pin any material to a
 * block with a `block.<material>` option.
 *
 * Only plain, breakable cubes are candidates -- no glyph props, no thin
 * walls, no ores, no ritual blocks -- and anything a building is made of must
 * not fall, so digging under a house does not bring it down in sand.
 */
class BlockPalette(
    private val registry: BlockRegistry,
    private val palette: MaterialPalette,
    overrides: Map<String, String> = emptyMap(),
) {
    /** Block index for each micro material id; -1 means the material becomes air. */
    private val blockFor: IntArray

    /** The pack's tree sprite, placed at the top of a trunk so distant forests still read as forests. */
    val treeCrown: Int?

    /** Micro material for each block index, for drawing blocks the player placed. */
    private val materialFor: ShortArray

    init {
        val ids = palette.all
        blockFor = IntArray(ids.maxOf { it.id.toInt() } + 1) { -1 }
        for (m in ids) {
            if (m.id == MaterialPalette.AIR) continue
            val pinned = overrides[m.name]
            blockFor[m.id.toInt()] = when {
                // A pack block's own material is that block, exactly.
                m.name.startsWith(BlockMaterials.PREFIX) -> registry.indexOrNull(m.name.removePrefix(BlockMaterials.PREFIX)) ?: -1
                pinned != null -> registry.indexOrNull(pinned) ?: throw IllegalArgumentException(
                    "Microvoxel option 'block.${m.name}' names unknown block '$pinned'",
                )
                else -> choose(m.name, m.color)
            }
        }
        treeCrown = registry.all.indices.firstOrNull { i ->
            val b = registry.typeOf(i)
            b.material == BlockMaterial.FOLIAGE && b.glyph != null && b.glyphScale >= 1.2f
        } ?: registry.all.indices.firstOrNull { i -> registry.typeOf(i).let { it.material == BlockMaterial.FOLIAGE && it.glyph != null } }
        // Every block gets a micro material of its own colour, registered up front so the palette never changes under a renderer.
        materialFor = ShortArray(registry.size) { i ->
            if (i == BlockRegistry.AIR_INDEX) MaterialPalette.AIR
            else registry.typeOf(i).let { b ->
                palette.register(BlockMaterials.PREFIX + b.id, (b.topColor and 0xFFFFFF).toInt(), opaque = b.material != com.stratum.core.domain.world.BlockMaterial.LIQUID, solid = b.isSolid, emission = b.lightEmission / 10f)
            }
        }
    }

    fun block(material: Short): Int = blockFor.getOrElse(material.toInt()) { -1 }

    fun materialForBlock(index: Int): Short = materialFor.getOrElse(index) { MaterialPalette.AIR }

    private fun choose(name: String, color: Int): Int {
        val wanted = KINDS[name] ?: kindsByFamily(name) ?: return -1
        for (kind in wanted) {
            val structural = isStructural(name)
            val best = registry.all.indices.filter { i -> candidate(registry.typeOf(i), kind, structural) }
                .minByOrNull { i -> distance(registry.typeOf(i), color) }
            if (best != null) return best
        }
        return -1
    }

    private fun candidate(b: BlockType, kind: BlockMaterial, structural: Boolean): Boolean {
        if (b.material != kind || b.id == BlockType.AIR_ID || b.id == BlockType.BEDROCK.id) return false
        if (kind == BlockMaterial.LIQUID) return !b.isSolid
        return b.shape == BlockShape.CUBE && b.glyph == null && b.isSolid && b.isOpaque &&
            b.hardness != BlockType.UNBREAKABLE && !(structural && b.hasGravity)
    }

    private fun distance(b: BlockType, rgb: Int): Int = minOf(rgbDistance(b.topColor, rgb), rgbDistance(b.sideColor, rgb))

    private fun rgbDistance(argb: Long, rgb: Int): Int {
        val dr = ((argb shr 16) and 255).toInt() - ((rgb shr 16) and 255)
        val dg = ((argb shr 8) and 255).toInt() - ((rgb shr 8) and 255)
        val db = (argb and 255).toInt() - (rgb and 255)
        return 2 * dr * dr + 4 * dg * dg + 3 * db * db
    }

    companion object {
        private val S = BlockMaterial.SOIL
        private val ST = BlockMaterial.STONE
        private val W = BlockMaterial.WOOD
        private val ME = BlockMaterial.METAL

        /** The kind of block each material wants, most wanted first. Missing = becomes air (flowers, leaves). */
        val KINDS: Map<String, List<BlockMaterial>> = mapOf(
            M.GRASS to listOf(S), M.GRASS_DRY to listOf(S), M.DIRT to listOf(S), M.SAND to listOf(S, ST),
            M.GRAVEL to listOf(S, ST), M.SNOW to listOf(S, ST), M.STONE to listOf(ST, S), M.DARK_STONE to listOf(ST, S),
            M.WATER to listOf(BlockMaterial.LIQUID),
            M.BARK to listOf(W, ST), M.TIMBER to listOf(W, ST),
            M.ASPHALT to listOf(ST, S), M.LANE_WHITE to listOf(ST, S), M.LANE_YELLOW to listOf(ST, S),
            M.SIDEWALK to listOf(ST, S), M.CURB to listOf(ST, S), M.CONCRETE to listOf(ST, S),
            M.BRICK to listOf(ST, S), M.BRICK_DARK to listOf(ST, S), M.PLASTER to listOf(ST, S), M.PLASTER_BLUE to listOf(ST, S),
            M.ROOF_TILE to listOf(ST, W, S), M.ROOF_SLATE to listOf(ST, S), M.GLASS to listOf(ST, S), M.GLASS_LIT to listOf(ST, S),
            M.METAL to listOf(ME, ST), M.LAMP to listOf(ME, ST),
        )

        private val LOOSE = setOf(
            R.LATERITE, R.MOTTLED_CLAY, R.SAPROLITE, R.FERRALSOL, R.VERTISOL, R.ANDOSOL, R.GYPCRETE, R.SALT, R.TRONA, R.SULPHUR,
            R.KALAHARI_SAND, R.ERG_SAND, R.NAMIB_SAND, R.CORAL_SAND, R.REG_GRAVEL, R.DELTA_MUD, R.DIATOMITE, R.TERMITE_CLAY,
            R.TALUS, R.NANKA_SAND,
        )
        private val WOODEN = setOf(A.TORON, A.MANGROVE_POLE, A.CARVED_DOOR, A.POST, A.AKSUM_TIMBER, A.THORN)
        private val THATCHED = setOf(A.MILLET_THATCH, A.GRASS_WEAVE, A.RAFFIA)

        /**
         * Kinds by family, for the African rocks and building materials: soils and sands want soil
         * blocks, rock wants stone, timber wants wood, thatch (like the built-in thatch) is detail
         * that reads as air at block scale, and walls must never fall.
         */
        private fun kindsByFamily(name: String): List<BlockMaterial>? = when {
            name == M.SALT_WATER || name == M.SODA_WATER -> listOf(BlockMaterial.LIQUID)
            name == M.OBSIDIAN || name == M.LAVA_GLOW -> listOf(ST, S)
            // A player's painted model: sturdy, whatever its colour.
            name.startsWith(com.stratum.engine.microvoxel.Paints.PREFIX) -> listOf(ST, S)
            name.startsWith("geo:") -> if (name in LOOSE) listOf(S, ST) else listOf(ST, S)
            name in THATCHED -> null
            name in WOODEN -> listOf(W, ST)
            name.startsWith("arch:") -> listOf(ST, S)
            else -> null
        }

        private fun isStructural(name: String) = name in STRUCTURAL || name.startsWith("arch:") || name.startsWith(com.stratum.engine.microvoxel.Paints.PREFIX)

        /** What buildings and roads are made of: never a block that falls. */
        val STRUCTURAL = setOf(
            M.ASPHALT, M.LANE_WHITE, M.LANE_YELLOW, M.SIDEWALK, M.CURB, M.CONCRETE, M.BRICK, M.BRICK_DARK,
            M.PLASTER, M.PLASTER_BLUE, M.ROOF_TILE, M.ROOF_SLATE, M.GLASS, M.GLASS_LIT, M.METAL, M.LAMP, M.TIMBER, M.BARK,
        )
    }
}
