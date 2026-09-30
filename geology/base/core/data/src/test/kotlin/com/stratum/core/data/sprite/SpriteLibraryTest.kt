package com.stratum.core.data.sprite

import androidx.test.core.app.ApplicationProvider
import com.stratum.core.domain.sprite.AnimationClip
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SpriteFacing
import com.stratum.core.domain.sprite.SpriteSheet
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class SpriteLibraryTest {

    private val library = SpriteLibrary(ApplicationProvider.getApplicationContext())

    private fun twoAngleSheet() = SpriteSheet(
        id = "hero:turner",
        name = "Turner",
        columns = 6,
        rows = 14,
        frameWidth = 100,
        frameHeight = 192,
        clips = listOf(
            AnimationClip(AnimationState.IDLE, firstFrame = 0, frameCount = 6),
            AnimationClip(AnimationState.WALK, firstFrame = 6, frameCount = 6),
        ),
        facingRows = mapOf(
            SpriteFacing.SOUTH_EAST to 0,
            SpriteFacing.SOUTH_WEST to 0,
            SpriteFacing.NORTH_EAST to 7,
            SpriteFacing.NORTH_WEST to 7,
        ),
    )

    @Test
    fun `a sheet that can turn around still can after it is reloaded`() {
        // The whole of the away art lives in the image, and the only thing
        // saying how to reach it is this map. It was not being written, so a
        // character turned around until the app was restarted and then never
        // again -- the rows still there, unreferenced, nothing reporting a
        // problem.
        val sheet = twoAngleSheet()
        library.save(sheet, ByteArray(8))

        val reloaded = assertNotNull(library.all().firstOrNull { it.id == sheet.id })
        assertEquals(sheet.facingRows, reloaded.facingRows)

        // And the thing the renderer actually asks: walking away reads the
        // second block of rows, not the first.
        val frame = 6
        assertEquals(frame, reloaded.frameFor(frame, SpriteFacing.SOUTH_EAST))
        assertEquals(
            frame + 7 * reloaded.columns,
            reloaded.frameFor(frame, SpriteFacing.NORTH_EAST),
        )
    }

    @Test
    fun `a one-angle sheet reloads with no facing rows and draws as it always did`() {
        val plain = twoAngleSheet().copy(id = "hero:plain", facingRows = emptyMap(), rows = 7)
        library.save(plain, ByteArray(8))

        val reloaded = assertNotNull(library.all().firstOrNull { it.id == plain.id })
        assertEquals(emptyMap(), reloaded.facingRows)
        assertEquals(3, reloaded.frameFor(3, SpriteFacing.NORTH_WEST))
    }
}
