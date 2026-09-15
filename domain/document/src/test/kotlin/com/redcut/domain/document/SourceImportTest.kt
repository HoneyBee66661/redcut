package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The import policy and the importer's plan (FR-1.2, FR-1.3, FR-1.4).
 *
 * These are the rules with judgement in them — which files may enter a document, and as
 * what — and they are here, in the fast tier, because the cases that matter are the ones a
 * device makes hard to produce: a 60 ms video, an MPEG-4 Part 2 codec, a file whose
 * duration the probe could not read, a rotation of 720 degrees.
 */
class SourceImportTest {

    private fun probe(
        durationUs: Long = 5_000_000L,
        width: Int = 1920,
        height: Int = 1080,
        rotationDegrees: Int = 0,
        frameRate: Float = 30f,
        videoCodec: String = "video/avc",
        audioCodec: String? = "audio/mp4a-latm",
        hasAudio: Boolean = true,
    ) = SourceProbe(
        durationUs = durationUs,
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        frameRate = frameRate,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        hasAudio = hasAudio,
    )

    private fun probed(
        name: String = "clip.mp4",
        uri: String = "content://media/1",
        probe: SourceProbe = probe(),
    ) = ProbedSource(uri = uri, displayName = name, probe = probe)

    /** The music bed's shape: a container the probe found sound in and no picture at all (FR-1.6). */
    private fun audioProbe(durationUs: Long = 180_000_000L) = probe(
        durationUs = durationUs,
        width = 0,
        height = 0,
        videoCodec = "",
        audioCodec = "audio/mp4a-latm",
        hasAudio = true,
    )

    /** The shape neither branch of the policy can use: no picture, and no sound either. */
    private fun neitherProbe() = probe(
        width = 0,
        height = 0,
        videoCodec = "",
        audioCodec = null,
        hasAudio = false,
    )

    // --- Acceptance --------------------------------------------------------

    @Test
    fun `a supported source becomes a SourceRef carrying every probed fact`() {
        val outcome = SourceImportPolicy.accept(
            id = "src-1",
            probed = probed(
                probe = probe(
                    durationUs = 7_500_000L,
                    width = 1280,
                    height = 720,
                    rotationDegrees = 90,
                ),
            ),
        )

        assertThat(outcome).isInstanceOf(ImportOutcome.Accepted::class.java)
        val source = (outcome as ImportOutcome.Accepted).source
        assertThat(source.id).isEqualTo("src-1")
        assertThat(source.uri).isEqualTo("content://media/1")
        assertThat(source.displayName).isEqualTo("clip.mp4")
        assertThat(source.durationUs).isEqualTo(7_500_000L)
        assertThat(source.width).isEqualTo(1280)
        assertThat(source.height).isEqualTo(720)
        assertThat(source.rotationDegrees).isEqualTo(90)
        assertThat(source.hasAudio).isTrue()
    }

    @Test
    fun `a mime-reported codec is reduced to the token the policy compares`() {
        val source = acceptedSource(probe(videoCodec = "video/hevc"))

        assertThat(source.videoCodec).isEqualTo("hevc")
        assertThat(source.audioCodec).isEqualTo("mp4a-latm")
    }

    // --- Normalisation -----------------------------------------------------

    @Test
    fun `rotation is brought into range and snapped to a right angle`() {
        // Containers disagree about the range: -90, 270 and 720 all mean the same quarter
        // turn, and a reader that stored any of them raw would hand the renderer a value
        // it cannot use.
        assertThat(SourceImportPolicy.normalizeRotation(-90)).isEqualTo(270)
        assertThat(SourceImportPolicy.normalizeRotation(270)).isEqualTo(270)
        assertThat(SourceImportPolicy.normalizeRotation(720)).isEqualTo(0)
        assertThat(SourceImportPolicy.normalizeRotation(450)).isEqualTo(90)
        assertThat(SourceImportPolicy.normalizeRotation(180)).isEqualTo(180)
    }

    @Test
    fun `a silent or absurd frame rate falls back to 30 and a real one passes through`() {
        assertThat(SourceImportPolicy.normalizeFrameRate(0f)).isEqualTo(30f)
        assertThat(SourceImportPolicy.normalizeFrameRate(Float.NaN)).isEqualTo(30f)
        assertThat(SourceImportPolicy.normalizeFrameRate(59.94f)).isEqualTo(59.94f)
    }

    // --- The music bed, accepted rather than refused (FR-1.6) --------------

