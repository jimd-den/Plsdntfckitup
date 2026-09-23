package com.stratum.engine.scene

import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.art.ActorStyle
import com.stratum.core.domain.art.EffectKind
import com.stratum.core.domain.art.MoteKind
import com.stratum.core.domain.art.PartRole
import com.stratum.core.domain.art.PropCue
import com.stratum.core.domain.art.PropSilhouettes
import com.stratum.core.domain.art.PropStyle
import com.stratum.core.domain.art.SceneArtDirector
import com.stratum.core.domain.art.SceneLighting
import com.stratum.core.domain.art.Tint
import com.stratum.core.domain.art.WorldArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.world.World
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Something that moves, as the scene needs to know it. */
data class SceneActor(
    val x: Float,
    val y: Float,
    val z: Float,
    val presentation: ActorPresentation,
    val facingX: Float = 0f,
    val facingY: Float = 1f,
    /** A forged sprite to draw instead of the low-poly body, by texture key. */
    val spriteKey: String? = null,
    /**
     * True when the platform draws this actor's animated sprite itself, over
     * the scene. The scene then lays only its footing — shadow, rank ring,
     * halo — and no stand-in body, which would otherwise show behind the art.
     */
    val drawnElsewhere: Boolean = false,
)

/**
 * Everything one frame needs, in backend-neutral form.
 *
 * The contract between the scene and whatever draws it. A GPU and a software
 * rasteriser both take exactly this, which is what makes a screenshot from a
 * build machine evidence about the game rather than a mock-up of it.
 */
class SceneFrame(
    val camera: SceneCamera,
    val lighting: SceneLighting,
    /** The nearest few point lights. Backends support up to [MAX_LIGHTS]. */
    val lights: List<PointLight>,
    /** Sun's view-projection, for the shadow map. */
    val shadowViewProjection: FloatArray,
    val opaque: List<MeshBatch>,
    val cutout: MeshBatch,
    val decals: MeshBatch,
    val glows: MeshBatch,
) {
    companion object {
        const val MAX_LIGHTS = 8
    }
}

/**
 * Assembles a frame: terrain, props, actors, decals, light and weather.
 *
 * The art director answers every question about how anything looks; this class
 * only knows how to turn those answers into triangles. Terrain is meshed once
 * per world revision and reused, because remeshing a few thousand cubes sixty
 * times a second to redraw a world nobody changed is the definition of waste.
 */
