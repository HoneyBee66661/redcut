package com.redcut.feature.editor

import com.redcut.core.common.timeline.ClipSpan
import com.redcut.core.common.timeline.ClipTiming
import com.redcut.core.common.timeline.LaneSpans
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.positionedClips

/**
 * The document as the timeline's geometry needs it (spec §5.1, §7.1).
 *
 * This one-line mapping is the seam between the domain and the pure geometry in `:core:common`,
 * and it exists as a named function for two reasons ([laneSpans] is the same crossing for a document
 * with tracks, and the same reasons hold for it):
 *
 * 1. `:core:common` may not import the domain — dependencies in this repo point one way — so
 *    something has to do the crossing, and it should be somewhere a test can see it. Reading the
 *    wrong field here (`durationUs` instead of `timelineDurationUs`, say) would draw a timeline
 *    whose clips are the wrong width for a speed-changed clip, and nothing else would complain.
 * 2. Timeline position is DERIVED, never stored (§5.1): `spansOf` computes each clip's start by
 *    prefix-summing what this function returns, so this function must return durations and
 *    nothing that looks like a position.
 */
internal fun EditDocument.toClipTimings(): List<ClipTiming> =
    clips.map { clip -> ClipTiming(clipId = clip.id, timelineDurationUs = clip.timelineDurationUs) }

/**
 * The same crossing, for a document that has TRACKS: one lane per track, in the order they stack.
 *
 * The sibling of [toClipTimings], and it exists because that function cannot answer this
 * question: it prefix-sums one flat list, so two tracks' clips would be laid AFTER each
 * other rather than BESIDE each other — which is the whole difference between the
 * geometry's flat reading and its lane reading, and a bug a one-track project can never
 * show.
 *
 * The starts come from [Track.positionedClips], the domain's own cursor — which advances
 * over gaps as well as clips — rather than from a second sum written here. A lane that
 * summed its own starts could disagree with the document about where a clip begins, and
 * the disagreement would be invisible until a gap moved one of them.
 *
 * A track with no clips yields a lane with no spans, and that is a lane the geometry
 * still draws: an empty track waiting for its first clip is track, not a hole.
 */
internal fun EditDocument.laneSpans(): List<LaneSpans> = tracks.map { track ->
    LaneSpans(
        trackId = track.id,
        spans = track.positionedClips().map { placed ->
            ClipSpan(
                clipId = placed.clip.id,
                startUs = placed.startUs,
                durationUs = placed.clip.timelineDurationUs,
            )
        },
    )
}
