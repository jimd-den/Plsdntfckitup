package com.stratum.engine.render

import com.stratum.core.domain.art.PropSilhouette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The vector shapes props are drawn from.
 *
 * The world used to draw its scenery as emoji, one glyph per block type. That
 * was a real improvement over stacked brown boxes — a tree that reads as a tree
 * at a glance is worth a great deal — but it has a ceiling: every tree in the
 * world is the identical glyph at the identical size, so a forest reads as a
 * repeating texture rather than as woodland, and the shapes belong to whichever
 * font the device happens to ship rather than to this game.
 *
 * Here a prop is a handful of polygons generated from a family and a variant.
 * Five families cover everything a pack can scatter, the variant makes every
 * instance a different individual, and the shapes are the same on every device
 * because we own them.
 *
 * Coordinates are in tile units: x runs -0.5..0.5 across the base, y is
 * negative upwards from the ground contact point, so a prop one tile tall
 * reaches y = -1. The caller scales.
 */
object PropSilhouettes {

    /** How many distinct individuals exist per family. More is indistinguishable. */
    const val VARIANTS = 8

    /**
     * Shapes are generated once and reused for the life of the process.
     *
     * A frame draws hundreds of props and there are only forty possible shapes
     * between them, so generating per draw would be the same trigonometry a
     * hundred times over for an answer that cannot change.
     */
    private val cache = HashMap<Int, List<SilhouettePart>>()

    fun parts(family: PropSilhouette, variant: Int): List<SilhouettePart> {
        val slot = variant.mod(VARIANTS)
        val key = family.ordinal * VARIANTS + slot
        return cache.getOrPut(key) { build(family, slot) }
    }

    private fun build(family: PropSilhouette, variant: Int): List<SilhouettePart> {
        val rng = Roll(family.ordinal * 7919L + variant * 104729L)
        return when (family) {
            PropSilhouette.CANOPY -> canopy(rng)
            PropSilhouette.SPIRE -> spire(rng)
            PropSilhouette.FROND -> frond(rng)
            PropSilhouette.BOULDER -> boulder(rng)
            PropSilhouette.SHRINE -> shrine(rng)
            PropSilhouette.CRYSTAL -> crystal(rng)
            PropSilhouette.BRAZIER -> brazier(rng)
            PropSilhouette.SIGIL -> sigil(rng)
        }
    }

    /**
     * A broad crown on a tapered trunk.
     *
     * The crown is a jittered ring rather than a circle, and the trunk leans
     * slightly: two lines of code that are most of the difference between a
     * forest and a field of lollipops.
     */
    private fun canopy(rng: Roll): List<SilhouettePart> {
        val height = 1.25f + rng.next() * 0.55f
        val lean = (rng.next() - 0.5f) * 0.16f
        val crownY = -height
        val crownRadius = 0.36f + rng.next() * 0.16f

        val trunk = floatArrayOf(
            -0.07f, 0f,
            0.07f, 0f,
            0.05f + lean, crownY + crownRadius * 0.6f,
            -0.05f + lean, crownY + crownRadius * 0.6f,
        )
        val crown = blob(lean, crownY, crownRadius, crownRadius * 0.82f, points = 9, jitter = 0.22f, rng = rng)
        // The lower right of the crown, so a canopy has a lit side and a shaded
        // one without needing a gradient or a second light.
        val shade = arcSlice(lean, crownY, crownRadius * 0.98f, crownRadius * 0.8f, from = -20f, to = 150f)

        return listOf(
            SilhouettePart(trunk, PartRole.SUPPORT),
            SilhouettePart(crown, PartRole.BODY),
            SilhouettePart(shade, PartRole.SHADE),
        )
    }

