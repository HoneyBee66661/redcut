package com.redcut.domain.document

/**
 * What a probe reports about one file (FR-1.3), before anything is decided about it.
 *
 * Deliberately separate from [SourceRef]: this is what the platform said, and [SourceRef]
 * is what the document decides to keep. Collapsing the two is how a policy change (a new
 * supported codec) turns into a schema migration — and how a probe's junk value ends up
 * stored in a project file.
 *
 * Every field is a fact from the file, never a default chosen here. `frameRate = 0f` means
 * "the container did not say", which is a different thing from 30 fps, and the policy is
 * where that difference is resolved ([SourceImportPolicy.accept]).
 */
data class SourceProbe(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val frameRate: Float = 0f,
    val videoCodec: String = "",
    val audioCodec: String? = null,
    val hasAudio: Boolean = false,
) {
    /**
     * True when the container reported a picture track the policy could judge.
     *
     * Derived from the three facts above rather than reported by the platform, because no platform
     * reports it: a probe that found no video track leaves the dimensions at zero and the codec blank,
     * and that ABSENCE is the fact. Naming it here is what lets [SourceImportPolicy.accept] ask the
     * question once instead of repeating the three-way test — and it is the same reading
     * [SourceRef.hasVideo] applies to what the policy stored, so the probe's shape and the document's
     * shape cannot come apart.
     */
    val hasVideo: Boolean get() = width > 0 && height > 0 && videoCodec.isNotBlank()
}

/** One file that was read: where it came from, what it is called, what the probe found. */
data class ProbedSource(
    val uri: String,
    val displayName: String,
    val probe: SourceProbe,
)

/** Microseconds in a millisecond: what every user-facing duration message divides by. */
private const val US_PER_MS = 1_000L

/** A right angle, and how many of them make a full turn. */
private const val QUARTER_TURN_DEGREES = 90
private const val QUARTER_TURNS = 4

/**
 * Why a source was refused (FR-1.4: "Reject unsupported sources with a specific,
 * human-readable reason").
 *
 * A sealed type rather than a string, so the reason is (a) exhaustive to handle,
 * (b) comparable in tests, and (c) not something a UI has to parse. [message] is the
 * human-readable half, and it is built with the offending value in it — "uses MPEG-4
 * Part 2, which RedCut cannot decode" is actionable; "unsupported file" is not.
 *
 * [Unreadable] is the one that is not a judgement about the file: the file could not be
 * opened at all (revoked permission, moved, corrupt container). Keeping it in the same
 * vocabulary is what lets the import report read as one list instead of two.
 */
sealed interface ImportRejection {
    val message: String

    /**
     * The container has neither picture nor sound — so there is nothing to import it AS.
     *
     * Not the audio-only case any more: an audio-only file is a music bed and is accepted (FR-1.6).
     * What is left for this reason is the file that reported no video track and no audio track either,
     * which is a container RedCut has nothing to do with. The name is kept because a rejection reason
     * is a wire value a report and a test compare, and the sentence it produces is still exactly true
     * of the file it is now raised for.
     */
    data class NoVideoTrack(val displayName: String) : ImportRejection {
        override val message: String get() = "\"$displayName\" has no video track."
    }

    /** The video codec is outside the set this app decodes (spec §8.2's platform set). */
    data class UnsupportedCodec(val displayName: String, val codec: String) : ImportRejection {
        override val message: String
            get() = "\"$displayName\" uses $codec, which RedCut cannot decode."
    }

    /**
     * The probe could not establish a duration. Refused rather than assumed, because every
     * clip's out-point, every trim bound and the whole timeline come from this number: a
     * guess here is a corrupt edit that looks fine until playback.
     */
    data class UnknownDuration(val displayName: String) : ImportRejection {
        override val message: String get() = "RedCut could not read the length of \"$displayName\"."
    }

    /** Shorter than the minimum clip length (FR-2's 100 ms floor). */
    data class TooShort(val displayName: String, val durationUs: Long) : ImportRejection {
        override val message: String
            get() = "\"$displayName\" is ${durationUs / US_PER_MS} ms long, shorter than the " +
                "${Clip.MIN_DURATION_US / US_PER_MS} ms minimum clip."
    }

    /** The file could not be opened or probed at all. [reason] is the platform's own words. */
    data class Unreadable(val displayName: String, val reason: String) : ImportRejection {
        override val message: String get() = "\"$displayName\" could not be opened: $reason"
    }
}

/** The two possible results of assessing one probed file. */
sealed interface ImportOutcome {
    data class Accepted(val source: SourceRef) : ImportOutcome
    data class Rejected(val rejection: ImportRejection) : ImportOutcome
}

/**
 * Which probed files may enter a document, and as what (FR-1.3, FR-1.4).
 *
 * This lives in the pure domain, not in the Android probe, for one reason: it is the part
 * with rules in it, and rules that live next to `MediaMetadataRetriever` are rules that
 * can only be tested on a device. Here they are tested in the fast tier, in milliseconds,
 * including the cases (a 60 ms file, an MPEG-4 Part 2 codec, a zero duration) that are
 * expensive or impossible to produce as real files.
 *
 * The Android side's job is then narrow and honest: report the facts, or say it could not.
 */
