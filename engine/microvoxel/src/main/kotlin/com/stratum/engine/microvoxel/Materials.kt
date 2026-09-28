package com.stratum.engine.microvoxel

import com.stratum.engine.microvoxel.arch.ArchMaterials
import com.stratum.engine.microvoxel.geo.GeoMaterials

/**
 * What a microvoxel is made of.
 *
 * A material is a palette entry, not an object per voxel: the world stores
 * 16-bit ids and looks the rest up here. [color] is linear-ish sRGB packed as
 * 0xRRGGBB; [emission] makes a voxel light its surroundings in renderers that
 * support it (street lamps, windows at night); [opaque] decides face culling
 * and meshing; [solid] decides collision.
 */
data class Material(
    val id: Short,
    val name: String,
    val color: Int,
    val opaque: Boolean = true,
    val solid: Boolean = true,
    val emission: Float = 0f,
    /** 0 = matte, 1 = mirror. Only the preview tracer and high-tier shaders use it. */
    val gloss: Float = 0f,
    /** Per-voxel brightness jitter, which is most of what makes microvoxels read as a surface and not plastic. */
    val jitter: Float = 0.06f,
)

/**
 * The materials a world knows, indexed by id.
 *
 * Open: a pack registers its own after the built-ins. Ids are dense so a
 * lookup is an array index, which matters when a mesher asks millions of times.
 */
class MaterialPalette {
    private val byId = ArrayList<Material?>()
    private val byName = HashMap<String, Material>()

    val all: List<Material> get() = byId.filterNotNull()

    fun register(name: String, color: Int, opaque: Boolean = true, solid: Boolean = true, emission: Float = 0f, gloss: Float = 0f, jitter: Float = 0.06f): Short {
        byName[name]?.let { return it.id }
        val id = byId.size.toShort()
        val material = Material(id, name, color, opaque, solid, emission, gloss, jitter)
        byId.add(material)
        byName[name] = material
        return id
    }

    operator fun get(id: Short): Material = byId.getOrNull(id.toInt()) ?: error("Unknown material id $id")

    fun id(name: String): Short = byName[name]?.id ?: throw IllegalArgumentException(
        "No material named '$name'. Known: ${byName.keys.sorted().joinToString()}",
    )

    fun isOpaque(id: Short): Boolean = id != AIR && byId[id.toInt()]!!.opaque

