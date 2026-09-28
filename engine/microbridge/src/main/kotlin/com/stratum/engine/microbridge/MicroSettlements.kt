package com.stratum.engine.microbridge

import com.stratum.core.domain.settlement.Facing
import com.stratum.core.domain.settlement.PlacedBuilding
import com.stratum.core.domain.settlement.SettlementAtlas
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.world.TerrainContext
import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.gen.FieldKey
import com.stratum.engine.microvoxel.gen.Fields
import com.stratum.engine.microvoxel.gen.Footprint
import com.stratum.engine.microvoxel.gen.Hash
import com.stratum.engine.microvoxel.gen.HeightFunction
import com.stratum.engine.microvoxel.gen.LatticeHeight
import com.stratum.engine.microvoxel.gen.MicroGenContext
import com.stratum.engine.microvoxel.gen.MicroStage
import com.stratum.engine.microvoxel.gen.MicroStageFactory
import com.stratum.engine.microvoxel.gen.Describable
import com.stratum.engine.microvoxel.gen.StageInfo
import com.stratum.engine.microvoxel.gen.StageParam
import com.stratum.engine.microvoxel.gen.StageSetup
import com.stratum.engine.microvoxel.gen.TerrainStage
import com.stratum.engine.microvoxel.gen.TreesStage
import com.stratum.engine.settlement.HomeTown
import com.stratum.engine.settlement.SettlementLayouts
import com.stratum.engine.settlement.SettlementPlanner
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * `micro:settlements` -- the packs' own towns, the starting town among them,
 * built in microvoxels.
 *
 * Towns are planned by the same [SettlementPlanner] the block engine uses,
 * with the same seed, recipes, density and starting-town rule -- so every
 * gameplay fact about a town (where it is, its name and faction, where each
 * building and door stands, who garrisons it) is exactly what it would be on
 * blocks. Only how it *looks* changes: each building is drawn by a
 * microvoxel grammar, and the town's ground is levelled into the land at a
 * quarter block. The pack's blocks are the building material, and every
 * voxel is laid so that read back into blocks it gives the same walls,
 * doors, floors and roofs the block stamp would have.
 *
 * Because the generator then *is* the town atlas, the session does not stamp
 * towns over the land afterwards, and the home town is drawn in full detail.
 *
 * Options: `style` -- `earthen` (default: plinths, rounded mud corners, uli
 * bands in white nzu, timber doorways, lattice windows, steep thatch with
 * ragged eaves, round huts, compound walls, a sacred tree in the square) or
 * `plain` (the block buildings, cleanly bevelled); `density` scales the
 * world rules' town density; `sacredTree` plants the square's iroko.
 *
 * The home town is the player's to reshape: `homeRecipe` (a settlement id,
 * or `auto`), `homeSize` (0.5..2 times its radius), `homeLayout` (`auto` or
 * a layout id), `homeWalls` (`auto`, `on`, `off`) and `homeVariant` (another
 * roll of the same town). See [HomeTown].
 */
class SettlementsStage(private val context: TerrainContext, private val blocks: BlockMaterials, private val biomeId: (Int, Int) -> String?) : MicroStageFactory, Describable {

    override fun describe() = StageInfo(
        ID, "Towns & home", "The packs' towns and your home town, built in mud, thatch and timber.",
        listOf(
            StageParam.Choice("style", "Look", "Earthen: mud walls, uli bands, thatch and round huts. Plain: the block buildings.", listOf(EARTHEN, PLAIN), EARTHEN),
            StageParam.Number("density", "Towns", "How many towns the wilds hold, relative to the world rules.", 0f, 3f, 1f),
            StageParam.Toggle("sacredTree", "Sacred tree", "An iroko in each earthen town's square.", true),
            StageParam.Choice("homeRecipe", "Home town", "Which kind of town you begin in.", listOf(AUTO) + context.settlements.map { it.id }, AUTO),
            StageParam.Number("homeSize", "Home size", "Hamlet to city: scales the home town's radius.", 0.5f, 2f, 1f),
            StageParam.Choice("homeLayout", "Home streets", "The home town's street pattern.", listOf(AUTO) + SettlementLayouts.standard.ids, AUTO),
            StageParam.Choice("homeWalls", "Home walls", "Whether the home town is walled.", listOf(AUTO, "on", "off"), AUTO),
            StageParam.Number("homeVariant", "Home layout roll", "Another arrangement of the same town.", 0f, 20f, 0f, 1f),
        ),
    )

