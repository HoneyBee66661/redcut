package com.redcut.domain.document

import kotlinx.serialization.Serializable

/**
 * A lane of the timeline: what it is FOR, the items that sit in it, and how it is mixed and composited.
 *
 * ### Why the contents live inside the track
 *
 * The document used to be one flat, ordered clip list, which is the same thing as a document with
 * exactly one track — the shape of the timeline was implied by the order of the list and nothing could
 * say "this clip is audio". A [Track] makes the lane explicit, and putting the contents INSIDE it is what
 * keeps that a single source of truth: were the clips also a flat list on the document, two lanes could
 * disagree about which one holds a clip, and the disagreement would only surface as a clip drawn in the
 * wrong place. [EditDocument.clips] is a derived view over these, not a second copy.
 *
 * ### Why the contents are [TrackItem]s and not clips
 *
 * Since v3 a lane is an ordered list of items, and an item is either a [Clip] or a [Gap]. A gap is what
 * lets a lane say "nothing plays here" as a thing in its own right rather than as a distance between two
 * clips; [Gap] carries the argument for why that has to be an element. [clips] is the derived view every
 * reader written before gaps keeps using, and it is `get`-only for the same reason
 * [EditDocument.clips] is: a caller writing a plain clip list back has to say which lane it writes to.
 *
 * ### The kind is a promise about the CONTENT
 *
 * A track's [kind] says what its items are: a VIDEO lane holds picture, an AUDIO lane holds a source's
 * sound, a TEXT_OVERLAY lane holds titles drawn over the picture, and an ADJUSTMENT lane holds no items
 * of its own — it carries effects for the lanes under it. It is stored rather than inferred from the
 * items because an empty lane is a legal document (a fresh project is one empty video track), and because
 * the user's model of the timeline is "track 1 is video, track 2 is audio" before any audio clip exists.
 *
 * ### The attributes, and which lane may carry them
 *
 * A lane's switches, level and mix belong to the LANE rather than to any clip in it, which is what makes
 * them survive a re-edit of its contents. The rule this model enforces is the narrow, defensible one: the
 * attributes that describe a lane's SOUND — [volume], [pan], [isMuted], [isSolo] — belong to an AUDIO
 * lane, and the attributes that describe how a lane is COMPOSITED — [opacity], [blendMode] — do not apply
 * to one. A VIDEO or TEXT_OVERLAY lane has no sound of its own to mix or solo; an AUDIO lane is not
 * drawn, so it has no opacity and no blend mode. In both directions the other half would be state the
 * render path has to invent a meaning for, and a lane attribute nobody honours is worse than a lane
 * attribute nobody can set. [isLocked], [isVisible] and [isCollapsed] are about the lane as an object,
 * so all four kinds carry them.
 *
 * The numbers are checked here too: this model makes an illegal document unrepresentable at the boundary,
 * so a file carrying a pan of 4 is refused where it is read rather than silently drawn as hard left.
 *
 * Immutable by construction, like everything else the document holds: a change produces a new instance,
 * which is what keeps snapshot undo a pointer copy.
 */
