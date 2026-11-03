package com.inspect3d.viewer.parse

import com.inspect3d.viewer.core.FloatList
import com.inspect3d.viewer.core.IntList
import com.inspect3d.viewer.core.Mat4
import com.inspect3d.viewer.model.ModelParseException
import com.inspect3d.viewer.model.RawMesh
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * glTF 2.0 reader for `.gltf` (JSON, with embedded base64 buffers) and `.glb` (binary
 * container) files.
 *
 * The viewer needs geometry, not a material pipeline, so this reads the scene graph,
 * flattens every mesh instance through its node transforms, and keeps POSITION and NORMAL.
 * Materials, textures, animation, skinning, morph targets and Draco compression are
 * ignored; a Draco-compressed or external-buffer file is reported as an error the user can
 * act on rather than silently rendering nothing.
 */
object GltfParser {

    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"

    private const val BYTE = 5120
    private const val UNSIGNED_BYTE = 5121
    private const val SHORT = 5122
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val FLOAT = 5126

    private const val MODE_TRIANGLES = 4
    private const val MAX_NESTING = 64

    fun parse(bytes: ByteArray): RawMesh {
        val container = readContainer(bytes)
        val root = try {
            JSONObject(container.json)
        } catch (e: JSONException) {
            throw ModelParseException("The glTF JSON in this file could not be read.", e)
        }

        root.optJSONArray("extensionsRequired")?.let { required ->
            for (i in 0 until required.length()) {
                val extension = required.optString(i)
                if (extension == "KHR_draco_mesh_compression") {
                    throw ModelParseException(
                        "This file uses Draco mesh compression, which this viewer cannot decode.",
                    )
                }
            }
        }

        val buffers = readBuffers(root, container.bin)
        val bufferViews = root.optJSONArray("bufferViews") ?: JSONArray()
        val accessors = root.optJSONArray("accessors") ?: JSONArray()
        val meshes = root.optJSONArray("meshes")
            ?: throw ModelParseException("This glTF file contains no meshes.")
        val nodes = root.optJSONArray("nodes")

        val positions = FloatList(1 shl 16)
        val normals = FloatList(1 shl 16)
        val indices = IntList(1 shl 16)
        var everyPrimitiveHasNormals = true
        var instanceCount = 0

        fun emitMesh(meshIndex: Int, transform: FloatArray) {
            val mesh = meshes.optJSONObject(meshIndex) ?: return
            val primitives = mesh.optJSONArray("primitives") ?: return
            val normalMatrix = Mat4.normalMatrix(transform)

            for (p in 0 until primitives.length()) {
                val primitive = primitives.optJSONObject(p) ?: continue
                if (primitive.optInt("mode", MODE_TRIANGLES) != MODE_TRIANGLES) continue

                val attributes = primitive.optJSONObject("attributes") ?: continue
                if (!attributes.has("POSITION")) continue

                val positionAccessor = attributes.optInt("POSITION", -1)
                val vertices = readVec3(accessors, bufferViews, buffers, positionAccessor)
                    ?: continue
                val vertexCount = vertices.size / 3

                val sourceNormals = if (attributes.has("NORMAL")) {
                    readVec3(accessors, bufferViews, buffers, attributes.optInt("NORMAL", -1))
                } else {
                    null
                }
                if (sourceNormals == null || sourceNormals.size != vertices.size) {
                    everyPrimitiveHasNormals = false
                }

                val base = positions.size / 3
                val scratch = FloatArray(3)
                var i = 0
                while (i + 2 < vertices.size) {
                    Mat4.transformPoint(transform, vertices[i], vertices[i + 1], vertices[i + 2], scratch, 0)
                    positions.add(scratch[0], scratch[1], scratch[2])
                    if (sourceNormals != null && i + 2 < sourceNormals.size) {
                        Mat4.transformDirection(
                            normalMatrix,
                            sourceNormals[i], sourceNormals[i + 1], sourceNormals[i + 2],
                            scratch, 0,
                        )
                        val length = kotlin.math.sqrt(
                            scratch[0] * scratch[0] + scratch[1] * scratch[1] + scratch[2] * scratch[2],
                        )
                        if (length > 1e-20f) {
                            normals.add(scratch[0] / length, scratch[1] / length, scratch[2] / length)
                        } else {
                            normals.add(0f, 1f, 0f)
                        }
                    } else {
                        normals.add(0f, 1f, 0f)
                    }
                    i += 3
                }

                val primitiveIndices = if (primitive.has("indices")) {
                    readIndices(accessors, bufferViews, buffers, primitive.optInt("indices", -1))
                } else {
                    IntArray(vertexCount) { it }
                }
                if (primitiveIndices == null) continue

                var t = 0
                while (t + 2 < primitiveIndices.size) {
                    val a = primitiveIndices[t]
                    val b = primitiveIndices[t + 1]
                    val c = primitiveIndices[t + 2]
                    if (a in 0 until vertexCount && b in 0 until vertexCount && c in 0 until vertexCount) {
                        indices.add(base + a, base + b, base + c)
                    }
                    t += 3
                }
                instanceCount++
            }
        }

        if (nodes != null && nodes.length() > 0) {
            for (nodeIndex in sceneRoots(root, nodes)) {
                walkNode(nodes, nodeIndex, Mat4.identity(), 0) { mesh, transform ->
                    emitMesh(mesh, transform)
                }
            }
        } else {
            for (m in 0 until meshes.length()) emitMesh(m, Mat4.identity())
        }

        if (indices.size < 3) {
            throw ModelParseException("No triangle geometry was found in this glTF file.")
        }

        return RawMesh(
            positions = positions.toArray(),
            normals = if (everyPrimitiveHasNormals) normals.toArray() else null,
            indices = indices.toArray(),
            // glTF is defined in metres.
            unit = "meter",
            objectCount = instanceCount.coerceAtLeast(1),
        )
    }

