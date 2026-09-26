package com.stratum.importer.tiled

import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.importer.common.MemoryImportSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Shapes real projects take that small fixtures do not: repeated names, and maps that cannot be read. */
class ProjectShapeTest {

    private fun map(image: String, tilesetName: String = "tileset") = """
        {"type": "map", "width": 1, "height": 1, "tilewidth": 16, "tileheight": 16,
         "tilesets": [{"firstgid": 1, "name": "$tilesetName", "tilewidth": 16, "tileheight": 16,
                       "tilecount": 1, "columns": 1, "image": "$image"}],
         "layers": [{"type": "tilelayer", "name": "floor", "width": 1, "height": 1, "data": [1]}]}
    """.trimIndent()

    @Test
    fun `maps with the same file name in different folders keep separate ids`() {
        val source = MemoryImportSource.ofText(
            "Game",
            mapOf("assets/dungeon/map.tmj" to map("dungeon.png"), "assets/town/map.tmj" to map("town.png")),
        )
        val pack = TiledProjectImporter().import(source).pack

        assertEquals(setOf("game:dungeon_map", "game:town_map"), pack.maps.map { it.id }.toSet())
        ContentPackAssembler().assemble(listOf(pack))
    }

    @Test
    fun `tilesets with the same name but different art make different blocks`() {
        val source = MemoryImportSource.ofText(
            "Game",
            mapOf("a.tmj" to map("grass.png"), "b.tmj" to map("stone.png")),
        )
        val pack = TiledProjectImporter().import(source).pack
        val floors = pack.maps.map { it.layers.single().blockIdAt(0, 0) }

        assertNotEquals(floors[0], floors[1])
    }

    @Test
    fun `the same art embedded in two maps is one block`() {
        val source = MemoryImportSource.ofText(
            "Game",
            mapOf("a.tmj" to map("grass.png", "Grass"), "b.tmj" to map("grass.png", "Meadow")),
        )
        val floors = TiledProjectImporter().import(source).pack.maps.map { it.layers.single().blockIdAt(0, 0) }

        assertEquals(floors[0], floors[1])
    }

    @Test
    fun `a map that cannot be read is left out and named, and the rest import`() {
        val infinite = """{"type":"map","width":1,"height":1,"tilewidth":8,"tileheight":8,"infinite":true,
            "tilesets":[],"layers":[{"type":"tilelayer","name":"L","chunks":[]}]}"""
        val source = MemoryImportSource.ofText("Game", mapOf("good.tmj" to map("grass.png"), "endless.tmj" to infinite))
        val result = TiledProjectImporter().import(source)

        assertEquals(listOf("game:good"), result.pack.maps.map { it.id })
        assertTrue(result.warnings.any { "endless.tmj" in it && "fixed map size" in it })
    }
}
