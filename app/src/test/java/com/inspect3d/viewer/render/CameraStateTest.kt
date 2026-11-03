package com.inspect3d.viewer.render

import com.inspect3d.viewer.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class CameraStateTest {

    private val width = 1080
    private val height = 2400

    @Test
    fun `the world is Z-up`() {
        val camera = CameraState()

        assertEquals(1f, CameraState.WORLD_UP.z, 0f)
        assertTrue("the default view looks down onto the XY plane", camera.eye.z > camera.target.z)
        assertTrue("+Z points up the screen", camera.up.z > 0.5f)
        assertEquals("screen-right stays horizontal", 0f, camera.right.z, 1e-5f)
    }

    @Test
    fun `yaw 0 and pitch 0 is the front view, looking along +Y with +X to the right`() {
        val front = CameraState(yawDegrees = 0f, pitchDegrees = 0f)

        assertEquals(1f, front.forward.y, 1e-5f)
        assertEquals(1f, front.right.x, 1e-5f)
        assertEquals(1f, front.up.z, 1e-5f)
    }

    @Test
    fun `orbiting turns about the Z axis and leaves the height alone`() {
        val before = CameraState(yawDegrees = 10f, pitchDegrees = 25f)
        val after = before.orbit(dxPixels = 200f, dyPixels = 0f)

        assertEquals("a horizontal drag must not change the eye height", before.eye.z, after.eye.z, 1e-4f)
        val horizontal = { v: Vec3 -> kotlin.math.sqrt(v.x * v.x + v.y * v.y) }
        assertEquals("nor its distance from the Z axis", horizontal(before.eye), horizontal(after.eye), 1e-4f)
    }

    @Test
    fun `the ray through the centre pixel is the view direction`() {
        val camera = CameraState()

        val ray = camera.rayThrough(width / 2f, height / 2f, width, height)

        val forward = camera.forward
        assertEquals(forward.x, ray.direction.x, 1e-4f)
        assertEquals(forward.y, ray.direction.y, 1e-4f)
        assertEquals(forward.z, ray.direction.z, 1e-4f)
        assertEquals(1f, ray.direction.length, 1e-4f)
    }

    @Test
    fun `a world point projects to a pixel whose ray passes back through it`() {
        // This is the round trip that catches a flipped Y or a transposed aspect ratio —
        // both of which show up in the app as measurements that drift near the edges.
        val camera = CameraState(yawDegrees = -35f, pitchDegrees = 18f, distance = 4f)
        val target = Vec3(0.3f, -0.2f, 0.15f)

        val pixel = project(camera, target)
        val ray = camera.rayThrough(pixel.first, pixel.second, width, height)

        val toPoint = target - ray.origin
        val along = toPoint dot ray.direction
        val closest = ray.origin + ray.direction * along
        val error = (closest - target).length

        assertTrue("ray missed the point by $error", error < 1e-3f)
    }

    @Test
    fun `orthographic rays are parallel and offset across the viewport`() {
        val camera = CameraState(projection = ProjectionMode.ORTHOGRAPHIC)

        val centre = camera.rayThrough(width / 2f, height / 2f, width, height)
        val corner = camera.rayThrough(0f, 0f, width, height)

        assertEquals("directions stay parallel", 1f, centre.direction dot corner.direction, 1e-4f)
        assertTrue("origins differ", (centre.origin - corner.origin).length > 0.1f)
    }

    @Test
    fun `pitch is clamped so the camera never flips over the pole`() {
        var camera = CameraState()
        repeat(100) { camera = camera.orbit(0f, 50f) }
        assertTrue(camera.pitchDegrees <= CameraState.MAX_PITCH)

        repeat(400) { camera = camera.orbit(0f, -50f) }
        assertTrue(camera.pitchDegrees >= -CameraState.MAX_PITCH)
    }

    @Test
    fun `zoom is clamped at both ends`() {
        var camera = CameraState()
        repeat(100) { camera = camera.zoom(2f) }
        assertTrue(camera.distance >= CameraState.MIN_DISTANCE)

        repeat(200) { camera = camera.zoom(0.5f) }
        assertTrue(camera.distance <= CameraState.MAX_DISTANCE)
    }

    @Test
    fun `panning moves the target across the view plane only`() {
        val camera = CameraState()
        val panned = camera.pan(100f, 60f, height)

        val shift = panned.target - camera.target
        assertTrue("the target actually moved", shift.length > 1e-4f)
        assertEquals("and stays in the view plane", 0f, shift dot camera.forward, 1e-4f)
    }

    @Test
    fun `the fit distance accounts for the narrow axis of a portrait viewport`() {
        val radius = 1.2f
        val portrait = CameraState.fitDistance(radius, aspect = 0.45f)
        val square = CameraState.fitDistance(radius, aspect = 1f)

        assertTrue(
            "a portrait viewport must pull the camera further back ($portrait vs $square)",
            portrait > square * 1.5f,
        )
    }

    @Test
    fun `a model at the fit distance is inside the frustum on both axes`() {
        val radius = 1.3f
        val aspect = width.toFloat() / height
        val fov = 45f
        val distance = CameraState.fitDistance(radius, aspect, fov)

        val halfHeight = distance * tan(Math.toRadians(fov / 2.0)).toFloat()
        val halfWidth = halfHeight * aspect

        assertTrue("vertical fit: $halfHeight vs $radius", halfHeight >= radius)
        assertTrue("horizontal fit: $halfWidth vs $radius", halfWidth >= radius)
    }

    @Test
    fun `switching to orthographic keeps the eye clear of the near plane`() {
        val perspective = CameraState(distance = 0.5f)
        val ortho = perspective.copy(projection = ProjectionMode.ORTHOGRAPHIC)

        assertTrue(
            "the orthographic eye is pulled back so zooming in cannot clip the model",
            ortho.eyeDistance > perspective.eyeDistance + 5f,
        )
        assertTrue(ortho.near > 0f)
        assertTrue(ortho.far > ortho.eyeDistance)
    }

    /** Projects a world point to a screen pixel, independently of the production code. */
    private fun project(camera: CameraState, point: Vec3): Pair<Float, Float> {
        val eye = camera.eye
        val relative = point - eye
        val z = relative dot camera.forward
        val x = relative dot camera.right
        val y = relative dot camera.up

        val tanHalf = tan(Math.toRadians(camera.fovDegrees / 2.0)).toFloat()
        val aspect = width.toFloat() / height
        val ndcX = (x / z) / (tanHalf * aspect)
        val ndcY = (y / z) / tanHalf

        return Pair((ndcX + 1f) / 2f * width, (1f - ndcY) / 2f * height)
    }
}
