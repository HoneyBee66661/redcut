package com.redcut.domain.document

/**
 * The frame the preview should show, and where to get it (FR-2's "correct preview").
 *
 * ### Why the preview needs a mapping rather than a position
 *
 * The playhead is a position on the TIMELINE. The frame it refers to lives at a position in a SOURCE
 * file, and the journey between them crosses everything the Cut stage can change: the clip's trims
 * (where in the file it starts), its speed (how fast the file is consumed), and its direction. A
 * preview that seeked the file to the playhead's time would be right only for an untrimmed, unmodified,
 * forward clip — and wrong in a way that is hard to notice, because it is off by exactly the amount
 * the user trimmed.
 *
 * So this is the same mapping the trim and the filmstrip use ([Clip.sourceTimeFor]), in one named
 * function, with tests.
 */
data class PreviewTarget(
    val clipId: String,
    val sourceId: String,
    /** The file to show, as the platform understands it (a `content://` uri). */
    val uri: String,
    /** Where in that file the frame is. */
    val sourceTimeUs: Long,
)

/**
 * What the preview shows when the playhead is at [playheadUs], or null when it is past the end of the
 * timeline (nothing to show, which the UI renders as a blank stage rather than a stale frame).
 */
fun EditDocument.previewTargetAt(playheadUs: Long): PreviewTarget? {
    val clip = clipAt(playheadUs) ?: return null
    val source = sources.firstOrNull { it.id == clip.sourceId } ?: return null
    val offsetUs = offsetIntoClip(clip.id, playheadUs) ?: return null
    return PreviewTarget(
        clipId = clip.id,
        sourceId = clip.sourceId,
        uri = source.uri,
        sourceTimeUs = clip.sourceTimeFor(offsetUs),
    )
}