class SceneBuilder(
    private val director: WorldArtDirector,
    private val textures: TextureLibrary,
    private val biomeAt: (Int, Int) -> BiomeDefinition? = { _, _ -> null },
    private val scene: SceneArtDirector = director as? SceneArtDirector
        ?: error("${director::class.simpleName} cannot describe a 3D scene"),
) {
    private val mesher = TerrainMesher(scene, textures, biomeAt)

    private var cachedKey: Long = Long.MIN_VALUE
    private var cachedTerrain: TerrainMesher.Result? = null

    private val cutout = MeshBuilder(MaterialKind.CUTOUT)
    private val decals = MeshBuilder(MaterialKind.DECAL)
    private val glows = MeshBuilder(MaterialKind.GLOW)
    private val actorMesh = MeshBuilder(MaterialKind.OPAQUE)

    // This frame's sun on the ground: which way shadows fall, how long they
    // are per unit of height, and how dark. Set at the start of build().
    private var shadowDirX = 0f
    private var shadowDirY = 1f
    private var shadowReach = 0.7f
    private var shadowOpacity = 0.5f

    /** Forgets the cached terrain, e.g. after the textures or the director change. */
    fun invalidate() {
        cachedKey = Long.MIN_VALUE
    }

    fun build(
        world: World,
        camera: SceneCamera,
        actors: List<SceneActor> = emptyList(),
        time: WorldTime = WorldTime(),
        /** Bumped by the engine on every block change; the terrain cache keys on it. */
        worldRevision: Int = 0,
        radius: Int = DEFAULT_RADIUS,
        /** Cells a pending build or erase covers, shown before it is committed. */
        ghosts: List<com.stratum.core.domain.world.BlockPos> = emptyList(),
        ghostsAffordable: Boolean = true,
        /** The cell the player is about to act on. */
        highlight: com.stratum.core.domain.world.BlockPos? = null,
        /** Combat theatre in progress; see [EffectTrack]. */
        effects: List<ActiveEffect> = emptyList(),
    ): SceneFrame {
        val cx = floor(camera.target.x).toInt()
        val cy = floor(camera.target.y).toInt()
        // Terrain is cached per region-quantised position and revision: moving
        // a few blocks does not remesh, walking into new ground does.
        val regionX = Math.floorDiv(cx, REGION_STEP)
        val regionY = Math.floorDiv(cy, REGION_STEP)
        val key = (regionX.toLong() shl 40) xor (regionY.toLong() shl 20) xor worldRevision.toLong()
        val terrain = cachedTerrain.takeIf { key == cachedKey } ?: mesher.mesh(
            world,
            regionX * REGION_STEP - radius, regionX * REGION_STEP + radius,
            regionY * REGION_STEP - radius, regionY * REGION_STEP + radius,
        ).also {
            cachedTerrain = it
            cachedKey = key
        }

        val biome = biomeAt(cx, cy)
        val styled = scene.lightingFor(biome?.id, time)
        // Fog is authored relative to the focus; backends measure from the eye.
        // The fog is also what hides the edge of the world. Terrain only exists
        // as far as the engine has loaded it, and past that the sky showed
        // through as a black staircase; fog is total before the meshed edge,
        // and the sky leans towards the fog colour, so the boundary dissolves.
        val edge = camera.distance + radius * EDGE_FOG_SHARE
        val lighting = styled.copy(
            fogFloor = camera.target.z - FOG_FLOOR_DEPTH,
            fogStart = minOf(styled.fogStart + camera.distance, edge - MIN_FOG_SPAN),
            fogEnd = minOf(styled.fogEnd + camera.distance, edge),
            skyTop = Tint.mix(styled.skyTop, styled.fogColor, SKY_TO_FOG),
            skyBottom = Tint.mix(styled.skyBottom, styled.fogColor, SKY_TO_FOG),
        ).let { lit ->
            // Fill from behind the lens, a little raised: every face this
            // camera can see gets some of it.
            val toEye = (camera.eye - camera.target).let { Vec3(it.x, it.y, 0f).normalized() }
            val fill = Vec3(toEye.x, toEye.y, FILL_LIFT).normalized()
            lit.copy(fillX = fill.x, fillY = fill.y, fillZ = fill.z)
        }

        cutout.clear(); decals.clear(); glows.clear(); actorMesh.clear()
        run {
            // Shadows fall away from the sun, longer the lower it is.
            val horizontal = sqrt(lighting.sunX * lighting.sunX + lighting.sunY * lighting.sunY).coerceAtLeast(1e-3f)
            shadowDirX = -lighting.sunX / horizontal
            shadowDirY = -lighting.sunY / horizontal
            shadowReach = (horizontal / lighting.sunZ.coerceAtLeast(0.1f)).coerceIn(MIN_SHADOW_REACH, MAX_SHADOW_REACH)
            shadowOpacity = SPRITE_SHADOW_OPACITY * lighting.shadowStrength
        }
        val eyeLevel = floor(camera.target.z).toInt()

        val forward = (camera.target - camera.eye).let { Vec3(it.x, it.y, 0f).normalized() }
        terrain.details.forEach(::litter)
        terrain.props.forEach { prop(it, camera, eyeLevel, occlusionFade(it, actors, forward)) }
        terrain.lights.forEach { light ->
            // A light you can see the source of. Point lights colour the ground;
            // the bloom is what tells the eye where the fire actually is.
            glow(camera, light.x, light.y, light.z + BLOOM_LIFT, BLOOM_RADIUS * (0.6f + light.strength), light.color, BLOOM_OPACITY)
        }
        actors.forEach { actor(it, camera, eyeLevel) }
        val palette = director.direction.palette
        ghosts.forEach { cell ->
            // A pending build glows where it will stand, in the hero's colour,
            // or the danger colour when there are not enough blocks for it.
            val tint = if (ghostsAffordable) palette.heroRim else palette.hostile
            glow(camera, cell.x + 0.5f, cell.y + 0.5f, cell.z + 0.5f, GHOST_RADIUS, tint, GHOST_OPACITY)
        }
        highlight?.let { cell ->
            decal(cell.x + 0.5f, cell.y + 0.5f, cell.z + 1f, HIGHLIGHT_RADIUS, palette.heroRim, HIGHLIGHT_OPACITY, Vertex.RING)
        }
        motes(camera, time, biome)
        val flashes = ArrayList<PointLight>()
        effects.forEach { effect(it, camera, flashes) }

        val hero = actors.firstOrNull { it.presentation.role == com.stratum.core.domain.art.ActorRole.PLAYER }
            ?.takeIf { lighting.heroLight > 0f }
            ?.let { PointLight(it.x, it.y, it.z + HERO_LIGHT_HEIGHT, lighting.sunColor, lighting.heroLight, lighting.heroLightRadius) }
        // A hit lights the ground around it for a moment — the cheapest way to
        // make an impact feel like it has weight. The brightest few win.
        val bursts = flashes.sortedByDescending { it.strength }.take(MAX_EFFECT_LIGHTS)
        val nearest = listOfNotNull(hero) + bursts + terrain.lights
            .sortedBy { abs(it.x - camera.target.x) + abs(it.y - camera.target.y) }
            .take(SceneFrame.MAX_LIGHTS - (if (hero != null) 1 else 0) - bursts.size)
            .map { it.copy(strength = it.strength * lighting.pointLightGain) }

        return SceneFrame(
            camera = camera,
            lighting = lighting,
            lights = nearest,
            shadowViewProjection = shadowMatrix(camera.target, lighting),
            opaque = listOfNotNull(terrain.mesh, actorMesh.takeUnless { it.isEmpty }?.build()),
            cutout = cutout.build(),
            decals = decals.build(),
            glows = glows.build(),
        )
    }

    /**
     * A prop, as a sprite that stands upright and turns to face the camera.
     *
     * This is the Hades and Diablo II answer to scenery and it is the right one
     * here too: painted 2D art standing in a lit 3D world reads as more
     * finished than a low-poly tree ever will, and it is exactly the kind of
     * asset an image model is good at producing.
     */
    /**
     * How see-through a prop should be, 1 for solid.
     *
     * A prop standing between the camera and an actor, close to them, is faded
     * — the thing every action RPG does to its scenery, because a player who
     * cannot see their own character behind a tree is a player who dies to
     * something they could not see either. Only props *in front* fade: one
     * behind an actor is scenery, and fading it would just make the world look
     * broken.
     */
    private fun occlusionFade(prop: PropInstance, actors: List<SceneActor>, forward: Vec3): Float {
        var fade = 1f
        val px = prop.x + 0.5f; val py = prop.y + 0.5f
        for (actor in actors) {
            val dx = actor.x - px; val dy = actor.y - py
            // Positive when the actor is further from the camera than the prop.
            val behind = dx * forward.x + dy * forward.y
            if (behind <= 0f) continue
            val side = abs(-dx * forward.y + dy * forward.x)
            if (side > FADE_WIDTH || behind > FADE_DEPTH) continue
            fade = minOf(fade, FADED_OPACITY)
        }
        return fade
    }

    private fun prop(prop: PropInstance, camera: SceneCamera, eyeLevel: Int, opacity: Float = 1f) {
        val variant = hash(prop.x, prop.y)
        val style = director.propStyleFor(
            PropCue(prop.block, prop.biomeId, variant, depthBelowEye = (eyeLevel - prop.z).coerceAtLeast(0)),
        ) ?: return
        val baseX = prop.x + 0.5f
        val baseY = prop.y + 0.5f
        val baseZ = prop.z.toFloat()

        decal(baseX, baseY, baseZ, PROP_SHADOW_RADIUS * style.scale * CONTACT_WITH_SHADOW, director.direction.palette.ink, style.contactShadow, Vertex.DISC)

        // One of the forged individuals, picked by place: neighbours differ,
        // and the same tree is the same tree every time you walk past it.
        val paintings = textures.variantsOf("prop:${prop.block.id}")
        if (paintings.isNotEmpty()) {
            val sprite = paintings[(variant ushr 3) % paintings.size]
            val texture = textures.textureAt(sprite)!!
            val size = 1f + (((variant ushr 11) and 0xFF) / 255f - 0.5f) * PROP_SIZE_SPREAD
            val height = SPRITE_HEIGHT * style.scale * size
            val width = height * texture.width / texture.height
            val mirrored = (variant ushr 19) and 1 == 1
            spriteShadow(baseX, baseY, baseZ, width, height, sprite, mirrored, opacity)
            billboard(
                camera, baseX, baseY, baseZ, width, height, style.fill or Tint.OPAQUE, sprite.toFloat(), opacity,
                mirrored = mirrored,
            )
        } else {
            silhouette(camera, baseX, baseY, baseZ, style, opacity)
        }
        if (Tint.alpha(style.glow) > 0) {
            glow(camera, baseX, baseY, baseZ + style.scale * 0.8f, style.scale * 1.6f, style.glow, Tint.alpha(style.glow) / 255f)
        }
    }

    /**
     * A textured quad standing on its base and facing the camera squarely.
     *
     * Squarely, not merely upright. An upright sprite seen from fifty degrees
     * up is foreshortened to about sixty per cent of its height, which turned
     * every tree into a mushroom. Leaning the sprite back to face the lens is
     * what Diablo and Hades both do — their scenery is drawn for the camera,
     * not for the world — and the base stays planted where it stands.
     */
    private fun billboard(
        camera: SceneCamera, x: Float, y: Float, z: Float, width: Float, height: Float,
        tint: Long, layer: Float, opacity: Float = 1f, mirrored: Boolean = false, emissive: Float = 0f,
    ) {
        val u0 = if (mirrored) 1f else 0f
        val u1 = 1f - u0
        val right = camera.right
        val up = camera.up
        val n = billboardNormal(camera)
        val hw = width / 2f
        val tx = up.x * height; val ty = up.y * height; val tz = up.z * height
        // For cut-outs the occlusion slot carries opacity: below one, the
        // backends drop a dithered share of the pixels (screen-door fade),
        // which needs no sorting and keeps depth writes intact.
        val a = cutout.vertex(x - right.x * hw, y - right.y * hw, z, n.x, n.y, n.z, white(tint), opacity, u0, 1f, layer, emissive)
        val b = cutout.vertex(x + right.x * hw, y + right.y * hw, z, n.x, n.y, n.z, white(tint), opacity, u1, 1f, layer, emissive)
        val c = cutout.vertex(x + right.x * hw + tx, y + right.y * hw + ty, z + tz, n.x, n.y, n.z, white(tint), opacity, u1, 0f, layer, emissive)
        val d = cutout.vertex(x - right.x * hw + tx, y - right.y * hw + ty, z + tz, n.x, n.y, n.z, white(tint), opacity, u0, 0f, layer, emissive)
        cutout.quad(a, b, c, d)
    }

    /**
     * The fallback when no sprite has been forged: the vector silhouette, stood
     * up in the world. Every prop always draws something.
     */
    private fun silhouette(camera: SceneCamera, x: Float, y: Float, z: Float, style: PropStyle, opacity: Float = 1f) {
        val right = camera.right
        val up = camera.up
        val n = billboardNormal(camera)
        val unit = style.scale * SILHOUETTE_UNIT
        PropSilhouettes.parts(style.silhouette, style.variant).forEach { part ->
            val color = when (part.role) {
                PartRole.SUPPORT -> style.support
                PartRole.BODY -> style.fill
                PartRole.SHADE -> style.shade
                PartRole.ACCENT -> if (Tint.alpha(style.glow) > 0) style.glow else style.fill
            }
            val emissive = if (part.role == PartRole.ACCENT && Tint.alpha(style.glow) > 0) 0.8f else 0f
            val points = part.points
            val count = points.size / 2
            // Pushed very slightly towards the camera per part, so parts that
            // overlap in the silhouette keep their drawing order in depth.
            val lift = when (part.role) { PartRole.SUPPORT -> 0f; PartRole.BODY -> 0.01f; PartRole.SHADE -> 0.02f; PartRole.ACCENT -> 0.03f }
            val first = cutout.vertexCount
            for (i in 0 until count) {
                val px = points[i * 2] * unit
                val pz = -points[i * 2 + 1] * unit
                cutout.vertex(
                    x + right.x * px + up.x * pz + n.x * lift,
                    y + right.y * px + up.y * pz + n.y * lift,
                    z + up.z * pz,
                    n.x, n.y, n.z, color, opacity, 0f, 0f, Vertex.FLAT, emissive,
                )
            }
            for (i in 1 until count - 1) cutout.triangle(first, first + i, first + i + 1)
        }
    }

    /**
     * A body, turned on a lathe.
     *
     * Low-poly and untextured on purpose: this is the stand-in until a forged
     * sprite exists, and its job is to read as a figure — hooded head, broad
     * shoulders, a weapon pointing where it is going — and to catch the rim
     * light, which is what separates it from the ground at a glance.
     */
    private fun actor(actor: SceneActor, camera: SceneCamera, eyeLevel: Int) {
        val style = director.actorStyleFor(
            actor.presentation.copy(depthBelowEye = (eyeLevel - floor(actor.z).toInt()).coerceAtLeast(0)),
        )
        val ink = director.direction.palette.ink
        decal(actor.x, actor.y, actor.z, SHADOW_RADIUS * style.scale, ink, style.contactShadow, Vertex.DISC)
        if (Tint.alpha(style.groundRing) > 0) {
            decal(actor.x, actor.y, actor.z, RING_RADIUS * style.scale, style.groundRing, Tint.alpha(style.groundRing) / 255f, Vertex.RING)
        }
        if (Tint.alpha(style.halo) > 0) {
            decal(actor.x, actor.y, actor.z, HALO_RADIUS * style.scale, style.halo, Tint.alpha(style.halo) / 255f + 0.2f, Vertex.DISC)
        }

        if (actor.drawnElsewhere) return
        val sprite = textures.layerOf(actor.spriteKey)
        if (sprite >= 0) {
            val texture = textures.textureAt(sprite)!!
            // Half the contract's size cheat. The cheat exists to lift a
            // one-block marker off a field of blocks; a painted character does
            // not need it, and at full strength stood as tall as the trees.
            val height = ACTOR_SPRITE_HEIGHT * (1f + (style.scale - 1f) * 0.5f)
            // Forged characters are drawn facing the lower right; one heading
            // the other way on screen is the same art mirrored.
            val screenwise = actor.facingX * camera.right.x + actor.facingY * camera.right.y
            val width = height * texture.width / texture.height
            spriteShadow(actor.x, actor.y, actor.z, width, height, sprite, screenwise < -0.01f)
            billboard(
                camera, actor.x, actor.y, actor.z, height * texture.width / texture.height, height,
                Tint.OPAQUE or 0xFFFFFF, sprite.toFloat(), mirrored = screenwise < -0.01f,
                // Struck, the painted body blanches for a moment, like Hades'.
                emissive = actor.presentation.flash.coerceIn(0f, 1f) * SPRITE_FLASH,
            )
            return
        }

        val body = if (Tint.alpha(style.flashTint) > 0) Tint.mix(style.body, style.flashTint or Tint.OPAQUE, Tint.alpha(style.flashTint) / 255f) else style.body
        val r = BODY_RADIUS * style.scale
        val h = BODY_HEIGHT * style.scale
        lathe(actorMesh, actor.x, actor.y, actor.z, BODY_PROFILE, r, h, body)

        // A blade held out in front, pointing the way the body faces.
        val len = sqrt(actor.facingX * actor.facingX + actor.facingY * actor.facingY)
        if (len > 1e-3f) {
            val fx = actor.facingX / len
            val fy = actor.facingY / len
            orientedBox(
                actorMesh,
                actor.x + fx * r * 1.3f, actor.y + fy * r * 1.3f, actor.z + h * 0.48f,
                fx, fy, r * 1.4f, r * 0.14f, r * 0.14f,
                Tint.mix(style.rim, body, 0.3f),
            )
        }
    }

    private fun lathe(out: MeshBuilder, x: Float, y: Float, z: Float, profile: FloatArray, radius: Float, height: Float, color: Long) {
        val rings = profile.size / 2
        val first = out.vertexCount
        for (ring in 0 until rings) {
            val pr = profile[ring * 2] * radius
            val pz = profile[ring * 2 + 1] * height
            // Normal from the slope between neighbouring rings.
            val prev = (ring - 1).coerceAtLeast(0)
            val next = (ring + 1).coerceAtMost(rings - 1)
            val dr = profile[next * 2] * radius - profile[prev * 2] * radius
            val dz = profile[next * 2 + 1] * height - profile[prev * 2 + 1] * height
            val nlen = sqrt(dr * dr + dz * dz).coerceAtLeast(1e-5f)
            val nOut = dz / nlen
            val nUp = -dr / nlen
            for (seg in 0..LATHE_SEGMENTS) {
                val a = seg.toFloat() / LATHE_SEGMENTS * TAU
                val ca = cos(a); val sa = sin(a)
                out.vertex(x + ca * pr, y + sa * pr, z + pz, ca * nOut, sa * nOut, nUp, color, 1f, 0f, 0f, Vertex.ACTOR)
            }
        }
        val stride = LATHE_SEGMENTS + 1
        for (ring in 0 until rings - 1) {
            for (seg in 0 until LATHE_SEGMENTS) {
                val a = first + ring * stride + seg
                val b = a + 1
                val c = a + stride + 1
                val d = a + stride
                out.quad(a, b, c, d)
            }
        }
    }

    private fun orientedBox(out: MeshBuilder, cx: Float, cy: Float, cz: Float, fx: Float, fy: Float, length: Float, width: Float, height: Float, color: Long) {
        val sx = -fy; val sy = fx
        fun corner(l: Float, w: Float, h: Float) = floatArrayOf(cx + fx * l + sx * w, cy + fy * l + sy * w, cz + h)
        val hl = length / 2; val hw = width / 2; val hh = height / 2
        val faces = listOf(
            Triple(floatArrayOf(0f, 0f, 1f), listOf(corner(-hl, -hw, hh), corner(hl, -hw, hh), corner(hl, hw, hh), corner(-hl, hw, hh)), 0),
            Triple(floatArrayOf(sx, sy, 0f), listOf(corner(-hl, hw, -hh), corner(hl, hw, -hh), corner(hl, hw, hh), corner(-hl, hw, hh)), 0),
            Triple(floatArrayOf(-sx, -sy, 0f), listOf(corner(-hl, -hw, -hh), corner(hl, -hw, -hh), corner(hl, -hw, hh), corner(-hl, -hw, hh)), 0),
            Triple(floatArrayOf(fx, fy, 0f), listOf(corner(hl, -hw, -hh), corner(hl, hw, -hh), corner(hl, hw, hh), corner(hl, -hw, hh)), 0),
        )
        faces.forEach { (n, c, _) ->
            val idx = c.map { p -> out.vertex(p[0], p[1], p[2], n[0], n[1], n[2], color, 1f, 0f, 0f, Vertex.ACTOR) }
            out.quad(idx[0], idx[1], idx[2], idx[3])
        }
    }

    /** A piece of ground litter, lying flat and turned its own way. */
    private fun litter(detail: GroundDetail) {
        val texture = textures.textureAt(detail.layer) ?: return
        val hw = detail.size / 2f
        val hh = hw * texture.height / texture.width
        val c = cos(detail.angle); val sn = sin(detail.angle)
        val z = detail.z + DETAIL_LIFT
        fun corner(u: Float, v: Float): Int {
            val lx = (u * 2f - 1f) * hw; val ly = (1f - v * 2f) * hh
            return cutout.vertex(
                detail.x + lx * c - ly * sn, detail.y + lx * sn + ly * c, z,
                0f, 0f, 1f, LITTER_TINT, 1f, u, v, detail.layer.toFloat(),
            )
        }
        cutout.quad(corner(0f, 1f), corner(1f, 1f), corner(1f, 0f), corner(0f, 0f))
    }

    /**
     * One piece of combat theatre, as decals and glows.
     *
     * Everything here is additive light or a soft decal: no textures, no
     * sorting, the same on both backends, and nothing a style cannot recolour.
     */
    private fun effect(active: ActiveEffect, camera: SceneCamera, lights: MutableList<PointLight>) {
        val fx = active.effect
        val p = active.progress
        val fade = 1f - p
        val color = fx.color or Tint.OPAQUE
        val x = active.x; val y = active.y; val z = active.z
        when (fx.kind) {
            EffectKind.IMPACT_RING -> {
                val grow = 1f - fade * fade
                val r = fx.radius * (0.25f + 0.75f * grow)
                decal(x, y, z, r, color, fade * RING_EFFECT_OPACITY, Vertex.RING)
                decal(x, y, z, fx.radius * 0.55f, color, fade * fade * SCORCH_OPACITY, Vertex.DISC)
                // The ring itself burns: beads of light around the rim, so it
                // reads as energy against any ground, dark or bright.
                val beads = (RING_BEADS * (0.6f + fx.radius * 0.3f)).toInt()
                for (i in 0 until beads) {
                    val a = i * TAU / beads
                    glow(camera, x + cos(a) * r * 0.82f, y + sin(a) * r * 0.82f, z + 0.12f, RING_BEAD_SIZE * (0.6f + 0.4f * r), color, fade * RING_BEAD_OPACITY)
                }
                lights += PointLight(x, y, z + 0.6f, color, fade * IMPACT_LIGHT, 2f + fx.radius * 2f)
            }
            EffectKind.HIT_FLASH -> {
                val burst = fade * fade
                glow(camera, x, y, z + BODY_CENTRE, (0.55f + 0.7f * p) * (0.8f + fx.intensity * 0.5f), color, burst * fx.intensity * FLASH_OPACITY)
                // Sparks thrown out across the screen from the point of impact.
                val spin = unit(active.seed, 9) * TAU
                for (i in 0 until FLASH_SPARKS) {
                    val a = spin + i * TAU / FLASH_SPARKS
                    val reach = (0.3f + 0.9f * (1f - fade * fade)) * (0.7f + 0.6f * unit(active.seed * 17 + i, 3))
                    val sx = cos(a) * reach; val sz = sin(a) * reach
                    glow(
                        camera,
                        x + camera.right.x * sx + camera.up.x * sz,
                        y + camera.right.y * sx + camera.up.y * sz,
                        z + BODY_CENTRE + camera.up.z * sz,
                        SPARK_SIZE * (0.6f + fade), color, burst * SPARK_OPACITY,
                    )
                    glow(
                        camera,
                        x + camera.right.x * sx + camera.up.x * sz,
                        y + camera.right.y * sx + camera.up.y * sz,
                        z + BODY_CENTRE + camera.up.z * sz,
                        SPARK_SIZE * 0.45f, HOT_CORE, burst * SPARK_OPACITY,
                    )
                }
                lights += PointLight(x, y, z + 1f, color, burst * fx.intensity * FLASH_LIGHT, 4.5f)
            }
            EffectKind.DEBRIS -> {
                val count = (DEBRIS_MIN + DEBRIS_EXTRA * fx.intensity).toInt()
                for (i in 0 until count) {
                    val salt = active.seed * 131 + i
                    val a = unit(salt, 1) * TAU
                    val out = fx.radius * (0.4f + 0.9f * unit(salt, 2)) * (1f - fade * fade)
                    // A thrown arc that lands where it ends.
                    val lift = DEBRIS_HEIGHT * (0.5f + unit(salt, 3))
                    val dz = 0.8f * fade + lift * 4f * p * fade
                    val size = DEBRIS_SIZE * (0.7f + 0.6f * unit(salt, 4))
                    glow(camera, x + cos(a) * out, y + sin(a) * out, z + dz, size, color, sqrt(fade) * DEBRIS_OPACITY)
                    glow(camera, x + cos(a) * out, y + sin(a) * out, z + dz, size * 0.4f, HOT_CORE, fade * DEBRIS_OPACITY)
                }
            }
            EffectKind.BEAM -> {
                val envelope = ShadingModel.smoothstep(0f, 0.12f, p) * (1f - ShadingModel.smoothstep(0.65f, 1f, p))
                for (k in 0 until BEAM_SEGMENTS) {
                    val share = k / BEAM_SEGMENTS.toFloat()
                    val pulse = 0.85f + 0.15f * sin(active.age * 11f + k * 0.9f)
                    glow(
                        camera, x, y, z + 0.3f + k * BEAM_STEP,
                        fx.radius * (1.1f - share * 0.7f) * pulse, color,
                        envelope * fx.intensity * (1f - share) * BEAM_OPACITY,
                    )
                }
                decal(x, y, z, fx.radius * 1.6f, color, envelope * 0.7f, Vertex.DISC)
                lights += PointLight(x, y, z + 1.5f, color, envelope * fx.intensity * BEAM_LIGHT, 5f)
            }
            EffectKind.WEAPON_ARC -> {
                // A slash sweeping across the front of the body, trailing light.
                val facing = atan2(active.facingY, active.facingX)
                val head = -ARC_HALF + 2f * ARC_HALF * (1f - fade * fade * fade)
                for (k in 0 until ARC_SEGMENTS) {
                    val share = k / ARC_SEGMENTS.toFloat()
                    val a = head - share * ARC_TRAIL
                    if (a < -ARC_HALF) break
                    val angle = facing + a
                    val lean = sin(a) * 0.35f
                    val ax = x + cos(angle) * fx.radius; val ay = y + sin(angle) * fx.radius; val az = z + BODY_CENTRE + lean
                    val strength = fade * fx.intensity * (1f - share)
                    glow(camera, ax, ay, az, ARC_SIZE * (1f - share * 0.6f), color, strength * ARC_OPACITY)
                    // A white-hot edge on the leading part of the blade's path.
                    if (share < 0.5f) glow(camera, ax, ay, az, ARC_SIZE * 0.4f, HOT_CORE, strength * ARC_OPACITY)
                }
            }
            EffectKind.AFTERIMAGE -> {
                val len = sqrt(active.facingX * active.facingX + active.facingY * active.facingY).coerceAtLeast(1e-3f)
                val bx = -active.facingX / len; val by = -active.facingY / len
                for (k in 1..AFTERIMAGES) {
                    val back = k * AFTERIMAGE_STEP * (0.5f + p)
                    val o = fade * fx.intensity * (1f - k / (AFTERIMAGES + 1f)) * AFTERIMAGE_OPACITY
                    glow(camera, x + bx * back, y + by * back, z + 0.5f, 0.45f, color, o)
                    glow(camera, x + bx * back, y + by * back, z + 1.15f, 0.4f, color, o)
                }
            }
            EffectKind.GROUND_DECAL -> {
                val envelope = ShadingModel.smoothstep(0f, 0.15f, p) * fade
                val pulse = 0.92f + 0.08f * sin(active.age * 9f)
                decal(x, y, z, fx.radius * pulse, color, envelope * 0.75f, Vertex.DISC)
                decal(x, y, z, fx.radius * 1.15f * pulse, color, envelope, Vertex.RING)
                lights += PointLight(x, y, z + 0.8f, color, envelope * GROUND_LIGHT, 3f + fx.radius)
            }
            EffectKind.SCREEN_SHAKE, EffectKind.NUMBER -> Unit
        }
    }

    /**
     * The shadow a sprite casts: its own silhouette laid on the ground and
     * stretched away from the sun.
     *
     * The 2.5D answer, and the one Diablo II and Hades use. A camera-facing
     * card seen from the sun casts a sliver or a slab — in the preview it was
     * literally a rectangle beside every tree — while this is a tree-shaped
     * shadow that points the way the light says it should, costs one quad, and
     * needs nothing from the shadow map. It darkens more at the foot than at
     * the tip, as a real shadow's contact edge does.
     */
    private fun spriteShadow(
        x: Float, y: Float, z: Float, width: Float, height: Float, layer: Int, mirrored: Boolean, opacity: Float = 1f,
    ) {
        val strength = shadowOpacity * (0.4f + 0.6f * opacity)
        if (strength <= 0f) return
        val length = height * shadowReach * SHADOW_SQUASH
        val px = -shadowDirY * width / 2f; val py = shadowDirX * width / 2f
        val tipX = x + shadowDirX * length; val tipY = y + shadowDirY * length
        val lift = z + DECAL_LIFT * 0.5f
        val u0 = if (mirrored) 1f else 0f; val u1 = 1f - u0
        val ink = director.direction.palette.ink
        val l = layer.toFloat()
        val a = decals.vertex(x - px, y - py, lift, 0f, 0f, 1f, ink, strength, u0, 1f, Vertex.SPRITE_SHADOW, 0f, l)
        val b = decals.vertex(x + px, y + py, lift, 0f, 0f, 1f, ink, strength, u1, 1f, Vertex.SPRITE_SHADOW, 0f, l)
        val c = decals.vertex(tipX + px, tipY + py, lift, 0f, 0f, 1f, ink, strength * SHADOW_TIP, u1, 0f, Vertex.SPRITE_SHADOW, 0f, l)
        val d = decals.vertex(tipX - px, tipY - py, lift, 0f, 0f, 1f, ink, strength * SHADOW_TIP, u0, 0f, Vertex.SPRITE_SHADOW, 0f, l)
        decals.quad(a, b, c, d)
    }

    /** A soft shape lying on the ground: shadow, ring or halo. */
    private fun decal(x: Float, y: Float, z: Float, radius: Float, color: Long, opacity: Float, pattern: Float) {
        if (opacity <= 0f) return
        val lift = z + DECAL_LIFT
        val a = decals.vertex(x - radius, y - radius, lift, 0f, 0f, 1f, color, opacity, -1f, -1f, pattern)
        val b = decals.vertex(x + radius, y - radius, lift, 0f, 0f, 1f, color, opacity, 1f, -1f, pattern)
        val c = decals.vertex(x + radius, y + radius, lift, 0f, 0f, 1f, color, opacity, 1f, 1f, pattern)
        val d = decals.vertex(x - radius, y + radius, lift, 0f, 0f, 1f, color, opacity, -1f, 1f, pattern)
        decals.quad(a, b, c, d)
    }

    /** A soft additive disc facing the camera. */
    private fun glow(camera: SceneCamera, x: Float, y: Float, z: Float, radius: Float, color: Long, opacity: Float) {
        val r = camera.right * radius
        val u = camera.up * radius
        val a = glows.vertex(x - r.x - u.x, y - r.y - u.y, z - r.z - u.z, 0f, 0f, 1f, color, opacity, -1f, -1f, Vertex.DISC)
        val b = glows.vertex(x + r.x - u.x, y + r.y - u.y, z + r.z - u.z, 0f, 0f, 1f, color, opacity, 1f, -1f, Vertex.DISC)
        val c = glows.vertex(x + r.x + u.x, y + r.y + u.y, z + r.z + u.z, 0f, 0f, 1f, color, opacity, 1f, 1f, Vertex.DISC)
        val d = glows.vertex(x - r.x + u.x, y - r.y + u.y, z - r.z + u.z, 0f, 0f, 1f, color, opacity, -1f, 1f, Vertex.DISC)
        glows.quad(a, b, c, d)
    }

    /**
     * Weather, as a stateless field.
     *
     * Positions are a function of the mote's index and the clock, wrapped into a
     * box around the camera target. No particles to spawn, age or keep in sync,
     * and the same frame on every device.
     */
    private fun motes(camera: SceneCamera, time: WorldTime, biome: BiomeDefinition?) {
        val atmosphere = director.atmosphereFor(biome, time)
        if (atmosphere.moteKind == MoteKind.NONE || atmosphere.moteDensity <= 0) return
        val count = atmosphere.moteDensity * MOTE_MULTIPLIER
        val t = time.elapsedSeconds
        val fall = when (atmosphere.moteKind) {
            MoteKind.SNOW, MoteKind.RAIN, MoteKind.ASH, MoteKind.LEAVES, MoteKind.PETALS -> -1f
            MoteKind.EMBERS, MoteKind.SPARKS -> 1f
            else -> 0.15f
        }
        for (i in 0 until count) {
            val hx = unit(i, 1); val hy = unit(i, 2); val hz = unit(i, 3); val speed = 0.5f + unit(i, 4)
            val x = camera.target.x + (wrap(hx + sin(t * 0.3f * speed + i) * 0.02f) - 0.5f) * MOTE_BOX
            val y = camera.target.y + (wrap(hy + cos(t * 0.27f * speed + i) * 0.02f) - 0.5f) * MOTE_BOX
            val z = camera.target.z + wrap(hz + t * fall * atmosphere.moteDrift * speed * 0.08f) * MOTE_HEIGHT
            val blink = if (atmosphere.moteKind == MoteKind.FIREFLIES || atmosphere.moteKind == MoteKind.EMBERS) {
                0.35f + 0.65f * (0.5f + 0.5f * sin(t * 2.3f * speed + i * 1.7f))
            } else {
                0.75f
            }
            val size = if (atmosphere.moteKind == MoteKind.MIST) 2.2f else 0.12f + 0.08f * unit(i, 5)
            val opacity = if (atmosphere.moteKind == MoteKind.MIST) 0.08f else blink
            glow(camera, x, y, z, size, atmosphere.moteColor, opacity)
        }
    }

    /**
     * The sun's camera: orthographic, looking along the light, framing the
     * area around the target. Everything inside it casts; everything outside
     * is simply lit, which past the fog is invisible anyway.
     */
    private fun shadowMatrix(target: Vec3, lighting: SceneLighting): FloatArray {
        val sun = Vec3(lighting.sunX, lighting.sunY, lighting.sunZ).normalized()
        val eye = target + sun * SHADOW_DISTANCE
        val up = if (abs(sun.z) > 0.95f) Vec3(0f, 1f, 0f) else Vec3.UP
        val view = Mat4.lookAt(eye, target, up)
        val proj = Mat4.orthographic(-SHADOW_EXTENT, SHADOW_EXTENT, -SHADOW_EXTENT, SHADOW_EXTENT, 1f, SHADOW_DISTANCE * 2f)
        return Mat4.multiply(proj, view)
    }

    /** Sprites face the camera but are lit mostly as if they faced up. */
    private fun billboardNormal(camera: SceneCamera): Vec3 {
        val toCamera = (camera.eye - camera.target).let { Vec3(it.x, it.y, 0f).normalized() }
        return (toCamera * 0.55f + Vec3.UP * 0.85f).normalized()
    }

    /** Textured sprites are tinted by white, so their own colours come through. */
    private fun white(@Suppress("UNUSED_PARAMETER") tint: Long): Long = 0xFFFFFFFF

    private fun hash(x: Int, y: Int): Int {
        var h = x * 73856093 + y * 19349663
        h = (h xor (h ushr 15)) * -0x7a143595
        h = (h xor (h ushr 13)) * -0x3d4d51cb
        return (h xor (h ushr 16)) and 0x7FFFFFFF
    }

    private fun unit(i: Int, salt: Int): Float = (hash(i, salt * 7919) and 0xFFFF) / 65535f

    private fun wrap(v: Float): Float = v - floor(v)

    companion object {
        /** Blocks meshed around the camera target in each direction. */
        const val DEFAULT_RADIUS = 56
        const val REGION_STEP = 6
        const val FOG_FLOOR_DEPTH = 4f
        /** Share of the meshed radius past the focus where fog becomes total. */
        const val EDGE_FOG_SHARE = 0.8f
        const val MIN_FOG_SPAN = 8f
        const val SKY_TO_FOG = 0.65f
        const val FILL_LIFT = 0.45f

        const val SPRITE_HEIGHT = 2.6f
        const val SPRITE_SHADOW_OPACITY = 0.75f
        /** Shadow length per unit of sprite height, at the lowest and highest sun. */
        const val MIN_SHADOW_REACH = 0.35f
        const val MAX_SHADOW_REACH = 1.4f
        /** Painted sprites already lean back; their shadows are drawn a little shorter to match. */
        const val SHADOW_SQUASH = 0.8f
        /** How much of the foot's darkness reaches the shadow's tip. */
        const val SHADOW_TIP = 0.55f
        /** The round contact shadow shrinks when a cast shadow is there as well. */
        const val CONTACT_WITH_SHADOW = 0.7f
        /** How much forged props vary in size, as a share either way. */
        const val PROP_SIZE_SPREAD = 0.3f
        const val DETAIL_LIFT = 0.03f
        /** Litter is darkened a little, so it sits in the ground rather than on it. */
        const val LITTER_TINT = 0xFFB4B4AA
        const val SPRITE_FLASH = 0.9f

        const val MAX_EFFECT_LIGHTS = 3
        const val BODY_CENTRE = 0.9f
        const val RING_EFFECT_OPACITY = 1f
        const val RING_BEADS = 20
        const val RING_BEAD_SIZE = 0.3f
        const val RING_BEAD_OPACITY = 1.6f
        const val SPARK_OPACITY = 2.6f
        const val DEBRIS_OPACITY = 2.2f
        /** The white-hot centre of sparks and blades. */
        const val HOT_CORE = 0xFFFFF4E0
        const val SCORCH_OPACITY = 0.45f
        const val IMPACT_LIGHT = 1.4f
        const val FLASH_OPACITY = 2.4f
        const val FLASH_SPARKS = 7
        const val SPARK_SIZE = 0.26f
        const val FLASH_LIGHT = 2.2f
        const val DEBRIS_MIN = 6
        const val DEBRIS_EXTRA = 12
        const val DEBRIS_HEIGHT = 0.9f
        const val DEBRIS_SIZE = 0.22f
        const val BEAM_SEGMENTS = 12
        const val BEAM_STEP = 0.5f
        const val BEAM_OPACITY = 1.1f
        const val BEAM_LIGHT = 1.6f
        const val ARC_HALF = 1.3f
        const val ARC_TRAIL = 1.4f
        const val ARC_SEGMENTS = 14
        const val ARC_SIZE = 0.5f
        const val ARC_OPACITY = 2.2f
        const val AFTERIMAGES = 4
        const val AFTERIMAGE_STEP = 0.35f
        const val AFTERIMAGE_OPACITY = 0.45f
        const val GROUND_LIGHT = 1f

        /** How close in front of an actor a prop must be to fade, across and along the view. */
        const val FADE_WIDTH = 1.6f
        const val FADE_DEPTH = 4.5f
        const val FADED_OPACITY = 0.22f
        const val ACTOR_SPRITE_HEIGHT = 1.7f
        const val SILHOUETTE_UNIT = 1.25f
        const val PROP_SHADOW_RADIUS = 0.55f
        const val SHADOW_RADIUS = 0.42f
        const val RING_RADIUS = 0.62f
        const val HALO_RADIUS = 0.8f
        const val DECAL_LIFT = 0.02f
        const val BLOOM_RADIUS = 1.3f
        const val BLOOM_OPACITY = 0.32f
        const val BLOOM_LIFT = 0.9f
        const val HERO_LIGHT_HEIGHT = 1.8f
        const val GHOST_RADIUS = 0.62f
        const val GHOST_OPACITY = 0.45f
        const val HIGHLIGHT_RADIUS = 0.7f
        const val HIGHLIGHT_OPACITY = 0.9f

        const val BODY_RADIUS = 0.26f
        const val BODY_HEIGHT = 1.25f
        const val LATHE_SEGMENTS = 10
        const val TAU = 6.2831855f

        /** Radius, height pairs from foot to crown: robe, shoulders, neck, hooded head. */
        val BODY_PROFILE = floatArrayOf(
            0f, 0f,
            0.95f, 0f,
            1f, 0.08f,
            0.82f, 0.45f,
            1.05f, 0.6f,
            0.9f, 0.66f,
            0.42f, 0.7f,
            0.62f, 0.78f,
            0.66f, 0.88f,
            0.4f, 0.98f,
            0f, 1.02f,
        )

        const val MOTE_MULTIPLIER = 2
        const val MOTE_BOX = 34f
        const val MOTE_HEIGHT = 6f

        const val SHADOW_DISTANCE = 45f
        const val SHADOW_EXTENT = 30f
    }
}
