package com.inspect3d.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inspect3d.viewer.core.Vec3
import com.inspect3d.viewer.render.CameraState

/**
 * The XYZ orientation triad drawn in a corner of the viewport.
 *
 * Each world axis is projected through the camera's own right/up/forward vectors — the
 * same basis the renderer and the picker use — so the triad can never disagree with the
 * 3D view. Axes pointing away from the viewer are drawn first and dimmed, so whichever
 * axis is nearer reads on top, the depth cue a static legend lacks.
 *
 * Colours follow the near-universal CAD convention: X red, Y green, Z blue.
 */
@Composable
fun AxisIndicator(
    camera: CameraState,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    lightBackground: Boolean = false,
) {
    val textMeasurer = rememberTextMeasurer()
    val right = camera.right
    val up = camera.up
    val forward = camera.forward

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Axis orientation: X red, Y green, Z blue" },
    ) {
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        val length = this.size.minDimension * 0.33f
        val tipRadius = 8.dp.toPx()

        // A faint disc keeps the triad legible over a busy model.
        drawCircle(
            color = if (lightBackground) Color(0x1A000000) else Color(0x33000000),
            radius = this.size.minDimension / 2f,
        )

        val projected = AXES.map { axis ->
            ProjectedAxis(
                axis = axis,
                screen = Offset(axis.direction dot right, -(axis.direction dot up)),
                // Positive: the axis points into the screen, away from the viewer.
                depth = axis.direction dot forward,
            )
        }.sortedByDescending { it.depth }

        for (p in projected) {
            val away = p.depth > 0.05f
            val color = if (away) p.axis.color.copy(alpha = 0.55f) else p.axis.color
            val tip = centre + p.screen * length

            drawLine(
                color = color,
                start = centre,
                end = tip,
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(color = color, radius = tipRadius, center = tip)

            val label = textMeasurer.measure(
                text = p.axis.label,
                style = TextStyle(
                    color = Color(0xFF0B0F14),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            drawText(
                textLayoutResult = label,
                topLeft = tip - Offset(label.size.width / 2f, label.size.height / 2f),
            )
        }

        drawCircle(
            color = if (lightBackground) Color(0xFF5A6270) else Color(0xFFB8C2D0),
            radius = 2.5.dp.toPx(),
            center = centre,
        )
    }
}

private data class Axis(val label: String, val direction: Vec3, val color: Color)

private data class ProjectedAxis(val axis: Axis, val screen: Offset, val depth: Float)

private val AXES = listOf(
    Axis("X", Vec3(1f, 0f, 0f), Color(0xFFE5534B)),
    Axis("Y", Vec3(0f, 1f, 0f), Color(0xFF57C25E)),
    Axis("Z", Vec3(0f, 0f, 1f), Color(0xFF4C9AFF)),
)
