package com.stratum.engine.model.gltf

import com.stratum.engine.model.ModelFormatException

/**
 * The two chunks of a binary glTF file, checked and split.
 *
 * A GLB is a 12-byte header — magic, version, total length — then a JSON
 * chunk, then optionally one binary chunk. Every length in it is checked
 * against the bytes actually present before anything is read, because the
 * file came from a network endpoint: a truncated download or an HTML error
 * page saved as `.glb` must fail here with a sentence, not later with an
 * index out of bounds in the middle of an accessor.
 */
class GlbContainer private constructor(val json: String, val bin: ByteArray?) {

    companion object {
        private const val MAGIC = 0x46546C67 // "glTF", little-endian
        private const val JSON_CHUNK = 0x4E4F534A // "JSON"
        private const val BIN_CHUNK = 0x004E4942 // "BIN\0"
        private const val HEADER = 12
        private const val CHUNK_HEADER = 8

        fun read(bytes: ByteArray, limits: ModelLimits = ModelLimits()): GlbContainer {
            if (bytes.size < HEADER + CHUNK_HEADER) throw ModelFormatException("Too short to be a GLB file (${bytes.size} bytes)")
            if (bytes.size > limits.maxFileBytes) {
                throw ModelFormatException("The model is ${bytes.size / 1_048_576} MB; the limit on a phone is ${limits.maxFileBytes / 1_048_576} MB")
            }
            if (int(bytes, 0) != MAGIC) throw ModelFormatException("Not a GLB file: it does not start with 'glTF'")
            val version = int(bytes, 4)
            if (version != 2) throw ModelFormatException("GLB version $version is not supported; only glTF 2.0 is")
            val declared = int(bytes, 8)
            if ((declared.toLong() and 0xFFFFFFFFL) > bytes.size) {
                throw ModelFormatException("The file says it is $declared bytes but only ${bytes.size} arrived; the download was cut short")
            }
            val end = if (declared < 0) bytes.size else declared.coerceAtMost(bytes.size)

            var offset = HEADER
            var json: String? = null
            var bin: ByteArray? = null
            while (offset + CHUNK_HEADER <= end) {
                val length = int(bytes, offset)
                val type = int(bytes, offset + 4)
                val start = offset + CHUNK_HEADER
                if (length < 0 || start.toLong() + length > end) {
                    throw ModelFormatException("A chunk at byte $offset claims $length bytes, past the end of the file")
                }
                when {
                    json == null -> {
                        if (type != JSON_CHUNK) throw ModelFormatException("The first chunk of a GLB must be JSON")
                        json = String(bytes, start, length, Charsets.UTF_8).trimEnd(' ', '\u0000')
                    }
                    type == BIN_CHUNK && bin == null -> bin = bytes.copyOfRange(start, start + length)
                    // Unknown chunk types are skipped, as the specification requires.
                }
                // Chunks are padded to four bytes.
                offset = start + ((length + 3) and 3.inv())
            }
            return GlbContainer(json ?: throw ModelFormatException("The GLB has no JSON chunk"), bin)
        }

        internal fun int(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
}

/**
 * How much a model may cost a phone.
 *
 * Checked while reading rather than after, so a hostile or merely enormous file
 * is refused before it has been expanded into float arrays several times its
 * size.
 */
data class ModelLimits(
    val maxFileBytes: Int = 48 * 1_048_576,
    val maxVertices: Int = 400_000,
    val maxTriangles: Int = 600_000,
    /** Textures above this edge are downsampled on load; a prop is never drawn larger. */
    val maxTextureEdge: Int = 512,
)
