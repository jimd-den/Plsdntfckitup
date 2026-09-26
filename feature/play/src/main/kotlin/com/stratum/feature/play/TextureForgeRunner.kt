package com.stratum.feature.play

import com.stratum.core.domain.ai.ImageModelPort
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.ForgeOrder
import com.stratum.core.domain.art.ForgePlanner
import com.stratum.core.domain.content.ContentPack
import com.stratum.engine.scene.forge.AssetForge
import com.stratum.engine.scene.forge.ForgeProgress
import com.stratum.feature.play.gl.AndroidImageCodec
import com.stratum.feature.play.gl.ForgedKits
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Paints a style's textures with the image model and keeps them on disk.
 *
 * One runner for every place the forge is started from -- the texture forge
 * screen and the Style panel in play -- so both plan the same orders, skip
 * what an earlier run already made, write to the same folder, and report
 * progress the same way.
 */
class TextureForgeRunner(
    private val model: ImageModelPort,
    private val root: File,
    private val concurrency: Int = CONCURRENCY,
) {
    /** What a run for [direction] over [pack] would order, and which of those are already on disk. */
    fun plan(direction: ArtDirection, pack: ContentPack, biomeIds: Set<String> = emptySet(), includeActors: Boolean = false): Plan {
        val folder = ForgedKits.localFolder(root, direction)
        val orders = ForgePlanner.plan(direction, pack, biomeIds, includeActors)
        val (done, todo) = orders.partition { File(folder, ForgedKits.fileNameFor(it.key)).exists() }
        return Plan(folder, todo, done.size)
    }

    /**
     * Forges [plan], reporting after every asset. Cancelling the calling
     * coroutine stops it between images; what already arrived is kept.
     */
    suspend fun run(plan: Plan, onProgress: (ForgeProgress) -> Unit): ForgeProgress {
        plan.folder.mkdirs()
        var progress = ForgeProgress(total = plan.orders.size + plan.alreadyMade, skipped = plan.alreadyMade)
        onProgress(progress)
        val forge = AssetForge(model, AndroidImageCodec)
        return try {
            forge.forge(plan.orders, concurrency) { asset ->
                asset.texture?.let { texture ->
                    File(plan.folder, ForgedKits.fileNameFor(asset.order.key)).writeBytes(AndroidImageCodec.encodePng(texture))
                }
                // Assets finish concurrently; the progress is rebuilt under a lock.
                synchronized(this) {
                    progress = progress.recording(asset)
                    onProgress(progress)
                }
            }
            progress
        } catch (stopped: CancellationException) {
            onProgress(progress.cancelling())
            throw stopped
        }
    }

    /** A run's orders, where they will be written, and how many were made before. */
    data class Plan(val folder: File, val orders: List<ForgeOrder>, val alreadyMade: Int) {
        val kit: String get() = ForgedKits.LOCAL + folder.absolutePath
        val isEmpty: Boolean get() = orders.isEmpty()
    }

    private companion object {
        const val CONCURRENCY = 3
    }
}