    @Test
    fun `an audio-only file is accepted as a music bed rather than refused for having no video`() {
        // FR-1.6, and the MVP scope line "single video track + one audio bed": "no video track" stopped
        // being a reason to refuse a file once there was somewhere for its sound to go. The codec check
        // is skipped for it — the supported set is a set of VIDEO codecs (spec §8.2), so asking it about
        // a song would refuse every song ever imported.
        val outcome = SourceImportPolicy.accept(
            id = "src-1",
            probed = probed(name = "song.m4a", probe = audioProbe()),
        )

        assertThat(outcome).isInstanceOf(ImportOutcome.Accepted::class.java)
        val source = (outcome as ImportOutcome.Accepted).source
        assertThat(source.displayName).isEqualTo("song.m4a")
        assertThat(source.durationUs).isEqualTo(180_000_000L)
        assertThat(source.hasAudio).isTrue()
        assertThat(source.audioCodec).isEqualTo("mp4a-latm")
        // The audio-only shape as STORED: no dimensions and no video codec. That is what makes the
        // derived readings agree with this verdict instead of a flag beside it that could contradict it.
        assertThat(source.width).isEqualTo(0)
        assertThat(source.height).isEqualTo(0)
        assertThat(source.videoCodec).isEmpty()
        assertThat(source.isAudioOnly).isTrue()
        assertThat(source.hasVideo).isFalse()
    }

    @Test
    fun `a video with sound is a video source and not a music bed`() {
        // Sound WITH picture rides on the video layer, so only a source with no picture at all is a bed.
        // This is the branch that decides which lane an import lands on, so it is asserted directly.
        val source = acceptedSource(probe(hasAudio = true))

        assertThat(source.hasVideo).isTrue()
        assertThat(source.isAudioOnly).isFalse()
    }

    // --- Rejection, one case per reason (FR-1.4) ---------------------------

    @Test
    fun `a file with neither picture nor sound is still refused for having no video track`() {
        // The rejection that survives D1, with its old name and its old sentence — because that
        // sentence is still exactly true of the file it is now raised for.
        val outcome = SourceImportPolicy.accept(
            id = "src-1",
            probed = probed(name = "mystery.bin", probe = neitherProbe()),
        )

        val rejection = (outcome as ImportOutcome.Rejected).rejection
        assertThat(rejection).isInstanceOf(ImportRejection.NoVideoTrack::class.java)
        assertThat(rejection.message).contains("mystery.bin")
    }

    @Test
    fun `an audio-only file is still held to the clip duration floor`() {
        // Being a music bed exempts a source from the CODEC check and from nothing else: every clip's
        // out-point and the whole timeline come from this number, and a song is not exempt from that.
        val outcome = SourceImportPolicy.accept(
            id = "src-1",
            probed = probed(name = "blip.m4a", probe = audioProbe(durationUs = 60_000L)),
        )

        assertThat((outcome as ImportOutcome.Rejected).rejection)
            .isInstanceOf(ImportRejection.TooShort::class.java)
    }

    @Test
    fun `an unsupported codec is named in the reason`() {
        val outcome = SourceImportPolicy.accept(
            id = "src-1",
            probed = probed(name = "old.avi", probe = probe(videoCodec = "video/mp4v-es")),
        )

        val rejection = (outcome as ImportOutcome.Rejected).rejection
        assertThat(rejection).isInstanceOf(ImportRejection.UnsupportedCodec::class.java)
        // The reason has to name the codec: "unsupported file" tells a user nothing about
        // whether a re-encode would fix it.
        assertThat(rejection.message).contains("video/mp4v-es")
    }

    @Test
    fun `a probe that could not read a duration is refused rather than assumed`() {
        val outcome = SourceImportPolicy.accept(
            id = "src-1",
            probed = probed(probe = probe(durationUs = 0L)),
        )

        assertThat((outcome as ImportOutcome.Rejected).rejection)
            .isInstanceOf(ImportRejection.UnknownDuration::class.java)
    }

    @Test
    fun `a file shorter than the minimum clip length is refused with its length in the reason`() {
        val outcome = SourceImportPolicy.accept(
            id = "src-1",
            probed = probed(probe = probe(durationUs = 60_000L)),
        )

        val rejection = (outcome as ImportOutcome.Rejected).rejection
        assertThat(rejection).isInstanceOf(ImportRejection.TooShort::class.java)
        assertThat(rejection.message).contains("60 ms")
        assertThat(rejection.message).contains("100 ms")
    }

    // --- The plan (FR-1.2) -------------------------------------------------

