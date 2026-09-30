package com.stratum.engine.scene

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A smooth, lit, glowing object that floats rather than stands: a mask
 * spirit, drawn as real 3D triangles with smooth normals, flat colour fields,
 * and parts that light up.
 *
 * ## Why not a [PropModel]
 *
 * A prop is baked once into the terrain's batch at a quarter turn, faceted,
 * one colour a triangle, and never moves. A spirit bobs, turns to look at
 * what it is about to strike, lunges, flickers and dissolves, every frame; it
 * needs smooth shading so it reads as a carved and polished object rather than
 * a heap of facets, and it needs to *glow* -- its eyes, its uli lines, the
 * jewels on its crest -- by an amount that changes with what it is doing.
 *
 * ## Layout
 *
 * An indexed mesh in the model's own frame: x right, **y forward** (the face
 * looks down +y), z up, one unit about the mask's height, centred on the
 * middle of the face so it turns about itself. Vertices are shared between
 * triangles of the same colour and split where the colour changes, so a
 * colour boundary is a crisp edge while the surface stays smooth across it.
 *
 * Each vertex belongs to at most one glow [channel][GlowChannel]. At draw
 * time a [SpiritPose] says how brightly each channel burns; a lit vertex is
 * pushed towards its [glowColors] entry and made emissive, so a dark ink eye
 * slit becomes a burning jewel without a second mesh or a texture.
 *
 * Immutable once built, so one mesh per genome is shared by every copy of
 * that mask on screen and cached for the life of the process.
 */
class SpiritMesh(
    /** x, y, z per vertex. */
    val positions: FloatArray,
    /** Unit normal per vertex, from the shape's gradient, not the facets. */
    val normals: FloatArray,
    /** ARGB per vertex: the flat designer colour of the field it sits in. */
    val colors: IntArray,
    /** Which [GlowChannel] each vertex lights with, [GlowChannel.NONE] for none. */
    val channels: ByteArray,
    /** ARGB per vertex: what the vertex burns as when its channel is lit. */
    val glowColors: IntArray,
    /** Three vertex indices per triangle. */
    val indices: IntArray,
    /**
     * Where the eyes are, in model space (x, y, z for the left then the right):
     * a crit's flash and a spirit's gaze are drawn there.
     */
    val eyes: FloatArray = FloatArray(6),
    /** The colour the whole spirit radiates: its aura, trail and rings. */
    val auraColor: Int = 0xFFFFE2B0.toInt(),
    /** Its second colour, for the inside of rings and the tail of a trail. */
    val auraSecond: Int = auraColor,
    /** Height of the mesh in model units; 1 unless built otherwise. */
    val height: Float = 1f,
    /** The mask's field colour, for its hands. */
    val faceColor: Int = 0xFFF1EBDF.toInt(),
    /** Its fringe's colours, ribbon by ribbon: raffia in bands, like a Deco awning. */
    val fringeColors: IntArray = intArrayOf(0xFFD9B26A.toInt(), 0xFF8C5A2B.toInt()),
    /** Its charms' colour (cowries, beads). */
    val charmColor: Int = 0xFFF6EDDC.toInt(),
) {
    init {
        require(positions.size % 3 == 0) { "positions are x, y, z triples" }
        val n = positions.size / 3
        require(normals.size == positions.size) { "one normal per vertex" }
        require(colors.size == n && channels.size == n && glowColors.size == n) { "one colour, channel and glow per vertex" }
        require(indices.size % 3 == 0) { "indices must be whole triangles" }
        require(eyes.size == 6) { "two eyes, x y z each" }
    }

    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    /** The front of a bare emoji face, where a drawn expression is laid; null for a mask with its features carved. */
    @Volatile var face: SpiritFace? = null
}

/**
 * The front surface of a bare emoji face, sampled on a grid, so 2D features
 * can be laid on the 3D head wherever they are drawn.
 *
 * Features are designed in the mask's face units (u right, v up, the face
 * spanning v -1..1); [surface] carries a point of that plane onto the head
 * in model space, riding its curve, with the surface's normal.
 */
