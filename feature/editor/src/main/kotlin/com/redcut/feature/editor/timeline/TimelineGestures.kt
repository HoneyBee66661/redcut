package com.redcut.feature.editor.timeline

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import com.redcut.core.common.timeline.ClipSpan
import com.redcut.core.common.timeline.TimelineGeometry
import com.redcut.core.common.timeline.TimelineHit
import com.redcut.core.common.timeline.TimelineZoom
import com.redcut.domain.document.Clip
import com.redcut.domain.document.ClipEdge
import com.redcut.domain.document.sourceTimeFor
import com.redcut.feature.editor.EditorIntent
import com.redcut.feature.editor.toClipEdge

/**
 * The timeline's gestures, and the ORDER that decides who wins.
 *
 * A `Modifier` chain runs its pointer handlers in declaration order, so this file's whole job is to
 * state that order once, readably:
 *
 * 1. **Ruler strip** — the playhead's own surface; consumes what it takes.
 * 2. **Trim** (FR-2.1) — a press inside a clip's edge zone, below the ruler.
 * 3. **Reorder** (FR-2.7) — a LONG PRESS on a clip body, then a drag.
 * 4. **Pan / zoom** — the clip area's drag and pinch.
 * 5. **Tap** — select a clip, or clear the selection on empty space.
 *
 * Reorder sits above pan/zoom and needs its long press for exactly that reason: a plain horizontal
 * drag across a clip is how the user SCROLLS the timeline, and two gestures cannot own the same finger.
 * Holding still to pick a clip up is the convention every NLE uses, and it leaves scrolling working
 * everywhere on the track.
 *
 * Getting it wrong is not a crash: it is a timeline where a pinch also scrubs, or where dragging an
 * edge scrolls the clips instead of trimming. That arbitration is BEHAVIOUR, and behaviour here is
 * verified by a device pass (Phase 1's exit criterion), not by CI — this host has no Android
 * runtime, and no test can press a finger on a Canvas. What CI proves is that this compiles and that
 * the arithmetic it delegates to is tested.
 */

/**
 * The four things a trim drag does.
 *
 * Grouped rather than flattened into [TimelineGestures] for two reasons: a constructor with eight
 * lambdas is a smell `detekt` names, and "the trim gesture" is a thing — the four callbacks are only
 * meaningful together.
 */
internal class TrimGestures(
    val begin: (clipId: String, edge: ClipEdge, screenX: Float) -> Unit,
    val update: (clipId: String, screenX: Float) -> Unit,
    val end: () -> Unit,
    val cancel: () -> Unit,
)

/** The reorder drag's four callbacks (FR-2.7), grouped for the same reason [TrimGestures] is. */
internal class ReorderGestures(
    val start: (clipId: String, screenX: Float) -> Unit,
    val update: (screenX: Float) -> Unit,
    val end: () -> Unit,
    val cancel: () -> Unit,
)

/** Everything a gesture can do. One value, so the Canvas call stays a single statement. */
internal class TimelineGestures(
    val scrub: (screenX: Float) -> Unit,
    val scroll: (deltaPx: Float) -> Unit,
    val zoom: (anchorX: Float, factor: Float) -> Unit,
    val tap: (screenX: Float) -> Unit,
    val trim: TrimGestures,
    val reorder: ReorderGestures,
)

/**
 * What a tap means, in one place.
 *
 * A tap on a clip selects it AND moves the playhead, which is what a timeline does. A tap on empty
 * space clears the selection. The [TimelineHit.Edge] branch is the FALLBACK path: the trim detector
 * below normally claims an edge press, and this runs only if it declined (a zero-width edge zone, or
 * a finger that never moved — see [trimGesture] for which of those means what).
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
 * The trim drag (FR-2.1), and the difference between a drag and a tap on an edge.
 *
 * A press in an edge zone opens a trim as a PREVIEW — the document follows the finger, so the clip
 * visibly changes length while the gesture is open. Two endings:
 *
 * * the finger moved past the touch slop: `end()`, which commits the whole drag as one history entry;
 * * the finger did not move: `cancel()`, which rolls the preview back and hands the gesture to the
 *   tap behaviour instead. Without that, tapping an edge would leave a "Trim" entry in the history
 *   that changes nothing — an undo button that appears to be broken.
 *
 * The slop is the platform's own ([androidx.compose.ui.platform.ViewConfiguration.touchSlop]) rather
 * than a number invented here: it is the same distance that separates a tap from a drag everywhere
 * else in Android.
 */