    override fun create(setup: StageSetup): MicroStage {
        val o = setup.options
        val style = o.string("style", EARTHEN)
        require(style == EARTHEN || style == PLAIN) { "Stage '$ID' option 'style' is '$EARTHEN' or '$PLAIN', not '$style'" }
        val fields = setup.fields
        val natural = fields.require(Fields.SURFACE)
        val rules = context.config.rules
        val home = homeOf(o)
        val planner = SettlementPlanner(
            seed = context.config.seed,
            recipes = context.settlements,
            biomeAt = biomeId,
            groundAt = { x, y -> floor(natural.heightAt(x * R + R / 2, y * R + R / 2) / R).toInt() },
            startingTown = rules.startInTown,
            density = rules.townDensity * o.float("density", 1f).coerceIn(0f, 3f),
            welcoming = context.welcoming,
            home = home,
        )
        val sacred = o.boolean("sacredTree", true)
        fields.publish(KEY, planner)

        // The ground: flat at each town's level inside it, easing back into the land over the blend ring.
        fields.publish(Fields.SURFACE, LatticeHeight(HeightFunction { mx, my -> levelled(planner, natural, mx, my) }, R))

        val previous = fields.get(Fields.FOOTPRINT)
        fields.publish(Fields.FOOTPRINT, TownFootprint(planner, previous))

        val grammar = TownGrammar(setup.palette, blocks, style == EARTHEN)
        return MicroStage { ctx -> build(ctx, planner, grammar, sacred) }
    }

    /** The home-town options, checked: an unknown recipe or layout is an error, not a silent default. */
    private fun homeOf(o: com.stratum.engine.microvoxel.gen.StageOptions): HomeTown {
        val recipe = o.string("homeRecipe", AUTO).takeIf { it != AUTO }
        require(recipe == null || context.settlements.any { it.id == recipe }) {
            "Stage '$ID' option 'homeRecipe' '$recipe' is not a settlement. Known: ${context.settlements.joinToString { it.id }}"
        }
        val layout = o.string("homeLayout", AUTO).takeIf { it != AUTO }
        require(layout == null || layout in SettlementLayouts.standard.ids) {
            "Stage '$ID' option 'homeLayout' '$layout' is not a layout. Known: ${SettlementLayouts.standard.ids.joinToString()}"
        }
        val walls = when (val w = o.string("homeWalls", AUTO)) {
            AUTO -> null; "on" -> true; "off" -> false
            else -> throw IllegalArgumentException("Stage '$ID' option 'homeWalls' is auto, on or off, not '$w'")
        }
        return HomeTown(recipe, o.float("homeSize", 1f).coerceIn(0.5f, 2f), layout, walls, o.int("homeVariant", 0))
    }

    private fun levelled(planner: SettlementPlanner, natural: HeightFunction, mx: Int, my: Int): Float {
        val h = natural.heightAt(mx, my)
        val plan = planOf(planner, Math.floorDiv(mx, R), Math.floorDiv(my, R)) ?: return h
        val flat = (plan.groundZ * R + R - 1) + 0.5f
        val d = microDistance(plan, mx, my)
        if (d <= plan.radius) return flat
        val t = TerrainStage.smooth(0f, 1f, ((d - plan.radius) / SettlementPlanner.BLEND).coerceIn(0f, 1f))
        return flat + (h - flat) * t
    }

    private fun build(ctx: MicroGenContext, planner: SettlementPlanner, g: TownGrammar, sacred: Boolean) {
        val bx0 = Math.floorDiv(ctx.x0, R); val by0 = Math.floorDiv(ctx.y0, R)
        val span = MicroChunk.SIZE / R
        val plans = planner.settlementsNear(bx0 + span / 2, by0 + span / 2, span)
        for (plan in plans) {
            val floorTop = plan.groundZ * R + R - 1
            val tallest = (plan.buildings.maxOfOrNull { it.template.height } ?: 0).coerceAtLeast(plan.recipe.wallHeight)
            if (ctx.z1 < floorTop - 4 || ctx.z0 > (plan.groundZ + tallest + 12) * R) continue
            ground(ctx, plan, g, bx0, by0, span)
            for (b in plan.buildings) {
                val x0 = b.x * R - EAVES; val x1 = (b.x + b.width) * R - 1 + EAVES
                val y0 = b.y * R - EAVES; val y1 = (b.y + b.depth) * R - 1 + EAVES
                val zTop = (plan.groundZ + 1 + b.template.height) * R + max(b.width, b.depth) * R / 2 + 6
                if (!ctx.overlaps(x0, y0, floorTop, x1, y1, zTop)) continue
                for (z in max(ctx.z0, floorTop + 1)..min(ctx.z1, zTop))
                    for (y in max(ctx.y0, y0)..min(ctx.y1, y1))
                        for (x in max(ctx.x0, x0)..min(ctx.x1, x1)) {
                            val v = g.building(b, plan, x, y, z)
                            if (v != KEEP) ctx.set(x, y, z, v)
                        }
                furniture(ctx, plan, b, g)
            }
            if (g.earthen && sacred) sacredTree(ctx, plan)
        }
    }

