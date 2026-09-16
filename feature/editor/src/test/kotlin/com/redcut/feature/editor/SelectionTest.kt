package com.redcut.feature.editor

import com.google.common.truth.Truth.assertThat
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
 * What the editor has selected (spec §7.2, UI revision 2 §WS E / Task E1).
 *
 * Its own class rather than three more cases inside `EditorViewModelTest`, which is already the largest
 * test class in the module: the selection rule is a subject of its own — one behaviour read four ways
 * (`None`, `Clip`, `Text`, `Track`) — and the ViewModel's class-size finding is the honest signal that the
 * two subjects had grown into one file.
 *
 * The harness is the same three lines `EditorViewModelTest` needs, for the same reason: the import runs on
 * `viewModelScope` (Main) and on the injected IO dispatcher, and `runTest(dispatcher)` is what puts the
 * test's own scheduler in charge of both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `tapping a lane selects the track`() = runTest(dispatcher) {
        // §WS E / Task E1: the lane selection the tap's TimelineHit.Track arm emits. Asserted on the
        // ViewModel rather than only on the gesture because the stale-id guard and the reconciliation both
        // live on this side of the boundary.
        val model = viewModel(RecordingReader(listOf(video())))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        val trackId = model.state.value.document.tracks.single().id

        model.onIntent(EditorIntent.SelectTrack(trackId))

        assertThat(model.state.value.selection).isEqualTo(Selection.Track(trackId))
    }

    @Test
    fun `a selection for a track the document does not have is ignored`() = runTest(dispatcher) {
        // The lane's half of the clip rule: the id comes from a tap resolved against a frame the user saw,
        // so a lane the document does not hold must not become the selection — the toolbar would then offer
        // track tools for a lane that is not on screen.
        val model = viewModel(RecordingReader(listOf(video())))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        model.onIntent(EditorIntent.ClearSelection)

        model.onIntent(EditorIntent.SelectTrack("track-that-never-existed"))

        assertThat(model.state.value.selection).isEqualTo(Selection.None)
    }

    @Test
    fun `selecting a lane replaces a clip selection rather than adding to it`() = runTest(
        dispatcher,
    ) {
        // `Selection` is what the user is working with, not a list of things, so the four variants replace
        // each other. A state that kept both ids would have to pick a winner at every read, and the
        // inspector and the toolbar would disagree about which one that is.
        val model = viewModel(RecordingReader(listOf(video())))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        val clipId = model.state.value.document.clips.single().id
        val trackId = model.state.value.document.tracks.single().id
        model.onIntent(EditorIntent.SelectClip(clipId))

        model.onIntent(EditorIntent.SelectTrack(trackId))

        assertThat(model.state.value.selection).isEqualTo(Selection.Track(trackId))
        assertThat(model.state.value.selection.clipIdOrNull).isNull()
    }

    @Test
    fun `a lane selection is re-derived against the document's tracks, not only its clips`() {
        // The rule publish() applies on every document change, read as a pure function so it needs no
        // document to go stale: a lane the list does not hold drops the selection, and a clip the list does
        // not hold still does too — the two halves the ViewModel passes in together. No command removes a
        // lane yet, which is exactly why the rule is pinned here rather than left to the first one that does.
        val clipIds = listOf("clip-0")
        val trackIds = listOf("video")

        assertThat(Selection.Track("video").reconciledWith(clipIds, trackIds))
            .isEqualTo(Selection.Track("video"))
        assertThat(Selection.Track("audio").reconciledWith(clipIds, trackIds))
            .isEqualTo(Selection.None)
        assertThat(Selection.Clip("clip-0").reconciledWith(clipIds, trackIds))
            .isEqualTo(Selection.Clip("clip-0"))
        assertThat(Selection.Clip("clip-gone").reconciledWith(clipIds, trackIds))
            .isEqualTo(Selection.None)
    }
}
