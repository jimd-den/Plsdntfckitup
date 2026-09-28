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
    )

    fun build(seed: Long, specs: List<StageSpec>, palette: MaterialPalette = MaterialPalette.standard()): MicroGenerator =
        stages.build(seed, specs, palette)

    fun preset(id: String, seed: Long, palette: MaterialPalette = MaterialPalette.standard()): MicroGenerator =
        build(seed, presets[id] ?: throw IllegalArgumentException("No preset '$id'. Known: ${presets.keys.joinToString()}"), palette)
}
