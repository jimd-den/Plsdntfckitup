package com.stratum.importer.tiled

import com.stratum.core.domain.importing.ImageRegion
import com.stratum.core.domain.importing.ImportedTexture
import com.stratum.core.domain.map.TileLayer
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockType
import com.stratum.importer.common.ImportNaming
import com.stratum.importer.common.SeedColors

/**
 * Hands out one engine block per distinct tile, and remembers the art for it.
 *
 * A tile used on a floor and the same tile used as a solid obstacle are two
 * blocks, because solidity belongs to the block in this engine rather than to
 * where it was placed.
 */
internal class TileBlockCatalog(private val namespace: String, private val map: TiledMap) {

    private val blocks = LinkedHashMap<String, BlockType>()
    private val textures = LinkedHashMap<String, ImportedTexture>()

    /** Blocks whose colour the author gave, as opposed to one hashed from the id. */
    private val authoredColors = mutableSetOf<String>()

    val allBlocks: List<BlockType> get() = blocks.values.toList()
    val allTextures: List<ImportedTexture> get() = textures.values.toList()

    /** The block for a raw gid on a layer with [role], or null for an empty cell or an unknown tile. */
    fun blockFor(rawGid: Int, role: LayerRole): String? {
        val gid = TiledGid.tileOf(rawGid)
        if (gid == 0) return null
        val tileset = map.tilesetFor(gid) ?: return null
        val localId = gid - tileset.firstGid
        val tile = tileset.tiles[localId]
        val properties = tile?.properties ?: TiledProperties.EMPTY
        val solid = properties.firstBool("solid", "collides", "collision") ?: role.solid
        val form = formOf(role, properties)
        val id = baseIdFor(tileset, localId, tile) + form.suffix
        blocks.getOrPut(id) { block(id, tileset, localId, tile, solid, form) }
        regionOf(tileset, localId, tile)?.let { region -> rememberTexture(id, form, region) }
        return id
    }

    /**
     * How a tile stands in the world. Floors and walls are terrain; anything
     * on a decoration layer is scenery, drawn the way the engine draws its own
     * -- a painted sprite standing up and cut out by its transparency -- unless
     * the author marked it `flat`, as a rug or a crack in the floor would be.
     */
    private enum class Form(val suffix: String, val shape: BlockShape, val prop: Boolean) {
        TERRAIN("", BlockShape.CUBE, prop = false),
        SOLID("_solid", BlockShape.CUBE, prop = false),
        PROP("_prop", BlockShape.CUBE, prop = true),
        DECAL("_decal", BlockShape.FLOOR, prop = false),
    }

    private fun formOf(role: LayerRole, properties: TiledProperties): Form = when (role.kind) {
        LayerRole.Kind.FLOOR -> Form.TERRAIN
        LayerRole.Kind.WALL -> Form.SOLID
        else -> if (properties.bool("flat") == true) Form.DECAL else Form.PROP
    }

    /** A plain, untextured block for the ground under everything, or for collision with no tile. */
    fun plainBlock(name: String, color: Long, solid: Boolean = true, shape: BlockShape = BlockShape.CUBE): String {
        val id = ImportNaming.id(namespace, name)
        blocks.getOrPut(id) {
            BlockType(
                id = id, displayName = ImportNaming.displayName(name), material = BlockMaterial.SOIL,
                isSolid = solid, isOpaque = shape == BlockShape.CUBE, shape = shape,
                topColor = color, sideColor = SeedColors.shade(color),
            )
        }
        return id
    }

    /** The colour of the block a layer uses most, when that colour was given rather than invented. */
    fun commonestColor(layer: TileLayer): Long? {
        val counts = (0 until layer.height).flatMap { y -> (0 until layer.width).mapNotNull { x -> layer.blockIdAt(x, y) } }
            .groupingBy { it }.eachCount()
        val commonest = counts.maxByOrNull { it.value }?.key ?: return null
        return commonest.takeIf { it in authoredColors }?.let { blocks.getValue(it).topColor }
    }

    /**
     * Tiles are named by the art they come from, not by the tileset's name:
     * two tilesets both called "tileset" are different art, and two maps
     * embedding the same image are the same art and should share blocks.
     */
    private fun baseIdFor(tileset: TiledTileset, localId: Int, tile: TiledTile?): String = when {
        tile?.image != null -> ImportNaming.id(namespace, artKey(tile.image))
        tileset.image != null -> ImportNaming.id(namespace, artKey(tileset.image), localId.toString())
        else -> ImportNaming.id(namespace, tileset.name, localId.toString())
    }

    /** `assets/images/tiles/forest.png` -> `tiles/forest`, which the slug makes `tiles_forest`. */
    private fun artKey(imagePath: String): String =
        imagePath.removePrefix("assets/").removePrefix("images/").substringBeforeLast('.')

    private fun block(id: String, tileset: TiledTileset, localId: Int, tile: TiledTile?, solid: Boolean, form: Form): BlockType {
        val properties = (tile?.properties ?: TiledProperties.EMPTY)
        val authored = SeedColors.parse(properties.firstString("color", "colour"))
        if (authored != null) authoredColors += id
        val top = authored ?: SeedColors.topFor(id)
        return BlockType(
            id = id,
            displayName = properties.string("name") ?: tile?.type?.takeIf(String::isNotBlank)?.let(ImportNaming::displayName)
                ?: "${ImportNaming.displayName(tileset.name)} $localId",
            material = materialOf(properties.string("material")),
            hardness = properties.string("hardness")?.toFloatOrNull() ?: 1f,
            isSolid = solid,
            isOpaque = form.shape == BlockShape.CUBE && !form.prop,
            lightEmission = (properties.int("light") ?: 0).coerceIn(0, 15),
            shape = form.shape,
            // The engine draws a block with a glyph as scenery; the glyph itself only labels it in the bag.
            glyph = PROP_GLYPH.takeIf { form.prop },
            topColor = top,
            sideColor = SeedColors.shade(top),
        )
    }

    private fun materialOf(name: String?): BlockMaterial =
        BlockMaterial.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BlockMaterial.STONE

    private fun regionOf(tileset: TiledTileset, localId: Int, tile: TiledTile?): ImageRegion? {
        tile?.image?.let { image ->
            val width = tile.imageWidth.takeIf { it > 0 } ?: tileset.tileWidth
            val height = tile.imageHeight.takeIf { it > 0 } ?: tileset.tileHeight
            return ImageRegion(image, 0, 0, width, height)
        }
        val image = tileset.image ?: return null
        val rect = tileset.sourceRect(localId) ?: return null
        return ImageRegion(image, rect.x, rect.y, rect.width, rect.height)
    }

    /**
     * Texture keys follow the renderer's conventions: `<block id>/<face>` for
     * terrain, `prop:<block id>` for scenery. Only a terrain block's top is
     * written: a tile is one painting, and the renderer gives an imported
     * block's sides its top rather than holding the same art twice.
     */
    private fun rememberTexture(blockId: String, form: Form, region: ImageRegion) {
        val key = if (form.prop) "prop:$blockId" else "$blockId/top"
        textures.getOrPut(key) { ImportedTexture(key, region) }
    }

    private companion object {
        const val PROP_GLYPH = "✦"
    }
}
