package com.stratum.core.domain.micro

/** A box of blocks, inclusive: what a stamp touched. */
data class BlockBox(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int)

/**
 * Land whose generator lays microvoxel models over what it makes: statues,
 * sculpted hills, chiselled tunnels. Implemented by the microvoxel terrain.
 *
 * Stamps are generation, not edits: once stamped, a model is part of the
 * land as generated, so it is drawn in full microvoxel detail, and a save
 * keeps the stamps and models rather than the blocks they cover.
 */
interface MicroStampSurface {
    /** Models the stamps name, by id: what a save keeps. */
    fun stampModels(): List<MicroModel>

    /** Every stamp, oldest first. */
    fun stamps(): List<MicroStamp>

    /** Replaces every model and stamp: loading a save. */
    fun restoreStamps(models: Collection<MicroModel>, stamps: List<MicroStamp>)

    /**
     * Lays [stamp] over the land, with [model] as the model it names
     * (brushes are made from their id and need none). Returns the blocks it
     * touched, or null when the model is unknown.
     */
    fun stamp(stamp: MicroStamp, model: MicroModel? = null): BlockBox?

    /** Takes back the newest stamp; returns the blocks it had touched, or null when there are none. */
    fun unstamp(): BlockBox?
}
