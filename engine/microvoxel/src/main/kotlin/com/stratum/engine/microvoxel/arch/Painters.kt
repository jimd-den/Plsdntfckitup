package com.stratum.engine.microvoxel.arch

import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.gen.Hash
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The shared shape of a walled building, which most traditions refine: a
 * wall ring three voxels thick keeping the block promise (see [Building]),
 * a door and its jambs, windows, and hooks for what makes a tradition itself
 * -- its skin, its ornaments outside the wall, its roof, and whether it
 * builds this one round.
 */
abstract class WalledPainter(protected val m: ArchPalette) : Tradition {
    protected val AIR: Short = MaterialPalette.AIR
    protected val thatch = m[M.THATCH]
    protected val thatchDark = m[M.THATCH_DARK]
    protected val timber = m[M.TIMBER]

    override val reach: Int = 3
    override fun top(b: Building): Int = b.wallTop + max(b.width, b.depth) / 2 + 8

    /** Door frame (jambs and lintel). */
    protected open val frame: Short get() = timber

    /** Builds this one round instead (a hut, a granary, a dome); see [round]. */
    protected open fun isRound(b: Building): Boolean = false

    override fun voxel(b: Building, x: Int, y: Int, z: Int): Short {
        if (isRound(b)) return round(b, x, y, z)
        val front = frontOfDoor(b, x, y)
        if (!front) ornament(b, x, y, z).let { if (it != KEEP) return it }
        if (z > b.wallTop) return roof(b, x, y, z)
        if (!b.inside(x, y)) return outside(b, x, y, z)
        val e = b.edge(x, y)
        if (e > 2) return interior(b, x, y, z)
        val h = z - b.base
        val r = b.r
        if (b.isDoor(x, y)) return when {
            h < 2 * r -> AIR
            h <= 2 * r + 1 -> frame
            else -> if (e == 0) skin(b, x, y, z, h) ?: b.wall else b.wall
        }
        if (b.isJamb(x, y) && h <= 2 * r && e <= 1) return frame
        if (b.isWindow(x, y, z) && !b.isCornerCell(x, y)) return windowVoxel(b, x, y, z, e)
        if (e == 0) return skin(b, x, y, z, h) ?: b.wall
        return b.wall
    }

    /** Right in front of the door, where nothing may stand. */
    protected fun frontOfDoor(b: Building, x: Int, y: Int): Boolean {
        if (b.inside(x, y)) return false
        val out = b.out(x, y)
        // Step back towards the wall as far as we are out: in front of the door lands in the door cell.
        return out <= 4 && b.isDoor(x - b.door.dx * out, y - b.door.dy * out)
    }

    /** The wall's outer voxel; null for the plan's wall. */
    protected open fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short? = null

    /** Anything outside the wall or above it that is not roof: buttresses, beams, posts, towers. KEEP for none. */
    protected open fun ornament(b: Building, x: Int, y: Int, z: Int): Short = KEEP

    /** Outside the box, below the wall top. */
    protected open fun outside(b: Building, x: Int, y: Int, z: Int): Short = KEEP

    protected open fun interior(b: Building, x: Int, y: Int, z: Int): Short = AIR

    protected open fun windowVoxel(b: Building, x: Int, y: Int, z: Int, e: Int): Short =
        if (e == 0 && ((x + y + z) and 1) == 0) AIR else b.window!!

    protected abstract fun roof(b: Building, x: Int, y: Int, z: Int): Short

    protected open fun round(b: Building, x: Int, y: Int, z: Int): Short = KEEP

    // ---- Roofs ----------------------------------------------------------------------

    /** Thatch laid in courses: a lighter band every third layer, as bundles are laid. */
    protected fun courses(b: Building, z: Int, light: Short = thatch): Short = if ((z / 3) % 2 == 0) light else b.roof ?: thatchDark

    /**
     * A gable roof along the building's long axis: [pitch] up per voxel
     * across, [eaves] over the walls, gable ends filled with [gable].
     */
    protected fun gable(b: Building, x: Int, y: Int, z: Int, eaves: Int, pitch: Float, ragged: Boolean, gableMat: Short, cap: Boolean = true, surface: (Int) -> Short): Short {
        val ridgeAlongY = b.width <= b.depth
        val a = if (ridgeAlongY) x - b.x0 else y - b.y0
        val span = if (ridgeAlongY) b.width else b.depth
        val along = if (ridgeAlongY) y - b.y0 else x - b.x0
        val length = if (ridgeAlongY) b.depth else b.width
        if (a < -eaves || a >= span + eaves || along < -2 || along >= length + 2) return KEEP
        val rise = min(a, span - 1 - a) * pitch
        val roofZ = b.wallTop + 1 + rise
        val dz = z - roofZ
        if (dz in -2.5f..0.5f) {
            if (ragged && (a == -eaves || a == span - 1 + eaves) && Hash.unit(b.seed, x, y, z, 62) < 0.35f) return AIR
            return surface(z)
        }
        if (cap && dz in 0.5f..1.5f && rise >= (span - 1) / 2f * pitch - pitch) return if ((along and 1) == 0 || along <= 0 || along >= length - 1) thatchDark else KEEP
        if (dz < -2.5f && a in 0 until span && along in 0 until length) return if (along <= 2 || along >= length - 3) gableMat else AIR
        return KEEP
    }

    /** A hipped roof: sloping on all four sides from [eaves] beyond the walls. */
    protected fun hip(b: Building, x: Int, y: Int, z: Int, eaves: Int, pitch: Float, surface: (Int) -> Short): Short {
        val dxIn = min(x - (b.x0 - eaves), (b.x1 + eaves) - x)
        val dyIn = min(y - (b.y0 - eaves), (b.y1 + eaves) - y)
        val d = min(dxIn, dyIn)
        if (d < 0) return KEEP
        val roofZ = b.wallTop + 1 + (d - eaves).coerceAtLeast(0) * pitch + if (d < eaves) (d - eaves) * 0.3f else 0f
        val dz = z - roofZ
        if (dz in -2.5f..0.5f) return surface(z)
        if (dz < -2.5f && b.inside(x, y)) return AIR
        return KEEP
    }

