package com.inspect3d.viewer.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionStateTest {

    @Test
    fun `the default cut is the XY plane, removing the upper half`() {
        val section = SectionState()

        val n = section.normal
        assertEquals(0f, n.x, 1e-6f)
        assertEquals(0f, n.y, 1e-6f)
        assertEquals("normal points up, at the half that is cut away", 1f, n.z, 1e-6f)
    }

    @Test
    fun `azimuth turns about Z from +X towards +Y`() {
        val alongX = SectionState(azimuthDegrees = 0f, elevationDegrees = 0f).normal
        val alongY = SectionState(azimuthDegrees = 90f, elevationDegrees = 0f).normal

        assertEquals(1f, alongX.x, 1e-6f)
        assertEquals(1f, alongY.y, 1e-6f)
        assertEquals("elevation 0 keeps the normal horizontal", 0f, alongY.z, 1e-6f)
    }

    @Test
    fun `the plane equation removes exactly the half the normal points at`() {
        // Cut at z = 0.25: a point above must be on the removed side, one below must not.
        val plane = SectionState(offsetZ = 0.25f).planeEquation()
        val side = { x: Float, y: Float, z: Float -> plane[0] * x + plane[1] * y + plane[2] * z + plane[3] }

        assertEquals(0.25f, side(0f, 0f, 0.5f), 1e-6f)
        assertEquals(-0.25f, side(0f, 0f, 0f), 1e-6f)
    }

    @Test
    fun `flipping keeps the other half`() {
        val n = SectionState(flipped = true).normal
        assertEquals(-1f, n.z, 1e-6f)
    }
}
