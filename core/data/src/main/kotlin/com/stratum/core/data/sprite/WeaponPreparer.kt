package com.stratum.core.data.sprite

import android.graphics.Bitmap
import com.stratum.core.domain.sprite.SourceRect
import com.stratum.core.domain.sprite.SpriteKeying
import com.stratum.core.domain.sprite.SpriteOrigin
import com.stratum.core.domain.sprite.SpriteSlicing
import com.stratum.core.domain.sprite.WeaponKind
import com.stratum.core.domain.sprite.WeaponSprite
import java.io.ByteArrayOutputStream

/** A generated weapon after the chroma is gone and the empty canvas is cut away. */
data class PreparedWeapon(val weapon: WeaponSprite, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PreparedWeapon && weapon == other.weapon && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * weapon.hashCode() + bytes.contentHashCode()
}

/**
 * Keys and trims a generated weapon.
 *
 * The trim is not tidying, it is the whole reason this exists. The grip is
 * stored as a fraction of the weapon's box — "the hand is 88% of the way down
 * this sword" — and a model hands back a sword occupying a third of a 1024
 * pixel square with green all round it. Measured against that canvas the grip
 * lands somewhere in the empty air below the pommel, and every frame of every
 * attack holds the weapon by nothing.
 *
 * Cutting the canvas down to the weapon makes the fraction mean what it says.
 */
object WeaponPreparer {

    fun prepare(
        id: String,
        name: String,
        kind: WeaponKind,
        bytes: ByteArray,
        origin: SpriteOrigin = SpriteOrigin.AI_GENERATED,
    ): PreparedWeapon? {
        val decoded = SpriteAtlasBaker.decode(bytes) ?: return null
        val width = decoded.width
        val height = decoded.height
        if (width <= 0 || height <= 0) {
            decoded.recycle()
            return null
        }

        val pixels = SpriteAtlasBaker.pixelsOf(decoded)
        decoded.recycle()

        val keyed = SpriteKeying.key(pixels, width, height)
        val bounds = SpriteSlicing.contentBounds(
            pixels = keyed.pixels,
            imageWidth = width,
            imageHeight = height,
            rect = SourceRect(0, 0, width, height),
        ) ?: return null

        val trimmed = runCatching {
            Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until bounds.height) {
                    setPixels(
                        keyed.pixels,
                        (bounds.top + y) * width + bounds.left,
                        width,
                        0,
                        y,
                        bounds.width,
                        1,
                    )
                }
            }
        }.getOrNull() ?: return null

        val out = ByteArrayOutputStream()
        val ok = trimmed.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        trimmed.recycle()
        if (!ok) return null

        return PreparedWeapon(
            weapon = WeaponSprite(
                id = id,
                name = name,
                kind = kind,
                width = bounds.width,
                height = bounds.height,
                origin = origin,
            ),
            bytes = out.toByteArray(),
        )
    }

    /** PNG ignores this, but the API demands it. */
    private const val PNG_QUALITY = 100
}
