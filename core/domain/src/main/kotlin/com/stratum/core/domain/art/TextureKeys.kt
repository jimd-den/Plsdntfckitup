package com.stratum.core.domain.art

/**
 * How a texture key is spelled as a file name, and back.
 *
 * Keys hold `:` and `/` (`igbo:grove_turf/top`), which file systems and APK
 * resources do not all allow, so `~` stands for `:` and `__` for `/`. The
 * forge writes this, the game and the preview tool read it, and importers
 * write it too -- one spelling, defined once.
 */
object TextureKeys {

    fun fileNameFor(key: String): String = key.replace(":", "~").replace("/", "__") + ".png"

    fun keyFor(fileName: String): String = fileName.removeSuffix(".png").replace("__", "/").replace("~", ":")
}
