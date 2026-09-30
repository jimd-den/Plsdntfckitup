package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BuildPlannerTest {

    private val origin = BlockPos(0, 0, 10)

    @Test
    fun `a single placement is one block, wherever the drag started`() {
        val plan = BuildPlanner.plan(BuildTool.SINGLE, BlockPos(5, 5, 10), origin)
        assertEquals(listOf(origin), plan)
    }

    @Test
    fun `a line snaps to the axis the drag mostly followed`() {
        // Mostly east, with a wobble north: should be a straight east run.
        val plan = BuildPlanner.plan(BuildTool.LINE, origin, BlockPos(5, 1, 10))
        assertEquals(6, plan.size)
        assertTrue(plan.all { it.y == origin.y }, "the line wobbled off axis")
        assertEquals((0..5).map { BlockPos(it, 0, 10) }, plan)
    }

    @Test
    fun `a line runs backwards too`() {
        val plan = BuildPlanner.plan(BuildTool.LINE, origin, BlockPos(-3, 0, 10))
        assertEquals(4, plan.size)
        assertTrue(plan.contains(BlockPos(-3, 0, 10)))
    }

    @Test
    fun `a floor fills the rectangle at one level`() {
        val plan = BuildPlanner.plan(BuildTool.FLOOR, origin, BlockPos(2, 3, 10))
        assertEquals(12, plan.size)
        assertTrue(plan.all { it.z == 10 })
        assertTrue(plan.contains(BlockPos(2, 3, 10)))
    }

    @Test
    fun `a floor works when dragged in any direction`() {
        val forward = BuildPlanner.plan(BuildTool.FLOOR, origin, BlockPos(2, 2, 10)).toSet()
        val backward = BuildPlanner.plan(BuildTool.FLOOR, BlockPos(2, 2, 10), origin).toSet()
        assertEquals(forward.size, backward.size)
    }

    @Test
    fun `walls are a hollow perimeter, raised`() {
        val plan = BuildPlanner.plan(BuildTool.WALLS, origin, BlockPos(4, 4, 10), height = 3)
        // The interior must be untouched, or it is a solid block not a wall.
        assertFalse(plan.contains(BlockPos(2, 2, 10)))
        assertTrue(plan.contains(BlockPos(0, 0, 10)))
        assertTrue(plan.contains(BlockPos(4, 4, 12)), "walls did not reach full height")
        assertEquals(3, plan.map { it.z }.distinct().size)
    }

    @Test
    fun `a room has a floor, a roof and a hollow inside`() {
        val plan = BuildPlanner.plan(BuildTool.ROOM, origin, BlockPos(4, 4, 10), height = 3).toSet()

        assertTrue(plan.contains(BlockPos(2, 2, 10)), "no floor under the middle")
        assertTrue(plan.contains(BlockPos(2, 2, 13)), "no roof over the middle")
        assertFalse(plan.contains(BlockPos(2, 2, 11)), "the inside was filled in")
        assertFalse(plan.contains(BlockPos(2, 2, 12)), "the inside was filled in")
    }

    @Test
    fun `a room is cut a doorway you can walk through`() {
        val plan = BuildPlanner.plan(BuildTool.ROOM, origin, BlockPos(4, 4, 10), height = 3).toSet()

        // Two blocks tall, in a wall, so a person fits.
        val wallCells = (0..4).flatMap { x -> listOf(BlockPos(x, 0, 11), BlockPos(x, 0, 12)) }
        val missing = wallCells.filterNot(plan::contains)
        assertEquals(2, missing.size, "expected a two-block doorway, found ${missing.size} gaps")
        assertEquals(missing.map { it.x }.distinct().size, 1, "the doorway was not one column")
    }

    @Test
    fun `a room too small to stand in falls back to a pad rather than a sealed box`() {
        val plan = BuildPlanner.plan(BuildTool.ROOM, origin, BlockPos(1, 1, 10))
        assertTrue(plan.all { it.z == 10 }, "a 2x2 'room' built walls around nothing")
    }

    @Test
    fun `a plan never exceeds the cap, so a mis-drag cannot flatten a region`() {
        val plan = BuildPlanner.plan(BuildTool.FLOOR, BlockPos(0, 0, 10), BlockPos(500, 500, 10))
        assertTrue(plan.size <= BuildPlanner.MAX_PLAN_SIZE)
    }

    @Test
    fun `a plan has no duplicates`() {
        val plan = BuildPlanner.plan(BuildTool.WALLS, origin, BlockPos(3, 3, 10), height = 2)
        assertEquals(plan.size, plan.distinct().size, "corners were placed twice")
    }
}

