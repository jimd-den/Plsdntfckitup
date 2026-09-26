package com.stratum.tools.artpreview

import com.stratum.core.domain.art.TextureKeys
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.TextureLibrary
import java.io.File
import javax.imageio.ImageIO

/**
 * Loads a forged asset kit from disk into a texture library.
 *
 * The file name is the key, with `__` standing in for `/` and `~` for `:`, so
 * `igbo~grove_turf__top.png` is the top face of `igbo:grove_turf`. Anything
 * that fails to decode is skipped: a kit with one bad file should still draw
 * everything else.
 */
object ForgedTextures {

    fun keyFor(file: File): String = TextureKeys.keyFor(file.name)

    fun fileNameFor(key: String): String = TextureKeys.fileNameFor(key)

    fun loadInto(directory: File, library: TextureLibrary): Int {
        if (!directory.isDirectory) return 0
        var loaded = 0
        directory.walkTopDown().filter { it.isFile && it.extension == "png" }.sortedBy { it.name }.forEach { file ->
            val image = runCatching { ImageIO.read(file) }.getOrNull() ?: return@forEach
            val pixels = IntArray(image.width * image.height)
            image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
            library.put(keyFor(file), Texture(image.width, image.height, pixels))
            loaded++
        }
        return loaded
    }
}
