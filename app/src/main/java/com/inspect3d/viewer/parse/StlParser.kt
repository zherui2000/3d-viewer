package com.inspect3d.viewer.parse

import com.inspect3d.viewer.core.FloatList
import com.inspect3d.viewer.model.ModelParseException
import com.inspect3d.viewer.model.RawMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * STL reader covering both flavours of the format.
 *
 * STL has no vertex sharing and no units, so every triangle contributes three vertices and
 * the facet normal is used for shading when the file provides a usable one (many exporters
 * write zeroes, in which case the normal is derived from the winding order).
 */
object StlParser {

    private const val BINARY_HEADER_SIZE = 84
    private const val BINARY_TRIANGLE_SIZE = 50

    fun parse(bytes: ByteArray): RawMesh =
        if (looksBinary(bytes)) parseBinary(bytes) else parseAscii(bytes)

    private fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.size < BINARY_HEADER_SIZE) return false

        // The reliable test is whether the declared triangle count matches the file length;
        // the leading "solid" keyword is not trustworthy, some binary writers emit it too.
        val declared = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (declared > 0) {
            val expected = BINARY_HEADER_SIZE.toLong() + declared.toLong() * BINARY_TRIANGLE_SIZE
            if (expected == bytes.size.toLong()) return true
            // Some writers pad the end of the file; accept a small surplus.
            if (bytes.size >= expected && bytes.size - expected < 1024) return true
        }

        val head = String(bytes, 0, minOf(5, bytes.size), Charsets.US_ASCII).lowercase()
        return head != "solid"
    }

    private fun parseBinary(bytes: ByteArray): RawMesh {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        val declared = buffer.int
        val available = (bytes.size - BINARY_HEADER_SIZE) / BINARY_TRIANGLE_SIZE
        val triangleCount = minOf(declared, available)
        if (triangleCount <= 0) {
            throw ModelParseException("This STL file contains no triangles.")
        }

        val positions = FloatArray(triangleCount * 9)
        val normals = FloatArray(triangleCount * 9)

        for (t in 0 until triangleCount) {
            val nx = buffer.float
            val ny = buffer.float
            val nz = buffer.float
            val o = t * 9
            for (v in 0 until 3) {
                positions[o + v * 3] = buffer.float
                positions[o + v * 3 + 1] = buffer.float
                positions[o + v * 3 + 2] = buffer.float
            }
            buffer.short // attribute byte count, unused
            writeFacetNormal(positions, normals, o, nx, ny, nz)
        }

        return RawMesh(positions = positions, normals = normals, indices = IntArray(triangleCount * 3) { it })
    }

    private fun parseAscii(bytes: ByteArray): RawMesh {
        val tokenizer = ByteTokenizer(bytes)
        val positions = FloatList(1 shl 16)
        val normals = FloatList(1 shl 16)

        var nx = 0f
        var ny = 0f
        var nz = 0f
        var vertexInFacet = 0
        var facetStart = 0

        while (true) {
            val token = tokenizer.nextToken() ?: break
            when (token) {
                "facet" -> {
                    // "facet normal ni nj nk"
                    if (tokenizer.nextToken() == "normal") {
                        nx = ByteTokenizer.toFloat(tokenizer.nextToken())
                        ny = ByteTokenizer.toFloat(tokenizer.nextToken())
                        nz = ByteTokenizer.toFloat(tokenizer.nextToken())
                    } else {
                        nx = 0f; ny = 0f; nz = 0f
                    }
                    vertexInFacet = 0
                    facetStart = positions.size
                }

                "vertex" -> {
                    positions.add(
                        ByteTokenizer.toFloat(tokenizer.nextToken()),
                        ByteTokenizer.toFloat(tokenizer.nextToken()),
                        ByteTokenizer.toFloat(tokenizer.nextToken()),
                    )
                    normals.add(0f, 0f, 0f)
                    vertexInFacet++
                }

                "endfacet" -> {
                    if (vertexInFacet == 3) {
                        writeFacetNormal(positions.data, normals.data, facetStart, nx, ny, nz)
                    } else if (vertexInFacet in 1..2) {
                        // Truncated facet: drop the partial triangle.
                        rollback(positions, normals, facetStart)
                    }
                    vertexInFacet = 0
                }
            }
        }

        // A file that ends part-way through a facet leaves vertices belonging to no
        // triangle. They would never be drawn, but they would still count towards the
        // model's bounds and so distort both the reported size and the camera framing.
        val complete = (positions.size / 9) * 9
        if (positions.size != complete) {
            positions.shrink(positions.size - complete)
            normals.shrink(normals.size - complete)
        }

        if (positions.size < 9) {
            throw ModelParseException("This STL file contains no triangles.")
        }

        val pos = positions.toArray()
        val nrm = normals.toArray()

        // Files that omit "endfacet" (or write zero normals) leave gaps; derive those
        // normals from the winding order so shading is never left undefined.
        var o = 0
        while (o + 8 < pos.size) {
            val lenSq = nrm[o] * nrm[o] + nrm[o + 1] * nrm[o + 1] + nrm[o + 2] * nrm[o + 2]
            if (lenSq < 0.25f) writeFacetNormal(pos, nrm, o, 0f, 0f, 0f)
            o += 9
        }

        return RawMesh(positions = pos, normals = nrm, indices = IntArray(pos.size / 3) { it })
    }

    private fun rollback(positions: FloatList, normals: FloatList, facetStart: Int) {
        positions.shrink(positions.size - facetStart)
        normals.shrink(normals.size - facetStart)
    }

    /**
     * Fills the three vertex normals of the facet starting at [offset]. Uses the facet
     * normal from the file when it is a usable unit-ish vector, otherwise the winding.
     */
    private fun writeFacetNormal(
        positions: FloatArray,
        normals: FloatArray,
        offset: Int,
        nx: Float,
        ny: Float,
        nz: Float,
    ) {
        var x = nx
        var y = ny
        var z = nz
        val len = sqrt(x * x + y * y + z * z)
        if (len < 0.5f || len.isNaN()) {
            val e1x = positions[offset + 3] - positions[offset]
            val e1y = positions[offset + 4] - positions[offset + 1]
            val e1z = positions[offset + 5] - positions[offset + 2]
            val e2x = positions[offset + 6] - positions[offset]
            val e2y = positions[offset + 7] - positions[offset + 1]
            val e2z = positions[offset + 8] - positions[offset + 2]
            x = e1y * e2z - e1z * e2y
            y = e1z * e2x - e1x * e2z
            z = e1x * e2y - e1y * e2x
            val l2 = sqrt(x * x + y * y + z * z)
            if (l2 > 1e-20f) {
                x /= l2; y /= l2; z /= l2
            } else {
                x = 0f; y = 1f; z = 0f
            }
        } else if (abs(len - 1f) > 1e-3f) {
            x /= len; y /= len; z /= len
        }
        for (v in 0 until 3) {
            normals[offset + v * 3] = x
            normals[offset + v * 3 + 1] = y
            normals[offset + v * 3 + 2] = z
        }
    }
}
