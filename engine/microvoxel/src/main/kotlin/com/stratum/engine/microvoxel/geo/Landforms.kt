package com.stratum.engine.microvoxel.geo

import com.stratum.engine.microvoxel.gen.Hash
import com.stratum.engine.microvoxel.gen.Noise
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shape of a kind of country: relief in microvoxels above its province's
 * floor, at any world column. Pure, cheap, and continuous except where a
 * landform means a cliff.
 *
 * [grain] is the land's direction there, in radians: the axis of a rift, the
 * strike of a fold belt, the wind that lines up the dunes. It turns slowly
 * across the world, so neighbouring columns agree.
 */
fun interface Landform {
    fun relief(x: Float, y: Float, grain: Float, n: Noise): Float
}

/** Shared shaping helpers. */
internal object Shape {
    fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Steps [step] tall whose risers are the last [riser] share of each: flat treads, steep fronts. */
    fun steps(h: Float, step: Float, riser: Float = 0.25f): Float {
        val base = floor(h / step) * step
        return base + smooth(1f - riser, 1f, (h - base) / step) * step
    }

    fun across(x: Float, y: Float, a: Float) = -x * sin(a) + y * cos(a)
    fun along(x: Float, y: Float, a: Float) = x * cos(a) + y * sin(a)

    /**
     * The tallest of the features scattered one per [cell] (with chance
     * [chance]) around (x, y): each is a point with its own random size, and
     * [shape] turns distance-over-radius and that size into height. Features
     * are kept inside their cell so none is ever cut off.
     */
    inline fun features(
        seed: Long, x: Float, y: Float, cell: Float, chance: Float, salt: Int,
        shape: (t: Float, size: Float, variant: Float) -> Float,
    ): Float {
        val cx = floor(x / cell).toInt(); val cy = floor(y / cell).toInt()
        var best = 0f
        for (oy in -1..1) for (ox in -1..1) {
            val i = cx + ox; val j = cy + oy
            if (Hash.unit(seed, i, j, salt, 1) >= chance) continue
            val px = (i + 0.25f + 0.5f * Hash.unit(seed, i, j, salt, 2)) * cell
            val py = (j + 0.25f + 0.5f * Hash.unit(seed, i, j, salt, 3)) * cell
            val size = Hash.unit(seed, i, j, salt, 4)
            val radius = cell * (0.1f + 0.13f * size)
            val dx = x - px; val dy = y - py
            val t = sqrt(dx * dx + dy * dy) / radius
            if (t >= 1f) continue
            best = max(best, shape(t, size, Hash.unit(seed, i, j, salt, 5)))
        }
        return best
    }
}

/**
 * The landforms. Every amplitude is in microvoxels and sized for the block
 * world's 48-block column: sea at 12 blocks, peaks eased under 40.
 */
object Landforms {

    /**
     * Laterite plateaus: flat-topped mesas capped by iron crust, ending in
     * sharp "breakaway" scarps above a gently sloping pediment -- the Fouta
     * Djallon, the Mossi plateau, the bowé of Guinea.
     */
    fun lateritePlateau(seed: Long) = Landform { x, y, _, n ->
        val f = n.fbm(x * 0.0032f + 11f, y * 0.0032f - 4f, 4)
        val mesa = Shape.smooth(-0.04f, 0.05f, f)
        val pediment = 5f + n.fbm(x * 0.01f, y * 0.01f, 3) * 4f
        val top = 30f + n.fbm(x * 0.006f - 3f, y * 0.006f, 2) * 5f
        pediment + mesa * (top - pediment).coerceAtLeast(0f)
    }

    /**
     * Inselbergs on a pediment plain: bornhardts (smooth domes of bare
     * granite, sheeted by exfoliation) and castle kopjes -- Idanre, Olumo,
     * Zuma Rock, the Matobo Hills, Spitzkoppe.
     */
    fun inselbergs(seed: Long) = Landform { x, y, grain, n ->
        val plain = 4f + n.fbm(x * 0.008f, y * 0.008f, 3) * 4f
        // Domes are a little elongated along the joints (the grain), as most bornhardts are.
        val u = Shape.across(x, y, grain); val v = Shape.along(x, y, grain) * 0.72f
        val dome = Shape.features(seed, u, v, 280f, 0.62f, 101) { t, size, variant ->
            val h = 40f + 60f * size
            if (variant < 0.3f) {
                // Castle kopje: jointed into stacked, rounded blocks.
                Shape.steps(h * (1f - t.pow(3f)).coerceAtLeast(0f).pow(0.5f), 6f, 0.4f)
            } else {
                // Bornhardt: near-vertical sides and a broad, smooth, rounded crown.
                h * (1f - t.pow(2.6f)).coerceAtLeast(0f).pow(0.42f)
            }
        }
        max(plain, plain * 0.3f + dome)
    }

