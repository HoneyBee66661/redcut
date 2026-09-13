package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Every command names the track it touches, and refuses when the clip is not there.
 *
 * ### The bug this file exists to prevent
 *
 * Clip ids are unique across the document, so a command COULD look a clip up without a lane and find
 * it — and then apply a trim, a delete or a ripple to a lane the user is not looking at, because the id
 * arrived from a frame the timeline has since moved past (an undo, a ripple, a scroll). On one flat
 * list there was nowhere for that to go wrong; with two lanes it is one forgotten parameter away.
 *
 * So the assertions come in two shapes. [a command that names the wrong lane changes nothing] walks the
 * whole command vocabulary through the failure; the rest pin the behaviour that must survive once the
 * lane is right — that a command acts on ONE track and leaves the other exactly as it was, and that
 * "next to each other" no longer means "mergeable" when the two clips are on different lanes.
 */
class TrackScopedCommandsTest {

    private fun document(
        video: List<Clip> =
            listOf(clip("v1", "s1", 0L, 2 * SEC), clip("v2", "s1", 2 * SEC, 4 * SEC)),
        audio: List<Clip> = listOf(clip("a1", "s1", 0L, 4 * SEC)),
    ) = EditDocument(
        id = "doc",
        name = "Doc",
        sources = listOf(source("s1")),
        tracks = listOf(videoTrack(video), Track(AUDIO, TrackKind.AUDIO, audio)),
    )

    /** Every command that addresses a clip by id, aimed at one lane. */
    private fun commandsFor(trackId: String, clipId: String) = listOf(
        TrimClip(trackId, clipId, 0L, SEC),
        SplitClip(trackId, clipId, SEC, "$clipId-b"),
        CutLeft(trackId, clipId, SEC),
        CutRight(trackId, clipId, SEC),
        DeleteClip(trackId, clipId),
        DuplicateClip(trackId, clipId, "$clipId-copy"),
        ReorderClip(trackId, clipId, 1),
        MergeClips(trackId, listOf(clipId, "v2")),
        SetSpeed(trackId, clipId, 2f),
        SetVolume(trackId, clipId, 0.5f),
        SetMuted(trackId, clipId, true),
        SetFades(trackId, clipId, 100L, 100L),
        SetReverse(trackId, clipId, true),
    )

    @Test
    fun `a command that names the wrong lane changes nothing`() {
        // `v1` is real and every command below is otherwise valid: only the lane is wrong, which is
        // exactly what a stale id produces. Each must be a no-op rather than an edit elsewhere.
        val doc = document()

        commandsFor(AUDIO, "v1").forEach { command ->
            assertSame("$command should not have touched the document", doc, command.apply(doc))
        }
    }

    @Test
    fun `a command that names a lane the document does not have changes nothing`() {
        val doc = document()

        commandsFor("no-such-track", "v1").forEach { command ->
            assertSame(doc, command.apply(doc))
        }
        assertSame(doc, AppendClip("no-such-track", "new", "s1", 0L, SEC).apply(doc))
    }

    @Test
    fun `a command acts on its own lane and leaves the other one exactly as it was`() {
        // The audio clip is the video lane's neighbour, and NOTHING the user does to the picture may
        // move it: ripple is per track, which is what keeps a delete on the video lane from dragging
        // the sound out of sync.
        val doc = document()

        val after = listOf(
            TrimClip(VIDEO, "v1", 0L, SEC),
            DeleteClip(VIDEO, "v1"),
            SplitClip(VIDEO, "v2", 3 * SEC, "v2b"),
            ReorderClip(VIDEO, "v1", 1),
        ).fold(doc) { current, command -> command.apply(current) }

        assertThat(after.trackById(AUDIO)?.clips).isEqualTo(doc.trackById(AUDIO)?.clips)
    }

    @Test
    fun `a clip can move between lanes only by being placed there, never by a cut`() {
        // A split's halves, a duplicate and a reordered clip all stay on the lane they were addressed
        // on, however the indices move: "independent entity" (the user's words for a split) means
        // independent of its siblings, not free to change lanes.
        val doc = document()

        val split = SplitClip(VIDEO, "v1", SEC, "v1b").apply(doc)
        val duplicated = DuplicateClip(AUDIO, "a1", "a1copy").apply(doc)

        assertThat(split.trackById(VIDEO)?.clips?.map { it.id })
            .containsExactly("v1", "v1b", "v2")
            .inOrder()
        assertThat(split.trackById(AUDIO)?.clips?.map { it.id }).containsExactly("a1")
        assertThat(duplicated.trackById(AUDIO)?.clips?.map { it.id })
            .containsExactly("a1", "a1copy")
            .inOrder()
        assertThat(duplicated.trackById(VIDEO)?.clips?.map { it.id })
            .containsExactly("v1", "v2")
            .inOrder()
    }