    // ---------------------------------------------------------------- container

    private class Container(val json: String, val bin: ByteArray?)

    private fun readContainer(bytes: ByteArray): Container {
        if (bytes.size >= 12) {
            val header = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
            if (header.int == GLB_MAGIC) {
                header.int // version, unused: chunk layout is identical for 2.x
                header.int // total length, unused
                return readGlbChunks(bytes)
            }
        }
        return Container(String(bytes, Charsets.UTF_8), null)
    }

    private fun readGlbChunks(bytes: ByteArray): Container {
        var offset = 12
        var json: String? = null
        var bin: ByteArray? = null

        while (offset + 8 <= bytes.size) {
            val head = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
            val length = head.int
            val type = head.int
            val start = offset + 8
            if (length < 0 || start + length > bytes.size) break

            when (type) {
                CHUNK_JSON -> json = String(bytes, start, length, Charsets.UTF_8)
                CHUNK_BIN -> bin = bytes.copyOfRange(start, start + length)
            }
            // Chunks are padded to a four-byte boundary.
            offset = start + length + ((4 - (length % 4)) % 4)
        }

        return Container(
            json ?: throw ModelParseException("This GLB file has no JSON chunk."),
            bin,
        )
    }

    private fun readBuffers(root: JSONObject, bin: ByteArray?): List<ByteArray> {
        val declared = root.optJSONArray("buffers") ?: JSONArray()
        val result = ArrayList<ByteArray>(declared.length())

        for (i in 0 until declared.length()) {
            val buffer = declared.optJSONObject(i)
            val uri = buffer?.optString("uri")?.takeIf { it.isNotEmpty() }

            when {
                uri == null -> {
                    // No URI means the GLB binary chunk.
                    result.add(bin ?: throw ModelParseException("This glTF file is missing its binary data."))
                }

                uri.startsWith("data:") -> {
                    val comma = uri.indexOf(',')
                    if (comma < 0) throw ModelParseException("A data URI in this glTF file is malformed.")
                    val payload = uri.substring(comma + 1)
                    result.add(
                        try {
                            // java.util.Base64 (API 26+) rather than android.util.Base64:
                            // identical behaviour, but it keeps this parser free of
                            // Android types so it can be unit tested on the JVM. The MIME
                            // decoder is the lenient one, ignoring any line wrapping.
                            Base64.getMimeDecoder().decode(payload)
                        } catch (e: IllegalArgumentException) {
                            throw ModelParseException("A data URI in this glTF file could not be decoded.", e)
                        },
                    )
                }

                else -> throw ModelParseException(
                    "This .gltf file keeps its geometry in a separate file ($uri). " +
                        "Open the .glb version, or a .gltf with embedded buffers.",
                )
            }
        }
        return result
    }