    /** A flat roof slab with a parapet [parapet] voxels high around its edge; [merlon] shapes the parapet's top. */
    protected fun flat(b: Building, x: Int, y: Int, z: Int, slab: Short, parapetMat: Short, parapet: Int, merlon: (along: Int) -> Int = { 0 }): Short {
        if (!b.inside(x, y)) return KEEP
        val dz = z - b.wallTop
        if (dz in 1..2) return slab
        val e = b.edge(x, y)
        if (e == 0 && dz in 3..2 + parapet + merlon(b.along(x, y))) return parapetMat
        return KEEP
    }

    /** A cone over a round plan or around a point: [radius] at the eaves, rising [pitch] per voxel inward. */
    protected fun cone(cx: Float, cy: Float, baseZ: Int, radius: Float, pitch: Float, x: Int, y: Int, z: Int, surface: (Int) -> Short, ragged: Boolean, seed: Long): Short {
        val dx = x - cx; val dy = y - cy
        val d = sqrt(dx * dx + dy * dy)
        if (d > radius) return KEEP
        val coneZ = baseZ + (radius - d) * pitch
        val dz = z - coneZ
        if (d < 1.2f && z <= coneZ + 3 && z > baseZ) return thatchDark // finial
        if (dz in -2.5f..0.5f) {
            if (ragged && d > radius - 1 && Hash.unit(seed, x, y, z, 63) < 0.35f) return AIR
            return surface(z)
        }
        return if (dz < -2.5f && z > baseZ) AIR else KEEP
    }

    // ---- Round buildings ---------------------------------------------------------------

    /**
     * A round-walled hut on the building's square: wall ring three voxels
     * thick, the door as a gap facing the plan's door, a plinth, and [roofOf]
     * above the wall. [skinOf] paints the outer voxel by height and angle.
     */
    protected fun roundHut(
        b: Building, x: Int, y: Int, z: Int, plinth: Short,
        skinOf: (h: Int, angle: Float, radius: Float) -> Short?,
        roofOf: (radius: Float) -> Short,
    ): Short {
        val radius = b.width / 2f - 0.5f
        val d = b.radial(x, y)
        if (z > b.wallTop) return roofOf(radius)
        if (d > radius + 1) return KEEP
        val h = z - b.base
        if (d > radius) return if (h < 2) plinth else KEEP
        if (d < radius - 3) return AIR
        val facing = atan2(y - b.cy, x - b.cx)
        val doorAngle = atan2(b.door.dy.toFloat(), b.door.dx.toFloat())
        var off = abs(facing - doorAngle); if (off > Math.PI) off = (2 * Math.PI - off).toFloat()
        if (off < 2.2f / radius && h < 2 * b.r) return AIR
        if (off < 3.2f / radius && h <= 2 * b.r) return frame
        if (d > radius - 1) skinOf(h, facing, radius)?.let { return it }
        return b.wall
    }

    /**
     * A shell of revolution over the building's plan: at each height the
     * plan shrinks to [profile] (1 at the foot, 0 at the crown) of itself, as
     * a rounded rectangle. The outer voxel is [skinOf]; the layers inside it
     * keep the block promise with the plan's wall; the door is cut through.
     */
    protected fun shell(
        b: Building, x: Int, y: Int, z: Int, height: Int, roundness: Float,
        profile: (t: Float) -> Float, skinOf: (h: Int, angle: Float) -> Short,
    ): Short {
        val h = z - b.base
        if (h < 0 || h > height) return KEEP
        val t = h.toFloat() / height
        val rho = profile(t)
        if (rho <= 0f) return KEEP
        val s = b.squircle(x, y, roundness)
        if (s > rho) return KEEP
        val halfMin = min(b.width, b.depth) / 2f
        val inner1 = rho - 1f / halfMin; val inner3 = rho - 3.2f / halfMin
        if (s < inner3 && h < height - 3) return AIR
        if (b.isDoor(x, y) && h < 2 * b.r) return AIR
        if (b.isJamb(x, y) && h <= 2 * b.r && b.inside(x, y)) return frame
        if (s >= inner1) return skinOf(h, atan2(y - b.cy, x - b.cx))
        return b.wall
    }
}

// ================================================================================================
// West Africa
// ================================================================================================

/**
 * Sudano-Sahelian: Djenné, Timbuktu, Mopti, Agadez, Bobo-Dioulasso. Sun-dried
 * adobe under a smooth mud render, renewed every year (Djenné's Crépissage);
 * engaged pilasters rising into rounded pinnacles; toron -- palm beams left
 * jutting from the wall as permanent scaffolding for the replastering; flat
 * roofs behind parapets. The Great Mosque of Djenné (rebuilt 1907) and the
 * Djinguereber in Timbuktu (1327) are its monuments.
 */
class SudanoSahelian(m: ArchPalette) : WalledPainter(m) {
    override val id = "sudano_sahelian"
    override val name = "Sudano-Sahelian"
    override val origin = "The mud architecture of the Niger bend from the 13th century: Djenné, Timbuktu, Mopti, Agadez. Adobe under a mud render " +
        "renewed each year, pilasters rising into pinnacles, toron palm beams left in the walls as scaffolding, flat roofs."
    override val town = TownStyle(WallStyle.ADOBE_PINNACLED, yard = com.stratum.engine.microvoxel.geo.R.ERG_SAND, sacred = Sacred.BAOBAB)
    override fun top(b: Building) = b.wallTop + 12
    private val render = m[A.RENDER]; private val adobe = m[A.ADOBE]; private val toron = m[A.TORON]; private val white = m[A.PAINT_WHITE]

    override val frame: Short get() = toron

    private fun pilaster(b: Building, x: Int, y: Int) = Math.floorMod(b.along(x, y) + 2, 8) < 2

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short = render

    override fun ornament(b: Building, x: Int, y: Int, z: Int): Short {
        val out = b.out(x, y)
        if (out !in 1..3 || z < b.base) return KEEP
        // Measure along the face we are outside of.
        val px = x.coerceIn(b.x0, b.x1); val py = y.coerceIn(b.y0, b.y1)
        val onPilaster = pilaster(b, px, py) || b.isCornerCell(px, py) && b.edge(px, py) == 0
        val h = z - b.base
        val parapetTop = b.wallTop + 4
        if (out == 1 && onPilaster) {
            if (z <= parapetTop) return adobe
            // The pinnacle: tapering above the parapet, white-capped where the corners carry egg finials.
            val corner = b.isCornerCell(px, py)
            val tip = parapetTop + if (corner) 8 else 5
            if (z <= tip) return if (corner && z == tip) white else adobe
            return KEEP
        }
        // Toron: two rows of beam ends, three voxels proud of the wall.
        if (out in 1..3 && z <= b.wallTop && (h == 5 || h == 10) && Math.floorMod(b.along(px, py), 8) == 5) return toron
        return KEEP
    }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short =
        flat(b, x, y, z, slab = b.roof ?: adobe, parapetMat = render, parapet = 2)
}

