package com.inspect3d.viewer.parse

import com.inspect3d.viewer.model.ModelParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ObjParserTest {

    private fun parse(text: String) = ObjParser.parse(text.trimIndent().toByteArray())

    @Test
    fun `a quad face is triangulated with a fan`() {
        val mesh = parse(
            """
            v 0 0 0
            v 2 0 0
            v 2 1 0
            v 0 1 0
            f 1 2 3 4
            """,
        )

        assertEquals("one quad becomes two triangles", 2, mesh.indices.size / 3)
        // Fan from the first corner: (0,1,2) then (0,2,3).
        assertEquals(0, mesh.indices[0])
        assertEquals(1, mesh.indices[1])
        assertEquals(2, mesh.indices[2])
        assertEquals(0, mesh.indices[3])
        assertEquals(2, mesh.indices[4])
        assertEquals(3, mesh.indices[5])
    }

    @Test
    fun `negative indices resolve relative to the current vertex count`() {
        val withNegatives = parse(
            """
            v 0 0 0
            v 2 0 0
            v 2 1 0
            f -3 -2 -1
            """,
        )
        val withPositives = parse(
            """
            v 0 0 0
            v 2 0 0
            v 2 1 0
            f 1 2 3
            """,
        )

        assertEquals(withPositives.indices.toList(), withNegatives.indices.toList())
    }

    @Test
    fun `vertex normals are kept and repeated pairs reuse one vertex`() {
        val mesh = parse(
            """
            v 0 0 0
            v 2 0 0
            v 2 1 0
            v 0 1 0
            vn 0 0 1
            f 1//1 2//1 3//1
            f 1//1 3//1 4//1
            """,
        )

        assertNotNull("the file supplies normals, so they must survive", mesh.normals)
        assertEquals(2, mesh.indices.size / 3)
        // Four distinct (position, normal) pairs across the two triangles.
        assertEquals(4, mesh.positions.size / 3)
    }

    @Test
    fun `the same position with two different normals becomes two vertices`() {
        val mesh = parse(
            """
            v 0 0 0
            v 2 0 0
            v 2 1 0
            vn 0 0 1
            vn 1 0 0
            f 1//1 2//1 3//1
            f 1//2 2//2 3//2
            """,
        )

        assertEquals(6, mesh.positions.size / 3)
    }

    @Test
    fun `normals are dropped when only some corners have them`() {
        val mesh = parse(
            """
            v 0 0 0
            v 2 0 0
            v 2 1 0
            vn 0 0 1
            f 1//1 2//1 3
            """,
        )

        assertNull("a partial normal set falls back to flat shading", mesh.normals)
    }

    @Test
    fun `texture coordinates and materials are read past`() {
        val mesh = parse(
            """
            # a comment
            mtllib thing.mtl
            usemtl steel
            v 0 0 0
            vt 0 0
            v 2 0 0
            vt 1 0
            v 2 1 0
            s off
            f 1/1 2/2 3/1
            """,
        )

        assertEquals(1, mesh.indices.size / 3)
        assertEquals(3, mesh.positions.size / 3)
    }

    @Test
    fun `per-vertex colour after the position is ignored`() {
        val mesh = parse(
            """
            v 0 0 0 1.0 0.5 0.2
            v 2 0 0 1.0 0.5 0.2
            v 2 1 0 1.0 0.5 0.2
            f 1 2 3
            """,
        )

        assertEquals(3, mesh.positions.size / 3)
        assertEquals(2f, mesh.positions[3], 1e-5f)
        assertEquals(0f, mesh.positions[4], 1e-5f)
    }

    @Test
    fun `object groups are counted`() {
        val mesh = parse(
            """
            o first
            v 0 0 0
            v 2 0 0
            v 2 1 0
            f 1 2 3
            o second
            v 0 0 5
            v 2 0 5
            v 2 1 5
            f 4 5 6
            """,
        )

        assertEquals(2, mesh.objectCount)
        assertEquals(2, mesh.indices.size / 3)
    }

    @Test(expected = ModelParseException::class)
    fun `a face referencing a vertex that does not exist is rejected`() {
        parse(
            """
            v 0 0 0
            v 2 0 0
            v 2 1 0
            f 1 2 9
            """,
        )
    }

    @Test(expected = ModelParseException::class)
    fun `a file with no faces is rejected`() {
        parse(
            """
            v 0 0 0
            v 2 0 0
            """,
        )
    }
}
