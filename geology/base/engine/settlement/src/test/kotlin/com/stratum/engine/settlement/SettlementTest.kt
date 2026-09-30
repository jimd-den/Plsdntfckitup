package com.stratum.engine.settlement

import com.stratum.core.domain.actor.PackMember
import com.stratum.core.domain.settlement.BuildingRole
import com.stratum.core.domain.settlement.BuildingTemplate
import com.stratum.core.domain.settlement.RoadGeometry
import com.stratum.core.domain.settlement.SettlementAtlas
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val grass = BlockType("t:grass", "Grass", BlockMaterial.SOIL)
private val stone = BlockType("t:stone", "Stone")
private val cobble = BlockType("t:cobble", "Cobble")
private val plank = BlockType("t:plank", "Plank")
private val thatch = BlockType("t:thatch", "Thatch")
private val registry = BlockRegistry.build(listOf(grass, stone, cobble, plank, thatch))

/** Flat grass at z = 10, so every difference in a chunk is the town's. */
private object Flat : TerrainGenerator {
    override fun generate(pos: ChunkPos, registry: BlockRegistry): Chunk = Chunk(pos).apply {
        for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
            for (z in 1..9) setBlock(x, y, z, registry.indexOf(stone.id))
            setBlock(x, y, 10, registry.indexOf(grass.id))
        }
    }
}

private val house = BuildingTemplate("t:house", "House", BuildingRole.HOUSE, 5, 5, 3, plank.id, floorBlockId = plank.id, roofBlockId = thatch.id)
private val smithy = BuildingTemplate("t:smithy", "Smithy", BuildingRole.SMITHY, 6, 5, 3, stone.id, roofBlockId = thatch.id, minCount = 1, maxCount = 1)
private val hall = BuildingTemplate("t:hall", "Hall", BuildingRole.HALL, 9, 7, 5, stone.id, roofBlockId = thatch.id)

private fun recipe(layout: String, wall: Boolean = true) = SettlementRecipe(
    id = "t:town-$layout", name = "Town", layoutId = layout, minRadius = 24, maxRadius = 30, chance = 1f,
    roadBlockId = cobble.id, foundationBlockId = stone.id, groundBlockId = grass.id,
    wallBlockId = stone.id.takeIf { wall }, buildings = listOf(house, smithy, hall),
)

private fun planner(recipe: SettlementRecipe, seed: Long = 7L) =
    SettlementPlanner(seed, listOf(recipe), biomeAt = { _, _ -> null }, groundAt = { _, _ -> 10 }, startingTown = true)

class SettlementPlanTest {

    @Test
    fun `the starting town stands on the origin, levelled to the land`() {
        val plan = assertNotNull(planner(recipe(SettlementRecipe.ORGANIC)).planFor(0, 0))

        assertEquals(0, plan.centerX)
        assertEquals(0, plan.centerY)
        assertEquals(10, plan.groundZ)
    }

    @Test
    fun `every layout packs buildings that stay inside, off the roads, and apart`() {
        listOf(SettlementRecipe.ORGANIC, SettlementRecipe.GRID, SettlementRecipe.FORTRESS, SettlementRecipe.CAMP).forEach { layout ->
            val plan = assertNotNull(planner(recipe(layout)).planFor(0, 0), layout)

            assertTrue(plan.buildings.size >= 5, "$layout placed ${plan.buildings.size}")
            assertEquals(1, plan.buildings.count { it.template.role == BuildingRole.SMITHY }, "$layout smithy is required once")
            plan.buildings.forEach { b ->
                for (y in b.y until b.y + b.depth) for (x in b.x until b.x + b.width) {
                    assertTrue(plan.distance(x, y) <= plan.radius, "$layout ${b.template.id} leaves the town at $x,$y")
                    assertTrue(plan.roads.none { RoadGeometry.covers(it, x, y) }, "$layout ${b.template.id} sits on a road at $x,$y")
                }
            }
            plan.buildings.forEachIndexed { i, a ->
                plan.buildings.drop(i + 1).forEach { b ->
                    val apart = a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.depth <= b.y || b.y + b.depth <= a.y
                    assertTrue(apart, "$layout buildings overlap")
                }
            }
        }
    }

    @Test
    fun `the centre is always an open square, where the player arrives`() {
        listOf(SettlementRecipe.ORGANIC, SettlementRecipe.GRID, SettlementRecipe.FORTRESS, SettlementRecipe.CAMP).forEach { layout ->
            val plan = planner(recipe(layout)).planFor(0, 0)!!
            assertEquals(null, plan.buildingAt(0, 0), layout)
        }
    }

    @Test
    fun `towns are the same every time and never touch each other`() {
        val a = planner(recipe(SettlementRecipe.ORGANIC), seed = 3L)
        val b = planner(recipe(SettlementRecipe.ORGANIC), seed = 3L)
        val plans = (-3..3).flatMap { cy -> (-3..3).mapNotNull { cx -> a.planFor(cx, cy) } }

        assertEquals(plans, (-3..3).flatMap { cy -> (-3..3).mapNotNull { cx -> b.planFor(cx, cy) } })
        assertTrue(plans.size > 5, "found ${plans.size}")
        plans.forEachIndexed { i, first ->
            plans.drop(i + 1).forEach { second ->
                val dx = (first.centerX - second.centerX).toFloat()
                val dy = (first.centerY - second.centerY).toFloat()
                val gap = kotlin.math.sqrt(dx * dx + dy * dy)
                assertTrue(gap > first.radius + second.radius + 2 * SettlementPlanner.BLEND, "${first.id} touches ${second.id}")
            }
        }
    }

