package com.inspect3d.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inspect3d.viewer.model.LoadedModel
import com.inspect3d.viewer.viewmodel.MeasurementState
import com.inspect3d.viewer.viewmodel.formatLength
import com.inspect3d.viewer.viewmodel.formatSignedLength
import com.inspect3d.viewer.viewmodel.unitSuffix

/** Colours of the two measurement markers; kept in step with the renderer's. */
internal val PointAColor = Color(0xFFE5533D)
internal val PointBColor = Color(0xFF4F8EF7)

/**
 * Floating readout for measurement mode: which points are placed, the distance between
 * them in the file's own units, and the per-axis deltas an engineer usually wants next.
 */
@Composable
fun MeasurementCard(
    model: LoadedModel?,
    measurement: MeasurementState,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Straighten,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Measurement",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onUndo, enabled = measurement.pointCount > 0) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo last point")
                }
                IconButton(onClick = onClear, enabled = measurement.pointCount > 0) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear measurement")
                }
            }

            Text(
                text = formatLength(model, measurement.worldDistance),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.width(4.dp))

            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PointIndicator("A", PointAColor, measurement.pointA != null)
                PointIndicator("B", PointBColor, measurement.pointB != null)
            }

            val delta = measurement.worldDelta
            if (delta != null) {
                val suffix = unitSuffix(model).let { if (it.isEmpty()) "" else " $it" }
                Text(
                    text = "ΔX ${formatSignedLength(model, delta.x)}   " +
                        "ΔY ${formatSignedLength(model, delta.y)}   " +
                        "ΔZ ${formatSignedLength(model, delta.z)}$suffix",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Text(
                    text = if (measurement.pointCount == 0) {
                        "Tap the model to place point A."
                    } else {
                        "Tap again to place point B."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PointIndicator(label: String, color: Color, placed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (placed) color else MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (placed) "Point $label set" else "Point $label",
            style = MaterialTheme.typography.bodySmall,
            color = if (placed) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
