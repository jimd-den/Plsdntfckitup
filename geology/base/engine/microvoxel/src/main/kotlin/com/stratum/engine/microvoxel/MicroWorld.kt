package com.stratum.engine.microvoxel

import com.stratum.engine.microvoxel.gen.Fields
import com.stratum.engine.microvoxel.gen.MicroGenerator
import com.stratum.engine.microvoxel.mesh.QualityProfile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * An infinite, editable microvoxel world.
 *
 * Chunks are generated on demand around a focus and dropped when it moves
 * away. Edits are not stored in the chunks: they live in a sparse overlay
 * ([edits]) keyed by chunk, replayed over the regenerated chunk whenever it
 * comes back. So
 *  - walking away and back costs a regeneration, never a disk read;
 *  - a save file is only the player's edits -- kilobytes, however far they
 *    roamed -- because everything else is a pure function of the seed;
 *  - changing a generator keeps every player edit exactly where it was.
 *
 * Not thread-safe; a game drives it from one thread and hands [MicroChunk]s
 * to meshing workers read-only.
 */
class MicroWorld(val generator: MicroGenerator, val quality: QualityProfile = QualityProfile.MEDIUM) {

    val palette: MaterialPalette get() = generator.palette

    private val loaded = LinkedHashMap<MicroChunkPos, MicroChunk>()
    private val edits = HashMap<MicroChunkPos, HashMap<Int, Short>>()
    private val dirty = LinkedHashSet<MicroChunkPos>()

    val loadedChunks: Collection<MicroChunk> get() = loaded.values

    /** Chunks whose voxels changed since the last [takeDirty], neighbours included when an edit touched a border. */
    fun takeDirty(): Set<MicroChunkPos> = LinkedHashSet(dirty).also { dirty.clear() }

    fun peek(pos: MicroChunkPos): MicroChunk? = loaded[pos]

    /** The chunk at [pos], generating it (and replaying edits) if needed. */
    fun chunk(pos: MicroChunkPos): MicroChunk = loaded[pos] ?: generator.generate(pos).also { chunk ->
        edits[pos]?.forEach { (i, m) -> chunk.set(i and 63, (i ushr 6) and 63, i ushr 12, m) }
        loaded[pos] = chunk
        dirty += pos
    }

    /**
     * Generates many chunks at once on a thread pool, then adopts them on the
     * caller's thread. Generation is pure, so this is safe; only the adoption
     * touches the world. For loading screens, teleports and tools.
     */
    fun preload(positions: Collection<MicroChunkPos>) {
        val missing = positions.filter { it !in loaded }
        val made = missing.parallelStream().map { generator.generate(it) }.collect(java.util.stream.Collectors.toList())
        for (chunk in made) {
            edits[chunk.pos]?.forEach { (i, m) -> chunk.set(i and 63, (i ushr 6) and 63, i ushr 12, m) }
            loaded[chunk.pos] = chunk
            dirty += chunk.pos
        }
    }

    operator fun get(x: Int, y: Int, z: Int): Short {
        val pos = MicroChunkPos.containing(x, y, z)
        return chunk(pos)[x - pos.originX, y - pos.originY, z - pos.originZ]
    }

    /** Sets one voxel and remembers it as an edit. */
    fun set(x: Int, y: Int, z: Int, material: Short): Boolean {
        val pos = MicroChunkPos.containing(x, y, z)
        val lx = x - pos.originX; val ly = y - pos.originY; val lz = z - pos.originZ
        if (!chunk(pos).set(lx, ly, lz, material)) return false
        edits.getOrPut(pos) { HashMap() }[lx or (ly shl 6) or (lz shl 12)] = material
        markDirty(pos, lx, ly, lz)
        return true
    }

