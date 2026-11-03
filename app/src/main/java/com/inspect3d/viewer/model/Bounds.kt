package com.inspect3d.viewer.model

import com.inspect3d.viewer.core.Vec3
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned bounding box. */
data class Bounds(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
) {
    val center: Vec3 get() = Vec3((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f)
    val size: Vec3 get() = Vec3(maxX - minX, maxY - minY, maxZ - minZ)

    /** Half of the longest edge; 0 for a degenerate (single point) model. */
    val maxHalfExtent: Float
        get() = max(max(maxX - minX, maxY - minY), maxZ - minZ) * 0.5f

    val isValid: Boolean get() = minX <= maxX && minY <= maxY && minZ <= maxZ

    companion object {
        val EMPTY = Bounds(
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
        )

        /** Bounds of an interleaved xyz position array. */
        fun of(positions: FloatArray): Bounds {
            if (positions.isEmpty()) return EMPTY
            var nx = Float.POSITIVE_INFINITY
            var ny = Float.POSITIVE_INFINITY
            var nz = Float.POSITIVE_INFINITY
            var xx = Float.NEGATIVE_INFINITY
            var xy = Float.NEGATIVE_INFINITY
            var xz = Float.NEGATIVE_INFINITY
            var i = 0
            while (i + 2 < positions.size) {
                val x = positions[i]
                val y = positions[i + 1]
                val z = positions[i + 2]
                nx = min(nx, x); ny = min(ny, y); nz = min(nz, z)
                xx = max(xx, x); xy = max(xy, y); xz = max(xz, z)
                i += 3
            }
            return Bounds(nx, ny, nz, xx, xy, xz)
        }
    }
}
