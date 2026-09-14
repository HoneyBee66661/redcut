package com.redcut.domain.document

/**
 * One lane read as its own timeline: the track it is, and the clips it places on its own clock.
 *
 * A lane is a thing the user sees run from the head of the video, so its clips start where
 * [Track.positionedClips] puts them — from 0, over the lane's own items — and the second lane is
 * not shifted by the first: the lanes run in PARALLEL. That is the whole of the difference from
 * [EditDocument.timeline], whose walk lays the lanes end to end because end to end is what a
 * one-lane document has always meant.
 *
 * [slots] reuses [TimelineSlot] rather than introducing a second placement type: what a lane
 * changes is where its clips land, not what a placed clip IS. The field whose meaning travels with
 * the reading is [TimelineSlot.index]: the index within THIS lane, so two lanes both have a slot 0,
 * the same way they both have a start of 0. The flat reading's indices run across the whole
 * document, so the two are not comparable, and a caller that mixed them would be numbering the
 * lanes as one list.
 */
data class LaneTimeline(
    /** The id of the [Track]: [EditDocument.trackById] resolves it back to the lane. */
    val trackId: String,
    /**
     * How long this lane runs: the end of its last ITEM, a trailing [Gap] included.
     *
     * [Track.contentEndUs] and not the end of the last slot, for the reason that property exists: a
     * lane that finishes with a gap still spends that room, and a ruler drawn to the last clip
     * would stop short of it. A lane holding nothing runs for no time at all.
     */
    val durationUs: Long,
    /** This lane's clips in playback order, each with the start the lane's own items give it. */
    val slots: List<TimelineSlot>,
)

/**
 * Every lane read as its own timeline, in the order the body stacks them.
 *
 * The reading a caller with lanes should use, and the one [EditDocument.timeline] points at: entry
 * 1 is lane 1's clips BESIDE lane 0's rather than after them, so a second lane draws and compiles
 * OVER the first instead of extending the project. [EditDocument.timeline] cannot say that — it is
 * one list, and two lanes overlapping in it would have to interleave — which is why both readings
 * exist and why neither is the other's bug.
 *
 * ### An empty lane appears, and that is the point
 *
 * A track with no clips — a fresh document's video lane, a lane whose clips were all deleted — is
 * in the list with no slots and a duration of 0. There is one entry per TRACK, so `lanes.indices`
 * and `tracks.indices` are the same index: a caller walking the body's lanes and a caller walking
 * this reading are looking at the same lane. Dropping the empty ones would quietly renumber the
 * rest, and the bug that produces is lane 2's clips drawn as lane 1's.
 *
 * Not cached, for the reason [EditDocument.timeline] is not: a few dozen additions, called on every
 * compile, and a cache here would be a correctness liability for no measurable gain.
 */
val EditDocument.lanes: List<LaneTimeline>
    get() = tracks.map { track ->
        LaneTimeline(
            trackId = track.id,
            durationUs = track.contentEndUs,
            slots = track.positionedClips().mapIndexed { index, placed ->
                TimelineSlot(
                    clip = placed.clip,
                    index = index,
                    startUs = placed.startUs,
                    endUs = placed.endUs,
                )
            },
        )
    }
