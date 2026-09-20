package com.stratum.core.data.sprite

import android.content.Context
import com.stratum.core.domain.sprite.WeaponFit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How each character holds a weapon.
 *
 * Keyed by sprite sheet rather than by weapon, because it is a fact about the
 * character: where its hands sit in its frames, and how large it was drawn in
 * them. A warrior calibrated once holds every sword in the armoury correctly,
 * which is the whole reason weapons and characters are separate assets.
 *
 * Tiny and read on every resolver rebuild, so it is kept as one file rather
 * than one per character: a hundred characters is a few kilobytes.
 */
class WeaponFitStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private var cache: MutableMap<String, FitDto>? = null

    fun fitFor(sheetId: String): WeaponFit =
        load()[sheetId]?.toDomain() ?: WeaponFit.none

    fun save(sheetId: String, fit: WeaponFit) {
        val fits = load()
        if (fit.isIdentity) fits.remove(sheetId) else fits[sheetId] = fit.toDto()
        runCatching { file.writeText(json.encodeToString(fits)) }
    }

    private fun load(): MutableMap<String, FitDto> {
        cache?.let { return it }
        val read = if (file.isFile) {
            runCatching {
                json.decodeFromString<Map<String, FitDto>>(file.readText()).toMutableMap()
            }.getOrNull()
        } else {
            null
        }
        return (read ?: mutableMapOf()).also { cache = it }
    }

    private companion object {
        const val FILE = "weapon_fits.json"
    }
}

@Serializable
private data class FitDto(
    val offsetX: Float,
    val offsetY: Float,
    val scale: Float,
    /** Defaulted, so fits written before the mirror existed keep their meaning. */
    val mirrored: Boolean = false,
)

private fun WeaponFit.toDto() = FitDto(offsetX, offsetY, scale, mirrored)

private fun FitDto.toDomain() = WeaponFit(
    offsetX = offsetX.coerceIn(-WeaponFit.MAX_OFFSET, WeaponFit.MAX_OFFSET),
    offsetY = offsetY.coerceIn(-WeaponFit.MAX_OFFSET, WeaponFit.MAX_OFFSET),
    scale = scale.coerceIn(WeaponFit.MIN_SCALE, WeaponFit.MAX_SCALE),
    mirrored = mirrored,
)
