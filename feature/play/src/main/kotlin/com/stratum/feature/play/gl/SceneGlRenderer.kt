package com.stratum.feature.play.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.stratum.engine.scene.MeshBatch
import com.stratum.engine.scene.SceneFrame
import com.stratum.engine.scene.ShadingModel
import com.stratum.engine.scene.Surfel
import com.stratum.engine.scene.SurfelBatch
import com.stratum.engine.scene.SplatBatch
import com.stratum.engine.scene.SplatMode
import com.stratum.engine.scene.VoxelSplat
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.TextureBudget
import com.stratum.engine.scene.Vertex
import com.stratum.engine.scene.quality.DeviceClassifier
import com.stratum.engine.scene.quality.DeviceProfile
import com.stratum.engine.scene.quality.FrameGovernor
import com.stratum.engine.scene.quality.QualityTier
import com.stratum.engine.scene.quality.RenderSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.IdentityHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws [SceneFrame]s with OpenGL ES 3.
 *
 * The GPU twin of the software rasteriser in `:tools:artpreview`, pass for
 * pass: sun depth into a shadow map; sky, opaque, cut-out, decals and glows
 * into a high-range target; then one full-screen pass that tone maps, grades
 * and vignettes. Rendering into a high-range target and finishing at the end,
 * rather than tone mapping per fragment, is what keeps a torch's glow on top of
 * a lit wall from clipping to a flat white disc.
 *
 * Frames arrive from the UI thread through [submit]; this class never touches
 * the world. Terrain buffers are cached per chunk by the identity of their
 * [MeshBatch], which the scene builder reuses until that chunk changes, so an
 * edit uploads one chunk rather than the whole view.
 */
