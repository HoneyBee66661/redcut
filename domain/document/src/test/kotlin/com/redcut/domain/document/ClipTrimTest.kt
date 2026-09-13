package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The trim intent, and how it meets the trim command's clamping (FR-2.1).
 *
 * Two responsibilities, deliberately in two places, and these tests are where that seam is checked:
 * [Clip.trimmedTo] says what the user ASKED for (one edge moves, the other is held), and [TrimClip]
 * decides what they GET (source bounds, the 100 ms floor, a guard for a source shorter than a clip).
 * If both clamped, a drag past the end of the source would freeze silently with nothing to tell the
 * UI to stop following the finger.
 */
class ClipTrimTest {

    private val oneSecond = 1_000_000L

    /** A clip reading 4 s..16 s of a 20 s source, at 1x, not reversed. */
    private fun clip(
        sourceInUs: Long = 4 * oneSecond,
        sourceOutUs: Long = 16 * oneSecond,
        speed: Float = 1f,
        reverse: Boolean = false,
    ) = Clip(
        id = "clip-1",
        sourceId = "src-1",
        sourceInUs = sourceInUs,
        sourceOutUs = sourceOutUs,
        speed = speed,
        reverse = reverse,
    )

    private fun document(clip: Clip = clip()) = EditDocument(
        id = "doc",
        name = "Doc",
        sources = listOf(
            SourceRef(
                id = "src-1",
                uri = "content://media/1",
                displayName = "clip.mp4",
                durationUs = 20 * oneSecond,
                width = 1920,
                height = 1080,
            ),
        ),
        tracks = listOf(videoTrack(clip)),
    )

    // --- The intent --------------------------------------------------------

    @Test
    fun `dragging the in-point moves only the in-point`() {
        val intent = clip().trimmedTo(ClipEdge.IN, 7 * oneSecond)

        assertThat(intent).isEqualTo(7 * oneSecond to 16 * oneSecond)
    }

    @Test
    fun `dragging the out-point moves only the out-point`() {
        val intent = clip().trimmedTo(ClipEdge.OUT, 9 * oneSecond)

        assertThat(intent).isEqualTo(4 * oneSecond to 9 * oneSecond)
    }

    // --- The command's clamping, reached through the intent ---------------

    @Test
    fun `a drag past the start of the source clamps instead of going negative`() {
        // The intent is what the user asked for (a negative in-point is not a document change), and
        // the command is what happens: the clip starts at the beginning of the source.
        val (intentIn, intentOut) = clip().trimmedTo(ClipEdge.IN, -3 * oneSecond)
        assertThat(intentIn).isEqualTo(-3 * oneSecond)

        val trimmed = TrimClip(
            VIDEO,
            "clip-1",
            intentIn,
            intentOut,
        ).apply(document()).clipById("clip-1")!!

        assertThat(trimmed.sourceInUs).isEqualTo(0L)
        assertThat(trimmed.sourceOutUs).isEqualTo(16 * oneSecond)
    }

    @Test
    fun `a drag past the end of the source clamps instead of running off it`() {
        val (intentIn, intentOut) = clip().trimmedTo(ClipEdge.OUT, 30 * oneSecond)

        val trimmed = TrimClip(
            VIDEO,
            "clip-1",
            intentIn,
            intentOut,
        ).apply(document()).clipById("clip-1")!!

        // The source is 20 s long: the clip cannot read past it however far the finger went.
        assertThat(trimmed.sourceOutUs).isEqualTo(20 * oneSecond)
    }

    @Test
    fun `a drag that crosses the other edge still yields a valid clip`() {
        // Dragging the out-point back past the in-point is what a finger does at the end of a gesture.
        // The result is the 100 ms floor, not an inverted range that would break every downstream
        // calculation that assumes out > in.
        val (intentIn, intentOut) = clip().trimmedTo(ClipEdge.OUT, 2 * oneSecond)

        val trimmed = TrimClip(
            VIDEO,
            "clip-1",
            intentIn,
            intentOut,
        ).apply(document()).clipById("clip-1")!!

        assertThat(trimmed.sourceOutUs - trimmed.sourceInUs).isEqualTo(Clip.MIN_DURATION_US)
    }

    @Test
    fun `a trim snaps to the floor rather than deleting the clip`() {
        // FR-2's rule for trim specifically: it CLAMPS. (Split and cut are the ones that delete a
        // piece that would fall below the floor.) A trim gesture that deleted a clip because the
        // finger went 20 ms too far would be unrecoverable mid-drag.
        val (intentIn, intentOut) = clip().trimmedTo(ClipEdge.IN, 16 * oneSecond - 20_000L)

        val trimmed = TrimClip(
            VIDEO,
            "clip-1",
            intentIn,
            intentOut,
        ).apply(document()).clipById("clip-1")!!

        assertThat(trimmed).isNotNull()
        assertThat(trimmed.timelineDurationUs).isEqualTo(Clip.MIN_DURATION_US)
    }

    // --- Timeline position to source time ---------------------------------

    @Test
    fun `an edge drag lands on a source time, not a timeline position`() {
        // The clip reads 4 s..16 s. One second into the clip is source 5 s.
        assertThat(clip().sourceTimeFor(oneSecond)).isEqualTo(5 * oneSecond)
    }

    @Test
    fun `a sped-up clip maps the drag differently, which is why the clip does the mapping`() {
        // At 2x, one second of timeline is two seconds of source: a trim that used the timeline
        // position directly would land on the wrong frame, and would be wrong by more the faster the
        // clip plays.
        val doubled = clip(speed = 2f)

        assertThat(doubled.sourceTimeFor(oneSecond)).isEqualTo(6 * oneSecond)
    }

    @Test
    fun `a reversed clip maps the drag from the other end`() {
        val reversed = clip(reverse = true)

        assertThat(reversed.sourceTimeFor(oneSecond)).isEqualTo(15 * oneSecond)
    }

    private fun EditDocument.clipById(id: String): Clip? = clips.firstOrNull { it.id == id }
}
