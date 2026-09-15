package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The caption commands (FR-4.3, spec task 3.6): add a text overlay, move it, retime it.
 *
 * The three of them share one subject — the document's effect stack — so they are tested together, the way
 * `AdjustCommandsTest` holds the Edit stage's five. What each case is really asserting is one of two
 * things: that the caption lands where the model says an overlay lives (the stack, document-scoped,
 * appended), or that a value a gesture could plausibly produce ends up one the model can hold.
 */
class TextOverlayCommandsTest {

    /** A document with one caption on it, in the shape [AddTextOverlay] gives one. */
    private fun documentWithCaption(caption: AppliedEffect.Text = caption("t1")): EditDocument =
        sampleDocument().copy(effects = listOf(caption))

    private fun caption(
        id: String = "t1",
        scope: EffectScope = EffectScope.Document,
        timeRange: TimeRange = TimeRange(0L, 2 * SEC),
        enabled: Boolean = true,
    ) = AppliedEffect.Text(
        id = id,
        scope = scope,
        timeRange = timeRange,
        spec = TextSpec(content = "a caption"),
        enabled = enabled,
        // The box a caption actually arrives in, so the fixture is the shape the add command produces
        // rather than the frame-filling default a hand-built Text effect would get — which is the one box
        // a caption can never be dragged out of. See TextOverlayBox.
        transform = TextOverlayBox.DEFAULT.toTransform(),
    )

    private fun EditDocument.captionAt(effectId: String): AppliedEffect.Text =
        requireNotNull(textOverlayById(effectId)) { "no caption $effectId in the document" }

    // --- Adding (FR-4.3) ---------------------------------------------------

    @Test
    fun `adding a caption appends it to the effect stack in order`() {
        // Render order IS stack order, so "appended" is what makes the caption the user just added the one
        // drawn on top rather than the one hidden behind an older overlay.
        val first = AddTextOverlay(
            "t1",
            TextSpec("first"),
            TimeRange(0L, SEC),
        ).apply(sampleDocument())
        val second = AddTextOverlay("t2", TextSpec("second"), TimeRange(SEC, 2 * SEC)).apply(first)

        assertThat(second.effects.map { it.id }).containsExactly("t1", "t2").inOrder()
        assertThat(first.effects).hasSize(1)
        // The stack the caption was appended to is otherwise untouched.
        assertThat(second.clips).isEqualTo(sampleDocument().clips)
    }

    @Test
    fun `the caption a command adds is document scoped, enabled and placed in the caption band`() {
        val added = AddTextOverlay("t1", TextSpec("hello"), TimeRange(0L, SEC))
            .apply(sampleDocument())
            .captionAt("t1")

        assertThat(added.scope).isEqualTo(EffectScope.Document)
        assertThat(added.enabled).isTrue()
        assertThat(added.timeRange).isEqualTo(TimeRange(0L, SEC))
        // The default box is NOT the frame: a frame-filling box cannot be dragged, which is the one thing
        // a caption has to be able to do. See TextOverlayBox.
        assertThat(added.transform.toOverlayBox()).isEqualTo(TextOverlayBox.DEFAULT)
    }

    @Test
    fun `adding a caption whose id the stack already holds changes nothing`() {
        // Idempotence by id is what makes the command safe to fire twice: a double tap must not stack two
        // captions on the same words.
        val doc = documentWithCaption()
        val again = AddTextOverlay(
            "t1",
            TextSpec("different"),
            TimeRange(3 * SEC, 4 * SEC),
        ).apply(doc)

        assertThat(again).isEqualTo(doc)
        assertThat(again.effects).hasSize(1)
    }

    @Test
    fun `a blank caption and one below the floor are refused rather than stored`() {
        val doc = sampleDocument()

        // Blank: nothing to draw and no bounds to press, so it would be an effect the user cannot see.
        assertThat(
            AddTextOverlay("t1", TextSpec("   "), TimeRange(0L, SEC)).apply(doc),
        ).isEqualTo(doc)
        // Below the floor: a drag that overshot, not a caption.
        assertThat(
            AddTextOverlay(
                "t1",
                TextSpec("hi"),
                TimeRange(0L, MIN_TEXT_DURATION_US - 1),
            ).apply(doc),
        ).isEqualTo(doc)
        // Exactly at the floor is legal.
        assertThat(
            AddTextOverlay(
                "t1",
                TextSpec("hi"),
                TimeRange(0L, MIN_TEXT_DURATION_US),
            ).apply(doc).effects,
        ).hasSize(1)
    }

