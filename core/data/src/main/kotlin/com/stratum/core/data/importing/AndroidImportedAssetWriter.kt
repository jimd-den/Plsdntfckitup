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

    /** One source image in memory at a time: every tile cut from it, then it is freed. */
    override fun writeTextures(packId: String, source: ImportSource, textures: List<ImportedTexture>) {
        val folder = store.textureDirectory(packId).apply { mkdirs() }
        textures.groupBy { it.region.imagePath }.forEach { (path, fromImage) ->
            withImages(source) { image ->
                val bitmap = image(path) ?: return@withImages
                fromImage.forEach { texture -> File(folder, TextureKeys.fileNameFor(texture.key)).writeBytes(tile(bitmap, texture.region)) }
            }
        }
    }

    /** One sheet's images in memory at a time. */
    override fun writeSpriteSheets(packId: String, source: ImportSource, sheets: List<ImportedSpriteSheet>) {
        sheets.forEach { imported ->
            withImages(source) { image ->
                val composed = compose(imported, image)
                sprites.save(imported.sheet, composed.toPng())
                composed.recycle()
            }
        }
        sprites.refresh()
    }

    /** A tile scaled up by a whole number with no smoothing, as PNG bytes. */
    private fun tile(bitmap: Bitmap, region: ImageRegion): ByteArray {
        val scale = ImportedArtLayout.upscaleFactor(region.width, region.height)
        val tile = draw(region.width * scale, region.height * scale) { canvas ->
            canvas.drawBitmap(bitmap, region.toRect(), Rect(0, 0, canvas.width, canvas.height), crisp)
        }
        return tile.toPng().also { tile.recycle() }
    }

    /** One grid image, each frame at the bottom centre of its cell. */
    private fun compose(imported: ImportedSpriteSheet, image: (String) -> Bitmap?): Bitmap {
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

    /** Decodes each image at most once inside [block], and frees them all after it. */
    private fun withImages(source: ImportSource, block: ((String) -> Bitmap?) -> Unit) {
        val decoded = HashMap<String, Bitmap?>()
        val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        try {
            block { path -> decoded.getOrPut(path) { source.read(path)?.let { BitmapFactory.decodeByteArray(it, 0, it.size, options) } } }
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
