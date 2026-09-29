package com.stratum.engine.microvoxel.arch

import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.gen.Hash
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Buildings from a genome: one painter that draws every combination of plan,
 * storeys, walls, openings, roof, towers and dress a [BuildingGenome] can
 * roll -- billions of distinct buildings, each a pure function of its seed.
 *
 * It keeps the same promise as the traditions (see [Building]): on the
 * ground floor the wall ring is the plan's wall inside its skin, the door is
 * open two blocks high, and the plan's window cells are its window block. So
 * a parametric town plays exactly like any other; only how it looks is free.
 * The plan may be cut into an L, T, U, cross or courtyard, rounded or
 * chamfered, but never where the door is: a plan that would cut the door
 * falls back to the full rectangle.
 *
 * Above the plan's walls it is free: upper storeys (stepped back, or jettied
 * out), balconies, eleven roof forms fitted to any plan outline, towers with
 * caps of their own, chimneys, verandas, roof gardens and finials.
 */
class ParametricTradition(
    private val m: ArchPalette,
    val rules: GenomeRules = GenomeRules(),
    override val id: String = ID,
    /** The town around the buildings: a vernacular town keeps its tradition's walls and heart. */
    override val town: TownStyle = TownStyle(WallStyle.PLAIN, beaten = true),
    override val name: String = "Parametric",
) : Tradition {
    override val origin = "No one people's: a grammar of plans, storeys, walls, openings and roofs, rolled per building " +
        "from the town's shared look -- so no two streets, and no two houses on one, are alike."
    override val reach: Int = REACH

    private val shapes = ConcurrentHashMap<Building, ParametricShape>()

    fun genomeOf(b: Building): BuildingGenome = shape(b).g

    override fun top(b: Building): Int = shape(b).top

    override fun voxel(b: Building, x: Int, y: Int, z: Int): Short = shape(b).voxel(x, y, z)

    /** Draws a building from a genome given outright: the model studio's generator. */
    fun shapeOf(b: Building, genome: BuildingGenome): ParametricShape = ParametricShape(b, genome, m)

    private fun shape(b: Building): ParametricShape {
        shapes[b]?.let { return it }
        if (shapes.size > MAX_CACHED) shapes.clear()
        return shapes.getOrPut(b) { ParametricShape(b, BuildingGenome.roll(b.seed, b.town, rules), m) }
    }

    companion object {
        const val ID = "parametric"
        /** Voxels a building may draw outside its box: verandas, towers, eaves. */
        const val REACH = 7
        private const val MAX_CACHED = 4096
    }
}

/** One building's geometry, worked out once from its genome. */
class ParametricShape internal constructor(private val b: Building, val g: BuildingGenome, m: ArchPalette) {
    private val air = MaterialPalette.AIR
    private val wall = m[g.wall]
    private val accent = m[g.accent]
    private val roofMat = m[g.roofMaterial]
    private val roofAlt = m[g.roofAlt]
    private val trim = m[g.trim]
    private val glass = m[M.GLASS]
    private val lit = m[M.GLASS_LIT]
    private val leaves = m[M.LEAVES]
    private val grass = m[M.GRASS]
    private val flower = m[M.FLOWER_RED]
    private val r = b.r
    private val storeyH = g.storeyHeight
    val upperTop = b.wallTop + g.upper * storeyH
    private val w = b.width
    private val d = b.depth

    /** The plan actually drawn: the genome's, unless it would cut the door or the box is too small for it. */
    val plan: PlanShape
    private val cuts: Array<IntArray>
    val roof: RoofForm
    private val eaves: Int
    private val pitch: Float

    /** The deepest a voxel of the top storey lies inside its outline: half the roof's span. */
    private val extent: Int
    private val rise: Float
    private val towers: List<Tower>
    private val chimneyX: Int
    private val chimneyY: Int
    val top: Int

    private class Tower(val cx: Float, val cy: Float, val radius: Float, val topZ: Int)

