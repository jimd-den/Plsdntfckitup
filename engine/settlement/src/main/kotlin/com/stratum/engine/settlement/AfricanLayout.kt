package com.stratum.engine.settlement

import com.stratum.core.domain.settlement.BuildingRole
import com.stratum.core.domain.settlement.BuildingTemplate
import com.stratum.core.domain.settlement.Facing
import com.stratum.core.domain.settlement.Road
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.settlement.culture.CityGenerator
import com.stratum.core.domain.settlement.culture.CityGenome
import com.stratum.core.domain.settlement.culture.Craft
import com.stratum.core.domain.settlement.culture.Cultures
import com.stratum.core.domain.settlement.culture.Heart
import com.stratum.core.domain.settlement.culture.Mark
import com.stratum.core.domain.settlement.culture.Pattern
import com.stratum.core.domain.settlement.culture.Quarter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lays a town out the way its people make places, from its [CityGenome].
 *
 * The genome says what kind of place it is; each [Pattern] arranges the
 * ground its own way:
 *
 * - **Ringed** -- houses in a ring facing the cattle byre, granaries
 *   between, one way in through the fence.
 * - **Compound** -- one homestead: the head's house at the back of the
 *   yard, the others round it, granaries at the corners.
 * - **Dispersed** -- a square with the seat and the shrine on it, winding
 *   paths out, and along them family compounds of a few houses and a
 *   granary about their own yard; a village-group has several wards, each
 *   with its own small square.
 * - **Terraces** -- rows along the contour of a scarp, the meeting house
 *   and shrines on the lowest, granaries crowding the upper ones.
 * - **Ksar** -- a square fortified block of narrow lanes round a court of
 *   granaries, towers at its corners.
 * - **Walled** -- a wall with a gate for each road, every road running to
 *   the centre, the palace and the mosque at the centre and each quarter
 *   its wedge between two roads.
 * - **Radial** -- the seat's great court at the centre, broad ways out,
 *   quarters by guild in the sectors, a ring road binding them.
 * - **Lanes** -- close houses on winding lanes, warehouses and boatyards
 *   along the waterfront side.
 * - **Citadel** -- a walled enclosure on the height with the seat and the
 *   shrines, the town spread below.
 * - **Linear** -- one long street, the market at its middle, the fields at
 *   its ends.
 *
 * The recipe's own buildings are used first. Where it has none for what a
 * quarter needs -- a granary, a gatehouse, dye pits, a cattle byre -- one is
 * made from the recipe's own materials and named in the town's way, so
 * every building is still drawn and stamped from blocks the pack has.
 * History leaves marks: a burnt or abandoned quarter stands thin, newcomers
 * have their own quarter, a raised wall rings what was open.
 */
object AfricanLayout : SettlementLayout {
    override val id = SettlementRecipe.AFRICAN

    override fun arrange(site: SettlementSite, recipe: SettlementRecipe, random: Random): Layout {
        val g = site.character ?: CityGenerator.roll(random.nextLong(), CultureOfRecipe.of(recipe))
        val built = Furnish.recipeFor(recipe, g)
        val plan = Plan(site, built, g, random)
        when (g.form.pattern) {
            Pattern.RINGED -> plan.ringed()
            Pattern.COMPOUND -> plan.compound()
            Pattern.DISPERSED -> plan.dispersed()
            Pattern.TERRACES -> plan.terraces()
            Pattern.KSAR -> plan.ksar()
            Pattern.WALLED -> plan.walled()
            Pattern.RADIAL -> plan.radial()
            Pattern.LANES -> plan.lanes()
            Pattern.CITADEL -> plan.citadel()
            Pattern.LINEAR -> plan.linear()
        }
        return Layout(plan.roads, plan.packer.placed, square = plan.square, recipe = built)
    }

    /** One town being laid out. */
    private class Plan(val site: SettlementSite, val recipe: SettlementRecipe, val g: CityGenome, val random: Random) {
        val roads = ArrayList<Road>()
        val square = g.form.pattern == Pattern.KSAR
        val r = site.radius.toFloat()
        val cx = site.centerX + 0.5f
        val cy = site.centerY + 0.5f
        private val plaza = when (g.form.pattern) {
            // The byre or the yard is the middle: there is no open square to keep clear.
            Pattern.RINGED, Pattern.COMPOUND -> -1
            Pattern.KSAR -> 1
            Pattern.LANES, Pattern.TERRACES -> 3
            else -> (site.radius / 6).coerceIn(4, 8)
        }
        lateinit var packer: LotPacker

