package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Which Cut tools are available where, and what they mean (FR-2.2–2.6).
 *
 * The point of testing this in the domain rather than in a screen: the rules are the commands' own
 * rules read back, and a button that offers an action the command will refuse is the classic way an
 * editor feels broken. Every case below asks the same two questions of one document — "is it
 * available" and "what does it do" — so the two can never drift apart unnoticed.
 */
class CutToolsTest {

    private val oneSecond = 1_000_000L

    private fun source(durationUs: Long = 20 * oneSecond) = SourceRef(
        id = "src-1",
        uri = "content://media/1",
        displayName = "clip.mp4",
        durationUs = durationUs,
        width = 1920,
        height = 1080,
    )

    private fun clip(id: String, sourceInUs: Long, sourceOutUs: Long, sourceId: String = "src-1") =
        Clip(id = id, sourceId = sourceId, sourceInUs = sourceInUs, sourceOutUs = sourceOutUs)

    /** Two 4-second clips: 0–4 s and 4–8 s of timeline. */
    private fun twoClips() = EditDocument(
        id = "doc",
        name = "Doc",
        sources = listOf(source()),
        clips = listOf(
            clip("clip-a", 0L, 4 * oneSecond),
            clip("clip-b", 8 * oneSecond, 12 * oneSecond),
        ),
    )

    private fun EditDocument.clipIds() = clips.map { it.id }

    // --- Where the playhead is ---------------------------------------------

    @Test
    fun `the clip at the playhead is the one whose span contains it`() {
        val doc = twoClips()

        assertThat(doc.clipAt(0L)?.id).isEqualTo("clip-a")
        assertThat(doc.clipAt(3 * oneSecond)?.id).isEqualTo("clip-a")
        assertThat(doc.clipAt(6 * oneSecond)?.id).isEqualTo("clip-b")
        assertThat(doc.clipAt(8 * oneSecond)).isNull()
    }

    @Test
    fun `a playhead exactly on a cut point belongs to the clip it is about to start`() {
        // The same rule the timeline's hit-testing uses for a clip edge, and it is the one that makes
        // "cut right" at a cut point mean something: it trims the clip the playhead is sitting on,
        // not the one that just ended.
        val doc = twoClips()

        assertThat(doc.clipAt(4 * oneSecond)?.id).isEqualTo("clip-b")
        assertThat(doc.offsetIntoClip("clip-b", 4 * oneSecond)).isEqualTo(0L)
    }

    @Test
    fun `an offset into a clip is timeline time, and null when the playhead is not on it`() {
        val doc = twoClips()

        assertThat(doc.offsetIntoClip("clip-b", 6 * oneSecond)).isEqualTo(2 * oneSecond)
        assertThat(doc.offsetIntoClip("clip-a", 6 * oneSecond)).isNull()
        assertThat(doc.timelineStartOf("clip-b")).isEqualTo(4 * oneSecond)
        assertThat(doc.timelineStartOf("clip-nope")).isNull()
    }

    // --- Availability ------------------------------------------------------

    @Test
    fun `an empty document offers nothing, and says why`() {
        val empty = EditDocument(id = "doc", name = "Doc")

        val availability = empty.availabilityFor(CutTool.SPLIT, 0L)

        assertThat(availability).isInstanceOf(CutAvailability.Unavailable::class.java)
        assertThat((availability as CutAvailability.Unavailable).reason)
            .isEqualTo("Import a video to start cutting.")
    }

    @Test
    fun `a playhead past the end explains itself instead of silently disabling four buttons`() {
        val availability = twoClips().availabilityFor(CutTool.SPLIT, 9 * oneSecond)

        assertThat((availability as CutAvailability.Unavailable).reason)
            .isEqualTo("Move the playhead onto a clip; it is past the end of the timeline.")
    }

    @Test
    fun `split needs room for two clips, not one`() {
        val doc = twoClips()

        assertThat(
            doc.availabilityFor(CutTool.SPLIT, 2 * oneSecond),
        ).isEqualTo(CutAvailability.Available)
        // 200 ms into the clip: the head would survive, the tail would not.
        assertThat(doc.availabilityFor(CutTool.SPLIT, 4 * oneSecond - 20_000L))
            .isInstanceOf(CutAvailability.Unavailable::class.java)
        // A playhead exactly on the cut point belongs to clip-b (the clip it is about to start), and
        // there it sits at its very first millisecond — so a split is too close to the edge.
        assertThat(doc.availabilityFor(CutTool.SPLIT, 4 * oneSecond))
            .isInstanceOf(CutAvailability.Unavailable::class.java)
    }