    /** Tall, narrow, stacked: conifers, cypresses, standing stones. */
    private fun spire(rng: Roll): List<SilhouettePart> {
        val height = 1.4f + rng.next() * 0.7f
        val width = 0.3f + rng.next() * 0.12f
        val tiers = 3
        val parts = mutableListOf<SilhouettePart>()
        parts += SilhouettePart(
            floatArrayOf(-0.06f, 0f, 0.06f, 0f, 0.04f, -height * 0.35f, -0.04f, -height * 0.35f),
            PartRole.SUPPORT,
        )
        for (tier in 0 until tiers) {
            val bottom = -height * (0.28f + 0.22f * tier)
            val top = -height * (0.28f + 0.22f * tier + 0.34f)
            val spread = width * (1f - tier * 0.24f)
            parts += SilhouettePart(
                floatArrayOf(-spread, bottom, spread, bottom, 0f, top),
                if (tier == tiers - 1) PartRole.BODY else PartRole.BODY,
            )
        }
        parts += SilhouettePart(
            floatArrayOf(0f, -height * 0.28f, width * 0.55f, -height * 0.28f, 0f, -height * 0.62f),
            PartRole.SHADE,
        )
        return parts
    }

    /** Blades from a common base: reeds, grass, crops. */
    private fun frond(rng: Roll): List<SilhouettePart> {
        val blades = 5 + (rng.next() * 3f).toInt()
        val parts = mutableListOf<SilhouettePart>()
        for (blade in 0 until blades) {
            val spread = (blade - (blades - 1) / 2f) / blades
            val height = 0.5f + rng.next() * 0.5f
            val tipX = spread * 0.9f + (rng.next() - 0.5f) * 0.2f
            parts += SilhouettePart(
                floatArrayOf(
                    -0.045f + spread * 0.18f, 0f,
                    0.045f + spread * 0.18f, 0f,
                    tipX, -height,
                ),
                if (blade % 3 == 0) PartRole.SHADE else PartRole.BODY,
            )
        }
        return parts
    }

    /** Low and irregular: boulders, rubble, shrubs. */
    private fun boulder(rng: Roll): List<SilhouettePart> {
        val radius = 0.3f + rng.next() * 0.18f
        val height = radius * (0.8f + rng.next() * 0.5f)
        val body = blob(0f, -height * 0.55f, radius, height * 0.85f, points = 7, jitter = 0.3f, rng = rng)
        val shade = arcSlice(0f, -height * 0.55f, radius * 0.95f, height * 0.8f, from = -10f, to = 160f)
        return listOf(
            SilhouettePart(body, PartRole.BODY),
            SilhouettePart(shade, PartRole.SHADE),
        )
    }

    /** Built and symmetrical, with a plinth: shrines, altars, idols. */
    private fun shrine(rng: Roll): List<SilhouettePart> {
        val height = 1.0f + rng.next() * 0.5f
        val width = 0.3f + rng.next() * 0.1f
        return listOf(
            SilhouettePart(
                floatArrayOf(-width * 1.35f, 0f, width * 1.35f, 0f, width * 1.15f, -height * 0.16f, -width * 1.15f, -height * 0.16f),
                PartRole.SUPPORT,
            ),
            SilhouettePart(
                floatArrayOf(
                    -width, -height * 0.16f,
                    width, -height * 0.16f,
                    width * 0.82f, -height * 0.8f,
                    -width * 0.82f, -height * 0.8f,
                ),
                PartRole.BODY,
            ),
            SilhouettePart(
                floatArrayOf(-width * 1.1f, -height * 0.8f, width * 1.1f, -height * 0.8f, 0f, -height),
                PartRole.ACCENT,
            ),
            SilhouettePart(
                floatArrayOf(0f, -height * 0.16f, width, -height * 0.16f, width * 0.82f, -height * 0.8f, 0f, -height * 0.8f),
                PartRole.SHADE,
            ),
        )
    }

    /** Faceted and emissive: crystals, ritual growths, exposed seams. */
    private fun crystal(rng: Roll): List<SilhouettePart> {
        val parts = mutableListOf<SilhouettePart>()
        val shards = 3
        for (shard in 0 until shards) {
            val offset = (shard - 1) * 0.16f + (rng.next() - 0.5f) * 0.08f
            val height = (0.55f + rng.next() * 0.6f) * (if (shard == 1) 1.25f else 0.8f)
            val width = 0.09f + rng.next() * 0.06f
            parts += SilhouettePart(
                floatArrayOf(
                    offset - width, 0f,
                    offset + width, 0f,
                    offset + width * 0.5f, -height * 0.7f,
                    offset, -height,
                    offset - width * 0.7f, -height * 0.55f,
                ),
                if (shard == 1) PartRole.ACCENT else PartRole.BODY,
            )
        }
        return parts
    }