class SpiritFace(
    /** Face units covered: u from -[uMax] to [uMax], v from [v0] by [step], [nu] by [nv] samples. */
    val uMax: Float,
    val v0: Float,
    val step: Float,
    val nu: Int,
    val nv: Int,
    /** How far the surface stands forward at each sample, in face units; NaN off the face. */
    val front: FloatArray,
    /** Face units to model units, and the v that is model height 0. */
    val scale: Float,
    val midV: Float,
    /** Where features sit, in face units: half the face's width, the eye line, the eyes' spread, the mouth line. */
    val plan: FloatArray,
) {
    private fun at(i: Int, j: Int): Float = front[j.coerceIn(0, nv - 1) * nu + i.coerceIn(0, nu - 1)]

    /** Forward height at ([u], [v]), bilinear; NaN when any sample round it is off the face. */
    fun height(u: Float, v: Float): Float {
        val fi = (u + uMax) / step; val fj = (v - v0) / step
        val i = kotlin.math.floor(fi).toInt(); val j = kotlin.math.floor(fj).toInt()
        val tx = fi - i; val ty = fj - j
        val a = at(i, j); val b = at(i + 1, j); val c = at(i, j + 1); val d = at(i + 1, j + 1)
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
    }

    /**
     * The face point ([u], [v]) on the head, raised [lift] face units off it:
     * model x, y (forward), z and the normal into [out] (six floats). False
     * off the face, where there is nothing to draw on.
     */
    fun surface(u: Float, v: Float, lift: Float, out: FloatArray): Boolean {
        val h = height(u, v)
        if (!h.isFinite()) return false
        val e = step * 0.75f
        val hx = (height(u + e, v) - height(u - e, v)) / (2 * e)
        val hz = (height(u, v + e) - height(u, v - e)) / (2 * e)
        // Normal of w = h(u, v) in face units is (-dh/du, 1, -dh/dv); model x is -u.
        var nx = if (hx.isFinite()) hx else 0f
        var nz = if (hz.isFinite()) -hz else 0f
        var ny = 1f
        val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        nx /= l; ny /= l; nz /= l
        out[0] = -u * scale + nx * lift * scale
        out[1] = h * scale + ny * lift * scale
        out[2] = (v - midV) * scale + nz * lift * scale
        out[3] = nx; out[4] = ny; out[5] = nz
        return true
    }
}

/**
 * A drawn expression, laid on a [SpiritFace]: flat-coloured triangles in the
 * spirit's model space, each vertex with a normal and how much it glows (eyes
 * burn, ink does not). Rebuilt whenever the expression changes, cached by
 * whoever builds it; the scene only poses it with its spirit.
 */
class SpiritFeatures(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: IntArray,
    /** 0 drawn colour, 1 lights with the spirit's eyes. */
    val glow: FloatArray,
    val indices: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
}

/** The parts of a spirit that light up, each driven on its own. */
object GlowChannel {
    const val NONE: Byte = 0
    /** Eye slits, pupils and bores. Flashes on a critical. */
    const val EYES: Byte = 1
    /** Uli linework, ichi cuts and cheek marks. Breathes. */
    const val LINES: Byte = 2
    /** Jewels, accent bands and tips of the crest. Flares on an attack. */
    const val CREST: Byte = 3
    const val COUNT = 4
}

/**
 * Where a spirit is and how it is lit this frame: everything the motion
 * layers decide, in the form the scene draws.
 *
 * Mutable and reused, frame after frame, by whoever animates the spirit: a
 * pose is filled in place rather than allocated, so fifty spirits cost no
 * garbage. Angles are radians; [yaw] turns about z from facing +y.
 */
class SpiritPose {
    var x = 0f
    var y = 0f
    var z = 0f
    var yaw = 0f
    var pitch = 0f
    var roll = 0f
    /** World height of the mask, in blocks. */
    var scale = 0.6f

    /** Overall glow, 0 dormant to 1 blazing; each channel adds to it. */
    var glow = 0.3f
    var eyes = 0f
    var lines = 0f
    var crest = 0f

    /** 1 solid; below it the spirit dissolves through a screen-door fade. */
    var opacity = 1f

    /** 0..1: a white flash over the whole mask, for a hit taken. */
    var flash = 0f

    /** 0..1: a shield of light raised in front of whoever it guards. */
    var shield = 0f

    /** 0..1: how far into its strike, for rings and trails; 0 when not striking. */
    var flare = 0f

    /** 0..1: a death's burst of shards, 0 when alive. */
    var shatter = 0f

    /** Where the shield stands, and which way it faces (unit, in plan). */
    var shieldX = 0f
    var shieldY = 0f
    var shieldZ = 0f
    var shieldFacingX = 0f
    var shieldFacingY = 1f

    /**
     * The last [TRAIL] places the spirit has been, newest first, x y z each;
     * [trailCount] of them are real. Drawn as a comet tail of afterimages
     * while it strikes.
     */
    val trail = FloatArray(TRAIL * 3)
    var trailCount = 0

