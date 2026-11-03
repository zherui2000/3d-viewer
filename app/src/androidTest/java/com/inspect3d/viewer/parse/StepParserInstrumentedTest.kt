package com.inspect3d.viewer.parse

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inspect3d.viewer.model.Bounds
import com.inspect3d.viewer.model.LoadedModel
import com.inspect3d.viewer.model.ModelFormat
import com.inspect3d.viewer.model.ModelParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Runs the real OpenCASCADE bridge on a device. The JVM unit tests cannot: the parser is
 * native code built for Android ABIs.
 *
 * The fixture is a hand-written AP214 file: a 20 mm diameter, 30 mm tall cylinder standing
 * on +Z. Small as it is, it covers what goes wrong in a STEP bridge: a curved face that has
 * to be tessellated, a seam edge used in both directions, and a bottom cap declared with
 * `same_sense = .F.`, i.e. a reversed face whose normals and winding must be flipped.
 */
@RunWith(AndroidJUnit4::class)
class StepParserInstrumentedTest {

    private fun fixture(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    @Test
    fun theCylinderComesBackAtItsTrueSizeInMillimetres() {
        val mesh = StepParser.parse(fixture("cylinder.step"))
        val bounds = Bounds.of(mesh.positions)

        assertEquals("millimeter", mesh.unit)
        assertEquals("one solid", 1, mesh.objectCount)
        // The tessellation inscribes the circle, so the width can fall short by at most the
        // chordal deflection; the height is exact.
        assertEquals(20f, bounds.maxX - bounds.minX, 0.05f)
        assertEquals(20f, bounds.maxY - bounds.minY, 0.05f)
        assertEquals(30f, bounds.maxZ - bounds.minZ, 1e-4f)
        assertEquals("stands on z = 0", 0f, bounds.minZ, 1e-4f)
    }

    @Test
    fun everyTriangleFacesOutwardAndAgreesWithItsNormals() {
        val mesh = StepParser.parse(fixture("cylinder.step"))
        val p = mesh.positions
        val n = requireNotNull(mesh.normals)
        var side = 0
        var bottom = 0
        var top = 0

        for (t in 0 until mesh.indices.size / 3) {
            val a = mesh.indices[t * 3] * 3
            val b = mesh.indices[t * 3 + 1] * 3
            val c = mesh.indices[t * 3 + 2] * 3

            // The normal implied by the winding order...
            val e1x = p[b] - p[a]; val e1y = p[b + 1] - p[a + 1]; val e1z = p[b + 2] - p[a + 2]
            val e2x = p[c] - p[a]; val e2y = p[c + 1] - p[a + 1]; val e2z = p[c + 2] - p[a + 2]
            var wx = e1y * e2z - e1z * e2y
            var wy = e1z * e2x - e1x * e2z
            var wz = e1x * e2y - e1y * e2x
            val length = sqrt(wx * wx + wy * wy + wz * wz)
            if (length < 1e-9f) continue
            wx /= length; wy /= length; wz /= length

            // ...must agree with the normals the bridge computed from the surface.
            val vx = n[a] + n[b] + n[c]
            val vy = n[a + 1] + n[b + 1] + n[c + 1]
            val vz = n[a + 2] + n[b + 2] + n[c + 2]
            assertTrue("triangle $t: winding and normals disagree", wx * vx + wy * vy + wz * vz > 0f)

            // And both must point out of the solid.
            val cz = (p[a + 2] + p[b + 2] + p[c + 2]) / 3f
            when {
                abs(wz) < 0.1f -> {
                    val cx = (p[a] + p[b] + p[c]) / 3f
                    val cy = (p[a + 1] + p[b + 1] + p[c + 1]) / 3f
                    assertTrue("side triangle $t faces inward", wx * cx + wy * cy > 0f)
                    side++
                }
                cz < 1f -> {
                    assertTrue("bottom triangle $t faces up, into the part", wz < 0f)
                    bottom++
                }
                else -> {
                    assertTrue("top triangle $t faces down, into the part", wz > 0f)
                    top++
                }
            }
        }

        assertTrue("the curved side was tessellated ($side triangles)", side >= 24)
        assertTrue("the reversed bottom cap is present", bottom > 0)
        assertTrue("the top cap is present", top > 0)
    }

    @Test
    fun aStepModelLoadsLikeAnyOtherAndGetsOutlined() {
        val model = LoadedModel.from("cylinder.step", ModelFormat.STEP, StepParser.parse(fixture("cylinder.step")))

        val edges = requireNotNull(model.featureEdges)
        assertTrue("the two rims are outlined", edges.isNotEmpty())
        // The facets of the side meet at a few degrees and the seam is welded shut, so the
        // only creases are the two circular rims, which lie at the top and bottom.
        for (i in edges.indices step 2) {
            val za = model.positions[edges[i] * 3 + 2]
            val zb = model.positions[edges[i + 1] * 3 + 2]
            assertEquals("an outline segment runs around a rim, never up the side", za, zb, 1e-4f)
        }
    }

    @Test(expected = ModelParseException::class)
    fun somethingThatIsNotStepIsRejectedWithAMessage() {
        StepParser.parse("ISO-10303-21;\nthis is not a step file".toByteArray())
    }
}
