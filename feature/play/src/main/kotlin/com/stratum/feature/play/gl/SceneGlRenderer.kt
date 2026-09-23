package com.stratum.feature.play.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.stratum.engine.scene.MaterialKind
import com.stratum.engine.scene.MeshBatch
import com.stratum.engine.scene.SceneFrame
import com.stratum.engine.scene.ShadingModel
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.Vertex
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
 * the world. Terrain buffers are cached by the identity of their [MeshBatch],
 * which the scene builder reuses until the world changes.
 */
class SceneGlRenderer : GLSurfaceView.Renderer {

    @Volatile private var pending: SceneFrame? = null
    @Volatile private var pendingTextures: List<Texture>? = null

    private var width = 1
    private var height = 1

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
    private var hasTextures = false
    private var emptyVao = 0

    private val staticMeshes = IdentityHashMap<MeshBatch, GpuMesh>()
    private val dynamicMesh = GpuMesh()

    /** Hands a new frame to the GL thread. Cheap; call as often as the world changes. */
    fun submit(frame: SceneFrame) {
        pending = frame
    }

    /** Replaces the texture array. Layer order must match the scene's [com.stratum.engine.scene.TextureLibrary]. */
    fun submitTextures(textures: List<Texture>) {
        pendingTextures = textures
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        lit = program(SceneShaders.LIT_VERTEX, SceneShaders.LIT_FRAGMENT)
        soft = program(SceneShaders.SOFT_VERTEX, SceneShaders.SOFT_FRAGMENT)
        shadowProgram = program(SceneShaders.SHADOW_VERTEX, SceneShaders.SHADOW_FRAGMENT)
        sky = program(SceneShaders.SCREEN_VERTEX, SceneShaders.SKY_FRAGMENT)
        finish = program(SceneShaders.SCREEN_VERTEX, SceneShaders.FINISH_FRAGMENT)
        emptyVao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        createShadowMap()
        staticMeshes.clear()
        pendingTextures = pendingTextures ?: emptyList()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        createSceneTarget()
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingTextures?.let { uploadTextures(it); pendingTextures = null }
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

    private fun shadowPass(frame: SceneFrame) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glViewport(0, 0, SHADOW_SIZE, SHADOW_SIZE)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glColorMask(false, false, false, false)
        GLES30.glUseProgram(shadowProgram)
        matrix(shadowProgram, "uShadowViewProj", frame.shadowViewProjection)
        bindTextures(shadowProgram)
        GLES30.glUniform1i(loc(shadowProgram, "uCutout"), 0)
        frame.opaque.forEach { draw(it, static = it === frame.opaque.first()) }
        GLES30.glUniform1i(loc(shadowProgram, "uCutout"), 1)
        draw(frame.cutout, static = false)
        GLES30.glColorMask(true, true, true, true)
    }

    private fun scenePass(frame: SceneFrame) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, width, height)
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
        GLES30.glUniform1f(loc(lit, "uShadowSize"), SHADOW_SIZE.toFloat())
        val lights = frame.lights.take(SceneFrame.MAX_LIGHTS)
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
        frame.opaque.forEach { draw(it, static = it === frame.opaque.first()) }
        GLES30.glUniform1i(loc(lit, "uCutout"), 1)
        draw(frame.cutout, static = false)

        // Decals and glows test depth but never write it.
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glUseProgram(soft)
        matrix(soft, "uViewProj", frame.camera.viewProjection)
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
        GLES30.glBindVertexArray(0)
        mesh.count = batch.indices.size
    }

    private fun attribute(index: Int, size: Int, offsetFloats: Int, stride: Int) {
        GLES30.glEnableVertexAttribArray(index)
        GLES30.glVertexAttribPointer(index, size, GLES30.GL_FLOAT, false, stride, offsetFloats * 4)
    }

    /** Terrain batches from earlier world revisions are freed once they stop being drawn. */
    private fun releaseStale(frame: SceneFrame) {
        val live = frame.opaque.firstOrNull()
        val stale = staticMeshes.keys.filter { it !== live }
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

    private fun createShadowMap() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        shadowDepth = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowDepth)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT32F, SHADOW_SIZE, SHADOW_SIZE, 0,
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
     * Half-float where the device can render to it, which is nearly all of
     * them; eight-bit otherwise, which still works and merely loses the
     * brightest highlights to clipping before the finishing pass sees them.
     */
    private fun createSceneTarget() {
        if (sceneFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(sceneColor), 0)
            GLES30.glDeleteRenderbuffers(1, intArrayOf(sceneDepth), 0)
        }
        val halfFloat = GLES30.glGetString(GLES30.GL_EXTENSIONS)?.contains("GL_EXT_color_buffer_half_float") == true
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        sceneColor = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneColor)
        if (halfFloat) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        } else {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glGenRenderbuffers(1, ids, 0)
        sceneDepth = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, sceneDepth)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
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
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureArray = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureArray)
        val layers = textures.size.coerceAtLeast(1)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, MIP_LEVELS, GLES30.GL_RGBA8, LAYER_SIZE, LAYER_SIZE, layers)
        textures.forEachIndexed { layer, texture ->
            val source = Bitmap.createBitmap(texture.argb, texture.width, texture.height, Bitmap.Config.ARGB_8888)
            val scaled = Bitmap.createScaledBitmap(source, LAYER_SIZE, LAYER_SIZE, true)
            val pixels = IntArray(LAYER_SIZE * LAYER_SIZE)
            scaled.getPixels(pixels, 0, LAYER_SIZE, 0, 0, LAYER_SIZE, LAYER_SIZE)
            // ARGB ints to RGBA bytes.
            val bytes = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder())
            pixels.forEach { c ->
                bytes.put(((c shr 16) and 0xFF).toByte()); bytes.put(((c shr 8) and 0xFF).toByte())
                bytes.put((c and 0xFF).toByte()); bytes.put(((c ushr 24) and 0xFF).toByte())
            }
            bytes.position(0)
            GLES30.glTexSubImage3D(
                GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, LAYER_SIZE, LAYER_SIZE, 1,
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
        hasTextures = textures.isNotEmpty()
    }

    private fun bindTextures(program: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureArray)
        GLES30.glUniform1i(loc(program, "uTextures"), 0)
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
        const val SHADOW_SIZE = 2048
        const val LAYER_SIZE = 512
        const val MIP_LEVELS = 10
    }
}