    /** Roads, floors and yards levelled at the town's height, and the compound wall. */
    private fun ground(ctx: MicroGenContext, plan: SettlementPlan, g: TownGrammar, bx0: Int, by0: Int, span: Int) {
        val floorTop = plan.groundZ * R + R - 1
        val road = blocks[plan.recipe.roadBlockId]
        // Earthen compounds have swept laterite yards: the town's own ground, else its foundation earth.
        val yard = blocks[plan.recipe.groundBlockId] ?: if (g.earthen) blocks[plan.recipe.foundationBlockId] else null
        val foundation = blocks[plan.recipe.foundationBlockId]
        val wall = blocks[plan.recipe.wallBlockId]
        for (by in by0 until by0 + span) for (bx in bx0 until bx0 + span) {
            val d = plan.distance(bx, by)
            if (d > plan.radius + SettlementPlanner.BLEND) continue
            val inside = d <= plan.radius
            val onRoad = plan.onRoad(bx, by)
            val building = if (inside) plan.buildingAt(bx, by) else null
            val top = when {
                building != null -> blocks[building.template.floorBlockId] ?: foundation
                onRoad -> road
                inside -> yard
                else -> null
            }
            for (ly in 0 until R) for (lx in 0 until R) {
                val mx = bx * R + lx; val my = by * R + ly
                val surface = if (inside) floorTop else floor(ctx.fields.require(Fields.SURFACE).heightAt(mx, my)).toInt()
                if (top != null) {
                    val solidTop = blocks.blockOf(top)?.isSolid != false
                    if (solidTop) {
                        for (z in surface - 1..surface) if (z in ctx.z0..ctx.z1) ctx.set(mx, my, z, top)
                        // Footworn patches in a swept yard, so it reads as earth and not as a floor.
                        if (g.earthen && top == yard && building == null && surface in ctx.z0..ctx.z1 &&
                            Hash.unit(ctx.fields.seed, mx, my, 0, 64) < 0.18f
                        ) ctx.set(mx, my, surface, g.beaten)
                    } else if (surface + 1 in ctx.z0..ctx.z1) ctx.set(mx, my, surface + 1, top) // a rug or boardwalk lies on the ground
                }
                // Beaten earth softens a road's edge into the yard beside it, a quarter block at a time.
                if (g.earthen && onRoad && top != null && !plan.onRoad(Math.floorDiv(mx + EDGE_X[lx], R), Math.floorDiv(my + EDGE_Y[ly], R)) &&
                    Hash.unit(ctx.fields.seed, mx, my, 0, 61) < 0.5f && surface in ctx.z0..ctx.z1
                ) ctx.set(mx, my, surface, g.beaten)
                if (inside && wall != null && plan.walled && !onRoad && d > plan.radius - WALL_THICKNESS) {
                    val height = plan.recipe.wallHeight * R + if (g.earthen) 1 else 0
                    for (z in surface + 1..surface + height) if (z in ctx.z0..ctx.z1) ctx.set(mx, my, z, wall)
                    // Earthen compound walls carry a thatch coping against the rain.
                    if (g.earthen && surface + height + 1 in ctx.z0..ctx.z1 && (lx == 1 || lx == 2 || ly == 1 || ly == 2))
                        ctx.set(mx, my, surface + height + 1, g.thatchDark)
                }
            }
        }
    }

    /** Furniture stands in the middle of the room, a whole block so it reads back as itself (a brazier stays a brazier). */
    private fun furniture(ctx: MicroGenContext, plan: SettlementPlan, b: PlacedBuilding, g: TownGrammar) {
        val m = blocks[b.template.furnitureBlockId] ?: return
        val fx = (b.x + b.width / 2) * R; val fy = (b.y + b.depth / 2) * R; val fz = (plan.groundZ + 1) * R
        ctx.fill(fx, fy, fz, fx + R - 1, fy + R - 1, fz + R - 1, m)
    }

