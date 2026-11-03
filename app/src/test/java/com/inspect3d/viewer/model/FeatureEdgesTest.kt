package com.inspect3d.viewer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FeatureEdgesTest {

    @Test
    fun `a box is outlined by its 12 edges and never by its face diagonals`() {
        // Triangle soup, every corner duplicated per triangle, which is exactly what an
        // STL gives. Without welding, all 36 triangle edges would read as boundaries.
        val (positions, indices) = soup(boxCorners(), BOX_FACES)

        val lines = FeatureEdges.compute(positions, indices)

        assertNotNull(lines)
        assertEquals("box edges", 12, lines!!.size / 2)
        for (segment in segments(positions, lines)) {
            val axesChanged = (0 until 3).count { abs(segment.first[it] - segment.second[it]) > 1e-6f }
            assertEquals("an outline segment runs along one axis, never across a face", 1, axesChanged)
        }
    }

    @Test
    fun `an open quad keeps its boundary and drops the shared diagonal`() {
        val positions = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f,
            0f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f,
        )
        val lines = FeatureEdges.compute(positions, IntArray(6) { it })!!

        assertEquals(4, lines.size / 2)
    }

    @Test
    fun `the facets of a curved surface are not outlined, only where it ends`() {
        // An open tube of 36 facets: neighbouring facets meet at 10 degrees, far below the
        // crease angle. Only the two rims are boundaries.
        val segmentsAround = 36
        val positions = ArrayList<Float>()
        for (s in 0 until segmentsAround) {
            val a0 = 2.0 * Math.PI * s / segmentsAround
            val a1 = 2.0 * Math.PI * (s + 1) / segmentsAround
            val x0 = cos(a0).toFloat(); val y0 = sin(a0).toFloat()
            val x1 = cos(a1).toFloat(); val y1 = sin(a1).toFloat()
            positions += listOf(x0, y0, -1f, x1, y1, -1f, x1, y1, 1f)
            positions += listOf(x0, y0, -1f, x1, y1, 1f, x0, y0, 1f)
        }
        val array = positions.toFloatArray()

        val lines = FeatureEdges.compute(array, IntArray(array.size / 3) { it })!!

        assertEquals("one segment per facet on each rim", segmentsAround * 2, lines.size / 2)
        for (segment in segments(array, lines)) {
            assertEquals("rim segments stay at one height", segment.first[2], segment.second[2], 1e-6f)
        }
    }

    @Test
    fun `a crease sharper than the threshold is kept, a gentler one is not`() {
        // Two triangles hinged on the x axis at a chosen fold angle.
        fun hinge(degrees: Double): Int {
            val r = Math.toRadians(degrees)
            val positions = floatArrayOf(
                0f, 0f, 0f, 1f, 0f, 0f, 0.5f, 1f, 0f,
                1f, 0f, 0f, 0f, 0f, 0f, 0.5f, -cos(r).toFloat(), sin(r).toFloat(),
            )
            val lines = FeatureEdges.compute(positions, IntArray(6) { it })!!
            return lines.size / 2
        }

        // 4 outer boundary segments always; the hinge adds a fifth when it is a crease.
        assertEquals("a 10 degree fold is smooth", 4, hinge(10.0))
        assertEquals("a 60 degree fold is an edge", 5, hinge(60.0))
    }

    @Test
    fun `degenerate triangles draw nothing`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f)
        val lines = FeatureEdges.compute(positions, intArrayOf(0, 1, 2))!!
        assertTrue(lines.isEmpty())
    }

    private fun segments(positions: FloatArray, lines: IntArray): List<Pair<FloatArray, FloatArray>> =
        (0 until lines.size / 2).map { i ->
            val a = lines[i * 2] * 3
            val b = lines[i * 2 + 1] * 3
            positions.copyOfRange(a, a + 3) to positions.copyOfRange(b, b + 3)
        }

    private fun boxCorners() = listOf(
        floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f),
        floatArrayOf(1f, 1f, -1f), floatArrayOf(-1f, 1f, -1f),
        floatArrayOf(-1f, -1f, 1f), floatArrayOf(1f, -1f, 1f),
        floatArrayOf(1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f),
    )

    /** Expands indexed faces into an unshared triangle soup. */
    private fun soup(corners: List<FloatArray>, faces: List<IntArray>): Pair<FloatArray, IntArray> {
        val positions = FloatArray(faces.size * 9)
        faces.forEachIndexed { t, face ->
            for (v in 0 until 3) corners[face[v]].copyInto(positions, t * 9 + v * 3)
        }
        return positions to IntArray(faces.size * 3) { it }
    }

    private companion object {
        val BOX_FACES = listOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
            intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
    }
}
