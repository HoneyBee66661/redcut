package com.redcut.feature.editor

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.AppliedEffect
import com.redcut.domain.document.CutTool
import com.redcut.domain.document.TextOverlayBox
import com.redcut.domain.document.TextSpec
import com.redcut.domain.document.textOverlaysAt
import com.redcut.domain.document.toOverlayBox
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
 * The caption path's wiring (FR-4.3, spec task 3.6): adding one, dragging one, and what the preview is
 * asked to draw.
 *
 * A class of its own beside `EditorViewModelTest` for the reason `EditorPlaybackTest` is: the cases in the
 * editor's own suite are about the Cut and Edit stages, and a caption is the Effect stage's. What is
 * checked here is the WIRING — which command a gesture becomes, and how many history entries it leaves —
 * never that any text reached a screen: the drawing is a composable this tier cannot press a finger on,
 * and that is a device pass's business (Phase 3's exit criterion).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorTextOverlayTest {

    // Same rule and same reason as the editor's own suite: the import runs on two dispatchers that must
    // be the same scheduler, and `viewModelScope` is Main.
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Imports one four-second clip, puts the playhead at [atUs], and adds a caption there. */
    private fun TestScope.importedCaption(atUs: Long = 0L): Pair<EditorViewModel, String> {
        val model = viewModel(RecordingReader(listOf(video(durationUs = 4_000_000L))))
        model.onIntent(EditorIntent.ImportMedia(listOf("content://media/1")))
        advanceUntilIdle()
        model.onIntent(EditorIntent.SetPlayhead(atUs))
        model.onIntent(EditorIntent.AddText)
        return model to model.state.value.document.effects.single().id
    }

    /** The document's one caption, as the model's own type — the half of an effect a drag changes. */
    private fun EditorViewModel.caption(): AppliedEffect.Text {
        val effect = state.value.document.effects.single()
        require(effect is AppliedEffect.Text) { "the fixture's effect is not a caption" }
        return effect
    }

    private fun EditorViewModel.captionBox(): TextOverlayBox = caption().transform.toOverlayBox()

    // --- Adding (FR-4.3) ---------------------------------------------------

    @Test
    fun `adding a caption puts one effect on the document, in one undo entry`() = runTest(
        dispatcher,
    ) {
        val (model, _) = importedCaption()

        assertThat(model.state.value.document.effects).hasSize(1)
        assertThat(model.state.value.history)
            .isEqualTo(HistoryState.Ready(canUndo = true, canRedo = false, topLabel = "Add text"))

        // One tap, one entry: the import is the entry underneath it, which is what makes the caption
        // undoable on its own rather than as part of the media it was added over.
        model.onIntent(EditorIntent.Undo)
        assertThat(model.state.value.document.effects).isEmpty()
    }

    @Test
    fun `the caption starts at the playhead and is the one the preview draws there`() =
        runTest(dispatcher) {
            val (model, captionId) = importedCaption(atUs = 1_000_000L)
            val document = model.state.value.document

            // The read the preview layer makes, asked here: visible at the playhead it was added at, and
            // not before it. This is the one claim CI can make about FR-4.3's "start time".
            assertThat(document.textOverlaysAt(1_000_000L).map { it.id }).containsExactly(captionId)
            assertThat(document.textOverlaysAt(0L)).isEmpty()
        }

    @Test
    fun `a caption is refused on a document with no clips, because nothing would render it`() =
        runTest(dispatcher) {
            val model = viewModel()
            val before = model.state.value

            model.onIntent(EditorIntent.AddText)

            // Same fact the Effect stage's button reads to disable itself, read again where the intent
            // actually arrives: a caption over no video would be an effect nobody can see or find.
            assertThat(model.state.value.document).isEqualTo(before.document)
            assertThat(model.state.value.history).isEqualTo(before.history)
        }

    // --- Dragging (FR-4.3's "position by dragging") ------------------------

    @Test
    fun `a whole caption drag is one undo entry labelled Move text`() = runTest(dispatcher) {
        val (model, captionId) = importedCaption()

        model.onIntent(EditorIntent.BeginTextDrag(captionId))
        model.onIntent(EditorIntent.UpdateTextDrag(centerX = 0.5f, centerY = 0.4f))
        model.onIntent(EditorIntent.UpdateTextDrag(centerX = 0.5f, centerY = 0.3f))
        model.onIntent(EditorIntent.EndTextDrag)

        assertThat(model.captionBox().centerY).isWithin(TOLERANCE).of(0.3f)
        // ONE entry for the gesture — the reason the drag previews rather than executing per frame.
        assertThat(model.state.value.history)
            .isEqualTo(HistoryState.Ready(canUndo = true, canRedo = false, topLabel = "Move text"))
        assertThat(model.state.value.tool).isEqualTo(ToolState.Idle)
    }

    @Test
    fun `a caption drag moves the box and leaves the words and the timing alone`() =
        runTest(dispatcher) {
            val (model, captionId) = importedCaption()
            val before = model.caption()

            model.onIntent(EditorIntent.BeginTextDrag(captionId))
            model.onIntent(EditorIntent.UpdateTextDrag(centerX = 0.5f, centerY = 0.2f))
            model.onIntent(EditorIntent.EndTextDrag)

            val after = model.caption()
            assertThat(after.transform).isNotEqualTo(before.transform)
            assertThat(after.spec).isEqualTo(before.spec)
            assertThat(after.timeRange).isEqualTo(before.timeRange)
        }

    @Test
    fun `a cancelled caption drag leaves the document and the history untouched`() = runTest(
        dispatcher,
    ) {
        val (model, captionId) = importedCaption()
        val before = model.state.value

        model.onIntent(EditorIntent.BeginTextDrag(captionId))
        model.onIntent(EditorIntent.UpdateTextDrag(centerX = 0.5f, centerY = 0.2f))
        model.onIntent(EditorIntent.CancelTextDrag)

        // No trace: a gesture the user abandoned is not an edit, and an entry that appeared to do nothing
        // would be worse than none.
        assertThat(model.state.value.document).isEqualTo(before.document)
        assertThat(model.state.value.history).isEqualTo(before.history)
        assertThat(model.state.value.tool).isEqualTo(ToolState.Idle)
    }

    @Test
    fun `a drag with no caption in flight is ignored`() = runTest(dispatcher) {
        val (model, _) = importedCaption()
        val before = model.state.value

        model.onIntent(EditorIntent.UpdateTextDrag(centerX = 0.5f, centerY = 0.2f))
        model.onIntent(EditorIntent.EndTextDrag)
        model.onIntent(EditorIntent.CancelTextDrag)

        assertThat(model.state.value.document).isEqualTo(before.document)
        assertThat(model.state.value.history).isEqualTo(before.history)
        assertThat(model.state.value.tool).isEqualTo(before.tool)
    }

    @Test
    fun `a caption drag for a caption the document does not have is ignored`() = runTest(
        dispatcher,
    ) {
        val (model, _) = importedCaption()
        val before = model.state.value

        // The id comes from a hit test against a frame the user saw, so a caption removed since — an undo,
        // a reopened project — must not open a gesture against nothing.
        model.onIntent(EditorIntent.BeginTextDrag("text-999"))
        model.onIntent(EditorIntent.UpdateTextDrag(centerX = 0.5f, centerY = 0.2f))
        model.onIntent(EditorIntent.EndTextDrag)

        assertThat(model.state.value.document).isEqualTo(before.document)
        assertThat(model.state.value.history).isEqualTo(before.history)
        assertThat(model.state.value.tool).isEqualTo(ToolState.Idle)
    }

    @Test
    fun `a caption dragged past the edge of the canvas stops there`() = runTest(dispatcher) {
        val (model, captionId) = importedCaption()

        model.onIntent(EditorIntent.BeginTextDrag(captionId))
        model.onIntent(EditorIntent.UpdateTextDrag(centerX = 9f, centerY = 9f))
        model.onIntent(EditorIntent.EndTextDrag)

        // The gesture reports the raw finger position, as every drag in this editor does; the clamp is the
        // model's ([TextOverlayBox]), and the user reads back where the caption actually stopped.
        val box = model.captionBox()
        assertThat(box.right).isWithin(TOLERANCE).of(1f)
        assertThat(box.bottom).isWithin(TOLERANCE).of(1f)
    }

    @Test
    fun `undoing a caption drag puts the caption back where it was`() = runTest(dispatcher) {
        val (model, captionId) = importedCaption()

        model.onIntent(EditorIntent.BeginTextDrag(captionId))
        model.onIntent(EditorIntent.UpdateTextDrag(centerX = 0.5f, centerY = 0.2f))
        model.onIntent(EditorIntent.EndTextDrag)

        model.onIntent(EditorIntent.Undo)

        assertThat(model.captionBox()).isEqualTo(TextOverlayBox.DEFAULT)
    }

    @Test
    fun `a caption drag that ends where it started adds no history entry`() = runTest(dispatcher) {
        val (model, captionId) = importedCaption()
        val history = model.state.value.history

        model.onIntent(EditorIntent.BeginTextDrag(captionId))
        // A no-move drag: the finger presses the caption's own centre and lets go. The gesture's
        // absolute-answer arithmetic (anchor + travel) yields the anchor for zero travel, and
        // TextOverlayBox.movedToCentre returns the identical box for its own centre — so the final
        // previewed frame is a no-op and UndoStack.commit rolls the gesture back instead of recording a
        // "Move text" entry that changed no pixels.
        model.onIntent(
            EditorIntent.UpdateTextDrag(
                TextOverlayBox.DEFAULT.centerX,
                TextOverlayBox.DEFAULT.centerY,
            ),
        )
        model.onIntent(
            EditorIntent.UpdateTextDrag(
                TextOverlayBox.DEFAULT.centerX,
                TextOverlayBox.DEFAULT.centerY,
            ),
        )
        model.onIntent(EditorIntent.EndTextDrag)

        assertThat(model.state.value.history).isEqualTo(history)
        assertThat(model.captionBox()).isEqualTo(TextOverlayBox.DEFAULT)
    }

    // --- Styling (FR-4.3's inspector card, J-2) -----------------------------

    @Test
    fun `a whole style drag is one undo entry labelled Text style`() = runTest(dispatcher) {
        val (model, captionId) = importedCaption()

        model.onIntent(EditorIntent.BeginTextStyle(captionId, TextSpec(content = "Text", fontSizeSp = 60f)))
        model.onIntent(EditorIntent.UpdateTextStyle(TextSpec(content = "Text", fontSizeSp = 72f)))
        model.onIntent(EditorIntent.UpdateTextStyle(TextSpec(content = "Text", fontSizeSp = 64f)))
        model.onIntent(EditorIntent.EndTextStyle)

        assertThat(model.caption().spec.fontSizeSp).isWithin(TOLERANCE).of(64f)
        assertThat(model.state.value.history)
            .isEqualTo(HistoryState.Ready(canUndo = true, canRedo = false, topLabel = "Text style"))
        assertThat(model.state.value.tool).isEqualTo(ToolState.Idle)

        model.onIntent(EditorIntent.Undo)
        assertThat(model.caption().spec.fontSizeSp).isWithin(TOLERANCE).of(48f)
    }

    @Test
    fun `a style drag selects the caption it styles`() = runTest(dispatcher) {
        val (model, captionId) = importedCaption()

        // The row and its selection are one thought, the rule BeginAdjust keeps for a slider and its
        // clip: the inspector draws from the selection, so a row that did not select would style a
        // caption the inspector is not showing.
        model.onIntent(EditorIntent.BeginTextStyle(captionId, TextSpec(content = "Text", fontSizeSp = 60f)))
        model.onIntent(EditorIntent.EndTextStyle)

        assertThat(model.state.value.selection).isEqualTo(Selection.Text(captionId))
    }

    @Test
    fun `a style drag for a caption the document does not have is ignored`() = runTest(dispatcher) {
        val (model, _) = importedCaption()
        val before = model.state.value

        model.onIntent(
            EditorIntent.BeginTextStyle(
                "text-999",
                TextSpec(content = "Text", fontSizeSp = 60f),
            ),
        )
        model.onIntent(EditorIntent.UpdateTextStyle(TextSpec(content = "Text", fontSizeSp = 64f)))
        model.onIntent(EditorIntent.EndTextStyle)

        assertThat(model.state.value.document).isEqualTo(before.document)
        assertThat(model.state.value.history).isEqualTo(before.history)
        assertThat(model.state.value.selection).isEqualTo(before.selection)
    }

    @Test
    fun `selecting a caption by tap names it for the inspector`() = runTest(dispatcher) {
        val (model, captionId) = importedCaption()

        model.onIntent(EditorIntent.SelectTextOverlay(captionId))

        assertThat(model.state.value.selection).isEqualTo(Selection.Text(captionId))
    }

    @Test
    fun `selecting a caption the document does not hold changes nothing`() = runTest(dispatcher) {
        val (model, _) = importedCaption()
        val before = model.state.value

        model.onIntent(EditorIntent.SelectTextOverlay("text-999"))

        assertThat(model.state.value.selection).isEqualTo(before.selection)
    }

    @Test
    fun `a caption selection survives an undo of an edit that is not the caption`() = runTest(dispatcher) {
        val (model, captionId) = importedCaption()
        // One more edit above the caption's, so the undo below rewinds THAT rather than the caption —
        // the state the reconciler runs in with a caption selection held. The duplicate is a discrete
        // command like the add is, so the stack now has an entry the caption does not care about.
        model.onIntent(EditorIntent.SetPlayhead(1_000_000L))
        model.onIntent(EditorIntent.ApplyCut(CutTool.DUPLICATE))
        model.onIntent(EditorIntent.SelectTextOverlay(captionId))

        model.onIntent(EditorIntent.Undo)

        assertThat(model.state.value.selection).isEqualTo(Selection.Text(captionId))
        assertThat(model.caption().spec.content).isEqualTo("Text")
    }

    private companion object {
        /** Room for the rounding of a float conversion, and nothing more. */
        const val TOLERANCE = 0.0001f
    }
}