    /** A bowl on a stand, with a flame: braziers, lanterns, torches. */
    private fun brazier(rng: Roll): List<SilhouettePart> {
        val height = 0.85f + rng.next() * 0.3f
        return listOf(
            SilhouettePart(
                floatArrayOf(-0.2f, 0f, 0.2f, 0f, 0.08f, -height * 0.55f, -0.08f, -height * 0.55f),
                PartRole.SUPPORT,
            ),
            SilhouettePart(
                floatArrayOf(
                    -0.26f, -height * 0.55f,
                    0.26f, -height * 0.55f,
                    0.18f, -height * 0.78f,
                    -0.18f, -height * 0.78f,
                ),
                PartRole.BODY,
            ),
            SilhouettePart(
                floatArrayOf(-0.12f, -height * 0.74f, 0.12f, -height * 0.74f, 0.05f, -height * 1.05f, 0f, -height * 1.25f, -0.06f, -height * 0.98f),
                PartRole.ACCENT,
            ),
        )
    }

    /** A flat marker standing upright: seals, banners, signs. */
    private fun sigil(rng: Roll): List<SilhouettePart> {
        val height = 0.9f + rng.next() * 0.4f
        val width = 0.22f + rng.next() * 0.08f
        return listOf(
            SilhouettePart(floatArrayOf(-0.04f, 0f, 0.04f, 0f, 0.03f, -height, -0.03f, -height), PartRole.SUPPORT),
            SilhouettePart(
                floatArrayOf(
                    -width, -height * 0.45f,
                    width, -height * 0.45f,
                    width, -height * 0.95f,
                    0f, -height * 0.86f,
                    -width, -height * 0.95f,
                ),
                PartRole.BODY,
            ),
        )
    }

    /** An n-gon with jittered radii: the workhorse organic shape. */
    private fun blob(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        points: Int,
        jitter: Float,
        rng: Roll,
    ): FloatArray {
        val out = FloatArray(points * 2)
        for (index in 0 until points) {
            val angle = index.toFloat() / points * TWO_PI
            val wobble = 1f - jitter / 2f + rng.next() * jitter
            out[index * 2] = centerX + cos(angle) * radiusX * wobble
            out[index * 2 + 1] = centerY + sin(angle) * radiusY * wobble
        }
        return out
    }

    /** A wedge of an ellipse, used as the shaded side of a round form. */
    private fun arcSlice(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        from: Float,
        to: Float,
        steps: Int = 7,
    ): FloatArray {
        val out = FloatArray((steps + 1) * 2 + 2)
        out[0] = centerX
        out[1] = centerY
        for (step in 0..steps) {
            val angle = (from + (to - from) * step / steps) / 180f * PI.toFloat()
            out[2 + step * 2] = centerX + cos(angle) * radiusX
            out[3 + step * 2] = centerY + sin(angle) * radiusY
        }
        return out
    }

    private const val TWO_PI = (PI * 2).toFloat()
}

/** One polygon of a prop, and which of the style's colours it takes. */
class SilhouettePart(val points: FloatArray, val role: PartRole)

/**
 * Which colour a part is drawn in.
 *
 * Roles rather than colours so the same shapes serve every style: the art
 * director decides what "shade" means in a kawaii world and what it means in a
 * grimdark one, and the geometry never finds out.
 */
enum class PartRole { SUPPORT, BODY, SHADE, ACCENT }

/** A tiny deterministic roller, so the same variant is the same shape forever. */
private class Roll(seed: Long) {
    private var state = seed * 6364136223846793005L + 1442695040888963407L

    fun next(): Float {
        state = state * 6364136223846793005L + 1442695040888963407L
        val bits = (state ushr 33).toInt() and 0xFFFF
        return bits.toFloat() / 0xFFFF
    }
}
