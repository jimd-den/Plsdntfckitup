package com.stratum.importer.tiled

import com.stratum.core.domain.importing.ImageRegion
import com.stratum.core.domain.importing.ImportedTexture
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

    val allBlocks: List<BlockType> get() = blocks.values.toList()
    val allTextures: List<ImportedTexture> get() = textures.values.toList()

    /** The block for a raw gid on a layer with [role], or null for an empty cell or an unknown tile. */
    fun blockFor(rawGid: Int, role: LayerRole): String? {
        val gid = TiledGid.tileOf(rawGid)
        if (gid == 0) return null
        val tileset = map.tilesetFor(gid) ?: return null
        val localId = gid - tileset.firstGid
        val tile = tileset.tiles[localId]
        val solid = tile?.properties?.firstBool("solid", "collides", "collision") ?: role.solid
        val shape = shapeFor(role, solid)
        val id = ImportNaming.id(namespace, tileset.name, localId.toString()) + suffixFor(role, shape)
        blocks.getOrPut(id) { block(id, tileset, localId, tile, solid, shape) }
        regionOf(tileset, localId, tile)?.let { region -> rememberTexture(id, region) }
        return id
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

    private fun shapeFor(role: LayerRole, solid: Boolean): BlockShape = when {
        role.kind == LayerRole.Kind.FLOOR -> BlockShape.CUBE
        !solid -> BlockShape.FLOOR
        else -> BlockShape.CUBE
    }

    /** Floor blocks keep the plain id; the same tile standing up or lying flat on top gets its own. */
    private fun suffixFor(role: LayerRole, shape: BlockShape): String = when {
        role.kind == LayerRole.Kind.FLOOR -> ""
        shape == BlockShape.FLOOR -> "_decal"
        else -> "_solid"
    }

    private fun block(id: String, tileset: TiledTileset, localId: Int, tile: TiledTile?, solid: Boolean, shape: BlockShape): BlockType {
        val properties = (tile?.properties ?: TiledProperties.EMPTY)
        val top = SeedColors.parse(properties.firstString("color", "colour")) ?: SeedColors.topFor(id)
        return BlockType(
            id = id,
            displayName = properties.string("name") ?: tile?.type?.takeIf(String::isNotBlank)?.let(ImportNaming::displayName)
                ?: "${ImportNaming.displayName(tileset.name)} $localId",
            material = materialOf(properties.string("material")),
            hardness = properties.string("hardness")?.toFloatOrNull() ?: 1f,
            isSolid = solid,
            isOpaque = shape == BlockShape.CUBE,
            lightEmission = (properties.int("light") ?: 0).coerceIn(0, 15),
            shape = shape,
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

    /** Texture keys follow the renderer's `<block id>/<face>` convention. */
    private fun rememberTexture(blockId: String, region: ImageRegion) {
        FACES.forEach { face -> textures.getOrPut("$blockId/$face") { ImportedTexture("$blockId/$face", region) } }
    }

    private companion object {
        val FACES = listOf("top", "side")
    }
}