    /** Squash and stretch along the mask's height: above 1 taller and thinner, below 1 squat. */
    var stretch = 1f

    /** 0..1 a crack of light where a blow just landed on it. */
    var crack = 0f

    /**
     * The raffia fringe, world positions: [fringeRibbons] chains of
     * [fringePoints] points, x y z each, anchor first. 0 ribbons for none.
     */
    val fringe = FloatArray(MAX_RIBBONS * MAX_POINTS * 3)
    var fringeRibbons = 0
    var fringePoints = 0

    /** Floating hands, 0..2: offsets in the mask's own frame, in mask heights, left then right. */
    var handCount = 0
    val hands = FloatArray(6)
    var handGlow = 0f

    /** Charms orbiting the mask, and where round the orbit they are. */
    var charms = 0
    var charmSpin = 0f

    /** Records the current position at the head of [trail]. */
    fun pushTrail() {
        System.arraycopy(trail, 0, trail, 3, trail.size - 3)
        trail[0] = x; trail[1] = y; trail[2] = z
        if (trailCount < TRAIL) trailCount++
    }

    fun copyFrom(o: SpiritPose) {
        x = o.x; y = o.y; z = o.z; yaw = o.yaw; pitch = o.pitch; roll = o.roll; scale = o.scale
        glow = o.glow; eyes = o.eyes; lines = o.lines; crest = o.crest; opacity = o.opacity
        flash = o.flash; shield = o.shield; flare = o.flare; shatter = o.shatter
        shieldX = o.shieldX; shieldY = o.shieldY; shieldZ = o.shieldZ; shieldFacingX = o.shieldFacingX; shieldFacingY = o.shieldFacingY
        o.trail.copyInto(trail); trailCount = o.trailCount
        stretch = o.stretch; crack = o.crack
        o.fringe.copyInto(fringe); fringeRibbons = o.fringeRibbons; fringePoints = o.fringePoints
        handCount = o.handCount; o.hands.copyInto(hands); handGlow = o.handGlow; charms = o.charms; charmSpin = o.charmSpin
    }

    /** Takes what a part carried by this pose shares with it: place, heading, size, fade and flash. */
    fun copyPartFrom(o: SpiritPose) {
        x = o.x; y = o.y; z = o.z; yaw = o.yaw; pitch = o.pitch; roll = o.roll; scale = o.scale; stretch = 1f
        opacity = o.opacity; flash = o.flash; eyes = 0f; lines = 0f; crest = 0f; glow = o.glow
    }

    companion object {
        const val TRAIL = 8
        const val MAX_RIBBONS = 12
        const val MAX_POINTS = 6
    }
}

/** One spirit to draw: which mesh, and where and how lit. Both reused frame to frame. */
class SpiritInstance(var mesh: SpiritMesh, val pose: SpiritPose = SpiritPose()) {
    /** Whose spirit this is, for a caller that pools instances. */
    var id: String = ""

    /** The live expression drawn on a bare emoji face this frame; null draws the mesh alone. */
    var features: SpiritFeatures? = null
}

/**
 * Puts a [SpiritMesh] into a frame at a [SpiritPose].
 *
 * Kept apart from [SceneBuilder] so the arithmetic can be tested and
 * benchmarked on its own, and so the builder's hook stays a few lines. Holds
 * its rotation in fields, so emitting allocates nothing.
 */
class SpiritEmitter {
    private var m00 = 1f; private var m01 = 0f; private var m02 = 0f
    private var m10 = 0f; private var m11 = 1f; private var m12 = 0f
    private var m20 = 0f; private var m21 = 0f; private var m22 = 1f
    private val channel = FloatArray(GlowChannel.COUNT)

    /** Sets the rotation for [pose]: roll about the face's axis, then pitch, then yaw. */
    private fun orient(pose: SpiritPose) {
        val cy = cos(pose.yaw); val sy = sin(pose.yaw)
        val cp = cos(pose.pitch); val sp = sin(pose.pitch)
        val cr = cos(pose.roll); val sr = sin(pose.roll)
        // R = Rz(yaw) * Rx(pitch) * Ry(roll); rows of the matrix.
        m00 = cy * cr - sy * sp * sr; m01 = -sy * cp; m02 = cy * sr + sy * sp * cr
        m10 = sy * cr + cy * sp * sr; m11 = cy * cp; m12 = sy * sr - cy * sp * cr
        m20 = -cp * sr; m21 = sp; m22 = cp * cr
    }

