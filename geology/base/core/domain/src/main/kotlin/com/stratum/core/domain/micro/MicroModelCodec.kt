package com.stratum.core.domain.micro

/**
 * A model's cells as a short string, for saves and plugin files.
 *
 * Run-length pairs (value, count) written as unsigned varints, then base64.
 * Models are mostly air and long runs of one colour, so a statue that fills
 * a quarter of its box saves in a few kilobytes. Its own base64 because the
 * JDK's is not on every Android this runs on.
 */
object MicroModelCodec {

    fun encode(cells: IntArray): String {
        val bytes = java.io.ByteArrayOutputStream(cells.size / 4 + 16)
        var i = 0
        while (i < cells.size) {
            val v = cells[i]
            var run = 1
            while (i + run < cells.size && cells[i + run] == v) run++
            varint(bytes, v); varint(bytes, run)
            i += run
        }
        return base64(bytes.toByteArray())
    }

    /** The cells of [text], which must decode to exactly [count]. */
    fun decode(text: String, count: Int): IntArray {
        val bytes = unbase64(text)
        val out = IntArray(count)
        var at = 0
        var p = 0
        while (p < bytes.size) {
            var v = 0; var shift = 0
            while (true) { val b = bytes[p++].toInt() and 0xFF; v = v or ((b and 0x7F) shl shift); shift += 7; if (b < 0x80) break }
            var run = 0; shift = 0
            while (true) { val b = bytes[p++].toInt() and 0xFF; run = run or ((b and 0x7F) shl shift); shift += 7; if (b < 0x80) break }
            require(at + run <= count) { "model cells run past $count" }
            out.fill(v, at, at + run)
            at += run
        }
        require(at == count) { "model cells hold $at of $count" }
        return out
    }

    private fun varint(out: java.io.ByteArrayOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) { out.write((v and 0x7F) or 0x80); v = v ushr 7 }
        out.write(v)
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun base64(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(ALPHABET[b0 shr 2]); sb.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < bytes.size) ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    private fun unbase64(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var i = 0
        while (i + 3 < clean.length) {
            val c0 = ALPHABET.indexOf(clean[i]); val c1 = ALPHABET.indexOf(clean[i + 1])
            val c2 = if (clean[i + 2] == '=') -1 else ALPHABET.indexOf(clean[i + 2])
            val c3 = if (clean[i + 3] == '=') -1 else ALPHABET.indexOf(clean[i + 3])
            require(c0 >= 0 && c1 >= 0) { "model cells are not base64" }
            out.write((c0 shl 2) or (c1 shr 4))
            if (c2 >= 0) out.write(((c1 and 15) shl 4) or (c2 shr 2))
            if (c3 >= 0) out.write(((c2 and 3) shl 6) or c3)
            i += 4
        }
        return out.toByteArray()
    }
}
