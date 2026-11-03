package com.inspect3d.viewer.parse

import java.nio.charset.StandardCharsets

/**
 * Whitespace tokenizer over a raw ASCII byte buffer, shared by the ASCII STL and OBJ
 * parsers. Working straight off the bytes avoids decoding a potentially hundreds-of-
 * megabytes file into a `String` first, which would double peak memory for no benefit.
 */
class ByteTokenizer(private val data: ByteArray) {

    private var pos = 0

    /** True once the last [nextTokenInLine] call ran into a line break or the end of input. */
    var lineEnded = false
        private set

    fun hasNext(): Boolean {
        skipWhitespace(includeNewlines = true)
        return pos < data.size
    }

    /** Next whitespace-delimited token, crossing line breaks. `null` at end of input. */
    fun nextToken(): String? {
        lineEnded = false
        skipWhitespace(includeNewlines = true)
        if (pos >= data.size) return null
        val start = pos
        while (pos < data.size && !isWhitespace(data[pos])) pos++
        return String(data, start, pos - start, StandardCharsets.US_ASCII)
    }

    /**
     * Next token on the current line, or `null` when the line is exhausted. The line break
     * itself is consumed, so the following call reads the next line's first token.
     */
    fun nextTokenInLine(): String? {
        lineEnded = false
        while (pos < data.size) {
            val b = data[pos]
            if (b == '\n'.code.toByte()) {
                pos++
                lineEnded = true
                return null
            }
            if (b == ' '.code.toByte() || b == '\t'.code.toByte() || b == '\r'.code.toByte()) {
                pos++
                continue
            }
            break
        }
        if (pos >= data.size) {
            lineEnded = true
            return null
        }
        val start = pos
        while (pos < data.size && !isWhitespace(data[pos])) pos++
        return String(data, start, pos - start, StandardCharsets.US_ASCII)
    }

    /** Discards the remainder of the current line, including the line break. */
    fun skipLine() {
        while (pos < data.size && data[pos] != '\n'.code.toByte()) pos++
        if (pos < data.size) pos++
        lineEnded = true
    }

    private fun skipWhitespace(includeNewlines: Boolean) {
        while (pos < data.size) {
            val b = data[pos]
            val isNewline = b == '\n'.code.toByte() || b == '\r'.code.toByte()
            if (isNewline && !includeNewlines) return
            if (!isWhitespace(b)) return
            pos++
        }
    }

    private fun isWhitespace(b: Byte): Boolean =
        b == ' '.code.toByte() || b == '\t'.code.toByte() ||
            b == '\n'.code.toByte() || b == '\r'.code.toByte()

    companion object {
        /** Lenient float parse: returns [fallback] instead of throwing on malformed input. */
        fun toFloat(token: String?, fallback: Float = 0f): Float =
            if (token == null) fallback else token.toFloatOrNull() ?: fallback
    }
}
