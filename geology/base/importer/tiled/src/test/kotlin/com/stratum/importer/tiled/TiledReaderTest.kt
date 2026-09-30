package com.stratum.importer.tiled

import com.stratum.core.domain.importing.ImportException
import com.stratum.importer.common.MemoryImportSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TiledReaderTest {

    @Test
    fun `a JSON map reads its size, layers and embedded tileset`() {
        val map = TiledReader(TiledFixtures.villageProject()).readMap("maps/village.tmj")

        assertEquals(4, map.width)
        assertEquals(3, map.height)
        assertEquals(listOf("Ground", "Walls", "Decor", "Roof", "Collisions", "Spawns", "Sky"), map.layers.map { it.name })
        assertEquals("images/overworld.png", map.tilesets.single().image, "image paths resolve against the map")
        assertEquals("Oak Village", map.properties.string("name"))
    }

    @Test
    fun `the object class field and the older type field both read as the type`() {
        val map = TiledReader(TiledFixtures.villageProject()).readMap("maps/village.tmj")
        val spawns = map.layers.filterIsInstance<TiledObjectGroup>().single { it.name == "Spawns" }

        assertEquals(listOf("", "enemy_spawn"), spawns.objects.map { it.type })
        assertTrue(spawns.objects.first().isPoint)
    }

    @Test
    fun `an XML map loads its external tileset and reads compressed data`() {
        listOf("zlib", "gzip", "").forEach { compression ->
            val map = TiledReader(TiledFixtures.dungeonProject(compression)).readMap("maps/dungeon.tmx")
            val floor = assertIs<TiledTileLayer>(map.layers.first())

            assertEquals(listOf(1, 1, 1, 1, 2, 1), floor.gids.toList(), "compression '$compression'")
            assertEquals("Dungeon", map.tilesets.single().name)
            assertEquals("tilesets/dungeon.png", map.tilesets.single().image)
            assertEquals(1, map.tilesets.single().firstGid)
        }
    }

    @Test
    fun `tile rectangles honour margin and spacing`() {
        val tileset = TiledReader(TiledFixtures.dungeonProject()).readTileset("tilesets/dungeon.tsx")

        assertEquals(TileRect(1, 1, 32, 32), tileset.sourceRect(0))
        assertEquals(TileRect(35, 35, 32, 32), tileset.sourceRect(3))
    }

    @Test
    fun `csv data and flip flags decode to plain tile ids`() {
        val flipped = 0x80000000.toInt() or 5

        assertEquals(listOf(1, 0, 3), LayerDataDecoder.decode("csv", null, "1, 0,\n3", expected = 3).toList())
        assertEquals(5, TiledGid.tileOf(flipped))
        assertTrue(TiledGid.isTransformed(flipped))
    }

    @Test
    fun `zstandard is refused by name rather than read as an empty layer`() {
        val failure = assertFailsWith<ImportException> { LayerDataDecoder.decode("base64", "zstd", "AAAA", expected = 1) }
        assertTrue("Zstandard" in failure.message.orEmpty())
    }

    @Test
    fun `an infinite map says how to fix it`() {
        val infinite = """{"type":"map","width":1,"height":1,"tilewidth":8,"tileheight":8,"infinite":true,
            "tilesets":[],"layers":[{"type":"tilelayer","name":"L","chunks":[]}]}"""
        val source = MemoryImportSource.ofText("Endless", mapOf("endless.tmj" to infinite))

        val failure = assertFailsWith<ImportException> { TiledReader(source).readMap("endless.tmj") }
        assertTrue("fixed map size" in failure.message.orEmpty())
    }

    @Test
    fun `a DOCTYPE in a TMX file is refused rather than expanded`() {
        val hostile = """<?xml version="1.0"?><!DOCTYPE map [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <map width="1" height="1" tilewidth="8" tileheight="8"><layer name="&x;" width="1" height="1"><data encoding="csv">0</data></layer></map>"""
        val source = MemoryImportSource.ofText("Hostile", mapOf("evil.tmx" to hostile))

        assertFailsWith<ImportException> { TiledReader(source).readMap("evil.tmx") }
    }

    @Test
    fun `only json files that say they are maps count as maps`() {
        val source = MemoryImportSource.ofText(
            "Mixed",
            mapOf("a.tmj" to "{}", "b.json" to """{"type":"map"}""", "c.json" to """{"frames":{}}""", "d.tmx" to "<map/>"),
        )

        assertEquals(listOf("a.tmj", "b.json", "d.tmx"), TiledReader.mapPathsIn(source))
    }
}