/**
 * Hausa: Kano, Zaria, Katsina. Mud walls under a lime-and-mud plaster, flat
 * roofs spanned by arched ribs (the soro), and zanko -- horn-like pinnacles --
 * at every corner of the parapet. Facades carry zayyana, raised relief of
 * interlace and knots around the entrance: the Emir's palace at Zaria, the
 * Gidan Makama in Kano.
 */
class Hausa(m: ArchPalette) : WalledPainter(m) {
    override val id = "hausa"
    override val name = "Hausa"
    override val origin = "The city architecture of Kano, Zaria and Katsina: lime-plastered mud, flat roofs on earthen ribs, zanko horns at the parapet " +
        "corners, and zayyana relief patterns worked around the entrance by master builders (magina)."
    override val town = TownStyle(WallStyle.MUD_COPED, sacred = Sacred.BAOBAB)
    override fun top(b: Building) = b.wallTop + 10
    private val plaster = m[A.HAUSA_PLASTER]; private val relief = m[A.HAUSA_RELIEF]

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short {
        if (h == b.height - 3) return relief
        return plaster
    }

    override fun ornament(b: Building, x: Int, y: Int, z: Int): Short {
        val out = b.out(x, y)
        // Zayyana: a raised interlace one voxel proud, framing the door.
        if (out == 1 && z in b.base + 2..b.wallTop - 2) {
            val px = x.coerceIn(b.x0, b.x1); val py = y.coerceIn(b.y0, b.y1)
            if (b.face(px, py) == b.door) {
                val doorAlong = if (b.door == Side.NORTH || b.door == Side.SOUTH) b.doorX * b.r + b.r / 2 - b.x0 else b.doorY * b.r + b.r / 2 - b.y0
                val a = b.along(px, py) - doorAlong
                val h = z - b.base
                if (abs(a) in 3..12 && (Math.floorMod(a + h, 6) == 0 || Math.floorMod(a - h, 6) == 0)) return relief
            }
        }
        // Zanko: horns at the parapet corners, leaning out.
        if (z > b.wallTop + 2) {
            val cornerX = if (x <= b.cx) b.x0 else b.x1; val cornerY = if (y <= b.cy) b.y0 else b.y1
            val k = z - (b.wallTop + 3)
            if (k in 0..6) {
                val lean = k / 3
                val hx = cornerX + (if (cornerX == b.x0) -lean else lean); val hy = cornerY + (if (cornerY == b.y0) -lean else lean)
                val w = if (k < 3) 1 else 0
                if (abs(x - hx) <= w && abs(y - hy) <= w) return plaster
            }
        }
        return KEEP
    }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short =
        flat(b, x, y, z, slab = b.roof ?: plaster, parapetMat = plaster, parapet = 2) { along -> if (Math.floorMod(along, 10) == 5) 2 else 0 }
}

/**
 * Igbo: the compounds of south-eastern Nigeria. Red earth walls on a low
 * plinth, painted by women with uli -- flowing line designs in white nzu
 * chalk over a dark dado -- under steep thatch with ragged eaves; small
 * round huts beside the rectangular houses; the obi, the head of the
 * family's meeting house, at the front.
 */
class Igbo(m: ArchPalette) : WalledPainter(m) {
    override val id = "igbo"
    override val name = "Igbo"
    override val origin = "The family compounds of south-eastern Nigeria: red earth walls on a plinth painted with uli in white nzu chalk, steep " +
        "thatch with ragged eaves, round huts, an obi meeting house and a sacred tree."
    override val town = TownStyle(WallStyle.MUD_COPED, sacred = Sacred.IROKO)
    private val mudDark = m[M.MUD_DARK]; private val nzu = m[M.NZU]

    override fun isRound(b: Building) = b.roundable && ((b.x0 / b.r) * 31 + (b.y0 / b.r) * 17) and 1 == 0

    override fun outside(b: Building, x: Int, y: Int, z: Int): Short =
        if (b.out(x, y) == 1 && z < b.base + 2 && !b.isDoor(x - b.door.dx, y - b.door.dy)) mudDark else KEEP

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short? {
        // Rounded outer corners, a quarter block.
        val dW = x - b.x0; val dE = b.x1 - x; val dN = y - b.y0; val dS = b.y1 - y
        if (min(dW, dE) + min(dN, dS) == 0) return AIR
        if (b.height < 8) return null
        if (h == 3) return mudDark
        val along = Math.floorMod(if (dN == 0 || dS == 0) x else y, 4)
        if ((h == 5 && along == 0) || (h == 6 && (along == 1 || along == 3)) || (h == 7 && along == 2)) return nzu
        return null
    }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short =
        gable(b, x, y, z, eaves = 3, pitch = 1f, ragged = true, gableMat = b.wall) { courses(b, it) }

    override fun round(b: Building, x: Int, y: Int, z: Int): Short = roundHut(b, x, y, z, mudDark,
        skinOf = { h, facing, radius -> if (h == 3) mudDark else if ((h == 5 || h == 6) && Math.floorMod((facing * radius).toInt() + h, 4) == 0) nzu else null },
        roofOf = { radius -> cone(b.cx, b.cy, b.wallTop, radius + 3, 1.15f, x, y, z, { courses(b, it) }, true, b.seed) },
    )
}

/**
 * Yoruba: the courtyard compounds (agbo ile) of Oyo, Ife and Ibadan. Wide
 * verandas under deep hipped roofs held on carved wooden posts (opo) --
 * the veranda posts of Olowe of Ise are among Africa's great sculpture.
 */
