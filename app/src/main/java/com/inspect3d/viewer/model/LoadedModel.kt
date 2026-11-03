package com.inspect3d.viewer.model

import com.inspect3d.viewer.core.Vec3

/**
 * What a parser produces: triangles in the coordinates and units of the source file.
 *
 * [normals] may be `null`, in which case flat (per-face) normals are generated, which is
 * the right default for mechanical parts coming out of CAD.
 */
class RawMesh(
    val positions: FloatArray,
    val normals: FloatArray?,
    val indices: IntArray,
    val unit: String? = null,
    val objectCount: Int = 1,
)

/**
 * A model that is ready to render: positions are centred on the origin and scaled so the
 * largest half-extent is 1 world unit, which lets the camera, the grid, the clipping plane
 * and the marker sizes all work in one predictable range regardless of whether the file was
 * authored in millimetres or metres.
 *
 * [worldPerSourceUnit] is kept so measurements can be reported back in the file's own
 * units: `sourceLength = worldLength / worldPerSourceUnit`.
 */
class LoadedModel(
    val name: String,
    val format: ModelFormat,
    val unit: String?,
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
    val sourceBounds: Bounds,
    val worldPerSourceUnit: Float,
    val bvh: Bvh,
    val objectCount: Int,
) {
    val triangleCount: Int get() = indices.size / 3
    val vertexCount: Int get() = positions.size / 3

    /** Model dimensions in the file's own units. */
    val sourceSize: Vec3 get() = sourceBounds.size

    /**
     * Radius of the model's bounding sphere in world units after normalisation. Between
     * 1 (a flat plate) and √3 (a cube), since the largest half-extent is normalised to 1.
     * The camera uses it to frame the model at any viewport shape.
     */
    val normalizedRadius: Float
        get() = (sourceSize * (worldPerSourceUnit * 0.5f)).length

    /** Converts a length measured in world (render) units back to the file's units. */
    fun toSourceLength(worldLength: Float): Float = worldLength / worldPerSourceUnit

    companion object {
        /**
         * Normalises [raw] for rendering: generates flat normals when the file had none,
         * centres and scales the geometry, and builds the picking hierarchy. Expected to
         * run off the main thread — building the BVH for a large mesh takes a while.
         */
        fun from(name: String, format: ModelFormat, raw: RawMesh): LoadedModel {
            if (raw.indices.size < 3) {
                throw ModelParseException("No triangles were found in this file.")
            }

            var positions = raw.positions
            var normals = raw.normals
            var indices = raw.indices

            if (normals == null) {
                val exploded = explodeToFlatShaded(positions, indices)
                positions = exploded.first
                normals = exploded.second
                indices = IntArray(positions.size / 3) { it }
            }

            val sourceBounds = Bounds.of(positions)
            if (!sourceBounds.isValid ||
                sourceBounds.minX.isNaN() || sourceBounds.maxX.isNaN() ||
                sourceBounds.minY.isNaN() || sourceBounds.maxY.isNaN() ||
                sourceBounds.minZ.isNaN() || sourceBounds.maxZ.isNaN()
            ) {
                throw ModelParseException("The geometry in this file has invalid coordinates.")
            }

            val halfExtent = sourceBounds.maxHalfExtent
            val scale = if (halfExtent > 1e-12f) 1f / halfExtent else 1f
            val center = sourceBounds.center

            // Bake the fit transform into the vertex data. Keeping the renderable's own
            // transform at identity means object space == world space, which is what lets
            // the clipping shader work from `getPosition()` (see MaterialFactory).
            val normalized = FloatArray(positions.size)
            var i = 0
            while (i + 2 < positions.size) {
                normalized[i] = (positions[i] - center.x) * scale
                normalized[i + 1] = (positions[i + 1] - center.y) * scale
                normalized[i + 2] = (positions[i + 2] - center.z) * scale
                i += 3
            }

            return LoadedModel(
                name = name,
                format = format,
                unit = raw.unit,
                positions = normalized,
                normals = normals,
                indices = indices,
                sourceBounds = sourceBounds,
                worldPerSourceUnit = scale,
                bvh = Bvh.build(normalized, indices),
                objectCount = raw.objectCount,
            )
        }

        /**
         * Turns an indexed mesh into an unshared triangle soup with per-face normals.
         * Faceted shading is what engineering parts should look like, and it also avoids
         * smoothing across the hard edges that indexed CAD exports usually share.
         */
        private fun explodeToFlatShaded(
            positions: FloatArray,
            indices: IntArray,
        ): Pair<FloatArray, FloatArray> {
            val triCount = indices.size / 3
            val out = FloatArray(triCount * 9)
            val normals = FloatArray(triCount * 9)

            for (t in 0 until triCount) {
                val i0 = indices[t * 3] * 3
                val i1 = indices[t * 3 + 1] * 3
                val i2 = indices[t * 3 + 2] * 3
                val o = t * 9

                out[o] = positions[i0]; out[o + 1] = positions[i0 + 1]; out[o + 2] = positions[i0 + 2]
                out[o + 3] = positions[i1]; out[o + 4] = positions[i1 + 1]; out[o + 5] = positions[i1 + 2]
                out[o + 6] = positions[i2]; out[o + 7] = positions[i2 + 1]; out[o + 8] = positions[i2 + 2]

                val e1x = out[o + 3] - out[o]
                val e1y = out[o + 4] - out[o + 1]
                val e1z = out[o + 5] - out[o + 2]
                val e2x = out[o + 6] - out[o]
                val e2y = out[o + 7] - out[o + 1]
                val e2z = out[o + 8] - out[o + 2]

                var nx = e1y * e2z - e1z * e2y
                var ny = e1z * e2x - e1x * e2z
                var nz = e1x * e2y - e1y * e2x
                val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 1e-20f) {
                    nx /= len; ny /= len; nz /= len
                } else {
                    // Degenerate triangle: any unit normal keeps the shader well defined.
                    nx = 0f; ny = 1f; nz = 0f
                }
                for (v in 0 until 3) {
                    normals[o + v * 3] = nx
                    normals[o + v * 3 + 1] = ny
                    normals[o + v * 3 + 2] = nz
                }
            }
            return out to normals
        }
    }
}
