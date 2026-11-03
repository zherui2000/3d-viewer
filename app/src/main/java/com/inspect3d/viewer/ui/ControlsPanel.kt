package com.inspect3d.viewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.inspect3d.viewer.render.ProjectionMode
import com.inspect3d.viewer.viewmodel.InteractionMode
import com.inspect3d.viewer.viewmodel.ModelColor
import com.inspect3d.viewer.viewmodel.SectionState
import com.inspect3d.viewer.viewmodel.ShadingMode
import com.inspect3d.viewer.viewmodel.ViewerUiState
import com.inspect3d.viewer.viewmodel.formatLength
import com.inspect3d.viewer.viewmodel.formatNumber

/**
 * The bottom control surface.
 *
 * Collapsed it keeps the four actions that are needed mid-inspection (measure, section,
 * reset view, expand) within thumb reach; expanded it shows the full rendering,
 * measurement and section controls as tabs.
 */
@Composable
fun ControlsPanel(
    state: ViewerUiState,
    actions: ViewerActions,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(bottom = 10.dp)) {
            // Drag handle doubles as the expand/collapse hit target.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline),
                )
            }

            QuickActions(
                state = state,
                actions = actions,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    PrimaryTabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent) {
                        TABS.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text(title, style = MaterialTheme.typography.labelLarge) },
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .heightIn(max = 290.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        when (selectedTab) {
                            0 -> RenderingControls(state, actions)
                            1 -> MeasurementControls(state, actions)
                            else -> SectionControls(state, actions)
                        }
                    }
                }
            }
        }
    }
}

private val TABS = listOf("Rendering", "Measurement", "Section view")

@Composable
private fun QuickActions(
    state: ViewerUiState,
    actions: ViewerActions,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.interactionMode == InteractionMode.MEASURE,
            onClick = {
                actions.setInteractionMode(
                    if (state.interactionMode == InteractionMode.MEASURE) {
                        InteractionMode.ORBIT
                    } else {
                        InteractionMode.MEASURE
                    },
                )
            },
            enabled = state.hasModel,
            label = { Text("Measure") },
            leadingIcon = {
                Icon(Icons.Rounded.Straighten, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )

        FilterChip(
            selected = state.section.enabled,
            onClick = { actions.setSectionEnabled(!state.section.enabled) },
            enabled = state.hasModel,
            label = { Text("Section") },
            leadingIcon = {
                Icon(Icons.Rounded.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )

        Spacer(Modifier.weight(1f))

        IconButton(onClick = actions.resetCamera, enabled = state.hasModel) {
            Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Reset camera")
        }
        IconButton(onClick = { onExpandedChange(!expanded) }) {
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = if (expanded) "Collapse controls" else "Expand controls",
            )
        }
    }
}

// ---------------------------------------------------------------- rendering

@Composable
private fun RenderingControls(state: ViewerUiState, actions: ViewerActions) {
    val appearance = state.appearance

    GroupLabel("Display mode")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShadingMode.entries.forEach { mode ->
            FilterChip(
                selected = appearance.shading == mode,
                onClick = { actions.setShading(mode) },
                label = { Text(mode.label) },
            )
        }
    }

    GroupLabel("Projection")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProjectionMode.entries.forEach { projection ->
            FilterChip(
                selected = state.camera.projection == projection,
                onClick = { actions.setProjection(projection) },
                label = { Text(projection.label) },
            )
        }
    }

    GroupLabel("Surface")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ModelColor.entries.forEach { color ->
            val selected = appearance.color == color
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(color.red, color.green, color.blue))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .clickable { actions.setColor(color) },
            )
        }
    }

    LabeledSlider(
        label = "Metallic",
        value = appearance.metallic,
        valueText = formatNumber(appearance.metallic),
        valueRange = 0f..1f,
        onValueChange = actions.setMetallic,
    )
    LabeledSlider(
        label = "Roughness",
        value = appearance.roughness,
        valueText = formatNumber(appearance.roughness),
        valueRange = 0.05f..1f,
        onValueChange = actions.setRoughness,
    )

    ToggleRow(
        label = "Ground grid",
        checked = appearance.showGrid,
        onCheckedChange = actions.setGridVisible,
    )
}