        /** Where the town's ruined quarter lies, if its history burnt or abandoned one: an angle and a half-width. */
        private val ruin: Pair<Double, Double>? = if (Mark.RUIN in g.marks) random.nextDouble() * 2 * PI to (0.35 + random.nextDouble() * 0.3) else null

        /** Roads are known before the packer is made, since it keeps buildings off them. */
        fun open(extra: Int = 0) {
            val most = (64 + (site.radius * site.radius) / 14 + extra).coerceAtMost(220)
            packer = LotPacker(site, recipe, random, roads, square, plaza, most)
        }

        // ---- the patterns ----------------------------------------------------------------------------

        fun ringed() {
            // One way in, from the downhill side (any side: the land is levelled).
            val gate = random.nextDouble() * 2 * PI
            // The way in stops at the ring of houses: the middle is the byre's.
            roads += radial(site, gate, r * 0.45, r + EXIT.toDouble(), 2)
            open()
            heart(cx, cy)
            // The great house stands opposite the gate, looking down the byre to it.
            seat(gate + PI, r * 0.62f)
            ring(r * 0.62f, BuildingRole.HOUSE, gate, skip = 0.5)
            ring(r * 0.36f, granaryRole(), gate + PI / 7, skip = 0.45)
        }

        fun compound() {
            roads += radial(site, PI / 2, r * 0.4, r + EXIT.toDouble(), 2)
            open()
            heart(cx, cy)
            seat(-PI / 2, r * 0.45f)
            ring(r * 0.5f, BuildingRole.HOUSE, PI / 2, skip = 0.6)
            for (k in 0 until 4) packer.templateFor(granaryRole())?.let { packer.placeNear(it, cx + cos(PI / 4 + k * PI / 2).toFloat() * r * 0.72f, cy + sin(PI / 4 + k * PI / 2).toFloat() * r * 0.72f, 2f) }
            outskirts()
        }

        fun dispersed() {
            val paths = 3 + random.nextInt(3) + if (g.form.scale == com.stratum.core.domain.settlement.culture.Scale.LARGE) 2 else 0
            val start = random.nextDouble() * 2 * PI
            val angles = List(paths) { start + it * 2 * PI / paths + (random.nextDouble() - 0.5) * 0.6 }
            angles.forEach { a -> roads += winding(a, plaza.toDouble(), r + EXIT.toDouble(), 2) }
            open()
            heart(cx, cy)
            seat(angles.first() + PI / paths, plaza + 5f)
            onSquare(angles)
            // Wards: each path's far reach is a cluster of compounds round its own little yard.
            val wards = if (g.form.scale == com.stratum.core.domain.settlement.culture.Scale.LARGE) angles.size else (angles.size + 1) / 2
            angles.take(wards).forEach { a ->
                val d = r * (0.45f + random.nextFloat() * 0.25f)
                val side = a + (if (random.nextBoolean()) 1 else -1) * 0.35
                compoundAt(cx + cos(side).toFloat() * d, cy + sin(side).toFloat() * d, 3 + random.nextInt(3))
            }
            roads.forEach { road -> packer.lineZoned(road) { x, y -> roleAt(x, y, sparse = true) } }
            outskirts()
        }

        fun terraces() {
            // The scarp's face: rows across it, every eleven blocks, the lowest by the square.
            val turn = random.nextInt(4) * PI / 2
            val rows = ((2 * r) / ROW).toInt().coerceAtLeast(3)
            for (i in 0 until rows) {
                val off = -r + ROW * (i + 0.5f)
                val half = kotlin.math.sqrt((r * r - off * off).coerceAtLeast(0f)) - 2f
                if (half < 6f) continue
                roads += turned(turn, -half, off, half, off, 2)
            }
            // One stair up through the rows.
            roads += turned(turn, 0f, -r - EXIT, 0f, r + EXIT, 2)
            open()
            heart(cx + rot(turn, 0f, -r * 0.7f).first, cy + rot(turn, 0f, -r * 0.7f).second)
            // The meeting house in the gap between two of the lowest rows, off the stair, looking down the scarp.
            packer.hall()?.let { hall ->
                val (fx, fy) = rot(turn, 0f, -1f)
                val door = LotPacker.facingToward(fx, fy)
                val placed = (1 until rows).asSequence().flatMap { k -> sequenceOf(9f, -9f, 15f, -15f, 4f, -4f).map { along -> k to along } }.any { (k, along) ->
                    val (x, y) = rot(turn, along, -r + ROW * k)
                    packer.placeCentred(hall, cx + x, cy + y, door)
                }
                if (!placed) seat(turn - PI / 2, r * 0.55f)
            }
            roads.forEach { road ->
                packer.lineZoned(road) { x, y ->
                    val (_, up) = unrot(turn, x - cx, y - cy)
                    when {
                        up < -r * 0.45f -> pick(Quarter.SHRINES, Quarter.AGE_GRADES, Quarter.MARKET)
                        up > r * 0.25f -> if (random.nextFloat() < 0.5f) granaryRole() else BuildingRole.HOUSE
                        else -> roleAt(x, y)
                    }
                }
            }
        }

