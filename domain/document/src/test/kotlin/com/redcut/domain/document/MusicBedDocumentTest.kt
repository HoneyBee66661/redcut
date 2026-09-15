package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * The music bed as the DOCUMENT sees it (FR-1.6, WS D).
 *
 * ### Why this is a file of its own
 *
 * [SourceImportTest] proves what a plan SAYS; this proves what the document is once the plan has been
 * through the stack. The two are different claims, and the second is the one a bug can hide in: a plan
 * that emits an [AddTrack] nothing carries out, or carries out into a lane the [AppendClip] does not
 * name, is a plan whose own assertions all pass.
 *
 * ### The other half — a document with no picture is legal
 *
 * The MVP's "single video track + one audio bed" became a lane rather than a field, so a project can
 * now hold a lane of sound beside an empty one of picture. Nothing in the model makes the video lane
 * special: it is not required to exist, not required to be first, and not required to hold anything.
 * The tests below hold that end of the deal — a document that constructs, round-trips through the file
 * format, and answers every derived question (its length, its renderability, the lane an import would
 * land on) without a clip of picture anywhere in it.
 */
class MusicBedDocumentTest {

    /** One song's worth of timeline, long enough to be a bed under a short video. */
    private val bedDurationUs = 180 * SEC

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun blankDocument(): EditDocument = EditDocument(id = "doc-1", name = "Untitled")

    /** The audio-only shape a probe reports for a song (FR-1.6): sound, and no picture at all. */
    private fun audioProbe() = SourceProbe(
        durationUs = bedDurationUs,
        width = 0,
        height = 0,
        videoCodec = "",
        audioCodec = "audio/mp4a-latm",
        hasAudio = true,
    )

    /**
     * A plan importing [count] songs, with ids derived from [tag].
     *
     * Two plans in one test have to be able to name different things, or the second import would be a
     * replay of the first and would prove nothing about a project that already has a bed.
     */
    private fun audioPlan(tag: String, count: Int = 1): ImportPlan = planImport(
        probed = (0 until count).map { index ->
            ProbedSource(
                uri = "content://media/$tag-$index",
                displayName = "$tag-$index.m4a",
                probe = audioProbe(),
            )
        },
        trackId = Track.MAIN_ID,
        sourceId = { "src-$tag-$it" },
        clipId = { "clip-$tag-$it" },
    )

    /** The plan carried out through the ordinary gate — the lock check included, as the stack does. */
    private fun EditDocument.applyAll(plan: ImportPlan): EditDocument =
        plan.commands.fold(this) { doc, command -> doc.after(command) }

    // --- Seeding the lane (D2) ---------------------------------------------

    @Test
    fun `a first music bed import creates the audio lane and puts the clip on it`() {
        val document = blankDocument().applyAll(audioPlan("one"))

        assertThat(document.tracks.map { it.kind })
            .containsExactly(TrackKind.VIDEO, TrackKind.AUDIO).inOrder()
        assertThat(document.trackById(Track.AUDIO_ID)?.clips?.map { it.id })
            .containsExactly("clip-one-0")
        // A new lane goes on top of the stack, and the video lane is left exactly as it was: a bed puts
        // no picture anywhere.
        assertThat(document.trackById(Track.MAIN_ID)?.clips).isEmpty()
        assertThat(document.isRenderable()).isTrue()
        assertThat(document.durationUs).isEqualTo(bedDurationUs)
    }

    @Test
    fun `a second music bed import reuses the lane the first one created`() {
        val afterSecond = blankDocument().applyAll(audioPlan("one")).applyAll(audioPlan("two"))

        // The seed is idempotent, so the second import is a second song on the SAME bed rather than a
        // second bed — which is what the plan cannot know on its own and AddTrack's no-op supplies.
        assertThat(afterSecond.tracks.count { it.kind == TrackKind.AUDIO }).isEqualTo(1)
        assertThat(afterSecond.tracks.map { it.kind })
            .containsExactly(TrackKind.VIDEO, TrackKind.AUDIO).inOrder()
        assertThat(afterSecond.trackById(Track.AUDIO_ID)?.clips?.map { it.id })
            .containsExactly("clip-one-0", "clip-two-0").inOrder()
    }

    @Test
    fun `adding a lane the document already has changes nothing`() {
        val document = blankDocument().applyAll(audioPlan("one"))
        val lane = requireNotNull(document.trackById(Track.AUDIO_ID))

        // Not merely equal — the SAME document, which is what makes the repeated seed free: the stack
        // records no entry and advances no revision for an edit that never happened.
        assertThat(document.after(AddTrack(lane))).isSameInstanceAs(document)
    }

    // --- A document with no picture is legal (D4) --------------------------

    @Test
    fun `an audio-only document survives a round trip through the file format`() {
        val before = blankDocument().applyAll(audioPlan("one"))

        val text = json.encodeToString(before)
        val after = json.decodeFromString<EditDocument>(text)

        assertThat(after).isEqualTo(before)
        // Asserted off the BYTES, not the model: a lane's kind is a wire value, and an audio lane the
        // next build read back as video would draw a song as footage.
        assertThat(text).contains(""""kind":"AUDIO"""")
        assertThat(after.trackById(Track.AUDIO_ID)?.kind).isEqualTo(TrackKind.AUDIO)
        assertThat(after.trackById(Track.AUDIO_ID)?.clips?.map { it.id })
            .containsExactly("clip-one-0")
    }

    @Test
    fun `an audio-only document is renderable and as long as its bed`() {
        val document = EditDocument(id = "doc-1", name = "Untitled", tracks = emptyList())
            .applyAll(audioPlan("one"))

        assertThat(document.isRenderable()).isTrue()
        assertThat(document.durationUs).isEqualTo(bedDurationUs)
        // The lane a VIDEO import into this document would be handed: the id a document of this shape
        // would have, rather than a null each caller would have to defend against. The video lane does
        // not exist, and asking for it is still answerable — which is the whole of "nothing requires one".
        assertThat(document.importTrackId).isEqualTo(Track.MAIN_ID)
        assertThat(document.trackById(document.importTrackId)).isNull()
    }

    @Test
    fun `a document with no lanes at all is legal`() {
        // The far end of the same rule: not even an audio lane is required. What this must NOT do is
        // throw, so every derived reading is asked for and each answers honestly instead.
        val empty = EditDocument(id = "doc-2", name = "Empty", tracks = emptyList())

        assertThat(empty.tracks).isEmpty()
        assertThat(empty.clips).isEmpty()
        assertThat(empty.durationUs).isEqualTo(0L)
        assertThat(empty.timeline).isEmpty()
        assertThat(empty.isRenderable()).isFalse()
    }
}
