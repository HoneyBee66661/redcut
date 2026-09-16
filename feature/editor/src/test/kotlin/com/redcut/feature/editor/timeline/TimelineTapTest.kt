package com.redcut.feature.editor.timeline

import com.google.common.truth.Truth.assertThat
import com.redcut.core.common.timeline.ClipTiming
import com.redcut.core.common.timeline.LaneSpans
import com.redcut.core.common.timeline.TimelineGeometry
import com.redcut.core.common.timeline.TimelineZoom
import com.redcut.core.common.timeline.spansOf
import com.redcut.feature.editor.EditorIntent
import org.junit.Test

/**
 * What a tap on the timeline means (device pass, third round).
 *
 * The rule is a TOGGLE — **tap selects, tap again deselects** — and it is tested here rather than left to
 * the device because the two ways to get it wrong are both quiet: a toggle that never deselects leaves the
 * user unable to tell the editor "nothing is selected", and one that toggles on the wrong clip means the
 * inspector silently switches documents under them.
 *
 * The rule only exists in this shape because the playhead is now FIXED: the previous round's tap-seek is
 * gone, since scrolling is what moves the playhead, and a tap that does not seek is a tap whose only job is
 * selection.
 *
 * `onTimelineTap` is a plain function over the geometry and a callback, so it can be tested with no Compose
 * runtime and no finger: the intents it emits ARE the behaviour.
 *
 * The y is the LANE (schema v3): a tap resolves which track's band it landed in before it reads x, so the
 * same x can mean two different clips — or none, below the last track. That is why every case here passes
 * a y, and why the flat geometry they share draws its one lane over `0..TRACK_HEIGHT_DP`.
 */
class TimelineTapTest {

    private val oneSecond = 1_000_000L

    /** Two one-second clips at 60 px/s: clip-0 occupies x 0..60, clip-1 x 60..120. */
    private fun geometry(): TimelineGeometry = TimelineGeometry(
        viewportWidthPx = 400f,
        spans = spansOf(listOf(ClipTiming("clip-0", oneSecond), ClipTiming("clip-1", oneSecond))),
        zoom = TimelineZoom(60f),
        scrollPx = 0f,
        density = 1f,
    )

    /**
     * The LANE reading (§WS E / Task E1): one VIDEO lane named `video`, holding one one-second clip.
     *
     * The flat geometry above has no track id to answer with, so `hitTest(x, y)` on its empty space can
     * only say `None` — which is why the lane-selection cases below need a document-shaped geometry with a
     * lane to name.
     */
    private fun lanesGeometry(): TimelineGeometry = TimelineGeometry(
        viewportWidthPx = 400f,
        lanes = listOf(LaneSpans("video", spansOf(listOf(ClipTiming("clip-0", oneSecond))))),
        zoom = TimelineZoom(60f),
        scrollPx = 0f,
        density = 1f,
    )

    private fun intentsFor(
        screenX: Float,
        selectedClipId: String? = null,
        selectedTrackId: String? = null,
        screenY: Float = LANE_MIDDLE_Y,
        geometry: TimelineGeometry = geometry(),
    ): List<EditorIntent> {
        val sent = mutableListOf<EditorIntent>()
        onTimelineTap(screenX, screenY, geometry, sent::add, selectedClipId, selectedTrackId)
        return sent
    }

    @Test
    fun `a tap on an unselected clip selects it`() {
        // 30 px is the middle of clip-0, which is a body at this width (a third per side reaches 20 px).
        assertThat(intentsFor(30f, selectedClipId = null))
            .containsExactly(EditorIntent.SelectClip("clip-0"))
    }

    @Test
    fun `a tap on the selected clip deselects it, and touches nothing else`() {
        // The toggle, and the reason the timeline needs one: without it there is no way to tell the editor
        // that nothing is selected, so the inspector keeps editing a clip the user has moved on from.
        assertThat(intentsFor(30f, selectedClipId = "clip-0"))
            .containsExactly(EditorIntent.ClearSelection)
    }

    @Test
    fun `tapping a different clip selects it rather than deselecting anything`() {
        // The decision is per clip, not "is something selected": tapping clip-1 while clip-0 is selected is a
        // selection, not a toggle off.
        assertThat(intentsFor(90f, selectedClipId = "clip-0"))
            .containsExactly(EditorIntent.SelectClip("clip-1"))
    }

    @Test
    fun `a tap past the last clip clears the selection`() {
        assertThat(intentsFor(380f, selectedClipId = "clip-0"))
            .containsExactly(EditorIntent.ClearSelection)
    }

    @Test
    fun `an edge taps like a body, so the toggle works from anywhere on a clip`() {
        // 5 px is inside clip-0's left edge zone (a third of 60 px is 20). The trim detector hands a
        // no-movement edge press here, and a tap that selects from the body but does nothing from the edge
        // is a dead strip the width of the edge target.
        assertThat(intentsFor(5f, selectedClipId = null))
            .containsExactly(EditorIntent.SelectClip("clip-0"))
        assertThat(intentsFor(5f, selectedClipId = "clip-0"))
            .containsExactly(EditorIntent.ClearSelection)
    }

    @Test
    fun `a tap below the last lane hits nothing, however many clips that x crosses`() {
        // 30 px is the middle of clip-0, and this tap is the same x one pixel below the track. The lane is
        // resolved from y FIRST, so there is no track there to hold a clip: the selection clears instead of
        // switching to a clip the finger is not on.
        assertThat(intentsFor(30f, selectedClipId = "clip-0", screenY = LANE_BELOW_Y))
            .containsExactly(EditorIntent.ClearSelection)
    }

    // --- The lane background selects the TRACK (§WS E / Task E1) -------------

    @Test
    fun `tapping a lane's background selects the track it names`() {
        // x 200 is past the lane's only clip, so the hit test lands on the LANE and answers with its track
        // id. The user's decision was that this selects the track — the selection the track tools will read
        // — rather than clearing, which is what it did while there was nothing for a lane to mean.
        assertThat(intentsFor(200f, geometry = lanesGeometry()))
            .containsExactly(EditorIntent.SelectTrack("video"))
    }

    @Test
    fun `tapping the lane already selected deselects it`() {
        // The same toggle a clip keeps, read in the lane's terms. Without it a lane, once selected, could
        // never be released by tapping it again — and the clip case below is what stops the two rules from
        // collapsing into one: the lane is resolved from y, but a clip standing on it still wins the x.
        assertThat(intentsFor(200f, selectedTrackId = "video", geometry = lanesGeometry()))
            .containsExactly(EditorIntent.ClearSelection)
    }

    @Test
    fun `a clip still wins the tap over the lane it stands on`() {
        // The priority: 30 px is inside clip-0 of the same lane, so the answer is the CLIP. A lane
        // selection here would leave the inspector unable to open a clip's rows by tapping it.
        assertThat(intentsFor(30f, selectedTrackId = "video", geometry = lanesGeometry()))
            .containsExactly(EditorIntent.SelectClip("clip-0"))
    }
}

/** The middle of the one lane the flat reading draws: half a 56 dp track at density 1. */
private const val LANE_MIDDLE_Y = TimelineGeometry.TRACK_HEIGHT_DP / 2f

/** One pixel below that lane: a y in no band at all, which is what the area past the last track is. */
private const val LANE_BELOW_Y = TimelineGeometry.TRACK_HEIGHT_DP + 1f