    /** Digs (or, with a material, fills) a ball. Returns how many voxels changed. */
    fun sphere(cx: Int, cy: Int, cz: Int, radius: Float, material: Short = MaterialPalette.AIR): Int {
        val r = radius.toInt() + 1
        var changed = 0
        for (z in cz - r..cz + r) for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
            val dx = x - cx; val dy = y - cy; val dz = z - cz
            if (sqrt((dx * dx + dy * dy + dz * dz).toFloat()) <= radius && set(x, y, z, material)) changed++
        }
        return changed
    }

    /** Fills an inclusive box: walls, floors, a whole house in one call. */
    fun box(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, material: Short): Int {
        var changed = 0
        for (z in z0..z1) for (y in y0..y1) for (x in x0..x1) if (set(x, y, z, material)) changed++
        return changed
    }

    /** Highest non-air voxel at a column within the loaded vertical range, or the generator's surface if unloaded. */
    fun surfaceAt(x: Int, y: Int): Int {
        val guess = generator.fields.get(Fields.SURFACE)?.heightAt(x, y)?.toInt() ?: 0
        for (z in guess + 64 downTo guess - 64) if (this[x, y, z] != MaterialPalette.AIR) return z
        return guess
    }

    /**
     * Streams chunks around a focus: loads up to [QualityProfile.chunksPerFrame]
     * missing chunks, nearest first, and unloads those beyond the view radius.
     * Call once a frame. Returns the chunks it loaded.
     */
    fun stream(focusX: Int, focusY: Int, focusZ: Int): List<MicroChunkPos> {
        val center = MicroChunkPos.containing(focusX, focusY, focusZ)
        val r = quality.viewRadius
        loaded.keys.filter { max(abs(it.x - center.x), abs(it.y - center.y)) > r + 1 }.forEach { loaded.remove(it) }
        val wanted = ArrayList<MicroChunkPos>()
        for (dy in -r..r) for (dx in -r..r) {
            val cx = center.x + dx; val cy = center.y + dy
            for (cz in verticalRange(cx, cy)) {
                val p = MicroChunkPos(cx, cy, cz)
                if (p !in loaded) wanted += p
            }
        }
        wanted.sortBy { (it.x - center.x) * (it.x - center.x) + (it.y - center.y) * (it.y - center.y) + (it.z - center.z) * (it.z - center.z) }
        return wanted.take(quality.chunksPerFrame).onEach { chunk(it) }
    }

    /** Vertical chunks that can hold anything at a column: from under the lowest ground to over the tallest thing built on it. */
    fun verticalRange(cx: Int, cy: Int): IntRange {
        val columns = generator.fields.get(Fields.COLUMNS) ?: return 0..3
        val c = columns.columns(cx, cy)
        val sea = generator.fields.get(Fields.SEA_LEVEL) ?: 0
        val lo = minOf(c.heights.min(), sea) - 16
        val hi = maxOf(c.heights.max(), sea) + 260 // tall enough for a tower and its mast
        return Math.floorDiv(lo, MicroChunk.SIZE)..Math.floorDiv(hi, MicroChunk.SIZE)
    }

    /** The player's changes, the whole of a save file. */
    fun exportEdits(): Map<MicroChunkPos, Map<Int, Short>> = edits.mapValues { HashMap(it.value) }

    fun importEdits(saved: Map<MicroChunkPos, Map<Int, Short>>) {
        saved.forEach { (pos, cells) ->
            edits.getOrPut(pos) { HashMap() }.putAll(cells)
            loaded.remove(pos) // regenerated with the edits on next access
        }
    }

    private fun markDirty(pos: MicroChunkPos, lx: Int, ly: Int, lz: Int) {
        dirty += pos
        val e = MicroChunk.SIZE - 1
        if (lx == 0) dirty += pos.copy(x = pos.x - 1)
        if (lx == e) dirty += pos.copy(x = pos.x + 1)
        if (ly == 0) dirty += pos.copy(y = pos.y - 1)
        if (ly == e) dirty += pos.copy(y = pos.y + 1)
        if (lz == 0) dirty += pos.copy(z = pos.z - 1)
        if (lz == e) dirty += pos.copy(z = pos.z + 1)
    }
}