    /** Low, wet, gently rolling basin cut by broad river valleys: the Cuvette Centrale of the Congo. */
    fun rainforestBasin(seed: Long) = Landform { x, y, _, n ->
        var h = 8f + n.erodedFbm(x * 0.004f, y * 0.004f, 5, 0.8f) * 18f
        val river = abs(n.fbm(x * 0.0014f + 70f, y * 0.0014f - 30f, 3))
        h -= 12f * (1f - Shape.smooth(0f, 0.07f, river))
        h
    }

    /**
     * Guinean forest hills, cut by gully erosion: the Agulu-Nanka gullies of
     * south-eastern Nigeria, deep red slots torn into friable sand.
     */
    fun forestHills(seed: Long) = Landform { x, y, _, n ->
        var h = 12f + n.erodedFbm(x * 0.005f, y * 0.005f, 5, 0.9f) * 30f + n.fbm(x * 0.02f, y * 0.02f, 2) * 3f
        val gullied = Shape.smooth(0.1f, 0.25f, n.fbm(x * 0.0011f - 40f, y * 0.0011f + 90f, 2))
        if (gullied > 0f) {
            val g = abs(n.fbm(x * 0.006f + n.fbm(y * 0.01f, x * 0.01f, 2) * 0.8f, y * 0.006f, 3))
            h -= 18f * gullied * (1f - Shape.smooth(0f, 0.035f, g))
        }
        h
    }

    /** Sahelian floodplain: nearly flat, with seasonal pools on black cotton soil -- the Inner Niger Delta's margins. */
    fun sahelPlain(seed: Long) = Landform { x, y, _, n ->
        val h = 5f + n.fbm(x * 0.004f, y * 0.004f, 3) * 4f
        val pool = Shape.smooth(0.28f, 0.36f, n.fbm(x * 0.007f + 13f, y * 0.007f, 2))
        h - pool * 5f
    }

    /**
     * A continental rift: a graben floor with soda lakes, stepped fault
     * scarps up to high shoulders, and volcanoes on the floor -- cones with
     * summit craters, and broad calderas. The Kenyan rift (Longonot, Suswa,
     * Menengai), Lake Natron, the Afar.
     */
    fun rift(seed: Long) = Landform { x, y, grain, n ->
        val v = Shape.along(x, y, grain)
        val u = Shape.across(x, y, grain) + n.fbm(v * 0.0009f, 3f, 2) * 260f
        val q = abs(Math.floorMod(u.toInt(), 2600) - 1300).toFloat()
        val halfFloor = 190f
        var h = if (q < halfFloor) {
            1f + n.fbm(x * 0.005f, y * 0.005f, 3) * 5f
        } else {
            // Tilted fault blocks, each a step up and back-tilted towards the rift.
            val s = (q - halfFloor) / 46f
            val k = min(floor(s), 5f)
            10f + k * 15f + (if (s < 6f) (s - floor(s)) * 5f else 5f) + n.fbm(x * 0.01f, y * 0.01f, 2) * 3f
        }
        val cone = Shape.features(seed, x, y, 520f, 0.45f, 211) { t, size, variant ->
            val height = 45f + 55f * size
            if (variant < 0.3f) {
                // Caldera: the summit collapsed into a broad flat-floored pit.
                if (t < 0.45f) height * 0.55f else height * (1f - t).pow(1.1f) * 1.8f
            } else {
                val body = height * (1f - t).pow(1.3f)
                if (t < 0.12f) body - (0.12f - t) / 0.12f * height * 0.3f else body
            }
        }
        h = max(h, cone)
        h
    }