        fun ksar() {
            val reach = site.radius + EXIT
            val spacing = 8
            // The court of granaries in the middle: lanes stop at its edge.
            val court = (r * 0.4f).coerceAtLeast(11f)
            for (o in -site.radius + spacing / 2 until site.radius step spacing) {
                val x = site.centerX + o
                if (abs(o) < court + 3) {
                    roads += Road(x, site.centerY - site.radius + 2, x, (site.centerY - court).roundToInt(), 2)
                    roads += Road(x, (site.centerY + court).roundToInt(), x, site.centerY + site.radius - 2, 2)
                } else {
                    roads += Road(x, site.centerY - site.radius + 2, x, site.centerY + site.radius - 2, 2)
                }
            }
            // The main lane through the gates.
            roads += Road(site.centerX - reach, site.centerY, site.centerX + reach, site.centerY, 3)
            if (g.gates >= 2) roads += Road(site.centerX, site.centerY - reach, site.centerX, site.centerY + reach, 3)
            open()
            // The seat on the court's north side, the granaries round the rest of it.
            packer.hall()?.let { hall ->
                val off = hall.depth / 2f + 2.5f
                listOf(0f, 6.5f, -6.5f).firstOrNull { dx -> packer.placeCentred(hall, cx + dx, cy - off, Facing.SOUTH) }
                    ?: listOf(0f, 6.5f, -6.5f).firstOrNull { dx -> packer.placeCentred(hall, cx + dx, cy + off, Facing.NORTH) }
                    ?: seat(PI * 1.5, off)
            }
            heart(cx, cy)
            corners()
            val granary = granaryRole()
            for (k in 0 until 10) {
                val a = PI * 0.15 + k * PI * 0.7 / 9 + if (k % 2 == 0) 0.0 else PI
                packer.templateFor(granary)?.let { packer.placeNear(it, cx + cos(a).toFloat() * court * 0.7f, cy + sin(a).toFloat() * court * 0.7f, 2.5f, 6) }
            }
            roads.forEach { road ->
                packer.lineZoned(road) { x, y -> if (hypot(x - cx, y - cy) < court) granary else roleAt(x, y, sparse = false) }
            }
        }

        fun walled() {
            val gates = g.gates.coerceAtLeast(2)
            val start = random.nextDouble() * 2 * PI
            val angles = List(gates) { start + it * 2 * PI / gates }
            angles.forEach { a -> roads += radial(site, a, plaza.toDouble(), r + EXIT.toDouble(), 3) }
            if (site.radius >= 24) roads += ring(site, r * 0.62f, 12, 2)
            open()
            heart(cx, cy)
            seat(angles.first() + PI / gates, plaza + 6f)
            gatehouses(angles)
            onSquare(angles)
            val sectors = sectorQuarters(gates)
            roads.forEach { road ->
                packer.lineZoned(road) { x, y ->
                    val d = hypot(x - cx, y - cy)
                    if (d < r * 0.3f) pick(Quarter.MARKET, Quarter.SHRINES, Quarter.SCHOOL)
                    else sectorRole(x, y, start, gates, sectors) ?: roleAt(x, y)
                }
            }
            outskirts()
        }

        fun radial() {
            val ways = 4 + random.nextInt(3)
            val start = random.nextDouble() * 2 * PI
            val angles = List(ways) { start + it * 2 * PI / ways }
            angles.forEach { a -> roads += radial(site, a, plaza.toDouble(), r + EXIT.toDouble(), 3) }
            roads += ring(site, r * 0.55f, 12, 2)
            if (site.radius >= 30) roads += ring(site, r * 0.82f, 16, 2)
            open()
            heart(cx, cy)
            // The seat's court faces the centre from between two ways: the town's largest building.
            seat(angles.first() + PI / ways, plaza + 6f)
            onSquare(angles)
            if (g.walled) gatehouses(angles)
            val sectors = sectorQuarters(ways)
            roads.forEach { road -> packer.lineZoned(road) { x, y -> sectorRole(x, y, start, ways, sectors) ?: roleAt(x, y) } }
            outskirts()
        }

