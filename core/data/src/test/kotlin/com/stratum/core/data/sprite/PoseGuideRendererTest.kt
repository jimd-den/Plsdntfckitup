package com.stratum.core.data.sprite

import android.graphics.BitmapFactory
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.MocapPoses
import com.stratum.core.domain.sprite.PoseGuideStyle
import com.stratum.core.domain.sprite.Skeleton
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PoseGuideRendererTest {

    /**
     * The diagram has to say which side of the body is nearer the camera.
     *
     * It always meant to -- the far limbs are drawn first so the near ones
     * cover them -- but every line was the same black, and a black line
     * covering a black line is invisible. Held against real generated art,
     * that is exactly what came back: the two halves of a walk, one leading
     * with the left leg and one with the right, returned as the same stride
     * drawn twice. The words said which leg led and the prompt says to match
     * the diagram, so the words lost.
     */
    @Test
    fun `the plain diagram draws the far side of the body in grey`() {
        val pose = Skeleton().pose(MocapPoses.poseFor(AnimationState.WALK, 0, 6))
        val bytes = PoseGuideRenderer.render(pose, SIZE, PoseGuideStyle.DIAGRAM)
        assertNotNull(bytes, "the diagram did not render")

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(bitmap, "the diagram was not a readable image")

        var black = 0
        var grey = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                when (luminance(bitmap.getPixel(x, y))) {
                    in 0..60 -> black++
                    in 120..200 -> grey++
                }
            }
        }
        assertTrue(black > MIN_PIXELS, "the diagram drew no near-side lines")
        assertTrue(grey > MIN_PIXELS, "the diagram drew the far side in the same ink as the near")
    }

    private fun luminance(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private companion object {
        const val SIZE = 256

        /** Enough to be a limb rather than an antialiased edge. */
        const val MIN_PIXELS = 200
    }
}