    /**
     * Flood-basalt traps: flow upon flow in steps, broken into ambas (sheer
     * flat-topped tablelands) by gorges a kilometre deep -- the Simien, the
     * Blue Nile gorge, Amba Aradam.
     */
    fun traps(seed: Long) = Landform { x, y, _, n ->
        val bulk = 42f + n.fbm(x * 0.0025f, y * 0.0025f, 4) * 36f
        val (wx, wy) = n.warp(x, y, 120f, 0.002f)
        val g = abs(n.fbm(wx * 0.0017f - 5f, wy * 0.0017f + 9f, 3))
        val gorge = 64f * (1f - Shape.smooth(0.0f, 0.09f, g))
        Shape.steps((bulk - gorge).coerceAtLeast(2f), 9f, 0.3f)
    }

    /**
     * A sandstone escarpment: a plateau ending in one sheer cliff, with
     * detached pillars and buttes below it -- Bandiagara, the Ennedi, the
     * Tassili n'Ajjer.
     */
    fun sandstoneEscarpment(seed: Long) = Landform { x, y, _, n ->
        val f = n.fbm(x * 0.0016f + 21f, y * 0.0016f - 8f, 4)
        val cliff = Shape.smooth(-0.012f, 0.012f, f)
        val low = 4f + n.fbm(x * 0.012f, y * 0.012f, 2) * 3f
        val top = 60f + n.fbm(x * 0.005f, y * 0.005f, 2) * 6f
        var h = low + cliff * (top - low)
        // Pillars and buttes stranded in front of the cliff as it retreats.
        val apron = Shape.smooth(-0.3f, -0.08f, f) * (1f - Shape.smooth(-0.04f, -0.01f, f))
        if (apron > 0f) {
            val pillar = Shape.features(seed, x, y, 70f, 0.45f * apron + 0.05f, 307) { t, size, _ ->
                if (t < 0.8f) (28f + 34f * size) * (1f - t.pow(8f)) else 0f
            }
            h = max(h, low + pillar)
        }
        h
    }

    /**
     * A sand sea: linear (seif) dunes lined up with the wind, each with a
     * gentle windward slope and a steep slip face, and star dunes where the
     * winds meet -- the Grand Erg Oriental, Erg Chebbi.
     */
    fun erg(seed: Long, amplitude: Float = 36f, wavelength: Float = 130f) = Landform { x, y, grain, n ->
        val v = Shape.along(x, y, grain)
        val u = Shape.across(x, y, grain)
        val t = u / wavelength + 0.4f * n.fbm(v * 0.004f, u * 0.001f, 2)
        val p = t - floor(t)
        val profile = if (p < 0.72f) (p / 0.72f).pow(1.3f) else ((1f - p) / 0.28f).pow(0.8f)
        val seif = amplitude * (0.55f + 0.45f * n.fbm(v * 0.003f + 4f, u * 0.002f, 2)) * profile
        val starMask = Shape.smooth(0.15f, 0.35f, n.fbm(x * 0.0012f + 50f, y * 0.0012f, 2))
        val star = starMask * n.ridged(x * 0.0045f, y * 0.0045f, 3) * amplitude * 1.3f
        2f + max(seif, star)
    }

    /**
     * Stony desert: reg gravel flats, hamada tablelands capped with dark
     * varnished rock, and yardangs -- wind-carved ridges streamlined along
     * the wind (the Borkou, the Tanezrouft).
     */
    fun regHamada(seed: Long) = Landform { x, y, grain, n ->
        var h = 3f + n.fbm(x * 0.01f, y * 0.01f, 2) * 2f
        h += 18f * Shape.smooth(0.16f, 0.2f, n.fbm(x * 0.003f - 7f, y * 0.003f + 2f, 3))
        val yardangs = Shape.smooth(0.05f, 0.2f, n.fbm(x * 0.0018f + 60f, y * 0.0018f, 2))
        if (yardangs > 0f) {
            val u = Shape.across(x, y, grain); val v = Shape.along(x, y, grain)
            val ridge = max(0f, cos(u / 28f * 6.2832f)).pow(3f)
            val stream = Shape.smooth(-0.4f, 0.6f, sin(v / 110f + n.fbm(u * 0.01f, 1f, 1) * 2f))
            h += yardangs * ridge * stream * 11f
        }
        h
    }

