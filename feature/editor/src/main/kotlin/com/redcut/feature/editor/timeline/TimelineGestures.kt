package com.redcut.feature.editor.timeline

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
 * 2. **Trim** (FR-2.1) — a press inside a clip's EDGE ZONE, below the ruler.
 * 3. **Reorder** (FR-2.7) — a LONG PRESS on a clip body, then a drag.
 * 4. **Pan / zoom** — a drag inside the clip area scrolls it, and a pinch zooms.
 * 5. **Tap** — seeks the playhead to the point touched, and selects the clip under it; empty space past
 *    the clips clears the selection instead.
 *
 * Two gestures want the track's one finger — scrolling it, and seeking in it — and the split is by
 * MOVEMENT, not by which detector ran first: **a tap seeks, a drag scrolls**. That is why the trim's edge
 * zone matters so much. It used to be capped at half a clip's width, so on any clip narrower than twice
 * the 48 dp touch target the two edge zones met in the middle and the whole clip trimmed on any drag at
 * all — the bug the first device pass found, where dragging to scroll shrank the user's clip instead.
 * A third per side leaves a body that scrolls and seeks like any other part of the track.
 *
 * The long press for reorder stays, for the reason it always had: a plain drag inside a clip is already
 * spoken for, and two gestures cannot own the same finger.
 *
 * Getting it wrong is not a crash: it is a timeline where a drag trims instead of scrolling, or where a
 * tap does nothing. That arbitration is BEHAVIOUR, and behaviour here is verified by a device pass
 * (Phase 1's exit criterion), not by CI — this host has no Android runtime, and no test can press a
 * finger on a Canvas. What CI proves is that this compiles and that the arithmetic it delegates to is
 * tested.
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
    val zoom: (factor: Float) -> Unit,
    val tap: (screenX: Float) -> Unit,
    val trim: TrimGestures,
    val reorder: ReorderGestures,
)

/**
 * What a tap means, in one place.
 *
 * ### The rule, and why it is stateful (device pass, second round)
 *
 * **The first tap on a clip selects it; a tap on the ALREADY selected clip seeks to that point.** The
 * user's model, verbatim: *"tap pertama di body timeline = select jika unselected; tap kedua atau kondisi
 * selected pindahin playhead ke touch point"*. The reason it is worth a state rather than a single
 * behaviour: the two intents are different acts — one says "this is the clip I am working on", the other
 * says "look here" — and doing both on every tap means a user who only wanted to select has also moved
 * the playhead somewhere they did not ask for.
 *
 * A tap on an edge seeks (the trim detector passes a no-movement edge press here), and a tap past the
 * clips clears the selection. [selectedClipId] is what makes the body branch stateful.
 */
internal fun onTimelineTap(
    screenX: Float,
    geometry: TimelineGeometry,
    onIntent: (EditorIntent) -> Unit,
    selectedClipId: String? = null,
) {
    when (val hit = geometry.hitTest(screenX)) {
        is TimelineHit.Body -> {
            if (hit.clipId == selectedClipId) {
                onIntent(EditorIntent.SetPlayhead(geometry.usFor(geometry.contentPxFor(screenX))))
            } else {
                onIntent(EditorIntent.SelectClip(hit.clipId))
            }
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
    playheadUs: Long,
    setZoomPxPerSecond: (Float) -> Unit,
    reorder: ReorderGestures,
    selectedClipId: String? = null,
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
            // A drag on the tracks MOVES THE PLAYHEAD (UI revision 1): the line is fixed at the centre and
            // the content follows the finger, so "scroll the viewport" and "seek" are one gesture. Dragging
            // right brings earlier material under the line, which is why the delta is subtracted.
            val movedPx = geometry.pxFor(playheadUs) - deltaPx
            onIntent(
                EditorIntent.SetPlayhead(
                    geometry.usFor(movedPx.coerceIn(0f, geometry.totalWidthPx)),
                ),
            )
        },
        zoom = { factor ->
            // The anchor is ignored now, and that is the model rather than a shortcut: with the playhead
            // fixed at the centre, a zoom necessarily happens ABOUT the playhead — there is no other point
            // that could stay still, because the playhead is what the scroll offset is derived from.
            setZoomPxPerSecond(
                TimelineZoom(geometry.zoom.pixelsPerSecond * factor).clamped().pixelsPerSecond,
            )
        },
        tap = { screenX -> onTimelineTap(screenX, geometry, onIntent, selectedClipId) },
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
 * The five detectors, chained in priority order.
 *
 * ### Why the keys changed with the revision (and why that is not a detail)
 *
 * The keys used to be the geometry's zoom and scroll, on the reasoning that a gesture must restart with
 * the CURRENT viewport. Under the revision that reasoning inverts: the scroll is DERIVED from the playhead,
 * so it changes on every playhead move — which means a drag that seeks would restart its own detector after
 * its first pixel and die. A key that changes cancels the gesture in flight, so the fix is to key the
 * detectors on things a gesture cannot change ([rulerHeightPx], and nothing else) and to read the live
 * mapping through [rememberUpdatedState] instead. The callbacks then always see the current geometry, and
 * the detector stays alive for the whole drag.
 *
 * The ORDER is the arbitration described at the top of the file: ruler, trim, reorder, pan/zoom, tap.
 */
@Composable
internal fun Modifier.timelineGestures(
    geometry: TimelineGeometry,
    rulerHeightPx: Float,
    actions: TimelineGestures,
): Modifier {
    val currentGeometry by rememberUpdatedState(geometry)
    val currentActions by rememberUpdatedState(actions)

    return this
        .pointerInput(rulerHeightPx) {
            awaitEachGesture {
                val callbacks = currentActions
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.position.y > rulerHeightPx) return@awaitEachGesture
                down.consume()
                callbacks.scrub(down.position.x)

                // One event at a time until the finger lifts, written as a condition rather than a
                // `while (true)` with breaks, so the exit is in a single place.
                var pointer = nextPointer(down)
                while (pointer != null && pointer.pressed) {
                    pointer.consume()
                    callbacks.scrub(pointer.position.x)
                    pointer = nextPointer(down)
                }
            }
        }
        .pointerInput(rulerHeightPx) {
            awaitEachGesture {
                trimGesture(currentGeometry, rulerHeightPx, currentActions, currentActions.tap)
            }
        }
        .pointerInput(rulerHeightPx) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val geometryNow = currentGeometry
                    if (offset.y <= rulerHeightPx) return@detectDragGesturesAfterLongPress
                    val hit = geometryNow.hitTest(offset.x)
                    val clipId = (hit as? TimelineHit.Body)?.clipId
                        ?: return@detectDragGesturesAfterLongPress
                    currentActions.reorder.start(clipId, offset.x)
                },
                onDrag = { change, _ -> currentActions.reorder.update(change.position.x) },
                onDragEnd = { currentActions.reorder.end() },
                onDragCancel = { currentActions.reorder.cancel() },
            )
        }
        .pointerInput(Unit) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                // A one-finger drag over the tracks seeks (the tracks move, the line does not); a pinch zooms
                // about the playhead, which is the only point that can stay still under this model.
                if (gestureZoom == 1f) {
                    currentActions.scroll(pan.x)
                } else {
                    currentActions.zoom(gestureZoom)
                }
            }
        }
        .pointerInput(rulerHeightPx) {
            detectTapGestures { offset ->
                if (offset.y > rulerHeightPx) currentActions.tap(offset.x)
            }
        }
}

/** The next event for the pointer that started the gesture, or null when it is gone. */
private suspend fun AwaitPointerEventScope.nextPointer(
    down: PointerInputChange,
): PointerInputChange? = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
