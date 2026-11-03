package com.inspect3d.viewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The encoding is hand-written matrix-to-quaternion maths whose failure mode is strange
 * shading rather than a crash, so it is checked against its own inverse: rotate (0,0,1) by
 * the quaternion — what the shader does — and the original normal must come back.
 */
class TangentFrameTest {

    private val quaternion = FloatArray(4)

    @Test
    fun `axis normals round trip`() {
        val axes = listOf(
            Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f),
            Vec3(0f, 1f, 0f), Vec3(0f, -1f, 0f),
            Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f),
        )

        for (axis in axes) {
            TangentFrame.pack(axis.x, axis.y, axis.z, quaternion)
            val decoded = TangentFrame.normalFrom(quaternion)
            assertEquals("x for $axis", axis.x, decoded.x, 1e-4f)
            assertEquals("y for $axis", axis.y, decoded.y, 1e-4f)
            assertEquals("z for $axis", axis.z, decoded.z, 1e-4f)
        }
    }

    @Test
    fun `random normals round trip`() {
        val random = Random(4711)
        repeat(500) {
            val n = Vec3(
                random.nextFloat() * 2f - 1f,
                random.nextFloat() * 2f - 1f,
                random.nextFloat() * 2f - 1f,
            )
            if (n.length < 1e-3f) return@repeat
            val unit = n.normalized()

            TangentFrame.pack(unit.x, unit.y, unit.z, quaternion)
            val decoded = TangentFrame.normalFrom(quaternion)

            assertEquals("x", unit.x, decoded.x, 1e-3f)
            assertEquals("y", unit.y, decoded.y, 1e-3f)
            assertEquals("z", unit.z, decoded.z, 1e-3f)
        }
    }

    @Test
    fun `an unnormalised normal is normalised first`() {
        TangentFrame.pack(0f, 7.5f, 0f, quaternion)
        val decoded = TangentFrame.normalFrom(quaternion)

        assertEquals(0f, decoded.x, 1e-4f)
        assertEquals(1f, decoded.y, 1e-4f)
        assertEquals(0f, decoded.z, 1e-4f)
    }

    @Test
    fun `the quaternion is unit length and keeps w non-negative`() {
        val random = Random(99)
        repeat(200) {
            val n = Vec3(
                random.nextFloat() * 2f - 1f,
                random.nextFloat() * 2f - 1f,
                random.nextFloat() * 2f - 1f,
            )
            if (n.length < 1e-3f) return@repeat
            val unit = n.normalized()
            TangentFrame.pack(unit.x, unit.y, unit.z, quaternion)

            val length = sqrt(
                quaternion[0] * quaternion[0] + quaternion[1] * quaternion[1] +
                    quaternion[2] * quaternion[2] + quaternion[3] * quaternion[3],
            )
            assertEquals("unit quaternion", 1f, length, 1e-3f)
            // Filament reads w's sign as handedness.
            assertTrue("w must not be negative, was ${quaternion[3]}", quaternion[3] >= -1e-6f)
        }
    }

    @Test
    fun `a zero normal still produces a finite frame`() {
        TangentFrame.pack(0f, 0f, 0f, quaternion)

        quaternion.forEachIndexed { i, v ->
            assertTrue("component $i must be finite, was $v", v.isFinite())
        }
        val decoded = TangentFrame.normalFrom(quaternion)
        assertEquals("falls back to +Y", 1f, decoded.y, 1e-4f)
    }
}
