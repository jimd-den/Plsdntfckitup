package com.stratum.engine.scene

/**
 * A 3D model ready to stand in the world as a prop: flat-coloured triangles,
 * in blocks, standing on z = 0 and centred on the origin in plan.
 *
 * Flat colour per triangle rather than a texture, on purpose. A prop is a
 * handful of pixels tall on a phone, a texture would cost a layer in the shared
 * array per model, and flat faces are what reads as intentional low-poly art at
 * this camera rather than a blurred miniature. It also means the prop goes
 * through the opaque path the terrain uses — lit, shadowed and fogged by the
 * one lighting equation both backends share — with no new shader.
 *
 * Built by `:engine:model` from whatever the provider sent; this class only
 * knows how to put the triangles into a frame.
 */
class PropModel(
    /** Three corners per triangle, x, y, z each: nine floats a triangle. */
    val positions: FloatArray,
    /** One unit normal per triangle. */
    val normals: FloatArray,
    /** One ARGB colour per triangle. */
    val colors: IntArray,
) {
    init {
        require(positions.size % 9 == 0) { "positions must be whole triangles" }
        require(normals.size == positions.size / 3) { "one normal per triangle" }
        require(colors.size == positions.size / 9) { "one colour per triangle" }
    }

    val triangleCount: Int get() = colors.size

    /** How tall the model stands, for shadows and picking. */
    val height: Float by lazy {
        var top = 0f
        for (i in 2 until positions.size step 3) if (positions[i] > top) top = positions[i]
        top
    }

    /**
     * Adds the model to [out], standing at ([x], [y], [z]) and turned by
     * [quarterTurns] right angles about the vertical.
     *
     * Quarter turns only, so a grove of the same statue varies without any
     * of them sitting skewed against the block grid.
     */
    fun emit(out: MeshBuilder, x: Float, y: Float, z: Float, quarterTurns: Int = 0, scale: Float = 1f) {
        val turn = Math.floorMod(quarterTurns, 4)
        for (t in 0 until triangleCount) {
            val color = colors[t].toLong() and 0xFFFFFFFFL
            val (nx, ny) = rotate(normals[t * 3], normals[t * 3 + 1], turn)
            val nz = normals[t * 3 + 2]
            val first = out.vertexCount
            for (corner in 0 until 3) {
                val o = t * 9 + corner * 3
                val (px, py) = rotate(positions[o] * scale, positions[o + 1] * scale, turn)
                out.vertex(x + px, y + py, z + positions[o + 2] * scale, nx, ny, nz, color, 1f, 0f, 0f, Vertex.FLAT)
            }
            out.triangle(first, first + 1, first + 2)
        }
    }

    private fun rotate(x: Float, y: Float, turn: Int): Pair<Float, Float> = when (turn) {
        1 -> -y to x
        2 -> -x to -y
        3 -> y to -x
        else -> x to y
    }
}
