package com.stratum.engine.microbridge

import com.stratum.core.domain.micro.MicroModel
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.arch.ArchPalette
import com.stratum.engine.microvoxel.arch.Building
import com.stratum.engine.microvoxel.arch.BuildingGenome
import com.stratum.engine.microvoxel.arch.GenomeRules
import com.stratum.engine.microvoxel.arch.KEEP
import com.stratum.engine.microvoxel.arch.ParametricTradition
import com.stratum.engine.microvoxel.arch.Side
import com.stratum.engine.microvoxel.arch.Vernacular

/**
 * Microvoxel models from the world's own generators, for the model studio:
 * a building rolled from the same genome a town's are, ready to edit,
 * place, or keep.
 */
object ModelFactory {

    private val palette by lazy { MaterialPalette.standard() }

    /**
     * A building [widthBlocks] x [depthBlocks] with walls [wallBlocks] high,
     * rolled from [seed] within [rules] -- or within tradition [tradition]'s
     * vernacular when one is named. Returns the model and the genome it came from.
     */
    fun building(
        seed: Long,
        rules: GenomeRules = GenomeRules(),
        tradition: String? = null,
        widthBlocks: Int = 6,
        depthBlocks: Int = 5,
        wallBlocks: Int = 3,
    ): Pair<MicroModel, BuildingGenome> {
        val r = 4
        val reach = ParametricTradition.REACH
        val w = widthBlocks.coerceIn(3, 20); val d = depthBlocks.coerceIn(3, 20)
        val base = 1
        val arch = ArchPalette(palette)
        val wall = palette.id(com.stratum.engine.microvoxel.M.PLASTER)
        val b = Building(
            x0 = reach, y0 = reach, x1 = reach + w * r - 1, y1 = reach + d * r - 1,
            base = base, wallTop = base + wallBlocks.coerceIn(2, 8) * r - 1,
            doorX = (reach + (w / 2) * r) / r, doorY = (reach + (d - 1) * r) / r, door = Side.SOUTH,
            wall = wall, window = palette.id(com.stratum.engine.microvoxel.M.GLASS), roof = null, r = r,
            seed = seed, windowAt = { bx, _, bz -> bz == 1 && bx % 3 == 0 }, town = seed * 7 + 1,
        )
        val chosen = tradition?.let { Vernacular.rulesFor(it, rules) } ?: rules
        val painter = ParametricTradition(arch, chosen)
        val genome = BuildingGenome.roll(b.seed, b.town, chosen)
        val shape = painter.shapeOf(b, genome)
        val sx = (w * r + 2 * reach).coerceAtMost(MicroModel.MAX_SIDE)
        val sy = (d * r + 2 * reach).coerceAtMost(MicroModel.MAX_SIDE)
        val sz = (shape.top + 2).coerceAtMost(MicroModel.MAX_SIDE)
        val names = ArrayList<String>()
        val index = HashMap<Short, Int>()
        val cells = IntArray(sx * sy * sz)
        for (z in 0 until sz) for (y in 0 until sy) for (x in 0 until sx) {
            val v = if (z < base) (if (x in reach until reach + w * r && y in reach until reach + d * r) wall else MaterialPalette.AIR) else shape.voxel(x, y, z)
            if (v == KEEP || v == MaterialPalette.AIR) continue
            val entry = index.getOrPut(v) { names += palette[v].name; names.size }
            cells[(z * sy + y) * sx + x] = entry
        }
        val model = MicroModel("building-$seed", genome.summary.substringBefore(',').replaceFirstChar { it.uppercase() }, sx, sy, sz, names, cells, source = "generator", tags = listOfNotNull(tradition, genome.family))
        return model to genome
    }
}