    @Test
    fun `reorder counts within one lane, not across the document`() {
        // Every index a drag can produce is an index into the lane the finger is on. Counting across
        // the flattened document would let a drag on the video lane land the clip on the audio one.
        val doc = document()

        val after = ReorderClip(VIDEO, "v1", 99).apply(doc)

        assertThat(
            after.trackById(VIDEO)?.clips?.map { it.id },
        ).containsExactly("v2", "v1").inOrder()
        assertThat(after.trackById(AUDIO)?.clips?.map { it.id }).containsExactly("a1")
    }

    @Test
    fun `clips that are neighbours in the flattened list but on different lanes do not merge`() {
        // The failure a flat list cannot even express: `v1` ends at source 2 s and `a1` starts exactly
        // there, so the source-side precondition holds for the PAIR — and merging them would fuse a
        // picture clip with a sound clip onto one lane. The lane is checked first, so it cannot.
        val doc = document(
            video = listOf(clip("v1", "s1", 0L, 2 * SEC)),
            audio = listOf(clip("a1", "s1", 2 * SEC, 4 * SEC)),
        )

        assertSame(doc, MergeClips(VIDEO, listOf("v1", "a1")).apply(doc))
        assertThat(doc.reasonForMerge(VIDEO, "v1")).isEqualTo(NOTHING_AFTER)
    }

    @Test
    fun `an emptied lane is legal, an emptied document is not`() {
        // One clip per lane: deleting the picture leaves a document that still renders — an audio lane
        // with no video above it is the audio workstream's shape — while deleting the last clip of the
        // whole document is still refused.
        val doc = document(
            video = listOf(clip("v1", "s1", 0L, 2 * SEC)),
            audio = listOf(clip("a1", "s1", 0L, 2 * SEC)),
        )

        val emptied = DeleteClip(VIDEO, "v1").apply(doc)

        assertThat(emptied.trackById(VIDEO)?.clips).isEmpty()
        assertThat(emptied.clips).hasSize(1)
        assertSame(emptied, DeleteClip(AUDIO, "a1").apply(emptied))
    }

    @Test
    fun `an appended clip lands on the lane it names, not on the first one`() {
        val doc = document()

        val appended = AppendClip(AUDIO, "a2", "s1", 4 * SEC, 6 * SEC).apply(doc)

        assertThat(appended.trackIdOf("a2")).isEqualTo(AUDIO)
        assertThat(appended.trackById(AUDIO)?.clips?.map { it.id })
            .containsExactly("a1", "a2")
            .inOrder()
        assertThat(appended.trackById(VIDEO)?.clips?.map { it.id })
            .containsExactly("v1", "v2")
            .inOrder()
    }

    @Test
    fun `a clip id another lane already holds is refused`() {
        // Not a nicety: ids are unique across the DOCUMENT, not per lane — every effect names a clip by
        // id alone, so two clips sharing one would make `clipById`, and therefore the effect, ambiguous.
        val doc = document()

        assertSame(doc, AppendClip(AUDIO, "v1", "s1", 0L, SEC).apply(doc))
        assertSame(doc, DuplicateClip(AUDIO, "a1", "v1").apply(doc))
    }

    @Test
    fun `undo restores both lanes, because a snapshot is the whole document`() {
        // The undo stack is not track-aware and does not need to be: it stores documents, and the
        // tracks are part of one. What this pins is that a track-scoped command is still ONE entry, and
        // that reversing it brings back the lane it touched without disturbing the other.
        val doc = document()
        val stack = UndoStack(doc)

        stack.execute(DeleteClip(VIDEO, "v1"))
        assertThat(stack.current.clipById("v1")).isNull()

        stack.undo()

        assertThat(stack.current).isEqualTo(doc)
        assertThat(stack.current.trackById(AUDIO)?.clips?.map { it.id }).containsExactly("a1")

        stack.redo()

        assertThat(stack.current.clipById("v1")).isNull()
        assertThat(stack.current.trackById(AUDIO)?.clips?.map { it.id }).containsExactly("a1")
    }

    @Test
    fun `a command on the wrong lane records no history entry`() {
        // A no-op that still consumed an undo slot would make Undo look broken: one tap, nothing
        // happens, and the user's previous edit is one step further away.
        val stack = UndoStack(document())

        stack.execute(DeleteClip(AUDIO, "v1"))

        assertThat(stack.canUndo).isFalse()
    }

    /** The reason [mergeAvailability] gives, which the toolbar shows verbatim. */
    private fun EditDocument.reasonForMerge(trackId: String, clipId: String): String =
        (mergeAvailability(trackId, clipId) as CutAvailability.Unavailable).reason

    private companion object {
        const val AUDIO = "track-audio-main"

        const val NOTHING_AFTER = "This is the last clip; there is nothing after it to merge with."
    }
}
