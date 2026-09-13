package com.redcut.domain.document

import kotlinx.serialization.Serializable

/**
 * The single source of truth for an edit.
 *
 * The three stages (Cut / Edit / Effect) are *views* over this one object, never
 * separate documents and never a one-way pipeline. That is what makes jumping
 * Cut -> Effect -> Cut free and lossless, and why adding a fourth stage later is
 * a UI change rather than a schema migration.
 *
 * Immutable by construction: every mutation produces a new instance, which is
 * what makes snapshot undo viable (a snapshot is a pointer copy, not a deep
 * clone) and what makes concurrent reads safe without locking.
 *
 * Positions are NEVER stored. A clip records where it reads *from the source*;
 * where it sits *on the timeline* is derived by [timeline]. Storing both is the
 * classic source of "the timeline went stale after a ripple edit" bugs.
 *
 * ### Tracks, since schema v2
 *
 * The document is a list of [Track]s, and a clip belongs to exactly one of them. [clips] is a derived
 * view over that list, so the many places that only need "every clip, in order" keep working — but it
 * is a READ: a caller that wants to change a clip names the track it is changing (see [EditCommand]),
 * because the one thing a flat rewrite cannot express is which lane a new clip belongs to.
 */
@Serializable
data class EditDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val sources: List<SourceRef> = emptyList(),
    /**
     * The lanes of the timeline, in the order the body stacks them (index 0 is the lowest).
     *
     * The default is one empty VIDEO track rather than an empty list: a new document, and a document
     * read from a file that predates tracks, is a project with a video lane waiting for its first clip
     * — not a project with no timeline at all. An empty list is still legal (the audio-only document of
     * the audio workstream is close to it), which is why the model does not require one either.
     */
    val tracks: List<Track> = listOf(Track.MAIN),
    val effects: List<AppliedEffect> = emptyList(),
    val canvas: CanvasSpec = CanvasSpec.PORTRAIT_1080,
    val createdAtMs: Long = 0L,
    val modifiedAtMs: Long = 0L,
    /**
     * The identity of this document's current *state*, and the recompilation
     * trigger (spec §1.1, §8.1).
     *
     * Spec §1.1 draws `EditDocument` as "immutable, versioned", and spec §8.1
     * names the trigger explicitly: "Recompilation triggers: any
     * `EditDocument.revision` change. Compilation is pure and cheap (< 1 ms for
     * typical projects), so it runs on every revision change without debouncing.
     * Preview rebuild is debounced at 120 ms during drags to avoid thrashing the
     * player." So this is a *state identity*, not a timestamp and not a save
     * counter: two documents with equal content but different revisions are
     * different states, and a downstream cache that sees the revision unchanged
     * is entitled to skip a recompile.
     *
     * [UndoStack] is the only thing that advances it. The stack is the single
     * mutation gateway, so it is the only place that can hand out a new state;
     * commands are pure and never touch this field — applying a command changes
     * content only (see [EditCommand]).
     *
     * Serialized with a default of `0`, so a document written before the field
     * existed still loads unchanged.
     */
    val revision: Long = 0L,
) {
    /**
     * Every clip the document holds, in track order and then in-track order.
     *
     * A VIEW, not a stored field: the clips live in their [Track], and this is the flattened reading of
     * them that the timeline maths, the render compiler and the frame stepping were all written
     * against. It is `get`-only on purpose — a caller that writes a plain list back has to say which
     * track it is writing to, and the compiler is what enforces that.
     */
    val clips: List<Clip> get() = tracks.flatMap { it.clips }

    /**
     * Clips paired with their derived timeline positions, in playback order.
     *
     * Computed by prefix-summing each clip's speed-adjusted duration. Cheap (a
     * few dozen additions) and called on every compile, so it is deliberately
     * NOT cached — a cache here would be a correctness liability for no
     * measurable gain.
     *
     * Still the FLAT reading since v2: the sum runs across [clips], so a second track's clips are
     * placed after the first track's rather than beside them. That is what a single-lane document has
     * always meant and it is what keeps the render graph and the frame stepping unchanged while the
     * lanes are introduced; a prefix sum PER track is the next step, and it is the one that makes the
     * lanes overlap in time.
     */
    val timeline: List<TimelineSlot>
        get() {
            var cursor = 0L
            return clips.mapIndexed { index, clip ->
                val start = cursor
                cursor += clip.timelineDurationUs
                TimelineSlot(clip = clip, index = index, startUs = start, endUs = cursor)
            }
        }

    /** Total playback duration after trims and speed changes. */
    val durationUs: Long
        get() = clips.sumOf { it.timelineDurationUs }

    /** The slot containing [positionUs], clamped to the last slot at the end. */
    fun slotAt(positionUs: Long): TimelineSlot? =
        timeline.firstOrNull { positionUs >= it.startUs && positionUs < it.endUs }
            ?: timeline.lastOrNull()?.takeIf { positionUs >= it.endUs }

    /**
     * The clip with [clipId], wherever it is.
     *
     * Document-wide rather than per-track, deliberately: clip ids are unique across the document
     * (every command refuses an id that is already taken), so "which clip is this" has one answer and
     * the reader does not have to know the lane to ask. A command is the opposite case — it must name
     * the track it writes to — which is why [EditCommand]s carry a track id of their own.
     */
    fun clipById(clipId: String): Clip? = clips.firstOrNull { it.id == clipId }

    /** The track with [trackId], or null when this document has no such lane. */
    fun trackById(trackId: String): Track? = tracks.firstOrNull { it.id == trackId }

    /** The track holding [clipId], or null when no track does. */
    fun trackOf(clipId: String): Track? = tracks.firstOrNull { it.clipById(clipId) != null }

    /** The id of the track holding [clipId], or null. The answer [EditCommand]s need to be built. */
    fun trackIdOf(clipId: String): String? = trackOf(clipId)?.id

    /**
     * The lane a video import lands on: the document's first VIDEO track, or the default one.
     *
     * A derived default rather than a stored field, because it is not a decision the user made — it is
     * what "import" means today: a new clip joins the video lane. It is the fallback that matters when
     * a document has no video lane at all (an audio-only project, which the audio workstream makes
     * legal): importing video into one is a question that workstream answers, and until then the
     * planner is handed the id of the lane that a document of this shape would have.
     */
    val importTrackId: String
        get() = tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.id ?: Track.MAIN_ID

    fun sourceById(sourceId: String): SourceRef? = sources.firstOrNull { it.id == sourceId }

    /** True when this document is safe to hand to the render pipeline. */
    fun isRenderable(): Boolean =
        clips.isNotEmpty() && clips.all { sourceById(it.sourceId) != null }

    companion object {
        /**
         * Written from day one. Retrofitting a version field after users have
         * projects on disk is not possible (spec §10.2).
         *
         * `2` is the tracks version: the clips moved inside [Track], so a file written by this build
         * says `tracks` where the previous one said `clips`. That is not a compatible read, which is
         * why :domain:project's codec migrates a v1 file on the way in rather than letting the old
         * list fall on the floor.
         */
        const val SCHEMA_VERSION = 2
    }
}

