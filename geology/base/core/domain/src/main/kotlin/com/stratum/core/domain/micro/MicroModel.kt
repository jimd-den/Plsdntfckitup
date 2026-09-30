package com.stratum.core.domain.micro

/**
 * A model made of microvoxels, a quarter block each: a player's statue, a
 * lamp post, a door arch, a picture turned into relief -- anything finer
 * than blocks.
 *
 * Cells run x fastest, then y, then z: `(z * sizeY + y) * sizeX + x`. Zero
 * is empty and `n` is `palette[n - 1]`. A palette entry is a colour
 * (`#RRGGBB`, matched to the nearest paint), a microvoxel material by name
 * (`brick`, `arch:lime`, `geo:granite`) or a pack block (`block:<id>`), so
 * a model can be as free as pixel art or as faithful as the pack's own
 * building blocks.
 *
 * Models are data, not code: a world keeps the ones it has placed in its
 * save, so a statue stands even after its maker deletes it from the library.
 */
data class MicroModel(
    val id: String,
    val name: String,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val palette: List<String>,
    val cells: IntArray,
    /** Where it came from: `studio`, `image`, `generator`, `import`. Shown in the library. */
    val source: String = "studio",
    val tags: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "a model needs an id" }
        require(sizeX in 1..MAX_SIDE && sizeY in 1..MAX_SIDE && sizeZ in 1..MAX_SIDE) { "model '$id' is ${sizeX}x${sizeY}x$sizeZ; each side is 1..$MAX_SIDE" }
        require(cells.size == sizeX * sizeY * sizeZ) { "model '$id' needs ${sizeX * sizeY * sizeZ} cells, has ${cells.size}" }
        require(palette.size <= MAX_PALETTE) { "model '$id' has ${palette.size} colours; at most $MAX_PALETTE" }
        require(cells.all { it in 0..palette.size }) { "model '$id' names a colour outside its palette" }
    }

    val volume: Int get() = sizeX * sizeY * sizeZ

    fun index(x: Int, y: Int, z: Int): Int = (z * sizeY + y) * sizeX + x

    fun contains(x: Int, y: Int, z: Int): Boolean = x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ

    /** The palette entry at a cell, or null when it is empty. */
    fun at(x: Int, y: Int, z: Int): String? = cells[index(x, y, z)].takeIf { it > 0 }?.let { palette[it - 1] }

    val filledCount: Int get() = cells.count { it > 0 }

    /** Blocks it spans on each side, rounded up, at [microPerBlock] cells to a block. */
    fun blocks(microPerBlock: Int = 4): Triple<Int, Int, Int> =
        Triple((sizeX + microPerBlock - 1) / microPerBlock, (sizeY + microPerBlock - 1) / microPerBlock, (sizeZ + microPerBlock - 1) / microPerBlock)

    /** The same model with its cells as [cells], palette unused entries dropped. */
    fun withCells(cells: IntArray): MicroModel = copy(cells = cells).compacted()

    /** Drops palette entries no cell uses, renumbering the cells. */
    fun compacted(): MicroModel {
        val used = BooleanArray(palette.size + 1)
        for (c in cells) used[c] = true
        if ((1..palette.size).all { used[it] }) return this
        val remap = IntArray(palette.size + 1)
        val kept = ArrayList<String>()
        for (i in 1..palette.size) if (used[i]) { kept += palette[i - 1]; remap[i] = kept.size }
        return copy(palette = kept, cells = IntArray(cells.size) { remap[cells[it]] })
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is MicroModel && id == other.id && name == other.name && sizeX == other.sizeX && sizeY == other.sizeY &&
                sizeZ == other.sizeZ && palette == other.palette && source == other.source && tags == other.tags &&
                cells.contentEquals(other.cells)
            )

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + palette.hashCode()) + cells.contentHashCode()

    companion object {
        /** 32 blocks on a side: a statue, a tree, a gate, a small house. */
        const val MAX_SIDE = 128
        const val MAX_PALETTE = 255

        fun empty(id: String, name: String, sizeX: Int, sizeY: Int, sizeZ: Int): MicroModel =
            MicroModel(id, name, sizeX, sizeY, sizeZ, emptyList(), IntArray(sizeX * sizeY * sizeZ))

        /** `#RRGGBB` for an RGB colour. */
        fun colour(rgb: Int): String = "#" + (rgb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

        /** The RGB of a `#RRGGBB` entry, or null for a named material. */
        fun rgbOf(entry: String): Int? = if (entry.length == 7 && entry[0] == '#') entry.substring(1).toIntOrNull(16) else null
    }
}

