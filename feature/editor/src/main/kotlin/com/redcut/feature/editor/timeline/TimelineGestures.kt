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
import androidx.compose.ui.input.pointer.PointerInputScope
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
    /**
     * [screenY] is in LANE space — the pointer's own y with the ruler strip taken off — which is
     * the space the geometry's lane bands and `drawTimeline`'s bands are both measured in.
     */
    val tap: (screenX: Float, screenY: Float) -> Unit,
    val trim: TrimGestures,
    val reorder: ReorderGestures,
)

/**
 * What a tap means, in one place.
 *
 * ### The rule, and why it became a TOGGLE (device pass, third round)
 *
 * **A tap on a clip selects it; a tap on the already selected clip deselects it.** The user's model,
 * verbatim: *"touch on body = select clip body. touch 2 = unselect it"*.
 *
 * This replaces the seek-on-second-tap rule of the previous round, and the replacement is the point of the
 * fixed playhead: a tap no longer has to move the playhead, because SCROLLING does that — the tracks move
 * under the line. With seeking gone from the tap, all the tap has left to do is change what is selected,
 * and a control that only changes selection is a toggle.
 *
 * An edge taps the same way as the body. Empty space past the clips clears the selection.
 *
 * A lane's BACKGROUND selects that LANE (§WS E / Task E1), which is the one reading that is not about a
 * clip: the tap still toggles, so a second tap on the lane already selected clears. Which lane was touched
 * is the hit test's answer ([TimelineHit.Track] carries the track id), and what the selection then MEANS
 * is the ViewModel's.
 *
 * ### The y picks the LANE (schema v3)
 *
 * [screenY] resolves which track's band was touched BEFORE the x is read, so an x that crosses a clip in
 * one lane cannot select a clip in another. A y in no lane at all — below the last track — is also empty
 * space, and clears the selection for the same reason the area past the clips does: there is nothing
 * there to select.
 */
