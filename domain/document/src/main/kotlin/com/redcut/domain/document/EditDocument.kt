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
 * where it sits *on the timeline* is derived — by [timeline] for the document
 * read as one lane, by [lanes] for each lane on its own clock. Storing either is
 * the classic source of "the timeline went stale after a ripple edit" bugs.
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
    /**
     * The other timelines this document holds — EMPTY in v3, and read by nothing.
     *
     * ### Why an empty hook is worth a schema change
     *
     * Nesting a sequence is the one model change that could not be added later without reshaping the
     * document. Today the document IS the timeline, so "a project with two timelines" has nowhere to put
     * the second one, and the day it arrives every reader would have to change at once. The hook costs a
     * key written as `[]` and nothing to ignore — the same reasoning the spec uses for
     * `SourceRef.uri: String`. If it is still empty in a year, it is a line of JSON.
     */
    val sequences: List<SequenceSpec> = emptyList(),
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
     * Each lane places its own clips ([Track.positionedClips]), and the cursor there advances over the
     * lane's ITEMS, so a [Gap] is room the timeline spends: it moves every clip after it later even
     * though it produces no slot of its own. Cheap (a few dozen additions) and called on every compile,
     * so it is deliberately NOT cached — a cache here would be a correctness liability for no
     * measurable gain.
     *
     * Still the FLAT reading: the lanes are walked in document order, so the second lane's clips
     * are placed after the first lane's rather than beside them. That is what a one-lane document
     * has always meant, and it gave the frame stepping and the commands their answer while the
     * lanes were introduced. [lanes] is the reading a caller with more than one lane wants: it
     * places each lane on its own clock, so lane 2 starts at 0 like lane 1 — the lanes run in
     * PARALLEL, and that is the whole of the difference between the two readings.
     *
     * This one is not deleted, and not flipped here, because flipping it is not arithmetic. With
     * two lanes there is no single answer to "which clip is the playhead on" — a position is on
     * one clip per lane, and this list holds only one of them, so whichever per-lane order it
     * picked would be an invented tie-break. The question becomes well posed when an intent says
     * which lane it acts on, which is [EditCommand]'s own shape and WS C6's task; until the
     * intents carry a track id, a per-lane [timeline] would only move the ambiguity into every
     * caller that reads it.
     *
     * "After" means after everything the lane OCCUPIES, not after its last clip: each lane is
     * shifted by the [Track.contentEndUs] of the lanes before it, which keeps this list's slots
     * and the room the lanes spend telling the same story. Shifting by the last CLIP instead left
     * the two disagreeing the moment a lane ended in a gap — the last slot ended one gap earlier
     * than the lane claimed to run, exactly the kind of pair of answers a caller ends up trusting
     * the wrong one of. With one lane the last slot still ends at [durationUs]; with more, this
     * list runs past it, because end to end is not the length of a timeline whose lanes play at
     * once.
     */
    val timeline: List<TimelineSlot>
        get() {
            var laneOffsetUs = 0L
            var index = 0
            return buildList {
                tracks.forEach { track ->
                    track.positionedClips().forEach { placed ->
                        add(
                            TimelineSlot(
                                clip = placed.clip,
                                index = index++,
                                startUs = laneOffsetUs + placed.startUs,
                                endUs = laneOffsetUs + placed.endUs,
                            ),
                        )
                    }
                    laneOffsetUs += track.contentEndUs
                }
            }
        }

    /**
     * Total playback duration after trims and speed changes, the gaps included.
     *
     * The LONGEST lane, because the lanes run in parallel: two lanes ending at 3 s and 5 s are 5 s
     * of video, not 8. A document whose longest lane ends in a [Gap] is as long as the room it
     * shows rather than only as long as its clips, since a lane's extent is [Track.contentEndUs].
     *
     * What this replaced was the flat SUM of the lanes' extents, which is the same number while a
     * document has one lane and a lie the moment it has two: the sum grows every time a lane is
     * added, and an export would carry the extra seconds of nothing at the end of it. The one-lane
     * document is the case the change had to leave alone, and it does — the longest of one lane IS
     * that lane, so every project that exists reads back the number it always did.
     *
     * A document with no lanes at all is 0, which is an empty lane's own extent too. [lanes] gives
     * the per-lane breakdown this is the maximum of.
     */
    val durationUs: Long get() = tracks.maxOfOrNull { it.contentEndUs } ?: 0L

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
         * `2` is the tracks version: the clips moved inside [Track], so a file written by that build
         * says `tracks` where the one before it said `clips`. `3` is the items version: a track's
         * contents became [TrackItem]s ([Clip] or [Gap]), the lanes gained the attributes [Track]
         * carries, and the document gained [sequences]. Neither is a compatible read, which is why
         * :domain:project's codec migrates an older file on the way in rather than letting the keys it
         * no longer names fall on the floor. `4` is the keyframes version: clips gained a `keyframes`
         * map (WS K). Unlike the first two it IS a compatible read — the field is defaulted empty, so a
         * v3 file decodes with no keyframes and only its stamp is advanced (see [promotedFromV3]).
         */
        const val SCHEMA_VERSION = 4
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
 *
 * What it writes is the lane's ITEMS, so a lane that held a [Gap] comes back with it gone: the clip list
 * a command hands over is the whole lane. Nothing builds a gap yet — the commands that insert and honour
 * them are the next workstream — and when one does, this is the function that has to learn about them.
 *
 * A caller that DOES know about gaps should hand over items instead (see [withTrackItems]); this overload
 * stays because "the lane is exactly these clips" is what the clip commands mean, and rewriting them to
 * carry items would make every one of them responsible for a hole none of them can create.
 */
