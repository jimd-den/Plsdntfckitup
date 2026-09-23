package com.stratum.engine.scene.forge

import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.ImageModelPort
import com.stratum.core.domain.ai.ImageRequest
import com.stratum.core.domain.art.AssetKind
import com.stratum.core.domain.art.ForgeOrder
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.TextureLibrary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Turns image bytes into pixels and back.
 *
 * A port because decoding is a platform concern — Android decodes WebP for
 * free, the JVM needs a plugin for it — and the forge should not care which.
 */
interface ImageCodec {
    fun decode(bytes: ByteArray): Texture?
    fun encodePng(texture: Texture): ByteArray
}

/** One order's outcome: a usable texture, or the reason there is none. */
data class ForgedAsset(val order: ForgeOrder, val texture: Texture?, val failure: String? = null) {
    val succeeded: Boolean get() = texture != null
}

/**
 * Generates a world's asset kit and makes it usable.
 *
 * Orders come from [com.stratum.core.domain.art.ForgePlanner]; images come from
 * whichever [ImageModelPort] the composition root wired in — OpenRouter with
 * `meta/muse-image` by default. Each image is cleaned up for its role (tiles
 * made seamless, sprites cut out and trimmed) and the result is ready to drop
 * into a [TextureLibrary] under the key the art director already uses.
 *
 * Failures are per order, never per kit. A world with nine of ten textures
 * draws the tenth in flat colour, which is a world; a forge that threw on the
 * tenth would leave the player with nothing.
 */
class AssetForge(
    private val images: ImageModelPort,
    private val codec: ImageCodec,
    private val modelId: String? = null,
    private val tileSize: Int = 256,
    private val spriteSize: Int = 384,
) {

    suspend fun forge(
        orders: List<ForgeOrder>,
        concurrency: Int = 4,
        onRaw: (ForgeOrder, ByteArray) -> Unit = { _, _ -> },
        onEach: (ForgedAsset) -> Unit = {},
    ): List<ForgedAsset> = coroutineScope {
        val gate = Semaphore(concurrency.coerceAtLeast(1))
        orders.map { order ->
            async {
                gate.withPermit {
                    forgeOne(order, onRaw).also(onEach)
                }
            }
        }.awaitAll()
    }

    /**
     * One order, retried when what comes back is not usable.
     *
     * [onRaw] sees every image as the model sent it, before any clean-up, so a
     * caller can keep originals for debugging without the forge knowing where.
     */
    suspend fun forgeOne(order: ForgeOrder, onRaw: (ForgeOrder, ByteArray) -> Unit = { _, _ -> }): ForgedAsset {
        var lastFailure = "no attempt made"
        repeat(ATTEMPTS) {
            val generated = images.generateImage(
                ImageRequest(prompt = order.prompt, modelId = modelId, width = 1024, height = 1024, requireTransparency = false),
                GenerationObserver.None,
            ).getOrElse { error ->
                val fatal = (error as? com.stratum.core.domain.ai.GenerationException)?.fatal == true
                if (fatal) return ForgedAsset(order, null, error.message ?: "generation failed")
                lastFailure = error.message ?: "generation failed"
                return@repeat
            }
            onRaw(order, generated.bytes)
            val decoded = codec.decode(generated.bytes)
            if (decoded == null) { lastFailure = "could not decode ${generated.mimeType}"; return@repeat }
            // Tiles are judged as they arrive; a sprite is *meant* to be mostly
            // key colour, so it is judged only once the key has been removed.
            if (order.kind != AssetKind.PROP_SPRITE) {
                Pixels.defect(decoded)?.let { lastFailure = "rejected: $it"; return@repeat }
            }
            val finished = runCatching { finish(order.kind, decoded) }
                .getOrElse { lastFailure = it.message ?: "post-processing failed"; return@repeat }
            if (order.kind == AssetKind.PROP_SPRITE) {
                Pixels.spriteDefect(finished)?.let { lastFailure = "rejected: $it"; return@repeat }
            }
            return ForgedAsset(order, finished)
        }
        return ForgedAsset(order, null, lastFailure)
    }

    /** The clean-up each kind of asset needs. Public so imported art gets the same treatment. */
    fun finish(kind: AssetKind, image: Texture): Texture = when (kind) {
        AssetKind.GROUND_TILE, AssetKind.WALL_TILE -> Pixels.seamless(Pixels.downscale(Pixels.square(image), tileSize))
        AssetKind.PROP_SPRITE -> {
            val keyed = Pixels.keyOut(image)
            val trimmed = Pixels.trim(keyed) ?: error("the sprite came back empty after keying")
            Pixels.downscale(trimmed, spriteSize)
        }
    }

    companion object {
        /** Generations per order before giving up on it. */
        const val ATTEMPTS = 3

        /** Adds every successful asset to a library under its order's key. */
        fun install(assets: List<ForgedAsset>, library: TextureLibrary): Int =
            assets.count { asset -> asset.texture?.let { library.put(asset.order.key, it) } != null }
    }
}
