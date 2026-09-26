package com.stratum.engine.scene.quality

/**
 * What a device can do, as far as the renderer cares. Gathered by the
 * platform (memory from the OS, limits from the GL context) and judged here,
 * so the judgement is the same code on every platform and can be tested.
 */
data class DeviceProfile(
    val totalMemoryMb: Int,
    val cpuCores: Int,
    /** GL_MAX_TEXTURE_SIZE. */
    val maxTextureSize: Int,
    /** Whether a half-float colour target can be rendered to (EXT_color_buffer_half_float). */
    val canRenderHalfFloat: Boolean,
    /** The OS's own verdict, e.g. Android Go devices. */
    val isLowRamDevice: Boolean = false,
    /** GL_RENDERER, e.g. "Mali-G78" or "Adreno (TM) 506". */
    val gpu: String = "",
)

/**
 * Picks a starting tier for a device.
 *
 * A starting point, not a verdict: the frame governor corrects it as the
 * game actually runs, and the player can always choose another. Memory
 * decides most of it, because it is what runs out first on a cheap phone --
 * textures, meshes and chunks all live there -- and GPUs known to be weak are
 * named, because a phone can have plenty of memory and very little GPU.
 */
object DeviceClassifier {

    fun tierFor(device: DeviceProfile): QualityTier = when {
        device.isLowRamDevice || device.totalMemoryMb < LOW_MEMORY_MB || device.cpuCores <= 4 || isWeakGpu(device.gpu) -> QualityTier.LOW
        device.totalMemoryMb < MEDIUM_MEMORY_MB || !device.canRenderHalfFloat || isMidGpu(device.gpu) -> QualityTier.MEDIUM
        device.totalMemoryMb >= ULTRA_MEMORY_MB && device.cpuCores >= 8 && device.maxTextureSize >= ULTRA_TEXTURE -> QualityTier.ULTRA
        else -> QualityTier.HIGH
    }

    /** The settings to start with: the tier's, fitted to what the device can do. */
    fun settingsFor(device: DeviceProfile, chosen: QualityTier? = null): RenderSettings =
        RenderSettings.of(chosen ?: tierFor(device)).fittedTo(device)

    /** Adreno 3xx/4xx/50x, Mali-4xx/T6xx/T7xx/G31/G51/G52, PowerVR SGX/GE8xxx. */
    private fun isWeakGpu(gpu: String): Boolean = WEAK.any { it.containsMatchIn(gpu) }

    /** Adreno 51x-61x, Mali-G57/G68/G71/G72/G76. */
    private fun isMidGpu(gpu: String): Boolean = MID.any { it.containsMatchIn(gpu) }

    private val WEAK = listOf(
        Regex("Adreno \\(TM\\) (3\\d\\d|4\\d\\d|50\\d)\\b"),
        Regex("Mali-(4\\d\\d|T6\\d\\d|T7\\d\\d|G31|G51|G52)\\b"),
        Regex("PowerVR (SGX|Rogue GE8)"),
    )
    private val MID = listOf(
        Regex("Adreno \\(TM\\) (5[1-9]\\d|60\\d|61\\d)\\b"),
        Regex("Mali-(G57|G68|G71|G72|G76)\\b"),
    )

    private const val LOW_MEMORY_MB = 3000
    private const val MEDIUM_MEMORY_MB = 5000
    private const val ULTRA_MEMORY_MB = 10000
    private const val ULTRA_TEXTURE = 8192
}