    companion object {
        const val AIR: Short = 0

        /** The built-in set every stage in this module can rely on. */
        fun standard(): MaterialPalette = MaterialPalette().apply {
            register("air", 0x000000, opaque = false, solid = false)
            register(M.GRASS, 0x5E9B3A, jitter = 0.12f)
            register(M.GRASS_DRY, 0xA3A34E, jitter = 0.12f)
            register(M.DIRT, 0x7A5638, jitter = 0.1f)
            register(M.STONE, 0x8A8A86, jitter = 0.1f)
            register(M.DARK_STONE, 0x5B5A58, jitter = 0.08f)
            register(M.SAND, 0xD8C58E, jitter = 0.07f)
            register(M.SNOW, 0xF2F5FA, jitter = 0.03f)
            register(M.WATER, 0x2F6FA0, opaque = false, solid = false, gloss = 0.8f, jitter = 0f)
            register(M.BARK, 0x5A3E28, jitter = 0.1f)
            register(M.LEAVES, 0x3F7F2E, jitter = 0.18f)
            register(M.LEAVES_AUTUMN, 0xC0692A, jitter = 0.18f)
            register(M.FLOWER_RED, 0xD8343A)
            register(M.FLOWER_YELLOW, 0xF0C93A)
            register(M.ASPHALT, 0x38393C, jitter = 0.05f)
            register(M.LANE_WHITE, 0xE8E8E0, jitter = 0.02f)
            register(M.LANE_YELLOW, 0xE0B83A, jitter = 0.02f)
            register(M.SIDEWALK, 0xB5B1A8, jitter = 0.05f)
            register(M.CURB, 0x9C9990, jitter = 0.04f)
            register(M.BRICK, 0x9C4A34, jitter = 0.14f)
            register(M.BRICK_DARK, 0x6E3326, jitter = 0.1f)
            register(M.PLASTER, 0xE6D9C0, jitter = 0.04f)
            register(M.PLASTER_BLUE, 0x8FB2C8, jitter = 0.04f)
            register(M.CONCRETE, 0xA9A8A2, jitter = 0.05f)
            register(M.ROOF_TILE, 0xA0462E, jitter = 0.1f)
            register(M.ROOF_SLATE, 0x4A5260, jitter = 0.08f)
            register(M.TIMBER, 0x7B5534, jitter = 0.08f)
            register(M.GLASS, 0x5A86A4, gloss = 0.9f, jitter = 0.01f)
            register(M.GLASS_LIT, 0xFFD890, emission = 1.2f, jitter = 0.05f)
            register(M.METAL, 0x44484C, gloss = 0.4f, jitter = 0.02f)
            register(M.LAMP, 0xFFF1C8, emission = 3f, jitter = 0f)
            register(M.GRAVEL, 0x8C857A, jitter = 0.16f)
            // Added after the first set so existing ids never move.
            register(M.THATCH, 0xB8914E, jitter = 0.16f)
            register(M.THATCH_DARK, 0x8A6A36, jitter = 0.12f)
            register(M.MUD, 0xA0583A, jitter = 0.08f)
            register(M.MUD_DARK, 0x6E3A25, jitter = 0.08f)
            register(M.NZU, 0xEDE4D0, jitter = 0.03f)
            register(M.PALM, 0x5E8F2C, jitter = 0.14f)
            register(M.FRUIT, 0xD8581E, jitter = 0.1f)
            register(M.BEATEN_EARTH, 0xB07A52, jitter = 0.1f)
            // Africa's rocks and regoliths, then its building materials: see the geo and arch packages.
            GeoMaterials.all.forEach { (name, color, jitter) -> register(name, color, jitter = jitter) }
            register(M.SALT_WATER, 0x5E9AA8, opaque = false, solid = false, gloss = 0.85f, jitter = 0f)
            register(M.SODA_WATER, 0xB86A5A, opaque = false, solid = false, gloss = 0.7f, jitter = 0f)
            register(M.OBSIDIAN, 0x1E1B22, gloss = 0.7f, jitter = 0.04f)
            register(M.LAVA_GLOW, 0xE8561E, emission = 2.2f, jitter = 0.1f)
            ArchMaterials.all.forEach { (name, color, jitter) -> register(name, color, jitter = jitter) }
        }
    }
}

/** Names of the built-in materials, so stages do not scatter string literals. */
object M {
    const val GRASS = "grass"
    const val GRASS_DRY = "grass_dry"
    const val DIRT = "dirt"
    const val STONE = "stone"
    const val DARK_STONE = "dark_stone"
    const val SAND = "sand"
    const val SNOW = "snow"
    const val WATER = "water"
    const val BARK = "bark"
    const val LEAVES = "leaves"
    const val LEAVES_AUTUMN = "leaves_autumn"
    const val FLOWER_RED = "flower_red"
    const val FLOWER_YELLOW = "flower_yellow"
    const val ASPHALT = "asphalt"
    const val LANE_WHITE = "lane_white"
    const val LANE_YELLOW = "lane_yellow"
    const val SIDEWALK = "sidewalk"
    const val CURB = "curb"
    const val BRICK = "brick"
    const val BRICK_DARK = "brick_dark"
    const val PLASTER = "plaster"
    const val PLASTER_BLUE = "plaster_blue"
    const val CONCRETE = "concrete"
    const val ROOF_TILE = "roof_tile"
    const val ROOF_SLATE = "roof_slate"
    const val TIMBER = "timber"
    const val GLASS = "glass"
    const val GLASS_LIT = "glass_lit"
    const val METAL = "metal"
    const val LAMP = "lamp"
    const val GRAVEL = "gravel"
    const val THATCH = "thatch"
    const val THATCH_DARK = "thatch_dark"
    const val MUD = "mud"
    const val MUD_DARK = "mud_dark"
    /** White kaolin chalk, the paint of uli wall designs. */
    const val NZU = "nzu"
    const val PALM = "palm"
    const val FRUIT = "fruit"
    const val BEATEN_EARTH = "beaten_earth"
    const val SALT_WATER = "salt_water"
    /** The red-pink brine of a soda lake (Natron, Magadi), coloured by salt-loving algae. */
    const val SODA_WATER = "soda_water"
    const val OBSIDIAN = "obsidian"
    const val LAVA_GLOW = "lava_glow"
}
