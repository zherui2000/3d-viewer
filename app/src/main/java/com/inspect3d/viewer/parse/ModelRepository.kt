package com.inspect3d.viewer.parse

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.inspect3d.viewer.model.LoadedModel
import com.inspect3d.viewer.model.ModelFormat
import com.inspect3d.viewer.model.ModelParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Turns a Storage Access Framework document into a renderable model.
 *
 * Everything here runs off the main thread: reading a document through a
 * `ContentResolver` can touch the network (cloud drives), and parsing plus building the
 * picking hierarchy is CPU-heavy for large meshes.
 */
class ModelRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun load(uri: Uri): LoadedModel = withContext(ioDispatcher) {
        val displayName = queryDisplayName(uri)
        val bytes = try {
            readBytes(uri)
        } catch (e: IOException) {
            throw ModelParseException("This file could not be read: ${e.message ?: "I/O error"}", e)
        } catch (e: SecurityException) {
            throw ModelParseException("Permission to read this file was denied.", e)
        } catch (e: OutOfMemoryError) {
            throw ModelParseException("This file is too large to open on this device.", e)
        }

        if (bytes.size < 16) {
            throw ModelParseException("This file is empty or truncated.")
        }

        val format = ModelFormat.fromFileName(displayName)
            ?: sniffFormat(bytes)
            ?: throw ModelParseException(
                "Unsupported file type. Open one of ${ModelFormat.supportedExtensionsLabel}.",
            )

        val raw = try {
            when (format) {
                ModelFormat.STL -> StlParser.parse(bytes)
                ModelFormat.OBJ -> ObjParser.parse(bytes)
                ModelFormat.THREE_MF -> ThreeMfParser.parse(bytes)
                ModelFormat.GLTF -> GltfParser.parse(bytes)
                ModelFormat.STEP -> StepParser.parse(bytes)
            }
        } catch (e: ModelParseException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw ModelParseException("This model is too large to open on this device.", e)
        } catch (e: Exception) {
            // Corrupted files surface here as index/format errors; keep the message useful.
            throw ModelParseException(
                "This ${format.label} file appears to be corrupted (${e.javaClass.simpleName}).",
                e,
            )
        }

        LoadedModel.from(
            name = displayName ?: "model.${format.extensions.first()}",
            format = format,
            raw = raw,
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun readBytes(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("the document could not be opened")

        stream.use { input ->
            val out = ByteArrayOutputStream(DEFAULT_BUFFER)
            val buffer = ByteArray(DEFAULT_BUFFER)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_FILE_BYTES) {
                    throw IOException("the file is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB")
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /** Last resort when the picker gives no usable file name: look at the bytes. */
    private fun sniffFormat(bytes: ByteArray): ModelFormat? {
        if (bytes.size >= 4) {
            // "glTF"
            if (bytes[0] == 0x67.toByte() && bytes[1] == 0x6C.toByte() &&
                bytes[2] == 0x54.toByte() && bytes[3] == 0x46.toByte()
            ) {
                return ModelFormat.GLTF
            }
            // "PK\u0003\u0004" — a zip container, which for this app means 3MF.
            if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                return ModelFormat.THREE_MF
            }
        }

        val head = String(bytes, 0, minOf(512, bytes.size), Charsets.US_ASCII)
        val trimmed = head.trimStart()
        return when {
            // Every ISO 10303-21 exchange file opens with this exact line.
            trimmed.startsWith("ISO-10303-21") -> ModelFormat.STEP
            trimmed.startsWith("{") -> ModelFormat.GLTF
            trimmed.startsWith("solid", ignoreCase = true) -> ModelFormat.STL
            Regex("(?m)^\\s*(v|vn|vt|f)\\s").containsMatchIn(head) -> ModelFormat.OBJ
            bytes.size > 84 -> ModelFormat.STL // most likely a binary STL
            else -> null
        }
    }

    private companion object {
        const val DEFAULT_BUFFER = 1 shl 16

        /** Above this, parsing would exhaust the heap on most phones. */
        const val MAX_FILE_BYTES = 300L * 1024 * 1024
    }
}