internal fun EditDocument.withTrackClips(trackId: String, clips: List<Clip>): EditDocument =
    withTrackItems(trackId, clips)

/**
 * Replaces the lane's whole item list — clips AND gaps.
 *
 * The gap-aware twin of [withTrackClips], for the commands whose subject IS the lane's item list rather
 * than a set of clips in it: `MergeTrackClips` walks the items and ends a run at a gap, so writing the
 * result back through the clip-only overload would delete exactly the hole it went to the trouble of
 * respecting.
 */
internal fun EditDocument.withTrackItems(trackId: String, items: List<TrackItem>): EditDocument =
    copy(tracks = tracks.map { if (it.id == trackId) it.copy(items = items) else it })

/**
 * Replaces the effect with the same id as [effect], in place, or leaves the document alone.
 *
 * The effect stack's half of what [withTrackClips] is to a lane, and it exists for the same reason: the
 * list is ordered (render order IS list order) and an effect is addressed by id, so "swap this one for
 * that one" is the only write a caption command needs — and a writer that rebuilt the list instead could
 * reorder the stack by accident, which the user would see as a caption that jumped in front of another.
 *
 * A whole [AppliedEffect] rather than a lambda, so the caller cannot change the id on the way through: the
 * match is by the value handed in, and an effect whose id no longer exists leaves the document untouched,
 * the same way every other unmet precondition in a command does.
 */
internal fun EditDocument.withEffect(effect: AppliedEffect): EditDocument =
    if (effects.none { it.id == effect.id }) {
        this
    } else {
        copy(effects = effects.map { if (it.id == effect.id) effect else it })
    }

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

/**
 * A named timeline inside a document: its own lanes, and its own canvas.
 *
 * ### Why this is called SequenceSpec and not Sequence
 *
 * `kotlin.sequences.Sequence` is a default import, so a domain type of that name would collide in every
 * file that uses both — and the one that won would be whichever the file did not mean. The suffix is the
 * one [TransformSpec] and [ColorAdjustSpec] already carry: a spec is the DATA of a thing, and the thing
 * itself is built from it.
 *
 * v3 defines this and puts nothing in it (see [EditDocument.sequences]): it is the shape a nested
 * timeline would take, named now so that the migration which fills it is not also the migration that
 * invents it.
 */
@Serializable
data class SequenceSpec(
    val id: String,
    val name: String,
    val tracks: List<Track> = emptyList(),
    val canvas: CanvasSpec = CanvasSpec.PORTRAIT_1080,
)

/** Output frame geometry: the 720p/1080p pairs plus the social-ratio canvas presets (FR-3.x). */
@Serializable
enum class CanvasSpec(val width: Int, val height: Int) {
    /** 9:16, 720p-class. */
    PORTRAIT_720(720, 1280),

    /** 9:16, 1080p-class. */
    PORTRAIT_1080(1080, 1920),

    /** 16:9, 720p-class. */
    LANDSCAPE_720(1280, 720),

    /** 16:9, 1080p-class. */
    LANDSCAPE_1080(1920, 1080),

    /** 1:1 square, 1080-class – Instagram feed / TikTok. */
    SQUARE_1080(1080, 1080),

    /** 4:5 portrait, 1080-class – Instagram portrait. */
    PORTRAIT_4_5_1080(1080, 1350),

    /** 3:4 portrait, 1080-class – classic photo ratio. */
    PORTRAIT_3_4_1080(1080, 1440),

    /** 4:3 landscape, 1080-class – classic photo ratio. */
    LANDSCAPE_4_3_1080(1440, 1080),
    ;

    val isPortrait: Boolean get() = height > width

    fun withLandscape(isLandscape: Boolean): CanvasSpec = if (isLandscape == !isPortrait) {
        this
    } else {
        entries.first {
            it.isPortrait == !isLandscape && it.width != it.height &&
                minOf(it.width, it.height) == minOf(width, height)
        }
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