    // ---------------------------------------------------------------- scene graph

    private fun sceneRoots(root: JSONObject, nodes: JSONArray): List<Int> {
        val scenes = root.optJSONArray("scenes")
        val sceneIndex = root.optInt("scene", 0)
        val scene = scenes?.optJSONObject(sceneIndex) ?: scenes?.optJSONObject(0)
        val sceneNodes = scene?.optJSONArray("nodes")

        if (sceneNodes != null && sceneNodes.length() > 0) {
            return (0 until sceneNodes.length()).map { sceneNodes.optInt(it, -1) }.filter { it >= 0 }
        }

        // No scene declared: treat every node that is not a child as a root.
        val children = HashSet<Int>()
        for (i in 0 until nodes.length()) {
            val list = nodes.optJSONObject(i)?.optJSONArray("children") ?: continue
            for (c in 0 until list.length()) children.add(list.optInt(c, -1))
        }
        return (0 until nodes.length()).filter { it !in children }
    }

    private fun walkNode(
        nodes: JSONArray,
        nodeIndex: Int,
        parentTransform: FloatArray,
        depth: Int,
        onMesh: (meshIndex: Int, transform: FloatArray) -> Unit,
    ) {
        if (depth > MAX_NESTING) return
        val node = nodes.optJSONObject(nodeIndex) ?: return
        val world = Mat4.multiply(parentTransform, localTransform(node))

        if (node.has("mesh")) onMesh(node.optInt("mesh", -1), world)

        val children = node.optJSONArray("children") ?: return
        for (c in 0 until children.length()) {
            walkNode(nodes, children.optInt(c, -1), world, depth + 1, onMesh)
        }
    }

    private fun localTransform(node: JSONObject): FloatArray {
        node.optJSONArray("matrix")?.let { matrix ->
            if (matrix.length() >= 16) {
                // glTF matrices are already column-major.
                return FloatArray(16) { matrix.optDouble(it, 0.0).toFloat() }
            }
        }

        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")

        val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f
        val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f
        val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f

        val qx = r?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val qy = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val qz = r?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qw = r?.optDouble(3, 1.0)?.toFloat() ?: 1f

        // Rotation matrix columns, scaled in place (M = T * R * S).
        val c0x = (1f - 2f * (qy * qy + qz * qz)) * sx
        val c0y = (2f * (qx * qy + qz * qw)) * sx
        val c0z = (2f * (qx * qz - qy * qw)) * sx
        val c1x = (2f * (qx * qy - qz * qw)) * sy
        val c1y = (1f - 2f * (qx * qx + qz * qz)) * sy
        val c1z = (2f * (qy * qz + qx * qw)) * sy
        val c2x = (2f * (qx * qz + qy * qw)) * sz
        val c2y = (2f * (qy * qz - qx * qw)) * sz
        val c2z = (1f - 2f * (qx * qx + qy * qy)) * sz

        return floatArrayOf(
            c0x, c0y, c0z, 0f,
            c1x, c1y, c1z, 0f,
            c2x, c2y, c2z, 0f,
            t?.optDouble(0, 0.0)?.toFloat() ?: 0f,
            t?.optDouble(1, 0.0)?.toFloat() ?: 0f,
            t?.optDouble(2, 0.0)?.toFloat() ?: 0f,
            1f,
        )
    }

    // ---------------------------------------------------------------- accessors

    private class AccessorView(
        val data: ByteBuffer,
        val baseOffset: Int,
        val stride: Int,
        val componentType: Int,
        val componentCount: Int,
        val count: Int,
        val normalized: Boolean,
    )

