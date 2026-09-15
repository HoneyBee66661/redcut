package com.redcut.feature.editor

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.FrameStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The player-to-timeline direction of FR-2.10: the position the preview publishes, and what the
 * editor does with it.
 *
 * Its mirror image — a tap and a frame step moving the picture — is pinned beside it in the same
 * class, because the pair is the rule: the playback path deliberately does not seek, and the user's
 * path deliberately does. These three cases are their own class because following the player is one
 * responsibility, and because it is the one that needs a renderer whose position the test drives;
 * nothing in [EditorViewModelTest] asks for that.
 *
 * The fakes they are wired with — the factory `viewModel(...)`, the recording renderer, the reader and
 * `video(...)` — live in `EditorTestFixtures`, together with the `dispatcher` they all run on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorPlaybackTest {

    // The import runs on TWO dispatchers that must be the same scheduler: `viewModelScope`
    // is Main, and the import body is the injected IO dispatcher. `runTest(dispatcher)`
    // below is what puts the test's scheduler in charge of both — without it the coroutine
    // would be queued on a scheduler nobody advances and the test would hang.
    @Before
    fun setUp() {
        // The import launches on `viewModelScope`, which uses Dispatchers.Main. Without this
        // the test would either hang or depend on a real main looper.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Playback moves the playhead (FR-2.10) ------------------------------

    /**
     * One four-second clip, imported, plus the renderer that can report a position.
     *
     * Four seconds because that is the document's length, and the end of the document is what two of
     * these cases are about. The seek log starts empty because every case here is about what the
     * PLAYBACK path does to it — the import publishes, and the playhead is re-derived there, so a log
     * that began at construction would carry that seek into every assertion.
     */
    private fun TestScope.playingPreview(): Pair<EditorViewModel, RecordingRenderer> {
        val renderer = RecordingRenderer()
        val model = viewModel(
            RecordingReader(listOf(video(durationUs = 4_000_000L))),
            renderer = renderer,
        )
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        renderer.seeks.clear()
        return model to renderer
    }

    @Test
    fun `a position the preview publishes while playing moves the playhead without a seek`() =
        runTest(dispatcher) {
            val (model, renderer) = playingPreview()

            // The picture reaches two seconds. THIS is the defect the device pass reported: before the
            // renderer carried a position, nothing brought that number back to the timeline, so the frame
            // moved and the playhead stood still — "harusnya ada link antara clip dan player".
            renderer.playingAt(2_000_000L)
            advanceUntilIdle()

            assertThat(model.state.value.playheadUs).isEqualTo(2_000_000L)
            // The same number reached the state the UI draws, together with the playing flag it came
            // with: one snapshot of the player, not two readings of it.
            assertThat(model.state.value.playback)
                .isEqualTo(PlaybackState(isPlaying = true, positionUs = 2_000_000L))
            // ...and NOTHING was seeked. A seek back to the position the player has just reported fights
            // the player that reported it, thirty times a second: the player is already where it says it
            // is, and the playhead is the one that catches up. This assertion is the whole reason the
            // follow path is not `movePlayhead`.
            assertThat(renderer.seeks).isEmpty()
        }

    @Test
    fun `a tap and a frame step still seek the preview and still move the playhead`() =
        runTest(dispatcher) {
            val (model, renderer) = playingPreview()

            // The other direction, unchanged by FR-2.10 and pinned here so it stays that way: the playback
            // path deliberately does not seek, and the user's path deliberately does. Folding one into the
            // other — a seek that would stutter, or a playhead that would stop following a tap — is the
            // bug this pair of cases exists to catch.
            model.onIntent(EditorIntent.SetPlayhead(2_000_000L))
            model.onIntent(EditorIntent.StepPlayhead(FrameStep.FORWARD))
            advanceUntilIdle()

            assertThat(renderer.seeks.first()).isEqualTo(2_000_000L)
            // The step's own length is the frame length of the clip under the playhead (FR-2.9), so the
            // last seek is compared against the state that step produced rather than against a literal.
            assertThat(renderer.seeks.last()).isEqualTo(model.state.value.playheadUs)
            assertThat(model.state.value.playheadUs).isGreaterThan(2_000_000L)
            // Two gestures, two seeks, and none from the follow loop running alongside them.
            assertThat(renderer.seeks).hasSize(2)
        }

    @Test
    fun `a position past the end of the document clamps the playhead and the snapshot`() =
        runTest(dispatcher) {
            val (model, renderer) = playingPreview()

            // A player knows the composition's length, not the timeline the ruler draws, and its last
            // reported position can sit a frame past the end of the document. Drawn unclamped, the
            // playhead would leave the timeline at its own end — invariant 5 of §7.2, which is why this
            // path clamps exactly as the user-driven one does.
            renderer.playingAt(9_000_000L)
            advanceUntilIdle()

            assertThat(model.state.value.playheadUs).isEqualTo(4_000_000L)
            // The snapshot is clamped with it, so the two numbers in the state cannot disagree about
            // where "here" is — and neither of them can point off the end of the document.
            assertThat(model.state.value.playback.positionUs).isEqualTo(4_000_000L)
            // Reaching the end moves the playhead, never the player.
            assertThat(renderer.seeks).isEmpty()
        }
}