    @Test
    fun `undoing the add restores the stack the caption was added to`() {
        val stack = UndoStack(sampleDocument())
        stack.execute(AddTextOverlay("t1", TextSpec("hello"), TimeRange(0L, SEC)))

        assertThat(stack.current.effects).hasSize(1)
        assertThat(stack.undoLabel).isEqualTo("Add text")

        stack.undo()
        assertThat(stack.current.effects).isEmpty()
        assertThat(stack.current).isEqualTo(sampleDocument())

        stack.redo()
        assertThat(stack.current.effects.map { it.id }).containsExactly("t1")
    }

    // --- Moving (FR-4.3's drag) --------------------------------------------

    @Test
    fun `moving a caption changes its transform and nothing else about it`() {
        val doc = documentWithCaption()
        val before = doc.captionAt("t1")
        val moved = TextOverlayBox.DEFAULT
            .movedToCentre(centerX = 0.5f, centerY = 0.2f)
            .toTransform(base = before.transform)

        val after = SetTextTransform("t1", moved).apply(doc).captionAt("t1")

        assertThat(after.transform).isEqualTo(moved)
        // The identity, the words and the timing are not the drag's business.
        assertThat(after.id).isEqualTo("t1")
        assertThat(after.spec).isEqualTo(before.spec)
        assertThat(after.timeRange).isEqualTo(before.timeRange)
        assertThat(after.scope).isEqualTo(before.scope)
        assertThat(after.enabled).isTrue()
    }

    @Test
    fun `a move that lands where the box already is records no history entry`() {
        // The last frames of a drag against the clamp send the same position repeatedly; each of them
        // changing nothing is what keeps one gesture to one undo entry.
        val stack = UndoStack(documentWithCaption())

        stack.execute(SetTextTransform("t1", TextOverlayBox.DEFAULT.toTransform()))

        assertThat(stack.canUndo).isFalse()
        assertThat(stack.current).isEqualTo(documentWithCaption())
    }

    @Test
    fun `a box dragged past the canvas stops at the edge instead of leaving it`() {
        // Clamped rather than refused, the way every drag in this repo behaves: the gesture sends the raw
        // finger position every frame and expects the box to stop where the picture does.
        val box = TextOverlayBox.DEFAULT

        val topLeft = box.movedToCentre(centerX = 0f, centerY = 0f)
        assertThat(topLeft.left).isWithin(EPSILON).of(0f)
        assertThat(topLeft.top).isWithin(EPSILON).of(0f)
        assertThat(topLeft.width).isWithin(EPSILON).of(box.width)

        val bottomRight = box.movedToCentre(centerX = 1f, centerY = 1f)
        assertThat(bottomRight.right).isWithin(EPSILON).of(1f)
        assertThat(bottomRight.bottom).isWithin(EPSILON).of(1f)

        // A drag that stays inside is honoured exactly.
        val moved = box.movedToCentre(centerX = 0.5f, centerY = 0.5f)
        assertThat(moved.centerX).isWithin(EPSILON).of(0.5f)
        assertThat(moved.centerY).isWithin(EPSILON).of(0.5f)
    }

    @Test
    fun `a box as large as the canvas cannot be moved, and says so by staying put`() {
        // The default transform's degenerate case: there is no legal centre other than the middle. The
        // add command hands a new caption a band for exactly this reason.
        val frame = TransformSpec().toOverlayBox()

        val dragged = frame.movedToCentre(centerX = 0f, centerY = 1f)

        assertThat(dragged).isEqualTo(frame)
    }

