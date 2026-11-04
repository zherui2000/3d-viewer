package com.inspect3d.viewer.core

/**
 * Turns an Android native-crash tombstone into a short, readable report.
 *
 * From Android 12 the system keeps the tombstone of a process's last native crash and hands
 * it back through `ApplicationExitInfo.getTraceInputStream()` in protobuf form (the
 * `Tombstone` message in AOSP's `system/core/debuggerd/proto/tombstone.proto`). This reads
 * the handful of fields that identify a crash — signal, abort message, the crashing thread's
 * backtrace and the app's last log lines — with a minimal wire-format reader, so no
 * protobuf library is needed for one message.
 *
 * Parsing is defensive throughout: the input is whatever the platform produced, and a
 * report that is partly missing is still far better than none. If the bytes cannot be
 * decoded as a tombstone at all, [format] falls back to the printable strings in them,
 * which still name the library and function that crashed.
 */
object Tombstone {

    /** Frames of the crashing thread's backtrace to keep. */
    private const val MAX_FRAMES = 32

    /** Trailing log lines to keep: the ones just before the crash are the useful ones. */
    private const val MAX_LOG_LINES = 30

    fun format(bytes: ByteArray): String =
        runCatching { decode(bytes) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: printableStrings(bytes)

    // ------------------------------------------------------------------ decoding

    private fun decode(bytes: ByteArray): String {
        val root = Message.parse(bytes)

        val crashingTid = root.varint(6)
        val signal = root.messages(10).firstOrNull()
        val abortMessage = root.string(14)
        val causes = root.messages(15).mapNotNull { it.string(1) }

        // threads: map<uint32, Thread>, i.e. repeated entries of { key = 1, value = 2 }.
        val threads = root.messages(16).mapNotNull { entry ->
            val thread = entry.messages(2).firstOrNull() ?: return@mapNotNull null
            (entry.varint(1) ?: thread.varint(1)) to thread
        }
        val crashing = threads.firstOrNull { it.first == crashingTid }?.second
            ?: threads.firstOrNull()?.second

        // A buffer of messages from nothing recognisable is not a tombstone.
        require(signal != null || abortMessage != null || crashing != null)

        return buildString {
            root.string(2)?.let { appendLine("build:     $it") }
            appendLine("tid:       ${crashingTid ?: "?"}" +
                (crashing?.string(2)?.let { "  ($it)" } ?: ""))
            signal?.let {
                val name = it.string(2) ?: "signal ${it.varint(1) ?: "?"}"
                val code = it.string(4) ?: it.varint(3)?.toString()
                val fault = if (it.varint(8) == 1L) it.varint(9) else null
                append("signal:    $name")
                code?.let { c -> append(" ($c)") }
                fault?.let { f -> append(", fault addr 0x${f.toULong().toString(16)}") }
                appendLine()
            }
            abortMessage?.let { appendLine("abort:     $it") }
            causes.forEach { appendLine("cause:     $it") }

            crashing?.let { thread ->
                val frames = thread.messages(4)
                if (frames.isNotEmpty()) {
                    appendLine()
                    appendLine("backtrace:")
                    frames.take(MAX_FRAMES).forEachIndexed { i, frame -> appendLine(frame(i, frame)) }
                    if (frames.size > MAX_FRAMES) appendLine("  … ${frames.size - MAX_FRAMES} more")
                }
                thread.strings(7).forEach { appendLine("note:      $it") }
            }

            val logs = root.messages(18).flatMap { buffer -> buffer.messages(2) }
            if (logs.isNotEmpty()) {
                appendLine()
                appendLine("last log lines:")
                logs.takeLast(MAX_LOG_LINES).forEach { log ->
                    appendLine("  ${log.string(5) ?: ""}: ${log.string(6)?.trimEnd() ?: ""}")
                }
            }
        }
    }

    private fun frame(index: Int, frame: Message): String {
        val pc = (frame.varint(1) ?: 0L).toULong().toString(16).padStart(16, '0')
        val file = frame.string(6) ?: "<unknown>"
        val function = frame.string(4)?.let { name ->
            " ($name+${frame.varint(5) ?: 0})"
        } ?: ""
        return "  #%02d pc %s  %s%s".format(index, pc, file, function)
    }

    /** Like `strings`: runs of four or more printable ASCII characters, de-duplicated. */
    internal fun printableStrings(bytes: ByteArray): String {
        val found = LinkedHashSet<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 4) found += run.toString()
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
        }
        flush()
        return found.take(200).joinToString("\n")
    }

    // ------------------------------------------------------------------ wire format

    /** One decoded protobuf message: field number to its raw values, in order. */
    private class Message(private val fields: Map<Int, List<Any>>) {

        fun varint(field: Int): Long? = fields[field]?.firstOrNull { it is Long } as Long?

        fun string(field: Int): String? = strings(field).firstOrNull()

        fun strings(field: Int): List<String> =
            fields[field].orEmpty().filterIsInstance<ByteArray>()
                .map { String(it, Charsets.UTF_8) }
                .filter { it.isNotEmpty() }

        fun messages(field: Int): List<Message> =
            fields[field].orEmpty().filterIsInstance<ByteArray>()
                .mapNotNull { runCatching { parse(it) }.getOrNull() }

        companion object {
            fun parse(bytes: ByteArray): Message {
                val fields = HashMap<Int, MutableList<Any>>()
                var pos = 0

                fun readVarint(): Long {
                    var result = 0L
                    var shift = 0
                    while (true) {
                        require(pos < bytes.size && shift < 64) { "truncated varint" }
                        val b = bytes[pos++].toInt()
                        result = result or ((b and 0x7F).toLong() shl shift)
                        if (b and 0x80 == 0) return result
                        shift += 7
                    }
                }

                fun skip(n: Long) {
                    require(n >= 0 && pos + n <= bytes.size) { "field runs past the end" }
                    pos += n.toInt()
                }

                while (pos < bytes.size) {
                    val key = readVarint()
                    val field = (key ushr 3).toInt()
                    require(field > 0) { "invalid field number" }
                    val value: Any? = when ((key and 7).toInt()) {
                        0 -> readVarint()
                        1 -> { skip(8); null }
                        2 -> {
                            val length = readVarint()
                            val start = pos
                            skip(length)
                            bytes.copyOfRange(start, pos)
                        }
                        5 -> { skip(4); null }
                        else -> throw IllegalArgumentException("unsupported wire type")
                    }
                    if (value != null) fields.getOrPut(field) { ArrayList() } += value
                }
                return Message(fields)
            }
        }
    }
}