        fun lanes() {
            // The waterfront along one side; lanes wander inland from it.
            val shore = random.nextInt(4) * PI / 2
            roads += Road((cx + rot(shore, -r, r * 0.8f).first).roundToInt(), (cy + rot(shore, -r, r * 0.8f).second).roundToInt(), (cx + rot(shore, r, r * 0.8f).first).roundToInt(), (cy + rot(shore, r, r * 0.8f).second).roundToInt(), 3)
            val lanes = 5 + random.nextInt(4)
            for (i in 0 until lanes) {
                val along = -r * 0.8f + i * (1.6f * r / (lanes - 1))
                val (ax, ay) = rot(shore, along, r * 0.75f)
                val (bx, by) = rot(shore, along * 0.6f + (random.nextFloat() - 0.5f) * 8f, -r * 0.6f)
                roads += wander(cx + ax, cy + ay, cx + bx, cy + by, 2)
            }
            roads += ring(site, r * 0.45f, 9, 2)
            open()
            heart(cx, cy)
            seat(shore + PI, r * 0.35f)
            roads.forEach { road ->
                packer.lineZoned(road) { x, y ->
                    val (_, toward) = unrot(shore, x - cx, y - cy)
                    if (toward > r * 0.55f) pick(Quarter.WATERFRONT, Quarter.MARKET, Quarter.STRANGERS) else roleAt(x, y)
                }
            }
            if (g.walled) corners()
        }

        fun citadel() {
            // The enclosure on the height, off to one side; the town in the valley below it.
            val up = random.nextDouble() * 2 * PI
            val hx = cx + cos(up).toFloat() * r * 0.5f
            val hy = cy + sin(up).toFloat() * r * 0.5f
            roads += Road(cx.roundToInt(), cy.roundToInt(), hx.roundToInt(), hy.roundToInt(), 2)
            val ways = 3 + random.nextInt(2)
            List(ways) { up + PI + (it - (ways - 1) / 2.0) * 0.8 }.forEach { a -> roads += winding(a, plaza.toDouble(), r + EXIT.toDouble(), 2) }
            open()
            heart(hx, hy)
            packer.hall()?.let { hall ->
                val sx = hx - cos(up).toFloat() * 6f; val sy = hy - sin(up).toFloat() * 6f
                if (!packer.placeNear(hall, sx, sy, 3f, 12) && !packer.placeNear(hall, sx, sy, 9f, 24)) claim(hall, sx, sy)
            }
            repeat(3) { packer.templateFor(BuildingRole.TEMPLE)?.let { t -> packer.placeNear(t, hx, hy, 7f) } }
            repeat(2) { packer.templateFor(BuildingRole.BARRACKS)?.let { t -> packer.placeNear(t, hx, hy, 9f) } }
            roads.forEach { road -> packer.lineZoned(road) { x, y -> if (hypot(x - hx, y - hy) < r * 0.3f) null else roleAt(x, y, sparse = true) } }
            onSquare(listOf(up))
            outskirts()
        }

        fun linear() {
            val along = random.nextDouble() * PI
            roads += radial(site, along, 0.0, r + EXIT.toDouble(), 3)
            roads += radial(site, along + PI, 0.0, r + EXIT.toDouble(), 3)
            // Back streets either side of the main one, shorter, as the town thins toward its ends.
            for (side in listOf(1f, -1f)) {
                val off = side * r * 0.42f
                val half = kotlin.math.sqrt((r * r - off * off).coerceAtLeast(0f)) * 0.8f
                roads += turned(along, -half, off, half, off, 2)
            }
            // A few short lanes off the street, down to the water or out to the fields.
            repeat(2 + random.nextInt(3)) {
                val d = (random.nextFloat() - 0.5f) * r * 1.2f
                val bx = cx + cos(along).toFloat() * d
                val by = cy + sin(along).toFloat() * d
                val side = along + if (random.nextBoolean()) PI / 2 else -PI / 2
                roads += Road(bx.roundToInt(), by.roundToInt(), (bx + cos(side).toFloat() * r * 0.8f).roundToInt(), (by + sin(side).toFloat() * r * 0.8f).roundToInt(), 2)
            }
            open()
            heart(cx, cy)
            seat(along + PI / 2, plaza + 5f)
            roads.forEach { road ->
                packer.lineZoned(road) { x, y ->
                    val d = hypot(x - cx, y - cy)
                    when {
                        d < r * 0.3f -> pick(Quarter.MARKET, Quarter.SHRINES, Quarter.STRANGERS)
                        d > r * 0.75f -> pick(Quarter.FIELDS, Quarter.GRANARIES, Quarter.WATERFRONT)
                        else -> roleAt(x, y)
                    }
                }
            }
        }