class Yoruba(m: ArchPalette) : WalledPainter(m) {
    override val id = "yoruba"
    override val name = "Yoruba"
    override val origin = "The courtyard compounds of Oyo, Ife and Ibadan: red earth walls, deep hipped roofs over wide verandas held on carved " +
        "veranda posts (opo), like those carved by Olowe of Ise for the palace at Ikere."
    override val town = TownStyle(WallStyle.MUD_COPED, sacred = Sacred.IROKO)
    override val reach = 6
    private val post = m[A.POST]; private val dark = m[A.ADOBE_DARK]; private val plaster = m[A.RED_POLISH]

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short = if (h < 3) dark else plaster

    override fun ornament(b: Building, x: Int, y: Int, z: Int): Short {
        // Carved posts along the veranda's edge, under the eaves, on every face.
        if (b.out(x, y) != 4 || z < b.base || z > b.wallTop + 1) return KEEP
        val px = x.coerceIn(b.x0, b.x1); val py = y.coerceIn(b.y0, b.y1)
        val corner = x !in b.x0..b.x1 && y !in b.y0..b.y1
        val a = b.along(px, py)
        return if (corner || Math.floorMod(a, 7) == 3) (if ((z - b.base) % 3 == 1) timber else post) else KEEP
    }

    override fun outside(b: Building, x: Int, y: Int, z: Int): Short =
        if (b.out(x, y) in 1..4 && z == b.base - 1) dark else KEEP // the raised veranda floor

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short = hip(b, x, y, z, eaves = 5, pitch = 0.8f) { courses(b, it) }
}

/**
 * Asante: the shrine houses of Kumasi (UNESCO, 1980): four buildings round a
 * courtyard, walls burnished red to the knee and white above, carved with
 * relief spirals and symbols in red, under steep thatch.
 */
class Asante(m: ArchPalette) : WalledPainter(m) {
    override val id = "asante"
    override val name = "Asante"
    override val origin = "The traditional buildings of Kumasi, Ghana (18th-19th century, UNESCO World Heritage): walls burnished red below and " +
        "white above, raised relief of spirals and symbols, steep thatched roofs round a courtyard."
    override val town = TownStyle(WallStyle.MUD_COPED, sacred = Sacred.IROKO)
    private val red = m[A.RED_POLISH]; private val white = m[A.PAINT_WHITE]

    private val spiral = arrayOf("#####", "#...#", "#.#.#", "#.###", "#....")

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short {
        if (h < 5) return red
        val band = h - 6
        if (band in 0..4) {
            val a = Math.floorMod(b.along(x, y), 8)
            if (a in 1..5 && spiral[4 - band][a - 1] == '#') return red
        }
        return white
    }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short =
        gable(b, x, y, z, eaves = 3, pitch = 1.35f, ragged = false, gableMat = white) { courses(b, it) }
}

/**
 * Benin (Edo): Benin City, whose earthworks were among the largest ever
 * built. Red laterite walls polished and ribbed with horizontal ridges;
 * roofs sloping inward to an impluvium open to the sky.
 */
class Benin(m: ArchPalette) : WalledPainter(m) {
    override val id = "benin"
    override val name = "Benin (Edo)"
    override val origin = "The palace and compounds of Benin City, Nigeria: red laterite walls polished and ribbed with horizontal ridges, roofs " +
        "sloping inward to an impluvium that catches the rain, all inside the city's vast earthen walls."
    override val town = TownStyle(WallStyle.MUD_COPED, sacred = Sacred.IROKO)
    private val red = m[A.RED_POLISH]

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short = red

    override fun ornament(b: Building, x: Int, y: Int, z: Int): Short =
        if (b.out(x, y) == 1 && z in b.base + 1..b.wallTop - 1 && (z - b.base) % 3 == 0) red else KEEP

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short {
        val eaves = 2
        if (b.out(x, y) > eaves) return KEEP
        // Distance from the centre as a share of the half-extent, square: the roof rises outward.
        val d = max(abs(x - b.cx) / (b.width / 2f + eaves), abs(y - b.cy) / (b.depth / 2f + eaves))
        if (d < 0.34f) return KEEP // the impluvium: open to the sky
        val roofZ = b.wallTop + 1 + (d - 0.34f) * 9f
        val dz = z - roofZ
        if (dz in -2f..0.5f) return courses(b, z)
        return if (dz < -2f && b.inside(x, y)) AIR else KEEP
    }
}

/**
 * Dogon: villages on the Bandiagara escarpment (UNESCO). Flat-roofed mud
 * houses whose facades carry rows of niches (the ginna, the lineage house),
 * and granaries with steep millet-thatch hats.
 */
class Dogon(m: ArchPalette) : WalledPainter(m) {
    override val id = "dogon"
    override val name = "Dogon"
    override val origin = "The villages of the Bandiagara escarpment, Mali (UNESCO World Heritage): flat-roofed mud houses, the ginna with its " +
        "facade of niches, square granaries under steep millet-thatch hats, and the toguna where the men meet."
    override val town = TownStyle(WallStyle.MUD_COPED, yard = com.stratum.engine.microvoxel.geo.R.SANDSTONE_RED, sacred = Sacred.TOGUNA)
    private val adobe = m[A.ADOBE]; private val dark = m[A.ADOBE_DARK]; private val millet = m[A.MILLET_THATCH]

    override fun isRound(b: Building) = b.width <= 3 * b.r && b.depth <= 3 * b.r

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short {
        // Niches in rows on the door face, above head height.
        if (b.onDoorFace(x, y) && h >= 6 && Math.floorMod(b.along(x, y), 4) in 1..2 && Math.floorMod(h, 4) in 1..2) return AIR
        return adobe
    }

    override fun ornament(b: Building, x: Int, y: Int, z: Int): Short {
        // Rounded posts at the parapet corners.
        if (z in b.wallTop + 1..b.wallTop + 5 && b.edge(x, y) == 0 && b.isCornerCell(x, y) &&
            (x == b.x0 || x == b.x1) && (y == b.y0 || y == b.y1)
        ) return dark
        return KEEP
    }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short = flat(b, x, y, z, slab = b.roof ?: adobe, parapetMat = adobe, parapet = 1)