/**
 * A model set into the world: which one, where its low corner lands in
 * microvoxels, and how it is turned. A carving stamp takes away instead: its
 * filled cells become air -- how the chisel and the sculpting brushes dig.
 *
 * A world keeps its stamps in order and lays them over the generated land
 * every time a chunk is made, so they save as a few numbers each and never
 * drift from the ground they stand on.
 */
data class MicroStamp(
    val modelId: String,
    val x: Int,
    val y: Int,
    val z: Int,
    /** Quarter turns about the vertical, counter-clockwise seen from above. */
    val turns: Int = 0,
    val mirror: Boolean = false,
    val carve: Boolean = false,
) {
    /** The model's size once turned: x and y swap on odd turns. */
    fun footprint(model: MicroModel): Pair<Int, Int> =
        if (Math.floorMod(turns, 2) == 1) model.sizeY to model.sizeX else model.sizeX to model.sizeY

    /**
     * The model cell index under world microvoxel (wx, wy, wz), or -1 outside
     * the stamp. Turning and mirroring are applied here, so a stamp never
     * needs a turned copy of its model.
     */
    fun cellAt(model: MicroModel, wx: Int, wy: Int, wz: Int): Int {
        val lz = wz - z
        if (lz !in 0 until model.sizeZ) return -1
        val (fx, fy) = footprint(model)
        var lx = wx - x; val ly = wy - y
        if (lx !in 0 until fx || ly !in 0 until fy) return -1
        if (mirror) lx = fx - 1 - lx
        val (mx, my) = when (Math.floorMod(turns, 4)) {
            0 -> lx to ly
            1 -> ly to (model.sizeY - 1 - lx)
            2 -> (model.sizeX - 1 - lx) to (model.sizeY - 1 - ly)
            else -> (model.sizeX - 1 - ly) to lx
        }
        return model.index(mx, my, lz)
    }
}

/**
 * The shapes the sculpting brushes stamp, made on demand from their id so a
 * save never stores them: `brush:<sphere|cube|dome|cylinder>:<radius>:<material>`.
 */
object MicroBrushes {
    const val PREFIX = "brush:"
    val shapes = listOf("sphere", "cube", "dome", "cylinder")

    fun id(shape: String, radius: Int, material: String = "stone"): String = "$PREFIX$shape:$radius:$material"

    fun isBrush(id: String): Boolean = id.startsWith(PREFIX)

    /** The brush an id names, or null when it is not one. */
    fun model(id: String): MicroModel? {
        if (!isBrush(id)) return null
        val parts = id.removePrefix(PREFIX).split(':', limit = 3)
        val shape = parts.getOrNull(0)?.takeIf { it in shapes } ?: return null
        val radius = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, MAX_RADIUS) ?: return null
        val material = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "stone"
        val size = radius * 2 + 1
        val cells = IntArray(size * size * size)
        val c = radius.toFloat()
        for (z in 0 until size) for (y in 0 until size) for (x in 0 until size) {
            val dx = x - c; val dy = y - c; val dz = z - c
            val inside = when (shape) {
                "sphere" -> dx * dx + dy * dy + dz * dz <= (radius + 0.35f) * (radius + 0.35f)
                "dome" -> z >= radius && dx * dx + dy * dy + dz * dz <= (radius + 0.35f) * (radius + 0.35f)
                "cylinder" -> dx * dx + dy * dy <= (radius + 0.35f) * (radius + 0.35f)
                else -> true
            }
            if (inside) cells[(z * size + y) * size + x] = 1
        }
        return MicroModel(id, "${shape.replaceFirstChar { it.uppercase() }} brush", size, size, size, listOf(material), cells, source = "brush")
    }

    const val MAX_RADIUS = 12
}
