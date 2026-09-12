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
 */
@Serializable
data class EditDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val sources: List<SourceRef> = emptyList(),
    val clips: List<Clip> = emptyList(),
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
     * existed still loads unchanged. [SCHEMA_VERSION] stays `1`.
     */
    val revision: Long = 0L,
) {
    /**
     * Clips paired with their derived timeline positions, in playback order.
     *
     * Computed by prefix-summing each clip's speed-adjusted duration. Cheap (a
     * few dozen additions) and called on every compile, so it is deliberately
     * NOT cached — a cache here would be a correctness liability for no
     * measurable gain.
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

    fun clipById(clipId: String): Clip? = clips.firstOrNull { it.id == clipId }

    fun sourceById(sourceId: String): SourceRef? = sources.firstOrNull { it.id == sourceId }

    /** True when this document is safe to hand to the render pipeline. */
    fun isRenderable(): Boolean =
        clips.isNotEmpty() && clips.all { sourceById(it.sourceId) != null }

    companion object {
        /**
         * Written from day one. Retrofitting a version field after users have
         * projects on disk is not possible (spec §10.2).
         */
        const val SCHEMA_VERSION = 1
    }
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

    operator fun contains(positionUs: Long): Boolean =
        positionUs >= startUs && positionUs < endUs
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

    fun withLandscape(isLandscape: Boolean): CanvasSpec =
        if (isLandscape == !isPortrait) this
        else entries.first { it.isPortrait == !isLandscape && it.width == minOf(width, height) }
}

/** A half-open range in microseconds: [startUs, endUs). */
@Serializable
data class TimeRange(val startUs: Long, val endUs: Long) {
    init {
        require(startUs >= 0) { "startUs must be >= 0, was $startUs" }
        require(endUs > startUs) { "endUs ($endUs) must exceed startUs ($startUs)" }
    }

    val durationUs: Long get() = endUs - startUs

    operator fun contains(positionUs: Long): Boolean =
        positionUs >= startUs && positionUs < endUs
}