class RoomScannerTest {

    private fun world(): MutableWorld {
        val w = StreamingWorld(
            TestContent.registry,
            LayeredTerrainGenerator(
                WorldConfig(seed = 1L, simulationRadius = 1),
                TestContent.assembled.biomes,
            ),
            WorldConfig(seed = 1L, simulationRadius = 1),
        )
        w.focusOn(com.stratum.core.domain.world.ChunkPos(0, 0))
        return w
    }

    private fun MutableWorld.clear(from: BlockPos, to: BlockPos) {
        for (z in from.z..to.z) for (y in from.y..to.y) for (x in from.x..to.x) {
            setBlock(BlockPos(x, y, z), BlockRegistry.AIR_INDEX)
        }
    }

    private fun MutableWorld.build(positions: List<BlockPos>, blockId: String = TestContent.stone.id) {
        val index = TestContent.registry.indexOf(blockId)
        positions.forEach { setBlock(it, index) }
    }

    @Test
    fun `an open field is not a room`() {
        val world = world()
        world.clear(BlockPos(0, 0, 20), BlockPos(10, 10, 30))
        val scan = RoomScanner(world).scan(BlockPos(5, 5, 25))
        assertIs<RoomScan.Open>(scan)
    }

    @Test
    fun `a sealed shell is a room`() {
        val world = world()
        world.clear(BlockPos(0, 0, 20), BlockPos(8, 8, 28))
        // A 5x5 shell with no doorway.
        val shell = BuildPlanner.plan(BuildTool.ROOM, BlockPos(1, 1, 20), BlockPos(5, 5, 20), height = 3)
        world.build(shell)
        // Seal the doorway the planner cut, so this measures enclosure alone.
        world.build(listOf(BlockPos(3, 1, 21), BlockPos(3, 1, 22)))

        val scan = RoomScanner(world).scan(BlockPos(3, 3, 21))
        val room = assertIs<RoomScan.Enclosed>(scan)
        assertTrue(room.volume > 0)
        assertTrue(room.floorArea > 0)
        assertTrue(room.isHabitable, "a 5x5 room was judged uninhabitable")
    }

    @Test
    fun `a hole in the wall makes it open again`() {
        val world = world()
        world.clear(BlockPos(0, 0, 20), BlockPos(8, 8, 28))
        val shell = BuildPlanner.plan(BuildTool.ROOM, BlockPos(1, 1, 20), BlockPos(5, 5, 20), height = 3)
        world.build(shell)
        world.build(listOf(BlockPos(3, 1, 21), BlockPos(3, 1, 22)))

        assertIs<RoomScan.Enclosed>(RoomScanner(world).scan(BlockPos(3, 3, 21)))

        // Knock one block out of the wall.
        world.setBlock(BlockPos(2, 1, 21), BlockRegistry.AIR_INDEX)
        assertIs<RoomScan.Open>(RoomScanner(world).scan(BlockPos(3, 3, 21)))
    }

    @Test
    fun `a door seals the room but can still be walked through`() {
        val world = world()
        world.clear(BlockPos(0, 0, 20), BlockPos(8, 8, 28))
        val shell = BuildPlanner.plan(BuildTool.ROOM, BlockPos(1, 1, 20), BlockPos(5, 5, 20), height = 3)
        world.build(shell)

        // The planner's doorway is open, so the room leaks.
        assertIs<RoomScan.Open>(RoomScanner(world).scan(BlockPos(3, 3, 21)))

        // Fill it with a non-solid block: not air, so it seals; not solid, so
        // the player walks through it.
        world.build(listOf(BlockPos(3, 1, 21), BlockPos(3, 1, 22)), TestContent.torch.id)
        assertFalse(TestContent.torch.isSolid)

        val room = assertIs<RoomScan.Enclosed>(RoomScanner(world).scan(BlockPos(3, 3, 21)))
        assertTrue(room.hasEntrance, "a doored room reported no way in")
        assertEquals(2, room.doorways.size)
    }

