package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Where a dragged clip lands (FR-2.7).
 *
 * The property that matters is at the bottom: whatever index this returns, applying `ReorderClip` with
 * it puts the clip in the slot the marker was drawn in. If those two ever disagree the user sees the
 * clip jump to a place they did not point at, which is worse than reorder not existing.
 */
class ReorderTargetTest {

    private val oneSecond = 1_000_000L

    private fun source() = SourceRef(
        id = "src-1",
        uri = "content://media/1",
        displayName = "clip.mp4",
        durationUs = 60 * oneSecond,
        width = 1920,
        height = 1080,
    )

    private fun clip(id: String, seconds: Int) = Clip(
        id = id,
        sourceId = "src-1",
        sourceInUs = 0L,
        sourceOutUs = seconds * oneSecond,
    )

    /** Three clips of different lengths, so a swapped index is visible rather than symmetric. */
    private fun document() = EditDocument(
        id = "doc",
        name = "Doc",
        sources = listOf(source()),
        tracks = listOf(videoTrack(clip("a", 1), clip("b", 2), clip("c", 3))),
    )

    private fun EditDocument.ids() = clips.map { it.id }

    @Test
    fun `dropping onto the left half of a clip goes before it`() {
        val doc = document()

        // Dragging a out of the way leaves b (0..2 s, midpoint 1 s) then c (2..5 s, midpoint 3.5 s),
        // laid out contiguously because the timeline has no gaps.
        assertThat(doc.reorderTargetIndex(VIDEO, "a", 0L)).isEqualTo(0)
        assertThat(doc.reorderTargetIndex(VIDEO, "a", 900_000L)).isEqualTo(0) // before b's midpoint
        assertThat(
            doc.reorderTargetIndex(VIDEO, "a", 1_400_000L),
        ).isEqualTo(1) // past it, before c's
        assertThat(doc.reorderTargetIndex(VIDEO, "a", 4_000_000L)).isEqualTo(2) // past c's as well
    }

    @Test
    fun `dropping past the end lands last, however far past it is`() {
        val doc = document()

        assertThat(doc.reorderTargetIndex(VIDEO, "a", 6 * oneSecond)).isEqualTo(2)
        assertThat(doc.reorderTargetIndex(VIDEO, "a", 999 * oneSecond)).isEqualTo(2)
    }

    @Test
    fun `the dragged clip is not counted in its own landing`() {
        // The subtle one: while a clip is being dragged it is still IN the document, and it must not
        // occupy a slot in the arithmetic — otherwise every drop would be off by one past its old
        // position.
        val doc = document()

        assertThat(doc.reorderTargetIndex(VIDEO, "b", 0L)).isEqualTo(0)
        assertThat(doc.reorderTargetIndex(VIDEO, "b", 5 * oneSecond)).isEqualTo(2)
    }

    @Test
    fun `a single-clip timeline has exactly one slot`() {
        val one = EditDocument(
            id = "doc",
            name = "Doc",
            sources = listOf(source()),
            tracks = listOf(videoTrack(clip("a", 1))),
        )

        assertThat(one.reorderTargetIndex(VIDEO, "a", 0L)).isEqualTo(0)
        assertThat(one.reorderTargetIndex(VIDEO, "a", 9 * oneSecond)).isEqualTo(0)
    }