    @Test
    fun `each accepted file yields a source and a clip spanning the whole source, in order`() {
        val plan = planImport(
            probed = listOf(
                probed(
                    name = "first.mp4",
                    uri = "content://media/1",
                    probe = probe(durationUs = 1_000_000L),
                ),
                probed(
                    name = "second.mp4",
                    uri = "content://media/2",
                    probe = probe(durationUs = 2_000_000L),
                ),
            ),
            trackId = VIDEO,
            sourceId = { "src-$it" },
            clipId = { "clip-$it" },
        )

        assertThat(plan.commands).hasSize(4)
        assertThat(plan.accepted.map { it.id }).containsExactly("src-0", "src-1").inOrder()
        // Selection order is the timeline order (FR-1.2): the first file selected is the
        // first clip, and nothing here sorts or de-duplicates on the user's behalf.
        assertThat(plan.commands[0]).isEqualTo(AddSource(plan.accepted[0]))
        assertThat(plan.commands[1]).isEqualTo(
            AppendClip(
                trackId = VIDEO,
                clipId = "clip-0",
                sourceId = "src-0",
                sourceInUs = 0L,
                sourceOutUs = 1_000_000L,
            ),
        )
        assertThat(plan.commands[2]).isEqualTo(AddSource(plan.accepted[1]))
        assertThat(plan.commands[3]).isEqualTo(
            AppendClip(
                trackId = VIDEO,
                clipId = "clip-1",
                sourceId = "src-1",
                sourceInUs = 0L,
                sourceOutUs = 2_000_000L,
            ),
        )
    }

    @Test
    fun `a refused file produces no commands but is still reported`() {
        val plan = planImport(
            probed = listOf(
                probed(name = "good.mp4", uri = "content://media/1"),
                probed(
                    name = "broken.avi",
                    uri = "content://media/2",
                    probe = probe(videoCodec = "video/mp4v-es"),
                ),
            ),
            trackId = VIDEO,
            sourceId = { "src-$it" },
            clipId = { "clip-$it" },
        )

        // Two commands: the good file's source and clip. Nothing at all for the refused
        // one — dropping it silently would leave the user counting clips.
        assertThat(plan.commands).hasSize(2)
        assertThat(plan.accepted).hasSize(1)
        assertThat(plan.rejected).hasSize(1)
        assertThat(plan.rejected.single().message).contains("broken.avi")
        assertThat(plan.hasImports).isTrue()
    }

    @Test
    fun `an import where every file is refused is empty and has nothing to undo`() {
        val plan = planImport(
            probed = listOf(
                probed(name = "a.bin", uri = "content://media/1", probe = neitherProbe()),
                probed(name = "b.bin", uri = "content://media/2", probe = neitherProbe()),
            ),
            trackId = VIDEO,
            sourceId = { "src-$it" },
            clipId = { "clip-$it" },
        )

        assertThat(plan.isEmpty).isTrue()
        assertThat(plan.hasImports).isFalse()
        assertThat(plan.rejected).hasSize(2)
    }

    @Test
    fun `an empty selection plans nothing`() {
        val plan =
            planImport(
                probed = emptyList(),
                trackId = VIDEO,
                sourceId = { "src-$it" },
                clipId = { "clip-$it" },
            )

        assertThat(plan.isEmpty).isTrue()
        assertThat(plan.rejected).isEmpty()
    }

    // --- Which lane an accepted file lands on, and seeding it (FR-1.6) -----

    @Test
    fun `an audio-only source is routed to the audio lane and a video source to the video lane`() {
        val plan = planImport(
            probed = listOf(
                probed(name = "film.mp4", uri = "content://media/1"),
                probed(name = "song.m4a", uri = "content://media/2", probe = audioProbe()),
            ),
            trackId = VIDEO,
            sourceId = { "src-$it" },
            clipId = { "clip-$it" },
        )

        // The source decides, not the caller and not a guess: the probe is the only thing that knows
        // which of these two files is a music bed.
        assertThat(plan.commands.filterIsInstance<AppendClip>().map { it.trackId })
            .containsExactly(VIDEO, Track.AUDIO_ID).inOrder()
        assertThat(plan.commands.filterIsInstance<AppendClip>().map { it.clipId })
            .containsExactly("clip-0", "clip-1").inOrder()
    }