    @Test
    fun `scanning from inside a block is not a room`() {
        val world = world()
        world.clear(BlockPos(0, 0, 20), BlockPos(8, 8, 28))
        world.build(listOf(BlockPos(3, 3, 21)))
        val scan = RoomScanner(world).scan(BlockPos(3, 3, 21))
        assertEquals(OpenReason.NOT_A_SPACE, assertIs<RoomScan.Open>(scan).reason)
    }

    @Test
    fun `a cupboard is enclosed but not habitable`() {
        val world = world()
        world.clear(BlockPos(0, 0, 20), BlockPos(8, 8, 28))
        // A 1x1x1 hole, fully boxed.
        val box = buildList {
            for (z in 20..22) for (y in 2..4) for (x in 2..4) add(BlockPos(x, y, z))
        }.filterNot { it == BlockPos(3, 3, 21) }
        world.build(box)

        val room = assertIs<RoomScan.Enclosed>(RoomScanner(world).scan(BlockPos(3, 3, 21)))
        assertFalse(room.isHabitable, "a one-block hole counted as a room")
    }

    @Test
    fun `scanning gives up rather than walking the whole world`() {
        val world = world()
        world.clear(BlockPos(0, 0, 20), BlockPos(15, 15, 30))
        val scan = RoomScanner(world, maxVolume = 64).scan(BlockPos(7, 7, 25))
        assertEquals(OpenReason.TOO_LARGE, assertIs<RoomScan.Open>(scan).reason)
    }
}

class SessionBuildTest {

