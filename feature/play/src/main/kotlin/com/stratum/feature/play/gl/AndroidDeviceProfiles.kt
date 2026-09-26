package com.stratum.feature.play.gl

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES30
import com.stratum.engine.scene.quality.DeviceProfile

/**
 * Gathers a [DeviceProfile] from Android: memory and cores from the OS, GL
 * limits and the GPU's name from a live context. Judging it is not done here;
 * see [com.stratum.engine.scene.quality.DeviceClassifier].
 */
object AndroidDeviceProfiles {

    /** What is known before a GL context exists. GL fields are optimistic until [withGl] fills them in. */
    fun fromContext(context: Context): DeviceProfile {
        val activities = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activities.getMemoryInfo(it) }
        return DeviceProfile(
            totalMemoryMb = (memory.totalMem / BYTES_PER_MB).toInt(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            maxTextureSize = ASSUMED_TEXTURE_SIZE,
            canRenderHalfFloat = true,
            isLowRamDevice = activities.isLowRamDevice,
        )
    }

    /** Adds what only the GL context knows. Call on the GL thread. */
    fun withGl(base: DeviceProfile): DeviceProfile {
        val limit = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, limit, 0)
        return base.copy(
            maxTextureSize = limit[0].takeIf { it > 0 } ?: base.maxTextureSize,
            canRenderHalfFloat = GLES30.glGetString(GLES30.GL_EXTENSIONS)?.contains("GL_EXT_color_buffer_half_float") == true,
            gpu = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty(),
        )
    }

    private const val BYTES_PER_MB = 1024L * 1024
    private const val ASSUMED_TEXTURE_SIZE = 4096
}
