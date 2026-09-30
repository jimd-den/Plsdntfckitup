package com.stratum.engine.microvoxel.geo

import com.stratum.engine.microvoxel.MaterialPalette

/**
 * A layer of rock or regolith, [thickness] microvoxels thick (four to a block).
 */
data class Band(val material: String, val thickness: Int) {
    init { require(thickness >= 1) { "band $material needs a thickness" } }
}

/**
 * How a province's rock is laid down.
 *
 * - [profile] is the weathering profile, from the surface down: what a road
 *   cut shows first (a laterite's crust, gravel, mottled clay, saprolite).
 *   It follows the ground.
 * - [bedding] is the rock itself, in bands that repeat with height, fixed to
 *   the world rather than the surface -- so a cliff, a gorge or an
 *   escarpment shows the same stripes on both sides, as the Blue Nile gorge
 *   and the Drakensberg do.
 * - [fold] bends the bedding: flat, tilted, or folded into anticlines along
 *   the province's grain (the Atlas).
 */
data class Strata(
    val profile: List<Band>,
    val bedding: List<Band>,
    val fold: Fold = Fold.FLAT,
    /**
     * A harder rock over everything more than [capAbove] microvoxels above
     * the province's floor: the dolerite sill on every Karoo mesa, the basalt
     * over the Drakensberg's sandstone. Null for none.
     */
    val cap: String? = null,
    val capAbove: Float = 0f,
) {
    init { require(bedding.isNotEmpty()) { "strata need rock" } }
}

/** How bedding bends: an offset in microvoxels added to height before the bands are read. */
data class Fold(
    /** Height of the folds, microvoxels; 0 is flat-lying. */
    val amplitude: Float = 0f,
    /** Distance between fold crests, microvoxels. */
    val wavelength: Float = 1f,
    /** A steady tilt, microvoxels of rise per microvoxel across the grain. */
    val tilt: Float = 0f,
) {
    companion object { val FLAT = Fold() }
}

/**
 * What lies on top, by situation. [ground] is the everyday surface; the
 * others are what replaces it where it cannot stay.
 */
data class Surface(
    /** Level, dry, stable ground. */
    val ground: String,
    /**
     * Whether [ground] is living soil a host may repaint with its own (a
     * pack's red earth or grove turf). Sand seas, salt, lava and bare rock
     * are the rock's own, whatever the pack says.
     */
    val soil: Boolean = false,
    /** Slopes too steep for soil but not cliffs: scree, gravel, crust rubble. */
    val slope: String = R.TALUS,
    /** Where a slope is steeper than this, bare rock shows its bedding. */
    val cliff: Float = 1.25f,
    /** Beach and shore. */
    val shore: String = com.stratum.engine.microvoxel.M.SAND,
    /** Where the landform's relief is under [floorBelow]: a pan's salt, a delta's mud. */
    val floor: String? = null,
    val floorBelow: Float = 0f,
    /**
     * Standing water in the landform's hollows, up to [lakeBelow] relief: a
     * rift's soda lakes, a pan's brine, a crater lake.
     */
    val lake: String? = null,
    val lakeBelow: Float = 0f,
    /**
     * Where the landform stands more than this above the province's floor,
     * the ground is bare rock: the crown of a granite dome, tsingy blades.
     * Infinite for none.
     */
    val bareAbove: Float = Float.MAX_VALUE,
)

/**
 * Where on the land a province belongs. Each value is an ideal and how far
 * from it the province still fits; see [Province.misfit].
 */
data class Niche(
    /** Heat, 0 (cool highland) to 1 (Saharan). */
    val heat: Float, val heatSpan: Float,
    /** Wet, 0 (hyper-arid) to 1 (rainforest). */
    val wet: Float, val wetSpan: Float,
    /** Tectonic activity, 0 (old craton) to 1 (active rift, hotspot). */
    val tectonic: Float = 0.3f, val tectonicSpan: Float = 0.6f,
    /** Relative elevation, 0 (coast, delta) to 1 (high plateau). */
    val elevation: Float = 0.5f, val elevationSpan: Float = 0.6f,
)

/**
 * One of Africa's geological provinces: a kind of country, with its own
 * rocks, its own shape of land and its own surface.
 *
 * [places] names real places that look like this, which is both the
 * reference the landform was built against and a promise to players who
 * know them.
 */
data class Province(
    val id: String,
    val name: String,
    val places: String,
    val summary: String,
    val niche: Niche,
    /** Microvoxels above the regional level the province's floor sits at. */
    val base: Float,
    val landform: Landform,
    val strata: Strata,
    val surface: Surface,
    /** How much grows here, 0 (salt, dune, lava) to 1 (rainforest). Scales every vegetation stage. */
    val fertility: Float,
    /** Trees that belong here, in order of how common they are; see the vegetation stages. */
    val trees: List<String> = emptyList(),
) {
    /** How badly a spot fits this province, 0 (ideal) upward. */
    fun misfit(heat: Float, wet: Float, tectonic: Float, elevation: Float): Float {
        fun d(v: Float, ideal: Float, span: Float): Float { val t = (v - ideal) / span; return t * t }
        return d(heat, niche.heat, niche.heatSpan) + d(wet, niche.wet, niche.wetSpan) +
            d(tectonic, niche.tectonic, niche.tectonicSpan) + d(elevation, niche.elevation, niche.elevationSpan)
    }
}

/** A province with its names turned into palette ids, once per world. */
class ResolvedProvince(val province: Province, private val palette: MaterialPalette) {
    private fun id(name: String) = palette.id(name)

    val ground = id(province.surface.ground)
    val slope = id(province.surface.slope)
    val shore = id(province.surface.shore)
    val floor = province.surface.floor?.let(::id)
    val lake = province.surface.lake?.let(::id)

    /** Material by depth below the surface, for the weathering profile. */
    val profile: ShortArray = province.strata.profile.flatMap { b -> List(b.thickness) { id(b.material) } }.toShortArray()

    /** Material by height, one period of the bedding. */
    val bedding: ShortArray = province.strata.bedding.flatMap { b -> List(b.thickness) { id(b.material) } }.toShortArray()

    /** The commonest bedding material: what deep rock is, filled in bulk. */
    val cap: Short? = province.strata.cap?.let(::id)

    val deep: Short = province.strata.bedding.groupBy { it.material }.maxBy { (_, bands) -> bands.sumOf { it.thickness } }.key.let(::id)

    fun bed(z: Int): Short = bedding[Math.floorMod(z, bedding.size)]
}
