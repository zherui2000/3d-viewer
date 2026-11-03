package com.inspect3d.viewer.parse

import com.inspect3d.viewer.TestModels
import com.inspect3d.viewer.model.ModelParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class StlParserTest {

    @Test
    fun `binary stl yields three unshared vertices per triangle`() {
        val mesh = StlParser.parse(TestModels.binaryStl(TestModels.QUAD))

        assertEquals(6, mesh.indices.size)
        assertEquals(18, mesh.positions.size)
        assertNotNull(mesh.normals)
        // STL shares no vertices, so indices are sequential.
        assertArrayEqualsInt(intArrayOf(0, 1, 2, 3, 4, 5), mesh.indices)
    }

    @Test
    fun `ascii and binary of the same solid produce identical geometry`() {
        val binary = StlParser.parse(TestModels.binaryStl(TestModels.QUAD))
        val ascii = StlParser.parse(TestModels.asciiStl(TestModels.QUAD))

        assertEquals(binary.positions.size, ascii.positions.size)
        for (i in binary.positions.indices) {
            assertEquals("position $i", binary.positions[i], ascii.positions[i], 1e-4f)
        }
    }

    @Test
    fun `binary file whose header starts with solid is still read as binary`() {
        // The classic STL trap: the "solid" keyword does not mean the file is ASCII.
        val bytes = TestModels.binaryStl(TestModels.QUAD, header = "solid created by a binary writer")

        val mesh = StlParser.parse(bytes)

        assertEquals(2, mesh.indices.size / 3)
        assertEquals(10f, mesh.positions[3], 1e-4f) // second vertex x
    }

    @Test
    fun `zero facet normal is replaced by one derived from the winding`() {
        val flat = listOf(
            TestModels.triangle(
                Triple(0f, 0f, 0f), // exporters often write this
                Triple(0f, 0f, 0f),
                Triple(2f, 0f, 0f),
                Triple(0f, 2f, 0f),
            ),
        )

        val normals = StlParser.parse(TestModels.binaryStl(flat)).normals!!

        val length = sqrt(normals[0] * normals[0] + normals[1] * normals[1] + normals[2] * normals[2])
        assertEquals("normal must be unit length", 1f, length, 1e-4f)
        assertEquals("winding gives +Z", 1f, abs(normals[2]), 1e-4f)
    }

    @Test
    fun `facet normal from the file is preserved when it is usable`() {
        val normals = StlParser.parse(TestModels.binaryStl(TestModels.QUAD)).normals!!
        assertEquals(0f, normals[0], 1e-5f)
        assertEquals(0f, normals[1], 1e-5f)
        assertEquals(1f, normals[2], 1e-5f)
    }

    @Test
    fun `a declared triangle count larger than the file is clamped`() {
        val bytes = TestModels.binaryStl(TestModels.QUAD)
        // Claim 1000 triangles in an 2-triangle file.
        bytes[80] = 0xE8.toByte()
        bytes[81] = 0x03.toByte()

        val mesh = StlParser.parse(bytes)

        assertEquals("only the triangles present are read", 2, mesh.indices.size / 3)
    }

    @Test(expected = ModelParseException::class)
    fun `an empty binary stl is rejected`() {
        StlParser.parse(TestModels.binaryStl(emptyList()))
    }

    @Test(expected = ModelParseException::class)
    fun `ascii text with no facets is rejected`() {
        StlParser.parse("solid empty\nendsolid empty\n".toByteArray())
    }

    @Test
    fun `an unterminated ascii facet does not produce a partial triangle`() {
        val text = """
            solid test
            facet normal 0 0 1
              outer loop
                vertex 0 0 0
                vertex 1 0 0
                vertex 1 1 0
              endloop
            endfacet
            facet normal 0 0 1
              outer loop
                vertex 5 5 5
        """.trimIndent()

        val mesh = StlParser.parse(text.toByteArray())

        assertEquals("the truncated facet is dropped", 1, mesh.indices.size / 3)
        assertTrue(mesh.positions.size == 9)
    }

    private fun assertArrayEqualsInt(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("index $i", expected[i], actual[i])
    }
}
