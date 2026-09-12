package com.redcut.feature.editor.timeline

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import com.redcut.core.common.timeline.TimelineGeometry
import com.redcut.core.common.timeline.TimelineHit
import com.redcut.core.common.timeline.TimelineZoom
import com.redcut.feature.editor.EditorIntent

/**
 * The timeline's gestures, and the ORDER that decides who wins.
 *
 * A `Modifier` chain runs its pointer handlers in declaration order, so this file's whole job is to
 * state that order once, readably: the ruler (which consumes what it takes) is asked first, then
 * pan/zoom, then taps. Getting it wrong is not a crash — it is a timeline where a pinch also scrubs,
 * or where dragging the ruler scrolls the clips instead.
 *
 * That arbitration is BEHAVIOUR, and behaviour here is verified by a device pass (Phase 1's exit
 * criterion), not by CI. This host has no Android runtime, and no test can press a finger on the
 * Canvas. What CI proves is that this compiles and that the arithmetic it delegates to is tested.
 */

/**
 * The four things a gesture can do. One value, so the Canvas call stays a single statement.
 *
 * Named after the file (and distinguished from the `Modifier.timelineGestures` extension below only
 * by its first letter) because it is the file's one type: the rest are the factory that builds it and
 * the detectors that call it.
 */
internal class TimelineGestures(
    val scrub: (screenX: Float) -> Unit,
    val scroll: (deltaPx: Float) -> Unit,
    val zoom: (anchorX: Float, factor: Float) -> Unit,
    val tap: (screenX: Float) -> Unit,
)

/**
 * Builds the gesture actions from the current [geometry] and the viewport's setters.
 *
 * Every one of these has the same shape — ask the geometry a question, then either send an intent or
 * write the viewport — so they live together, and the composable above reads as "state, then draw".
 */
internal fun gestureActions(
    geometry: TimelineGeometry,
    onIntent: (EditorIntent) -> Unit,
    setScrollPx: (Float) -> Unit,
    setZoomPxPerSecond: (Float) -> Unit,
): TimelineGestures = TimelineGestures(
    scrub = { screenX ->
        onIntent(EditorIntent.SetPlayhead(geometry.usFor(geometry.contentPxFor(screenX))))
    },
    scroll = { deltaPx ->
        setScrollPx(geometry.scrollClampedTo(geometry.visibleStartPx - deltaPx))
    },
    zoom = { anchorX, factor ->
        // Zooming about the gesture's centroid, so the frame under the fingers stays put.
        val zoomed = geometry.zoomedAround(
            anchorScreenX = anchorX,
            newZoom = TimelineZoom(geometry.zoom.pixelsPerSecond * factor).clamped(),
        )
        setZoomPxPerSecond(zoomed.zoom.pixelsPerSecond)
        setScrollPx(zoomed.scrollPx)
    },
    tap = { screenX -> onTimelineTap(screenX, geometry, onIntent) },
)

/**
 * What a tap means, in one place.
 *
 * A tap on a clip selects it AND moves the playhead, which is what a timeline does. A tap on an edge
 * is a trim handle in FR-2.1 and trimming is not this phase, so for now it moves the playhead — the
 * behaviour the user would see anyway. A tap on empty space clears the selection.
 */
internal fun onTimelineTap(
    screenX: Float,
    geometry: TimelineGeometry,
    onIntent: (EditorIntent) -> Unit,
) {
    when (val hit = geometry.hitTest(screenX)) {
        is TimelineHit.Body -> {
            onIntent(EditorIntent.SelectClip(hit.clipId))
            onIntent(EditorIntent.SetPlayhead(geometry.usFor(geometry.contentPxFor(screenX))))
        }
        is TimelineHit.Edge ->
            onIntent(EditorIntent.SetPlayhead(geometry.usFor(geometry.contentPxFor(screenX))))
        TimelineHit.None -> onIntent(EditorIntent.ClearSelection)
    }
}

/**
 * The three detectors, chained in priority order.
 *
 * The keys are the geometry's zoom and scroll rather than the whole geometry: a pan or a pinch must
 * restart the detector with the CURRENT viewport, or a drag after a pinch would scroll from the
 * position the pinch started at.
 */
internal fun Modifier.timelineGestures(
    geometry: TimelineGeometry,
    rulerHeightPx: Float,
    actions: GestureActions,
): Modifier = this
    .pointerInput(geometry.zoom, geometry.visibleStartPx, rulerHeightPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.y > rulerHeightPx) return@awaitEachGesture
            down.consume()
            actions.scrub(down.position.x)

            // One event at a time until the finger lifts, written as a condition rather than a
            // `while (true)` with breaks, so the exit is in a single place.
            var pointer = nextPointer(down)
            while (pointer != null && pointer.pressed) {
                pointer.consume()
                actions.scrub(pointer.position.x)
                pointer = nextPointer(down)
            }
        }
    }
    .pointerInput(geometry.zoom, geometry.visibleStartPx) {
        detectTransformGestures { centroid, pan, gestureZoom, _ ->
            if (gestureZoom == 1f) actions.scroll(pan.x) else actions.zoom(centroid.x, gestureZoom)
        }
    }
    .pointerInput(geometry, rulerHeightPx) {
        detectTapGestures { offset ->
            if (offset.y > rulerHeightPx) actions.tap(offset.x)
        }
    }

/** The next event for the pointer that started the gesture, or null when it is gone. */
private suspend fun AwaitPointerEventScope.nextPointer(
    down: PointerInputChange,
): PointerInputChange? = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