    @Test
    fun `a recipe for one region only builds there, and density scales how many`() {
        val marsh = recipe(SettlementRecipe.CAMP).copy(biomeIds = listOf("t:marsh"), chance = 1f)
        val nowhere = SettlementPlanner(1L, listOf(marsh), biomeAt = { _, _ -> "t:hills" }, groundAt = { _, _ -> 10 })
        val sparse = SettlementPlanner(1L, listOf(recipe(SettlementRecipe.ORGANIC).copy(chance = 1f)), { _, _ -> null }, { _, _ -> 10 }, density = 0.2f)
        fun count(p: SettlementPlanner) = (-4..4).sumOf { cy -> (-4..4).count { cx -> p.planFor(cx, cy) != null } }

        assertEquals(0, count(nowhere))
        assertTrue(count(sparse) in 1..40, "was ${count(sparse)}")
    }
}

class SettlementStampTest {

    private val recipe = recipe(SettlementRecipe.ORGANIC)
    private fun terrain(): TerrainGenerator = SettlementTerrain.over(Flat, registry, seed = 7L, recipes = listOf(recipe))
    private val plan: SettlementPlan by lazy { (terrain() as SettlementAtlas).settlementAt(0, 0)!! }

    private fun blockAt(generator: TerrainGenerator, x: Int, y: Int, z: Int): String {
        val chunk = generator.generate(ChunkPos.containing(x, y), registry)
        return registry.typeOf(chunk.blockAt(Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE), z)).id
    }

    @Test
    fun `the square is paved or grassed at the town's level with open air above`() {
        val generator = terrain()
        assertTrue(blockAt(generator, 0, 0, 10) in setOf(grass.id, cobble.id))
        assertEquals(BlockType.AIR.id, blockAt(generator, 0, 0, 11))
    }

    @Test
    fun `a building has walls, a doorway you can walk through, and a roof`() {
        val generator = terrain()
        val b = plan.buildings.first()
        val cornerWall = blockAt(generator, b.x, b.y, plan.groundZ + 1)
        assertEquals(b.template.wallBlockId, cornerWall)
        assertEquals(BlockType.AIR.id, blockAt(generator, b.doorX, b.doorY, plan.groundZ + 1))
        assertEquals(BlockType.AIR.id, blockAt(generator, b.doorX, b.doorY, plan.groundZ + 2))
        val roofZ = (plan.groundZ + b.template.height + 1..Chunk.HEIGHT - 1).first { blockAt(generator, b.x, b.y, it) != BlockType.AIR.id }
        assertEquals(thatch.id, blockAt(generator, b.x, b.y, roofZ))
    }

    @Test
    fun `the wall rings the town, with gates where roads leave`() {
        val generator = terrain()
        val ringCells = (0 until 360 step 3).map { deg ->
            val a = Math.toRadians(deg.toDouble())
            val r = plan.radius - 0.4
            (kotlin.math.cos(a) * r).toInt() to (kotlin.math.sin(a) * r).toInt()
        }.distinct().filter { (x, y) -> plan.distance(x, y) > plan.radius - 1.5f }
        val walled = ringCells.count { (x, y) -> blockAt(generator, x, y, plan.groundZ + 1) == stone.id }
        val gates = ringCells.count { (x, y) -> plan.onRoad(x, y) }

        assertTrue(walled > ringCells.size / 2, "only $walled of ${ringCells.size} walled")
        assertTrue(gates > 0, "no gate")
    }

    @Test
    fun `asking where the towns are before any chunk exists plans the same towns`() {
        val asked = (terrain() as SettlementAtlas).settlementAt(0, 0)
        val generated = terrain().also { it.generate(ChunkPos(0, 0), registry) } as SettlementAtlas

        assertEquals(asked, generated.settlementAt(0, 0))
        assertEquals(10, asked?.groundZ)
    }

    @Test
    fun `land beyond the town and its blend is untouched`() {
        val far = plan.radius + SettlementPlanner.BLEND + 3
        assertEquals(grass.id, blockAt(terrain(), far, 0, 10))
    }

    @Test
    fun `chunks come out the same whatever order they are generated in`() {
        val positions = (-3..3).flatMap { y -> (-3..3).map { x -> ChunkPos(x, y) } }
        val forward = terrain().let { g -> positions.associateWith { g.generate(it, registry).exportBlocks().toList() } }
        val backward = terrain().let { g -> positions.reversed().associateWith { g.generate(it, registry).exportBlocks().toList() } }

        positions.forEach { assertEquals(forward.getValue(it), backward.getValue(it), "chunk $it") }
        assertNotEquals(Flat.generate(ChunkPos(0, 0), registry).exportBlocks().toList(), forward.getValue(ChunkPos(0, 0)))
    }

    @Test
    fun `a pack with no recipes leaves the terrain alone`() {
        assertEquals(Flat, SettlementTerrain.over(Flat, registry, 1L, emptyList()))
        // A garrisoned recipe is never the starting town when a peaceful one exists.
        val stronghold = recipe(SettlementRecipe.FORTRESS).copy(id = "t:hold", garrison = listOf(PackMember("t:orc", 3)), weight = 1000)
        val start = SettlementPlanner(1L, listOf(stronghold, recipe), { _, _ -> null }, { _, _ -> 10 }, startingTown = true).planFor(0, 0)
        assertEquals(recipe.id, start?.recipe?.id)
    }
}
