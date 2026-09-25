package com.stratum.importer.tiled

import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.PackOrigin
import com.stratum.core.domain.map.MarkerKind
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.TerrainRecipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TiledProjectImporterTest {

    private val result = TiledProjectImporter().import(TiledFixtures.villageProject())
    private val map = result.pack.maps.single()

    @Test
    fun `the pack plays on its map and assembles cleanly`() {
        assertEquals(PackOrigin.IMPORTED, result.pack.origin)
        assertEquals(TerrainRecipe.TILE_MAP, result.pack.terrain?.generatorId)
        assertEquals(map.id, result.pack.terrain?.options?.get(TerrainRecipe.MAP_OPTION))

        val content = ContentPackAssembler().assemble(listOf(result.pack))
        assertNotNull(content.map(map.id))
    }

    @Test
    fun `layers stand at the height their role implies`() {
        val byName = map.layers.associateBy { it.name }

        assertEquals(0, byName.getValue("Ground").elevation)
        assertEquals(1, byName.getValue("Walls").elevation)
        assertEquals(2, byName.getValue("Walls").thickness)
        assertEquals(1, byName.getValue("Decor").elevation)
        assertNull(byName["Roof"], "overhead layers would bury the camera")
    }

    @Test
    fun `wall tiles are solid terrain and decoration is walkable scenery`() {
        val blocks = result.pack.blocks.associateBy { it.id }
        val wall = blocks.getValue(map.layers.single { it.name == "Walls" }.blockIdAt(0, 0)!!)
        val flower = blocks.getValue(map.layers.single { it.name == "Decor" }.blockIdAt(1, 1)!!)

        assertTrue(wall.isSolid)
        assertEquals(BlockShape.CUBE, wall.shape)
        assertNull(wall.glyph)
        assertFalse(flower.isSolid)
        assertNotNull(flower.glyph, "scenery is drawn as a standing sprite")
        assertTrue(result.textures.any { it.key == "prop:${flower.id}" })
        assertEquals("Flower", flower.displayName)
    }

    @Test
    fun `a colour property on a tile becomes the block's colour, and the ground's`() {
        val grassId = map.layers.single { it.name == "Ground" }.blockIdAt(0, 0)
        val grass = result.pack.blocks.single { it.id == grassId }
        val ground = result.pack.blocks.single { it.id == map.groundBlockId }

        assertEquals(0xFF3A7D44, grass.topColor)
        assertEquals(0xFF3A7D44, ground.topColor, "the fill takes the floor's authored colour")
        assertTrue(result.textures.none { it.key.startsWith(map.groundBlockId + "/") }, "the fill is plain, not a repeated tile")
    }

    @Test
    fun `collision rectangles become a barrier the player cannot walk through`() {
        val collision = map.layers.single { it.name == "collision" }
        val barrier = result.pack.blocks.single { it.id == collision.blockIdAt(3, 2) }

        assertTrue(barrier.isSolid)
        assertNull(collision.blockIdAt(0, 2))
    }

    @Test
    fun `spawn objects become markers in map cells`() {
        val player = map.markers.single { it.kind == MarkerKind.PLAYER_SPAWN }
        val slime = map.markers.single { it.kind == MarkerKind.ENEMY_SPAWN }

        assertEquals(1f, player.x)
        assertEquals(1f, player.y)
        assertEquals("slime", slime.refId)
    }

    @Test
    fun `every tile gets one texture cut from its tileset`() {
        val grassId = map.layers.single { it.name == "Ground" }.blockIdAt(0, 0)
        val grassTop = result.textures.single { it.key == "$grassId/top" }

        assertEquals("images/overworld.png", grassTop.region.imagePath)
        assertEquals(0, grassTop.region.x)
        assertTrue(result.textures.none { it.key == "$grassId/side" }, "sides reuse the top in the renderer")
    }

    @Test
    fun `what could not be placed is reported, not dropped`() {
        assertTrue(result.warnings.any { "Roof" in it })
        assertTrue(result.warnings.any { "Image layers" in it })
    }

    @Test
    fun `a tile the tileset marks solid is solid even on the floor layer`() {
        val dungeon = TiledProjectImporter().import(TiledFixtures.dungeonProject())
        val floor = dungeon.pack.maps.single().layers.single()

        assertTrue(dungeon.pack.blocks.single { it.id == floor.blockIdAt(1, 1) }.isSolid)
        assertEquals(2.5f, dungeon.pack.maps.single().playerSpawn?.x)
    }

    @Test
    fun `re-importing the same project produces the same ids`() {
        val again = TiledProjectImporter().import(TiledFixtures.villageProject())
        assertEquals(result.pack.blocks.map { it.id }, again.pack.blocks.map { it.id })
    }
}