class SceneGlRenderer(
    /** What the OS knows about the device; the GL limits are added once a context exists. */
    private val device: DeviceProfile,
    /** The player's choice, or null to let the device decide. */
    requestedTier: QualityTier? = null,
    /** Told, on the GL thread, which settings were settled on, so the scene builder can match them. */
    private val onSettings: (RenderSettings) -> Unit = {},
) : GLSurfaceView.Renderer {

    @Volatile private var requested: QualityTier? = requestedTier
    @Volatile private var settingsStale = true

    private var settings: RenderSettings = RenderSettings.of(QualityTier.HIGH)
    private var governor = FrameGovernor(settings)
    private var profile: DeviceProfile = device
    private var lastFrameNanos = 0L

    /** The textures and maps last submitted, kept so a change of tier or a new GL context can upload them again. */
    private var textures: List<Texture> = emptyList()
    private var maps: List<Texture> = emptyList()

    /** The newest frame not yet taken by the GL thread. */
    private val pending = java.util.concurrent.atomic.AtomicReference<SceneFrame?>(null)

    /** The frame the GL thread is drawing, redrawn until a newer one arrives. GL thread only. */
    private var current: SceneFrame? = null
    @Volatile private var pendingTextures: List<Texture>? = null
    @Volatile private var pendingMaps: List<Texture>? = null

    private var width = 1
    private var height = 1

    /** The scene target's size: the screen's, times the governor's render scale. */
    private var sceneWidth = 1
    private var sceneHeight = 1
    private var shadowSize = 0

    private var lit = 0
    private var soft = 0
    private var shadowProgram = 0
    private var sky = 0
    private var finish = 0
    private var surfelProgram = 0
    private var splatFast = 0
    private var splatExact = 0
    private var splatShadow = 0

    private var shadowFbo = 0
    private var shadowDepth = 0
    private var sceneFbo = 0
    private var sceneColor = 0
    private var sceneDepth = 0
    /** Whether [sceneDepth] is a texture the finishing pass reads (the diorama's depth finish), or a renderbuffer. */
    private var sceneDepthIsTexture = false
    private var textureArray = 0
    private var mapArray = 0
    private var hasTextures = false
    private var emptyVao = 0

    private val staticMeshes = IdentityHashMap<MeshBatch, GpuMesh>()

    /** Each chunk's surfels, uploaded once like its mesh and freed when it leaves the meshed square. */
    private val surfelBuffers = IdentityHashMap<SurfelBatch, GpuPoints>()
    private val staleSurfels = ArrayList<SurfelBatch>()

    /** Each chunk layer's voxel splats, uploaded once and freed like [surfelBuffers]. */
    private val splatBuffers = IdentityHashMap<SplatBatch, GpuPoints>()
    private val staleSplats = ArrayList<SplatBatch>()
    private val inverseViewProj = FloatArray(16)

    // Point-light uniforms, filled in place each frame rather than allocated.
    private val lightPos = FloatArray(SceneFrame.MAX_LIGHTS * 3)
    private val lightCol = FloatArray(SceneFrame.MAX_LIGHTS * 3)
    private val lightRad = FloatArray(SceneFrame.MAX_LIGHTS)

    // One buffer per kind of per-frame geometry. The actors are drawn by both
    // the shadow and the scene pass; each batch is uploaded once, when it is
    // new, rather than once per draw into one shared buffer.
    private val actorMesh = GpuMesh()
    private val cutoutMesh = GpuMesh()
    private val decalMesh = GpuMesh()
    private val glowMesh = GpuMesh()

    // Staging memory for uploads, grown when a batch outgrows it and otherwise
    // reused. Allocating it per upload was megabytes of native memory a frame.
    private var floatStaging: FloatBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var intStaging: IntBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer()

    /** Hands a new frame to the GL thread. Cheap; call as often as the world changes. */
    fun submit(frame: SceneFrame) {
        // A frame replaced before the GL thread took it was never read, so its
        // arrays can go straight back for the next one.
        pending.getAndSet(frame)?.release()
    }

    /** Switches quality tier; null goes back to the device's own. Takes effect on the next frame. */
    fun requestTier(tier: QualityTier?) {
        requested = tier
        settingsStale = true
    }

    /** Replaces the texture array. Layer order must match the scene's [com.stratum.engine.scene.TextureLibrary]. */
    fun submitTextures(textures: List<Texture>, maps: List<Texture> = emptyList()) {
        pendingTextures = textures
        pendingMaps = maps
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        lit = program(SceneShaders.LIT_VERTEX, SceneShaders.LIT_FRAGMENT)
        soft = program(SceneShaders.SOFT_VERTEX, SceneShaders.SOFT_FRAGMENT)
        shadowProgram = program(SceneShaders.SHADOW_VERTEX, SceneShaders.SHADOW_FRAGMENT)
        sky = program(SceneShaders.SCREEN_VERTEX, SceneShaders.SKY_FRAGMENT)
        finish = program(SceneShaders.SCREEN_VERTEX, SceneShaders.FINISH_FRAGMENT)
        surfelProgram = program(SceneShaders.SURFEL_VERTEX, SceneShaders.SURFEL_FRAGMENT)
        splatFast = program(SceneShaders.splatVertex(exact = false), SceneShaders.splatFragment(exact = false))
        splatExact = program(SceneShaders.splatVertex(exact = true), SceneShaders.splatFragment(exact = true))
        splatShadow = program(SceneShaders.SPLAT_SHADOW_VERTEX, SceneShaders.SPLAT_SHADOW_FRAGMENT)
        emptyVao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        profile = AndroidDeviceProfiles.withGl(device)
        shadowFbo = 0
        sceneFbo = 0
        textureArray = 0
        settingsStale = true
        staticMeshes.clear()
        surfelBuffers.clear()
        splatBuffers.clear()
        locations.clear()
        listOf(actorMesh, cutoutMesh, decalMesh, glowMesh).forEach { it.vao = 0; it.source = null }
        // A new context has none of the old one's objects: queue the last art again.
        pendingTextures = pendingTextures ?: textures
        pendingMaps = pendingMaps ?: maps
        mapArray = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        createSceneTarget()
    }

    override fun onDrawFrame(gl: GL10?) {
        if (settingsStale) applySettings()
        pace()
        pendingTextures?.let { textures = it; uploadTextures(it); pendingTextures = null }
        pendingMaps?.let { maps = it; uploadMaps(it); pendingMaps = null }
        pending.getAndSet(null)?.let { newer ->
            // The last frame's per-frame batches were uploaded when it was
            // first drawn and nothing reads their arrays again; recycle them.
            current?.release()
            current = newer
        }
        val frame = current ?: run {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        releaseStale(frame)
        releaseStaleSurfels(frame)
        releaseStalePoints(splatBuffers, frame.residentSplats, staleSplats)
        shadowPass(frame)
        scenePass(frame)
        finishPass(frame)
    }

    /**
     * Settles on settings for this device and the player's choice, and builds
     * every target they size. Runs on the GL thread, where the device's limits
     * can be read.
     */
    private fun applySettings() {
        settingsStale = false
        settings = DeviceClassifier.settingsFor(profile, requested)
        governor = FrameGovernor(settings)
        createShadowMap()
        createSceneTarget()
        // Queued art is uploaded right after this, at the new size; only re-size what is already up.
        if (textures.isNotEmpty() && pendingTextures == null) uploadTextures(textures)
        Log.i(TAG, "Rendering at ${settings.tier} on ${profile.gpu.ifEmpty { "an unnamed GPU" }}")
        onSettings(settings)
    }

    /**
     * Feeds the governor the time between frames, which includes the GPU's
     * share because the swap waits on it, and resizes the scene target when it
     * trades resolution for time.
     */
    private fun pace() {
        val now = System.nanoTime()
        val millis = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / NANOS_PER_MILLI
        lastFrameNanos = now
        if (governor.record(millis)) createSceneTarget()
    }

    private fun shadowPass(frame: SceneFrame) {
        if (!settings.shadows) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glViewport(0, 0, shadowSize, shadowSize)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glColorMask(false, false, false, false)
        GLES30.glUseProgram(shadowProgram)
        matrix(shadowProgram, "uShadowViewProj", frame.shadowViewProjection)
        bindTextures(shadowProgram)
        GLES30.glUniform1i(loc(shadowProgram, "uCutout"), 0)
        drawOpaque(frame)
        if (frame.splats.isNotEmpty()) drawSplatShadows(frame)
        // Sprites do not cast into the shadow map: a camera-facing card seen
        // from the sun casts a sliver or a slab. They lay their own silhouette
        // on the ground as a decal instead (Vertex.SPRITE_SHADOW).
        GLES30.glColorMask(true, true, true, true)
    }

    private fun scenePass(frame: SceneFrame) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, sceneWidth, sceneHeight)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_COLOR_BUFFER_BIT)
        val l = frame.lighting

        // Sky, behind everything.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(sky)
        vec3(sky, "uTop", ShadingModel.rgb(l.skyTop, 1f))
        vec3(sky, "uBottom", ShadingModel.rgb(l.skyBottom, 1f))
        GLES30.glUniform1f(loc(sky, "uExposure"), l.exposure)
        fullScreen()

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glUseProgram(lit)
        val t = ShadingModel.Terms(l)
        lighting(lit, frame, t)
        GLES30.glUniform1f(loc(lit, "uFloorDetail"), t.floorDetail)
        GLES30.glUniform1f(loc(lit, "uFloorSaturation"), t.floorSaturation)
        vec3(lit, "uRim", t.rim)
        GLES30.glUniform1f(loc(lit, "uShadowSize"), shadowSize.coerceAtLeast(1).toFloat())
        bindTextures(lit)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowDepth)
        GLES30.glUniform1i(loc(lit, "uShadowMap"), 1)

        GLES30.glUniform1i(loc(lit, "uCutout"), 0)
        drawOpaque(frame)
        if (frame.splats.isNotEmpty()) {
            drawSplats(frame, t)
            GLES30.glUseProgram(lit)
        }
        // Surfels on the land they lie on: depth-tested, never depth-written, as the rasteriser draws them.
        if (frame.surfels.isNotEmpty()) {
            drawSurfels(frame, t)
            GLES30.glUseProgram(lit)
        }
        GLES30.glUniform1i(loc(lit, "uCutout"), 1)
        drawStreamed(frame.cutout, cutoutMesh)

        // Decals and glows test depth but never write it.
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glUseProgram(soft)
        matrix(soft, "uViewProj", frame.camera.viewProjection)
        bindTextures(soft)
        GLES30.glUniform1i(loc(soft, "uGlow"), 0)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        drawStreamed(frame.decals, decalMesh)
        GLES30.glUniform1i(loc(soft, "uGlow"), 1)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        drawStreamed(frame.glows, glowMesh)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
    }

    /**
     * The uniforms every lit program shares: camera, sun, sky, fog, lights,
     * and the per-surface half of the diorama finish.
     */
    private fun lighting(program: Int, frame: SceneFrame, t: ShadingModel.Terms) {
        val l = frame.lighting
        matrix(program, "uViewProj", frame.camera.viewProjection)
        matrix(program, "uShadowViewProj", frame.shadowViewProjection)
        val eye = frame.camera.eye
        GLES30.glUniform3f(loc(program, "uEye"), eye.x, eye.y, eye.z)
        vec3(program, "uSun", t.sun)
        vec3(program, "uFill", t.fill)
        GLES30.glUniform1f(loc(program, "uFillStrength"), t.fillStrength)
        vec3(program, "uSunColor", t.sunColor)
        vec3(program, "uSky", t.sky)
        vec3(program, "uGround", t.ground)
        vec3(program, "uFog", t.fog)
        GLES30.glUniform1f(loc(program, "uFogStart"), l.fogStart)
        GLES30.glUniform1f(loc(program, "uFogEnd"), l.fogEnd)
        GLES30.glUniform1f(loc(program, "uFogFloor"), l.fogFloor)
        GLES30.glUniform1f(loc(program, "uShadowStrength"), l.shadowStrength)
        GLES30.glUniform1f(loc(program, "uExposure"), l.exposure)
        GLES30.glUniform1i(loc(program, "uShadowTaps"), if (settings.shadows) settings.shadowTaps else 0)
        val count = minOf(frame.lights.size, SceneFrame.MAX_LIGHTS, settings.maxPointLights)
        GLES30.glUniform1i(loc(program, "uLightCount"), count)
        if (count > 0) {
            for (i in 0 until count) {
                val light = frame.lights[i]
                lightPos[i * 3] = light.x; lightPos[i * 3 + 1] = light.y; lightPos[i * 3 + 2] = light.z
                ShadingModel.rgb(light.color, light.strength).copyInto(lightCol, i * 3)
                lightRad[i] = light.radius
            }
            GLES30.glUniform3fv(loc(program, "uLightPos[0]"), count, lightPos, 0)
            GLES30.glUniform3fv(loc(program, "uLightColor[0]"), count, lightCol, 0)
            GLES30.glUniform1fv(loc(program, "uLightRadius[0]"), count, lightRad, 0)
        }
        val look = frame.look
        GLES30.glUniform1f(loc(program, "uGrain"), look.grain)
        GLES30.glUniform1f(loc(program, "uBevel"), look.bevel)
        GLES30.glUniform1f(loc(program, "uOcclusionDepth"), look.occlusionDepth)
        GLES30.glUniform1i(loc(program, "uHaze"), if (look.aerialHaze) 1 else 0)
        GLES30.glUniform1f(loc(program, "uGlowGain"), look.nightGlow * frame.night)
        val focus = frame.camera.target
        GLES30.glUniform3f(loc(program, "uFocus"), focus.x, focus.y, focus.z)
        GLES30.glUniform1f(loc(program, "uFocusDistance"), frame.camera.distance)
        // World size of one scene-target pixel at distance 1, for fading the bevels out where voxels shrink to a pixel.
        GLES30.glUniform1f(loc(program, "uPixelAngle"), 2f / (frame.camera.projection[5] * sceneHeight))
    }

    // ---- surfels -----------------------------------------------------------

    private class GpuPoints {
        var vao = 0
        var vbo = 0
    }

    /**
     * The frame's surfels as point sprites, a prefix of each chunk's
     * rank-sorted buffer (see [com.stratum.engine.scene.SurfelLod]): one
     * draw call per chunk, nothing uploaded once a chunk is resident.
     */
    private fun drawSurfels(frame: SceneFrame, t: ShadingModel.Terms) {
        val p = surfelProgram
        GLES30.glUseProgram(p)
        lighting(p, frame, t)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowDepth)
        GLES30.glUniform1i(loc(p, "uShadowMap"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val reveal = frame.reveal
        if (reveal != null) GLES30.glUniform4f(loc(p, "uReveal"), reveal.x, reveal.y, reveal.z, reveal.radius)
        else GLES30.glUniform4f(loc(p, "uReveal"), 0f, 0f, 0f, 0f)
        GLES30.glUniform1f(loc(p, "uSurfelRadius"), frame.look.surfelRadius.toFloat())
        GLES30.glUniform1f(loc(p, "uPixelsPerUnit"), sceneHeight * frame.camera.projection[5] / 2f)
        GLES30.glUniform1f(loc(p, "uMinPixels"), ShadingModel.SURFEL_MIN_PIXELS * governor.renderScale)
        GLES30.glUniform2f(loc(p, "uViewport"), sceneWidth.toFloat(), sceneHeight.toFloat())
        GLES30.glDepthMask(false)
        val draws = frame.surfels
        for (i in draws.indices) {
            val draw = draws[i]
            val batch = draw.batch
            val points = surfelBuffers[batch] ?: GpuPoints().also { uploadSurfels(it, batch); surfelBuffers[batch] = it }
            GLES30.glUniform2f(loc(p, "uOrigin"), batch.originX.toFloat(), batch.originY.toFloat())
            GLES30.glUniform1f(loc(p, "uKeep"), draw.keep)
            GLES30.glBindVertexArray(points.vao)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, draw.count)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
    }

    private fun uploadSurfels(points: GpuPoints, batch: SurfelBatch) {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        points.vao = ids[0]; points.vbo = ids[1]
        GLES30.glBindVertexArray(points.vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, points.vbo)
        val n = batch.count * Surfel.INTS
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 4, ints(batch.data, n), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        // Integer attributes: the packed bits reach the shader untouched.
        GLES30.glVertexAttribIPointer(0, 3, GLES30.GL_UNSIGNED_INT, Surfel.INTS * 4, 0)
        GLES30.glBindVertexArray(0)
    }

    /** Surfel buffers of chunks that were remeshed or left the meshed square. */
    private fun releaseStaleSurfels(frame: SceneFrame) {
        val resident = frame.residentSurfels
        if (surfelBuffers.isEmpty()) return
        var allLive = surfelBuffers.size <= resident.size
        if (allLive) {
            // Cheap common case: every buffer's batch is still resident. Identity lookups, no allocation.
            var live = 0
            for (i in resident.indices) if (surfelBuffers.containsKey(resident[i])) live++
            allLive = live == surfelBuffers.size
        }
        if (allLive) return
        val keep = java.util.Collections.newSetFromMap(IdentityHashMap<SurfelBatch, Boolean>()).apply { addAll(resident) }
        staleSurfels.clear()
        for (b in surfelBuffers.keys) if (b !in keep) staleSurfels += b
        for (b in staleSurfels) surfelBuffers.remove(b)?.let { points ->
            GLES30.glDeleteVertexArrays(1, intArrayOf(points.vao), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(points.vbo), 0)
        }
        staleSurfels.clear()
    }

    // ---- voxel splats ------------------------------------------------------

    /**
     * The terrain's voxel splats, one draw per chunk layer, depth-tested and
     * depth-written like the mesh they replace. See [SceneShaders.splatVertex].
     */
    private fun drawSplats(frame: SceneFrame, t: ShadingModel.Terms) {
        val exact = frame.splatMode == SplatMode.EXACT
        val p = if (exact) splatExact else splatFast
        GLES30.glUseProgram(p)
        lighting(p, frame, t)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowDepth)
        GLES30.glUniform1i(loc(p, "uShadowMap"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val reveal = frame.reveal
        if (reveal != null) GLES30.glUniform4f(loc(p, "uReveal"), reveal.x, reveal.y, reveal.z, reveal.radius)
        else GLES30.glUniform4f(loc(p, "uReveal"), 0f, 0f, 0f, 0f)
        GLES30.glUniform1f(loc(p, "uPixelsPerUnit"), sceneHeight * frame.camera.projection[5] / 2f)
        GLES30.glUniform2f(loc(p, "uViewport"), sceneWidth.toFloat(), sceneHeight.toFloat())
        // The land's lamps are baked into the splats; shade only the lights that move.
        GLES30.glUniform1i(loc(p, "uLightCount"), minOf(frame.dynamicLights, frame.lights.size, SceneFrame.MAX_LIGHTS, settings.maxPointLights))
        GLES30.glUniform1f(loc(p, "uLampGain"), frame.lighting.pointLightGain)
        if (exact) {
            android.opengl.Matrix.invertM(inverseViewProj, 0, frame.camera.viewProjection, 0)
            matrix(p, "uInverseViewProj", inverseViewProj)
        }
        for (batch in frame.splats) {
            val points = splatBuffers[batch] ?: GpuPoints().also { uploadSplats(it, batch); splatBuffers[batch] = it }
            GLES30.glUniform2f(loc(p, "uOrigin"), batch.originX.toFloat(), batch.originY.toFloat())
            GLES30.glUniform1f(loc(p, "uPerMicro"), 1f / batch.microPerBlock)
            GLES30.glBindVertexArray(points.vao)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, batch.count)
        }
        GLES30.glBindVertexArray(0)
    }

    /** Splats into the sun's depth map as squares; the same list the scene draws, which is already widened for shadows. */
    private fun drawSplatShadows(frame: SceneFrame) {
        val p = splatShadow
        GLES30.glUseProgram(p)
        val m = frame.shadowViewProjection
        matrix(p, "uShadowViewProj", m)
        // The sun's map is orthographic: pixels per world unit are the same everywhere in it.
        val rowX = kotlin.math.sqrt(m[0] * m[0] + m[4] * m[4] + m[8] * m[8])
        val rowY = kotlin.math.sqrt(m[1] * m[1] + m[5] * m[5] + m[9] * m[9])
        GLES30.glUniform1f(loc(p, "uShadowPixelsPerUnit"), maxOf(rowX, rowY) * shadowSize / 2f)
        for (batch in frame.splats) {
            val points = splatBuffers[batch] ?: GpuPoints().also { uploadSplats(it, batch); splatBuffers[batch] = it }
            GLES30.glUniform2f(loc(p, "uOrigin"), batch.originX.toFloat(), batch.originY.toFloat())
            GLES30.glUniform1f(loc(p, "uPerMicro"), 1f / batch.microPerBlock)
            GLES30.glBindVertexArray(points.vao)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, batch.count)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(shadowProgram)
    }

    private fun uploadSplats(points: GpuPoints, batch: SplatBatch) {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        points.vao = ids[0]; points.vbo = ids[1]
        GLES30.glBindVertexArray(points.vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, points.vbo)
        val n = batch.count * VoxelSplat.INTS
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 4, ints(batch.data, n), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribIPointer(0, VoxelSplat.INTS, GLES30.GL_UNSIGNED_INT, VoxelSplat.INTS * 4, 0)
        GLES30.glBindVertexArray(0)
    }

    /** Point buffers whose batch left [resident]: remeshed, or out of the meshed square. */
    private fun <K> releaseStalePoints(buffers: IdentityHashMap<K, GpuPoints>, resident: List<K>, stale: ArrayList<K>) {
        if (buffers.isEmpty()) return
        if (buffers.size <= resident.size) {
            var live = 0
            for (i in resident.indices) if (buffers.containsKey(resident[i])) live++
            if (live == buffers.size) return
        }
        val keep = java.util.Collections.newSetFromMap(IdentityHashMap<K, Boolean>()).apply { addAll(resident) }
        stale.clear()
        for (b in buffers.keys) if (b !in keep) stale += b
        for (b in stale) buffers.remove(b)?.let { points ->
            GLES30.glDeleteVertexArrays(1, intArrayOf(points.vao), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(points.vbo), 0)
        }
        stale.clear()
    }

    private fun finishPass(frame: SceneFrame) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(finish)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneColor)
        GLES30.glUniform1i(loc(finish, "uScene"), 0)
        val look = frame.look
        val depthFinish = sceneDepthIsTexture && look.readsDepth
        GLES30.glUniform1i(loc(finish, "uDepthFinish"), if (depthFinish) 1 else 0)
        if (depthFinish) {
            val cam = frame.camera
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneDepth)
            GLES30.glUniform1i(loc(finish, "uDepth"), 3)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glUniform2f(loc(finish, "uTexel"), 1f / sceneWidth, 1f / sceneHeight)
            GLES30.glUniform1f(loc(finish, "uNear"), cam.near)
            GLES30.glUniform1f(loc(finish, "uFar"), cam.far)
            GLES30.glUniform1f(loc(finish, "uEdges"), look.edges)
            GLES30.glUniform1f(loc(finish, "uScreenAo"), look.screenOcclusion)
            GLES30.glUniform1f(loc(finish, "uTiltShift"), look.tiltShift)
            GLES30.glUniform1f(loc(finish, "uFocusDepth"), cam.distance)
            GLES30.glUniform1f(loc(finish, "uPixelsPerUnit"), sceneHeight * cam.projection[5] / 2f)
        }
        GLES30.glUniform1f(loc(finish, "uExposure"), frame.lighting.exposure)
        GLES30.glUniform1f(loc(finish, "uSaturation"), frame.lighting.saturation)
        GLES30.glUniform1f(loc(finish, "uVignette"), frame.lighting.vignette)
        fullScreen()
    }

    // ---- geometry ----------------------------------------------------------

    private class GpuMesh {
        var vao = 0
        var vbo = 0
        var ibo = 0
        var count = 0
        /** The batch now in the buffers, for per-frame meshes that are drawn by more than one pass. */
        var source: MeshBatch? = null
    }

    /** Terrain from the chunk buffers already on the GPU; actor bodies streamed fresh. */
    private fun drawOpaque(frame: SceneFrame) {
        // The land and its models open up around the player; actors never do.
        frame.reveal?.let { GLES30.glUniform4f(loc(lit, "uReveal"), it.x, it.y, it.z, it.radius) }
        frame.terrain.forEach { draw(it, static = true) }
        // Model props change only with the terrain, so they are kept on the GPU like it.
        frame.models.forEach { draw(it, static = true) }
        GLES30.glUniform4f(loc(lit, "uReveal"), 0f, 0f, 0f, 0f)
        frame.actors?.let { drawStreamed(it, actorMesh) }
    }

    private fun draw(batch: MeshBatch, static: Boolean) {
        if (batch.isEmpty) return
        check(static) { "per-frame batches go through drawStreamed" }
        drawMesh(staticMeshes.getOrPut(batch) { GpuMesh().also { upload(it, batch, GLES30.GL_STATIC_DRAW) } })
    }

    /** A batch rebuilt every frame, uploaded the first time this frame draws it. */
    private fun drawStreamed(batch: MeshBatch, mesh: GpuMesh) {
        if (batch.isEmpty) return
        if (mesh.source !== batch) {
            upload(mesh, batch, GLES30.GL_STREAM_DRAW)
            mesh.source = batch
        }
        drawMesh(mesh)
    }

    private fun drawMesh(mesh: GpuMesh) {
        GLES30.glBindVertexArray(mesh.vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.count, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun upload(mesh: GpuMesh, batch: MeshBatch, usage: Int) {
        if (mesh.vao == 0) {
            val ids = IntArray(3)
            GLES30.glGenVertexArrays(1, ids, 0)
            GLES30.glGenBuffers(2, ids, 1)
            mesh.vao = ids[0]; mesh.vbo = ids[1]; mesh.ibo = ids[2]
        }
        GLES30.glBindVertexArray(mesh.vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, batch.vertexFloats * 4, floats(batch.vertices, batch.vertexFloats), usage)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, batch.indexCount * 4, ints(batch.indices, batch.indexCount), usage)
        val stride = Vertex.STRIDE * 4
        attribute(0, 3, Vertex.PX, stride)
        attribute(1, 3, Vertex.NX, stride)
        attribute(2, 3, Vertex.R, stride)
        attribute(3, 1, Vertex.AO, stride)
        attribute(4, 2, Vertex.U, stride)
        attribute(5, 1, Vertex.LAYER, stride)
        attribute(6, 1, Vertex.EMISSIVE, stride)
        attribute(7, 2, Vertex.VARIANT_A, stride)
        GLES30.glBindVertexArray(0)
        mesh.count = batch.indexCount
    }

    private fun attribute(index: Int, size: Int, offsetFloats: Int, stride: Int) {
        GLES30.glEnableVertexAttribArray(index)
        GLES30.glVertexAttribPointer(index, size, GLES30.GL_FLOAT, false, stride, offsetFloats * 4)
    }

    /** Chunk batches that were remeshed or left the view are freed once they stop being drawn. */
    private fun releaseStale(frame: SceneFrame) {
        val kept = frame.residentTerrain + frame.models
        if (staticMeshes.size == kept.size && kept.all(staticMeshes::containsKey)) return
        val live = java.util.Collections.newSetFromMap(IdentityHashMap<MeshBatch, Boolean>()).apply { addAll(kept) }
        val stale = staticMeshes.keys.filterNot(live::contains)
        stale.forEach { key ->
            staticMeshes.remove(key)?.let { mesh ->
                GLES30.glDeleteVertexArrays(1, intArrayOf(mesh.vao), 0)
                GLES30.glDeleteBuffers(2, intArrayOf(mesh.vbo, mesh.ibo), 0)
            }
        }
    }

    private fun fullScreen() {
        GLES30.glBindVertexArray(emptyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    // ---- targets and textures ---------------------------------------------

    /** The sun's depth map at the tier's size, or none at all when the tier draws no shadows. */
    private fun createShadowMap() {
        if (shadowFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(shadowFbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(shadowDepth), 0)
            shadowFbo = 0
            shadowDepth = 0
        }
        shadowSize = settings.shadowMapSize
        if (!settings.shadows) return
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        shadowDepth = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowDepth)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT32F, shadowSize, shadowSize, 0,
            GLES30.GL_DEPTH_COMPONENT, GLES30.GL_FLOAT, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenFramebuffers(1, ids, 0)
        shadowFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowDepth, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * The high-range colour target.
     *
     * Half-float where the device can render to it and the tier wants it;
     * eight-bit otherwise, which still works and merely loses the brightest
     * highlights to clipping before the finishing pass sees them. Sized by the
     * governor's render scale, and stretched to the screen by the finishing
     * pass's linear filter.
     */
    private fun createSceneTarget() {
        sceneWidth = (width * governor.renderScale).toInt().coerceAtLeast(1)
        sceneHeight = (height * governor.renderScale).toInt().coerceAtLeast(1)
        if (sceneFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(sceneColor), 0)
            if (sceneDepthIsTexture) GLES30.glDeleteTextures(1, intArrayOf(sceneDepth), 0)
            else GLES30.glDeleteRenderbuffers(1, intArrayOf(sceneDepth), 0)
        }
        val halfFloat = settings.highRange
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        sceneColor = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneColor)
        if (halfFloat) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, sceneWidth, sceneHeight, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        } else {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, sceneWidth, sceneHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        // The diorama's depth finish reads the scene's depth, so those tiers
        // keep it in a texture; the rest keep the cheaper renderbuffer.
        sceneDepthIsTexture = settings.diorama.readsDepth
        if (sceneDepthIsTexture) {
            GLES30.glGenTextures(1, ids, 0)
            sceneDepth = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneDepth)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, sceneWidth, sceneHeight, 0,
                GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null,
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        } else {
            GLES30.glGenRenderbuffers(1, ids, 0)
            sceneDepth = ids[0]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, sceneDepth)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, sceneWidth, sceneHeight)
        }
        GLES30.glGenFramebuffers(1, ids, 0)
        sceneFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, sceneColor, 0)
        if (sceneDepthIsTexture) GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, sceneDepth, 0)
        else GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, sceneDepth)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Every texture in one array, each resampled to a common size.
     *
     * An array rather than an atlas so painted tiles can repeat across a whole
     * hillside with plain hardware wrapping; resampled because array layers
     * must match, and a stretched layer drawn back onto a quad of the right
     * shape comes out unstretched.
     */
    private fun uploadTextures(textures: List<Texture>) {
        if (textureArray != 0) GLES30.glDeleteTextures(1, intArrayOf(textureArray), 0)
        val fitting = textures.take(maxArrayLayers())
        if (fitting.size < textures.size) Log.w(TAG, "Drawing ${fitting.size} of ${textures.size} textures; the rest are untextured")
        val size = TextureBudget.layerSize(fitting.size, settings.textureBudgetBytes, settings.maxTextureSize)
        textureArray = uploadArray(fitting, size, TextureBudget.mipLevels(size))
        hasTextures = fitting.isNotEmpty()
    }

    /** GLES 3.0 promises 256 array layers; most devices allow 2048. */
    private fun maxArrayLayers(): Int {
        val limit = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS, limit, 0)
        return limit[0].takeIf { it > 0 } ?: MIN_ARRAY_LAYERS
    }

    /** Ground maps: a second, larger array, bound beside the first. See TextureLibrary.allMaps. */
    private fun uploadMaps(maps: List<Texture>) {
        if (mapArray != 0) GLES30.glDeleteTextures(1, intArrayOf(mapArray), 0)
        mapArray = if (maps.isEmpty()) 0 else uploadArray(maps, MAP_SIZE, MAP_MIP_LEVELS)
    }

    private fun uploadArray(textures: List<Texture>, size: Int, mips: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, ids[0])
        val layers = textures.size.coerceAtLeast(1)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, mips, GLES30.GL_RGBA8, size, size, layers)
        textures.forEachIndexed { layer, texture ->
            val source = Bitmap.createBitmap(texture.argb, texture.width, texture.height, Bitmap.Config.ARGB_8888)
            val scaled = Bitmap.createScaledBitmap(source, size, size, true)
            val pixels = IntArray(size * size)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            // ARGB ints to RGBA bytes.
            val bytes = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder())
            pixels.forEach { c ->
                bytes.put(((c shr 16) and 0xFF).toByte()); bytes.put(((c shr 8) and 0xFF).toByte())
                bytes.put((c and 0xFF).toByte()); bytes.put(((c ushr 24) and 0xFF).toByte())
            }
            bytes.position(0)
            GLES30.glTexSubImage3D(
                GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, size, size, 1,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes,
            )
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D_ARRAY)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        return ids[0]
    }

    private fun bindTextures(program: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureArray)
        GLES30.glUniform1i(loc(program, "uTextures"), 0)
        // Unit 2: the shadow map has unit 1. With no maps the sampler points
        // at the tile array, which no map layer ever reads.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, if (mapArray != 0) mapArray else textureArray)
        GLES30.glUniform1i(loc(program, "uMaps"), 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    // ---- plumbing ----------------------------------------------------------

    private fun program(vertex: String, fragment: String): Int {
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, shader(GLES30.GL_VERTEX_SHADER, vertex))
        GLES30.glAttachShader(program, shader(GLES30.GL_FRAGMENT_SHADER, fragment))
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "link failed: ${GLES30.glGetProgramInfoLog(program)}")
        return program
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source.trimIndent())
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
        return shader
    }

    // Per program, then per name: a pair key allocated one object per uniform per frame.
    private val locations = HashMap<Int, HashMap<String, Int>>()

    private fun loc(program: Int, name: String): Int =
        locations.getOrPut(program) { HashMap() }.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun matrix(program: Int, name: String, m: FloatArray) =
        GLES30.glUniformMatrix4fv(loc(program, name), 1, false, m, 0)

    private fun vec3(program: Int, name: String, v: FloatArray) =
        GLES30.glUniform3f(loc(program, name), v[0], v[1], v[2])

    private fun floats(data: FloatArray, count: Int): FloatBuffer {
        if (floatStaging.capacity() < count) {
            floatStaging = ByteBuffer.allocateDirect(grown(count) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        return floatStaging.apply { clear(); put(data, 0, count); flip() }
    }

    private fun ints(data: IntArray, count: Int): IntBuffer {
        if (intStaging.capacity() < count) {
            intStaging = ByteBuffer.allocateDirect(grown(count) * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        }
        return intStaging.apply { clear(); put(data, 0, count); flip() }
    }

    /** Room for this and a little more, so a batch growing by a few quads a frame does not reallocate each time. */
    private fun grown(needed: Int): Int = needed + needed / 4

    private companion object {
        const val TAG = "SceneGl"
        const val NANOS_PER_MILLI = 1_000_000f
        const val MIN_ARRAY_LAYERS = 256
        /** Sixteen blocks of ground at 64 texels a block. */
        const val MAP_SIZE = 1024
        const val MAP_MIP_LEVELS = 11
    }
}
