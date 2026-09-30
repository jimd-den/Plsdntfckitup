package com.stratum.engine.scene.forge

import com.stratum.core.domain.art.AssetKind
import com.stratum.core.domain.art.ForgeOrder

/**
 * How far a forge run has got, in terms a progress screen can show without
 * doing sums: how many of how many, how many worked, what failed and why,
 * and what finished most recently.
 *
 * Immutable and built by [recording] each finished asset, so the order
 * results arrive in -- they come back concurrently -- never matters.
 */
data class ForgeProgress(
    val total: Int,
    val made: List<ForgeOrder> = emptyList(),
    val failed: List<Pair<ForgeOrder, String>> = emptyList(),
    /** Orders already on disk from an earlier run, counted as done without asking the model again. */
    val skipped: Int = 0,
    val cancelled: Boolean = false,
) {
    val done: Int get() = skipped + made.size + failed.size

    val fraction: Float get() = if (total <= 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f)

    val isFinished: Boolean get() = cancelled || done >= total

    /** The newest successes first, for a gallery that fills in as the run goes. */
    val latest: List<ForgeOrder> get() = made.asReversed()

    /** "12 of 40 · 2 failed", the one line a progress bar needs. */
    val summary: String
        get() = buildString {
            append("$done of $total")
            if (failed.isNotEmpty()) append(" · ${failed.size} failed")
            if (skipped > 0) append(" · $skipped already made")
            if (cancelled) append(" · stopped")
        }

    fun recording(asset: ForgedAsset): ForgeProgress =
        if (asset.succeeded) copy(made = made + asset.order)
        else copy(failed = failed + (asset.order to (asset.failure ?: "failed")))

    fun cancelling(): ForgeProgress = copy(cancelled = true)

    companion object {
        /** Counts by kind of asset, so a plan can be described before it runs: "8 ground, 6 walls, 12 props". */
        fun describe(orders: List<ForgeOrder>): String =
            orders.groupingBy { it.kind }.eachCount().entries
                .sortedBy { it.key.ordinal }
                .joinToString { (kind, count) -> "$count ${kindName(kind, count)}" }

        private fun kindName(kind: AssetKind, count: Int): String = when (kind) {
            AssetKind.GROUND_TILE -> "ground"
            AssetKind.WALL_TILE -> if (count == 1) "wall" else "walls"
            AssetKind.PROP_SPRITE -> if (count == 1) "prop" else "props"
            AssetKind.GROUND_DETAIL -> "ground details"
            AssetKind.GROUND_MAP -> if (count == 1) "ground map" else "ground maps"
        }
    }
}