    /** A granary: a square mud body raised on stone feet under a tall millet-thatch hat. */
    override fun round(b: Building, x: Int, y: Int, z: Int): Short {
        if (z > b.wallTop) {
            val radius = b.width / 2f + 1.5f
            return cone(b.cx, b.cy, b.wallTop, radius, 1.6f, x, y, z, { if ((it / 2) % 2 == 0) millet else thatchDark }, true, b.seed)
        }
        if (!b.inside(x, y)) return KEEP
        val e = b.edge(x, y); val h = z - b.base
        if (b.isDoor(x, y) && h < 2 * b.r) return AIR
        if (e > 2) return AIR
        return if (e == 0) (if (h < 2) dark else adobe) else b.wall
    }
}

/**
 * Batammariba: the takienta tower-houses of Koutammakou (Togo and Benin,
 * UNESCO): two-storey fortified houses of round turrets -- granaries --
 * joined by walls, each turret under a conical thatch cap.
 */
class Batammariba(m: ArchPalette) : WalledPainter(m) {
    override val id = "batammariba"
    override val name = "Batammariba"
    override val origin = "The takienta tower-houses of Koutammakou, Togo and Benin (UNESCO World Heritage): round earthen turrets joined by walls, " +
        "a terrace between them, each turret a granary under a conical thatch cap."
    override val town = TownStyle(WallStyle.MUD_COPED, sacred = Sacred.BAOBAB)
    override val reach = 4
    override fun top(b: Building) = b.wallTop + 22
    private val adobe = m[A.ADOBE]; private val millet = m[A.MILLET_THATCH]

    private fun turret(b: Building, x: Int, y: Int): Float {
        val cx = if (x <= b.cx) b.x0 + 2f else b.x1 - 2f
        val cy = if (y <= b.cy) b.y0 + 2f else b.y1 - 2f
        return sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
    }

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short = adobe

    override fun ornament(b: Building, x: Int, y: Int, z: Int): Short {
        val d = turret(b, x, y)
        val turretTop = b.wallTop + 6
        if (d <= 5f && z >= b.base && z <= turretTop) return if (d > 4f) adobe else if (z >= b.wallTop) AIR else KEEP
        if (z > turretTop) {
            val cx = if (x <= b.cx) b.x0 + 2f else b.x1 - 2f
            val cy = if (y <= b.cy) b.y0 + 2f else b.y1 - 2f
            return cone(cx, cy, turretTop, 6.5f, 1.5f, x, y, z, { if ((it / 2) % 2 == 0) millet else thatchDark }, false, b.seed)
        }
        // Horizontal ridges round the walls.
        if (b.out(x, y) == 1 && z in b.base + 1..b.wallTop && (z - b.base) % 4 == 0) return adobe
        return KEEP
    }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short = flat(b, x, y, z, slab = b.roof ?: adobe, parapetMat = adobe, parapet = 1)
}

/**
 * Kassena: Tiébélé, Burkina Faso. Houses of the royal court painted by the
 * women of the compound in black, white and red earth: chevrons, triangles,
 * checkers and figures, renewed before the rains.
 */
class Kassena(m: ArchPalette) : WalledPainter(m) {
    override val id = "kassena"
    override val name = "Kassena"
    override val origin = "The royal court of Tiébélé, Burkina Faso: earthen houses painted by the women of the compound with geometric designs " +
        "in black, white and red earth, burnished with stones and varnished with néré-pod decoction."
    override val town = TownStyle(WallStyle.MUD_COPED, sacred = Sacred.BAOBAB)
    private val red = m[A.KASSENA_RED]; private val black = m[A.PAINT_BLACK]; private val white = m[A.PAINT_WHITE]

    override fun isRound(b: Building) = b.roundable

    private fun paint(along: Int, h: Int): Short {
        if (h < 3) return red
        val v = h - 3; val row = v / 4; val y = v % 4
        return if (row % 2 == 0) {
            if (Math.floorMod(along, 8) < 2 * (4 - y)) black else white
        } else {
            if (((along / 2) + (y / 2)) % 2 == 0) black else red
        }
    }

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short = if (h >= b.height - 1) red else paint(b.along(x, y), h)

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short = flat(b, x, y, z, slab = b.roof ?: red, parapetMat = red, parapet = 1)

    override fun round(b: Building, x: Int, y: Int, z: Int): Short = roundHut(b, x, y, z, red,
        skinOf = { h, facing, radius -> if (h >= b.height - 1) red else paint((facing * radius).toInt(), h) },
        roofOf = { radius ->
            val d = b.radial(x, y)
            val dz = z - b.wallTop
            if (d <= radius && dz in 1..2) red else if (d in radius - 0.8f..radius && dz == 3) red else KEEP
        },
    )
}

/**
 * Musgum: the teleuk of the Logone floodplain (Cameroon and Chad). Shell
 * domes of sun-dried earth, pointed like a bullet, built without scaffolding:
 * the raised ribs on their skin are the footholds the builders climbed.
 */
class Musgum(m: ArchPalette) : WalledPainter(m) {
    override val id = "musgum"
    override val name = "Musgum"
    override val origin = "The teleuk of the Logone floodplain, Cameroon and Chad: pointed shell domes of earth up to nine metres tall, built " +
        "without scaffolding -- the raised ribs on the outside are the footholds the builders climbed."
    override val town = TownStyle(WallStyle.MUD_COPED, sacred = Sacred.BAOBAB)
    private val earth = m[A.MUSGUM_EARTH]; private val rib = m[A.ADOBE_DARK]
    override fun top(b: Building) = b.base + domeHeight(b) + 2
    private fun domeHeight(b: Building) = max(b.height + 6, (max(b.width, b.depth) * 0.95f).toInt())

    override fun isRound(b: Building) = true

    override fun round(b: Building, x: Int, y: Int, z: Int): Short {
        val height = domeHeight(b)
        // Ribs: a chevron lattice one voxel proud of the skin.
        val v = shell(b, x, y, z, height, 2.6f, profile = { t -> (1f - t.pow(1.7f)).coerceAtLeast(0f).pow(0.55f) }) { h, angle ->
            val a = ((angle + 3.1416f) * 9f).toInt()
            if (Math.floorMod(a + h / 2, 5) == 0 || Math.floorMod(a - h / 2, 5) == 0) rib else earth
        }
        if (v != KEEP) return v
        // The crown: a small smoke hole ring.
        return KEEP
    }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short = KEEP
}

// ================================================================================================
// East and North-East Africa
// ================================================================================================

