package com.inspect3d.viewer.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inspect3d.viewer.core.Vec3
import com.inspect3d.viewer.model.ModelParseException
import com.inspect3d.viewer.parse.ModelRepository
import com.inspect3d.viewer.render.CameraState
import com.inspect3d.viewer.render.ProjectionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Single source of truth for the viewer.
 *
 * Holds the loaded model, the camera, the appearance, the section plane and the
 * measurement points. The renderer reads this state once per frame and reconciles the
 * scene with it, so gestures and sliders never have to talk to the engine directly.
 */
class ViewerViewModel(application: Application) : ViewModel() {

    private val repository = ModelRepository(application)

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    /** One-shot messages for the snackbar (load failures, picking hints, limits). */
    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var loadJob: Job? = null

    /** Last known viewport shape, needed to frame a model so it fits both screen axes. */
    private var viewportAspect = CameraState.DEFAULT_ASPECT

    /** Set once the user moves the camera, so automatic reframing stops interfering. */
    private var cameraAdjusted = false

    // ------------------------------------------------------------------ loading

    fun openModel(uri: Uri) {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, loadingFileName = uri.lastPathSegment) }

        loadJob = viewModelScope.launch {
            try {
                Log.i(TAG, "Opening $uri")
                val model = repository.load(uri)
                Log.i(
                    TAG,
                    "Loaded ${model.name}: ${model.format.label}, " +
                        "${model.triangleCount} triangles, ${model.vertexCount} vertices, " +
                        "bounds ${model.sourceSize.x} x ${model.sourceSize.y} x ${model.sourceSize.z} " +
                        "${model.unit ?: "(no unit)"}",
                )
                cameraAdjusted = false
                _uiState.update {
                    it.copy(
                        model = model,
                        isLoading = false,
                        loadingFileName = null,
                        // A new part deserves a clean slate: home view, no measurement,
                        // and the section plane back at the centre.
                        camera = CameraState.home(
                            projection = it.camera.projection,
                            radius = model.normalizedRadius,
                            aspect = viewportAspect,
                        ),
                        measurement = MeasurementState(),
                        section = SectionState(
                            enabled = false,
                            showPlane = it.section.showPlane,
                        ),
                        interactionMode = InteractionMode.ORBIT,
                    )
                }
                _messages.tryEmit(
                    "${model.name} · ${formatCount(model.triangleCount)} triangles",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelParseException) {
                // Logged as well as shown: a snackbar is gone in four seconds, and this
                // is the first thing anyone debugging a failed open will look for.
                Log.e(TAG, "Failed to open $uri", e)
                _uiState.update { it.copy(isLoading = false, loadingFileName = null) }
                _messages.tryEmit(e.message ?: "This file could not be opened.")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected failure opening $uri", e)
                _uiState.update { it.copy(isLoading = false, loadingFileName = null) }
                _messages.tryEmit("This file could not be opened (${e.javaClass.simpleName}).")
            }
        }
    }

    fun closeModel() {
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                model = null,
                isLoading = false,
                measurement = MeasurementState(),
                section = it.section.copy(enabled = false),
            )
        }
    }

    fun showNotice(message: String) {
        _messages.tryEmit(message)
    }

    // ------------------------------------------------------------------ camera

    fun orbit(dxPixels: Float, dyPixels: Float) {
        cameraAdjusted = true
        _uiState.update { it.copy(camera = it.camera.orbit(dxPixels, dyPixels)) }
    }

    fun zoom(scale: Float) {
        cameraAdjusted = true
        _uiState.update { it.copy(camera = it.camera.zoom(scale)) }
    }

    fun pan(dxPixels: Float, dyPixels: Float, viewportHeight: Int) {
        cameraAdjusted = true
        _uiState.update { it.copy(camera = it.camera.pan(dxPixels, dyPixels, viewportHeight)) }
    }

    fun resetCamera() {
        cameraAdjusted = false
        _uiState.update { it.copy(camera = homeCameraFor(it)) }
    }

    /**
     * The renderer reports its surface size here. A portrait phone's horizontal field of
     * view is less than half its vertical one, so the framing distance depends on the
     * viewport shape and cannot be a constant.
     */
    fun onViewportChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val aspect = width.toFloat() / height.toFloat()
        if (abs(aspect - viewportAspect) < 0.01f) return
        viewportAspect = aspect
        // Never yank a camera the user has positioned themselves.
        if (cameraAdjusted) return
        _uiState.update { it.copy(camera = homeCameraFor(it)) }
    }

    private fun homeCameraFor(state: ViewerUiState): CameraState = CameraState.home(
        projection = state.camera.projection,
        radius = state.model?.normalizedRadius ?: CameraState.MAX_NORMALIZED_RADIUS,
        aspect = viewportAspect,
    )

    fun setProjection(projection: ProjectionMode) {
        _uiState.update { it.copy(camera = it.camera.copy(projection = projection)) }
    }

    fun toggleProjection() {
        setProjection(
            if (_uiState.value.camera.projection == ProjectionMode.PERSPECTIVE) {
                ProjectionMode.ORTHOGRAPHIC
            } else {
                ProjectionMode.PERSPECTIVE
            },
        )
    }

    // ------------------------------------------------------------------ appearance

    fun setShading(mode: ShadingMode) {
        _uiState.update { it.copy(appearance = it.appearance.copy(shading = mode)) }
    }

    fun setColor(color: ModelColor) {
        _uiState.update { it.copy(appearance = it.appearance.copy(color = color)) }
    }

    fun setMetallic(value: Float) {
        _uiState.update { it.copy(appearance = it.appearance.copy(metallic = value.coerceIn(0f, 1f))) }
    }

    fun setRoughness(value: Float) {
        _uiState.update { it.copy(appearance = it.appearance.copy(roughness = value.coerceIn(0.05f, 1f))) }
    }

    fun setGridVisible(visible: Boolean) {
        _uiState.update { it.copy(appearance = it.appearance.copy(showGrid = visible)) }
    }

    // ------------------------------------------------------------------ measuring

    fun setInteractionMode(mode: InteractionMode) {
        _uiState.update { it.copy(interactionMode = mode) }
    }

    /**
     * Records a picked surface point. The first tap sets A, the second sets B, and a
     * third starts a new measurement from the point just tapped — which is how a user
     * expects to chain measurements without reaching for a "clear" button.
     */
    fun onSurfacePicked(point: Vec3) {
        _uiState.update { state ->
            val current = state.measurement
            val next = when {
                current.pointA == null -> current.copy(pointA = point)
                current.pointB == null -> current.copy(pointB = point)
                else -> MeasurementState(pointA = point)
            }
            state.copy(measurement = next)
        }
    }

    /** Called when a measurement tap did not hit the mesh. */
    fun onPickMissed() {
        _messages.tryEmit("No surface there — tap directly on the model.")
    }

    fun undoLastPoint() {
        _uiState.update { state ->
            val current = state.measurement
            val next = when {
                current.pointB != null -> current.copy(pointB = null)
                else -> MeasurementState()
            }
            state.copy(measurement = next)
        }
    }

    fun clearMeasurement() {
        _uiState.update { it.copy(measurement = MeasurementState()) }
    }

    // ------------------------------------------------------------------ section

    fun setSectionEnabled(enabled: Boolean) {
        _uiState.update { it.copy(section = it.section.copy(enabled = enabled)) }
    }

    fun setSectionOffset(x: Float = _uiState.value.section.offsetX, y: Float = _uiState.value.section.offsetY, z: Float = _uiState.value.section.offsetZ) {
        _uiState.update { it.copy(section = it.section.copy(offsetX = x, offsetY = y, offsetZ = z)) }
    }

    fun setSectionAzimuth(degrees: Float) {
        _uiState.update { it.copy(section = it.section.copy(azimuthDegrees = degrees)) }
    }

    fun setSectionElevation(degrees: Float) {
        _uiState.update { it.copy(section = it.section.copy(elevationDegrees = degrees)) }
    }

    fun setSectionFlipped(flipped: Boolean) {
        _uiState.update { it.copy(section = it.section.copy(flipped = flipped)) }
    }

    fun setSectionPlaneVisible(visible: Boolean) {
        _uiState.update { it.copy(section = it.section.copy(showPlane = visible)) }
    }

    fun resetSection() {
        _uiState.update {
            it.copy(section = SectionState(enabled = it.section.enabled, showPlane = it.section.showPlane))
        }
    }

    private companion object {
        const val TAG = "Inspect3D"
    }

    /** Minimal factory so the ViewModel can take the Application without Hilt. */
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ViewerViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return ViewerViewModel(application) as T
        }
    }
}
