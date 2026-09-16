package com.redcut.feature.editor

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.CutTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The lane's tools, through the ViewModel (UI revision 2 §WS E / Tasks E2-E3).
 *
 * What this covers is the path a tap takes: `EditorIntent.MergeTrack` → `MergeTrackClips` → the document,
 * plus the one thing a user notices about it — that merging a lane is ONE undo entry, not one per clip
 * that joined. The command's own arithmetic is `:domain:document`'s (`MergeTrackClipsTest`, the pure tier);
 * what lives here is the wiring between an intent and that arithmetic.
 *
 * The lane comes from a SPLIT rather than from a hand-built document, and that is the point: a split
 * produces two halves that are source-contiguous by construction, which is exactly the pair FR-2.4 allows
 * to merge. A fixture would have had to be written to look like the output of the very command this is
 * meant to reach, and the test would then prove that two hand-written clips match.
 *
 * NOT covered here, because no JVM test on this host can see it: that the toolbar actually DRAWS the lane
 * strip when a lane is selected (`StageTools`' own `if`). `:feature:editor` compiles and runs only in CI,
 * and Compose layout is a device fact — the row itself wants the device pass that closes WS E.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackToolsTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `merging the selected lane fuses the two halves a split made`() = runTest(dispatcher) {
        val model = viewModel(RecordingReader(listOf(video(durationUs = 4_000_000L))))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        model.onIntent(EditorIntent.SetPlayhead(2_000_000L))
        model.onIntent(model.cutIntent(CutTool.SPLIT))
        val trackId = model.state.value.document.tracks.single().id
        assertThat(model.state.value.document.clips).hasSize(2)

        model.onIntent(EditorIntent.SelectTrack(trackId))
        model.onIntent(EditorIntent.MergeTrack(trackId))

        assertThat(model.state.value.document.clips).hasSize(1)
        // The fused clip keeps the FIRST half's identity and covers both halves' source range — the rule
        // the domain's own test pins, asserted again here because this is the path the tap takes.
        val merged = model.state.value.document.clips.single()
        assertThat(merged.sourceInUs).isEqualTo(0L)
        assertThat(merged.sourceOutUs).isEqualTo(4_000_000L)
    }

    @Test
    fun `merging a lane is one undo entry, not one per clip`() = runTest(dispatcher) {
        // The reason a lane-level command exists rather than a loop of MergeClips at the call site: a user
        // who merged a lane of five clips should press Undo once. A loop would leave four more entries, and
        // the undo button would appear to work while the timeline barely changed.
        val model = viewModel(RecordingReader(listOf(video(durationUs = 4_000_000L))))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        model.onIntent(EditorIntent.SetPlayhead(2_000_000L))
        model.onIntent(model.cutIntent(CutTool.SPLIT))
        val trackId = model.state.value.document.tracks.single().id
        model.onIntent(EditorIntent.SelectTrack(trackId))

        model.onIntent(EditorIntent.MergeTrack(trackId))
        assertThat(model.state.value.document.clips).hasSize(1)

        model.onIntent(EditorIntent.Undo)

        assertThat(model.state.value.document.clips).hasSize(2)
        assertThat(model.state.value.selection).isEqualTo(Selection.Track(trackId))
    }

    @Test
    fun `merging a lane the document does not have changes nothing`() = runTest(dispatcher) {
        // The stale-id window the lane strip's own reason line exists for: the selection can be cleared, or
        // the lane can be gone, between the frame that drew the button and the tap. A no-op here is the
        // command answering an unknown lane with the same document, so nothing is recorded.
        val model = viewModel(RecordingReader(listOf(video(durationUs = 4_000_000L))))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        val before = model.state.value.document

        model.onIntent(EditorIntent.MergeTrack("track-that-never-existed"))

        assertThat(model.state.value.document).isSameInstanceAs(before)
    }
}