@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val items: List<TrackItem> = emptyList(),
    val isLocked: Boolean = false,
    val isVisible: Boolean = true,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val volume: Float = 1f,
    val pan: Float = 0f,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val isCollapsed: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "a track must have an id" }
        require(pan in -1f..1f) { "pan must be within -1..1, was $pan" }
        require(volume >= 0f) { "volume must be >= 0, was $volume" }
        require(opacity in 0f..1f) { "opacity must be within 0..1, was $opacity" }
        require(kind == TrackKind.AUDIO || !carriesSoundState) {
            "a ${kind.name} lane has no sound of its own to mix, mute or solo"
        }
        require(kind != TrackKind.AUDIO || !carriesPictureState) {
            "an AUDIO lane is not composited, so it has no opacity and no blend mode"
        }
    }

    /** True when an attribute only an AUDIO lane may carry has been set away from its default. */
    private val carriesSoundState: Boolean
        get() = volume != 1f || pan != 0f || isMuted || isSolo

    /** True when an attribute only a drawn lane may carry has been set away from its default. */
    private val carriesPictureState: Boolean
        get() = opacity != 1f || blendMode != BlendMode.NORMAL

    /** This lane's clips, in order, with the gaps left out — a view over [items], not a copy. */
    val clips: List<Clip> get() = items.filterIsInstance<Clip>()

    /** The clip with [clipId] on THIS track, or null. Document-wide is [EditDocument.clipById]. */
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

        /**
         * The id of the audio lane a project's music bed lands on (FR-1.6).
         *
         * A fixed string for the same reason [MAIN_ID] is one: the lane the first audio import seeds has
         * to be the SAME lane after a save and a load, or "does this project already have an audio
         * lane?" — the question that seeding is idempotent against — would get a different answer on
         * every read, and a project would grow a second music bed.
         *
         * It is [MAIN_ID]'s counterpart rather than a second video lane: a document has one of each,
         * which is the MVP's "single video track + one audio bed". Unlike [MAIN] there is no companion
         * instance of it, because the lane a plan seeds is built from the id the CALLER routes audio to
         * — a seed named [AUDIO_ID] while the plan appended to another lane would put the music bed on a
         * track that does not exist.
         */
        const val AUDIO_ID = "track-audio-main"

        /** The empty VIDEO track a new document begins with. */
        val MAIN = Track(id = MAIN_ID, kind = TrackKind.VIDEO)
    }
}

/**
 * What a track's contents ARE (FR-1, FR-3's audio, the text overlay of the revision-2 plan, and the
 * adjustment layer the roadmap asks for).
 *
 * ### Why the set is closed, and why that is worth a kind nothing honours
 *
 * A saved project names each lane by kind, and an enum value this build does not know is a decode failure
 * that `ignoreUnknownKeys` cannot absorb — so a kind introduced in a later release is a project an OLDER
 * build refuses to open, which is the one failure a migration cannot repair, because the old build cannot
 * decode the document far enough to migrate it. [ADJUSTMENT] is here for that reason and that reason
 * only, and its cost is named rather than hidden: no code honours an adjustment lane yet, so a document
 * may carry a lane that draws nothing.
 */
@Serializable
enum class TrackKind {
    /** Picture. */
    VIDEO,

    /** A source's sound, playing alongside picture. */
    AUDIO,

    /** Titles and captions drawn over the picture. */
    TEXT_OVERLAY,

    /**
     * Effects applied to the lanes beneath it, rather than items of its own.
     *
     * The one kind that is not a statement about its contents. It is in the schema because a kind is a
     * wire value and the vocabulary has to close before users have files (see above), not because
     * anything composites one today.
     */
    ADJUSTMENT,
}

/**
 * How a lane's picture is composited onto the lanes beneath it.
 *
 * [NORMAL] is the only mode the render path honours TODAY. [MULTIPLY] and [SCREEN] are in the schema so
 * that a lane attribute is a CLOSED set before the commands are re-audited for it — the same trade
 * [TrackKind.ADJUSTMENT] makes, and it carries the same named cost: a project can now say MULTIPLY and be
 * drawn NORMAL, so the file holds an intent the app does not yet carry out. Adding the values later
 * instead is not the free option it looks like: it is a project an older build refuses to open.
 */
@Serializable
enum class BlendMode { NORMAL, MULTIPLY, SCREEN }

/**
 * A single VIDEO track holding [clips] — the shape a document had before tracks existed, as a track.
 *
 * Kept as a named function rather than inlined at each call site because "one video track with these
 * clips" is how most of this codebase builds a document (fixtures, tests, the migration), and a
 * constructor spelled out a hundred times is a hundred chances to get the kind or the id wrong.
 */
fun videoTrack(clips: List<Clip>): Track =
    Track(id = Track.MAIN_ID, kind = TrackKind.VIDEO, items = clips)

/** [videoTrack] for the hand-built documents of tests, where the clips are written out one by one. */
fun videoTrack(vararg clips: Clip): Track = videoTrack(clips.toList())
