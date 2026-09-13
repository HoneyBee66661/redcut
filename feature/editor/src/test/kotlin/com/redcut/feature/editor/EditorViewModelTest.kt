package com.redcut.feature.editor

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.redcut.core.common.IdSource
import com.redcut.core.common.logging.NoOpRedcutLogger
import com.redcut.core.media.MediaSourceReader
import com.redcut.core.media.PreviewFrames
import com.redcut.core.media.SourceReadResult
import com.redcut.core.media.ThumbnailSource
import com.redcut.core.media.ThumbnailStore
import com.redcut.domain.document.ClipAdjustment
import com.redcut.domain.document.ClipEdge
import com.redcut.domain.document.CutTool
import com.redcut.domain.document.FrameStep
import com.redcut.domain.document.ImportRejection
import com.redcut.domain.document.ProbedSource
import com.redcut.domain.document.SourceProbe
import com.redcut.feature.editor.timeline.TimelineThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
        // The timeline's pictures are not this test's subject: a store whose source never returns
        // an image keeps every case here about state rather than about decoding.
        thumbnails = TimelineThumbnails(
            ThumbnailStore(source = NoThumbnails, logger = NoOpRedcutLogger),
        ),
        // Same fake for the preview: a source that never returns an image keeps every case here about
        // state rather than about decoding.
        previewFrames = PreviewFrames(source = NoThumbnails, logger = NoOpRedcutLogger),
        ids = ids(),
        io = dispatcher,
    )

    /** A thumbnail source that produces nothing, for tests that do not draw a timeline. */
    private object NoThumbnails : ThumbnailSource {
        override suspend fun thumbnail(sourceId: String, uri: String, positionUs: Long): Bitmap? =
            null
    }

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

    // --- Trim (FR-2.1) ------------------------------------------------------

    /** Imports one 4-second clip and returns the model plus its clip id. */
    private fun TestScope.importedClip(): Pair<EditorViewModel, String> {
        val model = viewModel(RecordingReader(listOf(video(durationUs = 4_000_000L))))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        return model to model.state.value.document.clips.single().id
    }

    @Test
    fun `a whole trim drag is one undo entry labelled Trim`() = runTest(dispatcher) {
        val (model, clipId) = importedClip()

        model.onIntent(EditorIntent.BeginTrim(clipId, ClipEdge.IN, 1_000_000L))
        model.onIntent(EditorIntent.UpdateTrim(1_400_000L))
        model.onIntent(EditorIntent.UpdateTrim(1_800_000L))
        model.onIntent(EditorIntent.EndTrim)

        // The drag moved the in-point, and the history has ONE entry for the gesture: an undo per
        // frame would make the user press undo twenty times to give back one second of footage.
        assertThat(model.state.value.document.clips.single().sourceInUs).isEqualTo(1_800_000L)
        assertThat(model.state.value.history)
            .isEqualTo(HistoryState.Ready(canUndo = true, canRedo = false, topLabel = "Trim"))
        assertThat(model.state.value.tool).isEqualTo(ToolState.Idle)

        model.onIntent(EditorIntent.Undo)
        assertThat(model.state.value.document.clips.single().sourceInUs).isEqualTo(0L)
    }

    @Test
    fun `trimming the in-point leaves the out-point alone`() = runTest(dispatcher) {
        val (model, clipId) = importedClip()

        model.onIntent(EditorIntent.BeginTrim(clipId, ClipEdge.IN, 1_000_000L))
        model.onIntent(EditorIntent.UpdateTrim(1_200_000L))
        model.onIntent(EditorIntent.EndTrim)

        val clip = model.state.value.document.clips.single()
        assertThat(clip.sourceInUs).isEqualTo(1_200_000L)
        assertThat(clip.sourceOutUs).isEqualTo(4_000_000L)
    }

    @Test
    fun `a drag past the end of the source clamps to it`() = runTest(dispatcher) {
        val (model, clipId) = importedClip()

        // The command owns the limit (FR-2.1 is non-destructive): the drag reports 9 s of a 4 s
        // source, and the document gets 4 s rather than rejecting the gesture or reading past the file.
        model.onIntent(EditorIntent.BeginTrim(clipId, ClipEdge.OUT, 9_000_000L))
        model.onIntent(EditorIntent.EndTrim)

        assertThat(model.state.value.document.clips.single().sourceOutUs).isEqualTo(4_000_000L)
    }

    @Test
    fun `a trim in flight reports where the edge is`() = runTest(dispatcher) {
        val (model, clipId) = importedClip()

        model.onIntent(EditorIntent.BeginTrim(clipId, ClipEdge.OUT, 3_000_000L))
        model.onIntent(EditorIntent.UpdateTrim(2_500_000L))

        // The tool carries the source time the edge has reached, which is what the stage body needs
        // to show the frame being trimmed to (FR-2.1's live edge preview).
        assertThat(model.state.value.tool)
            .isEqualTo(ToolState.Trimming(clipId, ClipEdge.OUT, 2_500_000L))
    }

    @Test
    fun `a cancelled trim leaves the document and the history untouched`() = runTest(dispatcher) {
        val (model, clipId) = importedClip()
        val before = model.state.value

        model.onIntent(EditorIntent.BeginTrim(clipId, ClipEdge.IN, 1_000_000L))
        model.onIntent(EditorIntent.UpdateTrim(2_000_000L))
        model.onIntent(EditorIntent.CancelTrim)

        // No trace: the document is back, the tool is idle, and there is no undo entry for a gesture
        // the user abandoned. An entry that appeared to do nothing would be worse than none.
        assertThat(model.state.value.document).isEqualTo(before.document)
        assertThat(model.state.value.tool).isEqualTo(ToolState.Idle)
        assertThat(model.state.value.history).isEqualTo(before.history)
    }

    @Test
    fun `a drag with no trim in flight is ignored`() = runTest(dispatcher) {
        val (model, _) = importedClip()
        val before = model.state.value

        model.onIntent(EditorIntent.UpdateTrim(1_000_000L))
        model.onIntent(EditorIntent.EndTrim)
        model.onIntent(EditorIntent.CancelTrim)

        // Taps and drags race on a real screen; an Update arriving after the gesture ended must do
        // nothing rather than trim a clip nobody is holding.
        assertThat(model.state.value).isEqualTo(before)
    }

    @Test
    fun `a trim on a clip the document does not have is ignored`() = runTest(dispatcher) {
        val (model, _) = importedClip()
        val before = model.state.value

        model.onIntent(EditorIntent.BeginTrim("clip-that-never-existed", ClipEdge.IN, 1_000_000L))

        assertThat(model.state.value).isEqualTo(before)
    }

    // --- Cut tools (FR-2.2–2.6) --------------------------------------------

    /** Imports the given videos and returns the model plus its clip ids, in timeline order. */
    private fun TestScope.importedClips(
        vararg videos: SourceReadResult.Read,
    ): Pair<EditorViewModel, List<String>> {
        val model = viewModel(RecordingReader(videos.toList()))
        model.onIntent(EditorIntent.ImportMedia(videos.map { it.source.uri }))
        advanceUntilIdle()
        return model to model.state.value.document.clips.map { it.id }
    }

    @Test
    fun `a split at the playhead makes two clips in one history entry`() = runTest(dispatcher) {
        val (model, clipIds) = importedClips(video())
        model.onIntent(EditorIntent.SetPlayhead(2_000_000L))

        model.onIntent(EditorIntent.ApplyCut(CutTool.SPLIT))

        // One entry, labelled with what the tool was: §7.3's "Undo Split". A cut is a discrete
        // decision, so unlike a trim drag it goes straight onto the stack.
        assertThat(model.state.value.document.clips).hasSize(2)
        assertThat(model.state.value.document.clips.first().id).isEqualTo(clipIds.single())
        assertThat(model.state.value.history)
            .isEqualTo(HistoryState.Ready(canUndo = true, canRedo = false, topLabel = "Split"))

        model.onIntent(EditorIntent.Undo)
        assertThat(model.state.value.document.clips).hasSize(1)
    }

    @Test
    fun `the tools act on the clip under the playhead`() = runTest(dispatcher) {
        // Two 4-second clips: the timeline is 0–4 s and 4–8 s. A playhead at 6 s is two seconds into
        // the SECOND clip, so the cut must land there and leave the first clip alone.
        val (model, clipIds) = importedClips(
            video(uri = "content://media/1"),
            video(uri = "content://media/2"),
        )
        model.onIntent(EditorIntent.SetPlayhead(6_000_000L))

        model.onIntent(EditorIntent.ApplyCut(CutTool.CUT_LEFT))

        val first = model.state.value.document.clips[0]
        val second = model.state.value.document.clips[1]
        assertThat(first.sourceInUs).isEqualTo(0L)
        // The surviving tail keeps the ORIGINAL id: a literal split-and-delete would orphan every
        // effect scoped to this clip and drop the selection (see CutLeft in the domain).
        assertThat(second.id).isEqualTo(clipIds[1])
        assertThat(second.sourceInUs).isEqualTo(2_000_000L)
    }

    @Test
    fun `delete ripples the gap closed rather than leaving a hole`() = runTest(dispatcher) {
        val (model, clipIds) = importedClips(
            video(uri = "content://media/1"),
            video(uri = "content://media/2"),
        )
        model.onIntent(EditorIntent.SetPlayhead(1_000_000L))

        model.onIntent(EditorIntent.ApplyCut(CutTool.DELETE))

        assertThat(model.state.value.document.clips.map { it.id }).containsExactly(clipIds[1])
    }

    @Test
    fun `a tool the document refuses changes nothing at all`() = runTest(dispatcher) {
        val (model, _) = importedClips(video())
        // The playhead clamps to the end of the timeline, where there is no clip to cut. The UI would
        // have disabled the button; the handler must not depend on the UI having done so.
        model.onIntent(EditorIntent.SetPlayhead(9_000_000L))
        val before = model.state.value

        model.onIntent(EditorIntent.ApplyCut(CutTool.SPLIT))
        model.onIntent(EditorIntent.ApplyCut(CutTool.DELETE))

        assertThat(model.state.value).isEqualTo(before)
    }

    @Test
    fun `merge undoes a split, fusing the halves back into one clip`() = runTest(dispatcher) {
        // The natural flow that makes merge useful, and the only one available before reorder exists:
        // a split produces two clips of the SAME source with adjacent source ranges, which is exactly
        // FR-2.4's precondition. Merging them back must be the inverse of the split.
        val (model, _) = importedClips(video())
        model.onIntent(EditorIntent.SetPlayhead(2_000_000L))
        model.onIntent(EditorIntent.ApplyCut(CutTool.SPLIT))
        assertThat(model.state.value.document.clips).hasSize(2)

        model.onIntent(EditorIntent.SetPlayhead(0L))
        model.onIntent(EditorIntent.ApplyCut(CutTool.MERGE))

        assertThat(model.state.value.document.clips).hasSize(1)
        assertThat(model.state.value.document.clips.single().sourceOutUs).isEqualTo(4_000_000L)
        assertThat(model.state.value.history)
            .isEqualTo(HistoryState.Ready(canUndo = true, canRedo = false, topLabel = "Merge"))

        // Merge is an ordinary command, so undo brings the split back.
        model.onIntent(EditorIntent.Undo)
        assertThat(model.state.value.document.clips).hasSize(2)
    }

    @Test
    fun `merge refuses clips from different files`() = runTest(dispatcher) {
        val (model, _) = importedClips(
            video(uri = "content://media/1"),
            video(uri = "content://media/2"),
        )
        model.onIntent(EditorIntent.SetPlayhead(0L))
        val before = model.state.value

        model.onIntent(EditorIntent.ApplyCut(CutTool.MERGE))

        // Two separately imported videos are not source-adjacent, so the toolbar's Merge is disabled
        // and the handler must refuse too — `MergeClips` returns the document unchanged, which would
        // otherwise be indistinguishable from a merge that happened to do nothing.
        assertThat(model.state.value).isEqualTo(before)
    }

    @Test
    fun `duplicate copies the clip at the playhead right after itself`() = runTest(dispatcher) {
        val (model, clipIds) = importedClips(video())
        model.onIntent(EditorIntent.SetPlayhead(1_000_000L))

        model.onIntent(EditorIntent.ApplyCut(CutTool.DUPLICATE))

        val clips = model.state.value.document.clips
        assertThat(clips).hasSize(2)
        // The copy sits immediately after the original, reads the same source range, and has its own
        // id — which means the two are NOT source-adjacent, so merge will (correctly) refuse them.
        assertThat(clips[0].id).isEqualTo(clipIds.single())
        assertThat(clips[1].id).isNotEqualTo(clipIds.single())
        assertThat(clips[1].sourceInUs).isEqualTo(clips[0].sourceInUs)
        assertThat(clips[1].sourceOutUs).isEqualTo(clips[0].sourceOutUs)
        assertThat(model.state.value.history.topLabelOrNull()).isEqualTo("Duplicate")
    }

    @Test
    fun `frame-stepping moves the playhead by one frame of the clip it is on`() = runTest(
        dispatcher,
    ) {
        val (model, _) = importedClips(video())
        val historyBefore = model.state.value.history

        // One 30 fps clip: a frame is 33 333 µs of timeline.
        model.onIntent(EditorIntent.StepPlayhead(FrameStep.FORWARD))
        assertThat(model.state.value.playheadUs).isEqualTo(33_333L)

        model.onIntent(EditorIntent.StepPlayhead(FrameStep.BACK))
        assertThat(model.state.value.playheadUs).isEqualTo(0L)

        // And it is view state, not an edit: stepping does not ADD a history entry. The import is
        // already on the stack, so the claim that matters is that stepping left it alone — a playhead
        // move must never become undoable.
        assertThat(model.state.value.history).isEqualTo(historyBefore)
    }

    @Test
    fun `frame-stepping stops at the ends instead of wrapping`() = runTest(dispatcher) {
        val (model, _) = importedClips(video())

        model.onIntent(EditorIntent.StepPlayhead(FrameStep.BACK))
        assertThat(model.state.value.playheadUs).isEqualTo(0L)

        // Four seconds of timeline, so a step forward from the end stays at the end.
        model.onIntent(EditorIntent.SetPlayhead(4_000_000L))
        model.onIntent(EditorIntent.StepPlayhead(FrameStep.FORWARD))
        assertThat(model.state.value.playheadUs).isEqualTo(4_000_000L)
    }

    // --- Reorder (FR-2.7) --------------------------------------------------

    @Test
    fun `reorder moves a clip to a slot, selects it, and is one undoable entry`() = runTest(
        dispatcher,
    ) {
        val (model, clipIds) = importedClips(
            video(uri = "content://media/1"),
            video(uri = "content://media/2"),
        )

        model.onIntent(EditorIntent.ApplyReorder(clipId = clipIds[1], toIndex = 0))

        assertThat(model.state.value.document.clips.map { it.id })
            .containsExactly(clipIds[1], clipIds[0]).inOrder()
        assertThat(model.state.value.history)
            .isEqualTo(HistoryState.Ready(canUndo = true, canRedo = false, topLabel = "Reorder"))
        // The moved clip ends up selected: after a reorder the user's attention is on where it went.
        assertThat(model.state.value.selection).isEqualTo(Selection.Clip(clipIds[1]))

        model.onIntent(EditorIntent.Undo)
        assertThat(model.state.value.document.clips.map { it.id })
            .containsExactly(clipIds[0], clipIds[1]).inOrder()
    }

    @Test
    fun `reorder to the slot it already occupies adds no history entry`() = runTest(dispatcher) {
        val (model, clipIds) = importedClips(
            video(uri = "content://media/1"),
            video(uri = "content://media/2"),
        )
        val historyBefore = model.state.value.history

        model.onIntent(EditorIntent.ApplyReorder(clipId = clipIds[0], toIndex = 0))

        // `ReorderClip` refuses a move to its own slot, and `UndoStack.execute` refuses to record a
        // command that changed nothing — without that pair, a drag that ended where it started would
        // leave an undo entry that appears to do nothing when tapped.
        assertThat(model.state.value.document.clips.map { it.id })
            .containsExactly(clipIds[0], clipIds[1]).inOrder()
        assertThat(model.state.value.history).isEqualTo(historyBefore)
    }

    // --- The inspector (FR-3.1–3.4, 3.9) ----------------------------------

    @Test
    fun `a slider drag is one undoable entry, however many frames it took`() = runTest(dispatcher) {
        val (model, clipIds) = importedClips(video())

        model.onIntent(EditorIntent.BeginAdjust(clipIds[0], ClipAdjustment.SPEED))
        // A slider emits a value per frame while the finger is down.
        listOf(1.5f, 2f, 2.5f).forEach { value -> model.onIntent(EditorIntent.UpdateAdjust(value)) }
        model.onIntent(EditorIntent.EndAdjust)

        assertThat(model.state.value.document.clips.single().speed).isEqualTo(2.5f)
        assertThat(model.state.value.history)
            .isEqualTo(HistoryState.Ready(canUndo = true, canRedo = false, topLabel = "Speed"))
        assertThat(model.state.value.tool).isEqualTo(ToolState.Idle)

        // One entry, so one undo puts the clip back where the drag started.
        model.onIntent(EditorIntent.Undo)
        assertThat(model.state.value.document.clips.single().speed).isEqualTo(1f)
    }

    @Test
    fun `starting an adjust selects the clip it is about`() = runTest(dispatcher) {
        val (model, clipIds) = importedClips(video())

        model.onIntent(EditorIntent.BeginAdjust(clipIds[0], ClipAdjustment.VOLUME))

        assertThat(model.state.value.selection).isEqualTo(Selection.Clip(clipIds[0]))
        assertThat(model.state.value.tool)
            .isEqualTo(ToolState.Adjusting(clipIds[0], ClipAdjustment.VOLUME))
    }

    @Test
    fun `abandoning a drag rolls the clip back and adds no entry`() = runTest(dispatcher) {
        val (model, clipIds) = importedClips(video())
        val historyBefore = model.state.value.history

        model.onIntent(EditorIntent.BeginAdjust(clipIds[0], ClipAdjustment.VOLUME))
        model.onIntent(EditorIntent.UpdateAdjust(0.2f))
        model.onIntent(EditorIntent.CancelAdjust)

        assertThat(model.state.value.document.clips.single().volume).isEqualTo(1f)
        assertThat(model.state.value.history).isEqualTo(historyBefore)
        assertThat(model.state.value.tool).isEqualTo(ToolState.Idle)
    }

    @Test
    fun `a drag that never moves the control changes nothing`() = runTest(dispatcher) {
        val (model, clipIds) = importedClips(video())
        val historyBefore = model.state.value.history

        model.onIntent(EditorIntent.BeginAdjust(clipIds[0], ClipAdjustment.MUTE))
        model.onIntent(EditorIntent.EndAdjust)

        // The finger went down and up on a control it did not move: recording a "Mute" entry there
        // would give the user an undo button that appears to do nothing.
        assertThat(model.state.value.history).isEqualTo(historyBefore)
        assertThat(model.state.value.document.clips.single().muted).isFalse()
    }

    @Test
    fun `an update with no drag in flight is ignored`() = runTest(dispatcher) {
        val (model, _) = importedClips(video())
        val before = model.state.value

        model.onIntent(EditorIntent.UpdateAdjust(3f))

        assertThat(model.state.value.document).isEqualTo(before.document)
    }

    @Test
    fun `an adjust on a clip the document does not have never starts`() = runTest(dispatcher) {
        val (model, _) = importedClips(video())

        model.onIntent(EditorIntent.BeginAdjust("nope", ClipAdjustment.SPEED))

        assertThat(model.state.value.tool).isEqualTo(ToolState.Idle)
    }

    private fun HistoryState.topLabelOrNull(): String? = (this as? HistoryState.Ready)?.topLabel
}