        // ---- shared pieces ----------------------------------------------------------------------

        /** What stands at the heart: a shrine or mosque on the square, a byre, a tower -- whatever the town gathers round. */
        fun heart(x: Float, y: Float) {
            val role = when (g.heart) {
                Heart.CATTLE_BYRE -> BuildingRole.FARM
                Heart.PALACE_COURT -> null
                Heart.SACRED_TREE, Heart.IROKO, Heart.BAOBAB, Heart.MARKET_TREE, Heart.WELL -> null
                else -> BuildingRole.TEMPLE
            } ?: return
            val template = packer.templateFor(role) ?: return
            // Right at the middle when there is no square; else just off it, where the player arrives.
            if (plaza < 0 && packer.placeCentred(template, x, y, Facing.SOUTH)) return
            if (packer.placeNear(template, x, y, plaza.coerceAtLeast(0) + template.width.toFloat(), 14)) return
            packer.placeNear(template, x, y, plaza.coerceAtLeast(0) + template.width * 2.5f, 24)
        }

        /** The seat: the ruler's house, [distance] out along [angle], facing the centre. */
        fun seat(angle: Double, distance: Float) {
            val hall = packer.hall() ?: return
            // Never so far out that it would stand in the wall.
            val reach = (r - maxOf(hall.width, hall.depth) / 2f - 4f).coerceAtLeast(0f)
            val d = minOf(distance + hall.depth / 2f, reach)
            if (placeFacingCentre(packer, site, hall, angle, d)) return
            for (step in 1..12) {
                val a = angle + (if (step % 2 == 0) 1 else -1) * (step / 2) * 0.45
                if (placeFacingCentre(packer, site, hall, a, d - (step % 3) * 2f)) return
            }
            if (packer.placeNear(hall, cx + cos(angle).toFloat() * d * 0.6f, cy + sin(angle).toFloat() * d * 0.6f, r * 0.4f, 24)) return
            // Nowhere free: the seat's compound takes its ground and closes the narrow lanes it stands on.
            claim(hall, cx + cos(angle).toFloat() * d * 0.6f, cy + sin(angle).toFloat() * d * 0.6f)
        }

        /**
         * Places [template] near ([x], [y]) facing the centre, closing any lane
         * (never a main way) under it: the first spot round about where that
         * makes room. True when it stands.
         */
        fun claim(template: BuildingTemplate, x: Float, y: Float): Boolean {
            val base = atan2((y - cy).toDouble(), (x - cx).toDouble())
            val far = hypot(x - cx, y - cy).coerceAtLeast(plaza + 6f)
            for (k in 0 until 24) {
                val a = base + (if (k % 2 == 0) 1 else -1) * (k / 2) * PI / 12
                for (dist in listOf(far, far * 1.4f, far * 0.7f)) {
                    val px = cx + cos(a).toFloat() * dist; val py = cy + sin(a).toFloat() * dist
                    val door = LotPacker.facingToward(cx - px, cy - py)
                    val (w, d) = LotPacker.footprint(template, door)
                    val x0 = (px - w / 2f).roundToInt(); val y0 = (py - d / 2f).roundToInt()
                    val under = roads.filter { road -> (y0 - 1..y0 + d).any { yy -> (x0 - 1..x0 + w).any { xx -> com.stratum.core.domain.settlement.RoadGeometry.covers(road, xx, yy) } } }
                    if (under.any { it.width > 2 }) continue
                    roads.removeAll(under.toSet())
                    if (packer.placeCentred(template, px, py, door)) return true
                    roads.addAll(under)
                }
            }
            return false
        }

        /** Market stalls, shrines and age-grade houses round the square, between the ways out. */
        fun onSquare(angles: List<Double>) {
            val wants = listOf(Quarter.MARKET, Quarter.SHRINES, Quarter.AGE_GRADES, Quarter.SCHOOL).filter { it in g.quarters }
            if (wants.isEmpty()) return
            angles.forEachIndexed { i, a ->
                val q = wants[i % wants.size]
                val role = q.role ?: return@forEachIndexed
                val template = packer.templateFor(role) ?: return@forEachIndexed
                placeFacingCentre(packer, site, template, a + PI / angles.size, plaza + 2f + template.depth / 2f)
            }
        }

