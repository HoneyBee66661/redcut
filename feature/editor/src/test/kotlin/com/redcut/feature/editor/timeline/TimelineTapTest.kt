package com.redcut.feature.editor.timeline

import com.google.common.truth.Truth.assertThat
import com.redcut.core.common.timeline.ClipTiming
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
}
