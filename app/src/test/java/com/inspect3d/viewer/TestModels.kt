package com.inspect3d.viewer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds model files in memory so the parser tests own their input exactly. Each helper
 * writes the real container format rather than a simplified stand-in — a test against a
 * fake container would not prove the parser reads the real one.
 */
object TestModels {

    /** One triangle: normal xyz, then three vertices. */
    fun triangle(
        normal: Triple<Float, Float, Float>,
        a: Triple<Float, Float, Float>,
        b: Triple<Float, Float, Float>,
        c: Triple<Float, Float, Float>,
    ): FloatArray = floatArrayOf(
        normal.first, normal.second, normal.third,
        a.first, a.second, a.third,
        b.first, b.second, b.third,
        c.first, c.second, c.third,
    )

    /** A 10 x 5 quad in the z = 0 plane, facing +Z. */
    val QUAD: List<FloatArray> = listOf(
        triangle(Triple(0f, 0f, 1f), Triple(0f, 0f, 0f), Triple(10f, 0f, 0f), Triple(10f, 5f, 0f)),
        triangle(Triple(0f, 0f, 1f), Triple(0f, 0f, 0f), Triple(10f, 5f, 0f), Triple(0f, 5f, 0f)),
    )

    fun binaryStl(triangles: List<FloatArray>, header: String = "test"): ByteArray {
        val buffer = ByteBuffer.allocate(84 + triangles.size * 50).order(ByteOrder.LITTLE_ENDIAN)
        val head = ByteArray(80)
        val headerBytes = header.toByteArray(Charsets.US_ASCII)
        headerBytes.copyInto(head, 0, 0, minOf(80, headerBytes.size))
        buffer.put(head)
        buffer.putInt(triangles.size)
        for (t in triangles) {
            for (f in t) buffer.putFloat(f)
            buffer.putShort(0)
        }
        return buffer.array()
    }

    fun asciiStl(triangles: List<FloatArray>): ByteArray = buildString {
        append("solid test\n")
        for (t in triangles) {
            append("facet normal ${t[0]} ${t[1]} ${t[2]}\n")
            append("  outer loop\n")
            for (v in 0 until 3) {
                append("    vertex ${t[3 + v * 3]} ${t[4 + v * 3]} ${t[5 + v * 3]}\n")
            }
            append("  endloop\n")
            append("endfacet\n")
        }
        append("endsolid test\n")
    }.toByteArray(Charsets.US_ASCII)

    /** Wraps a JSON chunk and an optional BIN chunk in a real GLB container. */
    fun glb(json: String, bin: ByteArray?): ByteArray {
        var jsonBytes = json.toByteArray(Charsets.UTF_8)
        while (jsonBytes.size % 4 != 0) jsonBytes += ' '.code.toByte()

        var binBytes = bin ?: ByteArray(0)
        while (binBytes.size % 4 != 0) binBytes += 0.toByte()

        val hasBin = bin != null
        val total = 12 + 8 + jsonBytes.size + (if (hasBin) 8 + binBytes.size else 0)
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67) // "glTF"
        out.putInt(2)
        out.putInt(total)
        out.putInt(jsonBytes.size)
        out.putInt(0x4E4F534A) // "JSON"
        out.put(jsonBytes)
        if (hasBin) {
            out.putInt(binBytes.size)
            out.putInt(0x004E4942) // "BIN\0"
            out.put(binBytes)
        }
        return out.array()
    }

    /** Positions then UNSIGNED_SHORT indices, which is what [GLTF_QUAD_JSON] describes. */
    fun quadGltfBin(): ByteArray {
        val buffer = ByteBuffer.allocate(48 + 12).order(ByteOrder.LITTLE_ENDIAN)
        // 4 x 2 quad in the z = 0 plane
        floatArrayOf(
            0f, 0f, 0f,
            4f, 0f, 0f,
            4f, 2f, 0f,
            0f, 2f, 0f,
        ).forEach { buffer.putFloat(it) }
        intArrayOf(0, 1, 2, 0, 2, 3).forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    const val GLTF_QUAD_JSON = """
        {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
         "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
         "accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3"},
                      {"bufferView":1,"componentType":5123,"count":6,"type":"SCALAR"}],
         "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":48},
                        {"buffer":0,"byteOffset":48,"byteLength":12}],
         "buffers":[{"byteLength":60}]}
    """

    /**
     * A 3MF package. [transform] is the build item's 3x4 row-major affine, so a test can
     * check that the geometry comes back scaled or placed as the item asked.
     */
    fun threeMf(
        unit: String = "millimeter",
        transform: String? = null,
        vertices: List<Triple<Float, Float, Float>>,
        triangles: List<Triple<Int, Int, Int>>,
        includeModelPart: Boolean = true,
    ): ByteArray {
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<model unit="$unit" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">""")
            append("<resources><object id=\"1\" type=\"model\"><mesh><vertices>")
            vertices.forEach { append("""<vertex x="${it.first}" y="${it.second}" z="${it.third}"/>""") }
            append("</vertices><triangles>")
            triangles.forEach { append("""<triangle v1="${it.first}" v2="${it.second}" v3="${it.third}"/>""") }
            append("</triangles></mesh></object></resources><build>")
            append(if (transform == null) """<item objectid="1"/>""" else """<item objectid="1" transform="$transform"/>""")
            append("</build></model>")
        }

        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            if (includeModelPart) {
                zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** Unit cube corners, shared by several tests. */
    val CUBE_VERTICES = listOf(
        Triple(0f, 0f, 0f), Triple(10f, 0f, 0f), Triple(10f, 10f, 0f), Triple(0f, 10f, 0f),
        Triple(0f, 0f, 10f), Triple(10f, 0f, 10f), Triple(10f, 10f, 10f), Triple(0f, 10f, 10f),
    )

    val CUBE_TRIANGLES = listOf(
        Triple(0, 2, 1), Triple(0, 3, 2), Triple(4, 5, 6), Triple(4, 6, 7),
        Triple(0, 1, 5), Triple(0, 5, 4), Triple(1, 2, 6), Triple(1, 6, 5),
        Triple(2, 3, 7), Triple(2, 7, 6), Triple(3, 0, 4), Triple(3, 4, 7),
    )
}
