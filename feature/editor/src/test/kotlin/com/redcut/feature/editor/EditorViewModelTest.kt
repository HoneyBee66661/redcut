package com.redcut.feature.editor

import com.google.common.truth.Truth.assertThat
import com.redcut.core.common.IdSource
import com.redcut.core.common.logging.NoOpRedcutLogger
import com.redcut.core.media.MediaSourceReader
import com.redcut.core.media.SourceReadResult
import com.redcut.domain.document.ImportRejection
import com.redcut.domain.document.ProbedSource
import com.redcut.domain.document.SourceProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The editor's wiring, tested as a plain JVM test.
 *
 * No Robolectric and no Hilt: [EditorViewModel] takes a logger, a media reader, an id source
 * and a dispatcher — all four are plain interfaces, and none of them is an Android API. The
 * reader is the point of this: `:core:media` binds the SAF implementation at the composition
 * root, so the import path's *logic* (what gets added, what gets reported, what one undo
 * removes) is verified here in milliseconds rather than by importing files by hand on a
 * device.
 *
 * What this does NOT prove: that SAF returns URIs, that `MediaExtractor` reports a codec, or
 * that the banner renders. The first two are platform behaviour (Phase 1's exit criterion —
 * a manual pass on a device), the third is a screenshot test's business (Phase 12.2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var idCounter = 0

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

    /** Deterministic ids, so a test can name the exact commands an import produced. */
    private fun ids(): IdSource = IdSource { "id-${idCounter++}" }

    private fun viewModel(reader: MediaSourceReader = RecordingReader()) = EditorViewModel(
        logger = NoOpRedcutLogger,
        sourceReader = reader,
        ids = ids(),
        io = dispatcher,
    )

    private fun video(
        name: String = "clip.mp4",
        uri: String = "content://media/1",
        durationUs: Long = 4_000_000L,
        videoCodec: String = "video/avc",
    ) = SourceReadResult.Read(
        ProbedSource(
            uri = uri,
            displayName = name,
            probe = SourceProbe(
                durationUs = durationUs,
                width = 1920,
                height = 1080,
                frameRate = 30f,
                videoCodec = videoCodec,
                audioCodec = "audio/mp4a-latm",
                hasAudio = true,
            ),
        ),
    )

    private fun unreadable(name: String = "gone.mp4") = SourceReadResult.Unreadable(
        ImportRejection.Unreadable(displayName = name, reason = "Permission denied"),
    )

    /** Returns whatever it was given, in order, and records what it was asked for. */
    private class RecordingReader(
        private val results: List<SourceReadResult> = emptyList(),
    ) : MediaSourceReader {
        var requested: List<String> = emptyList()

        override suspend fun read(uris: List<String>): List<SourceReadResult> {
            requested = uris
            return results
        }
    }

    // --- Existing behaviour: stage and history -----------------------------

    @Test
    fun `the editor starts on Cut with an empty history`() {
        val state = viewModel().state.value

        assertThat(state.stage).isEqualTo(Stage.Cut)
        assertThat(state.document.clips).isEmpty()
        assertThat(state.import).isNull()
        assertThat(state.history).isEqualTo(
            HistoryState.Ready(canUndo = false, canRedo = false, topLabel = null),
        )
    }

    @Test
    fun `selecting a stage changes only the stage`() {
        val model = viewModel()
        val before = model.state.value

        model.onIntent(EditorIntent.SelectStage(Stage.Effect))

        val after = model.state.value
        assertThat(after.stage).isEqualTo(Stage.Effect)
        assertThat(after.document).isEqualTo(before.document)
        assertThat(after.history).isEqualTo(before.history)
    }

    @Test
    fun `switching stages never advances the document revision`() {
        // Spec §7.1: "Stage switching is instant and free ... it does not recompile the
        // document". A stage tap that bumped the revision would trigger a recompile of
        // the whole timeline — the difference between a free switch and a stutter.
        val model = viewModel()
        val revisionBefore = model.state.value.revision

        Stage.entries.forEach { model.onIntent(EditorIntent.SelectStage(it)) }

        assertThat(model.state.value.revision).isEqualTo(revisionBefore)
    }

    @Test
    fun `undo and redo on an empty history change nothing`() {
        val model = viewModel()
        val before = model.state.value

        model.onIntent(EditorIntent.Undo)
        assertThat(model.state.value).isEqualTo(before)

        model.onIntent(EditorIntent.Redo)
        assertThat(model.state.value).isEqualTo(before)
    }

    @Test
    fun `the stage survives an undo`() {
        // The stage is not history: undoing an edit must not also unwind "the user is
        // looking at the Edit stage", which is what would happen if the whole UI state
        // were snapshotted along with the document.
        val model = viewModel()

        model.onIntent(EditorIntent.SelectStage(Stage.Edit))
        model.onIntent(EditorIntent.Undo)

        assertThat(model.state.value.stage).isEqualTo(Stage.Edit)
    }

    // --- Import (FR-1.2, FR-1.3, FR-1.4) -----------------------------------

    @Test
    fun `importing a video appends a source and a clip spanning it`() = runTest(dispatcher) {
        val model = viewModel(RecordingReader(listOf(video(durationUs = 4_000_000L))))

        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()

        val document = model.state.value.document
        assertThat(document.sources).hasSize(1)
        assertThat(document.clips).hasSize(1)
        assertThat(document.clips.single().sourceId).isEqualTo(document.sources.single().id)
        assertThat(document.clips.single().sourceOutUs).isEqualTo(4_000_000L)
        assertThat(document.isRenderable()).isTrue()
        assertThat(model.state.value.import).isEqualTo(
            ImportReport(importedCount = 1, rejected = emptyList()),
        )
    }

    @Test
    fun `several videos are appended in selection order`() = runTest(dispatcher) {
        val model = viewModel(
            RecordingReader(
                listOf(
                    video(name = "first.mp4", uri = "content://media/1", durationUs = 1_000_000L),
                    video(name = "second.mp4", uri = "content://media/2", durationUs = 2_000_000L),
                ),
            ),
        )

        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1", "content://media/2")))
        advanceUntilIdle()

        val document = model.state.value.document
        assertThat(
            document.sources.map {
                it.displayName
            },
        ).containsExactly("first.mp4", "second.mp4").inOrder()
        assertThat(document.durationUs).isEqualTo(3_000_000L)
    }

    @Test
    fun `a whole import is one undo entry`() = runTest(dispatcher) {
        // Two files, four commands (a source and a clip each) — and still ONE press of undo,
        // because that is the gesture the user made (FR-1.2).
        val model = viewModel(
            RecordingReader(
                listOf(
                    video(uri = "content://media/1"),
                    video(uri = "content://media/2"),
                ),
            ),
        )

        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1", "content://media/2")))
        advanceUntilIdle()
        assertThat(model.state.value.document.clips).hasSize(2)
        assertThat(model.state.value.history).isEqualTo(
            HistoryState.Ready(canUndo = true, canRedo = false, topLabel = "Add media"),
        )

        model.onIntent(EditorIntent.Undo)

        assertThat(model.state.value.document.clips).isEmpty()
        assertThat(model.state.value.document.sources).isEmpty()
    }

    @Test
    fun `a file the policy refuses is reported and adds nothing`() = runTest(dispatcher) {
        val model = viewModel(
            RecordingReader(listOf(video(name = "old.avi", videoCodec = "video/mp4v-es"))),
        )

        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/2")))
        advanceUntilIdle()

        val report = model.state.value.import
        assertThat(report?.importedCount).isEqualTo(0)
        assertThat(report?.messages?.single()).contains("old.avi")
        assertThat(model.state.value.document.clips).isEmpty()
        // Nothing changed, so there is nothing to undo: a rejected import must not leave an
        // undo entry that appears to do nothing.
        assertThat(model.state.value.history).isEqualTo(
            HistoryState.Ready(canUndo = false, canRedo = false, topLabel = null),
        )
    }

    @Test
    fun `a file that cannot be read is reported by name`() = runTest(dispatcher) {
        val model = viewModel(RecordingReader(listOf(unreadable(name = "gone.mp4"))))

        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/3")))
        advanceUntilIdle()

        assertThat(model.state.value.import?.messages?.single())
            .isEqualTo("\"gone.mp4\" could not be opened: Permission denied")
    }

    @Test
    fun `a mixed import adds what it can and reports the rest`() = runTest(dispatcher) {
        val model = viewModel(
            RecordingReader(
                listOf(
                    video(name = "good.mp4", uri = "content://media/1"),
                    unreadable(name = "gone.mp4"),
                    video(
                        name = "old.avi",
                        uri = "content://media/3",
                        videoCodec = "video/mp4v-es",
                    ),
                ),
            ),
        )

        model.onIntent(
            EditorIntent.ImportMedia(
                listOf("content://media/1", "content://media/2", "content://media/3"),
            ),
        )
        advanceUntilIdle()

        val report = model.state.value.import
        assertThat(report?.importedCount).isEqualTo(1)
        assertThat(report?.messages).hasSize(2)
        assertThat(model.state.value.document.clips).hasSize(1)
    }

    @Test
    fun `the import report survives an undo and is cleared only by dismissing it`() = runTest(
        dispatcher,
    ) {
        val model = viewModel(RecordingReader(listOf(video())))

        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        model.onIntent(EditorIntent.Undo)

        // The explanation of what arrived is not history: undoing the clips must not erase
        // the sentence that said how many arrived.
        assertThat(model.state.value.import?.importedCount).isEqualTo(1)

        model.onIntent(EditorIntent.DismissImport)

        assertThat(model.state.value.import).isNull()
    }

    @Test
    fun `an empty selection does nothing at all`() = runTest(dispatcher) {
        val reader = RecordingReader()
        val model = viewModel(reader)

        model.onIntent(EditorIntent.ImportMedia(emptyList()))
        advanceUntilIdle()

        assertThat(reader.requested).isEmpty()
        assertThat(model.state.value.import).isNull()
    }

    // --- Playhead and selection (view state, not history) -------------------

    @Test
    fun `the playhead moves and is held inside the timeline`() = runTest(dispatcher) {
        val model = viewModel(RecordingReader(listOf(video(durationUs = 4_000_000L))))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()

        model.onIntent(EditorIntent.SetPlayhead(2_000_000L))
        assertThat(model.state.value.playheadUs).isEqualTo(2_000_000L)

        // Past the end of the last clip: clamped, because a playhead drawn past the content is a
        // playhead the user cannot see or scrub back from.
        model.onIntent(EditorIntent.SetPlayhead(9_000_000L))
        assertThat(model.state.value.playheadUs).isEqualTo(4_000_000L)

        model.onIntent(EditorIntent.SetPlayhead(-1_000L))
        assertThat(model.state.value.playheadUs).isEqualTo(0L)
    }

    @Test
    fun `moving the playhead is not an edit and cannot be undone`() = runTest(dispatcher) {
        val model = viewModel(RecordingReader(listOf(video())))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        val historyAfterImport = model.state.value.history

        model.onIntent(EditorIntent.SetPlayhead(1_000_000L))

        // The stack did not move: undoing the import must not have to be undone twice, and an
        // undo of a trim must not rewind where the user is looking.
        assertThat(model.state.value.history).isEqualTo(historyAfterImport)
    }

    @Test
    fun `tapping a clip selects it`() = runTest(dispatcher) {
        val model = viewModel(RecordingReader(listOf(video())))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        val clipId = model.state.value.document.clips.single().id

        model.onIntent(EditorIntent.SelectClip(clipId))

        assertThat(model.state.value.selection).isEqualTo(Selection.Clip(clipId))
    }

    @Test
    fun `a selection for a clip the document does not have is ignored`() = runTest(dispatcher) {
        // The id comes from a tap resolved against a frame the user saw; a clip deleted in the
        // meantime must not leave the inspector editing a clip that is not there.
        val model = viewModel(RecordingReader(listOf(video())))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()

        model.onIntent(EditorIntent.SelectClip("clip-that-never-existed"))

        assertThat(model.state.value.selection).isEqualTo(Selection.None)
    }

    @Test
    fun `clearing the selection leaves nothing selected`() = runTest(dispatcher) {
        val model = viewModel(RecordingReader(listOf(video())))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        model.onIntent(EditorIntent.SelectClip(model.state.value.document.clips.single().id))

        model.onIntent(EditorIntent.ClearSelection)

        assertThat(model.state.value.selection).isEqualTo(Selection.None)
    }

    @Test
    fun `undoing the import leaves the playhead and the selection consistent with it`() = runTest(
        dispatcher,
    ) {
        val model = viewModel(RecordingReader(listOf(video(durationUs = 4_000_000L))))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        model.onIntent(EditorIntent.SelectClip(model.state.value.document.clips.single().id))
        model.onIntent(EditorIntent.SetPlayhead(3_000_000L))

        model.onIntent(EditorIntent.Undo)

        // Both view fields are re-derived against the new (empty) document rather than copied: a
        // playhead at 3 s and a selection naming a clip that no longer exists would both point at
        // nothing, and the timeline would draw off its own content.
        assertThat(model.state.value.document.clips).isEmpty()
        assertThat(model.state.value.playheadUs).isEqualTo(0L)
        assertThat(model.state.value.selection).isEqualTo(Selection.None)
    }
}