    /** The world position of a model-space point under the last [emit]'s pose. */
    fun worldOf(pose: SpiritPose, x: Float, y: Float, z: Float, out: FloatArray, at: Int = 0) {
        orient(pose)
        val s = pose.scale
        out[at] = pose.x + (m00 * x + m01 * y + m02 * z) * s
        out[at + 1] = pose.y + (m10 * x + m11 * y + m12 * z) * s
        out[at + 2] = pose.z + (m20 * x + m21 * y + m22 * z) * s
    }

    /**
     * Emits the mesh into [out]. [out] is the opaque actors' batch when the
     * spirit is solid, and the cut-out batch when it is fading, whose
     * occlusion slot is read as opacity (a dithered screen-door dissolve).
     */
    fun emit(mesh: SpiritMesh, pose: SpiritPose, out: MeshBuilder, fading: Boolean) {
        orient(pose)
        val s = pose.scale
        // Squash and stretch keep the volume: taller is thinner.
        val sz = s * pose.stretch.let { if (it.isFinite() && it > 0.2f) it else 1f }
        val sxy = s / sqrt(sz / s)
        val base = pose.glow.finite().coerceIn(0f, 1f)
        channel[GlowChannel.NONE.toInt()] = 0f
        channel[GlowChannel.EYES.toInt()] = (base * 0.6f + pose.eyes.finite()).coerceIn(0f, 1.5f)
        channel[GlowChannel.LINES.toInt()] = (base * 0.5f + pose.lines.finite()).coerceIn(0f, 1.5f)
        channel[GlowChannel.CREST.toInt()] = (base * 0.4f + pose.crest.finite()).coerceIn(0f, 1.5f)
        val flash = pose.flash.finite().coerceIn(0f, 1f)
        val opacity = if (fading) pose.opacity.finite().coerceIn(0f, 1f) else 1f
        val p = mesh.positions; val n = mesh.normals
        val first = out.vertexCount
        val material = if (mesh.face != null) Vertex.CLAY else Vertex.ACTOR
        for (i in 0 until mesh.vertexCount) {
            val o = i * 3
            val lx = p[o] * sxy; val ly = p[o + 1] * sxy; val lz = p[o + 2] * sz
            val nx = n[o]; val ny = n[o + 1]; val nz = n[o + 2]
            val g = channel[mesh.channels[i].toInt()]
            var color = mesh.colors[i]
            // A lit vertex takes on its glow colour quickly as it brightens,
            // so a dark ink line becomes a burning one rather than a glowing
            // black -- and never lingers in the muddy half-way mix.
            if (g > GLOW_ONSET) color = mixArgb(color, mesh.glowColors[i], ((g - GLOW_ONSET) / GLOW_RAMP).coerceAtMost(1f))
            if (flash > 0f) color = mixArgb(color, FLASH_WHITE, flash * 0.75f)
            val emissive = g * GLOW_EMISSIVE + base * BASE_EMISSIVE + flash * 0.6f
            out.vertex(
                pose.x + m00 * lx + m01 * ly + m02 * lz,
                pose.y + m10 * lx + m11 * ly + m12 * lz,
                pose.z + m20 * lx + m21 * ly + m22 * lz,
                m00 * nx + m01 * ny + m02 * nz,
                m10 * nx + m11 * ny + m12 * nz,
                m20 * nx + m21 * ny + m22 * nz,
                color.toLong() and 0xFFFFFFFFL, opacity, 0f, 0f, material, emissive,
            )
        }
        val idx = mesh.indices
        var t = 0
        while (t < idx.size) {
            out.triangle(first + idx[t], first + idx[t + 1], first + idx[t + 2])
            t += 3
        }
    }

