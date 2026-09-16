package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Merging a whole LANE (FR-2.4, UI revision 2 §WS E / Task E2).
 *
 * The rule under test is not "merge" — [ClipMergeTest] owns the precondition — but the walk that decides
 * which GROUPS of neighbours on a lane fuse: every maximal run, and never across a hole or a clip that
 * cannot join. A lane-level merge that got this wrong would be worse than a refused one: it would report
 * one history entry called "Merge lane" while silently deleting a gap or restyling a clip.
 *
 * `:domain:document` is the pure tier, so these run locally in milliseconds — which is the reason the
 * lane's arithmetic lives here rather than behind a finger.
 */
class MergeTrackClipsTest {

    private fun source(id: String) = SourceRef(
        id = id,
        uri = "content://media/$id",
        displayName = "$id.mp4",
        durationUs = 60 * SEC,
        width = 1920,
        height = 1080,
    )

    /** A lane holding [items] on [VIDEO], with both sources present so either can be named. */
    private fun documentWith(items: List<TrackItem>): EditDocument = EditDocument(
        id = "doc",
        name = "Doc",
        sources = listOf(source("s1"), source("s2")),
        tracks = listOf(Track(id = VIDEO, kind = TrackKind.VIDEO, items = items)),
    )

    /** Two clips of one source, source-contiguous: the pair that fuses. */
    private val pair = listOf(clip("c1", "s1", 0L, 2 * SEC), clip("c2", "s1", 2 * SEC, 5 * SEC))

    @Test
    fun `every fusable run on the lane becomes one clip`() {
        val after = MergeTrackClips(VIDEO).apply(documentWith(pair))

        assertThat(after.tracks.single().items).hasSize(1)
        val merged = after.clips.single()
        // The run's HEAD survives, with its source range extended to cover what joined it — the same rule
        // MergeClips applies to a run the user picked, which is what stops the two from disagreeing.
        assertThat(merged.id).isEqualTo("c1")
        assertThat(merged.sourceInUs).isEqualTo(0L)
        assertThat(merged.sourceOutUs).isEqualTo(5 * SEC)
    }

    @Test
    fun `a lane holding one clip is left exactly as it was, and records nothing`() {
        // Nothing to fuse is a no-op in the same shape as a failed precondition: the SAME document, not an
        // equal one, and no history entry. A "Merge lane" the user can undo that changed nothing is the
        // failure mode this asserts against.
        val before = documentWith(listOf(clip("c1", "s1", 0L, 2 * SEC)))
        val stack = UndoStack(before)

        val after = stack.execute(MergeTrackClips(VIDEO))

        assertThat(after).isSameInstanceAs(before)
        assertThat(stack.canUndo).isFalse()
    }

    @Test
    fun `a mixed lane fuses each run rather than refusing the whole lane`() {
        // The normal case the moment a project has two files in it: c1+c2 come from s1 and c3 from s2. A
        // lane-level merge that refused whenever any pair cannot join would be useless exactly then.
        val lane = pair + clip("c3", "s2", 0L, 3 * SEC)

        val after = MergeTrackClips(VIDEO).apply(documentWith(lane))

        assertThat(after.clips.map { it.id }).containsExactly("c1", "c3").inOrder()
        assertThat(after.clips.first().sourceOutUs).isEqualTo(5 * SEC)
        assertThat(after.clips.last().sourceOutUs).isEqualTo(3 * SEC)
    }

    @Test
    fun `clips that are not continuous in the source stay separate`() {
        // c2 starts at 3 s of the source, but c1 ends at 2 s: there is a second of footage between them that
        // merging would DROP. This is the case FR-2.4's precondition exists for, and the lane walk has to
        // honour it per pair rather than per lane.
        val lane = listOf(clip("c1", "s1", 0L, 2 * SEC), clip("c2", "s1", 3 * SEC, 5 * SEC))

        val after = MergeTrackClips(VIDEO).apply(documentWith(lane))

        assertThat(after.clips.map { it.id }).containsExactly("c1", "c2").inOrder()
    }

    @Test
    fun `a gap ends a run, and survives the merge`() {
        // c1 and c2 WOULD fuse — same source, source-contiguous — but the user has a hole between them. The
        // gap is an item, so the lane's write path has to carry items: rebuilding the lane from its clips
        // would delete the hole while reporting an edit called "Merge lane".
        val lane = listOf<Clip>(clip("c1", "s1", 0L, 2 * SEC))
            .plus(Gap(2 * SEC))
            .plus(clip("c2", "s1", 2 * SEC, 5 * SEC))
        val before = documentWith(lane)

        val after = MergeTrackClips(VIDEO).apply(before)

        assertThat(after).isSameInstanceAs(before)
        assertThat(after.tracks.single().items).hasSize(3)
    }

    @Test
    fun `the gap also stops the run on the far side from joining this one`() {
        // The other direction of the same rule, and the one a walk can get wrong while still keeping the
        // gap: c3 is contiguous with c1 in the SOURCE, but the hole is between them on the timeline.
        val lane = listOf<TrackItem>(clip("c1", "s1", 0L, 2 * SEC), Gap(2 * SEC))
            .plus(clip("c3", "s1", 2 * SEC, 4 * SEC))

        val after = MergeTrackClips(VIDEO).apply(documentWith(lane))

        assertThat(after.tracks.single().items).hasSize(3)
        assertThat(after.clips.map { it.id }).containsExactly("c1", "c3").inOrder()
    }

    @Test
    fun `a lane of two different sources is refused with a reason, not silently`() {
        // The availability rule the toolbar's button reads (WS E3), and the reason is the string the user
        // sees: "disabled" without an explanation is the thing FR-2 asks to avoid.
        val lane = listOf(clip("c1", "s1", 0L, 2 * SEC), clip("c2", "s2", 0L, 2 * SEC))

        val availability = documentWith(lane).mergeTrackAvailability(VIDEO)

        assertThat(availability).isInstanceOf(CutAvailability.Unavailable::class.java)
        assertThat((availability as CutAvailability.Unavailable).reason).isNotEmpty()
    }

    @Test
    fun `a lane with something to fuse is available`() {
        assertThat(documentWith(pair).mergeTrackAvailability(VIDEO))
            .isEqualTo(CutAvailability.Available)
    }

    @Test
    fun `an unknown lane is refused, both by the command and by the rule`() {
        val before = documentWith(pair)
        val stack = UndoStack(before)

        assertThat(
            stack.execute(MergeTrackClips("track-that-never-existed")),
        ).isSameInstanceAs(before)
        assertThat(stack.canUndo).isFalse()
        assertThat(before.mergeTrackAvailability("track-that-never-existed"))
            .isInstanceOf(CutAvailability.Unavailable::class.java)
    }
}