        /** Houses (or [role]) in a ring at [radius], doors to the centre, leaving the gate at [gate] clear and [skip] of the ring empty. */
        fun ring(radius: Float, role: BuildingRole, gate: Double, skip: Double) {
            val slots = ((2 * PI * radius) / RING_SLOT).toInt().coerceAtLeast(4)
            for (i in 0 until slots) {
                val a = gate + PI / slots + i * 2 * PI / slots
                if (angleGap(a, gate) < 0.35) continue
                if (random.nextDouble() < skip * 0.3) continue
                val template = packer.templateFor(role) ?: packer.templateFor(BuildingRole.HOUSE) ?: return
                // A slot taken by the seat or the byre: try just in, out or along before giving it up.
                NUDGES.firstOrNull { (dr, da) -> placeFacingCentre(packer, site, template, a + da, radius + dr) }
            }
        }

        /** A family compound: a few houses and a granary round their own yard at ([x], [y]). */
        fun compoundAt(x: Float, y: Float, houses: Int) {
            repeat(houses) { packer.templateFor(BuildingRole.HOUSE)?.let { packer.placeNear(it, x, y, 7f) } }
            packer.templateFor(granaryRole())?.let { packer.placeNear(it, x, y, 6f) }
        }

        /** Gatehouses where the roads leave the wall. */
        fun gatehouses(angles: List<Double>) {
            angles.forEach { a ->
                val tower = packer.templateFor(BuildingRole.TOWER) ?: return
                val inside = r - 5f
                val side = a + 0.12
                packer.placeCentred(tower, cx + cos(side).toFloat() * inside, cy + sin(side).toFloat() * inside, LotPacker.facingToward(-cos(a).toFloat(), -sin(a).toFloat()))
            }
        }

        /** Towers at a square town's four corners. */
        fun corners() {
            for ((sx, sy) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
                val tower = packer.templateFor(BuildingRole.TOWER) ?: return
                packer.placeNear(tower, cx + sx * (r - 6f), cy + sy * (r - 6f), 2f)
            }
        }

        /** Farms and granaries scattered round the edge, for a town that farms. */
        fun outskirts() {
            if (Quarter.FIELDS !in g.quarters && Quarter.GRANARIES !in g.quarters) return
            val n = (site.radius / 5).coerceIn(2, 10)
            repeat(n) {
                val a = random.nextDouble() * 2 * PI
                val role = if (Quarter.FIELDS in g.quarters && random.nextBoolean()) BuildingRole.FARM else granaryRole()
                val template = packer.templateFor(role) ?: return@repeat
                packer.placeNear(template, cx + cos(a).toFloat() * r * 0.82f, cy + sin(a).toFloat() * r * 0.82f, 4f, 4)
            }
        }

        /** A quarter for each sector between two roads, in the order the genome lists them. */
        fun sectorQuarters(sectors: Int): List<Quarter> {
            val pool = g.quarters.filter { it != Quarter.SEAT && it != Quarter.SQUARE && it != Quarter.GATES }.ifEmpty { listOf(Quarter.COMPOUNDS) }
            return List(sectors) { i -> if (i % 2 == 1) Quarter.COMPOUNDS else pool[(i / 2) % pool.size] }
        }

        fun sectorRole(x: Float, y: Float, start: Double, sectors: Int, quarters: List<Quarter>): BuildingRole? {
            if (inRuin(x, y)) return null
            val a = ((atan2((y - cy).toDouble(), (x - cx).toDouble()) - start) % (2 * PI) + 2 * PI) % (2 * PI)
            val q = quarters[(a / (2 * PI / sectors)).toInt().coerceIn(0, sectors - 1)]
            return mix(q)
        }

        /** The role wanted at a spot with no stronger rule: compounds mostly, the town's other quarters scattered through them by patches. */
        fun roleAt(x: Float, y: Float, sparse: Boolean = false): BuildingRole? {
            if (inRuin(x, y)) return null
            // Patches a dozen blocks across share a use, so a street has a smiths' end and a weavers' end.
            val patch = (Math.floorDiv(x.toInt(), 12) * 73856093) xor (Math.floorDiv(y.toInt(), 12) * 19349663) xor g.seed.toInt()
            val pool = g.quarters.filter { it.role != null && it != Quarter.SEAT && it != Quarter.GATES }
            if (sparse && (patch and 7) == 0) return null
            val q = if (pool.isEmpty() || (patch ushr 3) % 3 != 0) Quarter.COMPOUNDS else pool[Math.floorMod(patch ushr 5, pool.size)]
            return mix(q)
        }