    /** An iroko in the village square, where nothing else stands at the centre: the tree the village meets under. */
    private fun sacredTree(ctx: MicroGenContext, plan: SettlementPlan) {
        for (dy in -1..1) for (dx in -1..1) {
            if (plan.buildingAt(plan.centerX + dx, plan.centerY + dy) != null || plan.onRoad(plan.centerX + dx, plan.centerY + dy)) return
        }
        TreesStage.plant(ctx, TreesStage.Kind.IROKO, plan.centerX * R + R / 2, plan.centerY * R + R / 2, plan.groundZ * R + R, Hash.mix(ctx.fields.seed, plan.centerX, plan.centerY))
    }

    /** Towns own their ground: no trees in the streets, no tufts on a floor. */
    private inner class TownFootprint(private val planner: SettlementPlanner, private val previous: Footprint?) : Footprint {
        override fun urban(x: Int, y: Int): Float {
            val plan = planOf(planner, Math.floorDiv(x, R), Math.floorDiv(y, R)) ?: return previous?.urban(x, y) ?: 0f
            return if (plan.distance(Math.floorDiv(x, R), Math.floorDiv(y, R)) <= plan.radius) 1f else 0.6f
        }

        override fun isOccupied(x: Int, y: Int): Boolean {
            val bx = Math.floorDiv(x, R); val by = Math.floorDiv(y, R)
            val plan = planOf(planner, bx, by)
            if (plan != null && occupied(plan, bx, by)) return true
            return previous?.isOccupied(x, y) ?: false
        }

        override fun occupancyMask(cx: Int, cy: Int): BooleanArray? {
            val s = MicroChunk.SIZE
            val out = previous?.occupancyMask(cx, cy)?.copyOf() ?: BooleanArray(s * s)
            val bx0 = cx * s / R; val by0 = cy * s / R; val span = s / R
            val plans = planner.settlementsNear(bx0 + span / 2, by0 + span / 2, span)
            if (plans.isEmpty()) return if (previous == null) null else out
            for (by in 0 until span) for (bx in 0 until span) {
                if (plans.none { occupied(it, bx0 + bx, by0 + by) }) continue
                for (ly in 0 until R) for (lx in 0 until R) out[(by * R + ly) * s + bx * R + lx] = true
            }
            return out
        }

        /** Everything inside a town is its own: roads, houses and swept yards -- nothing grows there. */
        private fun occupied(plan: SettlementPlan, bx: Int, by: Int): Boolean {
            val d = plan.distance(bx, by)
            if (d > plan.radius + SettlementPlanner.BLEND) return false
            return d <= plan.radius || plan.onRoad(bx, by)
        }
    }

    companion object {
        const val ID = "micro:settlements"
        const val EARTHEN = "earthen"
        const val PLAIN = "plain"
        const val AUTO = "auto"
        val KEY = FieldKey<SettlementAtlas>("settlements")

        internal const val R = MicrovoxelTerrainGenerator.MICRO_PER_BLOCK
        internal const val KEEP: Short = Short.MIN_VALUE
        internal const val EAVES = 3
        private const val WALL_THICKNESS = 1.5f
        private val EDGE_X = intArrayOf(-1, 0, 0, 1)
        private val EDGE_Y = intArrayOf(-1, 0, 0, 1)

        internal fun planOf(planner: SettlementPlanner, bx: Int, by: Int): SettlementPlan? =
            planner.settlementsNear(bx, by, 0).firstOrNull()

        /** Distance from a town's centre at quarter-block precision, square or round as the town is. */
        internal fun microDistance(plan: SettlementPlan, mx: Int, my: Int): Float {
            val dx = (mx + 0.5f) / R - (plan.centerX + 0.5f)
            val dy = (my + 0.5f) / R - (plan.centerY + 0.5f)
            return if (plan.square) max(abs(dx), abs(dy)) else sqrt(dx * dx + dy * dy)
        }
    }
}

/**
 * How one town building looks, voxel by voxel: a function of the plan, so
 * any chunk can draw its part. Every choice keeps the block reading intact:
 * a wall cell stays at least three-quarters full, a door cell empty to two
 * blocks high, a window cell the window block.
 */