    init {
        val small = min(w, d)
        val wanted = g.plan
        val candidate = when {
            wanted == PlanShape.COURTYARD && small < 36 -> PlanShape.RECT
            wanted in CUT && small < 20 -> PlanShape.RECT
            (wanted == PlanShape.ROUND || wanted == PlanShape.OCTAGON) && small < 12 -> PlanShape.RECT
            wanted == PlanShape.ROUND && abs(w - d) > small / 3 -> PlanShape.OCTAGON
            else -> wanted
        }
        var chosenCuts = cutsFor(candidate)
        var chosen = candidate
        if (!doorClear(chosen, chosenCuts)) { chosen = PlanShape.RECT; chosenCuts = emptyArray() }
        plan = chosen
        cuts = chosenCuts
        // Ridged roofs need a plain rectangle; anything else takes the form that fits any outline.
        roof = when {
            g.roof in RIDGED && plan != PlanShape.RECT && plan != PlanShape.STEPPED -> if (plan == PlanShape.ROUND) RoofForm.CONE else RoofForm.HIP
            else -> g.roof
        }
        eaves = when (roof) {
            RoofForm.PYRAMID -> min(g.eaves, 1)
            RoofForm.CONE -> g.eaves + 1
            RoofForm.DOME, RoofForm.ONION -> 1
            RoofForm.MANSARD -> 1
            RoofForm.FLAT, RoofForm.TERRACE -> 0
            else -> g.eaves
        }
        pitch = when (roof) {
            RoofForm.PYRAMID -> max(1.3f, g.pitch * 1.4f)
            RoofForm.CONE -> max(1f, g.pitch * 1.25f)
            else -> g.pitch
        }
        var deepest = 0
        val level = g.upper
        for (y in b.y0..b.y1) for (x in b.x0..b.x1) deepest = max(deepest, edge(x, y, level))
        extent = deepest
        rise = when (roof) {
            RoofForm.FLAT, RoofForm.TERRACE -> 7f
            RoofForm.SAWTOOTH -> SAW_PERIOD * SAW_RISE + 3
            RoofForm.GABLE -> (topSpan() / 2f + eaves) * pitch + 3
            RoofForm.BARREL -> topSpan() / 2f + eaves + 3
            else -> surfAt(extent + eaves) + 3
        }
        val back = when (b.door) { Side.NORTH -> b.y1 - 1; Side.SOUTH -> b.y0 + 1; else -> 0 }
        val backX = when (b.door) { Side.WEST -> b.x1 - 1; Side.EAST -> b.x0 + 1; else -> 0 }
        val corners = listOf(
            (b.x0 + 1).toFloat() to (b.y0 + 1).toFloat(), (b.x1 - 1).toFloat() to (b.y0 + 1).toFloat(),
            (b.x0 + 1).toFloat() to (b.y1 - 1).toFloat(), (b.x1 - 1).toFloat() to (b.y1 - 1).toFloat(),
        )
        val backCorners = corners.filter { (cx, cy) ->
            when (b.door) { Side.NORTH, Side.SOUTH -> cy.toInt() == back; else -> cx.toInt() == backX }
        }
        val flip = (b.seed ushr 7) and 1L == 1L
        towers = when (g.towers) {
            0 -> emptyList()
            1 -> listOf(backCorners[if (flip) 1 else 0]).map { (cx, cy) -> Tower(cx, cy, 2.5f, upperTop + storeyH * 2 + 12) }
            2 -> backCorners.map { (cx, cy) -> Tower(cx, cy, if (small >= 24) 4f else 3f, upperTop + 6 + storeyH / 2) }
            else -> corners.map { (cx, cy) -> Tower(cx, cy, if (small >= 24) 4f else 3f, upperTop + 6 + storeyH / 2) }
        }
        chimneyX = b.x0 + if (flip) w * 3 / 4 - 1 else w / 4
        chimneyY = b.y0 + d / 3
        val towerTop = towers.maxOfOrNull { it.topZ + ((it.radius + 1) * 2.2f).toInt() + 6 } ?: 0
        top = max(upperTop + 1 + rise.toInt() + FINIAL + 2, towerTop)
    }

    fun voxel(x: Int, y: Int, z: Int): Short {
        if (z < b.base) return KEEP
        if (towers.isNotEmpty()) tower(x, y, z).let { if (it != KEEP) return it }
        if (z > upperTop) return roof(x, y, z)
        val level = if (z <= b.wallTop) 0 else 1 + (z - b.wallTop - 1) / storeyH
        val e = edge(x, y, level)
        val h = z - b.base
        if (e < 0) return outside(x, y, z, level, e, h)
        if (e > 2) return interior(z, level)
        return if (level == 0) groundWall(x, y, z, e, h) else upperWall(x, y, z, e, h, level)
    }