/**
 * Swahili: the stone towns of the coast -- Kilwa, Lamu, Zanzibar, Gedi,
 * Mombasa (Lamu and Zanzibar are UNESCO World Heritage). Coral rag set in
 * lime and plastered white, flat roofs on mangrove poles behind stepped
 * merlons, carved doors, and stone benches (baraza) flanking every entrance.
 */
class Swahili(m: ArchPalette) : WalledPainter(m) {
    override val id = "swahili"
    override val name = "Swahili"
    override val origin = "The stone towns of the East African coast from the 12th century: Kilwa, Gedi, Lamu, Zanzibar, Mombasa. Coral rag in lime, " +
        "whitewashed; flat roofs on mangrove poles behind stepped merlons; carved doors; baraza benches either side of the entrance."
    override val town = TownStyle(WallStyle.LIME_MERLONED, yard = com.stratum.engine.microvoxel.geo.R.CORAL_SAND, beaten = false, sacred = Sacred.PILLAR_TOMB)
    private val lime = m[A.LIME]; private val coral = m[A.CORAL_BLOCK]; private val door = m[A.CARVED_DOOR]; private val pole = m[A.MANGROVE_POLE]

    override val frame: Short get() = door

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short {
        // Coral quoins at the corners, alternating long and short.
        val a = b.along(x, y)
        val len = if (b.face(x, y) == Side.NORTH || b.face(x, y) == Side.SOUTH) b.width else b.depth
        val q = if ((h / 2) % 2 == 0) 3 else 2
        if (a < q || a >= len - q) return coral
        return lime
    }

    override fun ornament(b: Building, x: Int, y: Int, z: Int): Short {
        // Baraza: low stone benches either side of the door, and the ends of the roof poles.
        val out = b.out(x, y)
        if (out in 1..2 && z in b.base..b.base + 1) {
            val px = x.coerceIn(b.x0, b.x1); val py = y.coerceIn(b.y0, b.y1)
            if (b.face(px, py) == b.door && !b.isDoor(px, py)) {
                val near = if (b.door == Side.NORTH || b.door == Side.SOUTH) abs(b.blockX(px) - b.doorX) == 1 else abs(b.blockY(py) - b.doorY) == 1
                if (near) return lime
            }
        }
        if (out == 1 && z == b.wallTop) {
            val px = x.coerceIn(b.x0, b.x1); val py = y.coerceIn(b.y0, b.y1)
            if (Math.floorMod(b.along(px, py), 3) == 1) return pole
        }
        return KEEP
    }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short =
        flat(b, x, y, z, slab = b.roof ?: lime, parapetMat = lime, parapet = 2) { along -> when (Math.floorMod(along, 6)) { 0, 1 -> 2; 2 -> 1; else -> 0 } }
}

/**
 * Aksumite and Ethiopian highland: stone walls framed with timber whose
 * squared ends stand proud of the wall in rows -- the "monkey heads" of
 * Aksum, carved in stone on its stelae and still built at Debre Damo -- and
 * the tukul, a round house under a steep thatch cone with a pot finial.
 */
class Aksumite(m: ArchPalette) : WalledPainter(m) {
    override val id = "aksumite"
    override val name = "Aksumite and highland Ethiopian"
    override val origin = "The building of the Ethiopian highlands from Aksum (1st-7th century) to Debre Damo: stone walls framed in timber whose " +
        "squared ends, the \"monkey heads\", stand out in rows; recessed and projecting bays; and the round tukul under a steep thatch cone."
    override val town = TownStyle(WallStyle.MUD_COPED, sacred = Sacred.STELE)
    private val stone = m[A.AKSUM_STONE]; private val wood = m[A.AKSUM_TIMBER]; private val ochre = m[A.OCHRE]; private val adobe = m[A.ADOBE]

    override fun isRound(b: Building) = b.roundable

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short = if (Math.floorMod(h, 6) == 4) wood else stone

    override fun ornament(b: Building, x: Int, y: Int, z: Int): Short {
        if (b.out(x, y) != 1 || z > b.wallTop || z < b.base) return KEEP
        val px = x.coerceIn(b.x0, b.x1); val py = y.coerceIn(b.y0, b.y1)
        val h = z - b.base
        // Monkey heads: the squared beam ends, in rows on the timber bands.
        if (Math.floorMod(h, 6) == 4 && Math.floorMod(b.along(px, py), 4) == 1) return wood
        // Projecting corner bays.
        val a = b.along(px, py)
        val len = if (b.face(px, py) == Side.NORTH || b.face(px, py) == Side.SOUTH) b.width else b.depth
        if (a < 3 || a >= len - 3) return if (Math.floorMod(h, 6) == 4) wood else stone
        return KEEP
    }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short = flat(b, x, y, z, slab = b.roof ?: stone, parapetMat = stone, parapet = 1)

    override fun round(b: Building, x: Int, y: Int, z: Int): Short = roundHut(b, x, y, z, stone,
        skinOf = { h, _, _ -> if (h < 3) stone else adobe },
        roofOf = { radius ->
            val tip = b.wallTop + ((radius + 3) * 1.45f).toInt()
            if (b.radial(x, y) < 1.6f && z in tip - 1..tip + 2) ochre // the pot finial
            else cone(b.cx, b.cy, b.wallTop, radius + 3, 1.45f, x, y, z, { courses(b, it) }, true, b.seed)
        },
    )
}

/**
 * Nubian: the villages of the Nile between Aswan and Dongola. Mudbrick
 * roofed with vaults laid without centring (the Nubian vault, used since
 * Pharaonic times), whitewashed and painted round the doors in blue and
 * yellow with triangles, borders and emblems.
 */
