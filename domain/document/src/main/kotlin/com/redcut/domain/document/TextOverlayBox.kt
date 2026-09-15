package com.redcut.domain.document

/**
 * A text overlay's BOX on the canvas (FR-4.3): where a caption sits, in canvas fractions.
 *
 * ### Why the position is a rect, and where it is stored
 *
 * The model already carries the position. [AppliedEffect.Text.transform] is a [TransformSpec], and the
 * only rect-shaped thing on it is the crop — `cropLeft`..`cropBottom`. So a caption's frame IS that
 * rect, the same way a clip's crop is a rect on the same canvas, and placing a caption needed nothing
 * added to the schema: the field was already there and already defaulted.
 *
 * A `positionX`/`positionY` pair was the alternative and is rejected. It would be a SECOND description
 * of "where" — meaningful only to overlays, sitting beside the one clips already use — and
 * [TransformSpec]'s own KDoc makes the opposite promise: one fold, read through the render tier's
 * resolver, rather than a field per carrier. It would also lose the box, and the box is doing two jobs
 * a point cannot.
 *
 * ### What the box buys that a point cannot
 *
 * [TextAlignment] aligns text INSIDE something, and the box is that something: START is its left edge,
 * END its right, CENTER its middle — which is what turns an otherwise inert field on [TextSpec] into
 * something the preview draws differently. And the box's extent is what a later card's resize gesture
 * changes, with no model change at all (spec task 3.6's "drag/resize on preview").
 *
 * ### The clamp, and what a full-frame box means
 *
 * [movedToCentre] keeps the box inside `[0, 1]` — the same clamp [ViewportRect] applies to the
 * preview's crop rect, for the same reason: a caption dragged off the canvas is a caption the user
 * cannot grab again. A box at least as wide (or tall) as the canvas has nowhere to go and is pinned to
 * the centre, which is exactly what a default `TransformSpec()` means for an overlay: legal, drawn
 * centred, and immovable. [DEFAULT] is what a new caption gets instead, and the add command says why
 * the two defaults differ.
 */
data class TextOverlayBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        // The same two invariants TransformSpec states, restated where they are read rather than
        // trusted: an inverted rect would make `width` negative and the layout arithmetic below would
        // quietly place the text backwards instead of saying what was wrong.
        require(right > left) { "right ($right) must exceed left ($left)" }
        require(bottom > top) { "bottom ($bottom) must exceed top ($top)" }
    }

    val width: Float get() = right - left

    val height: Float get() = bottom - top

    val centerX: Float get() = left + width / HALF_DIVISOR

    val centerY: Float get() = top + height / HALF_DIVISOR

    /**
     * This box moved so that its centre lands on ([centerX], [centerY]), kept inside the canvas.
     *
     * Clamped rather than refused, for the reason every drag in this repo clamps: the gesture sends the
     * raw finger position every frame and expects the box to stop at the edge rather than to refuse to
     * move. A box with no room to move is pinned to the centre — see the type's KDoc.
     */
    fun movedToCentre(centerX: Float, centerY: Float): TextOverlayBox {
        val halfWidth = width / HALF_DIVISOR
        val halfHeight = height / HALF_DIVISOR
        // The degenerate branch is a guard against `coerceIn(min, max)` being handed min > max, which
        // throws: a box at least as large as the frame has exactly one legal centre.
        val newCenterX =
            if (halfWidth >= CENTER) CENTER else centerX.coerceIn(halfWidth, 1f - halfWidth)
        val newCenterY =
            if (halfHeight >= CENTER) CENTER else centerY.coerceIn(halfHeight, 1f - halfHeight)
        return TextOverlayBox(
            left = newCenterX - halfWidth,
            top = newCenterY - halfHeight,
            right = newCenterX + halfWidth,
            bottom = newCenterY + halfHeight,
        )
    }

    companion object {
        private const val HALF_DIVISOR = 2f

        /** The middle of the canvas, and the centre a box that cannot move is pinned to. */
        private const val CENTER = 0.5f

        /**
         * Where a new caption lands: a band across the lower third, inset from both edges.
         *
         * The inset is not decoration. A box flush with the frame could not be dragged at all (see the
         * clamp above), so the frame-filling `TransformSpec()` default — which is right for a clip, where
         * the crop rect IS the picture — is the one box a caption must not start in.
         */
        val DEFAULT = TextOverlayBox(left = 0.08f, top = 0.70f, right = 0.92f, bottom = 0.86f)
    }
}

/** This transform's crop rect, read as an overlay's box. See [TextOverlayBox]. */
fun TransformSpec.toOverlayBox(): TextOverlayBox =
    TextOverlayBox(left = cropLeft, top = cropTop, right = cropRight, bottom = cropBottom)

/**
 * This box written onto [base], so every field the box does not name survives the move.
 *
 * The `base` parameter is what keeps a caption's rotation and flip — carried on the same
 * [TransformSpec] — from being erased by a drag. The box is the half a drag edits; nothing else about
 * the transform is this type's business.
 */
fun TextOverlayBox.toTransform(base: TransformSpec = TransformSpec()): TransformSpec = base.copy(
    cropLeft = left,
    cropTop = top,
    cropRight = right,
    cropBottom = bottom,
)

/**
 * The text overlay with [effectId], or null when the stack has no such caption.
 *
 * Null for an effect of another KIND as well — a LUT is not a caption — so every caption command reads
 * as "find it, and do nothing when it is not there" without a second type check at each call site.
 */
fun EditDocument.textOverlayById(effectId: String): AppliedEffect.Text? =
    effects.firstOrNull { it.id == effectId } as? AppliedEffect.Text

/**
 * The captions VISIBLE at [playheadUs], in stack order. This is the list the preview draws.
 *
 * ### The three clauses, and why the range test needs no arithmetic
 *
 * ENABLED, because a disabled effect renders nothing and a preview that drew one would disagree with the
 * export about the same document.
 *
 * DOCUMENT-SCOPED, which is the clause that makes the third one cheap. An [EffectScope.Document] range is
 * absolute timeline time, so "is the playhead inside it" is `playheadUs in timeRange` — the same half-open
 * test ([TimeRange] excludes its end) the timeline uses to decide which clip the playhead is over, and
 * no subtraction anywhere. A [EffectScope.Clip] range is relative to its clip, so the same question has
 * a different answer that the render compiler already computes by rebasing the range onto the clip's
 * compiled start; the preview does not guess at it, and until a command can create a clip-scoped caption
 * there is nothing here for it to guess about.
 *
 * IN RANGE, half-open, matching the compiler: a caption ending exactly at the playhead is over, and one
 * starting exactly at it is on.
 */
fun EditDocument.textOverlaysAt(playheadUs: Long): List<AppliedEffect.Text> =
    effects.filterIsInstance<AppliedEffect.Text>().filter { caption ->
        caption.enabled &&
            caption.scope == EffectScope.Document &&
            playheadUs in caption.timeRange
    }
