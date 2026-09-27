package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpritePluginExportTest {

    /** The first 24 bytes of a PNG of the given size: all the exporter reads. */
    private fun png(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(33)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
            .copyInto(bytes)
        "IHDR".toByteArray().copyInto(bytes, 12)
        fun int(value: Int, at: Int) {
            bytes[at] = (value ushr 24).toByte(); bytes[at + 1] = (value ushr 16).toByte()
            bytes[at + 2] = (value ushr 8).toByte(); bytes[at + 3] = value.toByte()
        }
        int(width, 16)
        int(height, 20)
        return bytes
    }

    private fun sheet(id: String) = SpriteSheet(
        id = id, name = "Bronze", columns = 4, rows = 2, frameWidth = 64, frameHeight = 64,
        clips = listOf(AnimationClip(AnimationState.WALK, 0, 4)), origin = SpriteOrigin.AI_GENERATED,
    )

    @Test
    fun `sheets and their images go into one pack, keyed as the archive names them`() {
        val bundle = assertNotNull(SpritePluginExport.bundle(listOf(SheetArt(sheet("hero:bronze"), png(256, 128)))))
        assertEquals(bundle.manifest.id, bundle.pack.id)
        assertEquals(listOf("hero:bronze"), bundle.pack.spriteSheets.map { it.id })
        assertEquals(setOf("hero:bronze"), bundle.images.keys)
        assertEquals("Bronze", bundle.pack.name)
        assertTrue(bundle.skipped.isEmpty())
    }

    @Test
    fun `an image smaller than its grid is left out and said to be`() {
        val bundle = assertNotNull(
            SpritePluginExport.bundle(
                listOf(SheetArt(sheet("hero:a"), png(256, 128)), SheetArt(sheet("hero:b"), png(100, 100))),
            ),
        )
        assertEquals(listOf("hero:a"), bundle.pack.spriteSheets.map { it.id })
        assertTrue(bundle.skipped.getValue("hero:b").contains("smaller"))
    }

    @Test
    fun `a non-png is re-encoded when it can be, and dropped when it cannot`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0)
        val reencoded = SpritePluginExport.bundle(listOf(SheetArt(sheet("hero:j"), jpeg))) { png(256, 128) }
        assertNotNull(reencoded)
        assertNull(SpritePluginExport.bundle(listOf(SheetArt(sheet("hero:j"), jpeg))))
    }

    @Test
    fun `png header size is read without decoding`() {
        assertEquals(300 to 200, PngHeader.sizeOf(png(300, 200)))
        assertNull(PngHeader.sizeOf(byteArrayOf(1, 2, 3)))
    }
}