    // ---- The plan --------------------------------------------------------------------

    private fun inset(level: Int): Int = when {
        level == 0 -> 0
        plan == PlanShape.STEPPED -> g.setback * level
        g.jetty -> -1
        else -> 0
    }

    /** Voxels in from the outline of storey [level], 0 on its skin; negative outside it. */
    fun edge(x: Int, y: Int, level: Int): Int {
        val i = inset(level)
        val x0 = b.x0 + i; val x1 = b.x1 - i; val y0 = b.y0 + i; val y1 = b.y1 - i
        if (x1 - x0 < 6 || y1 - y0 < 6) return -1
        var e = min(min(x - x0, x1 - x), min(y - y0, y1 - y))
        when (plan) {
            PlanShape.ROUND -> {
                val rad = min(x1 - x0, y1 - y0) / 2f + 0.5f
                val dx = x - (x0 + x1) / 2f; val dy = y - (y0 + y1) / 2f
                e = floor(rad - sqrt(dx * dx + dy * dy)).toInt()
            }
            PlanShape.OCTAGON -> {
                val c = min(x1 - x0, y1 - y0) / 3
                e = min(e, min(x - x0, x1 - x) + min(y - y0, y1 - y) - c)
            }
            else -> for (cut in cuts) e = min(e, max(max(cut[0] - x, x - cut[2]), max(cut[1] - y, y - cut[3])) - 1)
        }
        return e
    }

    /** Rectangles taken out of the box, in the door's frame: u along the door's face, v in from it. */
    private fun cutsFor(shape: PlanShape): Array<IntArray> {
        val flip = (b.seed ushr 3) and 1L == 1L
        return when (shape) {
            PlanShape.L -> arrayOf(if (flip) uv(0.55f, 0.5f, 1f, 1f) else uv(0f, 0.5f, 0.45f, 1f))
            PlanShape.T -> arrayOf(uv(0f, 0.55f, 0.3f, 1f), uv(0.7f, 0.55f, 1f, 1f))
            PlanShape.U -> arrayOf(uv(0.35f, 0.5f, 0.65f, 1f))
            PlanShape.CROSS -> arrayOf(uv(0f, 0f, 0.28f, 0.28f), uv(0.72f, 0f, 1f, 0.28f), uv(0f, 0.72f, 0.28f, 1f), uv(0.72f, 0.72f, 1f, 1f))
            PlanShape.COURTYARD -> arrayOf(uv(0.32f, 0.32f, 0.68f, 0.68f))
            else -> emptyArray()
        }
    }

    private fun uv(u0: Float, v0: Float, u1: Float, v1: Float): IntArray {
        val alongX = b.door == Side.NORTH || b.door == Side.SOUTH
        val a0 = if (alongX) b.x0 else b.y0
        val span = if (alongX) w else d
        val depth = if (alongX) d else w
        val lo = a0 + (u0 * span).toInt(); val hi = a0 + (u1 * span).toInt() - 1
        val in0 = (v0 * depth).toInt(); val in1 = (v1 * depth).toInt() - 1
        return when (b.door) {
            Side.NORTH -> intArrayOf(lo, b.y0 + in0, hi, b.y0 + in1)
            Side.SOUTH -> intArrayOf(lo, b.y1 - in1, hi, b.y1 - in0)
            Side.WEST -> intArrayOf(b.x0 + in0, lo, b.x0 + in1, hi)
            Side.EAST -> intArrayOf(b.x1 - in1, lo, b.x1 - in0, hi)
        }
    }