    @Test
    fun `an id that is not in the document is refused by the command, not by the arithmetic`() {
        val doc = document()

        // The arithmetic treats an unknown id as "removing nothing", so its answer is merely a slot;
        // what matters is that the COMMAND refuses, so nothing moves and the document is untouched.
        assertThat(ReorderClip(VIDEO, "nope", 1).apply(doc)).isEqualTo(doc)
        assertThat(
            ReorderClip(VIDEO, "nope", 1).apply(doc).ids(),
        ).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `the marker sits where the clip would start after the move`() {
        val doc = document()

        // Index 0 -> at the start; 1 -> after a (1 s); 2 -> after a and b (3 s).
        assertThat(doc.reorderMarkerUs(VIDEO, "c", 0)).isEqualTo(0L)
        assertThat(doc.reorderMarkerUs(VIDEO, "c", 1)).isEqualTo(1 * oneSecond)
        assertThat(doc.reorderMarkerUs(VIDEO, "c", 2)).isEqualTo(3 * oneSecond)
    }

    @Test
    fun `whatever index the drag computes, the document puts the clip there`() {
        // The invariant that keeps the marker honest, checked across every drop position in 100 ms
        // steps for each clip: the clip ends up at the index, and it is the same clip list either way.
        val doc = document()

        doc.clips.forEach { dragged ->
            var drop = 0L
            while (drop <= 7 * oneSecond) {
                val target = doc.reorderTargetIndex(VIDEO, dragged.id, drop)
                val after = ReorderClip(VIDEO, dragged.id, target).apply(doc)

                assertThat(after.ids()).containsExactly("a", "b", "c")
                assertThat(after.clips.indexOfFirst { it.id == dragged.id }).isEqualTo(target)
                drop += 100_000L
            }
        }
    }

    /**
     * The same three clips with a 1 s [Gap] between a and b: a(1 s) gap(1 s) b(2 s) c(3 s).
     *
     * Built by hand because no command creates a gap yet — which is exactly why the arithmetic is worth
     * fixing NOW rather than when the first such command lands: the moment it does, every drag on that lane
     * would draw its marker in the wrong place, and this has to already be right.
     */
    private fun gappedDocument() = EditDocument(
        id = "doc",
        name = "Doc",
        sources = listOf(source()),
        tracks = listOf(
            Track(
                id = VIDEO,
                kind = TrackKind.VIDEO,
                items = listOf(
                    clip("a", 1),
                    Gap(durationUs = oneSecond),
                    clip("b", 2),
                    clip("c", 3),
                ),
            ),
        ),
    )

    @Test
    fun `a gap moves the drop point with it, so the marker lands where the clip really starts`() {
        val doc = gappedDocument()

        // c starts at 4 s on this lane (a 1 s + the gap's 1 s + b 2 s), and after a is taken out of the
        // layout the gap leads, so c starts at 3 s. Both numbers are gap-carrying and neither is 2 s — the
        // answer a sum over clip durations alone gives.
        assertThat(doc.reorderMarkerUs(VIDEO, "c", 2)).isEqualTo(4 * oneSecond)
        assertThat(doc.reorderMarkerUs(VIDEO, "a", 1)).isEqualTo(3 * oneSecond)
        // Slot 0 is "before b", and on this lane b starts at 1 s because the gap comes FIRST — the marker is
        // NOT 0 s. That is the model the card prescribes: the gaps keep their place in the layout and the
        // clips are placed among them, so the front of this lane is the end of its leading gap. Asserted
        // because 0 s is the answer a reader would assume, and the difference is the whole choice.
        assertThat(doc.reorderMarkerUs(VIDEO, "a", 0)).isEqualTo(oneSecond)
        assertThat(doc.reorderTargetIndex(VIDEO, "a", 0L)).isEqualTo(0)
    }

    @Test
    fun `a drop inside a gap-length's worth of slack lands one slot earlier than the sum said`() {
        val doc = gappedDocument()

        // With a dragged out, the others are gap(1 s) b(2 s) c(3 s): b's midpoint is at 2 s and c's at
        // 4.5 s. A drop at 3.6 s is before c's midpoint, so it belongs in the slot BEFORE c — while the
        // clip-duration sum puts c's midpoint at 3.5 s and answers the slot after it.
        assertThat(doc.reorderTargetIndex(VIDEO, "a", 3_600_000L)).isEqualTo(1)
        assertThat(doc.reorderTargetIndex(VIDEO, "a", 4_600_000L)).isEqualTo(2)
    }

    @Test
    fun `the end of the drag is the end of the OTHERS, not of the lane`() {
        val doc = gappedDocument()

        // The card's warning, as an assertion: the marker for "past everything" is the others' own end
        // (gap 1 s + b 2 s + c 3 s = 6 s) — NOT the lane's end, which still holds the room the dragged
        // clip occupies, and not a walk over the track's real items, which has nothing at that index and
        // would fall back to exactly that longer number.
        // `contentEndUs` is the LANE's end (Track's), not the document's — which is the point: the lane
        // still spends the dragged clip's room, and the marker must not follow it there.
        assertThat(requireNotNull(doc.trackById(VIDEO)).contentEndUs).isEqualTo(7 * oneSecond)
        assertThat(doc.reorderMarkerUs(VIDEO, "a", 3)).isEqualTo(6 * oneSecond)
        // The index past everything counts the OTHERS' CLIPS (b and c), not the items: a slot the command
        // could insert at is a clip's, and a gap has no identity to name.
        assertThat(doc.reorderTargetIndex(VIDEO, "a", 9 * oneSecond)).isEqualTo(2)
    }

    @Test
    fun `a lane with no gaps answers exactly what it answered before gaps existed`() {
        val doc = document()

        // The walk that gained an overload must not have moved the answers for the documents that already
        // shipped: with nothing but clips, the cursor lands on the same starts the clip sum produced.
        assertThat(doc.reorderTargetIndex(VIDEO, "c", 1_400_000L)).isEqualTo(1)
        assertThat(doc.reorderMarkerUs(VIDEO, "c", 2)).isEqualTo(3 * oneSecond)
        assertThat(doc.positionedClipsOf(VIDEO).map { it.clip.id to it.startUs })
            .containsExactly("a" to 0L, "b" to 1 * oneSecond, "c" to 3 * oneSecond)
            .inOrder()
    }

    /** The lane's own walk, so the assertion above reads as the production call rather than a bare list. */
    private fun EditDocument.positionedClipsOf(trackId: String): List<PositionedClip> =
        requireNotNull(trackById(trackId)).positionedClips()
}