object SourceImportPolicy {

    /**
     * Video codecs the MVP decodes (spec §8.2, Media3's platform set).
     *
     * Compared case-insensitively and against the SHORT form, because probes report both:
     * `MediaMetadataRetriever` returns a MIME string (`video/avc`, `video/hevc`) while
     * `MediaExtractor` reports a codec name (`avc`, `hevc`, and — on some devices —
     * `avc1`). Normalising in one place is what keeps a device quirk from becoming a
     * rejected import.
     */
    val SUPPORTED_VIDEO_CODECS =
        setOf("avc", "h264", "avc1", "hevc", "h265", "hevc1", "vp9", "vp09", "av01", "av1")

    /** 30 fps is the assumption the rest of the app makes (spec §8.1's `OutputSpec.fps`). */
    const val DEFAULT_FRAME_RATE = 30f

    /** Assumes 30 fps when the container is silent, and passes a known rate through. */
    fun normalizeFrameRate(frameRate: Float): Float =
        if (frameRate.isFinite() && frameRate > 0f) frameRate else DEFAULT_FRAME_RATE

    /**
     * Rotation is normalised to one of 0/90/180/270.
     *
     * Containers and devices disagree about the range: some report -90, some 270, some
     * 720 from a buggy encoder. Rounding to the nearest right angle and bringing it into
     * range means the renderer never sees a value it would have to defend against.
     */
    fun normalizeRotation(degrees: Int): Int {
        val quarterTurns = Math.floorMod(
            Math.round(degrees / QUARTER_TURN_DEGREES.toFloat()),
            QUARTER_TURNS,
        )
        return quarterTurns * QUARTER_TURN_DEGREES
    }

    /** Reduces a reported codec or MIME string to the token compared against the set. */
    fun normalizeCodec(reported: String): String =
        reported.substringAfterLast('/').substringBefore('.').lowercase()

    /**
     * Assesses one probed file.
     *
     * ### What may enter, and as what (FR-1.3, FR-1.6)
     *
     * Two shapes are importable: a file with a picture track this app decodes, and a file that is
     * sound and nothing else — the music bed of FR-1.6, which the MVP scope line ("single video track
     * + one audio bed") admits as exactly one kind of second source. A file with neither is refused,
     * and it is refused by the same reason it always was, because that reason is exactly true of it.
     *
     * ### Why the codec check is skipped for a music bed
     *
     * The supported-codec set is a set of VIDEO codecs (spec §8.2), so asking it about an audio-only
     * file is a category error that would refuse every song ever imported. This is the one place the
     * two shapes diverge, and it is a branch rather than a second `accept`, because everything else —
     * the duration floor, the unknown-duration refusal, the normalisation, the stored shape — is the
     * same judgement for both and two functions would be two places for them to drift apart.
     *
     * ### Order
     *
     * The reason a user is shown should be the most specific true one, so the "nothing to import"
     * check comes first (it is the only one that can be raised for both shapes), the codec check next
     * (an audio file's video codec does not exist to be wrong), and duration last — after the file is
     * known to be something the timeline can hold, since that is the check that needs a length.
     */
    fun accept(id: String, probed: ProbedSource): ImportOutcome {
        val probe = probed.probe
        val name = probed.displayName
        val hasVideo = probe.hasVideo

        if (!hasVideo && !probe.hasAudio) {
            return ImportOutcome.Rejected(ImportRejection.NoVideoTrack(name))
        }

        val codec = if (hasVideo) normalizeCodec(probe.videoCodec) else ""
        if (hasVideo && codec !in SUPPORTED_VIDEO_CODECS) {
            return ImportOutcome.Rejected(ImportRejection.UnsupportedCodec(name, probe.videoCodec))
        }

        if (probe.durationUs <= 0L) {
            return ImportOutcome.Rejected(ImportRejection.UnknownDuration(name))
        }

        if (probe.durationUs < Clip.MIN_DURATION_US) {
            return ImportOutcome.Rejected(ImportRejection.TooShort(name, probe.durationUs))
        }

        return ImportOutcome.Accepted(
            SourceRef(
                id = id,
                uri = probed.uri,
                displayName = name,
                durationUs = probe.durationUs,
                // An audio-only source is stored with NO dimensions and a blank codec, which is what
                // makes [SourceRef.hasVideo] — and so [SourceRef.isAudioOnly] — reproduce this verdict
                // on the stored value instead of a second flag that could contradict it. Zeroing them
                // here also drops whatever a probe reported for a file it found no picture in: a stray
                // 1920 from a container that guessed is a size the render path would try to use.
                width = if (hasVideo) probe.width else 0,
                height = if (hasVideo) probe.height else 0,
                rotationDegrees = normalizeRotation(probe.rotationDegrees),
                frameRate = normalizeFrameRate(probe.frameRate),
                hasAudio = probe.hasAudio,
                videoCodec = codec,
                audioCodec = probe.audioCodec?.let(::normalizeCodec),
            ),
        )
    }
}

