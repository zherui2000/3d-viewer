package com.inspect3d.viewer.parse

import com.inspect3d.viewer.core.FloatList
import com.inspect3d.viewer.core.IntList
import com.inspect3d.viewer.model.ModelParseException
import com.inspect3d.viewer.model.RawMesh

/**
 * Wavefront OBJ reader.
 *
 * Supports `v`, `vn`, `f` with any of the `v`, `v/vt`, `v/vt/vn` and `v//vn` corner forms,
 * negative (relative) indices, and n-gons, which are triangulated with a fan. Texture
 * coordinates and materials are read past: this is an inspection viewer, and geometry plus
 * normals is everything the renderer, the clipping shader and the picker need.
 *
 * If the file supplies no vertex normals (or only supplies them for part of the geometry)
 * the mesh is handed on with `normals = null` and flat per-face normals are generated
 * downstream, which is the correct look for a machined part.
 */
object ObjParser {

    fun parse(bytes: ByteArray): RawMesh {
        val tokenizer = ByteTokenizer(bytes)

        val vertices = FloatList(1 shl 16)
        val normals = FloatList(1 shl 16)

        // One entry per triangle corner; resolved to 0-based vertex/normal indices.
        val cornerVertex = IntList(1 shl 16)
        val cornerNormal = IntList(1 shl 16)

        // Corners of the face currently being read, before fan triangulation.
        val faceVertex = IntList(16)
        val faceNormal = IntList(16)

        var objectCount = 0
        var everyCornerHasNormal = true

        while (tokenizer.hasNext()) {
            when (tokenizer.nextToken()) {
                "v" -> {
                    vertices.add(
                        ByteTokenizer.toFloat(tokenizer.nextTokenInLine()),
                        ByteTokenizer.toFloat(tokenizer.nextTokenInLine()),
                        ByteTokenizer.toFloat(tokenizer.nextTokenInLine()),
                    )
                    // Drop a trailing `w` or per-vertex colour.
                    while (tokenizer.nextTokenInLine() != null) { /* drain the line */ }
                }

                "vn" -> {
                    normals.add(
                        ByteTokenizer.toFloat(tokenizer.nextTokenInLine()),
                        ByteTokenizer.toFloat(tokenizer.nextTokenInLine()),
                        ByteTokenizer.toFloat(tokenizer.nextTokenInLine()),
                    )
                    while (tokenizer.nextTokenInLine() != null) { /* drain the line */ }
                }

                "f" -> {
                    faceVertex.shrink(faceVertex.size)
                    faceNormal.shrink(faceNormal.size)

                    while (true) {
                        val corner = tokenizer.nextTokenInLine() ?: break
                        val slash = corner.indexOf('/')
                        val vToken = if (slash < 0) corner else corner.substring(0, slash)
                        val v = vToken.toIntOrNull() ?: continue

                        var n = 0
                        if (slash >= 0) {
                            val second = corner.indexOf('/', slash + 1)
                            if (second >= 0 && second + 1 < corner.length) {
                                n = corner.substring(second + 1).toIntOrNull() ?: 0
                            }
                        }

                        faceVertex.add(resolve(v, vertices.size / 3))
                        faceNormal.add(if (n == 0) -1 else resolve(n, normals.size / 3))
                    }

                    if (faceVertex.size >= 3) {
                        for (i in 1 until faceVertex.size - 1) {
                            cornerVertex.add(faceVertex[0], faceVertex[i], faceVertex[i + 1])
                            cornerNormal.add(faceNormal[0], faceNormal[i], faceNormal[i + 1])
                        }
                    }
                }

                "o", "g" -> {
                    objectCount++
                    tokenizer.skipLine()
                }

                null -> Unit

                // vt, vp, s, usemtl, mtllib, comments, anything unrecognised.
                else -> if (!tokenizer.lineEnded) tokenizer.skipLine()
            }
        }

        if (cornerVertex.size < 3) {
            throw ModelParseException("No faces were found in this OBJ file.")
        }

        val vertexCount = vertices.size / 3
        val normalCount = normals.size / 3
        val cornerCount = cornerVertex.size

        for (i in 0 until cornerCount) {
            val v = cornerVertex[i]
            if (v < 0 || v >= vertexCount) {
                throw ModelParseException("This OBJ file references vertices that do not exist.")
            }
            val n = cornerNormal[i]
            if (n < 0 || n >= normalCount) everyCornerHasNormal = false
        }

        val objects = objectCount.coerceAtLeast(1)

        if (normalCount == 0 || !everyCornerHasNormal) {
            return RawMesh(
                positions = vertices.toArray(),
                normals = null,
                indices = cornerVertex.toArray(),
                objectCount = objects,
            )
        }

        // The file has usable normals: build the unique (position, normal) pairs the GPU
        // needs, keeping the sharing the author intended so smooth surfaces stay smooth.
        val cache = HashMap<Long, Int>(cornerCount)
        val outPositions = FloatList(cornerCount * 3)
        val outNormals = FloatList(cornerCount * 3)
        val outIndices = IntArray(cornerCount)

        for (i in 0 until cornerCount) {
            val v = cornerVertex[i]
            val n = cornerNormal[i]
            val key = (v.toLong() shl 32) or (n.toLong() and 0xFFFFFFFFL)
            val existing = cache[key]
            if (existing != null) {
                outIndices[i] = existing
            } else {
                val index = outPositions.size / 3
                outPositions.add(vertices[v * 3], vertices[v * 3 + 1], vertices[v * 3 + 2])
                outNormals.add(normals[n * 3], normals[n * 3 + 1], normals[n * 3 + 2])
                cache[key] = index
                outIndices[i] = index
            }
        }

        return RawMesh(
            positions = outPositions.toArray(),
            normals = outNormals.toArray(),
            indices = outIndices,
            objectCount = objects,
        )
    }

    /** OBJ indices are 1-based; negative values count back from the current end. */
    private fun resolve(index: Int, count: Int): Int =
        if (index > 0) index - 1 else count + index
}