internal class TownGrammar(palette: MaterialPalette, private val blocks: BlockMaterials, val earthen: Boolean) {
    private val R = SettlementsStage.R
    private val KEEP = SettlementsStage.KEEP
    private val AIR = MaterialPalette.AIR
    val thatch = palette.id(M.THATCH)
    val thatchDark = palette.id(M.THATCH_DARK)
    private val mudDark = palette.id(M.MUD_DARK)
    private val nzu = palette.id(M.NZU)
    private val timber = palette.id(M.TIMBER)
    val beaten = palette.id(M.BEATEN_EARTH)

    fun building(b: PlacedBuilding, plan: SettlementPlan, x: Int, y: Int, z: Int): Short =
        if (earthen && isRound(b)) roundHut(b, plan, x, y, z) else house(b, plan, x, y, z)

    /** Small square houses with the door mid-wall become round huts under a conical thatch. */
    private fun isRound(b: PlacedBuilding): Boolean {
        if (b.width != b.depth || b.width !in 3..5) return false
        val centred = when (b.door) {
            Facing.NORTH, Facing.SOUTH -> b.doorX == b.x + b.width / 2
            Facing.EAST, Facing.WEST -> b.doorY == b.y + b.depth / 2
        }
        return centred && (b.x * 31 + b.y * 17) and 1 == 0
    }

    private fun house(b: PlacedBuilding, plan: SettlementPlan, x: Int, y: Int, z: Int): Short {
        val x0 = b.x * R; val x1 = (b.x + b.width) * R - 1; val y0 = b.y * R; val y1 = (b.y + b.depth) * R - 1
        val base = (plan.groundZ + 1) * R
        val wallTop = base + b.template.height * R - 1
        val wallMat = blocks[b.template.wallBlockId] ?: blocks[plan.recipe.foundationBlockId] ?: return KEEP
        val inside = x in x0..x1 && y in y0..y1
        if (z > wallTop) return roof(b, x, y, z, wallTop, wallMat)
        if (!inside) {
            // A plinth: a raised mud step around the foot of the wall.
            val out = max(max(x0 - x, x - x1), max(y0 - y, y - y1))
            return if (earthen && out == 1 && z < base + 2 && !doorAt(b, x, y)) mudDark else KEEP
        }
        val dW = x - x0; val dE = x1 - x; val dN = y - y0; val dS = y1 - y
        val e = min(min(dW, dE), min(dN, dS))
        if (e > 2) return AIR // the room
        // Rounded outer corners, a quarter block.
        if (earthen && e == 0 && min(dW, dE) + min(dN, dS) == 0) return AIR
        val h = z - base
        if (doorAt(b, x, y) && h < 2 * R) return AIR
        if (doorFrame(b, x, y) && h <= 2 * R && e <= 1) return timber
        if (doorAt(b, x, y) && h in 2 * R..2 * R + 1) return timber // lintel
        val bx = Math.floorDiv(x, R); val by = Math.floorDiv(y, R)
        val window = blocks[b.template.windowBlockId]
        if (window != null && Math.floorDiv(z, R) == plan.groundZ + 2 && (bx + by) % 3 == 0 && !cornerCell(b, bx, by)) {
            // A lattice window: the window block, pierced in a checker on its face.
            return if (e == 0 && ((x + y + z) and 1) == 0) AIR else window
        }
        if (earthen && e == 0 && wallTop - base >= 8) {
            // Uli: a white zigzag of nzu at shoulder height over a dark dado, as painted on Igbo compound walls.
            if (h == 3) return mudDark
            // A zigzag three voxels high: down on 0, middle on 1 and 3, up on 2.
            val along = Math.floorMod(if (dN == 0 || dS == 0) x else y, 4)
            if ((h == 5 && along == 0) || (h == 6 && (along == 1 || along == 3)) || (h == 7 && along == 2)) return nzu
        }
        return wallMat
    }

