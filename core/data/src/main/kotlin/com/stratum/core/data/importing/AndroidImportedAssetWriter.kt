package com.stratum.core.data.importing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.stratum.core.data.sprite.SpriteLibrary
import com.stratum.core.domain.art.TextureKeys
import com.stratum.core.domain.importing.ImageRegion
import com.stratum.core.domain.importing.ImportSource
import com.stratum.core.domain.importing.ImportedAssetWriter
import com.stratum.core.domain.importing.ImportedSpriteSheet
import com.stratum.core.domain.importing.ImportedTexture
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Cuts an import's art out of its images and stores it where the game reads
 * art from: textures as a kit folder the 3D view lays over the style's kit,
 * sprite sheets in the [SpriteLibrary] beside forged ones.
 */
class AndroidImportedAssetWriter(
    private val store: ImportedPackStore,
    private val sprites: SpriteLibrary,
) : ImportedAssetWriter {

    /** Nearest-neighbour: every pixel is copied, none is blended. */
    private val crisp = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

    override fun writeTextures(packId: String, source: ImportSource, textures: List<ImportedTexture>) {
        val folder = store.textureDirectory(packId).apply { mkdirs() }
        withImages(source, textures.map { it.region }) { image ->
            textures.forEach { texture ->
                val bitmap = image(texture.region.imagePath) ?: return@forEach
                val scale = ImportedArtLayout.upscaleFactor(texture.region.width, texture.region.height)
                val tile = draw(texture.region.width * scale, texture.region.height * scale) { canvas ->
                    canvas.drawBitmap(bitmap, texture.region.toRect(), Rect(0, 0, canvas.width, canvas.height), crisp)
                }
                File(folder, TextureKeys.fileNameFor(texture.key)).writeBytes(tile.toPng())
                tile.recycle()
            }
        }
    }

    override fun writeSpriteSheets(packId: String, source: ImportSource, sheets: List<ImportedSpriteSheet>) {
        withImages(source, sheets.flatMap { it.frames }) { image ->
            sheets.forEach { imported ->
                val composed = compose(imported, image) ?: return@forEach
                sprites.save(imported.sheet, composed.toPng())
                composed.recycle()
            }
        }
        sprites.refresh()
    }

    /** One grid image, each frame at the bottom centre of its cell. */
    private fun compose(imported: ImportedSpriteSheet, image: (String) -> Bitmap?): Bitmap? {
        val sheet = imported.sheet
        return draw(sheet.columns * sheet.frameWidth, sheet.rows * sheet.frameHeight) { canvas ->
            imported.frames.forEachIndexed { index, region ->
                val bitmap = image(region.imagePath) ?: return@forEachIndexed
                val (dx, dy) = ImportedArtLayout.anchorInCell(region.width, region.height, sheet.frameWidth, sheet.frameHeight)
                val left = (index % sheet.columns) * sheet.frameWidth + dx
                val top = (index / sheet.columns) * sheet.frameHeight + dy
                canvas.drawBitmap(bitmap, region.toRect(), Rect(left, top, left + region.width, top + region.height), crisp)
            }
        }
    }

    /** Decodes each image file at most once for a whole batch, and frees them all after. */
    private fun withImages(source: ImportSource, regions: List<ImageRegion>, block: ((String) -> Bitmap?) -> Unit) {
        val decoded = HashMap<String, Bitmap?>()
        val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val image = { path: String ->
            decoded.getOrPut(path) { source.read(path)?.let { BitmapFactory.decodeByteArray(it, 0, it.size, options) } }
        }
        regions.map { it.imagePath }.distinct().forEach { image(it) }
        try {
            block(image)
        } finally {
            decoded.values.forEach { it?.recycle() }
        }
    }

    private fun draw(width: Int, height: Int, paint: (Canvas) -> Unit): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { paint(Canvas(it)) }

    private fun ImageRegion.toRect() = Rect(x, y, x + width, y + height)

    private fun Bitmap.toPng(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }.toByteArray()

    private companion object {
        const val PNG_QUALITY = 100
    }
}
