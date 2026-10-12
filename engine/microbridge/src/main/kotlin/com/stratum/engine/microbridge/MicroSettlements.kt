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
import com.stratum.engine.microvoxel.arch.ArchPalette
import com.stratum.engine.microvoxel.arch.Building
import com.stratum.engine.microvoxel.arch.PlainBuildings
import com.stratum.engine.microvoxel.arch.GenomeRules
import com.stratum.engine.microvoxel.arch.MaterialFamilies
import com.stratum.engine.microvoxel.arch.Monuments
import com.stratum.engine.microvoxel.arch.ParametricTradition
import com.stratum.engine.microvoxel.arch.PlanShape
import com.stratum.engine.microvoxel.arch.RoofForm
import com.stratum.engine.microvoxel.arch.Vernacular
import com.stratum.engine.microvoxel.arch.Side
import com.stratum.engine.microvoxel.arch.Tradition
import com.stratum.engine.microvoxel.arch.Traditions
import com.stratum.engine.microvoxel.arch.WallStyle
import com.stratum.engine.microvoxel.geo.GeoField
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
 * Options: `style` -- `regional` (default: each town in the building
 * tradition of the land it stands on; see [Traditions]), one tradition's id
 * for every town (`earthen` is the old name for `igbo`), or `plain` (the
 * block buildings, cleanly bevelled); `homeStyle` sets the home town's apart;
 * `density` scales the world rules' town density; `sacredTree` raises what
 * stands at each town's heart (a sacred tree, a stele, a conical tower...).
 *
 * The home town is the player's to reshape: `homeRecipe` (a settlement id,
 * or `auto`), `homeSize` (0.5..2 times its radius), `homeLayout` (`auto` or
 * a layout id), `homeWalls` (`auto`, `on`, `off`) and `homeVariant` (another
 * roll of the same town). See [HomeTown].
 */
class SettlementsStage(private val context: TerrainContext, private val blocks: BlockMaterials, private val biomeId: (Int, Int) -> String?) : MicroStageFactory, Describable {

    override fun describe() = StageInfo(
        ID, "Towns & home", "The packs' towns and your home town, each built in the tradition of the land it stands on.",
        listOf(
            StageParam.Choice(
                "style", "Building tradition",
                "Regional: each town builds as the people of its land do (Sudano-Sahelian on the Sahel, Swahili on the coast, Great Zimbabwe " +
                    "among the granite domes...). Or one tradition everywhere. Plain: the block buildings.",
                listOf(REGIONAL) + Traditions.ids + ParametricTradition.ID + PLAIN, REGIONAL,
            ),
            StageParam.Number(
                "parametric", "Invented towns",
                "In regional mode, the share of towns that invent variations within their land's own grammar -- " +
                    "Kano's courtyards and pinnacles, Lamu's storeyed coral houses, Zimbabwe's round dry stone -- rolled per building.", 0f, 1f, 0.35f,
            ),
            StageParam.Choice("plans", "Plans", "Parametric buildings' plans (any, or a comma list).", listOf(GenomeRules.ANY) + PlanShape.entries.map { it.name.lowercase() }, GenomeRules.ANY),
            StageParam.Choice("roofs", "Roofs", "Parametric buildings' roofs (any, or a comma list).", listOf(GenomeRules.ANY) + RoofForm.entries.map { it.name.lowercase() }, GenomeRules.ANY),
            StageParam.Choice("materials", "Materials", "Parametric buildings' material families (any, or a comma list).", listOf(GenomeRules.ANY) + MaterialFamilies.ids, GenomeRules.ANY),
            StageParam.Choice("storeys", "Upper storeys", "Storeys above the ground floor: a number or a range.", listOf("0-2", "0", "1", "0-1", "1-3", "2-4", "3-5"), "0-2"),
            StageParam.Number("ornament", "Ornament", "Bare walls to every building dressed in all it can carry.", 0f, 1f, 0.5f),
            StageParam.Number("towers", "Towers", "How often a parametric building raises towers.", 0f, 1f, 0.2f),
            StageParam.Number("variety", "Variety", "How far each building strays from its town's shared look.", 0f, 1f, 0.45f),
            StageParam.Choice("homeStyle", "Home tradition", "How your home town is built; auto follows its land.", listOf(AUTO) + Traditions.ids + ParametricTradition.ID, AUTO),
            StageParam.Number("density", "Towns", "How many towns the wilds hold, relative to the world rules.", 0f, 3f, 1f),
            StageParam.Toggle("sacredTree", "Town heart", "What stands at each town's centre: a sacred tree, a stele, a tower, a kraal.", true),
            StageParam.Choice("homeRecipe", "Home town", "Which kind of town you begin in.", listOf(AUTO) + context.settlements.map { it.id }, AUTO),
            StageParam.Number("homeSize", "Home size", "Hamlet to city: scales the home town's radius.", 0.5f, 2f, 1f),
            StageParam.Choice("homeLayout", "Home streets", "The home town's street pattern.", listOf(AUTO) + SettlementLayouts.standard.ids, AUTO),
            StageParam.Choice("homeWalls", "Home walls", "Whether the home town is walled.", listOf(AUTO, "on", "off"), AUTO),
            StageParam.Number("homeVariant", "Home layout roll", "Another arrangement of the same town.", 0f, 20f, 0f, 1f),
        ),
    )

