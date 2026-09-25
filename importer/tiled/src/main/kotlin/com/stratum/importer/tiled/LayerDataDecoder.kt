package com.stratum.importer.tiled

import com.stratum.core.domain.importing.ImportException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Decodes a tile layer's data into global tile ids, whichever of Tiled's
 * encodings the author saved it in: CSV, or base64 over raw, zlib or gzip
 * bytes. Zstandard is refused by name, because the JVM has no decoder and a
 * silently empty layer would be worse than an error.
 */
object LayerDataDecoder {

    fun decode(encoding: String?, compression: String?, data: String, expected: Int): IntArray {
        val gids = when (encoding?.lowercase()) {
            "csv" -> runCatching { fromCsv(data) }.getOrElse { throw ImportException("Layer CSV data is not a list of tile ids", it) }
            "base64" -> fromLittleEndian(inflate(fromBase64(data), compression))
            null, "" -> throw ImportException("Layer data has no encoding")
            else -> throw ImportException("Unknown layer encoding '$encoding'")
        }
        if (gids.size != expected) throw ImportException("A layer holds ${gids.size} tiles where ${expected} were expected")
        return gids
    }

    fun fromCsv(data: String): IntArray =
        data.split(',').map(String::trim).filter(String::isNotEmpty).map { it.toLong().toInt() }.toIntArray()

    /** Tiled's JSON can also store data as a plain array of numbers. */
    fun fromNumbers(numbers: List<Long>): IntArray = IntArray(numbers.size) { numbers[it].toInt() }

    /**
     * Kotlin's decoder rather than `java.util.Base64`, which Android only has
     * from API 26. Tiled wraps long data across lines, so whitespace goes first.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun fromBase64(data: String): ByteArray = Base64.Default.decode(data.filterNot(Char::isWhitespace))

    private fun inflate(bytes: ByteArray, compression: String?): ByteArray = when (compression?.lowercase()) {
        null, "" -> bytes
        "zlib" -> InflaterInputStream(ByteArrayInputStream(bytes)).use(::readAll)
        "gzip" -> GZIPInputStream(ByteArrayInputStream(bytes)).use(::readAll)
        "zstd" -> throw ImportException("Zstandard-compressed layers are not supported; save the map with zlib, gzip or CSV")
        else -> throw ImportException("Unknown layer compression '$compression'")
    }

    private fun fromLittleEndian(bytes: ByteArray): IntArray {
        if (bytes.size % 4 != 0) throw ImportException("Layer data is not a whole number of tile ids")
        return IntArray(bytes.size / 4) { tile ->
            val at = tile * 4
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
        }
    }

    private fun readAll(stream: java.io.InputStream): ByteArray =
        ByteArrayOutputStream().also { stream.copyTo(it) }.toByteArray()
}