private suspend fun AwaitPointerEventScope.trimGesture(
    geometry: TimelineGeometry,
    rulerHeightPx: Float,
    actions: TimelineGestures,
    onTap: (screenX: Float) -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (down.position.y <= rulerHeightPx) return
    val hit = geometry.hitTest(down.position.x)
    if (hit !is TimelineHit.Edge) return

    down.consume()
    actions.trim.begin(hit.clipId, hit.side.toClipEdge(), down.position.x)

    var moved = false
    var lastX = down.position.x
    var pointer = nextPointer(down)
    while (pointer != null && pointer.pressed) {
        pointer.consume()
        lastX = pointer.position.x
        val travelled = (pointer.position - down.position).getDistance()
        if (!moved && travelled > viewConfiguration.touchSlop) {
            moved = true
        }
        if (moved) actions.trim.update(hit.clipId, lastX)
        pointer = nextPointer(down)
    }

    if (moved) {
        actions.trim.end()
    } else {
        actions.trim.cancel()
        onTap(lastX)
    }
}

/**
 * Builds every gesture callback from the current [geometry], the document's clips and the viewport's
 * setters.
 *
 * Every one has the same shape — ask the geometry a question, then either send an intent or write the
 * viewport — so they live together, and the Canvas above reads as "state, then draw".
 *
 * `sourceTimeAt` is the piece worth reading twice: a trim is the ONE place a screen position has to
 * become a document value, and it has to cross two boundaries to do it. Screen pixels become a
 * timeline position ([TimelineGeometry.contentPxFor] then [usFor]), the clip's start is subtracted to
 * get an offset WITHIN the clip, and the clip's own [sourceTimeFor] turns that offset into the source
 * time the command needs. Skipping the last step is the bug that shows up only with a sped-up or
 * reversed clip — the frame trimmed to, and the frame played back, would differ.
 */
internal fun timelineGestureHandlers(
    geometry: TimelineGeometry,
    onIntent: (EditorIntent) -> Unit,
    clipsById: Map<String, Clip>,
    spansByClip: Map<String, ClipSpan>,
    setScrollPx: (Float) -> Unit,
    setZoomPxPerSecond: (Float) -> Unit,
    reorder: ReorderGestures,
): TimelineGestures {
    fun sourceTimeAt(clipId: String, screenX: Float): Long {
        val clip = clipsById[clipId] ?: return 0L
        val span = spansByClip[clipId] ?: return 0L
        val withinClipUs = geometry.usFor(geometry.contentPxFor(screenX)) - span.startUs
        return clip.sourceTimeFor(withinClipUs)
    }

    return TimelineGestures(
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
        trim = TrimGestures(
            begin = { clipId, edge, screenX ->
                onIntent(EditorIntent.BeginTrim(clipId, edge, sourceTimeAt(clipId, screenX)))
            },
            update = { clipId, screenX ->
                onIntent(EditorIntent.UpdateTrim(sourceTimeAt(clipId, screenX)))
            },
            end = { onIntent(EditorIntent.EndTrim) },
            cancel = { onIntent(EditorIntent.CancelTrim) },
        ),
        reorder = reorder,
    )
}

/**
 * The four detectors, chained in priority order.
 *
 * The keys are the geometry's zoom and scroll rather than the whole geometry: a pan, a pinch or a
 * trim must restart the detector with the CURRENT viewport, or a drag after a zoom would move from
 * the position the zoom started at.
 */
internal fun Modifier.timelineGestures(
    geometry: TimelineGeometry,
    rulerHeightPx: Float,
    actions: TimelineGestures,
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
    .pointerInput(geometry, rulerHeightPx) {
        awaitEachGesture { trimGesture(geometry, rulerHeightPx, actions, actions.tap) }
    }
    .reorderGesture(geometry, rulerHeightPx, actions.reorder)
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

/**
 * Picking a clip up and moving it (FR-2.7).
 *
 * Long-press first, deliberately: see the order note at the top of the file. A plain horizontal drag
 * across a clip body is the SCROLL gesture, so reorder waits for the hold — and the wait is the
 * platform's, not a number invented here.
 *
 * The clip id is resolved here (the only thing this layer knows how to ask), and every position after
 * that is handed to the Canvas, which owns the drag state and the geometry needed to turn a finger
 * into a slot.
 */
private fun Modifier.reorderGesture(
    geometry: TimelineGeometry,
    rulerHeightPx: Float,
    actions: ReorderGestures,
): Modifier = pointerInput(geometry, rulerHeightPx) {
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            if (offset.y <= rulerHeightPx) return@detectDragGesturesAfterLongPress
            val hit = geometry.hitTest(offset.x)
            val clipId = (hit as? TimelineHit.Body)?.clipId
                ?: return@detectDragGesturesAfterLongPress
            actions.start(clipId, offset.x)
        },
        onDrag = { change, _ -> actions.update(change.position.x) },
        onDragEnd = { actions.end() },
        onDragCancel = { actions.cancel() },
    )
}

/** The next event for the pointer that started the gesture, or null when it is gone. */
private suspend fun AwaitPointerEventScope.nextPointer(
    down: PointerInputChange,
): PointerInputChange? = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
