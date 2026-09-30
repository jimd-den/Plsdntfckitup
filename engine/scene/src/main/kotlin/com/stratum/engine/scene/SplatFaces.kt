package com.stratum.engine.scene

/**
 * Which face of its voxel a pixel of a [SplatMode.FAST] splat shows, found
 * without casting a ray.
 *
 * A voxel is a few pixels across, so across it the projection is as good
 * as linear: each world axis moves a point a fixed number of pixels. Seen
 * from above, the cube's outline is a hexagon hung from its near top
 * corner: two edges run from that corner along the top (one belongs to the
 * top and the voxel's y-facing side, one to the top and its x-facing side),
 * and one runs straight down between the two sides. So a pixel's face is
 * which side of those three lines it lies on: three dot products, done
 * once per pixel, with the lines set up once per voxel.
 *
 * The sprite is square and the outline is not, so first a pixel outside
 * the hexagon is dropped: the hexagon is where three slabs cross, one
 * across each world axis's screen direction, three more dot products.
 * Without that, a sprite's corners painted the tops of the voxels behind
 * it -- it is nearer, so its flat depth won -- and every surface smeared
 * towards the horizon.
 *
 * Twin of the line set-up in `SceneShaders.SPLAT_VERTEX` and the test in
 * `SPLAT_FRAGMENT`; the preview rasteriser calls this.
 */
object SplatFaces {
    const val TOP = 0
    const val X_SIDE = 1
    const val Y_SIDE = 2

    /** How far past its exact outline a sprite is kept, in pixels: enough to close the seams, too little to see. */
    const val EDGE_PIXELS = 0.6f

    /** No face: the pixel is outside the cube's outline. */
    const val OUTSIDE = -1

    /** Floats [lines] fills: three lines, each (normal x, normal y, offset), then three slabs, each (normal x, normal y, half width). */
    const val FLOATS = 18

    /**
     * Sets up the three lines for a voxel of half-side [half] centred where
     * [clip] is its centre in clip space, seen from the side [sx], [sy]
     * (+1 or -1: which x and y faces turn towards the eye). Pixel offsets are
     * from the centre's projection, y up; [halfWidth] and [halfHeight] are half
     * the viewport in pixels.
     */
    fun lines(viewProj: FloatArray, clip: FloatArray, halfWidth: Float, halfHeight: Float, half: Float, sx: Float, sy: Float, lines: FloatArray) {
        val w = clip[3]
        val iw2 = 1f / (w * w)
        // Pixels a world unit along each axis moves the centre: the projection's derivative there.
        fun jx(i: Int) = (viewProj[i * 4] * w - clip[0] * viewProj[i * 4 + 3]) * iw2 * halfWidth
        fun jy(i: Int) = (viewProj[i * 4 + 1] * w - clip[1] * viewProj[i * 4 + 3]) * iw2 * halfHeight
        val axx = jx(0); val axy = jy(0)
        val ayx = jx(1); val ayy = jy(1)
        val azx = jx(2); val azy = jy(2)
        // The near top corner, and the three edges leaving it.
        val px = (axx * sx + ayx * sy + azx) * half
        val py = (axy * sx + ayy * sy + azy) * half
        val ex = -sx * axx; val ey = -sx * axy // along x: the edge the y-facing side shares with the top
        val fx = -sy * ayx; val fy = -sy * ayy // along y: the edge the x-facing side shares with the top
        val dx = -azx; val dy = -azy // straight down
        line(ex, ey, fx, fy, px, py, lines, 0) // the top lies on the far side of the x edge ...
        line(fx, fy, ex, ey, px, py, lines, 3) // ... and of the y edge
        line(dx, dy, ex, ey, px, py, lines, 6) // the y-facing side lies on the x edge's side of the vertical
        // The outline: across each axis's direction, the cube spans the other two axes' reach.
        slab(axx, axy, ayx, ayy, azx, azy, half, lines, 9)
        slab(ayx, ayy, axx, axy, azx, azy, half, lines, 12)
        slab(azx, azy, axx, axy, ayx, ayy, half, lines, 15)
    }

    private fun slab(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, half: Float, out: FloatArray, at: Int) {
        val nx = -ay; val ny = ax
        out[at] = nx; out[at + 1] = ny
        // Grown by [EDGE_PIXELS], or pixels exactly on the seam between two voxels are in neither.
        out[at + 2] = (kotlin.math.abs(nx * bx + ny * by) + kotlin.math.abs(nx * cx + ny * cy)) * half + kotlin.math.sqrt(nx * nx + ny * ny) * EDGE_PIXELS
    }

    /** The line through ([px], [py]) along ([ax], [ay]), its normal turned to the side ([bx], [by]) lies on. */
    private fun line(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float, out: FloatArray, at: Int) {
        val side = if (ax * by - ay * bx >= 0f) 1f else -1f
        val nx = -ay * side; val ny = ax * side
        out[at] = nx; out[at + 1] = ny; out[at + 2] = nx * px + ny * py
    }

    /** The face a pixel [dx], [dy] from the centre (y up) shows, or [OUTSIDE]. */
    fun face(lines: FloatArray, dx: Float, dy: Float): Int {
        for (k in 9 until FLOATS step 3) if (kotlin.math.abs(lines[k] * dx + lines[k + 1] * dy) > lines[k + 2]) return OUTSIDE
        val a = lines[0] * dx + lines[1] * dy - lines[2]
        val b = lines[3] * dx + lines[4] * dy - lines[5]
        if (a >= 0f && b >= 0f) return TOP
        return if (lines[6] * dx + lines[7] * dy - lines[8] >= 0f) Y_SIDE else X_SIDE
    }
}