    /** Whether the door cell, its jambs and a step inside it all stand in the plan, on its outline. */
    private fun doorClear(shape: PlanShape, shapeCuts: Array<IntArray>): Boolean {
        if (shape == PlanShape.RECT || shape == PlanShape.STEPPED) return true
        val saved = Probe(shape, shapeCuts)
        val mid = r / 2
        for (k in -mid - 1..mid + 1) {
            val (fx, fy, ix, iy) = when (b.door) {
                Side.NORTH -> intArrayOf(b.doorX * r + mid + k, b.y0, b.doorX * r + mid + k, b.y0 + 4)
                Side.SOUTH -> intArrayOf(b.doorX * r + mid + k, b.y1, b.doorX * r + mid + k, b.y1 - 4)
                Side.WEST -> intArrayOf(b.x0, b.doorY * r + mid + k, b.x0 + 4, b.doorY * r + mid + k)
                Side.EAST -> intArrayOf(b.x1, b.doorY * r + mid + k, b.x1 - 4, b.doorY * r + mid + k)
            }
            if (saved.edge(fx, fy) != 0 || saved.edge(ix, iy) < 3) return false
        }
        return true
    }

    /** The ground floor's outline for a plan not yet chosen. */
    private inner class Probe(private val shape: PlanShape, private val shapeCuts: Array<IntArray>) {
        fun edge(x: Int, y: Int): Int {
            var e = min(min(x - b.x0, b.x1 - x), min(y - b.y0, b.y1 - y))
            when (shape) {
                PlanShape.ROUND -> {
                    val rad = min(w - 1, d - 1) / 2f + 0.5f
                    val dx = x - b.cx; val dy = y - b.cy
                    e = floor(rad - sqrt(dx * dx + dy * dy)).toInt()
                }
                PlanShape.OCTAGON -> e = min(e, min(x - b.x0, b.x1 - x) + min(y - b.y0, b.y1 - y) - min(w - 1, d - 1) / 3)
                else -> for (cut in shapeCuts) e = min(e, max(max(cut[0] - x, x - cut[2]), max(cut[1] - y, y - cut[3])) - 1)
            }
            return e
        }
    }

    /** Whether a face at (x, y) runs along x (a north or south face) rather than along y. */
    private fun runsAlongX(x: Int, y: Int, level: Int): Boolean = edge(x, y + 1, level) != edge(x, y - 1, level)

    /** Whether the wall runs on [n] voxels either way from (x, y): false near a corner. */
    private fun continues(x: Int, y: Int, level: Int, n: Int): Boolean =
        if (runsAlongX(x, y, level)) edge(x + n, y, level) >= 0 && edge(x - n, y, level) >= 0
        else edge(x, y + n, level) >= 0 && edge(x, y - n, level) >= 0

    // ---- Walls -----------------------------------------------------------------------

    private fun groundWall(x: Int, y: Int, z: Int, e: Int, h: Int): Short {
        if (b.isDoor(x, y)) return when {
            h < 2 * r -> air
            h <= 2 * r + 1 -> trim
            else -> if (e == 0) skin(x, y, z, h, 0) else b.wall
        }
        if (b.isJamb(x, y) && h <= 2 * r && e <= 1) return trim
        if (b.isWindow(x, y, z) && !b.isCornerCell(x, y)) return if (e == 0 && ((x + y + z) and 1) == 0) air else b.window!!
        return if (e == 0) skin(x, y, z, h, 0) else b.wall
    }

    private fun upperWall(x: Int, y: Int, z: Int, e: Int, h: Int, level: Int): Short {
        val lh = (z - b.wallTop - 1) % storeyH
        // A string course marks each floor.
        if (lh <= 1) return if (e == 0) accent else wall
        val k = windowColumn(x, y, level)
        if (k != null && inWindow(k, lh) && continues(x, y, level, windowWidth() + 2)) return when (e) {
            0 -> if (g.windows == WindowForm.GRID && (k and 1) == 1) trim else air
            1 -> if (Hash.unit(b.seed, x / 4, y / 4, z / 8, 70) < 0.25f) lit else glass
            else -> air
        }
        if (e == 0 && g.windows == WindowForm.SHUTTERED && k != null && (k == -1 || k == windowWidth()) && lh in windowRows() &&
            continues(x, y, level, windowWidth() + 2)
        ) return accent
        return if (e == 0) skin(x, y, z, h, level) else wall
    }

    private fun interior(z: Int, level: Int): Short {
        if (level == 0) return air
        val lh = (z - b.wallTop - 1) % storeyH
        return if (lh <= 1) wall else air
    }

    private fun windowWidth(): Int = when (g.windows) {
        WindowForm.SLIT -> 1
        WindowForm.TALL -> 2
        WindowForm.ROUND -> 4
        WindowForm.GRID -> 5
        else -> 3
    }

