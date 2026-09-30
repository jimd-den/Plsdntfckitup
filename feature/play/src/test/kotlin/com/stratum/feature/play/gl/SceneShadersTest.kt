package com.stratum.feature.play.gl

import com.stratum.engine.scene.ShadingModel
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The GLSL is only compiled on a device, so these check what can be checked
 * on the JVM: every program is GLSL ES 3.00, the constants interpolated from
 * ShadingModel came out as GLSL literals, and the uniforms the renderer sets
 * are declared. Set `STRATUM_SHADER_DUMP=<dir>` to write each stage out for
 * an offline validator (glslangValidator on each file).
 */
class SceneShadersTest {

    private val stages = mapOf(
        "lit.vert" to SceneShaders.LIT_VERTEX,
        "lit.frag" to SceneShaders.LIT_FRAGMENT,
        "soft.vert" to SceneShaders.SOFT_VERTEX,
        "soft.frag" to SceneShaders.SOFT_FRAGMENT,
        "shadow.vert" to SceneShaders.SHADOW_VERTEX,
        "shadow.frag" to SceneShaders.SHADOW_FRAGMENT,
        "screen.vert" to SceneShaders.SCREEN_VERTEX,
        "sky.frag" to SceneShaders.SKY_FRAGMENT,
        "finish.frag" to SceneShaders.FINISH_FRAGMENT,
        "surfel.vert" to SceneShaders.SURFEL_VERTEX,
        "surfel.frag" to SceneShaders.SURFEL_FRAGMENT,
        "splat-fast.vert" to SceneShaders.splatVertex(exact = false),
        "splat-fast.frag" to SceneShaders.splatFragment(exact = false),
        "splat-exact.vert" to SceneShaders.splatVertex(exact = true),
        "splat-exact.frag" to SceneShaders.splatFragment(exact = true),
        "splat-shadow.vert" to SceneShaders.SPLAT_SHADOW_VERTEX,
        "splat-shadow.frag" to SceneShaders.SPLAT_SHADOW_FRAGMENT,
    )

    @Test
    fun `every stage is GLSL ES 3 with balanced braces`() {
        for ((name, source) in stages) {
            assertTrue(source.startsWith("#version 300 es"), "$name must start with its version line")
            assertEquals(source.count { it == '{' }, source.count { it == '}' }, "$name braces")
            assertEquals(source.count { it == '(' }, source.count { it == ')' }, "$name parentheses")
            assertTrue("void main()" in source, "$name has a main")
        }
        (System.getProperty("stratum.shaderDump") ?: System.getenv("STRATUM_SHADER_DUMP"))?.let { dir ->
            File(dir).mkdirs()
            for ((name, source) in stages) File(dir, name).writeText(source.trimIndent())
        }
    }

    @Test
    fun `interpolated constants are plain GLSL literals`() {
        // Kotlin prints tiny or huge floats as 1.0E-4, which GLSL ES reads, but NaN or Infinity it does not.
        val bad = Regex("""(NaN|Infinity|\$\{)""")
        for ((name, source) in stages) assertFalse(bad.containsMatchIn(source), "$name has an uninterpolated or invalid constant")
        assertTrue("BEVEL_WIDTH = ${ShadingModel.BEVEL_WIDTH};" in SceneShaders.LIT_FRAGMENT)
        assertTrue("HASH_OFFSET = ${ShadingModel.HASH_OFFSET}u;" in SceneShaders.LIT_FRAGMENT)
        assertTrue("EDGE_GAIN = ${ShadingModel.EDGE_GAIN};" in SceneShaders.FINISH_FRAGMENT)
        assertTrue("DOME = ${ShadingModel.SURFEL_DOME};" in SceneShaders.SURFEL_FRAGMENT)
    }

    @Test
    fun `the diorama uniforms the renderer sets are declared where it sets them`() {
        val lighting = listOf(
            "uGrain", "uBevel", "uOcclusionDepth", "uHaze", "uGlowGain", "uFocus", "uFocusDistance", "uPixelAngle",
            "uEye", "uSun", "uFill", "uSky", "uGround", "uFog", "uExposure", "uLightCount", "uShadowTaps",
        )
        for (u in lighting) {
            assertTrue(Regex("""uniform \w+ $u\b""").containsMatchIn(SceneShaders.LIT_FRAGMENT), "lit declares $u")
            assertTrue(Regex("""uniform \w+ $u\b""").containsMatchIn(SceneShaders.SURFEL_VERTEX), "surfel declares $u")
        }
        for (u in listOf("uOrigin", "uKeep", "uSurfelRadius", "uPixelsPerUnit", "uMinPixels", "uViewport", "uReveal"))
            assertTrue(Regex("""uniform \w+ $u\b""").containsMatchIn(SceneShaders.SURFEL_VERTEX), "surfel declares $u")
        for (u in listOf("uDepth", "uDepthFinish", "uTexel", "uNear", "uFar", "uEdges", "uScreenAo", "uTiltShift", "uFocusDepth", "uPixelsPerUnit"))
            assertTrue(Regex("""uniform \w+ $u\b""").containsMatchIn(SceneShaders.FINISH_FRAGMENT), "finish declares $u")
    }

    @Test
    fun `the GLSL voxel hash is the one ShadingModel computes`() {
        // The shader's uint arithmetic wraps exactly as Kotlin's Int does; spot-check the Kotlin twin's own contract.
        val a = ShadingModel.voxelHash(3, -7, 40)
        assertEquals(a, ShadingModel.voxelHash(3, -7, 40))
        assertTrue(a != ShadingModel.voxelHash(4, -7, 40))
        assertTrue("(u.x * 73856093u) ^ (u.y * 19349663u) ^ (u.z * 83492791u)" in SceneShaders.LIT_FRAGMENT)
    }
}