    @Test
    fun `a move or a retime addressed to an effect that is not a caption is refused`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                AppliedEffect.Lut("l1", EffectScope.Document, TimeRange(0L, SEC), LutRef("x")),
            ),
        )

        assertThat(SetTextTransform("l1", TextOverlayBox.DEFAULT.toTransform()).apply(doc))
            .isEqualTo(doc)
        assertThat(SetTextRange("l1", 0L, SEC).apply(doc)).isEqualTo(doc)
    }

    // --- Retiming (FR-4.3's start and end) ---------------------------------

    @Test
    fun `a range dragged past its own start collapses to the caption floor`() {
        // The gesture overshoots; TimeRange refuses an inverted range by throwing, so the clamp is what
        // keeps a drag from crashing the editor.
        val retimed = SetTextRange("t1", startUs = 1_500_000L, endUs = 200_000L)
            .apply(documentWithCaption())
            .captionAt("t1")

        assertThat(retimed.timeRange.startUs).isEqualTo(1_500_000L)
        assertThat(retimed.timeRange.durationUs).isEqualTo(MIN_TEXT_DURATION_US)
    }

    @Test
    fun `a negative start clamps to zero`() {
        val retimed = SetTextRange("t1", startUs = -SEC, endUs = SEC)
            .apply(documentWithCaption())
            .captionAt("t1")

        assertThat(retimed.timeRange).isEqualTo(TimeRange(0L, SEC))
    }

    @Test
    fun `a range running past the end of the video is kept, because the timeline can grow`() {
        // sampleDocument is five seconds long and the caption is asked for thirty. The render compiler
        // intersects an overlay with the finished video anyway, so clamping here would silently shorten a
        // caption the day the user appended footage.
        val retimed = SetTextRange("t1", startUs = 0L, endUs = 30 * SEC)
            .apply(documentWithCaption())
            .captionAt("t1")

        assertThat(retimed.timeRange).isEqualTo(TimeRange(0L, 30 * SEC))
    }

    @Test
    fun `a start with no room above it leaves the caption alone instead of throwing`() {
        // `apply` never throws — its contract — and TimeRange's own validation is what would otherwise
        // throw here, on a start no end can exceed.
        val doc = documentWithCaption()

        assertThat(clampedCaptionRange(Long.MAX_VALUE, Long.MAX_VALUE)).isNull()
        assertThat(SetTextRange("t1", Long.MAX_VALUE, Long.MAX_VALUE).apply(doc)).isEqualTo(doc)
    }

    @Test
    fun `retiming a caption leaves its words and its position alone`() {
        val doc = documentWithCaption()
        val before = doc.captionAt("t1")

        val after = SetTextRange("t1", 0L, 4 * SEC).apply(doc).captionAt("t1")

        assertThat(after.timeRange).isEqualTo(TimeRange(0L, 4 * SEC))
        assertThat(after.spec).isEqualTo(before.spec)
        assertThat(after.transform).isEqualTo(before.transform)
    }

    @Test
    fun `a range change that asks for the range the caption holds records nothing`() {
        val stack = UndoStack(documentWithCaption())

        stack.execute(SetTextRange("t1", 0L, 2 * SEC))

        assertThat(stack.canUndo).isFalse()
    }

    // --- What the preview draws (FR-4.3) -----------------------------------

    @Test
    fun `only the enabled document scoped captions covering the playhead are visible`() {
        val doc = sampleDocument().copy(
            effects = listOf(
                caption("document"),
                caption("clip", scope = EffectScope.Clip("c1")),
                caption("later", timeRange = TimeRange(2 * SEC, 4 * SEC)),
                caption("disabled", enabled = false),
            ),
        )

        // A clip-scoped range is relative to its clip, so the preview does not read it here; a disabled
        // effect renders nothing anywhere; "later" has not started yet.
        assertThat(doc.textOverlaysAt(SEC).map { it.id }).containsExactly("document")
        // Half-open, the rule TimeRange states: the caption ending exactly here is over, the one starting
        // exactly here is on.
        assertThat(doc.textOverlaysAt(2 * SEC).map { it.id }).containsExactly("later")
    }

    @Test
    fun `the visible captions come back in stack order`() {
        val doc = sampleDocument().copy(
            effects = listOf(caption("under"), caption("over")),
        )

        assertThat(doc.textOverlaysAt(SEC).map { it.id }).containsExactly("under", "over").inOrder()
        assertThat(doc.textOverlaysAt(10 * SEC)).isEmpty()
    }

    private companion object {
        /** Room for the rounding of a float subtraction, and nothing more. */
        const val EPSILON = 0.0001f
    }
}