    @Test
    fun `the minimum duration is the floor on both sides of a split`() {
        val doc = twoClips()

        // 80 ms in: the head is below the floor.
        assertThat(doc.availabilityFor(CutTool.SPLIT, 80_000L))
            .isInstanceOf(CutAvailability.Unavailable::class.java)
        // 100 ms in: both halves meet it exactly, so this is the first legal split.
        assertThat(
            doc.availabilityFor(CutTool.SPLIT, 100_000L),
        ).isEqualTo(CutAvailability.Available)
    }

    @Test
    fun `cut left and cut right stay available near an edge, because there they mean delete`() {
        val doc = twoClips()

        // FR-2's floor rule: a clip that would fall below 100 ms is deleted instead, so the user's
        // request ("remove everything on this side") is still satisfied rather than refused.
        assertThat(
            doc.availabilityFor(CutTool.CUT_LEFT, 50_000L),
        ).isEqualTo(CutAvailability.Available)
        assertThat(
            doc.availabilityFor(CutTool.CUT_RIGHT, 50_000L),
        ).isEqualTo(CutAvailability.Available)
    }

    @Test
    fun `delete refuses on the last clip, because an empty timeline cannot be played or undone`() {
        val one = EditDocument(
            id = "doc",
            name = "Doc",
            sources = listOf(source()),
            clips = listOf(clip("clip-a", 0L, 4 * oneSecond)),
        )

        assertThat(one.availabilityFor(CutTool.DELETE, oneSecond))
            .isInstanceOf(CutAvailability.Unavailable::class.java)
        assertThat(
            twoClips().availabilityFor(CutTool.DELETE, oneSecond),
        ).isEqualTo(CutAvailability.Available)
    }

    // --- The command a tool means ------------------------------------------

    @Test
    fun `split builds a split with a fresh id, and does not mint one when unavailable`() {
        val doc = twoClips()
        var minted = 0
        val ids = {
            minted++
            "clip-new"
        }

        val command = doc.commandFor(CutTool.SPLIT, 2 * oneSecond, ids)

        assertThat(command).isEqualTo(SplitClip("clip-a", 2 * oneSecond, "clip-new"))
        assertThat(minted).isEqualTo(1)

        // Past the end: no command, and no id invented for one either.
        assertThat(doc.commandFor(CutTool.SPLIT, 9 * oneSecond, ids)).isNull()
        assertThat(minted).isEqualTo(1)
    }

    @Test
    fun `cut tools act at the SOURCE time of the playhead`() {
        // clip-b reads 8 s..12 s of its source; two seconds into it is source 10 s.
        val command = twoClips().commandFor(CutTool.CUT_LEFT, 6 * oneSecond) { "unused" }

        assertThat(command).isEqualTo(CutLeft("clip-b", 10 * oneSecond))
    }

    @Test
    fun `cut left keeps the clip and drops the head`() {
        val after = twoClips().commandFor(CutTool.CUT_LEFT, 6 * oneSecond) { "unused" }!!
            .apply(twoClips())

        // The surviving tail keeps the original id (see CutLeft): a literal split-and-delete would
        // orphan every effect scoped to this clip and drop the user's selection.
        assertThat(after.clipIds()).containsExactly("clip-a", "clip-b").inOrder()
        assertThat(after.clips[1].sourceInUs).isEqualTo(10 * oneSecond)
        assertThat(after.clips[1].sourceOutUs).isEqualTo(12 * oneSecond)
    }

    @Test
    fun `delete ripples the gap closed rather than leaving a hole`() {
        val doc = twoClips()

        val after = doc.commandFor(CutTool.DELETE, 6 * oneSecond) { "unused" }!!.apply(doc)

        // MVP is ripple-only: the second clip moves left to fill the gap, so playback has no black.
        assertThat(after.clipIds()).containsExactly("clip-a")
        assertThat(after.timelineDurationOfClips()).isEqualTo(4 * oneSecond)
    }

    @Test
    fun `split then delete of the left half equals delete of the right half's timeline`() {
        // The spec's "cut left ≡ split + delete" identity, checked through the commands the UI will
        // actually build: split at the playhead, delete the left half, and the remaining clip starts
        // where the cut was.
        val doc = twoClips()
        val split = doc.commandFor(CutTool.SPLIT, 2 * oneSecond) { "clip-new" }!!
        val afterSplit = split.apply(doc)
        val afterDeleteLeft = DeleteClip("clip-a").apply(afterSplit)

        // The ripple closes the gap: clip-b (the one that was never touched) follows the second half
        // of the split immediately.
        assertThat(afterDeleteLeft.clipIds()).containsExactly("clip-new", "clip-b").inOrder()

        val cutLeft = doc.commandFor(CutTool.CUT_LEFT, 2 * oneSecond) { "unused" }!!.apply(doc)
        assertThat(cutLeft.clips.first().sourceInUs)
            .isEqualTo(afterDeleteLeft.clips.first().sourceInUs)
    }

    private fun EditDocument.timelineDurationOfClips(): Long = clips.sumOf { it.timelineDurationUs }
}
