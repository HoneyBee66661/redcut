package com.redcut.feature.editor

import com.redcut.core.common.timeline.ClipTiming
import com.redcut.domain.document.EditDocument

/**
 * The document as the timeline's geometry needs it (spec §5.1, §7.1).
 *
 * This one-line mapping is the seam between the domain and the pure geometry in `:core:common`,
 * and it exists as a named function for two reasons:
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

/** The timeline's total duration: where the last clip ends. */
internal val EditDocument.timelineDurationUs: Long
    get() = clips.sumOf { it.timelineDurationUs }
