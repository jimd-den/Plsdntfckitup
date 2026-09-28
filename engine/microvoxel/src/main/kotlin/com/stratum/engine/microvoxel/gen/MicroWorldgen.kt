package com.stratum.engine.microvoxel.gen

import com.stratum.engine.microvoxel.MaterialPalette

/**
 * The built-in stages and presets.
 *
 * Adding an algorithm is one line and a pipeline naming it:
 *
 * ```
 * MicroWorldgen.stages.register("mypack:canals") { setup -> MyCanals(setup) }
 * val gen = MicroWorldgen.build(seed, MicroWorldgen.presets.getValue(MicroWorldgen.CITY) + StageSpec("mypack:canals"))
 * ```
 *
 * Replacing one is the same: register a different factory under an existing
 * id, or publish a different service ([CityPlanStage.KEY],
 * [ArchitectureRegistry.KEY], [Fields.SURFACE]) from a stage of your own.
 */
object MicroWorldgen {
    const val WILDS = "micro:wilds"
    const val CITY = "micro:city"
    const val MIXED = "micro:mixed"
    const val CAVERNS = "micro:caverns"

    /**
     * Tuned for the isometric ARPG: the whole landscape fits a 48-block
     * column (192 microvoxels), hills are walkable, and towns are low
     * terraces and villas with doors at street level -- places to fight
     * through, not skylines. A bridge fills in `seaLevel` from the world config.
     */
    const val ARPG = "micro:arpg"

    val stages: StageRegistry = StageRegistry()
        .register(TerrainStage.ID, TerrainStage)
        .register(CavesStage.ID, CavesStage)
        .register(CityPlanStage.ID, CityPlanStage)
        .register(RoadsStage.ID, RoadsStage)
        .register(BuildingsStage.ID, BuildingsStage)
        .register(GroundcoverStage.ID, GroundcoverStage)
        .register(TreesStage.ID, TreesStage)

    private fun s(id: String, vararg o: Pair<String, String>) = StageSpec(id, mapOf(*o))

    val presets: Map<String, List<StageSpec>> = mapOf(
        WILDS to listOf(s(TerrainStage.ID), s(GroundcoverStage.ID), s(TreesStage.ID)),
        MIXED to listOf(
            s(TerrainStage.ID), s(CityPlanStage.ID), s(RoadsStage.ID), s(BuildingsStage.ID),
            s(GroundcoverStage.ID), s(TreesStage.ID),
        ),
        CITY to listOf(
            s(TerrainStage.ID, "height" to "0.6", "mountains" to "0.4"), s(CityPlanStage.ID, "density" to "0.95"),
            s(RoadsStage.ID), s(BuildingsStage.ID), s(GroundcoverStage.ID), s(TreesStage.ID, "density" to "0.8"),
        ),
        CAVERNS to listOf(s(TerrainStage.ID), s(CavesStage.ID), s(GroundcoverStage.ID), s(TreesStage.ID)),
        ARPG to listOf(
            s(TerrainStage.ID, "height" to "0.28", "mountains" to "0.35", "scale" to "0.7", "maxHeight" to "168", "minHeight" to "8"),
            // Two floors at most: from the isometric camera a taller house hides the street the hero is fighting in.
            s(CityPlanStage.ID, "density" to "0.3", "regionSize" to "384", "styles" to "terrace,villa", "maxFloors" to "2"),
            s(RoadsStage.ID), s(BuildingsStage.ID), s(GroundcoverStage.ID), s(TreesStage.ID, "density" to "0.9"),
        ),
    )

    /**
     * What each built-in stage does and the options it reads, as plain text
     * -- the reference an AI world-builder (or a settings screen) needs to
     * write a `terrain.passes` list without reading Kotlin.
     */
    val catalogue: Map<String, String> = linkedMapOf(
        TerrainStage.ID to "The ground: continents, eroded hills, ridged mountains, sea. Options: seaLevel, scale, height, mountains (0..2), snowLine, maxHeight, minHeight (microvoxels; 4 per block).",
        CavesStage.ID to "Tunnels under the surface. Options: threshold (0..1, lower = more cave), minDepth.",
        CityPlanStage.ID to "Towns on an endless grid of regions, flattening the land under them. Options: density (0..1), regionSize, styles (terrace,villa,tower), maxFloors.",
        RoadsStage.ID to "Draws the town's roads, kerbs, markings and lamps. Options: lampSpacing.",
        BuildingsStage.ID to "Raises each town lot's building in its style. No options.",
        GroundcoverStage.ID to "Grass tufts, flowers, shrubs and pebbles. Options: density (0..2).",
        TreesStage.ID to "Broadleaf and conifer trees by climate. Options: cell (spacing), density (0..2).",
    )

    fun build(seed: Long, specs: List<StageSpec>, palette: MaterialPalette = MaterialPalette.standard()): MicroGenerator =
        stages.build(seed, specs, palette)

    fun preset(id: String, seed: Long, palette: MaterialPalette = MaterialPalette.standard()): MicroGenerator =
        build(seed, presets[id] ?: throw IllegalArgumentException("No preset '$id'. Known: ${presets.keys.joinToString()}"), palette)
}
