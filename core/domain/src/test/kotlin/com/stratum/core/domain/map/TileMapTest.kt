package com.stratum.core.domain.map

import com.stratum.core.domain.world.Chunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileMapTest {

    @Test
    fun `a layer reads back the block placed in each cell, row by row`() {
        val layer = TileLayer.of("floor", width = 2, height = 2, blockIds = listOf("a", null, "b", "a"))

        assertEquals("a", layer.blockIdAt(0, 0))
        assertNull(layer.blockIdAt(1, 0))
        assertEquals("b", layer.blockIdAt(0, 1))
        assertEquals(listOf("a", "b"), layer.palette)
    }

    @Test
    fun `reading outside a layer is empty rather than an error`() {
        val layer = TileLayer.of("floor", 1, 1, listOf("a"))

        assertNull(layer.blockIdAt(-1, 0))
        assertNull(layer.blockIdAt(0, 1))
    }

    @Test
    fun `a map reports every block it places, ground included`() {
        val map = TileMap(
            id = "m", name = "M", width = 1, height = 1, groundBlockId = "ground",
            layers = listOf(TileLayer.of("floor", 1, 1, listOf("stone")), TileLayer.of("props", 1, 1, listOf(null))),
        )

        assertEquals(setOf("ground", "stone"), map.referencedBlockIds())
    }

    @Test
    fun `a layer the wrong size for its map is refused`() {
        assertFailsWith<IllegalArgumentException> {
            TileMap(id = "m", name = "M", width = 2, height = 2, groundBlockId = "g", layers = listOf(TileLayer.of("x", 1, 1, listOf("a"))))
        }
    }

    @Test
    fun `a layer that would poke out of the world column is refused`() {
        val tower = TileLayer.of("tower", 1, 1, listOf("a"), elevation = Chunk.HEIGHT, thickness = 1)

        assertFailsWith<IllegalArgumentException> {
            TileMap(id = "m", name = "M", width = 1, height = 1, groundBlockId = "g", layers = listOf(tower))
        }
    }

    @Test
    fun `the first player spawn marker is the spawn`() {
        val map = TileMap(
            id = "m", name = "M", width = 4, height = 4, groundBlockId = "g", layers = emptyList(),
            markers = listOf(
                MapMarker(MarkerKind.ENEMY_SPAWN, 1f, 1f, refId = "wolf"),
                MapMarker(MarkerKind.PLAYER_SPAWN, 2f, 3f),
            ),
        )

        assertEquals(2f, map.playerSpawn?.x)
        assertTrue(map.contains(3, 3))
    }
}
