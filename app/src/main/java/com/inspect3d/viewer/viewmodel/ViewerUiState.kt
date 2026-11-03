package com.inspect3d.viewer.viewmodel

import com.inspect3d.viewer.core.Vec3
import com.inspect3d.viewer.model.LoadedModel
import com.inspect3d.viewer.render.CameraState
import kotlin.math.cos
import kotlin.math.sin

/** What a tap on the viewport does. */
enum class InteractionMode(val label: String) {
    ORBIT("Navigate"),
    MEASURE("Measure"),
}

enum class ShadingMode(val label: String) {
    SOLID("Solid"),
    WIREFRAME("Wireframe"),
    SOLID_WITH_EDGES("Solid + edges"),
    ;

    val drawsSolid: Boolean get() = this != WIREFRAME
    val drawsWireframe: Boolean get() = this != SOLID
}

/** Preset surface colours, given in sRGB; the renderer converts them to linear. */
enum class ModelColor(val label: String, val red: Float, val green: Float, val blue: Float) {
    STEEL("Steel", 0.76f, 0.79f, 0.84f),
    SLATE("Slate", 0.42f, 0.48f, 0.56f),
    TEAL("Teal", 0.20f, 0.65f, 0.63f),
    AMBER("Amber", 0.93f, 0.65f, 0.20f),
    COPPER("Copper", 0.80f, 0.45f, 0.30f),
}

data class AppearanceState(
    val shading: ShadingMode = ShadingMode.SOLID,
    val color: ModelColor = ModelColor.STEEL,
    val metallic: Float = 0.25f,
    val roughness: Float = 0.45f,
    val showGrid: Boolean = true,
)

/**
 * The cross-section plane.
 *
 * The plane is defined by a point ([offsetX], [offsetY], [offsetZ]) that the three
 * translation sliders move, and a normal built from two rotation sliders. Everything in
 * the half space `dot(normal, p) + w > 0` is cut away, which is exactly the test the
 * shader performs.
 *
 * Offsets are in world units: the model is normalised so its largest half-extent is 1,
 * so the useful range is about -1.5 to 1.5 for any file.
 */
data class SectionState(
    val enabled: Boolean = false,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f,
    val azimuthDegrees: Float = 0f,
    val elevationDegrees: Float = 0f,
    val flipped: Boolean = false,
    val showPlane: Boolean = true,
) {
    val origin: Vec3 get() = Vec3(offsetX, offsetY, offsetZ)

    /** Unit normal of the plane, pointing at the half that gets removed. */
    val normal: Vec3
        get() {
            val azimuth = Math.toRadians(azimuthDegrees.toDouble()).toFloat()
            val elevation = Math.toRadians(elevationDegrees.toDouble()).toFloat()
            val horizontal = cos(elevation)
            val direction = Vec3(
                horizontal * cos(azimuth),
                sin(elevation),
                horizontal * sin(azimuth),
            ).normalized()
            return if (flipped) -direction else direction
        }

    /** `(nx, ny, nz, w)` as handed to the shader and to the picker. */
    fun planeEquation(): FloatArray {
        val n = normal
        return floatArrayOf(n.x, n.y, n.z, -(n dot origin))
    }

    companion object {
        const val OFFSET_RANGE = 1.5f
    }
}

/**
 * The two measured points, in world (render) units. Lengths are converted back to the
 * file's own units for display — see [formatLength].
 */
data class MeasurementState(
    val pointA: Vec3? = null,
    val pointB: Vec3? = null,
) {
    val pointCount: Int get() = (if (pointA != null) 1 else 0) + (if (pointB != null) 1 else 0)

    val isComplete: Boolean get() = pointA != null && pointB != null

    /** Euclidean distance in world units, or `null` until both points are placed. */
    val worldDistance: Float?
        get() {
            val a = pointA ?: return null
            val b = pointB ?: return null
            return (b - a).length
        }

    /** Per-axis deltas in world units. */
    val worldDelta: Vec3?
        get() {
            val a = pointA ?: return null
            val b = pointB ?: return null
            return b - a
        }
}

data class ViewerUiState(
    val model: LoadedModel? = null,
    val isLoading: Boolean = false,
    val loadingFileName: String? = null,
    val camera: CameraState = CameraState.home(),
    val appearance: AppearanceState = AppearanceState(),
    val section: SectionState = SectionState(),
    val measurement: MeasurementState = MeasurementState(),
    val interactionMode: InteractionMode = InteractionMode.ORBIT,
) {
    val hasModel: Boolean get() = model != null
}