        /** A quarter's role, with a granary now and then among a farming people's houses. */
        fun mix(q: Quarter): BuildingRole? {
            val role = q.role ?: return null
            if (role == BuildingRole.HOUSE && Quarter.GRANARIES in g.quarters && random.nextFloat() < 0.18f) return granaryRole()
            return role
        }

        fun pick(vararg wanted: Quarter): BuildingRole? = mix(wanted.firstOrNull { it in g.quarters } ?: Quarter.COMPOUNDS)

        fun granaryRole(): BuildingRole = BuildingRole.WAREHOUSE

        fun inRuin(x: Float, y: Float): Boolean {
            val (angle, half) = ruin ?: return false
            if (hypot(x - cx, y - cy) < r * 0.3f) return false
            val a = atan2((y - cy).toDouble(), (x - cx).toDouble())
            return angleGap(a, angle) < half && random.nextFloat() < 0.7f
        }

        /** A path out along [angle], bending a little at each third so it reads as trodden rather than surveyed. */
        fun winding(angle: Double, from: Double, to: Double, width: Int): List<Road> {
            val pts = (0..3).map { i ->
                val d = from + (to - from) * i / 3.0
                val bend = if (i == 0 || i == 3) 0.0 else (random.nextDouble() - 0.5) * 0.35
                (site.centerX + cos(angle + bend) * d).roundToInt() to (site.centerY + sin(angle + bend) * d).roundToInt()
            }
            return pts.zipWithNext().map { (a, b) -> Road(a.first, a.second, b.first, b.second, width) }
        }

        /** A lane from one point to another in three crooked pieces. */
        fun wander(ax: Float, ay: Float, bx: Float, by: Float, width: Int): List<Road> {
            val pts = (0..3).map { i ->
                val t = i / 3f
                val jitter = if (i == 0 || i == 3) 0f else (random.nextFloat() - 0.5f) * 6f
                (ax + (bx - ax) * t + jitter).roundToInt() to (ay + (by - ay) * t - jitter).roundToInt()
            }
            return pts.zipWithNext().map { (a, b) -> Road(a.first, a.second, b.first, b.second, width) }
        }

        fun rot(turn: Double, x: Float, y: Float): Pair<Float, Float> {
            val c = cos(turn).toFloat(); val s = sin(turn).toFloat()
            return (x * c - y * s) to (x * s + y * c)
        }

        fun unrot(turn: Double, x: Float, y: Float): Pair<Float, Float> = rot(-turn, x, y)

        fun turned(turn: Double, x0: Float, y0: Float, x1: Float, y1: Float, width: Int): Road {
            val (ax, ay) = rot(turn, x0, y0); val (bx, by) = rot(turn, x1, y1)
            return Road((cx + ax).roundToInt(), (cy + ay).roundToInt(), (cx + bx).roundToInt(), (cy + by).roundToInt(), width)
        }
    }

    private fun angleGap(a: Double, b: Double): Double {
        val d = abs(((a - b) % (2 * PI) + 3 * PI) % (2 * PI) - PI)
        return d
    }

    private const val RING_SLOT = 7.5f
    /** Where else a ring slot may go, in blocks out and radians along, when its own spot is taken. */
    private val NUDGES = listOf(0f to 0.0, 1.5f to 0.0, -1.5f to 0.0, 0f to 0.18, 0f to -0.18, 2.5f to 0.1, -2.5f to -0.1)
    private const val ROW = 11f
}

/**
 * The buildings a town needs, made from what its recipe has: the recipe's
 * own templates first, and for each role a quarter needs that the recipe
 * lacks, one cut from the recipe's materials and named in the town's way.
 */
