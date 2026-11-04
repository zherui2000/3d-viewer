package com.inspect3d.viewer.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TombstoneTest {

    // A minimal protobuf writer, enough to build tombstones shaped like the platform's.
    private class Proto {
        private val out = ByteArrayOutputStream()

        private fun varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        fun int(field: Int, value: Long) = apply { varint((field shl 3).toLong()); varint(value) }
        fun bytes(field: Int, value: ByteArray) = apply {
            varint(((field shl 3) or 2).toLong()); varint(value.size.toLong()); out.write(value)
        }
        fun str(field: Int, value: String) = bytes(field, value.toByteArray())
        fun msg(field: Int, value: Proto) = bytes(field, value.toByteArray())
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun frame(pc: Long, file: String, function: String?, offset: Long) = Proto()
        .int(1, pc)
        .apply { function?.let { str(4, it) } }
        .int(5, offset)
        .str(6, file)

    @Test
    fun decodesSignalAbortAndCrashingThreadBacktrace() {
        val crashing = Proto()
            .int(1, 4242)
            .str(2, "main")
            .msg(4, frame(0x5c2f0, "/apex/com.android.runtime/lib64/bionic/libc.so", "abort", 164))
            .msg(4, frame(0x1a2b3c, "/data/app/x/lib/arm64/libfilament-jni.so", "utils::panic", 52))
        val other = Proto()
            .int(1, 4300)
            .str(2, "RenderThread")
            .msg(4, frame(0x10, "/system/lib64/libOther.so", null, 0))

        val bytes = Proto()
            .str(2, "google/device/build:16/fingerprint")
            .int(6, 4242)
            .msg(10, Proto().int(1, 6).str(2, "SIGABRT").int(3, -1).str(4, "SI_TKILL"))
            .str(14, "Precondition in createSwapChain: unsupported")
            // Map entries: { key = tid, value = Thread }. The non-crashing one comes first.
            .msg(16, Proto().int(1, 4300).msg(2, other))
            .msg(16, Proto().int(1, 4242).msg(2, crashing))
            .msg(18, Proto().str(1, "main").msg(2, Proto().str(5, "Filament").str(6, "Backend: OpenGL\n")))
            .toByteArray()

        val report = Tombstone.format(bytes)

        assertTrue(report, report.contains("signal:    SIGABRT (SI_TKILL)"))
        assertTrue(report, report.contains("abort:     Precondition in createSwapChain: unsupported"))
        assertTrue(report, report.contains("tid:       4242  (main)"))
        assertTrue(report, report.contains("#00 pc 000000000005c2f0  /apex/com.android.runtime/lib64/bionic/libc.so (abort+164)"))
        assertTrue(report, report.contains("#01 pc 00000000001a2b3c  /data/app/x/lib/arm64/libfilament-jni.so (utils::panic+52)"))
        assertTrue(report, !report.contains("libOther.so"))
        assertTrue(report, report.contains("Filament: Backend: OpenGL"))
    }

    @Test
    fun reportsFaultAddressOnlyWhenPresent() {
        val segv = Proto()
            .int(6, 1)
            .msg(10, Proto().int(1, 11).str(2, "SIGSEGV").str(4, "SEGV_MAPERR").int(8, 1).int(9, 0xdeadL))
            .toByteArray()
        assertTrue(Tombstone.format(segv).contains("SIGSEGV (SEGV_MAPERR), fault addr 0xdead"))
    }

    @Test
    fun fallsBackToPrintableStringsForBytesThatAreNotATombstone() {
        val text = "*** *** ***\nsignal 11 (SIGSEGV) in libfilament-jni.so"
        val bytes = byteArrayOf(0x07, 0x7F.toByte()) + text.toByteArray()

        val report = Tombstone.format(bytes)

        assertTrue(report, report.contains("libfilament-jni.so"))
    }

    @Test
    fun neverThrowsOnTruncatedInput() {
        val full = Proto().str(14, "an abort message that gets cut off").toByteArray()
        for (n in 0..full.size) Tombstone.format(full.copyOf(n))
    }
}
