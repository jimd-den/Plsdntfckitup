package com.stratum.engine.model

import com.stratum.core.domain.micro.MicroModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The model studio's tools, as pure functions from a model to a new one:
 * so every stroke is one value, and undo is keeping the last few.
 *
 * Cells are addressed as the model stores them: x right, y back, z up.
 * [colour] everywhere is a palette entry (`#RRGGBB` or a material name);
 * null erases.
 */
object MicroModelOps {

    enum class Axis { X, Y, Z }

    /** Sets the cells [cells] (flat indices) to [colour]. The palette gains the colour if it lacks it. */
    fun paint(model: MicroModel, cells: Collection<Int>, colour: String?): MicroModel {
        if (cells.isEmpty()) return model
        val (palette, entry) = entryFor(model, colour)
        val out = model.cells.copyOf()
        for (i in cells) if (i in out.indices) out[i] = entry
        return model.copy(palette = palette, cells = out).compacted()
    }

    fun set(model: MicroModel, x: Int, y: Int, z: Int, colour: String?, mirror: Set<Axis> = emptySet()): MicroModel =
        paint(model, mirrored(model, listOf(Triple(x, y, z)), mirror), colour)

    /** A solid box between two corners. */
    fun box(model: MicroModel, a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>, colour: String?, hollow: Boolean = false, mirror: Set<Axis> = emptySet()): MicroModel {
        val cells = ArrayList<Triple<Int, Int, Int>>()
        val x0 = min(a.first, b.first); val x1 = max(a.first, b.first)
        val y0 = min(a.second, b.second); val y1 = max(a.second, b.second)
        val z0 = min(a.third, b.third); val z1 = max(a.third, b.third)
        for (z in z0..z1) for (y in y0..y1) for (x in x0..x1) {
            val skin = x == x0 || x == x1 || y == y0 || y == y1 || z == z0 || z == z1
            if (!hollow || skin) cells += Triple(x, y, z)
        }
        return paint(model, mirrored(model, cells, mirror), colour)
    }

    /** A ball of [radius] about a cell. */
    fun sphere(model: MicroModel, cx: Int, cy: Int, cz: Int, radius: Float, colour: String?, mirror: Set<Axis> = emptySet()): MicroModel {
        val r = radius.coerceAtLeast(0.5f)
        val cells = ArrayList<Triple<Int, Int, Int>>()
        val n = r.toInt() + 1
        for (z in cz - n..cz + n) for (y in cy - n..cy + n) for (x in cx - n..cx + n) {
            val dx = x - cx; val dy = y - cy; val dz = z - cz
            if (dx * dx + dy * dy + dz * dz <= r * r) cells += Triple(x, y, z)
        }
        return paint(model, mirrored(model, cells, mirror), colour)
    }

    /** A straight run of cells between two, one cell thick. */
    fun line(model: MicroModel, a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>, colour: String?, mirror: Set<Axis> = emptySet()): MicroModel {
        val steps = maxOf(abs(b.first - a.first), abs(b.second - a.second), abs(b.third - a.third)).coerceAtLeast(1)
        val cells = (0..steps).map { i ->
            val t = i / steps.toFloat()
            Triple((a.first + (b.first - a.first) * t).roundToInt(), (a.second + (b.second - a.second) * t).roundToInt(), (a.third + (b.third - a.third) * t).roundToInt())
        }
        return paint(model, mirrored(model, cells, mirror), colour)
    }

