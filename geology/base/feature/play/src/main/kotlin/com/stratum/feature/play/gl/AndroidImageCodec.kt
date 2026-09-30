package com.stratum.feature.play.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.forge.ImageCodec
import java.io.ByteArrayOutputStream

/**
 * The platform decoder, as the forge's [ImageCodec].
 *
 * Android decodes WebP natively and correctly, so on a phone the forge takes
 * Muse Image's default output as it comes.
 */
object AndroidImageCodec : ImageCodec {
    override fun decode(bytes: ByteArray): Texture? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Texture(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
    }

    override fun encodePng(texture: Texture): ByteArray {
        val bitmap = Bitmap.createBitmap(texture.argb, texture.width, texture.height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            .also { bitmap.recycle() }
    }
}