    @Test
    fun `an audio-only clip spans the whole of its source`() {
        val plan = planImport(
            probed = listOf(probed(name = "song.m4a", probe = audioProbe())),
            trackId = VIDEO,
            sourceId = { "src-$it" },
            clipId = { "clip-$it" },
        )

        assertThat(plan.commands.filterIsInstance<AppendClip>().single()).isEqualTo(
            AppendClip(
                trackId = Track.AUDIO_ID,
                clipId = "clip-0",
                sourceId = "src-0",
                sourceInUs = 0L,
                sourceOutUs = 180_000_000L,
            ),
        )
    }

    @Test
    fun `the first audio import seeds the audio lane once however many songs are selected`() {
        val plan = planImport(
            probed = listOf(
                probed(name = "one.m4a", uri = "content://media/1", probe = audioProbe()),
                probed(name = "two.m4a", uri = "content://media/2", probe = audioProbe()),
            ),
            trackId = VIDEO,
            sourceId = { "src-$it" },
            clipId = { "clip-$it" },
        )

        // Two songs are two clips on ONE music bed. A seed per audio source would be a second lane
        // whose clips the mixer has no shape for — and the plan is where that is prevented.
        val seeds = plan.commands.filterIsInstance<AddTrack>()
        assertThat(seeds).hasSize(1)
        assertThat(seeds.single().track.id).isEqualTo(Track.AUDIO_ID)
        assertThat(seeds.single().track.kind).isEqualTo(TrackKind.AUDIO)
        // Before the first thing that addresses the lane, or the AppendClip would name a lane the
        // document does not have and be a silent no-op.
        assertThat(plan.commands.indexOfFirst { it is AddTrack })
            .isLessThan(plan.commands.indexOfFirst { it is AppendClip })
    }

    @Test
    fun `a video-only import lands every clip on the video lane and seeds no audio lane`() {
        val plan = planImport(
            probed = listOf(
                probed(name = "a.mp4", uri = "content://media/1", probe = probe(hasAudio = false)),
                probed(name = "b.mp4", uri = "content://media/2", probe = probe(hasAudio = false)),
            ),
            trackId = VIDEO,
            sourceId = { "src-$it" },
            clipId = { "clip-$it" },
        )

        // The regression half of an additive change: a batch with no music bed in it plans exactly what
        // it planned before, down to the command count — no lane is created for a project with no sound.
        assertThat(plan.commands).hasSize(4)
        assertThat(plan.commands.filterIsInstance<AddTrack>()).isEmpty()
        assertThat(plan.commands.filterIsInstance<AppendClip>().map { it.trackId })
            .containsExactly(VIDEO, VIDEO).inOrder()
    }

    @Test
    fun `the caller can name the lane the music bed lands on`() {
        val plan = planImport(
            probed = listOf(probed(name = "song.m4a", probe = audioProbe())),
            trackId = VIDEO,
            sourceId = { "src-$it" },
            clipId = { "clip-$it" },
            audioTrackId = "track-audio-bed",
        )

        // The seed is built from the id the plan ROUTES to, not from the model's default: a lane created
        // under one id while the clip went to another is a music bed appended to nothing.
        assertThat(plan.commands.filterIsInstance<AddTrack>().single().track.id)
            .isEqualTo("track-audio-bed")
        assertThat(plan.commands.filterIsInstance<AppendClip>().single().trackId)
            .isEqualTo("track-audio-bed")
    }

    // --- The plan against the real commands ---------------------------------

    @Test
    fun `the planned commands build a renderable document`() {
        // The plan's output is only correct if the domain accepts it afterwards: the
        // commands must be total AND sufficient, i.e. applying them all must yield a
        // document whose clips all point at live sources.
        val plan = planImport(
            probed = listOf(
                probed(
                    name = "a.mp4",
                    uri = "content://media/1",
                    probe = probe(durationUs = 1_000_000L),
                ),
                probed(
                    name = "b.mp4",
                    uri = "content://media/2",
                    probe = probe(durationUs = 3_000_000L),
                ),
            ),
            trackId = VIDEO,
            sourceId = { "src-$it" },
            clipId = { "clip-$it" },
        )

        val document = plan.commands.fold(
            EditDocument(id = "doc", name = "Untitled"),
        ) { doc, command ->
            command.apply(doc)
        }

        assertThat(document.isRenderable()).isTrue()
        assertThat(document.clips.map { it.id }).containsExactly("clip-0", "clip-1").inOrder()
        assertThat(document.durationUs).isEqualTo(4_000_000L)
    }

    private fun acceptedSource(probe: SourceProbe): SourceRef =
        (SourceImportPolicy.accept("src-1", probed(probe = probe)) as ImportOutcome.Accepted).source
}
