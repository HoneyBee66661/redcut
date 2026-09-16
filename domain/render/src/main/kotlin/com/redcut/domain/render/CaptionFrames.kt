package com.redcut.domain.render

import com.redcut.domain.document.AppliedEffect
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.EffectScope
import com.redcut.domain.document.TextOverlayBox
import com.redcut.domain.document.TextSpec
import com.redcut.domain.document.TimeRange
import com.redcut.domain.document.toOverlayBox

/**
 * One caption, resolved to everything a renderer needs to paint it: the words, their style, their box on
 * the canvas, and the range they are visible for (FR-4.3).
 *
 * A value rather than a function-per-question, because the render side consumes a caption WHOLE — a
 * frame of video is composited from the picture plus every caption's ink in one pass, and a reader that
 * asked separately for style, position and timing would draw a caption assembled from three reads at
 * three moments. Everything a burn-in needs travels together, which is also what makes the value the
 * unit the parity tests compare.
 *
 * [spec] and [box] are the document's own values, carried verbatim: this is a RESOLUTION of the document,
 * not an interpretation of it. Nothing here substitutes defaults for missing fields — [TextSpec] is
 * total by construction, every style field defaulted at the model.
 */
data class CaptionFrame(
    /** The effect the frame was resolved from, so a caller can name it (selection, commands, logs). */
    val effectId: String,
    /** The words and their presentation — font, size, colour, stroke, background, alignment. */
    val spec: TextSpec,
    /** Where the words sit, in canvas fractions. See [TextOverlayBox]. */
    val box: TextOverlayBox,
    /** The range the caption is visible for, absolute on the compiled timeline. */
    val timeRange: TimeRange,
)

/**
 * The captions visible at [playheadUs], resolved and in stack order — the ONE read both the preview and
 * the export burn-in make of the document's captions.
 *
 * ### Why this lives in `:domain:render`, and why the preview asks it too
 *
 * The rule this file exists to keep is the one [KeyframeResolver] already states: one resolver feeds the
 * preview and the export identically, and a second opinion is how the two paths drift. A caption is
 * composited output the same way a keyframed crop is, so the same principle applies at the same seam —
 * the preview (which draws the frames of this list over the player's surface) and the export lane's
 * Transformer burn-in (which paints the same frames into the 1080p encode) MUST read the same list, and
 * the only way to guarantee it is for both to call this one function.
 *
 * The burn-in itself is the export lane's work, not this module's: an `:engine`/`:feature:export`
 * consumer takes the frames this resolver yields at each output timestamp and paints them onto the
 * encoder's canvas. This function is the seam that makes that a consumer of a resolved value rather than
 * a second reader of the document.
 *
 * ### The three clauses, inherited from the document's own filter
 *
 * ENABLED (a disabled effect renders nothing), DOCUMENT-SCOPED (an absolute range needs no rebasing;
 * a clip-scoped caption is a different feature), and IN RANGE, half-open — a caption ending exactly at
 * the playhead is over, one starting exactly at it is on. The clauses are the document's
 * `textOverlaysAt` rather than restated here: this resolver reads the model's answer and resolves the
 * boxes on top of it, so the two layers cannot disagree about WHICH captions are visible.
 */
fun EditDocument.captionFramesAt(playheadUs: Long): List<CaptionFrame> =
    effects.filterIsInstance<AppliedEffect.Text>()
        .filter { caption ->
            caption.enabled &&
                caption.scope == EffectScope.Document &&
                playheadUs in caption.timeRange
        }
        .map { caption ->
            CaptionFrame(
                effectId = caption.id,
                spec = caption.spec,
                box = caption.transform.toOverlayBox(),
                timeRange = caption.timeRange,
            )
        }
