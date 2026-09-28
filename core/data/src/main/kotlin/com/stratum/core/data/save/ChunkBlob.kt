package com.stratum.core.data.save

import com.stratum.core.domain.session.SavedChunk
import com.stratum.core.domain.world.Chunk
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * A world's changed chunks as one deflated binary file.
 *
 * A chunk is [Chunk.VOLUME] cells of mostly air and a few kinds of rock,
 * which deflate takes from 24 KB to a few hundred bytes; JSON would have
 * spelt every cell out. The header -- a magic number and a version -- is
 * written before the compressed body, so a file that is not ours, or is
 * from a newer build, is refused before anything is inflated.
 */
internal object ChunkBlob {

    private const val MAGIC = 0x53544348 // "STCH"
    const val VERSION = 1

    fun encode(chunks: List<SavedChunk>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run {
            writeInt(MAGIC)
            writeInt(VERSION)
            flush()
        }
        val cells = ByteBuffer.allocate(Chunk.VOLUME * Short.SIZE_BYTES)
        DataOutputStream(DeflaterOutputStream(bytes)).use { out ->
            out.writeInt(chunks.size)
            chunks.forEach { chunk ->
                out.writeInt(chunk.x)
                out.writeInt(chunk.y)
                cells.clear()
                cells.asShortBuffer().put(chunk.blocks)
                out.write(cells.array())
            }
        }
        return bytes.toByteArray()
    }

    /** Throws [IOException] for anything that is not a whole, readable chunk file. */
    fun decode(bytes: ByteArray): List<SavedChunk> {
        val header = DataInputStream(bytes.inputStream())
        if (bytes.size < HEADER_BYTES || header.readInt() != MAGIC) throw IOException("not a chunk file")
        val version = header.readInt()
        if (version !in 1..VERSION) throw IOException("chunk file version $version is newer than $VERSION")
        val body = DataInputStream(InflaterInputStream(bytes.inputStream(HEADER_BYTES, bytes.size - HEADER_BYTES)))
        return body.use { input ->
            val count = input.readInt()
            if (count !in 0..MAX_CHUNKS) throw IOException("implausible chunk count $count")
            val raw = ByteArray(Chunk.VOLUME * Short.SIZE_BYTES)
            List(count) {
                val x = input.readInt()
                val y = input.readInt()
                input.readFully(raw)
                val cells = ShortArray(Chunk.VOLUME)
                ByteBuffer.wrap(raw).asShortBuffer().get(cells)
                SavedChunk(x, y, cells)
            }
        }
    }

    private const val HEADER_BYTES = 8

    /** Far more than anyone digs; a count past it is a corrupt file, not a big world. */
    private const val MAX_CHUNKS = 1 shl 20
}