class Nubian(m: ArchPalette) : WalledPainter(m) {
    override val id = "nubian"
    override val name = "Nubian"
    override val origin = "The Nile villages between Aswan and Dongola: mudbrick roofed with Nubian vaults laid without centring since Pharaonic " +
        "times, whitewashed and painted round the doors in blue and yellow with triangles and borders."
    override val town = TownStyle(WallStyle.MUD_COPED, yard = com.stratum.engine.microvoxel.geo.R.ERG_SAND, beaten = false, sacred = Sacred.DATE_PALM)
    private val brick = m[A.MUDBRICK]; private val white = m[A.PAINT_WHITE]; private val blue = m[A.NUBIAN_BLUE]; private val yellow = m[A.NUBIAN_YELLOW]

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short {
        if (!b.onDoorFace(x, y)) return white
        if (h < 2) return blue
        // Sawtooth frieze under the vault.
        val fromTop = b.height - 1 - h
        if (fromTop in 1..3) return if (Math.floorMod(b.along(x, y), 4) < 4 - fromTop) yellow else blue
        // A blue border round the door.
        val doorAlong = if (b.door == Side.NORTH || b.door == Side.SOUTH) b.doorX * b.r - b.x0 else b.doorY * b.r - b.y0
        val a = b.along(x, y) - doorAlong
        if ((a in -2..-1 || a in b.r..b.r + 1) && h <= 2 * b.r + 2) return blue
        if (a in -2..b.r + 1 && h in 2 * b.r + 2..2 * b.r + 3) return blue
        return white
    }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short {
        // A barrel vault along the long axis.
        if (!b.inside(x, y)) return KEEP
        val acrossY = b.width >= b.depth
        val a = if (acrossY) y - b.cy else x - b.cx
        val radius = (if (acrossY) b.depth else b.width) / 2f
        if (abs(a) > radius) return KEEP
        val crown = b.wallTop + sqrt((radius * radius - a * a).coerceAtLeast(0f)) * 0.7f
        val dz = z - crown
        if (dz in -2f..0.5f) return if (dz > -0.5f) white else brick
        val endWall = if (acrossY) (x - b.x0 < 2 || b.x1 - x < 2) else (y - b.y0 < 2 || b.y1 - y < 2)
        return if (dz < -2f) (if (endWall) white else AIR) else KEEP
    }
}

// ================================================================================================
// North Africa
// ================================================================================================

/**
 * Amazigh (Berber) ksour and kasbahs: Aït Benhaddou (UNESCO), the Draa and
 * Dades valleys. Rammed-earth (pisé) walls, battered corner towers rising
 * above the roofs, their upper parts worked with lozenges and blind arcades
 * in the drying earth, crenellated tops.
 */
class Amazigh(m: ArchPalette) : WalledPainter(m) {
    override val id = "amazigh"
    override val name = "Amazigh ksar and kasbah"
    override val origin = "The fortified villages (ksour) and kasbahs of the Draa, Dades and Ziz valleys and Aït Benhaddou (UNESCO World Heritage): " +
        "rammed earth, battered corner towers, lozenge and arcade patterns worked into their tops, crenellations."
    override val town = TownStyle(WallStyle.PISE_CRENELLATED, sacred = Sacred.DATE_PALM)
    override fun top(b: Building) = b.wallTop + 16
    private val pise = m[A.PISE]; private val light = m[A.PISE_LIGHT]

    private fun towerHere(b: Building, x: Int, y: Int): Boolean {
        val tx = min(x - b.x0, b.x1 - x); val ty = min(y - b.y0, b.y1 - y)
        return tx < 6 && ty < 6
    }

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short {
        if (h > b.height - 5 && Math.floorMod(b.along(x, y) + h, 4) == 0 && Math.floorMod(b.along(x, y) - h, 4) == 0) return light
        return pise
    }

    override fun ornament(b: Building, x: Int, y: Int, z: Int): Short {
        val out = b.out(x, y)
        val towerTop = b.wallTop + 8
        // Battered towers: a voxel proud of the wall at the foot, sheer above.
        val px = x.coerceIn(b.x0, b.x1); val py = y.coerceIn(b.y0, b.y1)
        if (towerHere(b, px, py)) {
            if (out == 1 && z < b.base + b.height / 2) return pise
            if (out == 0 && z in b.wallTop + 1..towerTop) {
                val e = b.edge(x, y)
                if (e > 5) return KEEP
                if (z > towerTop - 5 && e == 0) {
                    // Lozenge lattice in the tower's crown.
                    val a = b.along(x, y); val hh = z - b.base
                    if (Math.floorMod(a + hh, 4) == 0 || Math.floorMod(a - hh, 4) == 0) return light
                }
                return pise
            }
            if (out == 0 && z in towerTop + 1..towerTop + 3 && b.edge(x, y) == 0) {
                // Stepped merlons.
                val a = b.along(x, y)
                val step = z - towerTop
                return if (Math.floorMod(a, 4) < 4 - step) pise else KEEP
            }
        }
        return KEEP
    }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short =
        flat(b, x, y, z, slab = b.roof ?: pise, parapetMat = pise, parapet = 1) { along -> if (Math.floorMod(along, 4) < 2) 1 else 0 }
}

// ================================================================================================
// Southern Africa
// ================================================================================================

/**
 * Great Zimbabwe (11th-15th century, UNESCO) and its successors Khami and
 * Danangombe: granite blocks dressed and laid without mortar in even
 * courses, the walls battered, with chevron and dentelle friezes; houses of
 * daga (earth) with conical thatch inside the enclosures.
 */
class GreatZimbabwe(m: ArchPalette) : WalledPainter(m) {
    override val id = "great_zimbabwe"
    override val name = "Great Zimbabwe (dry-stone)"
    override val origin = "The Shona capitals of the Zimbabwe plateau, 11th-15th century (UNESCO World Heritage): granite dressed and coursed " +
        "without mortar, battered walls with chevron friezes, a conical tower in the Great Enclosure, daga houses under thatch within."
    override val town = TownStyle(WallStyle.DRYSTONE, yard = com.stratum.engine.microvoxel.geo.R.GRANITE_WEATHERED, sacred = Sacred.CONICAL_TOWER)
    private val stone = m[A.DRYSTONE]; private val dark = m[A.DRYSTONE_DARK]; private val daga = m[A.ADOBE]

    override fun isRound(b: Building) = b.roundable

    private fun coursed(b: Building, along: Int, h: Int): Short {
        val fromTop = b.height - 1 - h
        if (fromTop in 1..3) return if (Math.floorMod(along + fromTop, 6) == 0 || Math.floorMod(along - fromTop, 6) == 0) dark else stone
        // Courses two voxels deep, with the odd darker block.
        return if (Hash.unit(b.seed, along / 2, h / 2, 0, 71) < 0.18f) dark else stone
    }

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short = coursed(b, b.along(x, y), h)