    override fun create(setup: StageSetup): MicroStage {
        val o = setup.options
        val style = o.string("style", REGIONAL).let { if (it == EARTHEN) "igbo" else it }
        require(style == REGIONAL || style == PLAIN || style == ParametricTradition.ID || style in Traditions.ids) {
            "Stage '$ID' option 'style' is $REGIONAL, $PLAIN, ${ParametricTradition.ID} or a tradition (${Traditions.ids.joinToString()}), not '$style'"
        }
        val genome = GenomeRules.parse { key -> o.string(key, "") }
        val invented = o.float("parametric", 0.35f).coerceIn(0f, 1f)
        val homeStyle = o.string("homeStyle", AUTO).let { if (it == EARTHEN) "igbo" else it }
        require(homeStyle == AUTO || homeStyle == ParametricTradition.ID || homeStyle in Traditions.ids || homeStyle.removePrefix("parametric:") in Traditions.ids) { "Stage '$ID' option 'homeStyle' is $AUTO or a tradition, not '$homeStyle'" }
        val fields = setup.fields
        val natural = fields.require(Fields.SURFACE)
        val rules = context.config.rules
        val home = homeOf(o)
        val geology = fields.get(Fields.GEOLOGY)
        val planner = SettlementPlanner(
            seed = context.config.seed,
            recipes = context.settlements,
            biomeAt = biomeId,
            groundAt = { x, y -> floor(natural.heightAt(x * R + R / 2, y * R + R / 2) / R).toInt() },
            startingTown = rules.startInTown,
            density = rules.townDensity * o.float("density", 1f).coerceIn(0f, 3f),
            welcoming = context.welcoming,
            home = home,
            // A town laid out by its people's ways follows the same people it is drawn as.
            cultureAt = { x, y ->
                val chosen = if (x == 0 && y == 0 && homeStyle != AUTO) homeStyle.removePrefix("parametric:") else style
                when (chosen) {
                    REGIONAL -> Traditions.choose(geology?.provinceAt(x * R, y * R)?.id, Hash.mix(0L, x, y, 0, 97))
                    else -> chosen.takeIf { it in Traditions.ids }
                }
            },
        )
        val sacred = o.boolean("sacredTree", true)
        fields.publish(KEY, planner)

        // The ground: flat at each town's level inside it, easing back into the land over the blend ring.
        fields.publish(Fields.SURFACE, LatticeHeight(HeightFunction { mx, my -> levelled(planner, natural, mx, my) }, R))

        val previous = fields.get(Fields.FOOTPRINT)
        fields.publish(Fields.FOOTPRINT, TownFootprint(planner, previous))

        val grammar = TownGrammar(setup.palette, blocks, style, homeStyle, fields.get(Fields.GEOLOGY), genome, invented)
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
            if (ctx.z1 < floorTop - 4 || ctx.z0 > (plan.groundZ + tallest + 16) * R) continue
            val tradition = g.traditionOf(plan)
            ground(ctx, plan, g, tradition, bx0, by0, span)
            for (b in plan.buildings) {
                val spec = g.spec(b, plan)
                val reach = tradition.reach
                val x0 = spec.x0 - reach; val x1 = spec.x1 + reach
                val y0 = spec.y0 - reach; val y1 = spec.y1 + reach
                val zTop = tradition.top(spec)
                if (!ctx.overlaps(x0, y0, floorTop, x1, y1, zTop)) continue
                for (z in max(ctx.z0, floorTop + 1)..min(ctx.z1, zTop))
                    for (y in max(ctx.y0, y0)..min(ctx.y1, y1))
                        for (x in max(ctx.x0, x0)..min(ctx.x1, x1)) {
                            val v = tradition.voxel(spec, x, y, z)
                            if (v != KEEP) ctx.set(x, y, z, v)
                        }
                furniture(ctx, plan, b, g)
            }
            if (sacred) heart(ctx, plan, tradition)
        }
    }

    /** Roads, floors and yards levelled at the town's height, and the compound wall in its tradition's manner. */
    private fun ground(ctx: MicroGenContext, plan: SettlementPlan, g: TownGrammar, tradition: Tradition, bx0: Int, by0: Int, span: Int) {
        val floorTop = plan.groundZ * R + R - 1
        val road = blocks[plan.recipe.roadBlockId]
        val traditional = tradition.town.wall != WallStyle.PLAIN
        // Swept yards: the town's own ground, else its tradition's (sand, coral, granite grit), else its foundation earth.
        val yard = blocks[plan.recipe.groundBlockId]
            ?: tradition.town.yard?.let { g.material(it) }
            ?: if (traditional) blocks[plan.recipe.foundationBlockId] else null
        val beaten = traditional && tradition.town.beaten
        val foundation = blocks[plan.recipe.foundationBlockId]
        val wall = blocks[plan.recipe.wallBlockId]
        val wallStyle = tradition.town.wall
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
                        if (beaten && top == yard && building == null && surface in ctx.z0..ctx.z1 &&
                            Hash.unit(ctx.fields.seed, mx, my, 0, 64) < 0.18f
                        ) ctx.set(mx, my, surface, g.beaten)
                    } else if (surface + 1 in ctx.z0..ctx.z1) ctx.set(mx, my, surface + 1, top) // a rug or boardwalk lies on the ground
                }
                // Beaten earth softens a road's edge into the yard beside it, a quarter block at a time.
                if (beaten && onRoad && top != null && !plan.onRoad(Math.floorDiv(mx + EDGE_X[lx], R), Math.floorDiv(my + EDGE_Y[ly], R)) &&
                    Hash.unit(ctx.fields.seed, mx, my, 0, 61) < 0.5f && surface in ctx.z0..ctx.z1
                ) ctx.set(mx, my, surface, g.beaten)
                if (inside && wall != null && plan.walled && !onRoad && d > plan.radius - WALL_THICKNESS) {
                    compoundWall(ctx, plan, g, wallStyle, wall, mx, my, surface, lx, ly)
                }
            }
        }
    }

    /**
     * One column of the town wall. The wall's core is always the recipe's
     * wall block, so it plays as planned; its face, top and coping are the
     * tradition's.
     */
    private fun compoundWall(ctx: MicroGenContext, plan: SettlementPlan, g: TownGrammar, style: WallStyle, wall: Short, mx: Int, my: Int, surface: Int, lx: Int, ly: Int) {
        val height = plan.recipe.wallHeight * R + if (style != WallStyle.PLAIN) 1 else 0
        val d = microDistance(plan, mx, my)
        val face = d > plan.radius - 0.3f || d < plan.radius - WALL_THICKNESS + 0.3f
        // Arc length round the town, for patterns that run along the wall.
        val along = (atan2((my - plan.centerY * R).toFloat(), (mx - plan.centerX * R).toFloat()) * plan.radius * R).toInt()
        for (z in surface + 1..surface + height) {
            if (z !in ctx.z0..ctx.z1) continue
            val h = z - surface - 1
            val skin = if (!face) null else when (style) {
                WallStyle.DRYSTONE -> if (height - h in 2..4 && (Math.floorMod(along + (height - h), 6) == 0 || Math.floorMod(along - (height - h), 6) == 0)) g.drystoneDark else g.drystone
                WallStyle.PISE_CRENELLATED -> g.pise
                WallStyle.ADOBE_PINNACLED -> g.render
                WallStyle.LIME_MERLONED -> g.lime
                WallStyle.THORN -> g.thorn
                WallStyle.MUD_COPED, WallStyle.PLAIN -> null
            }
            ctx.set(mx, my, z, skin ?: wall)
        }
        val topZ = surface + height + 1
        if (topZ + 4 < ctx.z0 || topZ > ctx.z1) return
        fun put(z: Int, m: Short) { if (z in ctx.z0..ctx.z1) ctx.set(mx, my, z, m) }
        when (style) {
            WallStyle.MUD_COPED -> if (lx == 1 || lx == 2 || ly == 1 || ly == 2) put(topZ, g.thatchDark) // thatch coping against the rain
            WallStyle.DRYSTONE -> put(topZ, g.drystoneDark)
            WallStyle.PISE_CRENELLATED -> if (Math.floorMod(along, 6) < 3) { put(topZ, g.pise); put(topZ + 1, g.pise); put(topZ + 2, g.pise) }
            WallStyle.ADOBE_PINNACLED -> {
                val k = Math.floorMod(along, 10)
                if (k < 2) for (dz in 0..3) put(topZ + dz, g.adobe) else put(topZ, g.render)
            }
            WallStyle.LIME_MERLONED -> when (Math.floorMod(along, 6)) { 0, 1 -> { put(topZ, g.lime); put(topZ + 1, g.lime) }; 2 -> put(topZ, g.lime); else -> Unit }
            WallStyle.THORN -> if (Hash.unit(ctx.fields.seed, mx, my, topZ, 66) < 0.5f) put(topZ, g.thorn)
            WallStyle.PLAIN -> Unit
        }
    }

    /** What stands in the square: the tradition's own heart, where nothing else stands at the centre. */
    private fun heart(ctx: MicroGenContext, plan: SettlementPlan, tradition: Tradition) {
        for (dy in -1..1) for (dx in -1..1) {
            if (plan.buildingAt(plan.centerX + dx, plan.centerY + dy) != null || plan.onRoad(plan.centerX + dx, plan.centerY + dy)) return
        }
        Monuments.draw(ctx, tradition.town.sacred, plan.centerX * R + R / 2, plan.centerY * R + R / 2, plan.groundZ * R + R, Hash.mix(ctx.fields.seed, plan.centerX, plan.centerY))
    }

    /** Furniture stands in the middle of the room, a whole block so it reads back as itself (a brazier stays a brazier). */
    private fun furniture(ctx: MicroGenContext, plan: SettlementPlan, b: PlacedBuilding, g: TownGrammar) {
        val m = blocks[b.template.furnitureBlockId] ?: return
        val fx = (b.x + b.width / 2) * R; val fy = (b.y + b.depth / 2) * R; val fz = (plan.groundZ + 1) * R
        ctx.fill(fx, fy, fz, fx + R - 1, fy + R - 1, fz + R - 1, m)
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
        /** Each town builds as the people of its land do. */
        const val REGIONAL = "regional"
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
internal class TownGrammar(
    private val palette: MaterialPalette,
    private val blocks: BlockMaterials,
    /** `regional`, `plain`, or one tradition's id for every town. */
    private val style: String,
    /** `auto`, or the home town's tradition. */
    private val homeStyle: String,
    private val geology: GeoField?,
    private val genome: GenomeRules = GenomeRules(),
    /** In regional mode, the share of towns that invent their look from their land's materials. */
    private val invented: Float = 0f,
) {
    private val arch = ArchPalette(palette)
    private val traditions: Map<String, Tradition> =
        (Traditions.all(palette) + PlainBuildings(arch) + ParametricTradition(arch, genome)).associateBy { it.id }

    /** A regional town that invents its own look, one per tradition: its land's grammar, rolled per building. */
    private val inventedBy = java.util.concurrent.ConcurrentHashMap<String, Tradition>()
    private val chosen = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val specs = java.util.concurrent.ConcurrentHashMap<PlacedBuilding, Building>()

    fun material(name: String): Short = palette.id(name)
    val drystone = palette.id(com.stratum.engine.microvoxel.arch.A.DRYSTONE)
    val drystoneDark = palette.id(com.stratum.engine.microvoxel.arch.A.DRYSTONE_DARK)
    val pise = palette.id(com.stratum.engine.microvoxel.arch.A.PISE)
    val render = palette.id(com.stratum.engine.microvoxel.arch.A.RENDER)
    val adobe = palette.id(com.stratum.engine.microvoxel.arch.A.ADOBE)
    val lime = palette.id(com.stratum.engine.microvoxel.arch.A.LIME)
    val thorn = palette.id(com.stratum.engine.microvoxel.arch.A.THORN)

    /**
     * The tradition a town builds in (`plain` for the block buildings, bevelled).
     * Regional towns follow the province at their centre; without geology, the
     * Igbo compounds the built-in pack comes from. The home town may be set apart.
     */
    fun traditionOf(plan: SettlementPlan): Tradition {
        val id = chosen.getOrPut(plan.id) {
            val home = plan.centerX == 0 && plan.centerY == 0
            when {
                home && homeStyle != SettlementsStage.AUTO -> homeStyle
                style != SettlementsStage.REGIONAL -> style
                else -> {
                    val native = Traditions.choose(geology?.provinceAt(plan.centerX * R, plan.centerY * R)?.id, Hash.mix(0L, plan.centerX, plan.centerY, 0, 97))
                    val invents = !home && invented > 0f && Hash.unit(0L, plan.centerX, plan.centerY, 0, 96) < invented
                    if (invents) INVENTED + native else native
                }
            }
        }
        if (id.startsWith(INVENTED)) {
            // A town that invents its look within its land's own grammar: its tradition's forms, walls and heart.
            val native = id.removePrefix(INVENTED)
            return inventedBy.getOrPut(native) {
                val tradition = traditions.getValue(native)
                ParametricTradition(arch, Vernacular.rulesFor(native, genome), id, tradition.town, "${tradition.name} (vernacular)")
            }
        }
        return traditions.getValue(id)
    }

    /** A plan's building as a tradition sees it; made once per building. */
    fun spec(b: PlacedBuilding, plan: SettlementPlan): Building = specs.getOrPut(b) {
        val base = (plan.groundZ + 1) * R
        Building(
            x0 = b.x * R, y0 = b.y * R, x1 = (b.x + b.width) * R - 1, y1 = (b.y + b.depth) * R - 1,
            base = base, wallTop = base + b.template.height * R - 1,
            doorX = b.doorX, doorY = b.doorY,
            door = when (b.door) { Facing.NORTH -> Side.NORTH; Facing.SOUTH -> Side.SOUTH; Facing.EAST -> Side.EAST; Facing.WEST -> Side.WEST },
            wall = blocks[b.template.wallBlockId] ?: blocks[plan.recipe.foundationBlockId] ?: palette.id(M.MUD),
            window = blocks[b.template.windowBlockId],
            roof = blocks[b.template.roofBlockId],
            r = R,
            seed = Hash.mix(0L, b.x, b.y, 0, 98),
            windowAt = { bx, by, bz -> bz == plan.groundZ + 2 && (bx + by) % 3 == 0 },
            town = Hash.mix(0L, plan.centerX, plan.centerY, 0, 99),
        )
    }
    private val R = SettlementsStage.R
    private val INVENTED = "parametric:"
    val thatchDark = palette.id(M.THATCH_DARK)
    val beaten = palette.id(M.BEATEN_EARTH)
}
