package com.stratum.tools.artpreview

import com.stratum.engine.scene.Mat4
import com.stratum.engine.scene.SceneFrame
import com.stratum.engine.scene.ShadingModel
import com.stratum.engine.scene.VoxelSplat
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One frame's voxel splats and every uniform the splat shaders read, for
 * `tools/splatgl`: a page that draws them through the game's own GLSL in a
 * real GPU context (WebGL 2 speaks the same GLSL ES 3.00), so the shaders
 * are shown working, not only their CPU twin.
 *
 * Writes `scene.json` (uniforms, and each batch's origin and range) and
 * `splats.bin` (every batch's sixteen-byte splats, little-endian, back to back).
 */
object SplatGpuDump {
    fun write(dir: File, frame: SceneFrame, width: Int, height: Int) {
        dir.mkdirs()
        val t = ShadingModel.Terms(frame.lighting)
        val total = frame.splats.sumOf { it.count }
        val buf = ByteBuffer.allocate(total * VoxelSplat.INTS * 4).order(ByteOrder.LITTLE_ENDIAN)
        val batches = StringBuilder()
        var offset = 0
        for (b in frame.splats) {
            for (i in 0 until b.count * VoxelSplat.INTS) buf.putInt(b.data[i])
            if (batches.isNotEmpty()) batches.append(",\n    ")
            batches.append("""{"ox": ${b.originX}, "oy": ${b.originY}, "perMicro": ${1f / b.microPerBlock}, "first": $offset, "count": ${b.count}}""")
            offset += b.count
        }
        DataOutputStream(File(dir, "splats.bin").outputStream().buffered()).use { it.write(buf.array()) }

        fun arr(a: FloatArray) = a.joinToString(", ", "[", "]")
        val cam = frame.camera
        val eye = cam.eye
        val look = frame.look
        val lights = frame.lights.take(minOf(frame.dynamicLights, SceneFrame.MAX_LIGHTS))
        val json = """{
  "width": $width, "height": $height,
  "viewProj": ${arr(cam.viewProjection)},
  "inverseViewProj": ${arr(Mat4.invert(cam.viewProjection)!!)},
  "pixelsPerUnit": ${height * cam.projection[5] / 2f},
  "eye": [${eye.x}, ${eye.y}, ${eye.z}],
  "sun": ${arr(t.sun)}, "fill": ${arr(t.fill)}, "fillStrength": ${t.fillStrength},
  "sunColor": ${arr(t.sunColor)}, "sky": ${arr(t.sky)}, "ground": ${arr(t.ground)}, "fog": ${arr(t.fog)},
  "fogStart": ${t.fogStart}, "fogEnd": ${t.fogEnd}, "fogFloor": ${t.fogFloor},
  "shadowStrength": ${t.shadowStrength}, "exposure": ${t.exposure}, "toneGain": ${ShadingModel.TONE_GAIN},
  "skyTop": ${arr(ShadingModel.rgb(frame.lighting.skyTop, 1f))}, "skyBottom": ${arr(ShadingModel.rgb(frame.lighting.skyBottom, 1f))},
  "lights": [${lights.joinToString(", ") { l -> """{"pos": [${l.x}, ${l.y}, ${l.z}], "color": ${arr(ShadingModel.rgb(l.color, l.strength))}, "radius": ${l.radius}}""" }}],
  "grain": ${look.grain}, "occlusionDepth": ${look.occlusionDepth}, "haze": ${look.aerialHaze}, "glowGain": ${look.nightGlow * frame.night},
  "focus": [${cam.target.x}, ${cam.target.y}, ${cam.target.z}], "focusDistance": ${cam.distance},
  "pixelAngle": ${2f / (cam.projection[5] * height)},
  "lampGain": ${frame.lighting.pointLightGain},
  "splats": $total,
  "batches": [
    $batches
  ]
}
"""
        File(dir, "scene.json").writeText(json)
    }
}
