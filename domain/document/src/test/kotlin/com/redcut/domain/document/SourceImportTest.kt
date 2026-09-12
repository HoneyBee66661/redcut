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

    // --- Rejection, one case per reason (FR-1.4) ---------------------------

    @Test
    fun `an audio-only file is refused for having no video track, not for its codec`() {
        val outcome = SourceImportPolicy.accept(
            id = "src-1",
            probed = probed(
                name = "song.m4a",
                probe = probe(width = 0, height = 0, videoCodec = ""),
            ),
        )

        val rejection = (outcome as ImportOutcome.Rejected).rejection
        assertThat(rejection).isInstanceOf(ImportRejection.NoVideoTrack::class.java)
        assertThat(rejection.message).contains("song.m4a")
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
                clipId = "clip-0",
                sourceId = "src-0",
                sourceInUs = 0L,
                sourceOutUs = 1_000_000L,
            ),
        )
        assertThat(plan.commands[2]).isEqualTo(AddSource(plan.accepted[1]))
        assertThat(plan.commands[3]).isEqualTo(
            AppendClip(
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
                probed(name = "a.m4a", probe = probe(width = 0, height = 0, videoCodec = "")),
                probed(name = "b.m4a", probe = probe(width = 0, height = 0, videoCodec = "")),
            ),
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
            planImport(probed = emptyList(), sourceId = { "src-$it" }, clipId = { "clip-$it" })

        assertThat(plan.isEmpty).isTrue()
        assertThat(plan.rejected).isEmpty()
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