    private fun accessorView(
        accessors: JSONArray,
        bufferViews: JSONArray,
        buffers: List<ByteArray>,
        index: Int,
    ): AccessorView? {
        val accessor = accessors.optJSONObject(index) ?: return null
        if (accessor.has("sparse")) {
            throw ModelParseException("This glTF file uses sparse accessors, which are not supported.")
        }
        val viewIndex = accessor.optInt("bufferView", -1)
        if (viewIndex < 0) return null
        val view = bufferViews.optJSONObject(viewIndex) ?: return null
        val bufferIndex = view.optInt("buffer", -1)
        val buffer = buffers.getOrNull(bufferIndex) ?: return null

        val componentType = accessor.optInt("componentType", -1)
        val componentCount = when (accessor.optString("type")) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            "MAT4" -> 16
            else -> return null
        }
        val componentSize = componentSize(componentType) ?: return null
        val declaredStride = view.optInt("byteStride", 0)
        val stride = if (declaredStride > 0) declaredStride else componentSize * componentCount

        return AccessorView(
            data = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN),
            baseOffset = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0),
            stride = stride,
            componentType = componentType,
            componentCount = componentCount,
            count = accessor.optInt("count", 0),
            normalized = accessor.optBoolean("normalized", false),
        )
    }

    private fun componentSize(componentType: Int): Int? = when (componentType) {
        BYTE, UNSIGNED_BYTE -> 1
        SHORT, UNSIGNED_SHORT -> 2
        UNSIGNED_INT, FLOAT -> 4
        else -> null
    }

    /** Reads a VEC3 accessor as floats, de-quantizing normalized integer formats. */
    private fun readVec3(
        accessors: JSONArray,
        bufferViews: JSONArray,
        buffers: List<ByteArray>,
        index: Int,
    ): FloatArray? {
        val view = accessorView(accessors, bufferViews, buffers, index) ?: return null
        if (view.componentCount < 3) return null

        val componentSize = componentSize(view.componentType) ?: return null
        val out = FloatArray(view.count * 3)

        for (i in 0 until view.count) {
            val element = view.baseOffset + i * view.stride
            for (c in 0 until 3) {
                val at = element + c * componentSize
                if (at + componentSize > view.data.capacity()) return null
                out[i * 3 + c] = readComponent(view, at)
            }
        }
        return out
    }

    private fun readComponent(view: AccessorView, at: Int): Float = when (view.componentType) {
        FLOAT -> view.data.getFloat(at)
        BYTE -> view.data.get(at).toFloat().let { if (view.normalized) maxOf(it / 127f, -1f) else it }
        UNSIGNED_BYTE -> (view.data.get(at).toInt() and 0xFF).toFloat()
            .let { if (view.normalized) it / 255f else it }
        SHORT -> view.data.getShort(at).toFloat().let { if (view.normalized) maxOf(it / 32767f, -1f) else it }
        UNSIGNED_SHORT -> (view.data.getShort(at).toInt() and 0xFFFF).toFloat()
            .let { if (view.normalized) it / 65535f else it }
        UNSIGNED_INT -> (view.data.getInt(at).toLong() and 0xFFFFFFFFL).toFloat()
        else -> 0f
    }

    private fun readIndices(
        accessors: JSONArray,
        bufferViews: JSONArray,
        buffers: List<ByteArray>,
        index: Int,
    ): IntArray? {
        val view = accessorView(accessors, bufferViews, buffers, index) ?: return null
        val componentSize = componentSize(view.componentType) ?: return null
        val out = IntArray(view.count)

        for (i in 0 until view.count) {
            val at = view.baseOffset + i * view.stride
            if (at + componentSize > view.data.capacity()) return null
            out[i] = when (view.componentType) {
                UNSIGNED_BYTE, BYTE -> view.data.get(at).toInt() and 0xFF
                UNSIGNED_SHORT, SHORT -> view.data.getShort(at).toInt() and 0xFFFF
                UNSIGNED_INT -> view.data.getInt(at)
                else -> return null
            }
        }
        return out
    }
}
