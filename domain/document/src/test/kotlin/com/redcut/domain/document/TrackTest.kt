package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The document's tracks, and the derived clip list that reads through them.
 *
 * ### What this file is defending
 *
 * The schema v2 change moved every clip into a track, and the rest of this module's suite (150-odd
 * tests) already asserts that none of the edit rules changed — this file is the other half: that the
 * model can say WHICH lane a clip is on, and that the flat list the rest of the code still reads is a
 * VIEW of the tracks rather than a second copy of them.
 *
 * The assertion that would catch a wrong implementation is the one that reads through both: if `clips`
 * were ever implemented as a stored field beside `tracks`, every test here could still pass while the
 * two drifted apart, so each test states what the track holds AND what the flattening says.
 */
class TrackTest {

    private val oneSecond = 1_000_000L

    private fun clipOf(id: String) = clip(id, "s1", 0L, oneSecond)

    private fun audioTrack(vararg clips: Clip) =
        Track(id = AUDIO_ID, kind = TrackKind.AUDIO, clips = clips.toList())

    @Test
    fun `a document built with no tracks is one empty video track`() {
        // The default, not a special case: every new document — and every document read from a file
        // that predates tracks — has a video lane waiting for its first clip. "No timeline at all" is
        // a different document, and one the model does not build by accident.
        val fresh = EditDocument(id = "doc", name = "Doc")

        assertThat(fresh.tracks).hasSize(1)
        assertThat(fresh.tracks.single().kind).isEqualTo(TrackKind.VIDEO)
        assertThat(fresh.tracks.single().id).isEqualTo(Track.MAIN_ID)
        assertThat(fresh.clips).isEmpty()
        assertThat(fresh.durationUs).isEqualTo(0L)
    }

    @Test
    fun `clips read back in track order, and then in the order the track holds them`() {
        val doc = EditDocument(
            id = "doc",
            name = "Doc",
            tracks = listOf(
                videoTrack(clipOf("v1"), clipOf("v2")),
                audioTrack(clipOf("a1")),
            ),
        )

        assertThat(doc.clips.map { it.id }).containsExactly("v1", "v2", "a1").inOrder()
    }

    @Test
    fun `an audio track's clips do not appear in the video track's order`() {
        // The failure this rules out is one a flat list cannot even express: an audio clip sitting
        // BETWEEN two video clips because the two lanes were never told apart.
        val doc = EditDocument(
            id = "doc",
            name = "Doc",
            tracks = listOf(
                videoTrack(clipOf("v1"), clipOf("v2")),
                audioTrack(clipOf("a1")),
            ),
        )

        assertThat(doc.trackById(Track.MAIN_ID)?.clips?.map { it.id })
            .containsExactly("v1", "v2")
            .inOrder()
        assertThat(doc.trackById(AUDIO_ID)?.clips?.map { it.id }).containsExactly("a1")
    }

    @Test
    fun `a clip is found by id wherever it is, and its track is named back`() {
        val doc = EditDocument(
            id = "doc",
            name = "Doc",
            sources = listOf(source("s1")),
            tracks = listOf(videoTrack(clipOf("v1")), audioTrack(clipOf("a1"))),
        )

        assertThat(doc.clipById("a1")).isEqualTo(clipOf("a1"))
        assertThat(doc.trackOf("a1")).isEqualTo(doc.trackById(AUDIO_ID))
        assertThat(doc.trackIdOf("a1")).isEqualTo(AUDIO_ID)
        assertThat(doc.trackIdOf("v1")).isEqualTo(Track.MAIN_ID)
    }

    @Test
    fun `a clip id no track holds has no track, and no lane answers for a clip it does not hold`() {
        // Both directions matter to the commands: "no such clip" has to be a refusal rather than a
        // guess, and a track must answer only for the clips it actually holds.
        val doc = EditDocument(
            id = "doc",
            name = "Doc",
            tracks = listOf(videoTrack(clipOf("v1")), audioTrack(clipOf("a1"))),
        )

        assertThat(doc.trackIdOf("ghost")).isNull()
        assertThat(doc.trackOf("ghost")).isNull()
        assertThat(doc.trackById("ghost")).isNull()
        assertThat(doc.trackById(Track.MAIN_ID)?.clipById("a1")).isNull()
    }

    @Test
    fun `a track must have an id`() {
        val thrown = runCatching { Track(id = "  ", kind = TrackKind.VIDEO) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the video lane helper builds the track every command writes to`() {
        assertThat(videoTrack(clipOf("v1"))).isEqualTo(
            Track(id = Track.MAIN_ID, kind = TrackKind.VIDEO, clips = listOf(clipOf("v1"))),
        )
        assertThat(videoTrack()).isEqualTo(Track.MAIN)
    }

    private companion object {
        /** A lane id that is NOT the video one, so a swapped track is visible rather than symmetric. */
        const val AUDIO_ID = "track-audio-main"
    }
}