/**
 * The commands one import produces, in order, plus what was refused.
 *
 * A plan rather than an applied document, because the caller owns the mutation gateway
 * ([UndoStack] is the only thing allowed to advance `revision`, spec §1.1). Planning is
 * pure; applying stays in one place.
 */
data class ImportPlan(
    val commands: List<EditCommand>,
    val accepted: List<SourceRef>,
    val rejected: List<ImportRejection>,
) {
    val isEmpty: Boolean get() = commands.isEmpty()

    /** True when something was imported — the case that is worth an undo entry. */
    val hasImports: Boolean get() = accepted.isNotEmpty()
}

/**
 * Turns probed files into commands (FR-1.2: "append to timeline in selection order").
 *
 * Each accepted source yields an [AddSource] followed by an [AppendClip] spanning the whole
 * source, in the order given. The ids are supplied by the caller rather than minted here —
 * the same rule the rest of [EditCommand] follows, because a command that invents its own
 * id cannot be compared, replayed, or asserted on.
 *
 * Refused files yield no commands at all, and are reported instead: silently dropping one
 * of five selected videos is how a user concludes the import button is broken.
 *
 * ### Which lane an accepted file lands on (FR-1.6)
 *
 * Two lanes, and the source itself decides between them rather than the caller or a guess:
 * a source with picture goes to [trackId], and a source that is sound and nothing else — a
 * music bed — goes to [audioTrackId]. The distinction is a fact about the file
 * ([SourceRef.isAudioOnly]), which is why it is not a parameter: a caller that had to say
 * "and this one is audio" for every selection would be re-reporting what the probe already
 * found, and would get it wrong exactly when it mattered.
 *
 * The two LANE IDS are the caller's, though, for the reason the entity ids are: which lane a
 * project's video lives on is a decision about the DOCUMENT, and a planner that guessed it
 * could not be told otherwise. [audioTrackId] defaults to [Track.AUDIO_ID] — the one audio
 * lane the model names — rather than to [trackId], because a default of the video lane would
 * put the music bed on the picture lane, which is the one placement that is certainly wrong.
 * A caller written before the bed existed keeps its exact signature and gets the right lane.
 *
 * ### Seeding the audio lane (D2)
 *
 * The first accepted audio source is preceded by an [AddTrack] creating [audioTrackId], so a
 * plan is sufficient on its own: a first audio import into a fresh document — one video lane,
 * no audio lane — lands the bed on a lane that exists, without the caller having to know
 * whether this is that first import.
 *
 * It is idempotent in the two ways it has to be. Within one plan, only the FIRST audio source
 * emits the seed, so importing two songs creates the lane once. Across plans, [AddTrack] is a
 * no-op when the lane is already there, so the second audio import emits a seed that does
 * nothing rather than a second lane — which is also why the planner does not need to see the
 * document to answer "does this project have an audio lane yet".
 */
fun planImport(
    probed: List<ProbedSource>,
    trackId: String,
    sourceId: (index: Int) -> String,
    clipId: (index: Int) -> String,
    audioTrackId: String = Track.AUDIO_ID,
): ImportPlan {
    val commands = mutableListOf<EditCommand>()
    val accepted = mutableListOf<SourceRef>()
    val rejected = mutableListOf<ImportRejection>()
    var seededAudioLane = false

    probed.forEachIndexed { index, item ->
        when (val outcome = SourceImportPolicy.accept(sourceId(index), item)) {
            is ImportOutcome.Accepted -> {
                val source = outcome.source
                val isBed = source.isAudioOnly
                if (isBed && !seededAudioLane) {
                    seededAudioLane = true
                    commands += AddTrack(audioLaneNamed(audioTrackId))
                }
                accepted += source
                commands += AddSource(source)
                commands += AppendClip(
                    trackId = if (isBed) audioTrackId else trackId,
                    clipId = clipId(index),
                    sourceId = source.id,
                    sourceInUs = 0L,
                    sourceOutUs = source.durationUs,
                )
            }

            is ImportOutcome.Rejected -> rejected += outcome.rejection
        }
    }

    return ImportPlan(commands = commands, accepted = accepted, rejected = rejected)
}

/**
 * The lane a first audio import seeds: an empty AUDIO lane with [audioTrackId].
 *
 * Built from the id the caller ROUTES to rather than from a constant, so the lane the plan
 * creates is the lane the plan appends to: a seed named [Track.AUDIO_ID] while the clips went
 * to an id the caller chose would append the music bed to a lane that does not exist, and
 * [AppendClip] would then quietly do nothing.
 *
 * Empty on purpose — a lane's sound-state attributes ([Track.volume], [Track.muted]) are the
 * mix, and the mix of a bed nobody has touched yet is the defaults.
 */
private fun audioLaneNamed(audioTrackId: String): Track =
    Track(id = audioTrackId, kind = TrackKind.AUDIO)
