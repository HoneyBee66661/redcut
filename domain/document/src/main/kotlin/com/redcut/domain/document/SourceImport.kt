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
)

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

    /** The container has no video track — audio-only, or something else entirely. */
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
     * Order matters: the reason a user is shown should be the most specific true one, so
     * "no video track" is checked before "unsupported codec" (an audio file's codec is
     * irrelevant), and duration is checked last, after the file is known to be a video.
     */
    fun accept(id: String, probed: ProbedSource): ImportOutcome {
        val probe = probed.probe
        val name = probed.displayName

        if (probe.width <= 0 || probe.height <= 0 || probe.videoCodec.isBlank()) {
            return ImportOutcome.Rejected(ImportRejection.NoVideoTrack(name))
        }

        val codec = normalizeCodec(probe.videoCodec)
        if (codec !in SUPPORTED_VIDEO_CODECS) {
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
                width = probe.width,
                height = probe.height,
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
 * [trackId] is the lane every clip of this import lands on, supplied by the caller for the
 * same reason the ids are: which lane an imported file belongs on is a decision about the
 * DOCUMENT ("the video lane", and later "the audio one"), and a planner that guessed it could
 * not be told otherwise. It is one track for the whole batch because one import is one act.
 *
 * Refused files yield no commands at all, and are reported instead: silently dropping one
 * of five selected videos is how a user concludes the import button is broken.
 */
fun planImport(
    probed: List<ProbedSource>,
    trackId: String,
    sourceId: (index: Int) -> String,
    clipId: (index: Int) -> String,
): ImportPlan {
    val commands = mutableListOf<EditCommand>()
    val accepted = mutableListOf<SourceRef>()
    val rejected = mutableListOf<ImportRejection>()

    probed.forEachIndexed { index, item ->
        when (val outcome = SourceImportPolicy.accept(sourceId(index), item)) {
            is ImportOutcome.Accepted -> {
                val source = outcome.source
                accepted += source
                commands += AddSource(source)
                commands += AppendClip(
                    trackId = trackId,
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
