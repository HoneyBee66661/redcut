package com.redcut.domain.document

import kotlinx.serialization.Serializable

/**
 * A lane of the timeline: what it is FOR, and the clips that sit in it.
 *
 * ### Why the clips live inside the track
 *
 * The document used to be one flat, ordered clip list, which is the same thing as a document with
 * exactly one track — the shape of the timeline was implied by the order of the list and nothing could
 * say "this clip is audio". A [Track] makes the lane explicit, and putting the clips INSIDE it is what
 * keeps that a single source of truth: were the clips also a flat list on the document, two lanes could
 * disagree about which one holds a clip, and the disagreement would only surface as a clip drawn in the
 * wrong place. [EditDocument.clips] is a derived view over these, not a second copy.
 *
 * ### The kind is a promise about the CONTENT
 *
 * A track's [kind] says what its clips are: a VIDEO lane holds picture, an AUDIO lane holds a source's
 * sound, a TEXT_OVERLAY lane holds titles drawn over the picture. It is stored rather than inferred
 * from the clips because an empty lane is a legal document (a fresh project is one empty video track),
 * and because the user's model of the timeline is "track 1 is video, track 2 is audio" before any audio
 * clip exists.
 *
 * Immutable by construction, like everything else the document holds: a change produces a new instance,
 * which is what keeps snapshot undo a pointer copy.
 */
@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val clips: List<Clip> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "a track must have an id" }
    }

    /** The clip with [clipId] on THIS track, or null. The document-wide lookup is [EditDocument.clipById]. */
    fun clipById(clipId: String): Clip? = clips.firstOrNull { it.id == clipId }

    companion object {
        /**
         * The id of the video track every document starts with.
         *
         * A fixed string rather than a generated one, for the same reason the placeholder project's id
         * is fixed: the first track has to be recognisable across a save and a load, and a document that
         * arrived from a v1 file must end up with the SAME track id as one built in memory, or a test
         * (and a diff) could not tell "migrated" from "made up".
         */
        const val MAIN_ID = "track-video-main"

        /** The empty VIDEO track a new document begins with. */
        val MAIN = Track(id = MAIN_ID, kind = TrackKind.VIDEO)
    }
}

/**
 * What a track's clips ARE (FR-1, FR-3's audio, and the text overlay of the revision-2 plan).
 *
 * Three kinds, because three are what the model can express today. The umbrella card mentions a fourth
 * ("effect") whose track would hold effects rather than clips — that is a different content shape, and
 * inventing it here would put a kind in the schema that no code can build or honour.
 */
@Serializable
enum class TrackKind {
    /** Picture. */
    VIDEO,

    /** A source's sound, playing alongside picture. */
    AUDIO,

    /** Titles and captions drawn over the picture. */
    TEXT_OVERLAY,
}

/**
 * A single VIDEO track holding [clips] — the shape a document had before tracks existed, as a track.
 *
 * Kept as a named function rather than inlined at each call site because "one video track with these
 * clips" is how most of this codebase builds a document (fixtures, tests, the migration), and a
 * constructor spelled out a hundred times is a hundred chances to get the kind or the id wrong.
 */
fun videoTrack(clips: List<Clip>): Track =
    Track(id = Track.MAIN_ID, kind = TrackKind.VIDEO, clips = clips)

/** [videoTrack] for the hand-built documents of tests, where the clips are written out one by one. */
fun videoTrack(vararg clips: Clip): Track = videoTrack(clips.toList())
