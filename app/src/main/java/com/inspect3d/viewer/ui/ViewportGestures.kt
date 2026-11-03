package com.inspect3d.viewer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The viewport's touch handling, as one gesture loop rather than several competing
 * detectors:
 *
 *  * one finger dragging  → orbit
 *  * two fingers pinching → zoom
 *  * two fingers dragging → pan
 *  * a press that never travels past the touch slop → tap (used by measurement mode)
 *
 * Doing it in a single `awaitEachGesture` avoids the usual problem of stacking
 * `detectTapGestures` on top of `detectTransformGestures`, where whichever runs first
 * consumes the events the other needs.
 */
@Composable
fun Modifier.viewportGestures(
    onOrbit: (dx: Float, dy: Float) -> Unit,
    onZoom: (scale: Float) -> Unit,
    onPan: (dx: Float, dy: Float) -> Unit,
    onTap: (position: Offset) -> Unit,
): Modifier {
    // The lambdas are recreated on every recomposition, but the gesture coroutine is
    // started once; this keeps it calling the current ones.
    val handlers = rememberUpdatedState(
        GestureHandlers(onOrbit = onOrbit, onZoom = onZoom, onPan = onPan, onTap = onTap),
    )

    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var maxPointers = 1
            var travelled = 0f
            val slop = viewConfiguration.touchSlop

            while (true) {
                val event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount == 0) break
                if (pressedCount > maxPointers) maxPointers = pressedCount

                val pan = event.calculatePan()
                travelled += pan.getDistance()

                if (pressedCount == 1) {
                    if (pan != Offset.Zero) handlers.value.onOrbit(pan.x, pan.y)
                } else {
                    val zoom = event.calculateZoom()
                    if (zoom > 0f && zoom != 1f) handlers.value.onZoom(zoom)
                    if (pan != Offset.Zero) handlers.value.onPan(pan.x, pan.y)
                }

                event.changes.forEach { it.consume() }
            }

            if (maxPointers == 1 && travelled <= slop) {
                handlers.value.onTap(down.position)
            }
        }
    }
}

private class GestureHandlers(
    val onOrbit: (Float, Float) -> Unit,
    val onZoom: (Float) -> Unit,
    val onPan: (Float, Float) -> Unit,
    val onTap: (Offset) -> Unit,
)
