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
        Track(id = AUDIO_ID, kind = TrackKind.AUDIO, items = clips.toList())

    /** The exception a lane or an item is refused with, or null when the model allows the value. */
    private fun refusal(build: () -> Any?): Throwable? = runCatching(build).exceptionOrNull()

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
            Track(id = Track.MAIN_ID, kind = TrackKind.VIDEO, items = listOf(clipOf("v1"))),
        )
        assertThat(videoTrack()).isEqualTo(Track.MAIN)
    }

    // --- The v3 lane: attributes, items, and the rule about which lane may carry which attribute ---

    @Test
    fun `a lane's attributes default to the neutral reading`() {
        // The default has to mean "nothing decided": a v2 lane arrives through the migration with these
        // values and nothing else, and a level nobody chose would be a mix the user never made.
        val fresh = Track(id = "track-1", kind = TrackKind.VIDEO)

        assertThat(fresh.items).isEmpty()
        assertThat(fresh.isLocked).isFalse()
        assertThat(fresh.isVisible).isTrue()
        assertThat(fresh.isMuted).isFalse()
        assertThat(fresh.isSolo).isFalse()
        assertThat(fresh.volume).isEqualTo(1f)
        assertThat(fresh.pan).isEqualTo(0f)
        assertThat(fresh.opacity).isEqualTo(1f)
        assertThat(fresh.blendMode).isEqualTo(BlendMode.NORMAL)
        assertThat(fresh.isCollapsed).isFalse()
    }

    @Test
    fun `a gap has to have a duration`() {
        // A zero-length gap is not a hole, it is nothing at all — an element the prefix sum would have
        // to account for while it accounts for no time.
        assertThat(refusal { Gap(0L) }).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(refusal { Gap(-oneSecond) }).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the clips view reads past the gaps, in order`() {
        // The failure this rules out is the one a gap is most likely to cause: a lane whose holes moved
        // a clip or hid it from the flat reading the compiler and the frame stepping still use.
        val track = Track(
            id = Track.MAIN_ID,
            kind = TrackKind.VIDEO,
            items = listOf(clipOf("v1"), Gap(oneSecond), clipOf("v2")),
        )

        assertThat(track.items).hasSize(3)
        assertThat(track.clips.map { it.id }).containsExactly("v1", "v2").inOrder()
        assertThat(track.clipById("v2")).isEqualTo(clipOf("v2"))
        assertThat(track.clipById("v3")).isNull()
    }

    @Test
    fun `a picture lane may not carry the attributes of sound`() {
        // volume, pan, mute and solo describe a lane's SOUND, and a VIDEO or TEXT_OVERLAY lane has none of
        // its own: set there, they would be state the render path has to invent a meaning for.
        assertThat(refusal { Track(id = "t", kind = TrackKind.VIDEO, volume = 0.5f) })
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(refusal { Track(id = "t", kind = TrackKind.VIDEO, pan = 0.5f) })
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(refusal { Track(id = "t", kind = TrackKind.VIDEO, isMuted = true) })
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(refusal { Track(id = "t", kind = TrackKind.TEXT_OVERLAY, isSolo = true) })
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a sound lane may not carry the attributes of picture`() {
        assertThat(refusal { Track(id = "t", kind = TrackKind.AUDIO, opacity = 0.5f) })
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            refusal { Track(id = "t", kind = TrackKind.AUDIO, blendMode = BlendMode.MULTIPLY) },
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the attributes a lane's kind does allow are ordinary documents`() {
        // The other half of the rule, and the half a rule that was too eager would break: a mixed audio
        // lane is an edit the user made, and a lane attribute the schema has to be able to express.
        assertThat(
            refusal {
                Track(
                    id = "t",
                    kind = TrackKind.AUDIO,
                    volume = 0.5f,
                    pan = -0.5f,
                    isMuted = true,
                    isSolo = true,
                )
            },
        ).isNull()
        assertThat(refusal { Track(id = "t", kind = TrackKind.VIDEO, opacity = 0.5f) }).isNull()
        assertThat(
            refusal { Track(id = "t", kind = TrackKind.VIDEO, blendMode = BlendMode.SCREEN) },
        ).isNull()
        assertThat(refusal { Track(id = "t", kind = TrackKind.ADJUSTMENT, opacity = 0.5f) })
            .isNull()
    }

    @Test
    fun `the lane's numbers are checked where they are set`() {
        // The boundary rule this module keeps everywhere else: a file carrying a pan of 1.5 is refused
        // where it is read rather than drawn as hard right.
        assertThat(refusal { Track(id = "t", kind = TrackKind.AUDIO, volume = -0.1f) })
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(refusal { Track(id = "t", kind = TrackKind.AUDIO, pan = 1.5f) })
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(refusal { Track(id = "t", kind = TrackKind.VIDEO, opacity = 1.5f) })
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        /** A lane id that is NOT the video one, so a swapped track is visible rather than symmetric. */
        const val AUDIO_ID = "track-audio-main"
    }
}