    /**
     * Emits a drawn expression with the last [emit]'s pose, squash and
     * stretch: it rides the face it was laid on. Eyes glow with the
     * spirit's eye channel; everything takes its hit flash.
     */
    fun emitFeatures(features: SpiritFeatures, pose: SpiritPose, out: MeshBuilder, fading: Boolean) {
        orient(pose)
        val s = pose.scale
        val sz = s * pose.stretch.let { if (it.isFinite() && it > 0.2f) it else 1f }
        val sxy = s / sqrt(sz / s)
        val eyes = channel[GlowChannel.EYES.toInt()]
        val flash = pose.flash.finite().coerceIn(0f, 1f)
        val opacity = if (fading) pose.opacity.finite().coerceIn(0f, 1f) else 1f
        val p = features.positions; val n = features.normals
        val first = out.vertexCount
        for (i in 0 until features.vertexCount) {
            val o = i * 3
            val lx = p[o] * sxy; val ly = p[o + 1] * sxy; val lz = p[o + 2] * sz
            val nx = n[o]; val ny = n[o + 1]; val nz = n[o + 2]
            var color = features.colors[i]
            if (flash > 0f) color = mixArgb(color, FLASH_WHITE, flash * 0.6f)
            // Features read as drawn: a touch of self-light keeps ink ink and colour colour on the shaded side.
            val emissive = FEATURE_EMISSIVE + features.glow[i] * (0.9f + eyes * GLOW_EMISSIVE) + flash * 0.5f
            out.vertex(
                pose.x + m00 * lx + m01 * ly + m02 * lz,
                pose.y + m10 * lx + m11 * ly + m12 * lz,
                pose.z + m20 * lx + m21 * ly + m22 * lz,
                m00 * nx + m01 * ny + m02 * nz,
                m10 * nx + m11 * ny + m12 * nz,
                m20 * nx + m21 * ny + m22 * nz,
                color.toLong() and 0xFFFFFFFFL, opacity, 0f, 0f, Vertex.CLAY, emissive,
            )
        }
        val idx = features.indices
        var t = 0
        while (t < idx.size) {
            out.triangle(first + idx[t], first + idx[t + 1], first + idx[t + 2])
            t += 3
        }
    }

    /**
     * The sticker's edge: the mesh again, swollen by [width] model units
     * along its normals and pushed [push] of them away from the eye along
     * ([ax], [ay], [az]), in flat [color]. The head covers all of it but a
     * clean border round its outline, whatever way it turns -- the white
     * die-cut edge of an emoji sticker.
     */
    fun emitShell(mesh: SpiritMesh, pose: SpiritPose, out: MeshBuilder, ax: Float, ay: Float, az: Float, width: Float, push: Float, color: Int) {
        orient(pose)
        val s = pose.scale
        val sz = s * pose.stretch.let { if (it.isFinite() && it > 0.2f) it else 1f }
        val sxy = s / sqrt(sz / s)
        val p = mesh.positions; val n = mesh.normals
        val first = out.vertexCount
        val c = color.toLong() and 0xFFFFFFFFL
        for (i in 0 until mesh.vertexCount) {
            val o = i * 3
            val nx = n[o]; val ny = n[o + 1]; val nz = n[o + 2]
            val lx = p[o] * sxy + nx * width * s; val ly = p[o + 1] * sxy + ny * width * s; val lz = p[o + 2] * sz + nz * width * s
            out.vertex(
                pose.x + m00 * lx + m01 * ly + m02 * lz + ax * push * s,
                pose.y + m10 * lx + m11 * ly + m12 * lz + ay * push * s,
                pose.z + m20 * lx + m21 * ly + m22 * lz + az * push * s,
                -ax, -ay, -az, c, 1f, 0f, 0f, Vertex.ACTOR, SHELL_EMISSIVE,
            )
        }
        val idx = mesh.indices
        var t = 0
        while (t < idx.size) {
            out.triangle(first + idx[t], first + idx[t + 1], first + idx[t + 2])
            t += 3
        }
    }

    companion object {
        /** Enough self-light that a white sticker edge stays white in any light. */
        const val SHELL_EMISSIVE = 0.55f

        /** Self-light on a drawn expression, so it reads as graphic, not as paint in shadow. */
        const val FEATURE_EMISSIVE = 0.18f

        /** How emissive a fully lit channel is; the shading multiplies it by the albedo. */
        const val GLOW_EMISSIVE = 1.7f
        /** Below this a channel keeps its drawn colour; over [GLOW_RAMP] more it becomes its light. */
        const val GLOW_ONSET = 0.12f
        const val GLOW_RAMP = 0.25f
        /** A whisper of self-light on the whole mask while it glows, so it never sinks into shadow. */
        const val BASE_EMISSIVE = 0.12f
        private const val FLASH_WHITE = 0xFFFFFBF2.toInt()

        private fun Float.finite(): Float = if (isFinite()) this else 0f

        /** Linear mix of two ARGB colours, alpha kept opaque. */
        fun mixArgb(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            val r = ((a shr 16) and 255) + (((b shr 16) and 255) - ((a shr 16) and 255)) * k
            val g = ((a shr 8) and 255) + (((b shr 8) and 255) - ((a shr 8) and 255)) * k
            val bl = (a and 255) + ((b and 255) - (a and 255)) * k
            return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or bl.toInt()
        }

        /** Length of a vector, for callers normalising without allocating. */
        fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
    }
}