    private fun windowRows(): IntRange = when (g.windows) {
        WindowForm.SLIT, WindowForm.TALL -> 3..storeyH - 3
        WindowForm.ROUND -> storeyH / 2 - 2..storeyH / 2 + 1
        else -> 4..storeyH - 3
    }

    /** Where along its bay a voxel of the face falls: 0 until the window's width is the opening; -1 just before it. */
    private fun windowColumn(x: Int, y: Int, level: Int): Int? {
        val alongX = runsAlongX(x, y, level)
        val along = if (alongX) x - b.x0 else y - b.y0
        val width = windowWidth()
        val spacing = max(g.windowSpacing, width + 3)
        val k = Math.floorMod(along - spacing / 2, spacing)
        return if (k == spacing - 1) -1 else k
    }

    private fun inWindow(k: Int, lh: Int): Boolean {
        val width = windowWidth()
        if (k !in 0 until width) return false
        val rows = windowRows()
        if (lh !in rows) return false
        return when (g.windows) {
            WindowForm.ARCHED -> !(lh == rows.last && (k == 0 || k == width - 1))
            WindowForm.ROUND -> {
                val cx = (width - 1) / 2f; val cz = (rows.first + rows.last) / 2f
                (k - cx) * (k - cx) + (lh - cz) * (lh - cz) <= 2.6f * 2.6f
            }
            else -> true
        }
    }

    /** The outer voxel of a wall: the genome's wall, patterned with its accent. */
    private fun skin(x: Int, y: Int, z: Int, h: Int, level: Int): Short {
        val alongX = runsAlongX(x, y, level)
        val along = if (alongX) x else y
        val lh = if (level == 0) h else (z - b.wallTop - 1) % storeyH
        val height = if (level == 0) b.height else storeyH
        return when (g.pattern) {
            WallPattern.PLAIN -> wall
            WallPattern.BANDS -> if (Math.floorMod(lh, 6) == 5) accent else wall
            WallPattern.PLINTH -> if (level == 0 && h < 5) accent else wall
            WallPattern.QUOINS -> if (!continues(x, y, level, 3) && (lh / 3) % 2 == 0) accent else wall
            WallPattern.CHECKER -> if (lh >= height - 5 && ((along / 2 + lh / 2) and 1) == 0) accent else wall
            WallPattern.ZIGZAG -> if (lh in height - 6 until height - 1 && (Math.floorMod(along + lh, 6) == 0 || Math.floorMod(along - lh, 6) == 0)) accent else wall
            WallPattern.DIAMOND -> if (abs(Math.floorMod(along, 8) - 4) + abs(Math.floorMod(lh, 8) - 4) == 3) accent else wall
            WallPattern.PILASTERS -> if (Math.floorMod(along, 10) < 2) accent else wall
            WallPattern.FRIEZE -> if (lh >= height - 3 || (lh == height - 4 && (along and 1) == 0)) accent else wall
        }
    }

    // ---- Outside the walls -----------------------------------------------------------

    private fun frontOfDoor(x: Int, y: Int): Boolean {
        if (b.inside(x, y)) return false
        val out = b.out(x, y)
        return out <= 4 && b.isDoor(x - b.door.dx * out, y - b.door.dy * out)
    }

    private fun outside(x: Int, y: Int, z: Int, level: Int, e: Int, h: Int): Short {
        val out = -e
        if (level == 0) {
            if (frontOfDoor(x, y) && h < 2 * r + 2) return KEEP
            if (out == 1 && h < g.plinth) return accent
            if (g.veranda) veranda(x, y, z).let { if (it != KEEP) return it }
            if (g.jetty && g.upper > 0 && out == 1 && z >= b.wallTop - 1 && Math.floorMod(x + y, 6) == 0) return trim
            if (g.beams && out in 1..2 && Math.floorMod(h, 12) == 8 && Math.floorMod(if (runsAlongX(x, y, 0)) x else y, 6) == 2) return trim
            return KEEP
        }
        val lh = (z - b.wallTop - 1) % storeyH
        // A stepped storey stands on the roof of the one below: a terrace with its own parapet and planters.
        if (plan == PlanShape.STEPPED) {
            val below = edge(x, y, level - 1)
            if (below >= 0) {
                if (lh <= 1) return roofMat
                if (below == 0 && lh in 2..3) return accent
                if (g.garden && lh == 2 && below >= 2 && Hash.unit(b.seed, x, y, level, 71) < 0.3f) return planting(x, y)
            }
        }
        if (g.balcony && onDoorSide(x, y, level) && out in 1..3) {
            if (lh <= 1) return trim
            if (out == 3 && lh in 2..4 && ((x + y) and 1) == 0) return trim
            if (out == 3 && lh == 5) return trim
        }
        if (g.beams && out in 1..2 && lh == storeyH - 3 && Math.floorMod(if (runsAlongX(x, y, level)) x else y, 6) == 2) return trim
        return KEEP
    }