    /**
     * Fills the connected region of cells like the one at (x, y, z) --
     * empty or the same colour -- within the layer z when [layerOnly], else
     * in 3D. How a player fills a floor or recolours a wall in one tap.
     */
    fun fill(model: MicroModel, x: Int, y: Int, z: Int, colour: String?, layerOnly: Boolean = true): MicroModel {
        if (!model.contains(x, y, z)) return model
        val target = model.cells[model.index(x, y, z)]
        val seen = BooleanArray(model.volume)
        val stack = ArrayDeque<Int>()
        val start = model.index(x, y, z)
        stack.addLast(start); seen[start] = true
        val region = ArrayList<Int>()
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            region += i
            val cx = i % model.sizeX; val cy = (i / model.sizeX) % model.sizeY; val cz = i / (model.sizeX * model.sizeY)
            for ((dx, dy, dz) in NEIGHBOURS) {
                if (layerOnly && dz != 0) continue
                val nx = cx + dx; val ny = cy + dy; val nz = cz + dz
                if (!model.contains(nx, ny, nz)) continue
                val j = model.index(nx, ny, nz)
                if (seen[j] || model.cells[j] != target) continue
                seen[j] = true; stack.addLast(j)
            }
        }
        return paint(model, region, colour)
    }

    /** Every cell of one colour turned another. */
    fun recolour(model: MicroModel, from: String, to: String): MicroModel {
        val i = model.palette.indexOf(from)
        if (i < 0 || from == to) return model
        val j = model.palette.indexOf(to)
        if (j < 0) return model.copy(palette = model.palette.toMutableList().also { it[i] = to })
        return model.copy(cells = IntArray(model.cells.size) { if (model.cells[it] == i + 1) j + 1 else model.cells[it] }).compacted()
    }

    /** A quarter turn about the vertical, counter-clockwise from above. */
    fun turn(model: MicroModel): MicroModel {
        val nx = model.sizeY; val ny = model.sizeX
        val out = IntArray(model.volume)
        for (z in 0 until model.sizeZ) for (y in 0 until model.sizeY) for (x in 0 until model.sizeX) {
            // (x, y) -> (sizeY - 1 - y, x)
            out[(z * ny + x) * nx + (model.sizeY - 1 - y)] = model.cells[model.index(x, y, z)]
        }
        return model.copy(sizeX = nx, sizeY = ny, cells = out)
    }

    fun flip(model: MicroModel, axis: Axis): MicroModel {
        val out = IntArray(model.volume)
        for (z in 0 until model.sizeZ) for (y in 0 until model.sizeY) for (x in 0 until model.sizeX) {
            val tx = if (axis == Axis.X) model.sizeX - 1 - x else x
            val ty = if (axis == Axis.Y) model.sizeY - 1 - y else y
            val tz = if (axis == Axis.Z) model.sizeZ - 1 - z else z
            out[model.index(tx, ty, tz)] = model.cells[model.index(x, y, z)]
        }
        return model.copy(cells = out)
    }

    /** Grows or shrinks the box, keeping cells anchored at the bottom centre. */
    fun resize(model: MicroModel, sx: Int, sy: Int, sz: Int): MicroModel {
        val nx = sx.coerceIn(1, MicroModel.MAX_SIDE); val ny = sy.coerceIn(1, MicroModel.MAX_SIDE); val nz = sz.coerceIn(1, MicroModel.MAX_SIDE)
        val ox = (nx - model.sizeX) / 2; val oy = (ny - model.sizeY) / 2
        val out = IntArray(nx * ny * nz)
        for (z in 0 until min(nz, model.sizeZ)) for (y in 0 until model.sizeY) for (x in 0 until model.sizeX) {
            val tx = x + ox; val ty = y + oy
            if (tx !in 0 until nx || ty !in 0 until ny) continue
            out[(z * ny + ty) * nx + tx] = model.cells[model.index(x, y, z)]
        }
        return model.copy(sizeX = nx, sizeY = ny, sizeZ = nz, cells = out).compacted()
    }

    /** Cuts the box down to what is filled. */
    fun trim(model: MicroModel): MicroModel {
        var x0 = model.sizeX; var y0 = model.sizeY; var z0 = model.sizeZ; var x1 = -1; var y1 = -1; var z1 = -1
        for (z in 0 until model.sizeZ) for (y in 0 until model.sizeY) for (x in 0 until model.sizeX) if (model.cells[model.index(x, y, z)] != 0) {
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y); z0 = min(z0, z); z1 = max(z1, z)
        }
        if (x1 < 0) return model
        val nx = x1 - x0 + 1; val ny = y1 - y0 + 1; val nz = z1 - z0 + 1
        val out = IntArray(nx * ny * nz)
        for (z in 0 until nz) for (y in 0 until ny) for (x in 0 until nx) out[(z * ny + y) * nx + x] = model.cells[model.index(x + x0, y + y0, z + z0)]
        return model.copy(sizeX = nx, sizeY = ny, sizeZ = nz, cells = out)
    }

    /** Scales by nearest neighbour, for doubling a sketch into detail or halving a scan. */
    fun scale(model: MicroModel, factor: Float): MicroModel {
        val nx = (model.sizeX * factor).roundToInt().coerceIn(1, MicroModel.MAX_SIDE)
        val ny = (model.sizeY * factor).roundToInt().coerceIn(1, MicroModel.MAX_SIDE)
        val nz = (model.sizeZ * factor).roundToInt().coerceIn(1, MicroModel.MAX_SIDE)
        val out = IntArray(nx * ny * nz)
        for (z in 0 until nz) for (y in 0 until ny) for (x in 0 until nx) {
            val sx = (x * model.sizeX / nx).coerceAtMost(model.sizeX - 1)
            val sy = (y * model.sizeY / ny).coerceAtMost(model.sizeY - 1)
            val sz = (z * model.sizeZ / nz).coerceAtMost(model.sizeZ - 1)
            out[(z * ny + y) * nx + x] = model.cells[model.index(sx, sy, sz)]
        }
        return model.copy(sizeX = nx, sizeY = ny, sizeZ = nz, cells = out)
    }

    /** Shading-free noise over the colours, for stone and earth that is not a flat fill. */
    fun weather(model: MicroModel, seed: Long, amount: Float = 0.12f): MicroModel {
        val palette = model.palette.toMutableList()
        val out = model.cells.copyOf()
        val rnd = kotlin.random.Random(seed)
        for (i in out.indices) {
            val v = out[i]
            if (v == 0 || rnd.nextFloat() > amount) continue
            val rgb = MicroModel.rgbOf(palette[v - 1]) ?: continue
            val k = if (rnd.nextBoolean()) 1.12f else 0.88f
            val shifted = MicroModel.colour(scaleRgb(rgb, k))
            var j = palette.indexOf(shifted)
            if (j < 0) { if (palette.size >= MicroModel.MAX_PALETTE) continue; palette += shifted; j = palette.lastIndex }
            out[i] = j + 1
        }
        return model.copy(palette = palette, cells = out)
    }

    private fun scaleRgb(rgb: Int, k: Float): Int {
        fun c(s: Int) = (((rgb shr s) and 255) * k).roundToInt().coerceIn(0, 255)
        return (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    private fun mirrored(model: MicroModel, cells: List<Triple<Int, Int, Int>>, mirror: Set<Axis>): List<Int> {
        var all = cells
        if (Axis.X in mirror) all = all + all.map { Triple(model.sizeX - 1 - it.first, it.second, it.third) }
        if (Axis.Y in mirror) all = all + all.map { Triple(it.first, model.sizeY - 1 - it.second, it.third) }
        if (Axis.Z in mirror) all = all + all.map { Triple(it.first, it.second, model.sizeZ - 1 - it.third) }
        return all.filter { model.contains(it.first, it.second, it.third) }.map { model.index(it.first, it.second, it.third) }.distinct()
    }

    /** The palette with [colour] in it, and its 1-based entry (0 for erasing). */
    private fun entryFor(model: MicroModel, colour: String?): Pair<List<String>, Int> {
        if (colour == null) return model.palette to 0
        val i = model.palette.indexOf(colour)
        if (i >= 0) return model.palette to i + 1
        val compact = model.compacted()
        require(compact.palette.size < MicroModel.MAX_PALETTE) { "a model holds at most ${MicroModel.MAX_PALETTE} colours" }
        return (model.palette + colour) to model.palette.size + 1
    }

    private val NEIGHBOURS = listOf(Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0), Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1))
}

