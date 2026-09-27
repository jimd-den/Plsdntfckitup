package com.stratum.engine.scene.quality

/**
 * Keeps the frame rate by trading resolution for time.
 *
 * Watches how long frames typically take and lowers the scene's render scale when
 * they run over budget, then gives it back once there is headroom. Pixels
 * are the cost that scales most directly and that a player notices least,
 * and changing them needs no reload. It moves in steps, and only after a
 * whole window agrees, so a single hitch -- a chunk loading, the GC --
 * does not make the picture pump.
 */
class FrameGovernor(private val settings: RenderSettings, private val window: Int = WINDOW) {

    var renderScale: Float = settings.renderScale
        private set

    private val samples = FloatArray(window)
    private var frames = 0
    private var calmWindows = 0

    /**
     * Records one frame's duration. Returns true when [renderScale] changed
     * and the backend should resize its targets.
     *
     * Gaps longer than [MAX_FRAME_MILLIS] are the game paused or backgrounded,
     * not slow frames, and are ignored.
     */
    fun record(frameMillis: Float): Boolean {
        if (frameMillis <= 0f || frameMillis > MAX_FRAME_MILLIS) return false
        samples[frames++] = frameMillis
        if (frames < window) return false
        frames = 0
        return adjust(typicalFrame())
    }

    /**
     * The window's median. An average is dragged over budget by one long
     * frame; the median is what most frames actually took.
     */
    private fun typicalFrame(): Float {
        val sorted = samples.sortedArray()
        return sorted[sorted.size / 2]
    }

    private fun adjust(typical: Float): Boolean {
        val budget = settings.frameBudgetMillis
        val before = renderScale
        when {
            typical > budget * OVER_BUDGET -> {
                calmWindows = 0
                renderScale = (renderScale - STEP_DOWN).coerceAtLeast(settings.minRenderScale)
            }
            typical < budget * HEADROOM -> {
                // Slower to give back than to take: two calm windows in a row.
                if (++calmWindows >= CALM_WINDOWS_TO_RISE) {
                    calmWindows = 0
                    renderScale = (renderScale + STEP_UP).coerceAtMost(settings.renderScale)
                }
            }
            else -> calmWindows = 0
        }
        return renderScale != before
    }

    companion object {
        const val WINDOW = 30
        const val OVER_BUDGET = 1.15f
        const val HEADROOM = 0.75f
        const val STEP_DOWN = 0.1f
        const val STEP_UP = 0.05f
        const val CALM_WINDOWS_TO_RISE = 2
        const val MAX_FRAME_MILLIS = 250f
    }
}
