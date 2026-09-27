package com.stratum.tools.artpreview

import com.stratum.core.domain.art.TextureKeys
import com.stratum.core.domain.importing.ImageRegion
import com.stratum.core.domain.importing.ImportSource
import com.stratum.core.domain.importing.ImportedAssetWriter
import com.stratum.core.domain.importing.ImportedSpriteSheet
import com.stratum.core.domain.importing.ImportedTexture
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * The desktop twin of the app's asset writer: textures into a kit folder the
 * preview renderer reads, sheets as PNGs beside it. Frames are placed the
 * same way, at the bottom centre of their cell.
 */
class JvmImportedAssetWriter(private val root: File) : ImportedAssetWriter {

    fun textureDirectory(packId: String) = File(root, "$packId/textures")

    fun sheetDirectory(packId: String) = File(root, "$packId/sheets")

    override fun writeTextures(packId: String, source: ImportSource, textures: List<ImportedTexture>) {
        val folder = textureDirectory(packId).apply { mkdirs() }
        val images = ImageCache(source)
        textures.forEach { texture ->
            val tile = images.crop(texture.region) ?: return@forEach
            ImageIO.write(tile, "png", File(folder, TextureKeys.fileNameFor(texture.key)))
        }
    }

    override fun writeSpriteSheets(packId: String, source: ImportSource, sheets: List<ImportedSpriteSheet>) {
        val folder = sheetDirectory(packId).apply { mkdirs() }
        val images = ImageCache(source)
        sheets.forEach { imported ->
            val sheet = imported.sheet
            val out = BufferedImage(sheet.columns * sheet.frameWidth, sheet.rows * sheet.frameHeight, BufferedImage.TYPE_INT_ARGB)
            val graphics = out.createGraphics()
            imported.frames.forEachIndexed { index, region ->
                val frame = images.crop(region) ?: return@forEachIndexed
                val left = (index % sheet.columns) * sheet.frameWidth + (sheet.frameWidth - region.width) / 2
                val top = (index / sheet.columns) * sheet.frameHeight + (sheet.frameHeight - region.height)
                graphics.drawImage(frame, left, top, null)
            }
            graphics.dispose()
            ImageIO.write(out, "png", File(folder, TextureKeys.fileNameFor(sheet.id)))
        }
    }

    /** Decodes each project image once. A region past the image's edge is clipped, not an error. */
    private class ImageCache(private val source: ImportSource) {
        private val decoded = HashMap<String, BufferedImage?>()

        fun crop(region: ImageRegion): BufferedImage? {
            val image = decoded.getOrPut(region.imagePath) {
                source.read(region.imagePath)?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
            } ?: return null
            val width = minOf(region.width, image.width - region.x)
            val height = minOf(region.height, image.height - region.y)
            if (width <= 0 || height <= 0) return null
            return image.getSubimage(region.x, region.y, width, height)
        }
    }
}
