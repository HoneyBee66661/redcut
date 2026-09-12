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
        clips = listOf(clip("a", 1), clip("b", 2), clip("c", 3)),
    )

    private fun EditDocument.ids() = clips.map { it.id }

    @Test
    fun `dropping onto the left half of a clip goes before it`() {
        val doc = document()

        // Dragging a out of the way leaves b (0..2 s, midpoint 1 s) then c (2..5 s, midpoint 3.5 s),
        // laid out contiguously because the timeline has no gaps.
        assertThat(doc.reorderTargetIndex("a", 0L)).isEqualTo(0)
        assertThat(doc.reorderTargetIndex("a", 900_000L)).isEqualTo(0) // before b's midpoint
        assertThat(doc.reorderTargetIndex("a", 1_400_000L)).isEqualTo(1) // past it, before c's
        assertThat(doc.reorderTargetIndex("a", 4_000_000L)).isEqualTo(2) // past c's as well
    }

    @Test
    fun `dropping past the end lands last, however far past it is`() {
        val doc = document()

        assertThat(doc.reorderTargetIndex("a", 6 * oneSecond)).isEqualTo(2)
        assertThat(doc.reorderTargetIndex("a", 999 * oneSecond)).isEqualTo(2)
    }

    @Test
    fun `the dragged clip is not counted in its own landing`() {
        // The subtle one: while a clip is being dragged it is still IN the document, and it must not
        // occupy a slot in the arithmetic — otherwise every drop would be off by one past its old
        // position.
        val doc = document()

        assertThat(doc.reorderTargetIndex("b", 0L)).isEqualTo(0)
        assertThat(doc.reorderTargetIndex("b", 5 * oneSecond)).isEqualTo(2)
    }

    @Test
    fun `a single-clip timeline has exactly one slot`() {
        val one = EditDocument(
            id = "doc",
            name = "Doc",
            sources = listOf(source()),
            clips = listOf(clip("a", 1)),
        )

        assertThat(one.reorderTargetIndex("a", 0L)).isEqualTo(0)
        assertThat(one.reorderTargetIndex("a", 9 * oneSecond)).isEqualTo(0)
    }

    @Test
    fun `an id that is not in the document is refused by the command, not by the arithmetic`() {
        val doc = document()

        // The arithmetic treats an unknown id as "removing nothing", so its answer is merely a slot;
        // what matters is that the COMMAND refuses, so nothing moves and the document is untouched.
        assertThat(ReorderClip("nope", 1).apply(doc)).isEqualTo(doc)
        assertThat(ReorderClip("nope", 1).apply(doc).ids()).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `the marker sits where the clip would start after the move`() {
        val doc = document()

        // Index 0 -> at the start; 1 -> after a (1 s); 2 -> after a and b (3 s).
        assertThat(doc.reorderMarkerUs("c", 0)).isEqualTo(0L)
        assertThat(doc.reorderMarkerUs("c", 1)).isEqualTo(1 * oneSecond)
        assertThat(doc.reorderMarkerUs("c", 2)).isEqualTo(3 * oneSecond)
    }

    @Test
    fun `whatever index the drag computes, the document puts the clip there`() {
        // The invariant that keeps the marker honest, checked across every drop position in 100 ms
        // steps for each clip: the clip ends up at the index, and it is the same clip list either way.
        val doc = document()

        doc.clips.forEach { dragged ->
            var drop = 0L
            while (drop <= 7 * oneSecond) {
                val target = doc.reorderTargetIndex(dragged.id, drop)
                val after = ReorderClip(dragged.id, target).apply(doc)

                assertThat(after.ids()).containsExactly("a", "b", "c")
                assertThat(after.clips.indexOfFirst { it.id == dragged.id }).isEqualTo(target)
                drop += 100_000L
            }
        }
    }
}
