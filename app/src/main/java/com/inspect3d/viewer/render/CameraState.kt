package com.inspect3d.viewer.render

import com.inspect3d.viewer.core.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class ProjectionMode(val label: String) {
    PERSPECTIVE("Perspective"),
    ORTHOGRAPHIC("Orthographic"),
}

/** A ray in world space. */
data class Ray(val origin: Vec3, val direction: Vec3)

/**
 * Orbit camera, expressed as the parameters a user actually manipulates rather than as a
 * matrix: an azimuth/elevation around a target, a distance, and a projection.
 *
 * Keeping the camera as immutable state in the ViewModel (instead of hiding it inside the
 * renderer) is what makes "reset view", projection switching and — importantly —
 * screen-to-world picking straightforward: [rayThrough] reproduces exactly the projection
 * the renderer was told to use, so a tap maps to the pixel the user actually touched.
 *
 * The loaded model is always normalised so its largest half-extent is one world unit, so
 * these distances are meaningful for any file, in any unit.
 */
data class CameraState(
    val yawDegrees: Float = -40f,
    val pitchDegrees: Float = 20f,
    /** Orbit radius for perspective, and the zoom level for orthographic. */
    val distance: Float = DEFAULT_DISTANCE,
    val target: Vec3 = Vec3.ZERO,
    val projection: ProjectionMode = ProjectionMode.PERSPECTIVE,
    val fovDegrees: Float = 45f,
) {

    private val yawRadians: Float get() = Math.toRadians(yawDegrees.toDouble()).toFloat()
    private val pitchRadians: Float get() = Math.toRadians(pitchDegrees.toDouble()).toFloat()

    /**
     * How far the eye actually sits from the target. In orthographic mode the eye is
     * pulled well back and the zoom is done entirely by the projection volume, so zooming
     * in can never push the model behind the near plane.
     */
    val eyeDistance: Float
        get() = if (projection == ProjectionMode.ORTHOGRAPHIC) distance + ORTHO_PULLBACK else distance

    val eye: Vec3
        get() {
            val cp = cos(pitchRadians)
            return target + Vec3(cp * sin(yawRadians), sin(pitchRadians), cp * cos(yawRadians)) * eyeDistance
        }

    val forward: Vec3 get() = (target - eye).normalized()

    val right: Vec3 get() = (forward cross WORLD_UP).normalized()

    val up: Vec3 get() = right cross forward

    /** Height of the orthographic projection volume, matched to the perspective framing. */
    val orthoHeight: Float
        get() = 2f * distance * tan(Math.toRadians(fovDegrees / 2.0)).toFloat()

    val near: Float get() = 0.01f
    val far: Float get() = eyeDistance + 40f

    // ------------------------------------------------------------------ gestures

    /** One-finger drag: orbit around the target. */
    fun orbit(dxPixels: Float, dyPixels: Float): CameraState = copy(
        yawDegrees = (yawDegrees - dxPixels * ORBIT_DEGREES_PER_PIXEL) % 360f,
        pitchDegrees = (pitchDegrees + dyPixels * ORBIT_DEGREES_PER_PIXEL)
            .coerceIn(-MAX_PITCH, MAX_PITCH),
    )

    /** Pinch: [scale] > 1 moves the camera closer. */
    fun zoom(scale: Float): CameraState = copy(
        distance = (distance / scale.coerceIn(0.1f, 10f)).coerceIn(MIN_DISTANCE, MAX_DISTANCE),
    )

    /** Two-finger drag: slide the target (and therefore the eye) across the view plane. */
    fun pan(dxPixels: Float, dyPixels: Float, viewportHeight: Int): CameraState {
        if (viewportHeight <= 0) return this
        val worldPerPixel = worldUnitsPerPixel(viewportHeight)
        val shift = right * (-dxPixels * worldPerPixel) + up * (dyPixels * worldPerPixel)
        return copy(target = target + shift)
    }

    private fun worldUnitsPerPixel(viewportHeight: Int): Float = when (projection) {
        ProjectionMode.PERSPECTIVE ->
            2f * distance * tan(Math.toRadians(fovDegrees / 2.0)).toFloat() / viewportHeight
        ProjectionMode.ORTHOGRAPHIC -> orthoHeight / viewportHeight
    }

    // ------------------------------------------------------------------ picking

    /**
     * The world-space ray through a point on the viewport, in Android screen coordinates
     * (origin top-left, y downwards).
     */
    fun rayThrough(x: Float, y: Float, viewportWidth: Int, viewportHeight: Int): Ray {
        if (viewportWidth <= 0 || viewportHeight <= 0) return Ray(eye, forward)

        val ndcX = 2f * x / viewportWidth - 1f
        val ndcY = 1f - 2f * y / viewportHeight
        val aspect = viewportWidth.toFloat() / viewportHeight

        val eyePoint = eye
        val forwardAxis = forward
        val rightAxis = right
        val upAxis = up

        return when (projection) {
            ProjectionMode.PERSPECTIVE -> {
                val tanHalf = tan(Math.toRadians(fovDegrees / 2.0)).toFloat()
                val direction = forwardAxis +
                    rightAxis * (ndcX * tanHalf * aspect) +
                    upAxis * (ndcY * tanHalf)
                Ray(eyePoint, direction.normalized())
            }

            ProjectionMode.ORTHOGRAPHIC -> {
                val halfHeight = orthoHeight * 0.5f
                val halfWidth = halfHeight * aspect
                val origin = eyePoint +
                    rightAxis * (ndcX * halfWidth) +
                    upAxis * (ndcY * halfHeight)
                Ray(origin, forwardAxis)
            }
        }
    }

    companion object {
        val WORLD_UP = Vec3(0f, 1f, 0f)

        const val DEFAULT_DISTANCE = 3.4f
        const val MIN_DISTANCE = 0.2f
        const val MAX_DISTANCE = 40f
        const val MAX_PITCH = 88f
        private const val ORBIT_DEGREES_PER_PIXEL = 0.3f

        /** Keeps orthographic geometry comfortably in front of the near plane. */
        private const val ORTHO_PULLBACK = 12f

        /** A little air around the model so it does not touch the screen edges. */
        private const val FIT_MARGIN = 1.1f

        /** Worst case for a model normalised to a largest half-extent of 1: a cube. */
        const val MAX_NORMALIZED_RADIUS = 1.74f

        /** A phone in portrait, used until the real viewport is known. */
        const val DEFAULT_ASPECT = 1080f / 2400f

        /**
         * Distance at which a model of the given radius fits the viewport on **both**
         * axes.
         *
         * The vertical field of view alone is not enough: on a phone in portrait the
         * horizontal field is only `aspect` times as wide (about 45% of it), so framing
         * against the vertical axis leaves a wide model running off the sides.
         */
        fun fitDistance(
            radius: Float = MAX_NORMALIZED_RADIUS,
            aspect: Float = DEFAULT_ASPECT,
            fovDegrees: Float = 45f,
        ): Float {
            val tanHalf = tan(Math.toRadians(fovDegrees / 2.0)).toFloat()
            // The narrower of the two half-angles is what actually limits the fit.
            val limiting = minOf(1f, aspect.coerceAtLeast(0.1f))
            return (radius / (tanHalf * limiting) * FIT_MARGIN).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        }

        /** The home view: a three-quarter angle, as CAD viewers open on. */
        fun home(
            projection: ProjectionMode = ProjectionMode.PERSPECTIVE,
            radius: Float = MAX_NORMALIZED_RADIUS,
            aspect: Float = DEFAULT_ASPECT,
        ): CameraState = CameraState(
            projection = projection,
            distance = fitDistance(radius, aspect),
        )
    }
}
