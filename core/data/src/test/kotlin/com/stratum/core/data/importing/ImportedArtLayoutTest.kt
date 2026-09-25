package com.stratum.core.data.importing

import org.junit.Test
import kotlin.test.assertEquals

class ImportedArtLayoutTest {

    @Test
    fun `small pixel art is scaled up by a whole number, large art is left alone`() {
        assertEquals(8, ImportedArtLayout.upscaleFactor(16, 16))
        assertEquals(4, ImportedArtLayout.upscaleFactor(32, 24))
        assertEquals(1, ImportedArtLayout.upscaleFactor(256, 256))
    }

    @Test
    fun `a small frame sits at the bottom centre of its cell`() {
        assertEquals(8 to 16, ImportedArtLayout.anchorInCell(16, 16, 32, 32))
        assertEquals(0 to 0, ImportedArtLayout.anchorInCell(32, 32, 32, 32))
    }
}
