package com.inspect3d.viewer.parse

import com.inspect3d.viewer.TestModels
import com.inspect3d.viewer.model.Bounds
import com.inspect3d.viewer.model.ModelParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class ThreeMfParserTest {

    /** The production default uses the platform's parser; tests supply a JVM one. */
    private fun parse(bytes: ByteArray) = ThreeMfParser.parse(bytes) { KXmlParser() }

    @Test
    fun `a cube mesh is read with its document unit`() {
        val bytes = TestModels.threeMf(
            unit = "millimeter",
            vertices = TestModels.CUBE_VERTICES,
            triangles = TestModels.CUBE_TRIANGLES,
        )

        val mesh = parse(bytes)

        assertEquals(12, mesh.indices.size / 3)
        assertEquals("millimeter", mesh.unit)
        val bounds = Bounds.of(mesh.positions)
        assertEquals(0f, bounds.minX, 1e-4f)
        assertEquals(10f, bounds.maxX, 1e-4f)
    }

    @Test
    fun `a build item transform is applied with the right row-column convention`() {
        // 3MF writes a row-major 4x3 affine. Reading it transposed would scale the wrong
        // axes, which is exactly what this asserts against: diag(3, 3, 6) on a 10-cube
        // must give 30 x 30 x 60, not some permutation.
        val bytes = TestModels.threeMf(
            transform = "3 0 0 0 3 0 0 0 6 0 0 0",
            vertices = TestModels.CUBE_VERTICES,
            triangles = TestModels.CUBE_TRIANGLES,
        )

        val bounds = Bounds.of(parse(bytes).positions)

        assertEquals(30f, bounds.maxX, 1e-3f)
        assertEquals(30f, bounds.maxY, 1e-3f)
        assertEquals(60f, bounds.maxZ, 1e-3f)
    }

    @Test
    fun `the translation row of a transform places the object`() {
        val bytes = TestModels.threeMf(
            transform = "1 0 0 0 1 0 0 0 1 5 -2 7",
            vertices = TestModels.CUBE_VERTICES,
            triangles = TestModels.CUBE_TRIANGLES,
        )

        val bounds = Bounds.of(parse(bytes).positions)

        assertEquals(5f, bounds.minX, 1e-3f)
        assertEquals(-2f, bounds.minY, 1e-3f)
        assertEquals(7f, bounds.minZ, 1e-3f)
    }

    @Test
    fun `3mf supplies no normals so flat shading is generated downstream`() {
        val mesh = parse(
            TestModels.threeMf(
                vertices = TestModels.CUBE_VERTICES,
                triangles = TestModels.CUBE_TRIANGLES,
            ),
        )

        assertNull(mesh.normals)
    }

    @Test
    fun `triangles referencing missing vertices are skipped rather than crashing`() {
        val bytes = TestModels.threeMf(
            vertices = TestModels.CUBE_VERTICES,
            triangles = TestModels.CUBE_TRIANGLES + listOf(Triple(0, 1, 99)),
        )

        val mesh = parse(bytes)

        assertEquals("the bad triangle is dropped", 12, mesh.indices.size / 3)
    }

    @Test
    fun `a package with no model part is reported clearly`() {
        val bytes = TestModels.threeMf(
            vertices = TestModels.CUBE_VERTICES,
            triangles = TestModels.CUBE_TRIANGLES,
            includeModelPart = false,
        )

        val error = runCatching { parse(bytes) }.exceptionOrNull()

        assertTrue(error is ModelParseException)
        assertTrue(error!!.message!!.contains("3D model part"))
    }

    @Test(expected = ModelParseException::class)
    fun `an object with no triangles is rejected`() {
        parse(
            TestModels.threeMf(
                vertices = TestModels.CUBE_VERTICES,
                triangles = emptyList(),
            ),
        )
    }
}
