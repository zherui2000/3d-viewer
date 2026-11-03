package com.inspect3d.viewer.model

import com.inspect3d.viewer.core.IntList
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The edges a CAD viewer outlines in its "shaded with edges" display: creases, where two
 * faces meet at more than [DEFAULT_CREASE_DEGREES], and boundaries, where a surface ends.
 *
 * This is what separates a part that looks like a drawing from one that looks like a
 * mesh. Drawing every triangle edge (the "Solid + mesh" mode) shows how the file happened
 * to be tessellated; a cylinder becomes a fan of stripes. Here, the facets of a
 * tessellated cylinder meet at a few degrees and are left alone, while the cylinder's rim
 * against a flat cap is a 90° crease and is outlined, which is what the eye expects.
 *
 * Vertices are welded by position first. STL and the flat-shading path both store every
 * triangle's corners separately, so without welding no two triangles would share an edge
 * and every edge would read as a boundary.
 */
object FeatureEdges {

    const val DEFAULT_CREASE_DEGREES = 30f

    /**
     * Above this the extraction's hash tables (around 100 MB, briefly, at the limit) would
     * cost more memory than they are worth on a phone, and the outline would be too dense
     * to read anyway.
     */
    const val MAX_TRIANGLES = 750_000

    /**
     * Weld tolerance. Positions are normalised to roughly [-1, 1], so this is about a
     * hundred-thousandth of the model's size: far below anything visible, far above
     * float rounding between two copies of the same exported coordinate.
     */
    private const val WELD_STEP = 1e-5f
    private const val QUANT_OFFSET = 1 shl 20
    private const val QUANT_LIMIT = (1 shl 20) - 1

    /**
     * Returns a line list — pairs of indices into [positions] — or `null` when the mesh is
     * larger than [MAX_TRIANGLES].
     */
    fun compute(
        positions: FloatArray,
        indices: IntArray,
        creaseDegrees: Float = DEFAULT_CREASE_DEGREES,
    ): IntArray? {
        val triangleCount = indices.size / 3
        if (triangleCount == 0) return IntArray(0)
        if (triangleCount > MAX_TRIANGLES) return null

        // ---- 1. weld: every vertex gets the id of the first vertex at its position.
        val vertexCount = positions.size / 3
        val canonical = IntArray(vertexCount)
        val representative = IntArray(vertexCount)
        val welded = LongIntMap(vertexCount)
        var nextId = 0
        for (v in 0 until vertexCount) {
            val key = positionKey(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2])
            val existing = welded[key]
            if (existing >= 0) {
                canonical[v] = existing
            } else {
                welded[key] = nextId
                representative[nextId] = v
                canonical[v] = nextId
                nextId++
            }
        }

        // ---- 2. face normals from the geometry itself, not the file's vertex normals:
        // a crease is a property of the shape, and smoothed normals would hide it.
        val faceNormals = FloatArray(triangleCount * 3)
        val usable = BooleanArray(triangleCount)
        for (t in 0 until triangleCount) {
            val a = indices[t * 3] * 3
            val b = indices[t * 3 + 1] * 3
            val c = indices[t * 3 + 2] * 3
            val e1x = positions[b] - positions[a]
            val e1y = positions[b + 1] - positions[a + 1]
            val e1z = positions[b + 2] - positions[a + 2]
            val e2x = positions[c] - positions[a]
            val e2y = positions[c + 1] - positions[a + 1]
            val e2z = positions[c + 2] - positions[a + 2]
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            // A zero-area triangle has no direction to crease against. Leaving it out
            // also stops the slivers some exporters emit from drawing stray lines.
            if (length > 1e-12f) {
                faceNormals[t * 3] = nx / length
                faceNormals[t * 3 + 1] = ny / length
                faceNormals[t * 3 + 2] = nz / length
                usable[t] = true
            }
        }

        // ---- 3. edge adjacency: up to two faces per welded edge, plus a flag for more.
        val maxEdges = triangleCount * 3
        val edgeIds = LongIntMap(maxEdges)
        val faceA = IntArray(maxEdges)
        val faceB = IntArray(maxEdges) { -1 }
        val nonManifold = BooleanArray(maxEdges)
        var edgeCount = 0