/**
 * Replaces the clips of the track [trackId], leaving every other lane exactly as it was.
 *
 * The one write path the commands share. [EditDocument.clips] is derived — and `get`-only — so the
 * compiler names every writer, and what a writer must supply is the lane it is writing to: a tracked
 * rewrite cannot move a clip between lanes by accident, which is the whole reason the clips moved
 * inside a [Track] rather than staying a flat list beside one.
 *
 * A track id the document does not have leaves the document untouched, the same way every other
 * unmet precondition in a command does. Nothing is created here: a lane is the document's own
 * structure, and a command that invented one would be editing a timeline the user cannot see.
 */
internal fun EditDocument.withTrackClips(trackId: String, clips: List<Clip>): EditDocument =
    copy(tracks = tracks.map { if (it.id == trackId) it.copy(clips = clips) else it })

/**
 * A clip as placed on the timeline: the clip plus where it lands.
 * [startUs]/[endUs] are derived, never stored — see [EditDocument.timeline].
 */
data class TimelineSlot(
    val clip: Clip,
    val index: Int,
    val startUs: Long,
    val endUs: Long,
) {
    val durationUs: Long get() = endUs - startUs

    operator fun contains(positionUs: Long): Boolean = positionUs >= startUs && positionUs < endUs
}

/** Output frame geometry. MVP ships only 720p and 1080p (FR-5.1). */
@Serializable
enum class CanvasSpec(val width: Int, val height: Int) {
    PORTRAIT_720(720, 1280),
    PORTRAIT_1080(1080, 1920),
    LANDSCAPE_720(1280, 720),
    LANDSCAPE_1080(1920, 1080),
    ;

    val isPortrait: Boolean get() = height > width

    fun withLandscape(isLandscape: Boolean): CanvasSpec = if (isLandscape == !isPortrait) {
        this
    } else {
        entries.first { it.isPortrait == !isLandscape && it.width == minOf(width, height) }
    }
}

/** A half-open range in microseconds: [startUs, endUs). */
@Serializable
data class TimeRange(val startUs: Long, val endUs: Long) {
    init {
        require(startUs >= 0) { "startUs must be >= 0, was $startUs" }
        require(endUs > startUs) { "endUs ($endUs) must exceed startUs ($startUs)" }
    }

    val durationUs: Long get() = endUs - startUs

    operator fun contains(positionUs: Long): Boolean = positionUs >= startUs && positionUs < endUs
}