    private fun session() = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = 12L, simulationRadius = 1),
    ).also { it.enemies = emptyList() }

    private fun WorldSession.flatten(radius: Int = 8) {
        val feet = player.blockPos
        val w = world as MutableWorld
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                w.setBlock(
                    BlockPos(feet.x + dx, feet.y + dy, feet.z - 1),
                    TestContent.registry.indexOf(TestContent.stone.id),
                )
                for (dz in 0..5) {
                    w.setBlock(BlockPos(feet.x + dx, feet.y + dy, feet.z + dz), BlockRegistry.AIR_INDEX)
                }
            }
        }
    }

    @Test
    fun `a preview places nothing`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        val from = BlockPos(feet.x + 2, feet.y, feet.z)
        val to = BlockPos(feet.x + 4, feet.y + 2, feet.z)

        session.selectBuildTool(BuildTool.FLOOR)
        val preview = session.previewBuild(from, to)

        assertTrue(preview.positions.isNotEmpty())
        assertTrue(
            preview.positions.all { session.world.blockAt(it).isAir },
            "the preview actually built something",
        )
    }

    @Test
    fun `the preview reports cost against what is held`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        session.selectBuildTool(BuildTool.FLOOR)

        val small = session.previewBuild(
            BlockPos(feet.x + 2, feet.y, feet.z),
            BlockPos(feet.x + 3, feet.y + 1, feet.z),
        )
        assertEquals(4, small.required)
        assertTrue(small.affordable, "four blocks were unaffordable from a full stack")

        val huge = session.previewBuild(
            BlockPos(feet.x + 2, feet.y, feet.z),
            BlockPos(feet.x + 40, feet.y + 40, feet.z),
        )
        assertFalse(huge.affordable, "a 40x40 floor was reported as affordable")
    }

    @Test
    fun `committing places the blocks and spends them`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        val blockId = session.player.selectedBlockId!!
        val before = session.player.countOf(blockId)

        session.selectBuildTool(BuildTool.FLOOR)
        val preview = session.previewBuild(
            BlockPos(feet.x + 2, feet.y, feet.z),
            BlockPos(feet.x + 3, feet.y + 1, feet.z),
        )
        val result = assertIs<BuildResult.Built>(session.commitBuild())

        assertEquals(4, result.placed)
        assertEquals(before - 4, session.player.countOf(blockId))
        preview.positions.forEach {
            assertFalse(session.world.blockAt(it).isAir, "a planned block was not placed")
        }
    }

    @Test
    fun `the preview clears after committing`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        session.selectBuildTool(BuildTool.SINGLE)
        session.previewBuild(feet, BlockPos(feet.x + 2, feet.y, feet.z))
        session.commitBuild()
        assertTrue(session.buildPreview.isEmpty(), "the ghost stayed on screen after building")
    }

    @Test
    fun `a build never places a block inside the player`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos

        session.selectBuildTool(BuildTool.FLOOR)
        val preview = session.previewBuild(
            BlockPos(feet.x - 2, feet.y - 2, feet.z),
            BlockPos(feet.x + 2, feet.y + 2, feet.z),
        )
        assertFalse(preview.positions.contains(feet), "a block was planned inside the player")
        assertFalse(preview.positions.contains(feet.above()))
    }

    @Test
    fun `a build skips cells that are already occupied`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        val occupied = BlockPos(feet.x + 3, feet.y, feet.z)
        (session.world as MutableWorld).setBlock(
            occupied,
            TestContent.registry.indexOf(TestContent.stone.id),
        )

        session.selectBuildTool(BuildTool.LINE)
        val preview = session.previewBuild(BlockPos(feet.x + 2, feet.y, feet.z), BlockPos(feet.x + 5, feet.y, feet.z))
        assertFalse(preview.positions.contains(occupied), "the plan included a filled cell")
    }

    @Test
    fun `running out of blocks builds what was afforded rather than nothing`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        val blockId = session.player.selectedBlockId!!
        session.player = session.player.copy(inventory = mapOf(blockId to 3))

        session.selectBuildTool(BuildTool.FLOOR)
        session.previewBuild(
            BlockPos(feet.x + 2, feet.y, feet.z),
            BlockPos(feet.x + 5, feet.y + 3, feet.z),
        )
        val result = assertIs<BuildResult.Built>(session.commitBuild())

        assertEquals(3, result.placed)
        assertTrue(result.short > 0, "a partial build did not report the shortfall")
        assertEquals(0, session.player.countOf(blockId))
    }

    @Test
    fun `committing with nothing previewed is refused`() {
        val session = session()
        session.flatten()
        assertIs<BuildResult.NothingToBuild>(session.commitBuild())
    }

    @Test
    fun `committing with an empty bag is refused rather than building for free`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        val blockId = session.player.selectedBlockId!!

        session.selectBuildTool(BuildTool.SINGLE)
        session.previewBuild(feet, BlockPos(feet.x + 2, feet.y, feet.z))
        session.player = session.player.copy(inventory = emptyMap())

        assertIs<BuildResult.OutOfBlocks>(session.commitBuild())
        assertTrue(session.world.blockAt(BlockPos(feet.x + 2, feet.y, feet.z)).isAir)
    }

    @Test
    fun `building a room and sealing its door makes the player sheltered`() {
        val session = session()
        session.flatten(radius = 10)
        val feet = session.player.blockPos
        val blockId = session.player.selectedBlockId!!
        session.player = session.player.copy(inventory = mapOf(blockId to 500))

        // Standing outside, there is no shelter.
        assertIs<RoomScan.Open>(session.shelter())

        val corner = BlockPos(feet.x + 2, feet.y + 2, feet.z)
        session.selectBuildTool(BuildTool.ROOM)
        session.previewBuild(corner, BlockPos(corner.x + 4, corner.y + 4, corner.z))
        session.commitBuild()

        // Step inside and seal the doorway behind us.
        val inside = BlockPos(corner.x + 2, corner.y + 2, corner.z + 1)
        session.player = session.player.copy(
            position = com.stratum.core.domain.world.WorldPoint.centerOf(inside),
        )
        session.selectBuildTool(BuildTool.SINGLE)
        listOf(BlockPos(corner.x + 2, corner.y, corner.z + 1), BlockPos(corner.x + 2, corner.y, corner.z + 2))
            .forEach { door ->
                session.previewBuild(door, door)
                session.commitBuild()
            }

        val shelter = session.shelter()
        assertIs<RoomScan.Enclosed>(shelter)
        assertTrue(shelter.isHabitable, "a 5x5 built room was not habitable")
    }

    @Test
    fun `the snapshot carries the ghost for the renderer`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        session.selectBuildTool(BuildTool.FLOOR)
        session.previewBuild(BlockPos(feet.x + 2, feet.y, feet.z), BlockPos(feet.x + 3, feet.y + 1, feet.z))

        val snapshot = session.snapshot()
        assertEquals(4, snapshot.buildPreview.size)
        assertEquals(BuildTool.FLOOR, snapshot.buildTool)
    }
}
