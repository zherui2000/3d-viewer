package com.inspect3d.viewer.parse

import com.inspect3d.viewer.TestModels
import com.inspect3d.viewer.model.Bounds
import com.inspect3d.viewer.model.ModelParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GltfParserTest {

    @Test
    fun `a glb container with a bin chunk is read`() {
        val bytes = TestModels.glb(TestModels.GLTF_QUAD_JSON, TestModels.quadGltfBin())

        val mesh = GltfParser.parse(bytes)

        assertEquals(2, mesh.indices.size / 3)
        assertEquals("glTF is defined in metres", "meter", mesh.unit)

        val bounds = Bounds.of(mesh.positions)
        assertEquals(0f, bounds.minX, 1e-4f)
        assertEquals(4f, bounds.maxX, 1e-4f)
        assertEquals("glTF's +Y becomes the viewer's +Z", 2f, bounds.maxZ, 1e-4f)
    }

    @Test
    fun `glTF's Y-up frame is turned into the viewer's Z-up frame`() {
        // The quad lies in glTF's z = 0 plane, spanning +Y as its "height". Upright in a
        // Y-up scene, it must be upright in the Z-up viewer too: height along Z, no depth.
        val mesh = GltfParser.parse(TestModels.glb(TestModels.GLTF_QUAD_JSON, TestModels.quadGltfBin()))
        val bounds = Bounds.of(mesh.positions)

        assertEquals("height moved from Y to Z", 0f, bounds.minZ, 1e-4f)
        assertEquals(2f, bounds.maxZ, 1e-4f)
        assertEquals("glTF's z = 0 plane is now y = 0", 0f, bounds.minY, 1e-4f)
        assertEquals(0f, bounds.maxY, 1e-4f)
    }

    @Test
    fun `the up-axis conversion is a proper rotation, so winding survives`() {
        // A reflection would flip every triangle inside out and the model would render
        // back-face shaded; the determinant tells the two apart.
        val m = GltfParser.Y_UP_TO_Z_UP
        val det = m[0] * (m[5] * m[10] - m[9] * m[6]) -
            m[4] * (m[1] * m[10] - m[9] * m[2]) +
            m[8] * (m[1] * m[6] - m[5] * m[2])
        assertEquals(1f, det, 1e-6f)
    }

    @Test
    fun `a primitive without normals falls back to flat shading`() {
        val mesh = GltfParser.parse(TestModels.glb(TestModels.GLTF_QUAD_JSON, TestModels.quadGltfBin()))
        assertNull(mesh.normals)
    }

    @Test
    fun `a gltf with an embedded base64 buffer is read`() {
        val encoded = Base64.getEncoder().encodeToString(TestModels.quadGltfBin())
        val json = TestModels.GLTF_QUAD_JSON.replace(
            """"buffers":[{"byteLength":60}]""",
            """"buffers":[{"byteLength":60,"uri":"data:application/octet-stream;base64,$encoded"}]""",
        )

        val mesh = GltfParser.parse(json.toByteArray(Charsets.UTF_8))

        assertEquals(2, mesh.indices.size / 3)
        assertEquals(4f, Bounds.of(mesh.positions).maxX, 1e-4f)
    }

    @Test
    fun `node transforms are applied to the geometry`() {
        val json = TestModels.GLTF_QUAD_JSON.replace(
            """"nodes":[{"mesh":0}]""",
            """"nodes":[{"mesh":0,"scale":[2,3,1],"translation":[1,0,0]}]""",
        )

        val mesh = GltfParser.parse(TestModels.glb(json, TestModels.quadGltfBin()))

        val bounds = Bounds.of(mesh.positions)
        assertEquals("x scaled by 2 then shifted by 1", 1f, bounds.minX, 1e-4f)
        assertEquals(9f, bounds.maxX, 1e-4f)
        // Node transforms apply in glTF's own frame, before the up-axis turn.
        assertEquals("glTF y scaled by 3, now the viewer's z", 6f, bounds.maxZ, 1e-4f)
    }

    @Test
    fun `an external bin file is reported with a message the user can act on`() {
        val json = TestModels.GLTF_QUAD_JSON.replace(
            """"buffers":[{"byteLength":60}]""",
            """"buffers":[{"byteLength":60,"uri":"geometry.bin"}]""",
        )

        val error = runCatching { GltfParser.parse(json.toByteArray()) }.exceptionOrNull()

        assertTrue(error is ModelParseException)
        assertTrue(
            "the message should name the missing file: ${error?.message}",
            error!!.message!!.contains("geometry.bin"),
        )
    }

    @Test
    fun `draco compression is rejected rather than silently rendering nothing`() {
        val json = TestModels.GLTF_QUAD_JSON.replace(
            """{"asset":{"version":"2.0"}""",
            """{"extensionsRequired":["KHR_draco_mesh_compression"],"asset":{"version":"2.0"}""",
        )

        val error = runCatching {
            GltfParser.parse(TestModels.glb(json, TestModels.quadGltfBin()))
        }.exceptionOrNull()

        assertTrue(error is ModelParseException)
        assertTrue(error!!.message!!.contains("Draco"))
    }

    @Test(expected = ModelParseException::class)
    fun `a glb without a json chunk is rejected`() {
        GltfParser.parse(TestModels.glb("", null).copyOfRange(0, 12))
    }

    @Test(expected = ModelParseException::class)
    fun `json that is not glTF is rejected`() {
        GltfParser.parse("""{"hello":"world"}""".toByteArray())
    }

    @Test(expected = ModelParseException::class)
    fun `malformed json is rejected`() {
        GltfParser.parse("""{"asset": """.toByteArray())
    }
}