        for (t in 0 until triangleCount) {
            if (!usable[t]) continue
            for (corner in 0 until 3) {
                val p = canonical[indices[t * 3 + corner]]
                val q = canonical[indices[t * 3 + (corner + 1) % 3]]
                if (p == q) continue
                val key = edgeKey(p, q)
                val id = edgeIds[key]
                when {
                    id < 0 -> {
                        edgeIds[key] = edgeCount
                        faceA[edgeCount] = t
                        edgeCount++
                    }
                    faceB[id] < 0 -> faceB[id] = t
                    else -> nonManifold[id] = true
                }
            }
        }

        // ---- 4. keep boundaries, creases and anything non-manifold.
        val creaseCos = cos(Math.toRadians(creaseDegrees.toDouble())).toFloat()
        val lines = IntList(1024)
        edgeIds.forEach { key, id ->
            val keep = when {
                nonManifold[id] -> true
                faceB[id] < 0 -> true
                else -> {
                    val fa = faceA[id] * 3
                    val fb = faceB[id] * 3
                    val dot = faceNormals[fa] * faceNormals[fb] +
                        faceNormals[fa + 1] * faceNormals[fb + 1] +
                        faceNormals[fa + 2] * faceNormals[fb + 2]
                    dot < creaseCos
                }
            }
            if (keep) {
                lines.add(representative[(key ushr 32).toInt()])
                lines.add(representative[key.toInt()])
            }
        }
        return lines.toArray()
    }

    private fun quantize(value: Float): Long =
        ((value / WELD_STEP).roundToInt().coerceIn(-QUANT_LIMIT, QUANT_LIMIT) + QUANT_OFFSET).toLong()

    /** 21 bits per axis, which covers ±1.04 world units at the weld step. */
    private fun positionKey(x: Float, y: Float, z: Float): Long =
        (quantize(x) shl 42) or (quantize(y) shl 21) or quantize(z)

    /** Order-independent: the edge p–q is the same edge as q–p. */
    private fun edgeKey(p: Int, q: Int): Long {
        val lo = minOf(p, q).toLong()
        val hi = maxOf(p, q).toLong()
        return (lo shl 32) or hi
    }
}

/**
 * Open-addressing `Long -> Int` map. A `HashMap<Long, Int>` over a million-triangle mesh
 * would allocate several million boxed entries; this is two flat arrays. Keys must be
 * non-negative (both key builders above guarantee it), and absent lookups return -1.
 *
 * [maxKeys] must be a true upper bound: linear probing never terminates in a full table.
 * Capacity keeps the load factor at or below 0.75 even when every key is distinct.
 */
private class LongIntMap(maxKeys: Int) {
    private val capacity: Int = run {
        val needed = maxKeys.toLong() * 4 / 3 + 1
        var c = 16
        while (c < needed) c = c shl 1
        c
    }
    private val mask = capacity - 1
    private val keys = LongArray(capacity) { EMPTY }
    private val values = IntArray(capacity)

    operator fun get(key: Long): Int {
        var slot = mix(key) and mask
        while (true) {
            val k = keys[slot]
            if (k == EMPTY) return -1
            if (k == key) return values[slot]
            slot = (slot + 1) and mask
        }
    }

    operator fun set(key: Long, value: Int) {
        var slot = mix(key) and mask
        while (true) {
            val k = keys[slot]
            if (k == EMPTY || k == key) {
                keys[slot] = key
                values[slot] = value
                return
            }
            slot = (slot + 1) and mask
        }
    }

    fun forEach(action: (key: Long, value: Int) -> Unit) {
        for (slot in 0 until capacity) {
            val k = keys[slot]
            if (k != EMPTY) action(k, values[slot])
        }
    }

    /** Spreads structured keys (packed coordinates, sequential ids) across the table. */
    private fun mix(key: Long): Int {
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return h.toInt()
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE
    }
}
