package com.inspect3d.viewer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LoadedModelTest {

    /** A 100 x 60 x 40 box as a triangle soup with no normals. */
    private fun boxMesh(): RawMesh {
        val v = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(100f, 0f, 0f),
            floatArrayOf(100f, 60f, 0f), floatArrayOf(0f, 60f, 0f),
            floatArrayOf(0f, 0f, 40f), floatArrayOf(100f, 0f, 40f),
            floatArrayOf(100f, 60f, 40f), floatArrayOf(0f, 60f, 40f),
        )
        val faces = listOf(
            0 to intArrayOf(0, 2, 1), 0 to intArrayOf(0, 3, 2),
            0 to intArrayOf(4, 5, 6), 0 to intArrayOf(4, 6, 7),
            0 to intArrayOf(0, 1, 5), 0 to intArrayOf(0, 5, 4),
            0 to intArrayOf(1, 2, 6), 0 to intArrayOf(1, 6, 5),
            0 to intArrayOf(2, 3, 7), 0 to intArrayOf(2, 7, 6),
            0 to intArrayOf(3, 0, 4), 0 to intArrayOf(3, 4, 7),
        )
        val positions = FloatArray(v.size * 3)
        v.forEachIndexed { i, p -> p.copyInto(positions, i * 3) }
        val indices = IntArray(faces.size * 3)
        faces.forEachIndexed { i, f -> f.second.copyInto(indices, i * 3) }
        return RawMesh(positions, null, indices)
    }

    @Test
    fun `geometry is centred and scaled so the largest half-extent is one`() {
        val model = LoadedModel.from("box.stl", ModelFormat.STL, boxMesh())

        assertEquals(100f, model.sourceSize.x, 1e-3f)
        assertEquals(60f, model.sourceSize.y, 1e-3f)
        assertEquals(40f, model.sourceSize.z, 1e-3f)

        // Largest half-extent is 50, so one source unit becomes 1/50 world units.
        assertEquals(0.02f, model.worldPerSourceUnit, 1e-6f)

        var maxAbs = 0f
        var sumX = 0f
        var i = 0
        while (i < model.positions.size) {
            maxAbs = maxOf(maxAbs, abs(model.positions[i]))
            sumX += model.positions[i]
            i += 3
        }
        assertEquals("the widest axis reaches exactly 1", 1f, maxAbs, 1e-5f)
        assertEquals("and is centred on the origin", 0f, sumX / (model.positions.size / 3), 1e-5f)
    }

    @Test
    fun `lengths convert back to the units of the source file`() {
        val model = LoadedModel.from("box.stl", ModelFormat.STL, boxMesh())

        // The full width of the model is 2 world units.
        assertEquals(100f, model.toSourceLength(2f), 1e-3f)
        assertEquals(25f, model.toSourceLength(0.5f), 1e-3f)
    }

    @Test
    fun `a mesh without normals is expanded to flat shaded triangles`() {
        val model = LoadedModel.from("box.stl", ModelFormat.STL, boxMesh())

        assertEquals(12, model.triangleCount)
        assertEquals("every triangle gets its own three vertices", 36, model.vertexCount)
        assertEquals(model.vertexCount * 3, model.normals.size)

        // Every normal must be unit length.
        var i = 0
        while (i < model.normals.size) {
            val n = model.normals
            val len = kotlin.math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2])
            assertEquals("normal at $i", 1f, len, 1e-4f)
            i += 3
        }
    }

    @Test
    fun `the normalised radius lies between a plate and a cube`() {
        val model = LoadedModel.from("box.stl", ModelFormat.STL, boxMesh())

        // Half-extents normalise to (1, 0.6, 0.4); |(1, 0.6, 0.4)| = 1.233.
        assertEquals(1.233f, model.normalizedRadius, 1e-3f)
        assertTrue(model.normalizedRadius <= 1.74f)
    }

    @Test
    fun `supplied normals are kept rather than regenerated`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f)
        val normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)
        val model = LoadedModel.from(
            "tri.obj",
            ModelFormat.OBJ,
            RawMesh(positions, normals, intArrayOf(0, 1, 2)),
        )

        assertEquals("no expansion when normals are present", 3, model.vertexCount)
        assertEquals(1f, model.normals[2], 1e-5f)
    }

    @Test(expected = ModelParseException::class)
    fun `a mesh with no triangles is rejected`() {
        LoadedModel.from("empty.stl", ModelFormat.STL, RawMesh(FloatArray(0), null, IntArray(0)))
    }

    @Test(expected = ModelParseException::class)
    fun `coordinates that are not finite are rejected`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, Float.NaN, 1f, 0f)
        LoadedModel.from("bad.stl", ModelFormat.STL, RawMesh(positions, null, intArrayOf(0, 1, 2)))
    }
}