    private fun onDoorSide(x: Int, y: Int, level: Int): Boolean {
        val i = inset(level)
        return when (b.door) {
            Side.NORTH -> y < b.y0 + i && x in b.x0 + i..b.x1 - i
            Side.SOUTH -> y > b.y1 - i && x in b.x0 + i..b.x1 - i
            Side.WEST -> x < b.x0 + i && y in b.y0 + i..b.y1 - i
            Side.EAST -> x > b.x1 - i && y in b.y0 + i..b.y1 - i
        }
    }

    /** A lean-to veranda along the door's face, on posts. */
    private fun veranda(x: Int, y: Int, z: Int): Short {
        val out: Int; val along: Int; val span: Int
        when (b.door) {
            Side.NORTH -> { out = b.y0 - y; along = x - b.x0; span = w }
            Side.SOUTH -> { out = y - b.y1; along = x - b.x0; span = w }
            Side.WEST -> { out = b.x0 - x; along = y - b.y0; span = d }
            Side.EAST -> { out = x - b.x1; along = y - b.y0; span = d }
        }
        if (out !in 1..VERANDA || along !in 0 until span) return KEEP
        val roofZ = b.wallTop - (out - 1) / 2
        if (z == roofZ) return roofMat
        if (z == roofZ - 1 && out == VERANDA) return trim
        if (out == VERANDA && z < roofZ - 1 && Math.floorMod(along, 8) == 3) return trim
        return KEEP
    }

    private fun planting(x: Int, y: Int): Short = when (Hash.int(b.seed, x, y, 72, 5)) {
        0 -> flower
        1, 2 -> grass
        else -> leaves
    }

    // ---- Roofs -----------------------------------------------------------------------

    /** The span across the top storey's short side, for ridged roofs. */
    private fun topSpan(): Int {
        val i = inset(g.upper)
        return min(w, d) - 2 * i
    }

    /** Height of the roof's surface above the wall top, [ee] voxels in from the eaves. */
    private fun surfAt(ee: Int): Float {
        val reach = (extent + eaves).coerceAtLeast(1)
        val at = min(ee, reach)
        return when (roof) {
            RoofForm.MANSARD -> {
                val k = max(2, reach / 3)
                if (at <= k) at * 2.2f else k * 2.2f + (at - k) * 0.35f
            }
            RoofForm.DOME -> {
                val t = at / reach.toFloat()
                reach * g.domeRatio * sqrt(1f - (1f - t) * (1f - t))
            }
            RoofForm.ONION -> {
                val t = at / reach.toFloat()
                val bulb = if (t < 0.18f) 1.5f else {
                    val q = ((1f - t) / 0.82f).coerceAtMost(1f)
                    reach * g.domeRatio * (0.25f + 0.85f * sqrt(1f - q * q))
                }
                if (t > 0.9f) bulb + (t - 0.9f) * reach * g.domeRatio * 5f else bulb
            }
            else -> at * pitch
        }
    }

    private fun roof(x: Int, y: Int, z: Int): Short {
        val dz = z - upperTop - 1
        if (g.chimney && roof != RoofForm.DOME && roof != RoofForm.ONION && x in chimneyX..chimneyX + 2 && y in chimneyY..chimneyY + 2) {
            val stackTop = rise.toInt() + 1
            if (dz <= stackTop) return if (dz >= stackTop - 1 && x == chimneyX + 1 && y == chimneyY + 1) air else accent
        }
        return when (roof) {
            RoofForm.FLAT -> flat(x, y, dz, garden = g.garden)
            RoofForm.TERRACE -> terrace(x, y, dz)
            RoofForm.GABLE -> gable(x, y, z, dz)
            RoofForm.BARREL -> barrel(x, y, z, dz)
            RoofForm.SAWTOOTH -> sawtooth(x, y, z, dz)
            else -> revolved(x, y, z, dz)
        }
    }