    override fun outside(b: Building, x: Int, y: Int, z: Int): Short =
        if (b.out(x, y) == 1 && z < b.base + b.height / 2) stone else KEEP // the batter

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short = hip(b, x, y, z, eaves = 3, pitch = 1f) { courses(b, it) }

    override fun round(b: Building, x: Int, y: Int, z: Int): Short = roundHut(b, x, y, z, stone,
        skinOf = { h, _, _ -> if (h < 2) stone else daga },
        roofOf = { radius -> cone(b.cx, b.cy, b.wallTop, radius + 3, 1.15f, x, y, z, { courses(b, it) }, true, b.seed) },
    )
}

/**
 * Ndebele: the painted homesteads of Mpumalanga, South Africa. Walls
 * whitewashed and painted by the women with bold panels outlined in black
 * and filled with blue, red, yellow and green -- a tradition that became a
 * statement of identity in the 20th century (Esther Mahlangu).
 */
class Ndebele(m: ArchPalette) : WalledPainter(m) {
    override val id = "ndebele"
    override val name = "Ndebele"
    override val origin = "The homesteads of the Ndebele of Mpumalanga, South Africa: walls painted by the women with bold geometric panels outlined " +
        "in black and filled with bright colour -- the art of Esther Mahlangu and her mother's generation."
    override val town = TownStyle(WallStyle.MUD_COPED, sacred = Sacred.KRAAL)
    private val white = m[A.PAINT_WHITE]; private val black = m[A.PAINT_BLACK]
    private val fills = shortArrayOf(m[A.NDEBELE_BLUE], m[A.NDEBELE_RED], m[A.NDEBELE_YELLOW], m[A.NDEBELE_GREEN])

    override fun isRound(b: Building) = b.roundable

    private fun paint(b: Building, along: Int, h: Int): Short {
        if (h < 2) return black
        if (h >= b.height - 1) return white
        val panel = Math.floorDiv(along, 7)
        val a = Math.floorMod(along, 7)
        val top = b.height - 2
        if (a == 0 || h == 2 || h == top) return black
        // A stepped motif in the middle of the panel.
        val mid = (2 + top) / 2
        if (abs(a - 3) + abs(h - mid) <= 1) return white
        return fills[Math.floorMod(panel + (b.seed and 3L).toInt(), 4)]
    }

    override fun skin(b: Building, x: Int, y: Int, z: Int, h: Int): Short = paint(b, b.along(x, y), h)

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short = hip(b, x, y, z, eaves = 2, pitch = 0.9f) { courses(b, it) }

    override fun round(b: Building, x: Int, y: Int, z: Int): Short = roundHut(b, x, y, z, black,
        skinOf = { h, facing, radius -> paint(b, (facing * radius).toInt() + 40, h) },
        roofOf = { radius -> cone(b.cx, b.cy, b.wallTop, radius + 2, 1f, x, y, z, { courses(b, it) }, true, b.seed) },
    )
}

/**
 * Zulu: the iqukwane, a beehive house of grass thatch woven over a frame of
 * saplings, in a homestead (umuzi) ringed round the cattle kraal (isibaya).
 */
class Zulu(m: ArchPalette) : WalledPainter(m) {
    override val id = "zulu"
    override val name = "Zulu"
    override val origin = "The umuzi homesteads of KwaZulu-Natal: beehive houses (iqukwane) of grass woven over a sapling frame, bound with rope, " +
        "set in a ring round the cattle kraal (isibaya)."
    override val town = TownStyle(WallStyle.THORN, sacred = Sacred.KRAAL)
    private val grass = m[A.GRASS_WEAVE]
    override fun top(b: Building) = b.base + dome(b) + 2
    private fun dome(b: Building) = max(3 * b.r, (min(b.width, b.depth) * 0.62f).toInt())

    override fun isRound(b: Building) = true

    override fun round(b: Building, x: Int, y: Int, z: Int): Short =
        shell(b, x, y, z, dome(b), 2.2f, profile = { t -> sqrt((1f - t * t).coerceAtLeast(0f)) }) { h, _ ->
            if (h % 4 == 3) thatchDark else grass // rope bands
        }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short = KEEP
}

/**
 * Pastoral dung houses: the Maasai inkajijik of the Rift, loaf-shaped and
 * plastered with dung and mud over a frame of poles, and the Himba
 * ozondjuwo of the Kaokoveld; homesteads ringed with thorn.
 */
class Pastoral(m: ArchPalette) : WalledPainter(m) {
    override val id = "pastoral"
    override val name = "Maasai and Himba pastoral"
    override val origin = "The homesteads of East and southern African herders: the Maasai inkajijik of the Rift and the Himba ozondjuwo of the " +
        "Kaokoveld, low loaf-shaped houses of poles plastered with dung and mud, inside a thorn fence around the cattle."
    override val town = TownStyle(WallStyle.THORN, sacred = Sacred.KRAAL)
    private val dung = m[A.DUNG_PLASTER]; private val dark = m[A.ADOBE_DARK]
    override fun top(b: Building) = b.base + loaf(b) + 1
    private fun loaf(b: Building) = max(3 * b.r, b.height)

    override fun isRound(b: Building) = true

    override fun round(b: Building, x: Int, y: Int, z: Int): Short =
        shell(b, x, y, z, loaf(b), 3.5f, profile = { t -> (1f - t.pow(3f)).coerceAtLeast(0f).pow(0.4f) }) { h, _ ->
            if (Hash.unit(b.seed, x, y, z, 81) < 0.12f) dark else dung
        }

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short = KEEP
}

/** No tradition: the plan's block buildings, drawn cleanly -- walls, a lattice window, a simple gable of the plan's roof. */
class PlainBuildings(m: ArchPalette) : WalledPainter(m) {
    override val id = "plain"
    override val name = "Plain"
    override val origin = "The pack's own buildings as its blocks describe them, cleanly bevelled."
    override val town = TownStyle(WallStyle.PLAIN, beaten = false)
    override val reach = 1

    override fun roof(b: Building, x: Int, y: Int, z: Int): Short {
        val roof = b.roof ?: return KEEP
        return gable(b, x, y, z, eaves = 1, pitch = 1f, ragged = false, gableMat = b.wall, cap = false) { roof }
    }
}
