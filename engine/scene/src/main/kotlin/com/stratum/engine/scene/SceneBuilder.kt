package com.stratum.engine.scene

import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.attack.AttackLook
import com.stratum.core.domain.attack.AttackSketch
import com.stratum.core.domain.attack.DeliveryKind
import com.stratum.core.domain.attack.SketchMark
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
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.World
import com.stratum.engine.scene.quality.QualityTier
import com.stratum.engine.scene.quality.RenderSettings
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
/**
 * The see-through cylinder around the player: everything in front of them
 * and above their feet within [radius] of the line from the camera is cut
 * away (dithered at the rim), so a hill, a roof or a wall never covers the
 * hero. See [ShadingModel.revealCut].
 */
data class Reveal(val x: Float, val y: Float, val z: Float, val radius: Float) {
    companion object {
        /** Blocks. Wide enough for the hero and what they are fighting next to them. */
        const val DEFAULT_RADIUS = 2.6f
        const val FEATHER = 0.9f
        /** Surfaces this far above the feet are kept: the floor underfoot, the kerb beside it. */
        const val FLOOR_CLEARANCE = 0.3f
        /** The line runs to the body's middle, not the feet. */
        const val BODY_CENTRE = 1.0f
        /** How close in front of the player's centre the cut stops, so the wall behind them stays. */
        const val BEHIND_MARGIN = 0.6f
    }
}

