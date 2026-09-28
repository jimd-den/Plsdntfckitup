package com.stratum.engine.render

import kotlin.math.abs
import kotlin.math.floor

/**
 * When a painted terrain layer can be reused, and where to put it.
 *
 * [WorldFrameRenderer.render] paints every visible cube: a couple of thousand
 * of them, several filled paths each, every frame, on a world that has not
 * changed. The terrain depends only on the world, the camera, the time of
 * day, the zoom, the highlighted cell and the art direction, and none of it
 * animates, so it can be painted once into a layer a margin larger than the
 * screen and slid under the camera until the camera nears the margin or
 * something it depends on changes. Actors, effects and the finishing washes
 * are still drawn fresh on top, as before.
 */
class TerrainLayerPolicy(
    /** Pixels painted past each edge of the screen; how far the camera may drift before a repaint. */
    val margin: Float,
    /** How many steps a day's light is painted in. Finer costs a repaint each step. */
    private val dayBuckets: Int = DAY_BUCKETS,
) {
    /** Everything about a frame the painted terrain depends on, other than where the camera is. */
    data class Key(
        val worldRevision: Int,
        val width: Int,
        val height: Int,
        val zoom: Float,
        val eyeLevel: Int,
        val biomeId: String?,
        val dayStep: Int,
        val underground: Boolean,
        val highlight: Any?,
        /** Identity of the art direction; a new director repaints. */
        val director: Any,
    )

    private var painted: Key? = null
    private var paintedOriginX = 0f
    private var paintedOriginY = 0f

    /** Which of [dayBuckets] steps a day fraction falls in. */
    fun dayStep(dayFraction: Float): Int = floor((dayFraction - floor(dayFraction)) * dayBuckets).toInt()

    /**
     * Whether the layer must be painted again for a frame whose screen origin
     * is ([originX], [originY]). The origin is where the world's zero lands
     * on screen, so its change is exactly how far the picture has moved.
     */
    fun needsPaint(key: Key, originX: Float, originY: Float): Boolean =
        painted != key || abs(originX - paintedOriginX) > margin || abs(originY - paintedOriginY) > margin

    /** Records a paint whose layer origin (in the layer's own pixels) is ([layerOriginX], [layerOriginY]). */
    fun painted(key: Key, originX: Float, originY: Float) {
        painted = key
        paintedOriginX = originX
        paintedOriginY = originY
    }

    /**
     * Where to draw the layer's top-left corner for a frame with screen
     * origin ([originX], [originY]). The layer was painted with its own
     * origin shifted by [margin], so that is taken back off.
     */
    fun layerOffsetX(originX: Float): Float = originX - paintedOriginX - margin

    fun layerOffsetY(originY: Float): Float = originY - paintedOriginY - margin

    /** Forgets the layer, e.g. when its surface was lost. */
    fun invalidate() {
        painted = null
    }

    companion object {
        /** Every six seconds of a twenty-minute day: slower than the eye can follow a change of light. */
        const val DAY_BUCKETS = 200
    }
}