    private fun roof(b: PlacedBuilding, x: Int, y: Int, z: Int, wallTop: Int, wallMat: Short): Short {
        val roofMat = blocks[b.template.roofBlockId] ?: if (earthen) thatch else return KEEP
        val eaves = if (earthen) SettlementsStage.EAVES else 1
        val x0 = b.x * R; val x1 = (b.x + b.width) * R - 1; val y0 = b.y * R; val y1 = (b.y + b.depth) * R - 1
        val ridgeAlongY = b.width <= b.depth
        val a = if (ridgeAlongY) x - x0 else y - y0 // across the ridge
        val span = if (ridgeAlongY) x1 - x0 + 1 else y1 - y0 + 1
        val along = if (ridgeAlongY) y - y0 else x - x0
        val length = if (ridgeAlongY) y1 - y0 + 1 else x1 - x0 + 1
        if (a < -eaves || a >= span + eaves || along < -2 || along >= length + 2) return KEEP
        val rise = min(a, span - 1 - a)
        val roofZ = wallTop + 1 + rise
        val dz = z - roofZ
        if (dz in -2..0) {
            // Ragged eaves: the lowest thatch hangs unevenly, as thatch does.
            if (earthen && (a == -eaves || a == span - 1 + eaves) && Hash.unit(0L, x, y, z, 62) < 0.35f) return AIR
            // Courses of thatch: a lighter band every third layer, as bundles are laid.
            return if (earthen && (z / 3) % 2 == 0) thatch else roofMat
        }
        // The ridge cap, and a knot of thatch at each end.
        if (earthen && dz == 1 && rise >= (span - 1) / 2) return if (along == 0 || along == length - 1) thatchDark else if ((along and 1) == 0) thatchDark else KEEP
        if (dz < -2 && a in 0 until span && along in 0 until length) {
            val gableEnd = along <= 2 || along >= length - 3
            return if (gableEnd) wallMat else AIR
        }
        return KEEP
    }

    private fun roundHut(b: PlacedBuilding, plan: SettlementPlan, x: Int, y: Int, z: Int): Short {
        val cx = (b.x + b.width / 2f) * R - 0.5f; val cy = (b.y + b.depth / 2f) * R - 0.5f
        val radius = b.width * R / 2f - 0.5f
        val base = (plan.groundZ + 1) * R
        val wallTop = base + b.template.height * R - 1
        val wallMat = blocks[b.template.wallBlockId] ?: blocks[plan.recipe.foundationBlockId] ?: return KEEP
        val dx = x - cx; val dy = y - cy
        val d = sqrt(dx * dx + dy * dy)
        if (z > wallTop) {
            val roofMat = blocks[b.template.roofBlockId] ?: thatch
            val coneZ = wallTop + 1 + (radius + SettlementsStage.EAVES - d) * 1.15f
            if (d > radius + SettlementsStage.EAVES) return KEEP
            val dz = z - coneZ
            if (d < 1.2f && z <= coneZ + 4) return thatchDark // the finial
            if (dz in -2.5f..0.5f) {
                if (d > radius + SettlementsStage.EAVES - 1 && Hash.unit(0L, x, y, z, 63) < 0.35f) return AIR
                return if ((z / 3) % 2 == 0) thatch else roofMat
            }
            return if (dz < -2.5f && d < radius - 2) AIR else KEEP
        }
        if (d > radius + 1) return KEEP
        if (d > radius) return if (z < base + 2) mudDark else KEEP // plinth
        if (d < radius - 3) return AIR
        val h = z - base
        val facing = atan2(dy, dx)
        val doorAngle = atan2(b.door.dy.toFloat(), b.door.dx.toFloat())
        var off = abs(facing - doorAngle); if (off > Math.PI) off = (2 * Math.PI - off).toFloat()
        if (off < 2.2f / radius && h < 2 * R) return AIR
        if (off < 3.2f / radius && h <= 2 * R) return timber
        if (h == 3) return mudDark
        if ((h == 5 || h == 6) && Math.floorMod((facing * radius).toInt() + h, 4) == 0) return nzu
        return wallMat
    }

    /** The door cell, the whole block, to the full depth of the wall. */
    private fun doorAt(b: PlacedBuilding, x: Int, y: Int): Boolean = Math.floorDiv(x, R) == b.doorX && Math.floorDiv(y, R) == b.doorY

    /** The quarter-block jambs either side of the door. */
    private fun doorFrame(b: PlacedBuilding, x: Int, y: Int): Boolean {
        val bx = Math.floorDiv(x, R); val by = Math.floorDiv(y, R)
        return when (b.door) {
            Facing.NORTH, Facing.SOUTH -> by == b.doorY && (x == b.doorX * R - 1 || x == b.doorX * R + R)
            Facing.EAST, Facing.WEST -> bx == b.doorX && (y == b.doorY * R - 1 || y == b.doorY * R + R)
        }
    }

    private fun cornerCell(b: PlacedBuilding, bx: Int, by: Int): Boolean =
        (bx == b.x || bx == b.x + b.width - 1) && (by == b.y || by == b.y + b.depth - 1)
}
