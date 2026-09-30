package com.stratum.engine.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TextureLibraryTest {

    private fun texture() = Texture(1, 1, intArrayOf(0xFFFFFFFF.toInt()))

    @Test
    fun `a library that is full refuses a tile rather than numbering it as a ground map`() {
        val library = TextureLibrary()
        repeat(TextureLibrary.MAX_TILES) { library.put("t$it/top", texture()) }

        assertEquals(-1, library.put("one:too/top", texture()))
        assertEquals(-1, library.layerOf("one:too/top"))
        assertNull(library.textureAt(TextureLibrary.MAP_BASE), "no ground map was invented")
    }

    @Test
    fun `sides with no painting of their own use the top's`() {
        val library = TextureLibrary()
        val top = texture()
        library.put("a:grass/top", top)
        library.put("a:stone/top", texture())
        library.put("a:stone/side", texture())
        library.aliasMissingSides()

        assertSame(top, library.textureAt(library.layerOf("a:grass/side")))
        assertEquals(3, library.all.size, "an alias adds no texture")
        assertEquals(library.layerOf("a:stone/side"), 2, "a painted side keeps its own")
    }

    @Test
    fun `layers shrink as their number grows, and the shipped kits stay full size`() {
        assertEquals(512, TextureBudget.layerSize(35))
        assertEquals(256, TextureBudget.layerSize(200))
        assertEquals(128, TextureBudget.layerSize(700))
        assertEquals(64, TextureBudget.layerSize(5000))
        assertEquals(10, TextureBudget.mipLevels(512))
        assertEquals(7, TextureBudget.mipLevels(64))
    }
}
