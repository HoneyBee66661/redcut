package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The merge precondition, and the reasons a merge is refused (FR-2.4).
 *
 * The spec is explicit about why this needs testing in both places it is read from: the button must be
 * disabled *and* explain itself when the precondition fails, "otherwise merge would silently drop
 * footage". So each case here asserts the reason the UI will show, not just that the answer was no.
 */
class ClipMergeTest {

    private val oneSecond = 1_000_000L

    private fun source(id: String = "src-1", durationUs: Long = 60 * oneSecond) = SourceRef(
        id = id,
        uri = "content://media/$id",
        displayName = "$id.mp4",
        durationUs = durationUs,
        width = 1920,
        height = 1080,
    )

    private fun clip(
        id: String,
        sourceInUs: Long,
        sourceOutUs: Long,
        sourceId: String = "src-1",
        speed: Float = 1f,
        reverse: Boolean = false,
    ) = Clip(
        id = id,
        sourceId = sourceId,
        sourceInUs = sourceInUs,
        sourceOutUs = sourceOutUs,
        speed = speed,
        reverse = reverse,
    )

    private fun document(vararg clips: Clip, sources: List<SourceRef> = listOf(source())) =
        EditDocument(
            id = "doc",
            name = "Doc",
            sources = sources,
            clips = clips.toList(),
        )

    private fun EditDocument.reasonFor(clipId: String): String =
        (mergeAvailability(clipId) as CutAvailability.Unavailable).reason

    // --- The run ------------------------------------------------------------

    @Test
    fun `a split clip fuses back into one, however many pieces it was split into`() {
        // Three contiguous pieces of one source: FR-2.4's "two or more", and the reason this walks
        // forwards rather than taking a fixed pair.
        val doc = document(
            clip("a", 0L, 2 * oneSecond),
            clip("b", 2 * oneSecond, 4 * oneSecond),
            clip("c", 4 * oneSecond, 6 * oneSecond),
        )

        assertThat(doc.mergeRunFrom("a").map { it.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(doc.mergeAvailability("a")).isEqualTo(CutAvailability.Available)
    }

    @Test
    fun `the run stops at the first clip that cannot join`() {
        val doc = document(
            clip("a", 0L, 2 * oneSecond),
            clip("b", 2 * oneSecond, 4 * oneSecond),
            clip("c", 10 * oneSecond, 12 * oneSecond),
        )

        // b joins a; c does not join b, so the run is a and b — and c is not dragged along by force.
        assertThat(doc.mergeRunFrom("a").map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(doc.mergeRunFrom("c").map { it.id }).containsExactly("c")
    }

    // --- The four reasons ---------------------------------------------------

    @Test
    fun `clips from different files say so`() {
        val doc = document(
            clip("a", 0L, 2 * oneSecond),
            clip("b", 2 * oneSecond, 4 * oneSecond, sourceId = "src-2"),
            sources = listOf(source(), source("src-2")),
        )

        assertThat(doc.reasonFor("a"))
            .isEqualTo("Merge needs clips from the same video; these come from different files.")
    }

    @Test
    fun `clips at different speeds say so, because merging would change how one of them plays`() {
        val doc = document(
            clip("a", 0L, 2 * oneSecond),
            clip("b", 2 * oneSecond, 4 * oneSecond, speed = 2f),
        )

        assertThat(doc.reasonFor("a"))
            .isEqualTo("Merge needs clips at the same speed; these play at different speeds.")
    }

    @Test
    fun `a reversed clip cannot join a forward one, and says which way is the problem`() {
        val doc = document(
            clip("a", 0L, 2 * oneSecond),
            clip("b", 2 * oneSecond, 4 * oneSecond, reverse = true),
        )

        assertThat(doc.reasonFor("a"))
            .isEqualTo("Merge needs clips pointing the same way; one of these is reversed.")
    }

    @Test
    fun `clips that are merely adjacent on the TIMELINE are refused, and told why`() {
        // The interesting refusal: on screen the two clips sit next to each other, and the reason they
        // cannot merge only exists inside the file — b starts 5 s into the source while a ends at 2 s.
        val doc = document(
            clip("a", 0L, 2 * oneSecond),
            clip("b", 5 * oneSecond, 7 * oneSecond),
        )

        assertThat(doc.reasonFor("a")).isEqualTo(
            "Merge needs clips that are continuous in the source: " +
                "one must end exactly where the other starts.",
        )
    }

    @Test
    fun `the last clip says there is nothing after it`() {
        val doc = document(clip("a", 0L, 2 * oneSecond))

        assertThat(doc.reasonFor("a"))
            .isEqualTo("This is the last clip; there is nothing after it to merge with.")
    }

    // --- The command --------------------------------------------------------

    @Test
    fun `a merged run spans the union of its source range and keeps the first clip's id`() {
        val doc = document(
            clip("a", 1 * oneSecond, 3 * oneSecond),
            clip("b", 3 * oneSecond, 5 * oneSecond),
        )

        val merged = doc.commandFor(CutTool.MERGE, 0L) { "unused" }!!.apply(doc)

        assertThat(merged.clips).hasSize(1)
        assertThat(merged.clips.single().id).isEqualTo("a")
        assertThat(merged.clips.single().sourceInUs).isEqualTo(1 * oneSecond)
        assertThat(merged.clips.single().sourceOutUs).isEqualTo(5 * oneSecond)
    }

    @Test
    fun `merge is unavailable exactly when the command would refuse`() {
        // The property the whole file exists for: the button and the command read the same rule, so
        // there is no state where the UI offers a merge the document will silently drop.
        val documents = listOf(
            document(clip("a", 0L, 2 * oneSecond)),
            document(clip("a", 0L, 2 * oneSecond), clip("b", 2 * oneSecond, 4 * oneSecond)),
            document(clip("a", 0L, 2 * oneSecond), clip("b", 5 * oneSecond, 7 * oneSecond)),
            document(
                clip("a", 0L, 2 * oneSecond),
                clip("b", 2 * oneSecond, 4 * oneSecond, speed = 0.5f),
            ),
        )

        documents.forEach { doc ->
            val available = doc.mergeAvailability("a") is CutAvailability.Available
            val command = doc.commandFor(CutTool.MERGE, 0L) { "unused" }
            assertThat(command != null).isEqualTo(available)

            // And when it IS available, applying it actually removes a clip rather than returning the
            // document unchanged (which is how MergeClips reports a refused merge).
            if (command != null) {
                assertThat(command.apply(doc).clips.size).isLessThan(doc.clips.size)
            }
        }
    }

    @Test
    fun `an unavailable merge produces no command at all`() {
        val doc = document(clip("a", 0L, 2 * oneSecond))

        assertThat(doc.commandFor(CutTool.MERGE, 0L) { "unused" }).isNull()
    }
}