class SceneFrame(
    val camera: SceneCamera,
    val lighting: SceneLighting,
    /** The nearest few point lights. Backends support up to [MAX_LIGHTS]. */
    val lights: List<PointLight>,
    /** Sun's view-projection, for the shadow map. */
    val shadowViewProjection: FloatArray,
    /**
     * Terrain, one batch per chunk. Each batch is the same object from frame
     * to frame until its chunk changes, so a backend can keep it on the GPU
     * and upload only what is new.
     */
    val terrain: List<MeshBatch>,
    /** Stand-in bodies for actors with no art of their own; rebuilt every frame. */
    val actors: MeshBatch?,
    val cutout: MeshBatch,
    val decals: MeshBatch,
    val glows: MeshBatch,
    /**
     * Props drawn as 3D models, one batch for the view. The same object from
     * frame to frame until the terrain changes, like [terrain], so a backend
     * can keep it on the GPU.
     */
    val models: List<MeshBatch> = emptyList(),
    /**
     * Every terrain batch still in the meshed square, drawn or not. [terrain]
     * holds only the chunks the camera can see; this is what a backend keeps
     * on the GPU, so a chunk that slides out of view and back is not uploaded
     * again.
     */
    val residentTerrain: List<MeshBatch> = terrain,
    /** Where the world is cut open so the player is never hidden; null cuts nothing. Applies to [terrain] and [models], never to actors. */
    val reveal: Reveal? = null,
    /** Which ingredients of the handcrafted finish to draw; see [DioramaLook]. */
    val look: com.stratum.engine.scene.quality.DioramaLook = com.stratum.engine.scene.quality.DioramaLook.OFF,
    /** 0 at noon, 1 at midnight, eased: how far lit windows and lamps are into their night glow. */
    val night: Float = 0f,
    /** Surfels near the focus, per chunk, as thinned for this frame; see [SurfelLod]. Drawn opaque, after the terrain. */
    val surfels: List<SurfelDraw> = emptyList(),
    /** Every chunk's surfels in the meshed square, drawn or not, for a backend to keep on the GPU like [residentTerrain]. */
    val residentSurfels: List<SurfelBatch> = emptyList(),
    /**
     * The micro-detail terrain as voxel splats, per chunk layer, that the
     * view (widened by the shadow reach) can see; see [SplatMode]. Drawn
     * opaque with the terrain, and into the shadow map.
     */
    val splats: List<SplatBatch> = emptyList(),
    /** Every splat batch in the meshed square, for a backend to keep on the GPU like [residentTerrain]. */
    val residentSplats: List<SplatBatch> = emptyList(),
    /** How [splats] are drawn: [SplatMode.FAST] or [SplatMode.EXACT]. */
    val splatMode: SplatMode = SplatMode.MESH,
    /**
     * How many of [lights], from the front, move: the hero's and the
     * impacts'. The rest are the land's own lamps, which splats carry
     * already baked in ([VoxelLight]) and so do not shade again.
     */
    val dynamicLights: Int = lights.size,
) {
    /** Everything opaque: the terrain, the model props, then the actors. */
    val opaque: List<MeshBatch> get() = terrain + models + listOfNotNull(actors)

    /**
     * Returns this frame's per-frame batches to be reused. Call once, when
     * nothing will read this frame again; terrain and models are kept.
     */
    fun release() {
        actors?.release()
        cutout.release()
        decals.release()
        glows.release()
    }

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
    /** How much this device can afford: view distance, lights, litter and motes. */
    private val settings: RenderSettings = RenderSettings.of(QualityTier.HIGH),
    /**
     * The 3D model a prop block is drawn as, by block id, or null for the
     * painted sprite. Models go through the opaque path, so they are lit,
     * shadowed and fogged exactly like the terrain.
     */
    private val propModels: (String) -> PropModel? = { null },
    /**
     * The microvoxels behind the world, when it was generated from them:
     * chunks within [RenderSettings.microDetailRadius] of the camera are drawn
     * from these instead of from blocks.
     */
    microTerrain: com.stratum.engine.microvoxel.MicroTerrainSource? = null,
) {
    private val chunks = ChunkMeshCache(
        TerrainMesher(scene, textures, biomeAt),
        microTerrain?.let {
            MicroDetailMesher(it, scatterSurfels = settings.diorama.surfels, surfelDensity = settings.diorama.surfelDensity, splatMode = settings.splats, voxelLight = settings.voxelLight)
        },
        settings.microDetailRadius,
        settings.microFarRadius,
    )

    /** Where a placed block's pop takes its colour from, so it matches the detail it becomes. */
    private val microSource = microTerrain

    /** Blocks just placed or removed near the camera, drawn as a pop or a puff of dust; see [BuildPulses]. */
    private val pulses = BuildPulses()

    /** Cells animating a placement or removal in the last frame; what a test or a tool checks. */
    val activePulses: List<BuildPulses.Pulse> get() = pulses.active

    /** Chunks drawn from microvoxels in the last frame, for profiling. */
    val detailedChunksLastFrame: Int get() = chunks.detailedLastCall

    /** Microvoxel chunk meshes still being made in the background. */
    val detailPending: Int get() = chunks.detailPending

    /** Chunks that fell from microvoxels back to blocks in the last frame: a visible pop, zero when edits are patched. */
    val detailPopsLastFrame: Int get() = chunks.poppedLastCall

    /** Microvoxel layers an edit remeshed during the last frame; see [ChunkMeshCache]. */
    val editLayersLastFrame: Int get() = chunks.editLayersLastCall

    /** The chunks' meshes, props, lights and litter gathered into lists, redone only when a chunk changes. */
    private class Terrain(
        val meshes: List<MeshBatch>,
        val props: List<PropInstance>,
        val lights: List<PointLight>,
        val details: List<GroundDetail>,
        val models: MeshBatch? = null,
        /** Each mesh's box, six floats apiece (min x, y, z, max x, y, z), for culling against the view. */
        val bounds: FloatArray = FloatArray(0),
        /** Surfels of the detailed chunks; see [SurfelScatter]. */
        val surfels: List<SurfelBatch> = emptyList(),
        /** Voxel splats of the detailed chunks, when the tier draws them; see [SplatMode]. */
        val splats: List<SplatBatch> = emptyList(),
    )

    /** This frame's bloom gain from the night glow; 1 by day. */
    private var nightSwell = 1f

    private var terrain = Terrain(emptyList(), emptyList(), emptyList(), emptyList())
    private var terrainGeneration = Long.MIN_VALUE

    /** Each mesh's box, measured once per mesh rather than whenever any chunk changes. */
    private var meshBounds = java.util.IdentityHashMap<MeshBatch, FloatArray>()

    /**
     * Each prop's style, asked of the director once per eye level; see [prop].
     * Keyed by the prop itself, which a chunk keeps until it is remeshed, so
     * the styles outlive the terrain lists being gathered again.
     */
    private var propStyles = java.util.IdentityHashMap<PropInstance, PropStyle?>()
    private var styledAtEye = Int.MIN_VALUE

    /** A prop block's paintings, by block id; the library does not change under a builder. */
    private val paintingsByBlock = HashMap<String, IntArray>()

    private val cutout = MeshBuilder(MaterialKind.CUTOUT)
    private val decals = MeshBuilder(MaterialKind.DECAL)
    private val glows = MeshBuilder(MaterialKind.GLOW)
    private val actorMesh = MeshBuilder(MaterialKind.OPAQUE)

    /** Draws floating mask spirits: their smooth bodies, auras, trails and shields. */
    private val spiritStage = SpiritStage()

    /** Arrays for the per-frame batches; the backend returns them through [SceneFrame.release]. */
    private val recycler = MeshRecycler()

    // This frame's sun on the ground: which way shadows fall, how long they
    // are per unit of height, and how dark. Set at the start of build().
    /** How wide the see-through cut around the player is, in blocks; 0 turns it off. See [Reveal]. */
    var revealRadius: Float = Reveal.DEFAULT_RADIUS

    private var shadowDirX = 0f
    private var shadowDirY = 1f
    private var shadowReach = 0.7f
    private var shadowOpacity = 0.5f

    /** Forgets the cached terrain, e.g. after the textures or the director change. */
    fun invalidate() {
        chunks.invalidate()
    }

    /** Chunks meshed while building the last frame, for profiling. */
    val chunksMeshedLastFrame: Int get() = chunks.meshedLastCall

    fun build(
        world: World,
        camera: SceneCamera,
        actors: List<SceneActor> = emptyList(),
        time: WorldTime = WorldTime(),
        /** Bumped by the engine on every block change; the terrain cache keys on it. */
        worldRevision: Int = 0,
        radius: Int = settings.viewRadius,
        /** Cells a pending build or erase covers, shown before it is committed. */
        ghosts: List<com.stratum.core.domain.world.BlockPos> = emptyList(),
        ghostsAffordable: Boolean = true,
        /** The cell the player is about to act on. */
        highlight: com.stratum.core.domain.world.BlockPos? = null,
        /** Combat theatre in progress; see [EffectTrack]. */
        effects: List<ActiveEffect> = emptyList(),
        /** Projectiles in flight, pulsing ground and wind-ups; see [CombatMark]. */
        marks: List<CombatMark> = emptyList(),
        /** Blocks thrown loose by attacks, still in the air. */
        debris: List<DebrisMark> = emptyList(),
        /** Fallen bodies lying loose. */
        ragdolls: List<RagdollMark> = emptyList(),
        /** Floating mask spirits, posed by whoever animates them; see [SpiritStage]. */
        spirits: List<SpiritInstance> = emptyList(),
    ): SceneFrame {
        val cx = floor(camera.target.x).toInt()
        val cy = floor(camera.target.y).toInt()
        // The view is centred on a region-quantised position, so walking a few
        // blocks keeps the same chunks in view; within it, only chunks that
        // changed are meshed again.
        // Only what the lens can see, or can reach into it, is built. The
        // meshed square is many times the screen, and every prop in it used
        // to be turned to the camera, shadowed and uploaded every frame.
        val volume = ViewVolume.of(camera)
        val terrain = terrainAround(
            world, Math.floorDiv(cx, REGION_STEP) * REGION_STEP, Math.floorDiv(cy, REGION_STEP) * REGION_STEP, radius, worldRevision,
            volume, camera.target.z,
        )

        val biome = biomeAt(cx, cy)
        val styled = scene.lightingFor(biome?.id, time)
        // Fog is authored relative to the focus; backends measure from the eye.
        // The fog is also what hides the edge of the world. Terrain only exists
        // as far as the engine has loaded it, and past that the sky showed
        // through as a black staircase; fog is total before the meshed edge,
        // and the sky leans towards the fog colour, so the boundary dissolves.
        val edge = camera.distance + radius * EDGE_FOG_SHARE
        val night = nightOf(time)
        nightSwell = 1f + settings.diorama.nightGlow * night * NIGHT_BLOOM_SWELL
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
        }.let { lit -> moonlit(lit, night * settings.diorama.nightGrade) }

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
        frameNormal = billboardNormal(camera)

        val forward = (camera.target - camera.eye).let { Vec3(it.x, it.y, 0f).normalized() }
        if (settings.groundLitter) terrain.details.forEach { if (volume.mayShow(it.x, it.y, it.z, it.size, it.size)) litter(it) }
        if (eyeLevel != styledAtEye) {
            // A prop's look depends on how far below the eye it stands, so a step up or down restyles.
            propStyles.clear()
            styledAtEye = eyeLevel
        }
        terrain.props.forEachIndexed { index, prop ->
            if (propModels(prop.block.id) != null) return@forEachIndexed
            val height = SPRITE_HEIGHT * prop.block.glyphScale * (1f + PROP_SIZE_SPREAD)
            // A shadow falls up to its caster's height times the reach away, so a tree off screen can still darken it.
            if (!volume.mayShow(prop.x + 0.5f, prop.y + 0.5f, prop.z.toFloat(), height, height * MAX_SHADOW_REACH + 1f)) return@forEachIndexed
            prop(index, prop, camera, eyeLevel, occlusionFade(prop, actors, forward))
        }
        terrain.lights.forEach { light ->
            if (!volume.mayShow(light.x, light.y, light.z, BLOOM_LIFT, BLOOM_RADIUS * (0.6f + light.strength))) return@forEach
            // A light you can see the source of. Point lights colour the ground;
            // the bloom is what tells the eye where the fire actually is.
            // At night the bloom swells with the glow, so a lamp reads from across the square.
            val swell = nightSwell
            glow(camera, light.x, light.y, light.z + BLOOM_LIFT, BLOOM_RADIUS * (0.6f + light.strength) * swell, light.color, BLOOM_OPACITY * swell)
        }
        actors.forEach { actor(it, camera, eyeLevel) }
        pulses.update(world, cx, cy, worldRevision, time.elapsedSeconds)
        pulses.active.forEach { pulse(world, it, time.elapsedSeconds) }
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
        if (settings.atmosphereMotes) motes(camera, time, biome)
        val flashes = ArrayList<PointLight>()
        effects.forEach { effect(it, camera, flashes) }
        marks.forEach { mark(it, camera, flashes, time.elapsedSeconds) }
        debris.forEach { d -> cube(actorMesh, d.x, d.y, d.z, d.size, d.color, shade(d.color)) }
        ragdolls.forEach(::ragdoll)
        if (spirits.isNotEmpty()) spiritStage.draw(spirits, camera, actorMesh, cutout, glows, flashes)

        val hero = actors.firstOrNull { it.presentation.role == com.stratum.core.domain.art.ActorRole.PLAYER }
            ?.takeIf { lighting.heroLight > 0f }
            ?.let { PointLight(it.x, it.y, it.z + HERO_LIGHT_HEIGHT, lighting.sunColor, lighting.heroLight, lighting.heroLightRadius) }
        // A hit lights the ground around it for a moment — the cheapest way to
        // make an impact feel like it has weight. The brightest few win.
        // The hero's light first, so a dark style stays playable at every tier;
        // then the impacts; then the nearest fixed lights, as many as fit.
        val bursts = flashes.sortedByDescending { it.strength }.take(MAX_EFFECT_LIGHTS)
        val budget = minOf(settings.maxPointLights, SceneFrame.MAX_LIGHTS)
        val fixedSlots = (budget - (if (hero != null) 1 else 0) - bursts.size).coerceAtLeast(0)
        val nearest = (
            listOfNotNull(hero) + bursts + terrain.lights
                .sortedBy { abs(it.x - camera.target.x) + abs(it.y - camera.target.y) }
                .take(fixedSlots)
                .map { it.copy(strength = it.strength * lighting.pointLightGain) }
            ).take(budget)

        return SceneFrame(
            camera = camera,
            lighting = lighting,
            lights = nearest,
            shadowViewProjection = shadowMatrix(camera.target, lighting),
            terrain = visibleTerrain(terrain, volume),
            residentTerrain = terrain.meshes,
            actors = actorMesh.takeUnless { it.isEmpty }?.build(recycler),
            cutout = cutout.build(recycler),
            decals = decals.build(recycler),
            glows = glows.build(recycler),
            models = listOfNotNull(terrain.models),
            reveal = actors.firstOrNull { it.presentation.role == com.stratum.core.domain.art.ActorRole.PLAYER }
                ?.takeIf { revealRadius > 0f }
                ?.let { Reveal(it.x, it.y, it.z, revealRadius) },
            look = settings.diorama,
            night = night,
            surfels = if (settings.diorama.surfels) SurfelLod.select(
                terrain.surfels, camera.target.x, camera.target.y, settings.diorama.surfelRadius, settings.diorama.surfelBudget,
            ) { b -> volume.intersects(b.originX.toFloat(), b.originY.toFloat(), b.minZ, b.originX + Chunk.SIZE.toFloat(), b.originY + Chunk.SIZE.toFloat(), b.maxZ) }
            else emptyList(),
            residentSurfels = terrain.surfels,
            splats = terrain.splats.filter { b ->
                volume.intersects(
                    b.originX - TERRAIN_SHADOW_MARGIN, b.originY - TERRAIN_SHADOW_MARGIN, b.minZ,
                    b.originX + Chunk.SIZE + TERRAIN_SHADOW_MARGIN, b.originY + Chunk.SIZE + TERRAIN_SHADOW_MARGIN, b.maxZ,
                )
            },
            residentSplats = terrain.splats,
            splatMode = settings.splatDraw,
            dynamicLights = if (settings.splats.splats && settings.voxelLight) minOf(nearest.size, (if (hero != null) 1 else 0) + bursts.size) else nearest.size,
        )
    }

    /**
     * How far into the night it is, for the glow of windows and lamps: 0
     * through the day, rising through dusk to 1 at midnight. The same
     * noon-to-midnight triangle the art director darkens the sun by, eased
     * so the windows only come on once the light has really gone.
     */
    /**
     * The night grade ([com.stratum.engine.scene.quality.DioramaLook.nightGrade]):
     * sun and sky dimmed and cooled towards moonlight by [n], fog and sky
     * deepened, lamps and the hero's light strengthened. [n] 0 returns
     * [lit] itself.
     */
    private fun moonlit(lit: com.stratum.core.domain.art.SceneLighting, n: Float): com.stratum.core.domain.art.SceneLighting {
        if (n <= 0f) return lit
        return lit.copy(
            sunIntensity = lit.sunIntensity * (1f - MOON_SUN_LOSS * n),
            sunColor = Tint.mix(lit.sunColor, MOONLIGHT, MOON_TINT * n),
            ambientIntensity = lit.ambientIntensity * (1f - MOON_AMBIENT_LOSS * n),
            skyAmbient = Tint.mix(lit.skyAmbient, MOON_SKY, MOON_TINT * n),
            groundAmbient = Tint.mix(lit.groundAmbient, MOON_GROUND, MOON_TINT * n * 0.6f),
            fogColor = Tint.mix(lit.fogColor, MOON_FOG, MOON_TINT * n),
            skyTop = Tint.mix(lit.skyTop, MOON_SKY_TOP, MOON_TINT * n),
            skyBottom = Tint.mix(lit.skyBottom, MOON_FOG, MOON_TINT * n),
            saturation = lit.saturation * (1f - MOON_DESATURATE * n),
            pointLightGain = lit.pointLightGain * (1f + MOON_LAMP_GAIN * n),
            heroLight = lit.heroLight * (1f + MOON_LAMP_GAIN * 0.5f * n),
        )
    }

    private fun nightOf(time: WorldTime): Float {
        val phase = ((time.dayFraction % 1f) + 1f) % 1f
        val dark = (abs(phase - 0.5f) / 0.5f).coerceIn(0f, 1f)
        return ShadingModel.smoothstep(NIGHT_GLOW_START, 1f, dark)
    }

    private fun terrainAround(
        world: World, centreX: Int, centreY: Int, radius: Int, worldRevision: Int, volume: ViewVolume, eyeZ: Float,
    ): Terrain {
        val results = chunks.around(
            world, centreX, centreY, radius, worldRevision,
            urgent = { pos ->
                volume.intersects(
                    pos.originX - TERRAIN_SHADOW_MARGIN, pos.originY - TERRAIN_SHADOW_MARGIN, eyeZ - URGENT_DEPTH,
                    pos.originX + Chunk.SIZE + TERRAIN_SHADOW_MARGIN, pos.originY + Chunk.SIZE + TERRAIN_SHADOW_MARGIN, eyeZ + URGENT_HEIGHT,
                )
            },
            offscreenBudget = OFFSCREEN_MESH_BUDGET,
        )
        if (chunks.generation != terrainGeneration) {
            val props = results.flatMap { it.props }
            val meshes = results.map { it.mesh }.filterNot { it.isEmpty }
            terrain = Terrain(
                meshes = meshes,
                props = props,
                lights = results.flatMap { it.lights },
                details = results.flatMap { it.details },
                models = modelProps(props),
                bounds = boundsOf(meshes),
                surfels = results.mapNotNull { it.surfels },
                splats = results.mapNotNull { it.splats },
            )
            // Styles of props still in the square are kept; the rest are let go.
            val kept = java.util.IdentityHashMap<PropInstance, PropStyle?>(props.size)
            props.forEach { if (propStyles.containsKey(it)) kept[it] = propStyles[it] }
            propStyles = kept
            terrainGeneration = chunks.generation
        }
        return terrain
    }

    /**
     * The chunks the camera can see, or that could throw a shadow into view.
     *
     * The shadow pass draws the same list, so the box is widened by how far a
     * cliff's shadow can fall; a chunk is sixteen blocks wide, so the margin
     * costs little.
     */
    private fun visibleTerrain(terrain: Terrain, volume: ViewVolume): List<MeshBatch> {
        val b = terrain.bounds
        return terrain.meshes.filterIndexed { i, _ ->
            val o = i * 6
            volume.intersects(
                b[o] - TERRAIN_SHADOW_MARGIN, b[o + 1] - TERRAIN_SHADOW_MARGIN, b[o + 2],
                b[o + 3] + TERRAIN_SHADOW_MARGIN, b[o + 4] + TERRAIN_SHADOW_MARGIN, b[o + 5],
            )
        }
    }

    /** Each mesh's box, measured once when the terrain changes rather than every frame. */
    private fun boundsOf(meshes: List<MeshBatch>): FloatArray {
        val out = FloatArray(meshes.size * 6)
        val kept = java.util.IdentityHashMap<MeshBatch, FloatArray>(meshes.size)
        meshes.forEachIndexed { i, mesh ->
            val known = meshBounds[mesh]
            if (known != null) {
                known.copyInto(out, i * 6)
                kept[mesh] = known
                return@forEachIndexed
            }
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            val v = mesh.vertices
            var at = 0
            while (at < mesh.vertexFloats) {
                val x = v[at + Vertex.PX]; val y = v[at + Vertex.PX + 1]; val z = v[at + Vertex.PX + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                at += Vertex.STRIDE
            }
            val o = i * 6
            out[o] = minX; out[o + 1] = minY; out[o + 2] = minZ; out[o + 3] = maxX; out[o + 4] = maxY; out[o + 5] = maxZ
            kept[mesh] = out.copyOfRange(o, o + 6)
        }
        // Only meshes still in the square are remembered, so this never outgrows it.
        meshBounds = kept
        return out
    }

    /**
     * Every prop that has a 3D model, as one opaque batch.
     *
     * Built only when the terrain changes, not per frame: a model prop does
     * not face the camera, so nothing about it depends on where the camera is.
     * Each is turned by a quarter picked from its cell, as sprite props pick
     * a painting, so a grove of one statue is not a row of clones.
     */
    private fun modelProps(props: List<PropInstance>): MeshBatch? {
        val out = MeshBuilder(MaterialKind.OPAQUE)
        props.forEach { prop ->
            val model = propModels(prop.block.id) ?: return@forEach
            model.emit(out, prop.x + 0.5f, prop.y + 0.5f, prop.z.toFloat(), quarterTurns = hash(prop.x, prop.y) ushr 5, scale = prop.block.glyphScale)
        }
        return out.takeUnless { it.isEmpty }?.build()
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

    private fun prop(index: Int, prop: PropInstance, camera: SceneCamera, eyeLevel: Int, opacity: Float = 1f) {
        val variant = hash(prop.x, prop.y)
        // Asked of the director once per prop per eye level rather than every
        // frame: it was the most expensive single call in a frame, for an
        // answer that had not changed.
        val style = (
            if (propStyles.containsKey(prop)) propStyles[prop]
            else director.propStyleFor(
                PropCue(prop.block, prop.biomeId, variant, depthBelowEye = (eyeLevel - prop.z).coerceAtLeast(0)),
            ).also { propStyles[prop] = it }
            ) ?: return
        val baseX = prop.x + 0.5f
        val baseY = prop.y + 0.5f
        val baseZ = prop.z.toFloat()

        decal(baseX, baseY, baseZ, PROP_SHADOW_RADIUS * style.scale * CONTACT_WITH_SHADOW, director.direction.palette.ink, style.contactShadow, Vertex.DISC)

        // One of the forged individuals, picked by place: neighbours differ,
        // and the same tree is the same tree every time you walk past it.
        val paintings = paintingsByBlock.getOrPut(prop.block.id) { textures.variantsOf("prop:${prop.block.id}") }
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
            glow(camera, baseX, baseY, baseZ + style.scale * 0.8f, style.scale * 1.6f * nightSwell, style.glow, Tint.alpha(style.glow) / 255f * nightSwell)
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
        val n = frameNormal
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
        val n = frameNormal
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

    /**
     * A block just placed or removed: the placed one pops in and kicks up a
     * little dust at its foot; the removed one leaves a puff where it stood.
     *
     * The pop is also what makes an edit show on the frame it happens. A
     * chunk drawn in microvoxels gets its new mesh from a worker a frame or
     * two later ([ChunkMeshCache.awaiting]); until then the pop stands in for
     * the block, at full size once its animation is over. It is a hair larger
     * than a block, so once the real one lands beneath it the two never fight
     * over the same pixels.
     */
    private fun pulse(world: World, p: BuildPulses.Pulse, now: Float) {
        val age = now - p.born
        val registry = world.registry
        if (p.placed) {
            val block = registry.typeOf(p.block)
            val (top, side) = pulseColours(registry, p.block)
            val t = age / BuildPulses.POP_SECONDS
            val solid = block.glyph == null && block.shape == com.stratum.core.domain.world.BlockShape.CUBE
            if (solid && (t < 1f || p.chunk in chunks.awaiting)) {
                val s = BuildPulses.popScale(t) * PULSE_SIZE
                cube(actorMesh, p.x + 0.5f, p.y + 0.5f, p.z + 0.5f, s, top, side)
            }
            if (age < BuildPulses.DUST_SECONDS) dust(p, age / BuildPulses.DUST_SECONDS, side, grains = PLACE_GRAINS, rise = 0.15f)
        } else if (p.was != com.stratum.core.domain.world.BlockRegistry.AIR_INDEX && age < BuildPulses.DUST_SECONDS) {
            dust(p, age / BuildPulses.DUST_SECONDS, pulseColours(registry, p.was).second, grains = BREAK_GRAINS, rise = 0.5f)
        }
    }

    /** A block's top and side colours as the detail mesher will draw it, or as the block mesher does. */
    private fun pulseColours(registry: com.stratum.core.domain.world.BlockRegistry, index: Int): Pair<Long, Long> {
        val micro = microSource
        if (micro != null) {
            val rgb = runCatching { micro.palette[micro.materialForBlock(index)].color }.getOrNull()
            if (rgb != null) {
                val c = Tint.OPAQUE or (rgb.toLong() and 0xFFFFFF)
                return c to Tint.scale(c, PULSE_SIDE_SHADE)
            }
        }
        val block = registry.typeOf(index.coerceAtLeast(0))
        return block.topColor to block.sideColor
    }

    /**
     * Grains of the block flung out from its cell and falling back, with a
     * faint scuff on the ground: small, quick and cheap -- a few boxes in the
     * per-frame actor batch -- because a build makes dozens of these.
     */
    private fun dust(p: BuildPulses.Pulse, t: Float, block: Long, grains: Int, rise: Float) {
        val fade = 1f - t
        // Dust is the block ground fine: paler than the block, or it vanishes against ground of the same stuff.
        val color = Tint.mix(block, DUST_LIGHT, DUST_PALER)
        val baseZ = if (p.placed) p.z.toFloat() else p.z + 0.5f
        decal(p.x + 0.5f, p.y + 0.5f, p.z.toFloat(), 0.45f + 0.45f * t, color, fade * fade * DUST_SCUFF_OPACITY, Vertex.DISC)
        for (i in 0 until grains) {
            val a = unit(hash(p.x * 31 + i, p.y * 17 + p.z), i) * TAU
            val reach = 0.35f + 0.55f * t * (0.6f + 0.4f * unit(p.x + i, p.y - i))
            val lift = rise * (0.5f + unit(p.y + i, p.x)) * t * (1.6f - t) * 2f - 0.35f * t * t
            val size = DUST_GRAIN * fade * (0.6f + 0.6f * unit(i, p.z))
            if (size <= 0.01f) continue
            cube(actorMesh, p.x + 0.5f + cos(a) * reach, p.y + 0.5f + sin(a) * reach, baseZ + 0.08f + lift, size, color, color)
        }
    }

    /**
     * A fallen body as a figure of small voxels: a run of cubes along each
     * limb and a bigger one for the head, shrinking away as it fades.
     */
    private fun ragdoll(r: RagdollMark) {
        if (r.alpha <= 0f) return
        val p = r.points
        val size = RAGDOLL_VOXEL * r.scale * (0.35f + 0.65f * r.alpha)
        val side = shade(r.color)
        for ((a, b) in RagdollMark.LIMBS) {
            val ax = p[a * 3]; val ay = p[a * 3 + 1]; val az = p[a * 3 + 2]
            val bx = p[b * 3]; val by = p[b * 3 + 1]; val bz = p[b * 3 + 2]
            val length = kotlin.math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))
            val n = (length / (size * 0.8f)).toInt().coerceIn(1, 8)
            for (i in 0..n) {
                val t = i.toFloat() / n
                cube(actorMesh, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, size, r.color, side)
            }
        }
        cube(actorMesh, p[0], p[1], p[2], size * 2.2f, r.color, side)
    }

    /** A colour a little darker, for the sides of a cube lit from above. */
    private fun shade(color: Long): Long {
        val r = ((color shr 16) and 0xFF) * 3 / 4; val g = ((color shr 8) and 0xFF) * 3 / 4; val b = (color and 0xFF) * 3 / 4
        return (color and 0xFF000000L) or (r shl 16) or (g shl 8) or b
    }

    /** An axis-aligned cube of edge [size] centred on a point: top and four sides, flat-coloured like microvoxel ground. */
    private fun cube(out: MeshBuilder, cx: Float, cy: Float, cz: Float, size: Float, top: Long, side: Long) {
        val h = size / 2
        val x0 = cx - h; val x1 = cx + h; val y0 = cy - h; val y1 = cy + h; val z0 = cz - h; val z1 = cz + h
        fun face(nx: Float, ny: Float, nz: Float, color: Long, vararg c: Float) {
            val a = out.vertex(c[0], c[1], c[2], nx, ny, nz, color, 1f, 0f, 0f, Vertex.FLAT)
            val b = out.vertex(c[3], c[4], c[5], nx, ny, nz, color, 1f, 0f, 0f, Vertex.FLAT)
            val d = out.vertex(c[6], c[7], c[8], nx, ny, nz, color, 1f, 0f, 0f, Vertex.FLAT)
            val e = out.vertex(c[9], c[10], c[11], nx, ny, nz, color, 1f, 0f, 0f, Vertex.FLAT)
            out.quad(a, b, d, e)
        }
        face(0f, 0f, 1f, top, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1)
        face(1f, 0f, 0f, side, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1)
        face(-1f, 0f, 0f, side, x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1)
        face(0f, 1f, 0f, side, x1, y1, z0, x0, y1, z0, x0, y1, z1, x1, y1, z1)
        face(0f, -1f, 0f, side, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1)
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
    /**
     * A projectile, a zone or a telegraph, in the same glows and ground decals
     * the combat theatre uses: a projectile is a hot point with a fading
     * trail and a little light; a zone is a lit disc with a rim; a wind-up is
     * its outline on the ground filling towards the moment it lands, which is
     * what the player reads to roll out of it.
     */
    private fun mark(mark: CombatMark, camera: SceneCamera, lights: MutableList<PointLight>, seconds: Float) {
        val color = (if (mark.hostile) director.direction.palette.hostile else mark.color) or Tint.OPAQUE
        val look = mark.look
        if (look != null && mark.kind != CombatMarkKind.TELEGRAPH) return forged(mark, look, camera, lights, seconds)
        when (mark.kind) {
            CombatMarkKind.PROJECTILE -> {
                val body = mark.radius.coerceAtLeast(MIN_PROJECTILE_GLOW)
                glow(camera, mark.x, mark.y, mark.z, body * 2.2f, color, PROJECTILE_OPACITY)
                glow(camera, mark.x, mark.y, mark.z, body, HOT_CORE, PROJECTILE_OPACITY)
                for (k in 1..PROJECTILE_TRAIL) {
                    val back = k * TRAIL_STEP
                    glow(camera, mark.x - mark.dirX * back, mark.y - mark.dirY * back, mark.z, body * (2f - k * 0.3f), color, PROJECTILE_OPACITY * (1f - k / (PROJECTILE_TRAIL + 1f)))
                }
                lights += PointLight(mark.x, mark.y, mark.z, color, PROJECTILE_LIGHT, 3f)
            }
            CombatMarkKind.ZONE -> {
                val pulse = 0.94f + 0.06f * sin(seconds * 6f + mark.x + mark.y)
                decal(mark.x, mark.y, mark.z, mark.radius * pulse, color, ZONE_FILL, Vertex.DISC)
                decal(mark.x, mark.y, mark.z, mark.radius, color, ZONE_RIM, Vertex.RING)
            }
            CombatMarkKind.TELEGRAPH -> telegraph(mark, color)
        }
    }

    /**
     * A forged attack in its own look: the same [AttackSketch] marks the
     * forge previews, laid into the world at the projectile or zone. Glows
     * stay glows, ground marks become decals, and a streak is a run of
     * small glows along its line.
     */
    private fun forged(mark: CombatMark, look: AttackLook, camera: SceneCamera, lights: MutableList<PointLight>, seconds: Float) {
        val sketch = when (mark.kind) {
            CombatMarkKind.PROJECTILE -> AttackSketch.body(look, seconds, if (look.body.delivery == DeliveryKind.SURFACE_WAVE) DeliveryKind.SURFACE_WAVE else DeliveryKind.BALLISTIC)
            else -> AttackSketch.decal(look, mark.radius * 0.9f) + AttackSketch.field(look, mark.radius, seconds, DeliveryKind.IMPACT_FIELD)
        }
        val heading = kotlin.math.atan2(mark.dirY, mark.dirX)
        // Ground under a flying body is half a body below it; a zone already lies on the ground.
        val ground = if (mark.kind == CombatMarkKind.PROJECTILE) mark.z - PROJECTILE_GROUND else mark.z
        val air = mark.z
        var drawn = 0
        for (raw in sketch) {
            if (drawn >= MAX_FORGED_MARKS) break
            val m = AttackSketch.transform(raw, mark.x, mark.y, heading)
            when (m) {
                is SketchMark.Glow -> { glow(camera, m.x, m.y, air + m.z, m.radius.coerceAtLeast(0.04f), argb(m.color), m.alpha * PROJECTILE_OPACITY); drawn++ }
                is SketchMark.Ground -> { decal(m.x, m.y, ground, m.radius, argb(m.color), m.alpha, if (m.ring) Vertex.RING else Vertex.DISC); drawn++ }
                is SketchMark.Streak -> {
                    val dx = m.x1 - m.x0; val dy = m.y1 - m.y0; val dz = m.z1 - m.z0
                    val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                    val step = (m.width * 0.8f).coerceAtLeast(0.08f)
                    val n = (length / step).toInt().coerceIn(1, MAX_STREAK_GLOWS)
                    for (i in 0..n) {
                        val u = i.toFloat() / n
                        glow(camera, m.x0 + dx * u, m.y0 + dy * u, air + m.z0 + dz * u, m.width * 0.6f, argb(m.color), m.alpha * PROJECTILE_OPACITY)
                    }
                    drawn += n
                }
            }
        }
        lights += PointLight(mark.x, mark.y, air, argb(look.primary), PROJECTILE_LIGHT * (0.8f + 0.2f * look.glow), 3f)
    }

    private fun argb(color: Int): Long = (color.toLong() and 0xFFFFFFFFL) or Tint.OPAQUE

    /** A wind-up's outline and its fill. Cones and lanes are laid out in discs, since a decal is a round mark. */
    private fun telegraph(mark: CombatMark, color: Long) {
        val fill = mark.progress.coerceIn(0f, 1f)
        when (mark.shape) {
            MarkShape.CIRCLE -> {
                decal(mark.x, mark.y, mark.z, mark.radius, color, TELEGRAPH_RIM, Vertex.RING)
                decal(mark.x, mark.y, mark.z, mark.radius, color, TELEGRAPH_AREA, Vertex.DISC)
                decal(mark.x, mark.y, mark.z, mark.radius * fill, color, TELEGRAPH_FILL, Vertex.DISC)
            }
            MarkShape.LANE -> {
                val step = (mark.halfWidth * 1.5f).coerceAtLeast(MIN_MARK_STEP)
                val count = (mark.radius / step).toInt().coerceIn(1, MAX_MARK_DISCS)
                for (i in 0..count) {
                    val along = mark.radius * i / count
                    val x = mark.x + mark.dirX * along
                    val y = mark.y + mark.dirY * along
                    decal(x, y, mark.z, mark.halfWidth.coerceAtLeast(MIN_MARK_STEP), color, if (along <= mark.radius * fill) TELEGRAPH_FILL else TELEGRAPH_AREA, Vertex.DISC)
                }
            }
            MarkShape.CONE -> {
                val heading = kotlin.math.atan2(mark.dirY, mark.dirX)
                val half = Math.toRadians(mark.angleDegrees / 2.0).toFloat()
                val rings = (mark.radius / CONE_RING).toInt().coerceIn(1, MAX_MARK_DISCS / 2)
                for (r in 1..rings) {
                    val distance = mark.radius * r / rings
                    val spokes = (2 * half * distance / CONE_RING).toInt().coerceIn(1, MAX_MARK_DISCS / 2)
                    for (k in 0..spokes) {
                        val angle = heading - half + 2 * half * k / spokes
                        decal(
                            mark.x + cos(angle) * distance, mark.y + sin(angle) * distance, mark.z, CONE_RING * 0.6f, color,
                            if (distance <= mark.radius * fill) TELEGRAPH_FILL else TELEGRAPH_AREA, Vertex.DISC,
                        )
                    }
                }
            }
        }
    }

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
    /** The billboard normal for the frame being built; the camera cannot turn mid-frame. */
    private var frameNormal: Vec3 = Vec3.UP

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
        const val REGION_STEP = 6
        /** How dark (0 noon, 1 midnight) it must be before windows start to glow. */
        const val NIGHT_GLOW_START = 0.45f
        /** Bloom radius and opacity gained per unit of night glow at midnight. */
        const val NIGHT_BLOOM_SWELL = 0.15f
        // The night grade: moonlight a pale blue-white, the sky and fog deep blue.
        const val MOONLIGHT = 0xFFA4B8F0L
        const val MOON_SKY = 0xFF5068A8L
        const val MOON_GROUND = 0xFF22263CL
        const val MOON_FOG = 0xFF1B2340L
        const val MOON_SKY_TOP = 0xFF0C1026L
        const val MOON_TINT = 0.75f
        const val MOON_SUN_LOSS = 0.6f
        const val MOON_AMBIENT_LOSS = 0.35f
        const val MOON_DESATURATE = 0.15f
        const val MOON_LAMP_GAIN = 0.8f
        /** How far past a chunk's edge its shadow may reach into view, in blocks. */
        const val TERRAIN_SHADOW_MARGIN = 8f
        /** Off-screen chunks meshed per frame; see [ChunkMeshCache]. */
        const val OFFSCREEN_MESH_BUDGET = 1
        /** The slab of a chunk tested for being on screen, below and above the eye. */
        const val URGENT_DEPTH = 24f
        const val URGENT_HEIGHT = 16f
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
        const val MIN_PROJECTILE_GLOW = 0.18f
        const val PROJECTILE_OPACITY = 1.6f
        const val PROJECTILE_TRAIL = 4
        /** Edge of one voxel of a fallen body, in blocks. */
        const val RAGDOLL_VOXEL = 0.16f
        /** A forged attack's mark draws at most this many glows and decals, however busy its look. */
        const val MAX_FORGED_MARKS = 64
        const val MAX_STREAK_GLOWS = 10
        /** How far below a flying body the ground lies, for a wave's or a shell's ground marks. */
        const val PROJECTILE_GROUND = 0.6f
        const val TRAIL_STEP = 0.18f
        const val PROJECTILE_LIGHT = 0.6f
        const val ZONE_FILL = 0.3f
        const val ZONE_RIM = 0.85f
        const val TELEGRAPH_RIM = 0.9f
        const val TELEGRAPH_AREA = 0.14f
        const val TELEGRAPH_FILL = 0.4f
        const val MIN_MARK_STEP = 0.35f
        const val MAX_MARK_DISCS = 40
        const val CONE_RING = 0.8f
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

        /** A placed block's pop, as a share of a block: a hair over, so it covers the real block when that lands. */
        const val PULSE_SIZE = 1.03f
        /** Sides of a microvoxel-coloured pop, darkened as the detail's sides read under the sun. */
        const val PULSE_SIDE_SHADE = 0.82f
        /** Grains of dust a placed block kicks up, and a removed one leaves. */
        const val PLACE_GRAINS = 5
        const val BREAK_GRAINS = 9
        const val DUST_GRAIN = 0.2f
        const val DUST_SCUFF_OPACITY = 0.35f
        const val DUST_LIGHT = 0xFFF2E6D0
        const val DUST_PALER = 0.4f
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
