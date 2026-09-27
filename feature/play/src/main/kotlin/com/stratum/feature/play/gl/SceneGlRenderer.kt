package com.stratum.feature.play.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.stratum.engine.scene.MeshBatch
import com.stratum.engine.scene.SceneFrame
import com.stratum.engine.scene.ShadingModel
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

    @Volatile private var pending: SceneFrame? = null
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

    private var shadowFbo = 0
    private var shadowDepth = 0
    private var sceneFbo = 0
    private var sceneColor = 0
    private var sceneDepth = 0
    private var textureArray = 0
    private var mapArray = 0
    private var hasTextures = false
    private var emptyVao = 0

    private val staticMeshes = IdentityHashMap<MeshBatch, GpuMesh>()
    private val dynamicMesh = GpuMesh()

    /** Hands a new frame to the GL thread. Cheap; call as often as the world changes. */
    fun submit(frame: SceneFrame) {
        pending = frame
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
        emptyVao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        profile = AndroidDeviceProfiles.withGl(device)
        shadowFbo = 0
        sceneFbo = 0
        textureArray = 0
        settingsStale = true
        staticMeshes.clear()
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
        val frame = pending ?: run {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        releaseStale(frame)
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
        matrix(lit, "uViewProj", frame.camera.viewProjection)
        matrix(lit, "uShadowViewProj", frame.shadowViewProjection)
        val eye = frame.camera.eye
        GLES30.glUniform3f(loc(lit, "uEye"), eye.x, eye.y, eye.z)
        vec3(lit, "uSun", t.sun)
        vec3(lit, "uFill", t.fill)
        GLES30.glUniform1f(loc(lit, "uFillStrength"), t.fillStrength)
        GLES30.glUniform1f(loc(lit, "uFloorDetail"), t.floorDetail)
        GLES30.glUniform1f(loc(lit, "uFloorSaturation"), t.floorSaturation)
        vec3(lit, "uSunColor", t.sunColor)
        vec3(lit, "uSky", t.sky)
        vec3(lit, "uGround", t.ground)
        vec3(lit, "uFog", t.fog)
        vec3(lit, "uRim", t.rim)
        GLES30.glUniform1f(loc(lit, "uFogStart"), l.fogStart)
        GLES30.glUniform1f(loc(lit, "uFogEnd"), l.fogEnd)
        GLES30.glUniform1f(loc(lit, "uFogFloor"), l.fogFloor)
        GLES30.glUniform1f(loc(lit, "uShadowStrength"), l.shadowStrength)
        GLES30.glUniform1f(loc(lit, "uExposure"), l.exposure)
        GLES30.glUniform1f(loc(lit, "uShadowSize"), shadowSize.coerceAtLeast(1).toFloat())
        GLES30.glUniform1i(loc(lit, "uShadowTaps"), if (settings.shadows) settings.shadowTaps else 0)
        val lights = frame.lights.take(minOf(SceneFrame.MAX_LIGHTS, settings.maxPointLights))
        GLES30.glUniform1i(loc(lit, "uLightCount"), lights.size)
        if (lights.isNotEmpty()) {
            val pos = FloatArray(lights.size * 3); val col = FloatArray(lights.size * 3); val rad = FloatArray(lights.size)
            lights.forEachIndexed { i, light ->
                pos[i * 3] = light.x; pos[i * 3 + 1] = light.y; pos[i * 3 + 2] = light.z
                ShadingModel.rgb(light.color, light.strength).copyInto(col, i * 3)
                rad[i] = light.radius
            }
            GLES30.glUniform3fv(loc(lit, "uLightPos[0]"), lights.size, pos, 0)
            GLES30.glUniform3fv(loc(lit, "uLightColor[0]"), lights.size, col, 0)
            GLES30.glUniform1fv(loc(lit, "uLightRadius[0]"), lights.size, rad, 0)
        }
        bindTextures(lit)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowDepth)
        GLES30.glUniform1i(loc(lit, "uShadowMap"), 1)

        GLES30.glUniform1i(loc(lit, "uCutout"), 0)
        drawOpaque(frame)
        GLES30.glUniform1i(loc(lit, "uCutout"), 1)
        draw(frame.cutout, static = false)

        // Decals and glows test depth but never write it.
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glUseProgram(soft)
        matrix(soft, "uViewProj", frame.camera.viewProjection)
        bindTextures(soft)
        GLES30.glUniform1i(loc(soft, "uGlow"), 0)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        draw(frame.decals, static = false)
        GLES30.glUniform1i(loc(soft, "uGlow"), 1)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        draw(frame.glows, static = false)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
    }

    private fun finishPass(frame: SceneFrame) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(finish)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneColor)
        GLES30.glUniform1i(loc(finish, "uScene"), 0)
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
    }

    /** Terrain from the chunk buffers already on the GPU; actor bodies streamed fresh. */
    private fun drawOpaque(frame: SceneFrame) {
        frame.terrain.forEach { draw(it, static = true) }
        frame.actors?.let { draw(it, static = false) }
    }

    private fun draw(batch: MeshBatch, static: Boolean) {
        if (batch.indices.isEmpty()) return
        val mesh = if (static) staticMeshes.getOrPut(batch) { GpuMesh().also { upload(it, batch, GLES30.GL_STATIC_DRAW) } }
        else dynamicMesh.also { upload(it, batch, GLES30.GL_STREAM_DRAW) }
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
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, batch.vertices.size * 4, floats(batch.vertices), usage)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, batch.indices.size * 4, ints(batch.indices), usage)
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
        mesh.count = batch.indices.size
    }

    private fun attribute(index: Int, size: Int, offsetFloats: Int, stride: Int) {
        GLES30.glEnableVertexAttribArray(index)
        GLES30.glVertexAttribPointer(index, size, GLES30.GL_FLOAT, false, stride, offsetFloats * 4)
    }

    /** Chunk batches that were remeshed or left the view are freed once they stop being drawn. */
    private fun releaseStale(frame: SceneFrame) {
        if (staticMeshes.size == frame.terrain.size && frame.terrain.all(staticMeshes::containsKey)) return
        val live = java.util.Collections.newSetFromMap(IdentityHashMap<MeshBatch, Boolean>()).apply { addAll(frame.terrain) }
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
            GLES30.glDeleteRenderbuffers(1, intArrayOf(sceneDepth), 0)
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
        GLES30.glGenRenderbuffers(1, ids, 0)
        sceneDepth = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, sceneDepth)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, sceneWidth, sceneHeight)
        GLES30.glGenFramebuffers(1, ids, 0)
        sceneFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, sceneColor, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, sceneDepth)
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

    private val locations = HashMap<Pair<Int, String>, Int>()

    private fun loc(program: Int, name: String): Int =
        locations.getOrPut(program to name) { GLES30.glGetUniformLocation(program, name) }

    private fun matrix(program: Int, name: String, m: FloatArray) =
        GLES30.glUniformMatrix4fv(loc(program, name), 1, false, m, 0)

    private fun vec3(program: Int, name: String, v: FloatArray) =
        GLES30.glUniform3f(loc(program, name), v[0], v[1], v[2])

    private fun floats(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }

    private fun ints(data: IntArray): IntBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(data); position(0) }

    private companion object {
        const val TAG = "SceneGl"
        const val NANOS_PER_MILLI = 1_000_000f
        const val MIN_ARRAY_LAYERS = 256
        /** Sixteen blocks of ground at 64 texels a block. */
        const val MAP_SIZE = 1024
        const val MAP_MIP_LEVELS = 11
    }
}