    /**
     * A salt pan: a dead-flat white floor with polygon ridges where the crust
     * buckled, a calcrete rim, and granite islands -- Makgadikgadi and its
     * Kubu Island, Etosha, the Chott el Djerid.
     */
    fun saltPan(seed: Long) = Landform { x, y, _, n ->
        val pan = Shape.smooth(-0.05f, 0.1f, n.fbm(x * 0.0011f + 3f, y * 0.0011f - 12f, 3) + 0.12f)
        var h = (1f - pan) * (10f + n.fbm(x * 0.006f, y * 0.006f, 2) * 4f)
        if (pan > 0.98f) {
            // Polygons: the crust's cracks push up into low ridges.
            val c = n.cellular(x / 11f, y / 11f)
            if (c > 0.52f) h += 1f
        }
        val island = Shape.features(seed, x, y, 700f, 0.25f, 401) { t, size, _ -> (18f + 18f * size) * (1f - t.pow(2f)).pow(0.6f) }
        max(h, island)
    }

    /** The Kalahari: red sand plains with long, low, grassed fossil dunes and small calcrete pans. */
    fun kalahari(seed: Long, dunes: Float = 1f) = Landform { x, y, grain, n ->
        val u = Shape.across(x, y, grain)
        val ridges = (0.5f + 0.5f * sin(u / 95f * 6.2832f + n.fbm(x * 0.002f, y * 0.002f, 2) * 2f)).pow(2f) * 9f * dunes
        var h = 4f + n.fbm(x * 0.004f, y * 0.004f, 3) * 4f + ridges
        h -= 6f * Shape.smooth(0.32f, 0.4f, n.fbm(x * 0.005f + 17f, y * 0.005f, 2))
        h
    }

    /** The Namib: the world's tallest dunes, red with iron, beside gravel plains -- Sossusvlei. */
    fun namib(seed: Long, dunesScale: Float = 1f): Landform {
        val dunes = erg(seed, amplitude = 64f * dunesScale, wavelength = 220f)
        return Landform { x, y, grain, n ->
            val sea = Shape.smooth(-0.1f, 0.15f, n.fbm(x * 0.0009f + 80f, y * 0.0009f, 2))
            3f + n.fbm(x * 0.01f, y * 0.01f, 2) * 2f + sea * (dunes.relief(x, y, grain, n) - 2f)
        }
    }

    /**
     * The Karoo: flat-lying shale and sandstone, its mesas and conical
     * koppies all capped by the same hard dolerite sill -- the Valley of
     * Desolation, the Great Karoo.
     */
    fun karoo(seed: Long) = Landform { x, y, _, n ->
        var h = 3f + n.fbm(x * 0.006f, y * 0.006f, 3) * 3f
        val f = n.fbm(x * 0.0035f + 44f, y * 0.0035f, 3)
        val mesa = Shape.smooth(0.2f, 0.23f, f)
        h = max(h, Shape.steps(3f + 42f * mesa, 7f, 0.35f))
        val koppie = Shape.features(seed, x, y, 210f, 0.5f, 503) { t, size, _ ->
            val top = 26f + 20f * size
            // A cone truncated where it reaches the sill: the Karoo's flat-capped koppie.
            min(top, top * 2.2f * (1f - t))
        }
        max(h, koppie)
    }

    /**
     * The Great Escarpment of the Drakensberg: golden sandstone cliffs below,
     * then a wall of basalt to the summit plateau -- the Amphitheatre,
     * Golden Gate.
     */
    fun drakensberg(seed: Long) = Landform { x, y, _, n ->
        val f = n.fbm(x * 0.0013f - 30f, y * 0.0013f + 5f, 4)
        val low = 6f + n.erodedFbm(x * 0.006f, y * 0.006f, 4, 0.8f) * 10f
        val sandstone = Shape.smooth(-0.015f, 0.015f, f) * 38f
        val basalt = Shape.smooth(-0.015f, 0.015f, f - 0.13f) * 50f
        val peaks = Shape.smooth(0.2f, 0.4f, f) * n.ridged(x * 0.006f, y * 0.006f, 3) * 14f
        low + sandstone + basalt + peaks
    }

