package com.stratum.engine.scene

import kotlin.math.cos
import kotlin.math.sin

/**
 * The small pieces a masquerade spirit carries: floating hands and orbiting
 * charms. Built from a few ellipsoids each -- a hundred-odd triangles -- so a
 * crowd of spirits can all have them.
 *
 * Deliberately graphic rather than anatomical. A hand is a smooth almond
 * palm with a thumb and a glowing eye in the palm, the way a Deco poster
 * draws a hand as one clean shape; a charm is a cowrie, the shell sewn onto
 * real masquerade costumes, with a line of light down its mouth.
 */
object SpiritParts {

    /** A hand in the mask's own units (a mask is 1 tall), palm facing +y, fingers up. */
    fun hand(color: Int, glow: Int): SpiritMesh {
        val b = Builder()
        b.ellipsoid(0f, 0f, 0f, 0.085f, 0.04f, 0.12f, 10, 8, color, glow) { x, y, z -> y > 0.025f && x * x / 0.0016f + z * z / 0.0036f < 1f }
        b.ellipsoid(-0.085f, 0.004f, -0.035f, 0.03f, 0.028f, 0.06f, 7, 5, color, glow, tiltZ = 0.7f) { _, _, _ -> false }
        return b.build(glow)
    }

    /** A cowrie shell, about a tenth of a mask tall, its lit mouth facing +y. */
    fun charm(color: Int, glow: Int): SpiritMesh {
        val b = Builder()
        b.ellipsoid(0f, 0f, 0f, 0.035f, 0.028f, 0.055f, 8, 6, color, glow) { x, y, _ -> y > 0.018f && kotlin.math.abs(x) < 0.008f }
        return b.build(glow)
    }

    private class Builder {
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val col = ArrayList<Int>(); val ch = ArrayList<Byte>(); val idx = ArrayList<Int>()

        fun ellipsoid(
            cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, around: Int, down: Int, color: Int, glow: Int,
            tiltZ: Float = 0f, lit: (Float, Float, Float) -> Boolean,
        ) {
            val first = pos.size / 3
            val ct = cos(tiltZ); val st = sin(tiltZ)
            for (j in 0..down) {
                val phi = Math.PI.toFloat() * j / down
                for (i in 0..around) {
                    val th = 2f * Math.PI.toFloat() * i / around
                    val ux = sin(phi) * cos(th); val uy = sin(phi) * sin(th); val uz = cos(phi)
                    var x = ux * rx; val y = uy * ry; var z = uz * rz
                    var nx = ux / rx; val ny = uy / ry; var nz = uz / rz
                    // Tilted about y (the thumb splays out), then placed.
                    val tx = x * ct - z * st; z = x * st + z * ct; x = tx
                    val tnx = nx * ct - nz * st; nz = nx * st + nz * ct; nx = tnx
                    val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                    pos += cx + x; pos += cy + y; pos += cz + z
                    nrm += nx / l; nrm += ny / l; nrm += nz / l
                    col += color
                    ch += if (lit(x, y, z)) GlowChannel.CREST else GlowChannel.NONE
                }
            }
            val row = around + 1
            for (j in 0 until down) for (i in 0 until around) {
                val a = first + j * row + i; val b = a + 1; val c = a + row; val d = c + 1
                idx += a; idx += c; idx += b
                idx += b; idx += c; idx += d
            }
        }

        fun build(glow: Int): SpiritMesh {
            val n = pos.size / 3
            val positions = FloatArray(pos.size) { pos[it] }
            val normals = FloatArray(nrm.size) { nrm[it] }
            // Triangles wound to agree with their normals, as the spirit mesher does.
            val indices = IntArray(idx.size) { idx[it] }
            for (t in indices.indices step 3) {
                val a = indices[t]; val b = indices[t + 1]; val c = indices[t + 2]
                val e1x = positions[b * 3] - positions[a * 3]; val e1y = positions[b * 3 + 1] - positions[a * 3 + 1]; val e1z = positions[b * 3 + 2] - positions[a * 3 + 2]
                val e2x = positions[c * 3] - positions[a * 3]; val e2y = positions[c * 3 + 1] - positions[a * 3 + 1]; val e2z = positions[c * 3 + 2] - positions[a * 3 + 2]
                val fx = e1y * e2z - e1z * e2y; val fy = e1z * e2x - e1x * e2z; val fz = e1x * e2y - e1y * e2x
                val nx = normals[a * 3] + normals[b * 3] + normals[c * 3]
                val ny = normals[a * 3 + 1] + normals[b * 3 + 1] + normals[c * 3 + 1]
                val nz = normals[a * 3 + 2] + normals[b * 3 + 2] + normals[c * 3 + 2]
                if (fx * nx + fy * ny + fz * nz < 0f) { indices[t + 1] = c; indices[t + 2] = b }
            }
            return SpiritMesh(
                positions, normals, IntArray(n) { col[it] }, ByteArray(n) { ch[it] }, IntArray(n) { glow }, indices,
                auraColor = glow, height = 0.25f,
            )
        }
    }
}