internal object Furnish {
    fun recipeFor(recipe: SettlementRecipe, g: CityGenome): SettlementRecipe {
        val base = recipe.buildings.firstOrNull { it.role == BuildingRole.HOUSE } ?: recipe.buildings.firstOrNull()
            ?: return recipe
        val have = recipe.buildings.mapTo(HashSet()) { it.role }
        val made = ArrayList<BuildingTemplate>()
        fun add(role: BuildingRole, name: String, w: Int, d: Int, h: Int, max: Int = Int.MAX_VALUE, weight: Int = 100, roof: Boolean = true) {
            if (role in have || made.any { it.role == role }) return
            made += base.copy(
                id = "${recipe.id}/${role.name.lowercase()}", name = name, role = role, width = w, depth = d, height = h.coerceAtMost(base.height + 3),
                roofBlockId = if (roof) base.roofBlockId else null, furnitureBlockId = null, windowBlockId = null,
                minCount = 0, maxCount = max, weight = weight,
            )
        }
        val craftOf = { role: BuildingRole -> g.crafts.firstOrNull { it.role == role } }
        g.quarters.forEach { q ->
            when (q) {
                Quarter.SEAT -> add(BuildingRole.HALL, g.rule.seat, 9, 7, base.height + 1, max = 1)
                Quarter.MARKET -> add(BuildingRole.SHOP, craftOf(BuildingRole.SHOP)?.workplace ?: "Market stall", 4, 3, 2, weight = 70)
                Quarter.SHRINES, Quarter.SCHOOL -> add(BuildingRole.TEMPLE, shrineName(g, q), 5, 5, base.height + 1, max = if (Mark.SHRINE in g.marks) 3 else 2)
                Quarter.CRAFTS -> add(BuildingRole.SMITHY, craftOf(BuildingRole.SMITHY)?.workplace ?: "Smithy", 6, 5, base.height)
                Quarter.GRANARIES, Quarter.WATERFRONT -> add(BuildingRole.WAREHOUSE, craftOf(BuildingRole.WAREHOUSE)?.workplace ?: if (q == Quarter.WATERFRONT) Quarter.WATERFRONT.building else "Granary", 3, 3, base.height, weight = 60)
                Quarter.FIELDS -> add(BuildingRole.FARM, craftOf(BuildingRole.FARM)?.workplace ?: "Farm house", 7, 5, 2, weight = 50, roof = Craft.CATTLE !in g.crafts)
                Quarter.STRANGERS -> add(BuildingRole.TAVERN, craftOf(BuildingRole.TAVERN)?.workplace ?: "Rest house for strangers", 6, 5, base.height, max = 3)
                Quarter.GATES -> add(BuildingRole.TOWER, "Gatehouse", 3, 3, base.height + 3, max = g.gates.coerceAtLeast(4))
                Quarter.GUARD -> add(BuildingRole.BARRACKS, "Guard house", 7, 4, base.height, max = 3)
                Quarter.AGE_GRADES, Quarter.COMPOUNDS, Quarter.SQUARE -> Unit
            }
        }
        // What the heart needs: a byre for a herding town, a house of prayer or of the ancestors for most.
        when (g.heart) {
            Heart.CATTLE_BYRE -> add(BuildingRole.FARM, "Cattle byre", 7, 7, 2, roof = false)
            Heart.SACRED_TREE, Heart.IROKO, Heart.BAOBAB, Heart.MARKET_TREE, Heart.WELL, Heart.PALACE_COURT -> Unit
            else -> add(BuildingRole.TEMPLE, shrineName(g, Quarter.SHRINES), 5, 5, base.height + 1, max = 2)
        }
        // Nearly every settlement keeps its grain or its stores in granaries.
        add(BuildingRole.WAREHOUSE, "Granary", 3, 3, base.height, weight = 60)
        val wall = when {
            g.walled -> recipe.wallBlockId ?: recipe.foundationBlockId
            else -> null
        }
        return recipe.copy(buildings = recipe.buildings + made, wallBlockId = wall, names = listOf(g.name))
    }

    private fun shrineName(g: CityGenome, q: Quarter): String = when {
        q == Quarter.SCHOOL && Craft.MANUSCRIPTS in g.crafts -> "Library and school"
        g.heart == Heart.MOSQUE || g.heart == Heart.CORAL_MOSQUE -> "Mosque"
        g.heart == Heart.CHURCH -> "Church"
        g.heart == Heart.MBARI -> "Mbari house"
        g.heart == Heart.TOGUNA -> "Toguna"
        g.heart == Heart.ANCESTOR_HOUSE -> "House of the ancestors"
        g.heart == Heart.MASK_GROUND -> "Masquerade house"
        else -> "Shrine"
    }
}

/** Which people a recipe's towns belong to, when nothing says: read from its id, else the Igbo. */
object CultureOfRecipe {
    fun of(recipe: SettlementRecipe): String = Cultures.ids.firstOrNull { recipe.id.contains(it) } ?: "igbo"
}