internal fun onTimelineTap(
    screenX: Float,
    screenY: Float,
    geometry: TimelineGeometry,
    onIntent: (EditorIntent) -> Unit,
    selectedClipId: String? = null,
    selectedTrackId: String? = null,
    textLane: TextLane? = null,
    selectedTextId: String? = null,
) {
    // The caption lane is BELOW the media lanes, so a y in its band is not in any clip lane's — and the
    // band answers for itself: a tap on a caption selects it, a tap on the band's empty stretch clears,
    // the same toggle the clips keep. Routed here rather than by a second detector so the band has ONE
    // place in the priority order, under the trim.
    if (textLane != null && screenY >= textLane.topPx && screenY <= textLane.bottomPx) {
        onTextLaneTap(
            screenX = screenX,
            lane = textLane,
            geometry = geometry,
            onIntent = onIntent,
            selectedEffectId = selectedTextId,
        )
        return
    }
    val hit = geometry.hitTest(screenX, screenY)
    // A lane's BACKGROUND is the track itself: `hitTest` answers TimelineHit.Track where no clip of the
    // lane was touched, and the user's decision was that such a tap SELECTS that track — which is what
    // the lane selection exists for (UI revision 2, §WS E). Handled before the clip reading rather than
    // folded into it because a track hit carries a track id, not a clip id, and the two ids are not
    // interchangeable in either direction.
    if (hit is TimelineHit.Track) {
        val intent = if (hit.trackId == selectedTrackId) {
            // The toggle rule UI revision 1 kept for a clip, read in the lane's terms: the lane already
            // selected deselects, so a second tap on the lane body is a way back to nothing selected.
            EditorIntent.ClearSelection
        } else {
            EditorIntent.SelectTrack(hit.trackId)
        }
        onIntent(intent)
        return
    }
    val tapped = when (hit) {
        is TimelineHit.Body -> hit.clipId
        is TimelineHit.Edge -> hit.clipId
        // The track case returned above; nothing else on the timeline names an item to select.
        else -> null
    }

    when (tapped) {
        null -> onIntent(EditorIntent.ClearSelection)
        selectedClipId -> onIntent(EditorIntent.ClearSelection)
        else -> onIntent(EditorIntent.SelectClip(tapped))
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
    onTap: (screenX: Float, screenY: Float) -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (down.position.y <= rulerHeightPx) return
    // The geometry's bands start at the top of the TRACK AREA, which is the pointer's own y once the
    // ruler strip above it is taken off — the same subtraction `drawTimeline` adds back when it places a
    // band at `rulerHeight + topPx`. Without it, touch and pixel would disagree by the ruler's height and
    // the bottom of every lane would be untouchable.
    val hit = geometry.hitTest(down.position.x, down.position.y - rulerHeightPx)
    if (hit !is TimelineHit.Edge) return

    down.consume()
    actions.trim.begin(hit.clipId, hit.side.toClipEdge(), down.position.x)

    var moved = false
    var lastX = down.position.x
    var lastY = down.position.y
    var pointer = nextPointer(down)
    while (pointer != null && pointer.pressed) {
        pointer.consume()
        lastX = pointer.position.x
        lastY = pointer.position.y
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
        onTap(lastX, lastY - rulerHeightPx)
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
    textLane: TextLane? = null,
    selectedClipId: String? = null,
    selectedTrackId: String? = null,
    selectedTextId: String? = null,
): TimelineGestures {
    fun sourceTimeAt(clipId: String, screenX: Float): Long {
        val clip = clipsById[clipId] ?: return 0L
        val span = spansByClip[clipId] ?: return 0L
        val withinClipUs = geometry.usFor(geometry.contentPxFor(screenX)) - span.startUs
        return clip.sourceTimeFor(withinClipUs)
    }

    // The caption edge's value, in TIMELINE time: a caption has no source, so unlike a clip trim there
    // is no source-time crossing here — the finger's position IS the value the command clamps.
    fun captionUsAt(screenX: Float): Long = geometry.usFor(geometry.contentPxFor(screenX))

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
        tap = { screenX, screenY ->
            onTimelineTap(
                screenX = screenX,
                screenY = screenY,
                geometry = geometry,
                onIntent = onIntent,
                selectedClipId = selectedClipId,
                selectedTrackId = selectedTrackId,
                textLane = textLane,
                selectedTextId = selectedTextId,
            )
        },
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
    textLane: TextLane? = null,
    textTrim: TextTrimGestures? = null,
): Modifier {
    val currentGeometry by rememberUpdatedState(geometry)
    val currentActions by rememberUpdatedState(actions)
    val currentTextLane by rememberUpdatedState(textLane)
    val currentTextTrim by rememberUpdatedState(textTrim)

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
        .pointerInput(rulerHeightPx, textLane?.topPx) {
            awaitEachGesture {
                textLaneTrimPress(currentGeometry, rulerHeightPx, currentTextLane, currentTextTrim)
            }
        }
        .pointerInput(rulerHeightPx) {
            reorderDragPress(currentGeometry, rulerHeightPx, currentActions)
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
                if (offset.y > rulerHeightPx) {
                    currentActions.tap(offset.x, offset.y - rulerHeightPx)
                }
            }
        }
}

/**
 * The caption lane's edge press (FR-4.3's card 4), in its own pointer scope.
 *
 * Extracted from [timelineGestures] because the chained detector pushed that composable over
 * detekt's LongMethod limit — and the extraction is a responsibility, not a line count: the lane is
 * the ONE detector that needs the text trim's geometry, so it reads the live lane and trim through
 * the same [rememberUpdatedState] snapshot the other detectors read the actions through.
 */
private suspend fun AwaitPointerEventScope.textLaneTrimPress(
    geometry: TimelineGeometry,
    rulerHeightPx: Float,
    textLane: TextLane?,
    textTrim: TextTrimGestures?,
) {
    // One gesture per [awaitEachGesture], which the CALL SITE opens — the same shape [trimGesture]
    // keeps. The caption lane's edges come after the clips': a y can only be in one band, so the two
    // trims never contend for the same press, and the order keeps the clips' behaviour bit-identical
    // to what it was before the lane existed.
    textLane?.let { lane ->
        val trim = textTrim
        if (trim != null) {
            textTrimGesture(
                geometry = geometry,
                rulerHeightPx = rulerHeightPx,
                lane = lane,
                actions = trim,
            )
        }
    }
}

/**
 * The reorder drag's long-press detector, in its own pointer scope.
 *
 * Extracted from [timelineGestures] for the same reason as [textLaneTrimPress]: the composable's
 * detector chain crossed detekt's LongMethod limit, and the reorder press is the one detector with
 * its own hit-test rule (the y picks the lane, so a long press in lane 2 with lane 1's clips under the
 * same x must not pick one of those up).
 */
private suspend fun PointerInputScope.reorderDragPress(
    geometry: TimelineGeometry,
    rulerHeightPx: Float,
    actions: TimelineGestures,
) {
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            val geometryNow = geometry
            if (offset.y <= rulerHeightPx) return@detectDragGesturesAfterLongPress
            val hit = geometryNow.hitTest(offset.x, offset.y - rulerHeightPx)
            val clipId = (hit as? TimelineHit.Body)?.clipId
                ?: return@detectDragGesturesAfterLongPress
            actions.reorder.start(clipId, offset.x)
        },
        onDrag = { change, _ -> actions.reorder.update(change.position.x) },
        onDragEnd = { actions.reorder.end() },
        onDragCancel = { actions.reorder.cancel() },
    )
}

/**
 * The next event for the pointer that started the gesture, or null when it is gone.
 *
 * Shared with the caption drag on the preview ([com.redcut.feature.editor.TextOverlayLayer]): "follow the
 * finger that went down" is one behaviour, and a second copy of it is a second place for the multi-touch
 * rule (a second finger is not this gesture) to be got wrong.
 */
internal suspend fun AwaitPointerEventScope.nextPointer(
    down: PointerInputChange,
): PointerInputChange? = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