/**
 * A model drawn to pixels, isometric and lit from the upper left: the
 * studio's preview and the library's thumbnails, with no GPU and no Android.
 */
object MicroModelRenderer {

    /** ARGB pixels, [size] square, transparent where the model is not. [turn] rotates the view a quarter at a time. */
    fun render(model: MicroModel, size: Int, colourOf: (String) -> Int = ::defaultColour, turn: Int = 0, background: Int = 0): IntArray {
        val px = IntArray(size * size) { background }
        val depth = FloatArray(size * size) { Float.NEGATIVE_INFINITY }
        val m = when (Math.floorMod(turn, 4)) { 0 -> model; 1 -> MicroModelOps.turn(model); 2 -> MicroModelOps.turn(MicroModelOps.turn(model)); else -> MicroModelOps.turn(MicroModelOps.turn(MicroModelOps.turn(model))) }
        // Isometric: x runs right and down, y left and down, z up.
        val minX = -m.sizeY * 0.8660254f; val maxX = m.sizeX * 0.8660254f
        val minY = -m.sizeZ.toFloat(); val maxY = (m.sizeX + m.sizeY) * 0.5f
        val s = (size - 4) / max(maxX - minX, maxY - minY)
        val ox = size / 2f - (minX + maxX) / 2f * s
        val oy = size / 2f - (minY + maxY) / 2f * s
        val colours = IntArray(m.palette.size) { colourOf(m.palette[it]) }
        val cube = max(1, (s * 1.05f).toInt() + 1)
        for (z in 0 until m.sizeZ) for (y in 0 until m.sizeY) for (x in 0 until m.sizeX) {
            val v = m.cells[m.index(x, y, z)]
            if (v == 0) continue
            // Only cells that show a face.
            val top = z == m.sizeZ - 1 || m.cells[m.index(x, y, z + 1)] == 0
            val left = y == m.sizeY - 1 || m.cells[m.index(x, y + 1, z)] == 0
            val right = x == m.sizeX - 1 || m.cells[m.index(x + 1, y, z)] == 0
            if (!top && !left && !right) continue
            val sx = ox + (x - y) * 0.8660254f * s
            val sy = oy + ((x + y + 1) * 0.5f - z - 0.5f) * s
            val d = (x + y + z).toFloat()
            val base = colours[v - 1]
            val face = when {
                top -> shade(base, 1.08f)
                right -> shade(base, 0.86f)
                else -> shade(base, 0.7f)
            }
            val x0 = (sx - cube / 2f).toInt(); val y0 = (sy - cube / 2f).toInt()
            for (yy in y0 until y0 + cube) for (xx in x0 until x0 + cube) {
                if (xx !in 0 until size || yy !in 0 until size) continue
                val i = yy * size + xx
                if (d >= depth[i]) { depth[i] = d; px[i] = face }
            }
        }
        return px
    }

    fun defaultColour(entry: String): Int = 0xFF000000.toInt() or (MicroModel.rgbOf(entry) ?: named(entry))

    /** A rough colour for a named material when no palette is at hand. */
    private fun named(entry: String): Int = when {
        "grass" in entry || "leaves" in entry -> 0x5E9B3A
        "water" in entry -> 0x2F6FA0
        "sand" in entry -> 0xD8C58E
        "brick" in entry || "roof_tile" in entry -> 0x9C4A34
        "timber" in entry || "bark" in entry || "wood" in entry -> 0x7B5534
        "thatch" in entry -> 0xB8914E
        "lime" in entry || "plaster" in entry || "white" in entry -> 0xE6D9C0
        "mud" in entry || "adobe" in entry || "earth" in entry -> 0xA0583A
        else -> 0x8A8A86
    }

    private fun shade(argb: Int, k: Float): Int {
        fun c(sft: Int) = (((argb shr sft) and 255) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }
}
