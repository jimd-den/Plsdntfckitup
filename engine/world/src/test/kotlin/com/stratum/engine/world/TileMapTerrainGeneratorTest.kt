package com.stratum.engine.world

import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.ContentPackException
import com.stratum.core.domain.map.MapMarker
import com.stratum.core.domain.map.MarkerKind
import com.stratum.core.domain.map.TileLayer
import com.stratum.core.domain.map.TileMap
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TileMapTerrainGeneratorTest {

    /**
     * A 5x5 room: stone floor, a wall along the top row, the player spawn in
     * the middle.
     */
    private val room = TileMap(
        id = "test:room",
        name = "Room",
        width = 5,
        height = 5,
        groundBlockId = TestContent.soil.id,
        groundLevel = 6,
        layers = listOf(
            TileLayer.of("floor", 5, 5, List(25) { TestContent.stone.id }),
            TileLayer.of("walls", 5, 5, List(25) { cell -> TestContent.wall.id.takeIf { cell < 5 } }, elevation = 1, thickness = 2),
        ),
        markers = listOf(MapMarker(MarkerKind.PLAYER_SPAWN, x = 2.5f, y = 2.5f)),
    )

    private val content = ContentPackAssembler().assemble(
        listOf(TestContent.pack.copy(maps = listOf(room), terrain = TerrainRecipe.tileMap(room.id))),
    )

    private fun session() = WorldSession(content, WorldConfig(seed = 1L, simulationRadius = 1))

    @Test
    fun `a pack naming the tile map generator plays on its map`() {
        val world = session().world

        // The spawn cell (2,2) is the world origin, so the map's (0,0) is at (-2,-2).
        assertEquals(TestContent.stone.id, world.blockAt(BlockPos(0, 0, 6)).id)
        assertEquals(TestContent.soil.id, world.blockAt(BlockPos(0, 0, 5)).id)
        assertEquals(BlockType.BEDROCK.id, world.blockAt(BlockPos(0, 0, 0)).id)
    }

    @Test
    fun `layers stand at their elevation and thickness`() {
        val world = session().world
        val topRow = -2

        assertEquals(TestContent.wall.id, world.blockAt(BlockPos(0, topRow, 7)).id)
        assertEquals(TestContent.wall.id, world.blockAt(BlockPos(0, topRow, 8)).id)
        assertTrue(world.blockAt(BlockPos(0, topRow, 9)).isAir)
        assertTrue(world.blockAt(BlockPos(0, 0, 7)).isAir, "the wall layer only fills the cells it names")
    }

    @Test
    fun `the player spawns on the map's spawn marker`() {
        val feet = session().player.blockPos

        assertEquals(0, feet.x)
        assertEquals(0, feet.y)
        assertEquals(7, feet.z)
    }

    @Test
    fun `ground beyond the map rises into a wall`() {
        val world = session().world
        val outside = world.surfaceAt(10, 0)

        assertEquals(room.groundLevel + TileMapTerrainGenerator.BORDER_HEIGHT, outside)
    }

    @Test
    fun `a map placing a block no pack defines is rejected at load`() {
        val broken = TileMap(
            id = "test:broken", name = "Broken", width = 1, height = 1,
            groundBlockId = TestContent.soil.id,
            layers = listOf(TileLayer.of("floor", 1, 1, listOf("nobody:marble"))),
        )

        val failure = assertFailsWith<ContentPackException> {
            ContentPackAssembler().assemble(listOf(TestContent.pack.copy(maps = listOf(broken))))
        }
        assertTrue("nobody:marble" in failure.message.orEmpty())
    }

    @Test
    fun `a recipe naming a map nobody loaded says which maps exist`() {
        val orphan = ContentPackAssembler().assemble(
            listOf(TestContent.pack.copy(maps = listOf(room), terrain = TerrainRecipe.tileMap("test:elsewhere"))),
        )

        val failure = assertFailsWith<IllegalArgumentException> { WorldSession(orphan, WorldConfig()) }
        assertTrue(room.id in failure.message.orEmpty())
    }
}

class MapEncounterTest {

    private fun roomWith(vararg markers: MapMarker) = TileMap(
        id = "test:arena", name = "Arena", width = 9, height = 9,
        groundBlockId = TestContent.soil.id, groundLevel = 6,
        layers = listOf(TileLayer.of("floor", 9, 9, List(81) { TestContent.stone.id })),
        markers = listOf(MapMarker(MarkerKind.PLAYER_SPAWN, 4.5f, 4.5f)) + markers,
    )

    private fun session(map: TileMap) = WorldSession(
        ContentPackAssembler().assemble(listOf(TestContent.pack.copy(maps = listOf(map), terrain = TerrainRecipe.tileMap(map.id)))),
        WorldConfig(seed = 3L, simulationRadius = 1),
    )

    @Test
    fun `an enemy marker naming a monster places that monster where it was marked`() {
        val wanted = TestContent.pack.enemies.first()
        val session = session(roomWith(MapMarker(MarkerKind.ENEMY_SPAWN, 7.5f, 4.5f, refId = wanted.name.uppercase())))
        val enemy = session.enemies.single()

        assertEquals(wanted.id, enemy.definitionId)
        assertEquals(session.player.position.x + 3f, enemy.position.x, "three blocks east of the spawn, as marked")
        assertEquals(7f, enemy.position.z, "standing on the floor")
    }

    @Test
    fun `an enemy marker naming nothing still becomes a fight`() {
        val session = session(roomWith(MapMarker(MarkerKind.ENEMY_SPAWN, 1.5f, 1.5f), MapMarker(MarkerKind.ENEMY_SPAWN, 2.5f, 1.5f)))

        assertEquals(2, session.enemies.size)
    }
}
