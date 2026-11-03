package com.inspect3d.viewer.model

import com.inspect3d.viewer.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class BvhTest {

    /** A triangle in the z = plane, spanning (0,0) to (10,10), facing +Z. */
    private val trianglePositions = floatArrayOf(
        0f, 0f, 0f,
        10f, 0f, 0f,
        0f, 10f, 0f,
    )

    @Test
    fun `a ray straight down hits the triangle at the expected point`() {
        val bvh = Bvh.build(trianglePositions, intArrayOf(0, 1, 2))

        val hit = bvh.intersect(Vec3(2f, 2f, 5f), Vec3(0f, 0f, -1f))

        assertNotNull(hit)
        assertEquals(5f, hit!!.distance, 1e-4f)
        assertEquals(2f, hit.point.x, 1e-4f)
        assertEquals(2f, hit.point.y, 1e-4f)
        assertEquals(0f, hit.point.z, 1e-4f)
        assertEquals("the normal must be unit length", 1f, hit.normal.length, 1e-4f)
    }

    @Test
    fun `axis aligned rays do not fall through the slab test`() {
        // Two of the three direction components are exactly zero here, which makes the
        // slab test compute 0 * infinity unless it is guarded. A NaN there silently turns
        // every tap along an axis into a miss. A closed box is used rather than a single
        // triangle so that each axis has a face to hit — a ray running *within* a
        // triangle's plane is a separate, genuinely degenerate case.
        val (positions, indices) = boxMesh()
        val bvh = Bvh.build(positions, indices)

        assertNotNull("along -Z", bvh.intersect(Vec3(5f, 5f, 100f), Vec3(0f, 0f, -1f)))
        assertNotNull("along +Z", bvh.intersect(Vec3(5f, 5f, -100f), Vec3(0f, 0f, 1f)))
        assertNotNull("along +X", bvh.intersect(Vec3(-100f, 5f, 5f), Vec3(1f, 0f, 0f)))
        assertNotNull("along -X", bvh.intersect(Vec3(100f, 5f, 5f), Vec3(-1f, 0f, 0f)))
        assertNotNull("along +Y", bvh.intersect(Vec3(5f, -100f, 5f), Vec3(0f, 1f, 0f)))
        assertNotNull("along -Y", bvh.intersect(Vec3(5f, 100f, 5f), Vec3(0f, -1f, 0f)))
    }

    @Test
    fun `a ray running inside a triangle's own plane does not register a hit`() {
        // Coplanar rays have no single well-defined intersection, so the determinant test
        // rejects them. Pinned here so the behaviour is deliberate rather than incidental.
        val bvh = Bvh.build(trianglePositions, intArrayOf(0, 1, 2))

        assertNull(bvh.intersect(Vec3(-5f, 1f, 0f), Vec3(1f, 0f, 0f)))
    }

    /** A closed 10-unit box at the origin, as an unshared triangle soup. */
    private fun boxMesh(): Pair<FloatArray, IntArray> {
        val corners = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(10f, 0f, 0f),
            floatArrayOf(10f, 10f, 0f), floatArrayOf(0f, 10f, 0f),
            floatArrayOf(0f, 0f, 10f), floatArrayOf(10f, 0f, 10f),
            floatArrayOf(10f, 10f, 10f), floatArrayOf(0f, 10f, 10f),
        )
        val faces = listOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
            intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        val positions = FloatArray(corners.size * 3)
        corners.forEachIndexed { i, c -> c.copyInto(positions, i * 3) }
        val indices = IntArray(faces.size * 3)
        faces.forEachIndexed { i, f -> f.copyInto(indices, i * 3) }
        return positions to indices
    }

    @Test
    fun `a ray that misses returns null`() {
        val bvh = Bvh.build(trianglePositions, intArrayOf(0, 1, 2))

        assertNull(bvh.intersect(Vec3(20f, 20f, 5f), Vec3(0f, 0f, -1f)))
        // Pointing away from the triangle.
        assertNull(bvh.intersect(Vec3(2f, 2f, 5f), Vec3(0f, 0f, 1f)))
    }

    @Test
    fun `back faces are hit too, so interiors can be measured`() {
        val bvh = Bvh.build(trianglePositions, intArrayOf(0, 1, 2))

        val hit = bvh.intersect(Vec3(2f, 2f, -5f), Vec3(0f, 0f, 1f))

        assertNotNull("triangles are two-sided", hit)
        assertEquals(0f, hit!!.point.z, 1e-4f)
    }

    @Test
    fun `the nearest of two stacked triangles is returned`() {
        val positions = floatArrayOf(
            0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f,
            0f, 0f, 2f, 10f, 0f, 2f, 0f, 10f, 2f,
        )
        val bvh = Bvh.build(positions, intArrayOf(0, 1, 2, 3, 4, 5))

        val hit = bvh.intersect(Vec3(1f, 1f, 5f), Vec3(0f, 0f, -1f))

        assertNotNull(hit)
        assertEquals("the z = 2 triangle is nearer", 2f, hit!!.point.z, 1e-4f)
    }

    @Test
    fun `geometry on the removed side of the clip plane is ignored`() {
        val positions = floatArrayOf(
            0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f,
            0f, 0f, 2f, 10f, 0f, 2f, 0f, 10f, 2f,
        )
        val bvh = Bvh.build(positions, intArrayOf(0, 1, 2, 3, 4, 5))

        // Cut away everything with z > 1, which removes the nearer triangle.
        val clip = floatArrayOf(0f, 0f, 1f, -1f)
        val hit = bvh.intersect(Vec3(1f, 1f, 5f), Vec3(0f, 0f, -1f), clip)

        assertNotNull(hit)
        assertEquals("the ray now reaches the far triangle", 0f, hit!!.point.z, 1e-4f)
    }

    @Test
    fun `an empty hierarchy misses everything`() {
        assertNull(Bvh.EMPTY.intersect(Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f)))
        assertNull(Bvh.build(FloatArray(0), IntArray(0)).intersect(Vec3.ZERO, Vec3.UNIT_Z))
    }

    @Test
    fun `coincident triangles still build a usable tree`() {
        // Every centroid is identical, which forces the median-split fallback. Without it
        // the build would not make progress.
        val positions = FloatArray(60 * 9)
        for (t in 0 until 60) {
            val o = t * 9
            positions[o] = 0f; positions[o + 1] = 0f; positions[o + 2] = 0f
            positions[o + 3] = 4f; positions[o + 4] = 0f; positions[o + 5] = 0f
            positions[o + 6] = 0f; positions[o + 7] = 4f; positions[o + 8] = 0f
        }
        val bvh = Bvh.build(positions, IntArray(60 * 3) { it })

        assertNotNull(bvh.intersect(Vec3(1f, 1f, 5f), Vec3(0f, 0f, -1f)))
    }

    @Test
    fun `the hierarchy agrees with a brute force scan over random geometry`() {
        // The single most valuable check here: it exercises partitioning, sibling
        // adjacency, the traversal stack and the split fallback together, rather than one
        // path at a time. Seeded so a failure is reproducible.
        val random = Random(20261103)
        val triangleCount = 800
        val positions = FloatArray(triangleCount * 9)
        for (t in 0 until triangleCount) {
            val ox = random.nextFloat() * 20f - 10f
            val oy = random.nextFloat() * 20f - 10f
            val oz = random.nextFloat() * 20f - 10f
            val o = t * 9
            for (v in 0 until 3) {
                positions[o + v * 3] = ox + random.nextFloat() * 3f
                positions[o + v * 3 + 1] = oy + random.nextFloat() * 3f
                positions[o + v * 3 + 2] = oz + random.nextFloat() * 3f
            }
        }
        val indices = IntArray(triangleCount * 3) { it }
        val bvh = Bvh.build(positions, indices)

        var compared = 0
        var hits = 0
        repeat(400) {
            val origin = Vec3(
                random.nextFloat() * 40f - 20f,
                random.nextFloat() * 40f - 20f,
                random.nextFloat() * 40f - 20f,
            )
            val direction = Vec3(
                random.nextFloat() * 2f - 1f,
                random.nextFloat() * 2f - 1f,
                random.nextFloat() * 2f - 1f,
            )
            if (direction.length < 1e-3f) return@repeat

            val expected = bruteForceDistance(positions, indices, origin, direction)
            val actual = bvh.intersect(origin, direction)?.distance
            compared++
            if (expected == null) {
                assertNull("ray $compared should miss", actual)
            } else {
                assertNotNull("ray $compared should hit at $expected", actual)
                assertEquals("ray $compared", expected, actual!!, 1e-2f)
                hits++
            }
        }

        assertTrue("the test data should produce some hits", hits > 20)
    }

    /** Linear scan reference: the answer the hierarchy must reproduce. */
    private fun bruteForceDistance(
        positions: FloatArray,
        indices: IntArray,
        origin: Vec3,
        direction: Vec3,
    ): Float? {
        val dir = direction.normalized()
        var best = Float.MAX_VALUE

        for (t in 0 until indices.size / 3) {
            val i0 = indices[t * 3] * 3
            val i1 = indices[t * 3 + 1] * 3
            val i2 = indices[t * 3 + 2] * 3

            val ax = positions[i0]; val ay = positions[i0 + 1]; val az = positions[i0 + 2]
            val e1x = positions[i1] - ax; val e1y = positions[i1 + 1] - ay; val e1z = positions[i1 + 2] - az
            val e2x = positions[i2] - ax; val e2y = positions[i2 + 1] - ay; val e2z = positions[i2 + 2] - az

            val px = dir.y * e2z - dir.z * e2y
            val py = dir.z * e2x - dir.x * e2z
            val pz = dir.x * e2y - dir.y * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-12f) continue
            val invDet = 1f / det

            val tx = origin.x - ax; val ty = origin.y - ay; val tz = origin.z - az
            val u = (tx * px + ty * py + tz * pz) * invDet
            if (u < -1e-6f || u > 1f + 1e-6f) continue

            val qx = ty * e1z - tz * e1y
            val qy = tz * e1x - tx * e1z
            val qz = tx * e1y - ty * e1x
            val v = (dir.x * qx + dir.y * qy + dir.z * qz) * invDet
            if (v < -1e-6f || u + v > 1f + 1e-6f) continue

            val hit = (e2x * qx + e2y * qy + e2z * qz) * invDet
            if (hit > 1e-5f && hit < best) best = hit
        }
        return if (best == Float.MAX_VALUE) null else best
    }
}