// ---------------------------------------------------------------- measurement

@Composable
private fun MeasurementControls(state: ViewerUiState, actions: ViewerActions) {
    ToggleRow(
        label = "Measurement mode",
        checked = state.interactionMode == InteractionMode.MEASURE,
        enabled = state.hasModel,
        onCheckedChange = { enabled ->
            actions.setInteractionMode(if (enabled) InteractionMode.MEASURE else InteractionMode.ORBIT)
        },
    )

    Text(
        text = "With measurement mode on, a tap casts a ray into the mesh and places a " +
            "marker on the surface it hits. Dragging still orbits, so you can line up a " +
            "view before each tap.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )

    ReadoutRow("Distance", formatLength(state.model, state.measurement.worldDistance))
    state.measurement.pointA?.let { point ->
        ReadoutRow(
            "Point A",
            "${formatNumber(point.x)}, ${formatNumber(point.y)}, ${formatNumber(point.z)}",
        )
    }
    state.measurement.pointB?.let { point ->
        ReadoutRow(
            "Point B",
            "${formatNumber(point.x)}, ${formatNumber(point.y)}, ${formatNumber(point.z)}",
        )
    }

    Row(
        modifier = Modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = actions.undoLastPoint,
            enabled = state.measurement.pointCount > 0,
        ) {
            Text("Undo point")
        }
        Button(
            onClick = actions.clearMeasurement,
            enabled = state.measurement.pointCount > 0,
        ) {
            Text("Clear")
        }
    }
}

// ---------------------------------------------------------------- section

@Composable
private fun SectionControls(state: ViewerUiState, actions: ViewerActions) {
    val section = state.section
    val range = -SectionState.OFFSET_RANGE..SectionState.OFFSET_RANGE

    ToggleRow(
        label = "Section view",
        checked = section.enabled,
        enabled = state.hasModel,
        onCheckedChange = actions.setSectionEnabled,
    )

    GroupLabel("Plane position")
    LabeledSlider(
        label = "Offset X",
        value = section.offsetX,
        valueText = formatLength(state.model, section.offsetX),
        valueRange = range,
        onValueChange = actions.setSectionOffsetX,
    )
    LabeledSlider(
        label = "Offset Y",
        value = section.offsetY,
        valueText = formatLength(state.model, section.offsetY),
        valueRange = range,
        onValueChange = actions.setSectionOffsetY,
    )
    LabeledSlider(
        label = "Offset Z",
        value = section.offsetZ,
        valueText = formatLength(state.model, section.offsetZ),
        valueRange = range,
        onValueChange = actions.setSectionOffsetZ,
    )

    GroupLabel("Plane normal")
    LabeledSlider(
        label = "Azimuth",
        value = section.azimuthDegrees,
        valueText = "${formatNumber(section.azimuthDegrees)}°",
        valueRange = -180f..180f,
        onValueChange = actions.setSectionAzimuth,
    )
    LabeledSlider(
        label = "Elevation",
        value = section.elevationDegrees,
        valueText = "${formatNumber(section.elevationDegrees)}°",
        valueRange = -90f..90f,
        onValueChange = actions.setSectionElevation,
    )

    ToggleRow(
        label = "Keep the other half",
        checked = section.flipped,
        onCheckedChange = actions.setSectionFlipped,
    )
    ToggleRow(
        label = "Show the plane",
        checked = section.showPlane,
        onCheckedChange = actions.setSectionPlaneVisible,
    )

    OutlinedButton(
        onClick = actions.resetSection,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text("Reset plane")
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ReadoutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
