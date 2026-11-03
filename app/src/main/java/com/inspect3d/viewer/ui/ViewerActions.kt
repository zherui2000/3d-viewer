package com.inspect3d.viewer.ui

import com.inspect3d.viewer.render.ProjectionMode
import com.inspect3d.viewer.viewmodel.InteractionMode
import com.inspect3d.viewer.viewmodel.ModelColor
import com.inspect3d.viewer.viewmodel.ShadingMode

/**
 * Everything the overlays can ask the ViewModel to do, bundled so the panels stay
 * stateless (and previewable) without taking twenty lambda parameters each.
 */
class ViewerActions(
    val openFilePicker: () -> Unit,
    val resetCamera: () -> Unit,
    val setProjection: (ProjectionMode) -> Unit,
    val setShading: (ShadingMode) -> Unit,
    val setColor: (ModelColor) -> Unit,
    val setMetallic: (Float) -> Unit,
    val setRoughness: (Float) -> Unit,
    val setGridVisible: (Boolean) -> Unit,
    val setInteractionMode: (InteractionMode) -> Unit,
    val undoLastPoint: () -> Unit,
    val clearMeasurement: () -> Unit,
    val setSectionEnabled: (Boolean) -> Unit,
    val setSectionOffsetX: (Float) -> Unit,
    val setSectionOffsetY: (Float) -> Unit,
    val setSectionOffsetZ: (Float) -> Unit,
    val setSectionAzimuth: (Float) -> Unit,
    val setSectionElevation: (Float) -> Unit,
    val setSectionFlipped: (Boolean) -> Unit,
    val setSectionPlaneVisible: (Boolean) -> Unit,
    val resetSection: () -> Unit,
)