    /**
     * A fold belt: parallel limestone and sandstone ridges along the strike,
     * cut across by slot gorges -- the High Atlas, the Todra and Dades gorges.
     * The bedding follows the same folds (see [Fold]).
     */
    fun foldBelt(seed: Long, wavelength: Float) = Landform { x, y, grain, n ->
        val u = Shape.across(x, y, grain); val v = Shape.along(x, y, grain)
        val phase = u / wavelength * 6.2832f + n.fbm(v * 0.0015f, u * 0.0005f, 2) * 1.6f
        val ridge = (0.5f + 0.5f * cos(phase)).pow(1.6f) * (58f + 24f * n.fbm(v * 0.002f, 5f, 2))
        var h = 8f + ridge + n.erodedFbm(x * 0.008f, y * 0.008f, 3, 1f) * 8f
        val slot = abs(n.fbm(v * 0.0035f + 9f, u * 0.0004f, 3))
        h -= (h - 6f).coerceAtLeast(0f) * (1f - Shape.smooth(0f, 0.035f, slot))
        h
    }

    /** Tsingy: a limestone plateau dissolved into a forest of razor pinnacles and crevasses -- Bemaraha, Madagascar. */
    fun tsingy(seed: Long) = Landform { x, y, _, n ->
        val plateau = 22f + n.fbm(x * 0.004f, y * 0.004f, 2) * 5f
        val field = Shape.smooth(-0.05f, 0.12f, n.fbm(x * 0.002f + 9f, y * 0.002f - 3f, 2))
        val blades = n.ridged(x * 0.06f, y * 0.06f, 2) * 26f
        val crevasse = if (abs(n.fbm(x * 0.012f, y * 0.012f, 2)) < 0.03f) 14f else 0f
        plateau + field * (blades - crevasse)
    }

    /** Raised coral terraces, beaches and lagoons: the Swahili coast from Lamu to Kilwa. */
    fun coralCoast(seed: Long) = Landform { x, y, _, n ->
        var h = Shape.steps(4f + n.fbm(x * 0.004f, y * 0.004f, 3) * 12f + 6f, 5f, 0.3f) - 3f
        h -= 7f * Shape.smooth(0.2f, 0.3f, n.fbm(x * 0.006f - 5f, y * 0.006f, 2))
        h
    }

    /** A delta: levees, creeks and mangrove mud barely above the tide -- the Niger delta, the Rufiji. */
    fun delta(seed: Long) = Landform { x, y, _, n ->
        var h = 2f + n.fbm(x * 0.006f, y * 0.006f, 2) * 2f
        val channel = abs(n.fbm(x * 0.0022f, y * 0.0022f, 3))
        val creek = abs(n.fbm(x * 0.009f + 30f, y * 0.009f, 2))
        h -= 6f * (1f - Shape.smooth(0f, 0.035f, channel)) + 3f * (1f - Shape.smooth(0f, 0.02f, creek))
        h
    }

    /**
     * Volcanic necks: the eroded throats of old volcanoes standing as sheer
     * spires over rolling hills, with young cinder cones -- Rhumsiki in the
     * Mandara Mountains, the Cameroon line.
     */
    fun volcanicNecks(seed: Long) = Landform { x, y, _, n ->
        val hills = 10f + n.erodedFbm(x * 0.005f, y * 0.005f, 4, 0.9f) * 18f
        val neck = Shape.features(seed, x, y, 250f, 0.4f, 601) { t, size, _ ->
            if (t > 0.45f) 0f else (58f + 40f * size) * (1f - (t / 0.45f).pow(4f)).pow(0.7f)
        }
        val cone = Shape.features(seed, x, y, 420f, 0.3f, 607) { t, size, _ ->
            val body = (22f + 14f * size) * (1f - t).pow(1.2f)
            if (t < 0.15f) body - (0.15f - t) * 40f else body
        }
        max(hills, max(hills * 0.4f + neck, hills * 0.5f + cone))
    }