    /** Hip, pyramid, cone, mansard, dome and onion: a surface rising with depth into the outline, fitted to any plan. */
    private fun revolved(x: Int, y: Int, z: Int, dz: Int): Short {
        val e = edge(x, y, g.upper)
        val ee = e + eaves
        if (ee < 0) return KEEP
        val s = surfAt(ee)
        if (dz > s + 0.5f) {
            val crowned = g.finial && roof != RoofForm.HIP && roof != RoofForm.MANSARD
            return if (crowned && e >= extent && dz <= s + FINIAL) (if (dz >= s + FINIAL - 1) accent else trim) else KEEP
        }
        val outer = if (ee == 0) -1f else surfAt(ee - 1)
        if (dz > s - 2.5f || dz > outer + 0.5f) return roofSurface(x, y, z, ragged = roof == RoofForm.CONE && ee == 0)
        return if (e >= 0) air else KEEP
    }

    private fun roofSurface(x: Int, y: Int, z: Int, ragged: Boolean): Short {
        if (ragged && Hash.unit(b.seed, x, y, z, 73) < 0.35f) return air
        return if ((z / 3) % 2 == 0) roofMat else roofAlt
    }

    private fun flat(x: Int, y: Int, dz: Int, garden: Boolean): Short {
        val e = edge(x, y, g.upper)
        if (e < 0) return KEEP
        if (dz <= 1) return roofMat
        if (e == 0 && dz in 2..3) return wall
        if (e == 0 && dz in 4..6 && g.pinnacles && Math.floorMod(x + y, 8) < 2) return if (dz == 6) accent else wall
        if (garden && dz == 2 && e >= 2 && Hash.unit(b.seed, x, y, 0, 74) < 0.3f) return planting(x, y)
        return KEEP
    }

    /** A flat roof made a garden: planters, and a pergola of posts and beams. */
    private fun terrace(x: Int, y: Int, dz: Int): Short {
        flat(x, y, dz, garden = true).let { if (it != KEEP) return it }
        val e = edge(x, y, g.upper)
        if (e == 2 && dz in 2..6 && Math.floorMod(x, 6) == 0 && Math.floorMod(y, 6) == 0) return trim
        if (e in 2..extent && dz == 7 && (Math.floorMod(x, 6) == 0 || Math.floorMod(y, 6) == 0)) return trim
        return KEEP
    }

    /** Along/across the top storey's box, ridge along its long side. */
    private inline fun <T> ridged(x: Int, y: Int, block: (a: Int, span: Int, along: Int, length: Int) -> T): T {
        val i = inset(g.upper)
        val x0 = b.x0 + i; val y0 = b.y0 + i
        val tw = w - 2 * i; val td = d - 2 * i
        val alongY = tw <= td
        return if (alongY) block(x - x0, tw, y - y0, td) else block(y - y0, td, x - x0, tw)
    }

    private fun gable(x: Int, y: Int, z: Int, dz: Int): Short = ridged(x, y) { a, span, along, length ->
        if (a < -eaves || a >= span + eaves || along < -1 || along > length) return@ridged KEEP
        val s = (min(a, span - 1 - a) + eaves) * pitch
        val dd = dz - s
        when {
            dd in -2.5f..0.5f -> roofSurface(x, y, z, ragged = false)
            dd < -2.5f && a in 0 until span && along in 0 until length -> if (along <= 2 || along >= length - 3) wall else air
            g.finial && dd in 0.5f..1.5f && a == span / 2 && (along == 0 || along == length - 1) -> trim
            else -> KEEP
        }
    }

