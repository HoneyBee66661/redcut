package com.redcut.feature.editor.timeline

import com.google.common.truth.Truth.assertThat
import com.redcut.core.common.timeline.ClipTiming
import com.redcut.core.common.timeline.TimelineGeometry
import com.redcut.core.common.timeline.TimelineZoom
import com.redcut.core.common.timeline.spansOf
import com.redcut.feature.editor.EditorIntent
import org.junit.Test

/**
 * What a tap on the timeline means (device pass, second round).
 *
 * The rule is stateful, and that is the whole reason it is tested here rather than left to the device:
 * **the first tap on a clip selects it; a tap on the already selected clip seeks**. Getting it wrong is
 * not a crash — it is an editor where selecting a clip also moves the playhead somewhere the user did not
 * ask for, which is the kind of thing that reads as "this app is twitchy" rather than as a bug report.
 *
 * `onTimelineTap` is a plain function over the geometry and a callback, so it can be tested with no
 * Compose runtime and no finger: the intents it emits ARE the behaviour.
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

    private fun intentsFor(screenX: Float, selectedClipId: String?): List<EditorIntent> {
        val sent = mutableListOf<EditorIntent>()
        onTimelineTap(screenX, geometry(), sent::add, selectedClipId)
        return sent
    }

    @Test
    fun `the first tap on an unselected clip selects it, and does not move the playhead`() {
        // 30 px is the middle of clip-0, which is a body at this width (a third per side reaches 20 px).
        val intents = intentsFor(30f, selectedClipId = null)

        assertThat(intents).containsExactly(EditorIntent.SelectClip("clip-0"))
    }

    @Test
    fun `a tap on the already selected clip seeks to that point`() {
        val intents = intentsFor(30f, selectedClipId = "clip-0")

        assertThat(intents).containsExactly(EditorIntent.SetPlayhead(500_000L))
    }

    @Test
    fun `tapping a DIFFERENT clip selects it instead of seeking in it`() {
        // The state is per clip, not "has the user tapped anything yet": tapping the other clip is a
        // selection, and the playhead stays where it was.
        val intents = intentsFor(90f, selectedClipId = "clip-0")

        assertThat(intents).containsExactly(EditorIntent.SelectClip("clip-1"))
    }

    @Test
    fun `a tap past the last clip clears the selection`() {
        val intents = intentsFor(380f, selectedClipId = "clip-0")

        assertThat(intents).containsExactly(EditorIntent.ClearSelection)
    }

    @Test
    fun `a tap on an edge seeks, whichever clip is selected`() {
        // 5 px is inside clip-0's left edge zone (a third of 60 px is 20). The trim detector hands a
        // no-movement edge press here, and an edge is a position, not a selection.
        assertThat(intentsFor(5f, selectedClipId = null))
            .containsExactly(EditorIntent.SetPlayhead(83_333L))
        assertThat(intentsFor(5f, selectedClipId = "clip-0"))
            .containsExactly(EditorIntent.SetPlayhead(83_333L))
    }
}