    /**
     * Canyon country: a high, flat, stony plateau cut by one deep, winding
     * canyon, its walls stepped where hard beds hold up benches -- an outer
     * canyon, a bench, and an inner gorge with the river on its floor --
     * and shorter side canyons feeding it. The Fish River Canyon (Namibia),
     * the Blyde River Canyon (South Africa), the Tekezé gorge (Ethiopia).
     */
    fun canyon(seed: Long) = Landform { x, y, _, n ->
        val plateau = 46f + n.fbm(x * 0.004f, y * 0.004f, 3) * 5f
        // The main canyon follows a warped contour, so it meanders in tight loops as incised rivers do.
        val (wx, wy) = n.warp(x, y, 150f, 0.0026f)
        val g = abs(n.fbm(wx * 0.0011f + 13f, wy * 0.0011f - 7f, 3))
        val outer = 1f - Shape.smooth(0.028f, 0.075f, g)
        val inner = 1f - Shape.smooth(0.006f, 0.02f, g)
        // Side canyons: shallower, narrower, and only near the main one.
        val side = abs(n.fbm(wx * 0.004f - 3f, wy * 0.004f + 21f, 2))
        val feeder = (1f - Shape.smooth(0.012f, 0.04f, side)) * (1f - Shape.smooth(0.07f, 0.2f, g))
        val cut = max(outer * 24f + inner * 20f, feeder * 18f)
        Shape.steps((plateau - cut).coerceAtLeast(3f), 6f, 0.35f)
    }

    /**
     * A coastal dune cordon: a barrier of tall, forested sand ridges parallel
     * to the shore, a long lagoon trapped behind it, and older, lower ridges
     * inland. The Maputaland and Wild Coast cordons (Mozambique, South
     * Africa) and the lagoons of Lagos (Nigeria), Ébrié (Côte d'Ivoire) and
     * Keta (Ghana).
     */
    fun duneCordon(seed: Long, dunes: Float = 1f) = Landform { x, y, grain, n ->
        val u = Shape.across(x, y, grain) + n.fbm(Shape.along(x, y, grain) * 0.0015f, 3f, 2) * 180f
        val period = 1500f
        val p = u / period - floor(u / period)
        val ridges = (0.5f + 0.5f * cos(u / 38f * 6.2832f + n.fbm(x * 0.006f, y * 0.006f, 2) * 2.5f)).pow(1.6f)
        val cordon = Shape.smooth(0.02f, 0.08f, p) * (1f - Shape.smooth(0.22f, 0.3f, p))
        val lagoon = Shape.smooth(0.3f, 0.36f, p) * (1f - Shape.smooth(0.5f, 0.58f, p))
        val inland = Shape.smooth(0.56f, 0.7f, p)
        2f + cordon * (8f + (14f + 10f * n.fbm(x * 0.003f, y * 0.003f, 2)) * ridges * dunes) -
            lagoon * 9f + inland * (5f + ridges * 4f * dunes + n.fbm(x * 0.008f, y * 0.008f, 2) * 3f)
    }

    /**
     * A montane plateau: high, cool, rolling grassland with granite knolls,
     * forest in the valleys and a stream in every fold -- the Nyika plateau
     * (Malawi), the Jos Plateau (Nigeria), the Bamenda highlands (Cameroon),
     * the Aberdares (Kenya).
     */
    fun montanePlateau(seed: Long) = Landform { x, y, _, n ->
        val rolling = 16f + n.erodedFbm(x * 0.0045f, y * 0.0045f, 5, 1.1f) * 30f + n.fbm(x * 0.018f, y * 0.018f, 2) * 2.5f
        val knoll = Shape.features(seed, x, y, 360f, 0.35f, 1101) { t, size, _ -> (14f + 16f * size) * (1f - t * t).pow(0.6f) }
        max(rolling, rolling * 0.6f + knoll)
    }

    /**
     * A basement shield: the worn-flat roots of the oldest crust, low and
     * rolling, crossed by long straight ridges of quartzite and banded iron
     * where greenstone belts are folded in. The Zimbabwe craton, the
     * Barberton belt (South Africa), the Man shield (Liberia, Guinea, Côte
     * d'Ivoire) with the iron ridges of the Nimba.
     */
    fun shield(seed: Long) = Landform { x, y, grain, n ->
        val plain = 5f + n.erodedFbm(x * 0.005f, y * 0.005f, 4, 0.8f) * 12f
        val v = Shape.along(x, y, grain)
        val u = Shape.across(x, y, grain) + n.fbm(v * 0.001f, 1f, 2) * 220f
        val belt = Shape.smooth(0.0f, 0.25f, n.fbm(x * 0.0009f + 70f, y * 0.0009f - 40f, 2))
        val ridge = max(0f, cos(u / 150f * 6.2832f)).pow(8f) * (18f + 12f * n.fbm(v * 0.003f, 4f, 2))
        plain + belt * ridge
    }
}