    private fun barrel(x: Int, y: Int, z: Int, dz: Int): Short = ridged(x, y) { a, span, along, length ->
        if (a < -eaves || a >= span + eaves || along < 0 || along >= length) return@ridged KEEP
        val radius = span / 2f + eaves
        val ee = min(a, span - 1 - a) + eaves
        val s = vault(ee, radius)
        if (dz > s + 0.5f) return@ridged KEEP
        val outer = if (ee == 0) -1f else vault(ee - 1, radius)
        when {
            dz > s - 2.5f || dz > outer + 0.5f -> roofSurface(x, y, z, ragged = false)
            along <= 2 || along >= length - 3 -> if (a in 0 until span) wall else KEEP
            a in 0 until span -> air
            else -> KEEP
        }
    }

    private fun vault(k: Int, radius: Float): Float {
        val t = (k / radius).coerceAtMost(1f)
        return radius * sqrt(1f - (1f - t) * (1f - t))
    }

    /** Rows of north lights: each tooth a slope, with glass in its upright face. */
    private fun sawtooth(x: Int, y: Int, z: Int, dz: Int): Short = ridged(x, y) { a, span, along, length ->
        if (a !in 0 until span || along !in 0 until length) return@ridged KEEP
        val k = Math.floorMod(along, SAW_PERIOD)
        val s = k * SAW_RISE
        when {
            k == 0 && dz <= (SAW_PERIOD - 1) * SAW_RISE && along > 0 -> if (dz <= 1) roofMat else glass
            dz.toFloat() in s - 1.5f..s + 0.5f -> roofSurface(x, y, z, ragged = false)
            dz < s - 1.5f -> air
            else -> KEEP
        }
    }

    // ---- Towers ----------------------------------------------------------------------

    private fun tower(x: Int, y: Int, z: Int): Short {
        for (t in towers) {
            val dx = x - t.cx; val dy = y - t.cy
            if (abs(dx) > t.radius + 2 || abs(dy) > t.radius + 2) continue
            val dist = sqrt(dx * dx + dy * dy)
            if (z <= t.topZ) {
                if (dist > t.radius) continue
                val h = z - b.base
                if (dist > t.radius - 1) {
                    if (Math.floorMod(h, 10) == 9) return accent
                    if (z > b.wallTop && Math.floorMod(h, 10) in 4..6 && (abs(dx) < 0.8f || abs(dy) < 0.8f)) return air
                    return wall
                }
                return if (dist < t.radius - 2 && z > b.wallTop) air else wall
            }
            val cap = towerCap(t, dx, dy, dist, z - t.topZ - 1)
            if (cap != KEEP) return cap
        }
        return KEEP
    }

    private fun towerCap(t: Tower, dx: Float, dy: Float, dist: Float, dz: Int): Short {
        val rad = t.radius + 1f
        val s: Float = when (g.towerRoof) {
            RoofForm.CONE -> if (dist > rad) return KEEP else (rad - dist) * 1.8f
            RoofForm.PYRAMID -> { val dd = max(abs(dx), abs(dy)); if (dd > rad) return KEEP else (rad - dd) * 1.9f }
            RoofForm.DOME -> if (dist > rad) return KEEP else rad * 1.1f * sqrt(1f - (dist / rad) * (dist / rad))
            RoofForm.ONION -> {
                if (dist > rad) return KEEP
                val q = dist / rad
                val bulb = rad * (0.4f + 1.2f * sqrt(max(0f, 1f - q * q)))
                if (q < 0.25f) bulb + (0.25f - q) * rad * 8f else bulb
            }
            else -> {
                if (dist > t.radius) return KEEP
                if (dz == 0) return roofMat
                val merlon = ((atan2(dy, dx) + Math.PI) * t.radius / 2).toInt() % 2 == 0
                return if (dist > t.radius - 1 && dz in 1..2 && merlon) accent else KEEP
            }
        }
        if (dz > s + 0.5f) return if (g.finial && dist < 0.8f && dz <= s + 4) trim else KEEP
        return if (dz > s - 2f) roofSurface(t.cx.toInt(), t.cy.toInt(), dz, ragged = false) else air
    }

    companion object {
        private val CUT = setOf(PlanShape.L, PlanShape.T, PlanShape.U, PlanShape.CROSS, PlanShape.COURTYARD)
        private val RIDGED = setOf(RoofForm.GABLE, RoofForm.BARREL, RoofForm.SAWTOOTH)
        private const val VERANDA = 5
        private const val FINIAL = 5
        private const val SAW_PERIOD = 10
        private const val SAW_RISE = 0.8f
    }
}
