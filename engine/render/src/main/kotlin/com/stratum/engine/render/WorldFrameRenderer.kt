package com.stratum.engine.render

import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.art.ActorStyle
import com.stratum.core.domain.art.MoteKind
import com.stratum.core.domain.art.PropCue
import com.stratum.core.domain.art.PropStyle
import com.stratum.core.domain.art.TerrainCue
import com.stratum.core.domain.art.Tint
import com.stratum.core.domain.art.WorldArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.world.IsometricProjection
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Turns a world into a frame.
 *
 * This used to live inside the Compose canvas, which meant the look of the game
 * was Android code: it could not be tested without a device, could not be
 * rendered on a build machine, and could not be reasoned about without reading
 * a composable. Here it is a pure walk over a read-only [World] that emits
 * primitives to a [FrameSink], and every decision about colour, weight and
 * atmosphere is delegated to a [WorldArtDirector].
 *
 * The division of labour is the point:
 *  - the world says what is there,
 *  - the projection says where it lands on screen,
 *  - the director says what it looks like,
 *  - the sink puts pixels down,
 *  - and this class knows the drawing order, which is the one thing none of
 *    the others can know on their own.
 */
class WorldFrameRenderer(
    private val projection: IsometricProjection,
    var director: WorldArtDirector,
) {

    /** Reused across frames. A frame allocating per cube was the old profile's worst line. */
    private val scratch = FloatArray(MAX_POLYGON_POINTS * 2)
    private val emissiveX = IntArray(MAX_EMISSIVE)
    private val emissiveY = IntArray(MAX_EMISSIVE)
    private val emissiveColor = LongArray(MAX_EMISSIVE)
    private var emissiveCount = 0

    /** Filled by [measureLight] for the column currently being drawn. */
    private var litStrength = 0f
    private var litColor = 0L

    /**
     * Draws terrain, props and atmosphere, and returns the view it used.
     *
     * Actors are not drawn here. They are depth-sorted against each other and
     * against nothing else, they come from a different part of the engine, and
     * on Android they are sprite sheets rather than polygons — so the caller
     * draws them between [render] and [finish], which is the one ordering
     * constraint this class imposes on its callers.
     */
    fun render(
        world: World,
        camera: WorldPoint,
        width: Float,
        height: Float,
        sink: FrameSink,
        highlight: BlockPos? = null,
        time: WorldTime = WorldTime(),
        /** The region a column belongs to, when the generator can say. */
        biomeAt: (Int, Int) -> BiomeDefinition? = { _, _ -> null },
    ): FrameView {
        val cameraScreen = projection.project(camera)
        val originX = width / 2f - cameraScreen.x
        val originY = height / 2f - cameraScreen.y
        val eyeLevel = floor(camera.z).toInt()
        val view = FrameView(width, height, originX, originY, eyeLevel)

        val here = biomeAt(floor(camera.x).toInt(), floor(camera.y).toInt())
        val atmosphere = director.atmosphereFor(here, time)
        sink.backdrop(atmosphere.skyTop, atmosphere.skyBottom)

        val range = projection.visibleRange(width, height, originX, originY)
        collectEmissive(world, range.minX, range.maxX, range.minY, range.maxY)

        // The far corner of what is on screen, so distance haze is a share of
        // what the player can actually see rather than of an arbitrary radius.
        val sightRadius = sqrt(
            ((range.maxX - range.minX) * (range.maxX - range.minX) +
                (range.maxY - range.minY) * (range.maxY - range.minY)).toFloat(),
        ).coerceAtLeast(1f) / 2f

        val halfWidth = projection.tileWidth * projection.zoom / 2f
        val halfHeight = projection.tileHeight * projection.zoom / 2f
        val lift = projection.blockHeight * projection.zoom

        range.forEachColumnInDrawOrder { x, y ->
            val surface = world.surfaceAt(x, y)
            if (surface < 0) return@forEachColumnInDrawOrder

            val floorZ = maxOf(0, surface - VISIBLE_DEPTH)
            if (!projection.isColumnOnScreen(x, y, surface, floorZ, originX, originY, width, height)) {
                return@forEachColumnInDrawOrder
            }

            // Props stand on the ground but are not the ground: shading and
            // ledge tests read the terrain underneath, or a tree would make its
            // own column look like a cliff.
            val ground = groundAt(world, x, y)

            val biome = biomeAt(x, y)
            val depthBelow = (eyeLevel - ground).coerceAtLeast(0)
            // Compared against the neighbours' *ground*, not their surface.
            // A surface includes whatever is standing on it, so a tree in the
            // next cell was casting the hard diamond-edged shadow of a cliff.
            val north = groundAt(world, x, y - 1) > ground
            val west = groundAt(world, x - 1, y) > ground
            val distance = (distanceFrom(camera, x, y) / sightRadius).coerceIn(0f, 1f)
            val grain = grainAt(x, y)
            measureLight(x, y)

            var propZ = -1
            for (z in floorZ..surface) {
                val pos = BlockPos(x, y, z)
                val block = world.blockAt(pos)
                if (block.isAir) continue
                if (block.glyph != null) {
                    propZ = z
                    continue
                }
                // Fully buried cubes are invisible, and skipping them is the
                // single largest saving in the whole loop.
                if (z < ground && isEnclosed(world, pos)) continue

                val isTop = z == ground
                val style = director.terrainStyleFor(
                    TerrainCue(
                        block = block,
                        biomeId = biome?.id,
                        isTop = isTop,
                        depthBelowEye = depthBelow,
                        ledgeShadow = isTop && (north || west),
                        cornerShadow = isTop && north && west,
                        lightLevel = lightAt(world, pos, ground, z),
                        // Every face, not just the lid. Lighting only the tops
                        // put a bright diamond on the floor around a torch and
                        // left the walls of the pit it was standing in black.
                        emissive = litStrength,
                        emissiveColor = litColor,
                        distance = distance,
                        grain = grain,
                    ),
                )
                val screen = projection.project(pos)
                sink.cube(
                    centerX = originX + screen.x,
                    centerY = originY + screen.y,
                    halfWidth = halfWidth,
                    halfHeight = halfHeight,
                    lift = lift,
                    style = style,
                    highlight = if (pos == highlight) {
                        Tint.withAlpha(director.direction.palette.heroRim, HIGHLIGHT_ALPHA)
                    } else {
                        0L
                    },
                )
            }

            if (propZ >= 0) {
                val block = world.blockAt(BlockPos(x, y, propZ))
                val style = director.propStyleFor(
                    PropCue(
                        block = block,
                        biomeId = biome?.id,
                        variant = variantOf(x, y),
                        depthBelowEye = depthBelow,
                        distance = distance,
                        lightLevel = world.lightAt(BlockPos(x, y, propZ)),
                    ),
                )
                if (style != null) {
                    val screen = projection.project(BlockPos(x, y, propZ))
                    prop(sink, originX + screen.x, originY + screen.y + halfHeight, style)
                }
            }
        }

        return view
    }

    /**
     * The passes that go over everything: motes, overlay wash and vignette.
     *
     * Split from [render] so actors land underneath them. Weather in front of
     * the characters is what makes a scene feel like a place you are inside
     * rather than a diagram you are looking at, and it is also the cheapest
     * depth cue available.
     */
    fun finish(
        sink: FrameSink,
        view: FrameView,
        time: WorldTime = WorldTime(),
        biome: BiomeDefinition? = null,
    ) {
        val atmosphere = director.atmosphereFor(biome, time)

        if (atmosphere.moteKind != MoteKind.NONE && atmosphere.moteDensity > 0) {
            motes(sink, view, time, atmosphere.moteKind, atmosphere.moteDensity, atmosphere.moteColor, atmosphere.moteDrift)
        }
        if (Tint.alpha(atmosphere.overlay) > 0) sink.wash(atmosphere.overlay)
        if (atmosphere.vignette > 0f) sink.vignette(atmosphere.vignette, atmosphere.haze)
    }

    /**
     * An actor's footing: the shadow that plants it, the ring that ranks it and
     * the halo that pulls the eye to it.
     *
     * Drawn by this class rather than by each renderer so that a monster is
     * grounded identically whether it is a sprite sheet on a phone or a polygon
     * in a test image. It is also the single cheapest fix for the thing that
     * makes characters look pasted onto terrain, which is that nothing connects
     * them to it.
     */
    fun ground(sink: FrameSink, x: Float, y: Float, style: ActorStyle) {
        val radius = projection.tileWidth * projection.zoom * ACTOR_RADIUS * style.scale

        if (Tint.alpha(style.halo) > 0) {
            sink.glow(x, y, radius * HALO_SPREAD, style.halo)
        }
        if (Tint.alpha(style.groundRing) > 0) {
            sink.ring(x, y, radius * RING_SPREAD, style.groundRing, RING_WIDTH)
            // Squashed to the ground plane, so a ring reads as lying on the
            // floor rather than standing up in front of the monster.
            sink.ellipse(x, y, radius * RING_SPREAD, radius * RING_SPREAD * GROUND_SQUASH, Tint.withAlpha(style.groundRing, RING_FILL_ALPHA))
        }
        sink.ellipse(
            x, y,
            radius * SHADOW_SPREAD,
            radius * SHADOW_SPREAD * GROUND_SQUASH,
            Tint.withAlpha(director.direction.palette.ink, style.contactShadow),
        )
    }

    /**
     * A whole actor, for renderers with no sprite for it.
     *
     * Every actor draws something, always. Sprite sheets arrive one generation
     * at a time and a world where the ungenerated half is invisible is a world
     * nobody can play while the art is being made.
     */
    fun actor(sink: FrameSink, x: Float, y: Float, style: ActorStyle, facingX: Float = 0f, facingY: Float = 1f) {
        ground(sink, x, y, style)

        val radius = projection.tileWidth * projection.zoom * ACTOR_RADIUS * style.scale
        val centerY = y - radius * BODY_LIFT

        sink.ellipse(x, centerY, radius, radius * BODY_SQUASH, style.body)
        // A lit cap along the top edge. This is the rim light, and on a busy
        // field of terrain it does more to separate a body from its background
        // than any amount of colour on the body itself.
        if (style.rimStrength > 0f) {
            sink.ellipse(
                x,
                centerY - radius * RIM_OFFSET,
                radius * RIM_SPREAD,
                radius * BODY_SQUASH * RIM_SPREAD * 0.55f,
                Tint.withAlpha(style.rim, style.rimStrength),
            )
        }
        if (Tint.alpha(style.flashTint) > 0) {
            sink.ellipse(x, centerY, radius, radius * BODY_SQUASH, style.flashTint)
        }
        sink.ring(x, centerY, radius, style.outline, style.outlineWidth)

        // Which way it is going, on the world's axes rather than the screen's,
        // so "east" points where east actually is.
        val tip = projection.project(facingX, facingY, 0f)
        val origin = projection.project(0f, 0f, 0f)
        val dx = tip.x - origin.x
        val dy = tip.y - origin.y
        val length = sqrt(dx * dx + dy * dy)
        if (length > 0.001f) {
            val nx = dx / length
            val ny = dy / length
            scratch[0] = x + nx * radius * FACING_REACH
            scratch[1] = centerY + ny * radius * FACING_REACH
            scratch[2] = x - ny * radius * FACING_WIDTH
            scratch[3] = centerY + nx * radius * FACING_WIDTH
            scratch[4] = x + ny * radius * FACING_WIDTH
            scratch[5] = centerY - nx * radius * FACING_WIDTH
            sink.polygon(scratch, 6, style.rim, style.outline, 0f)
        }
    }

    /** Builds the presentation for an actor and draws it in one step. */
    fun actor(sink: FrameSink, x: Float, y: Float, presentation: ActorPresentation, facingX: Float = 0f, facingY: Float = 1f) {
        actor(sink, x, y, director.actorStyleFor(presentation), facingX, facingY)
    }

    /** One prop: its polygons, its contact shadow and its glow if it has one. */
    private fun prop(sink: FrameSink, x: Float, baseY: Float, style: PropStyle) {
        val unit = projection.tileWidth * projection.zoom * style.scale

        if (style.contactShadow > 0f) {
            sink.ellipse(
                x, baseY,
                unit * PROP_SHADOW_SPREAD,
                unit * PROP_SHADOW_SPREAD * GROUND_SQUASH,
                Tint.withAlpha(director.direction.palette.ink, style.contactShadow),
            )
        }
        if (Tint.alpha(style.glow) > 0) {
            sink.glow(x, baseY - unit * GLOW_LIFT, unit * GLOW_SPREAD, style.glow)
        }

        PropSilhouettes.parts(style.silhouette, style.variant).forEach { part ->
            val count = minOf(part.points.size, scratch.size)
            var index = 0
            while (index < count) {
                scratch[index] = x + part.points[index] * unit
                scratch[index + 1] = baseY + part.points[index + 1] * unit
                index += 2
            }
            val fill = when (part.role) {
                PartRole.SUPPORT -> style.support
                PartRole.BODY -> style.fill
                PartRole.SHADE -> style.shade
                PartRole.ACCENT -> if (Tint.alpha(style.glow) > 0) style.glow or Tint.OPAQUE else style.fill
            }
            sink.polygon(scratch, count, fill, style.outline, style.outlineWidth)
        }
    }

    /**
     * What is in the air.
     *
     * Positions come from the frame time and an index rather than from stored
     * particles: there is no state to update, no allocation, no spawn budget,
     * and the field is identical on every device and in every replay. Motes are
     * scenery, and scenery that needs a simulation is scenery that is not worth
     * having.
     */
    private fun motes(
        sink: FrameSink,
        view: FrameView,
        time: WorldTime,
        kind: MoteKind,
        density: Int,
        color: Long,
        drift: Float,
    ) {
        val seconds = time.elapsedSeconds
        val size = projection.tileWidth * projection.zoom
        for (index in 0 until density) {
            val phaseX = hashUnit(index, 1)
            val phaseY = hashUnit(index, 2)
            val speed = 0.5f + hashUnit(index, 3)

            val fallSpeed = when (kind) {
                MoteKind.SNOW, MoteKind.RAIN, MoteKind.LEAVES, MoteKind.PETALS, MoteKind.ASH -> drift * FALL_GAIN
                MoteKind.EMBERS, MoteKind.SPARKS -> -drift * RISE_GAIN
                else -> drift * DRIFT_GAIN
            }
            val sway = when (kind) {
                MoteKind.RAIN -> 0f
                MoteKind.FIREFLIES, MoteKind.SPORES, MoteKind.MIST -> SWAY_WIDE
                else -> SWAY_NARROW
            }

            val x = wrap(phaseX + sway * sinish(seconds * speed + phaseY * TAU), 1f) * view.width
            val y = wrap(phaseY + seconds * fallSpeed * speed * 0.05f, 1f) * view.height

            val radius = size * when (kind) {
                MoteKind.MIST -> MIST_RADIUS
                MoteKind.SNOW, MoteKind.LEAVES, MoteKind.PETALS -> LARGE_MOTE_RADIUS
                else -> SMALL_MOTE_RADIUS
            } * (0.6f + hashUnit(index, 4))

            val alpha = when (kind) {
                MoteKind.MIST -> MIST_ALPHA
                MoteKind.FIREFLIES, MoteKind.SPARKS, MoteKind.EMBERS ->
                    // Fireflies blink. It costs one sine and is the whole
                    // difference between insects and floating dots.
                    (0.35f + 0.65f * sinish(seconds * speed * BLINK_RATE + phaseX * TAU) * 0.5f + 0.32f)
                else -> MOTE_ALPHA
            }

            val tinted = Tint.withAlpha(color, alpha.coerceIn(0f, 1f))
            if (kind == MoteKind.RAIN) {
                scratch[0] = x; scratch[1] = y
                scratch[2] = x + radius * 0.3f; scratch[3] = y
                scratch[4] = x + radius * 0.3f; scratch[5] = y + radius * RAIN_LENGTH
                scratch[6] = x; scratch[7] = y + radius * RAIN_LENGTH
                sink.polygon(scratch, 8, tinted, 0L, 0f)
            } else if (kind == MoteKind.MIST) {
                sink.glow(x, y, radius, tinted)
            } else {
                sink.ellipse(x, y, radius, radius, tinted)
            }
        }
    }

    /**
     * Emissive sources on screen, gathered once a frame.
     *
     * A torch that only lights its own cell is a texture; one that spills onto
     * the ground around it is a light. Doing that properly means propagating
     * through the voxel grid, which the simulation already does for gameplay
     * light — this is the *colour* of it, which is a screen-space effect and
     * cheap as long as the source list stays short.
     */
    private fun collectEmissive(world: World, minX: Int, maxX: Int, minY: Int, maxY: Int) {
        emissiveCount = 0
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (emissiveCount >= MAX_EMISSIVE) return
                val surface = world.surfaceAt(x, y)
                if (surface < 0) continue
                val block = world.blockAt(BlockPos(x, y, surface))
                if (block.lightEmission <= 0) continue
                emissiveX[emissiveCount] = x
                emissiveY[emissiveCount] = y
                emissiveColor[emissiveCount] = if (Tint.alpha(block.accentColor) > 0) {
                    block.accentColor
                } else {
                    block.topColor
                }
                emissiveCount++
            }
        }
    }

    /**
     * Finds the strongest light reaching a column and leaves it in
     * [litStrength] and [litColor].
     *
     * Two fields rather than a returned pair. This is called once per visible
     * column, which on a phone is a couple of thousand times a frame, and a
     * `Pair` each time is an object allocated and collected for two numbers
     * that are read immediately and never stored.
     */
    private fun measureLight(x: Int, y: Int) {
        litStrength = 0f
        litColor = 0L
        if (emissiveCount == 0) return
        val radius = director.direction.light.emissiveRadius
        for (index in 0 until emissiveCount) {
            val dx = (emissiveX[index] - x).toFloat()
            val dy = (emissiveY[index] - y).toFloat()
            val distance = sqrt(dx * dx + dy * dy)
            if (distance > radius) continue
            val falloff = 1f - distance / radius
            val strength = falloff * falloff
            if (strength > litStrength) {
                litStrength = strength
                litColor = emissiveColor[index]
            }
        }
    }

    /**
     * How lit a cell is, 0..15.
     *
     * The simulation carries a light channel but nothing writes to it yet, so
     * asking the world alone returns pitch black for every cell in an open
     * field at noon — which is exactly what the first version of the shading
     * model drew. Until light is propagated for real, the sky is assumed to
     * reach anything at or above the ground and to fall off below it, which is
     * true of most of a voxel world most of the time.
     *
     * When block light does arrive it wins wherever it is stronger, so this
     * becomes a floor rather than something to tear out.
     */
    private fun lightAt(world: World, pos: BlockPos, ground: Int, z: Int): Int {
        val sky = (MAX_LIGHT - (ground - z) * SKY_FALLOFF).coerceIn(0, MAX_LIGHT)
        return maxOf(world.lightAt(pos), sky)
    }

    private fun distanceFrom(camera: WorldPoint, x: Int, y: Int): Float {
        val dx = camera.x - x
        val dy = camera.y - y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Per-cell jitter, so a plain of one block is not one flat sheet.
     *
     * Properly mixed rather than a multiply and an exclusive-or. The cheap
     * version correlated strongly between neighbouring cells and drew a visible
     * chequerboard across every open field — which reads as a rendering fault,
     * not as ground.
     */
    private fun grainAt(x: Int, y: Int): Float {
        // Mostly a coarse value shared by a patch of cells, with a little
        // per-cell variation on top. Pure per-cell noise on a regular diamond
        // grid does not read as uneven ground, it reads as a chequerboard —
        // the grid wins, however random the numbers on it are.
        val patch = (hash(x shr 1, y shr 1) and 0xFF).toFloat() / 255f
        val cell = (hash(x, y) and 0xFF).toFloat() / 255f
        return patch * PATCH_SHARE + cell * (1f - PATCH_SHARE)
    }

    /**
     * The top of the terrain in a column, ignoring anything standing on it.
     *
     * [World.surfaceAt] answers with the prop, which is right for picking and
     * wrong for shading: a tree is not a ledge, and treating it as one put a
     * cliff's shadow in front of every trunk in the forest.
     */
    private fun groundAt(world: World, x: Int, y: Int): Int {
        var z = world.surfaceAt(x, y)
        while (z > 0 && world.blockAt(BlockPos(x, y, z)).glyph != null) z--
        return z
    }

    /** Stable per-cell variant, so a tree does not change shape when you walk past it. */
    private fun variantOf(x: Int, y: Int): Int = (hash(x + VARIANT_SALT, y) shr 8) and 0xFFFF

    private fun hash(x: Int, y: Int): Int {
        var h = x * GRAIN_X + y * GRAIN_Y
        h = (h xor (h ushr 15)) * MIX_A
        h = (h xor (h ushr 13)) * MIX_B
        return (h xor (h ushr 16)) and 0x7FFFFFFF
    }

    /**
     * A cube is invisible when the only three faces this projection ever draws
     * are all covered.
     *
     * Only three, because the camera is fixed: the far sides of a cube can
     * never come into view however the player moves, so testing them would be
     * work done to reach the same answer.
     */
    private fun isEnclosed(world: World, pos: BlockPos): Boolean =
        world.blockAt(BlockPos(pos.x, pos.y, pos.z + 1)).isOpaque &&
            world.blockAt(BlockPos(pos.x + 1, pos.y, pos.z)).isOpaque &&
            world.blockAt(BlockPos(pos.x, pos.y + 1, pos.z)).isOpaque

    /** 0..1 from two integers, without a random source or any state. */
    private fun hashUnit(index: Int, salt: Int): Float {
        var hash = (index * 374761393 + salt * 668265263).toLong()
        hash = (hash xor (hash shr 13)) * 1274126177L
        return ((hash ushr 16) and 0xFFFF).toFloat() / 0xFFFF
    }

    /** A triangle wave standing in for a sine: motes do not need the real one. */
    private fun sinish(phase: Float): Float {
        val wrapped = wrap(phase / TAU, 1f)
        return 1f - abs(wrapped * 4f - 2f).coerceAtMost(2f)
    }

    private fun wrap(value: Float, span: Float): Float {
        val result = value % span
        return if (result < 0f) result + span else result
    }

    private companion object {
        /** Levels drawn below a column's surface, so cliffs have sides. */
        const val VISIBLE_DEPTH = 6
        const val MAX_POLYGON_POINTS = 32
        /** Lights considered per frame. Past this the nearest ones already dominate. */
        const val MAX_EMISSIVE = 48
        const val MAX_LIGHT = 15
        /** Levels of light lost per level below the surface. */
        const val SKY_FALLOFF = 3

        const val GRAIN_X = 73856093
        const val GRAIN_Y = 19349663
        const val MIX_A = 0x85EBCA6B.toInt()
        const val MIX_B = 0xC2B2AE35.toInt()
        const val VARIANT_SALT = 0x5F3A71
        /** How much of the grain comes from the patch rather than the cell. */
        const val PATCH_SHARE = 0.7f

        const val HIGHLIGHT_ALPHA = 0.5f

        const val ACTOR_RADIUS = 0.21f
        const val BODY_LIFT = 0.85f
        const val BODY_SQUASH = 1.05f
        const val RIM_OFFSET = 0.42f
        const val RIM_SPREAD = 0.78f
        const val SHADOW_SPREAD = 0.95f
        const val GROUND_SQUASH = 0.5f
        const val RING_SPREAD = 1.5f
        const val RING_WIDTH = 2.5f
        const val RING_FILL_ALPHA = 0.18f
        const val HALO_SPREAD = 2.2f
        const val FACING_REACH = 1.9f
        const val FACING_WIDTH = 0.45f

        const val PROP_SHADOW_SPREAD = 0.42f
        const val GLOW_LIFT = 0.6f
        const val GLOW_SPREAD = 1.6f

        const val TAU = 6.2831855f
        const val FALL_GAIN = 1.4f
        const val RISE_GAIN = 1.1f
        const val DRIFT_GAIN = 0.5f
        const val SWAY_WIDE = 0.06f
        const val SWAY_NARROW = 0.02f
        const val BLINK_RATE = 2.2f
        const val MIST_RADIUS = 1.8f
        const val LARGE_MOTE_RADIUS = 0.07f
        const val SMALL_MOTE_RADIUS = 0.045f
        const val MIST_ALPHA = 0.1f
        const val MOTE_ALPHA = 0.6f
        const val RAIN_LENGTH = 3.5f
    }
}
