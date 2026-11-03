package com.inspect3d.viewer.model

import com.inspect3d.viewer.core.FloatList
import com.inspect3d.viewer.core.IntList
import com.inspect3d.viewer.core.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bounding volume hierarchy over a triangle soup, built once per loaded model on a
 * background thread. Screen taps are turned into rays and tested against this tree, which
 * keeps picking at a few microseconds even for multi-million-triangle meshes (a linear
 * scan over every triangle would take hundreds of milliseconds and stutter the UI).
 *
 * Nodes are stored in flat arrays rather than objects: `bounds` holds six floats per node
 * and `nodes` holds two ints. For an internal node the pair is
 * `(firstChild, 0)` — children are always allocated as adjacent siblings — and for a leaf
 * it is `(firstTriangle, triangleCount)`.
 */
class Bvh private constructor(
    private val positions: FloatArray,
    private val indices: IntArray,
    private val triangles: IntArray,
    private val bounds: FloatArray,
    private val nodes: IntArray,
    private val nodeCount: Int,
) {

    /** A ray/mesh intersection, in the same space as the mesh positions. */
    data class Hit(
        val point: Vec3,
        val normal: Vec3,
        val triangleIndex: Int,
        val distance: Float,
    )

    /**
     * Casts a ray and returns the closest intersection, or `null` when the ray misses.
     *
     * Triangles are treated as two-sided so that interior surfaces exposed by the section
     * plane can be measured. When [clipPlane] is given (`x, y, z` = plane normal,
     * `w` = offset, the half space `dot(n, p) + w > 0` being the part cut away), hits on
     * the removed side are ignored so taps land on what the user actually sees.
     */
    fun intersect(
        origin: Vec3,
        direction: Vec3,
        clipPlane: FloatArray? = null,
        maxDistance: Float = Float.MAX_VALUE,
    ): Hit? {
        if (nodeCount == 0) return null

        val dir = direction.normalized()
        // Nudge exact zeros so the slab test never produces NaN (0 * infinity).
        val invX = 1f / nonZero(dir.x)
        val invY = 1f / nonZero(dir.y)
        val invZ = 1f / nonZero(dir.z)

        var bestT = maxDistance
        var bestTri = -1
        var bestU = 0f
        var bestV = 0f

        val stack = IntArray(64)
        var sp = 0
        stack[sp++] = 0

        while (sp > 0) {
            val node = stack[--sp]
            if (!intersectsAabb(node, origin, invX, invY, invZ, bestT)) continue

            val first = nodes[node * 2]
            val count = nodes[node * 2 + 1]

            if (count == 0) {
                // Internal node: push both children (order is irrelevant for correctness,
                // the AABB test above culls them against the current best hit).
                stack[sp++] = first
                stack[sp++] = first + 1
                continue
            }

            for (i in first until first + count) {
                val tri = triangles[i]
                val i0 = indices[tri * 3] * 3
                val i1 = indices[tri * 3 + 1] * 3
                val i2 = indices[tri * 3 + 2] * 3

                val ax = positions[i0]; val ay = positions[i0 + 1]; val az = positions[i0 + 2]
                val e1x = positions[i1] - ax
                val e1y = positions[i1 + 1] - ay
                val e1z = positions[i1 + 2] - az
                val e2x = positions[i2] - ax
                val e2y = positions[i2 + 1] - ay
                val e2z = positions[i2 + 2] - az

                // Möller–Trumbore, two-sided.
                val px = dir.y * e2z - dir.z * e2y
                val py = dir.z * e2x - dir.x * e2z
                val pz = dir.x * e2y - dir.y * e2x
                val det = e1x * px + e1y * py + e1z * pz
                if (abs(det) < 1e-12f) continue
                val invDet = 1f / det

                val tx = origin.x - ax
                val ty = origin.y - ay
                val tz = origin.z - az

                val u = (tx * px + ty * py + tz * pz) * invDet
                if (u < -1e-6f || u > 1f + 1e-6f) continue

                val qx = ty * e1z - tz * e1y
                val qy = tz * e1x - tx * e1z
                val qz = tx * e1y - ty * e1x

                val v = (dir.x * qx + dir.y * qy + dir.z * qz) * invDet
                if (v < -1e-6f || u + v > 1f + 1e-6f) continue

                val t = (e2x * qx + e2y * qy + e2z * qz) * invDet
                if (t <= 1e-5f || t >= bestT) continue

                if (clipPlane != null) {
                    val hx = origin.x + dir.x * t
                    val hy = origin.y + dir.y * t
                    val hz = origin.z + dir.z * t
                    val d = clipPlane[0] * hx + clipPlane[1] * hy + clipPlane[2] * hz + clipPlane[3]
                    if (d > 0f) continue
                }

                bestT = t
                bestTri = tri
                bestU = u
                bestV = v
            }
        }

        if (bestTri < 0) return null

        val i0 = indices[bestTri * 3] * 3
        val i1 = indices[bestTri * 3 + 1] * 3
        val i2 = indices[bestTri * 3 + 2] * 3
        val ax = positions[i0]; val ay = positions[i0 + 1]; val az = positions[i0 + 2]
        val e1 = Vec3(positions[i1] - ax, positions[i1 + 1] - ay, positions[i1 + 2] - az)
        val e2 = Vec3(positions[i2] - ax, positions[i2 + 1] - ay, positions[i2 + 2] - az)

        // Reconstruct the hit from barycentrics so the point lies exactly on the triangle.
        val point = Vec3(ax, ay, az) + e1 * bestU + e2 * bestV
        val normal = (e1 cross e2).normalized()
        return Hit(point, normal, bestTri, bestT)
    }

    private fun intersectsAabb(
        node: Int,
        origin: Vec3,
        invX: Float,
        invY: Float,
        invZ: Float,
        limit: Float,
    ): Boolean {
        val b = node * 6
        var tMin = 0f
        var tMax = limit

        var t1 = (bounds[b] - origin.x) * invX
        var t2 = (bounds[b + 3] - origin.x) * invX
        tMin = max(tMin, min(t1, t2))
        tMax = min(tMax, max(t1, t2))

        t1 = (bounds[b + 1] - origin.y) * invY
        t2 = (bounds[b + 4] - origin.y) * invY
        tMin = max(tMin, min(t1, t2))
        tMax = min(tMax, max(t1, t2))

        t1 = (bounds[b + 2] - origin.z) * invZ
        t2 = (bounds[b + 5] - origin.z) * invZ
        tMin = max(tMin, min(t1, t2))
        tMax = min(tMax, max(t1, t2))

        return tMax >= tMin
    }

    companion object {
        private const val MAX_LEAF_TRIANGLES = 8

        private fun nonZero(v: Float): Float =
            if (abs(v) < 1e-8f) (if (v < 0f) -1e-8f else 1e-8f) else v

        /** Empty hierarchy, used while no model is loaded. */
        val EMPTY = Bvh(FloatArray(0), IntArray(0), IntArray(0), FloatArray(0), IntArray(0), 0)

        fun build(positions: FloatArray, indices: IntArray): Bvh {
            val triCount = indices.size / 3
            if (triCount == 0) return EMPTY

            val triangles = IntArray(triCount) { it }
            val centroids = FloatArray(triCount * 3)
            val triBounds = FloatArray(triCount * 6)

            for (t in 0 until triCount) {
                val i0 = indices[t * 3] * 3
                val i1 = indices[t * 3 + 1] * 3
                val i2 = indices[t * 3 + 2] * 3
                for (axis in 0 until 3) {
                    val a = positions[i0 + axis]
                    val b = positions[i1 + axis]
                    val c = positions[i2 + axis]
                    triBounds[t * 6 + axis] = min(a, min(b, c))
                    triBounds[t * 6 + 3 + axis] = max(a, max(b, c))
                    centroids[t * 3 + axis] = (a + b + c) / 3f
                }
            }

            val boundsOut = FloatList(triCount)
            val nodesOut = IntList(triCount)
            var nodeCount = 0

            fun allocNode(): Int {
                boundsOut.ensure((nodeCount + 1) * 6)
                nodesOut.ensure((nodeCount + 1) * 2)
                repeat(6) { boundsOut.add(0f) }
                nodesOut.add(0)
                nodesOut.add(0)
                return nodeCount++
            }

            // (start, end, node) triples on an explicit stack: recursion would risk a
            // StackOverflowError on deep trees.
            val stack = IntList(256)
            val root = allocNode()
            stack.add(0, triCount, root)

            while (stack.size > 0) {
                val node = stack[stack.size - 1]
                val end = stack[stack.size - 2]
                val start = stack[stack.size - 3]
                stack.shrink(3)

                var nx = Float.POSITIVE_INFINITY
                var ny = Float.POSITIVE_INFINITY
                var nz = Float.POSITIVE_INFINITY
                var xx = Float.NEGATIVE_INFINITY
                var xy = Float.NEGATIVE_INFINITY
                var xz = Float.NEGATIVE_INFINITY
                var cnx = Float.POSITIVE_INFINITY
                var cny = Float.POSITIVE_INFINITY
                var cnz = Float.POSITIVE_INFINITY
                var cxx = Float.NEGATIVE_INFINITY
                var cxy = Float.NEGATIVE_INFINITY
                var cxz = Float.NEGATIVE_INFINITY

                for (i in start until end) {
                    val t = triangles[i]
                    nx = min(nx, triBounds[t * 6]); ny = min(ny, triBounds[t * 6 + 1]); nz = min(nz, triBounds[t * 6 + 2])
                    xx = max(xx, triBounds[t * 6 + 3]); xy = max(xy, triBounds[t * 6 + 4]); xz = max(xz, triBounds[t * 6 + 5])
                    cnx = min(cnx, centroids[t * 3]); cny = min(cny, centroids[t * 3 + 1]); cnz = min(cnz, centroids[t * 3 + 2])
                    cxx = max(cxx, centroids[t * 3]); cxy = max(cxy, centroids[t * 3 + 1]); cxz = max(cxz, centroids[t * 3 + 2])
                }

                boundsOut[node * 6] = nx
                boundsOut[node * 6 + 1] = ny
                boundsOut[node * 6 + 2] = nz
                boundsOut[node * 6 + 3] = xx
                boundsOut[node * 6 + 4] = xy
                boundsOut[node * 6 + 5] = xz

                val count = end - start
                if (count <= MAX_LEAF_TRIANGLES) {
                    nodesOut[node * 2] = start
                    nodesOut[node * 2 + 1] = count
                    continue
                }

                // Split along the widest centroid axis, at the midpoint of that span.
                val ex = cxx - cnx
                val ey = cxy - cny
                val ez = cxz - cnz
                val axis = if (ex >= ey && ex >= ez) 0 else if (ey >= ez) 1 else 2
                val split = when (axis) {
                    0 -> (cnx + cxx) * 0.5f
                    1 -> (cny + cxy) * 0.5f
                    else -> (cnz + cxz) * 0.5f
                }

                var mid = start
                for (i in start until end) {
                    if (centroids[triangles[i] * 3 + axis] < split) {
                        val tmp = triangles[i]
                        triangles[i] = triangles[mid]
                        triangles[mid] = tmp
                        mid++
                    }
                }
                // Degenerate split (all centroids coincide): fall back to a median cut so
                // the recursion always makes progress.
                if (mid == start || mid == end) mid = (start + end) / 2

                val left = allocNode()
                val right = allocNode()
                nodesOut[node * 2] = left
                nodesOut[node * 2 + 1] = 0

                stack.add(start, mid, left)
                stack.add(mid, end, right)
            }

            return Bvh(
                positions = positions,
                indices = indices,
                triangles = triangles,
                bounds = boundsOut.toArray(),
                nodes = nodesOut.toArray(),
                nodeCount = nodeCount,
            )
        }
    }
}
