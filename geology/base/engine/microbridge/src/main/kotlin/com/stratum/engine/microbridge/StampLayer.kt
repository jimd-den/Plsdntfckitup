package com.stratum.engine.microbridge

import com.stratum.core.domain.micro.BlockBox
import com.stratum.core.domain.micro.MicroBrushes
import com.stratum.core.domain.micro.MicroModel
import com.stratum.core.domain.micro.MicroStamp
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.MicroChunkPos
import com.stratum.engine.microvoxel.Paints

/**
 * The models a world has had stamped into it, in order, laid over each
 * micro chunk as it is generated.
 *
 * Shared by every generator a [HotTerrain] builds, so retuning the land
 * keeps the statues standing on it. Safe to read from the mesh workers while
 * the game thread stamps: every read takes a snapshot of the stamps it needs
 * under the lock and applies them outside it.
 */
class StampLayer {
    private val lock = Any()
    private val models = LinkedHashMap<String, MicroModel>()
    private val stamps = ArrayList<MicroStamp>()

    /** Stamp indices by the micro chunk column (x, y) they reach into. */
    private val columns = HashMap<Long, MutableList<Int>>()

    /** Resolved materials per model and palette, so a model's colours are looked up once. */
    private val resolved = HashMap<Pair<Int, String>, ShortArray>()

    /** Bumped by every change, for anything caching what the layer made. */
    @Volatile var revision: Int = 0
        private set

    val isEmpty: Boolean get() = synchronized(lock) { stamps.isEmpty() }

    fun models(): List<MicroModel> = synchronized(lock) { stamps.map { it.modelId }.distinct().mapNotNull { models[it] } }

    fun stamps(): List<MicroStamp> = synchronized(lock) { stamps.toList() }

    fun restore(models: Collection<MicroModel>, stamps: List<MicroStamp>) = synchronized(lock) {
        this.models.clear(); this.stamps.clear(); columns.clear(); resolved.clear()
        models.forEach { this.models[it.id] = it }
        stamps.forEach { add(it) }
        revision++
    }

    /** Adds a stamp; returns the micro box it covers, or null when its model is unknown. */
    fun stamp(stamp: MicroStamp, model: MicroModel?): IntArray? = synchronized(lock) {
        if (model != null && !MicroBrushes.isBrush(model.id)) {
            if (models[model.id] != model) resolved.keys.removeAll { it.second == model.id }
            models[model.id] = model
        }
        val box = add(stamp) ?: return null
        revision++
        box
    }

    /** Removes the newest stamp; returns the micro box it covered. */
    fun unstamp(): IntArray? = synchronized(lock) {
        if (stamps.isEmpty()) return null
        val last = stamps.size - 1
        val stamp = stamps.removeAt(last)
        columns.values.forEach { it.remove(last) }
        revision++
        boxOf(stamp, modelOf(stamp.modelId) ?: return null)
    }

    private fun add(stamp: MicroStamp): IntArray? {
        val model = modelOf(stamp.modelId) ?: return null
        val box = boxOf(stamp, model)
        val index = stamps.size
        stamps += stamp
        for (cy in Math.floorDiv(box[1], S)..Math.floorDiv(box[4], S)) for (cx in Math.floorDiv(box[0], S)..Math.floorDiv(box[3], S)) {
            columns.getOrPut(key(cx, cy)) { ArrayList() } += index
        }
        return box
    }

    private fun modelOf(id: String): MicroModel? = models[id] ?: MicroBrushes.model(id)

    /** Lays every stamp reaching into [pos] over [generated]; the chunk itself is never changed. */
    fun apply(pos: MicroChunkPos, generated: MicroChunk, palette: MaterialPalette): MicroChunk {
        val mine: List<Pair<MicroStamp, MicroModel>>
        val materials: List<ShortArray>
        synchronized(lock) {
            val list = columns[key(pos.x, pos.y)] ?: return generated
            val z0 = pos.originZ; val z1 = z0 + S - 1
            mine = list.map { stamps[it] }.mapNotNull { s -> modelOf(s.modelId)?.let { s to it } }
                .filter { (s, m) -> s.z <= z1 && s.z + m.sizeZ - 1 >= z0 }
            if (mine.isEmpty()) return generated
            materials = mine.map { (_, m) -> resolved.getOrPut(System.identityHashCode(palette) to m.id) { resolve(m, palette) } }
        }
        val out = generated.copy()
        val ox = pos.originX; val oy = pos.originY; val oz = pos.originZ
        for ((i, pair) in mine.withIndex()) {
            val (stamp, model) = pair
            val mats = materials[i]
            val (fx, fy) = stamp.footprint(model)
            val x0 = maxOf(stamp.x, ox); val x1 = minOf(stamp.x + fx - 1, ox + S - 1)
            val y0 = maxOf(stamp.y, oy); val y1 = minOf(stamp.y + fy - 1, oy + S - 1)
            val z0 = maxOf(stamp.z, oz); val z1 = minOf(stamp.z + model.sizeZ - 1, oz + S - 1)
            for (z in z0..z1) for (y in y0..y1) for (x in x0..x1) {
                val cell = stamp.cellAt(model, x, y, z)
                if (cell < 0) continue
                val v = model.cells[cell]
                if (v == 0) continue
                out.set(x - ox, y - oy, z - oz, if (stamp.carve) MaterialPalette.AIR else mats[v - 1])
            }
        }
        return out
    }

    private fun resolve(model: MicroModel, palette: MaterialPalette): ShortArray =
        ShortArray(model.palette.size) { Paints.resolve(model.palette[it], palette) }

    companion object {
        private const val S = MicroChunk.SIZE

        private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

        /** Micro box (x0, y0, z0, x1, y1, z1) a stamp covers. */
        fun boxOf(stamp: MicroStamp, model: MicroModel): IntArray {
            val (fx, fy) = stamp.footprint(model)
            return intArrayOf(stamp.x, stamp.y, stamp.z, stamp.x + fx - 1, stamp.y + fy - 1, stamp.z + model.sizeZ - 1)
        }

        /** The blocks a micro box touches, at [r] microvoxels to a block. */
        fun blocksOf(box: IntArray, r: Int): BlockBox = BlockBox(
            Math.floorDiv(box[0], r), Math.floorDiv(box[1], r), Math.floorDiv(box[2], r),
            Math.floorDiv(box[3], r), Math.floorDiv(box[4], r), Math.floorDiv(box[5], r),
        )
    }
}
