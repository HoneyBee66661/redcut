package com.redcut.domain.document

/**
 * The caption commands (FR-4.3, spec task 3.6): add a text overlay, move it, retime it.
 *
 * ### Why a caption is a command at all
 *
 * A caption is document structure the same way a lane is ([AddTrack]) and a source is ([AddSource]), so
 * creating one has to be undoable, comparable, and made through the one mutation gateway — nothing here
 * reaches for `EditDocument.copy(effects = …)` at a call site, and the internal writer the commands share
 * is the only thing that touches the stack.
 *
 * ### Three commands rather than one "edit the caption"
 *
 * Because the three edits have three different gestures behind them — a button, a drag on the preview, and
 * (the inspector's card) a pair of timing fields — and one command per gesture is what makes each history
 * entry say what the user did: "Undo Add text", "Undo Move text", "Undo Text timing". A single command
 * carrying every field would label all three the same and make an undo of a drag look like an undo of the
 * caption.
 *
 * ### Document-scoped, always, and therefore no lane
 *
 * The scope is not a parameter: FR-4.3's caption is a caption on the PROJECT — a title, a lower third, a
 * sign-off — and the scope is what makes its [AppliedEffect.timeRange] absolute, which is what lets the
 * preview test the playhead against it with no arithmetic. A clip-scoped caption travels with one shot and
 * times itself against that shot; that is a different feature and it will arrive as its own command with
 * its own scope rather than as a flag on this one.
 *
 * So all three report [EditCommand.touchedTrackIds] EMPTY, and that is a claim rather than a default: the
 * effect stack is not a lane, so there is no lane whose CONTENTS these commands could disturb — and
 * contents are the only thing a lock protects. It is the same answer [AddTrack] gives for the same shape
 * of reason, and `CommandTargetsTest` names the three of them among the lane-free commands on purpose.
 */

/**
 * The shortest a caption may be (FR-4.3): the same 100 ms floor a clip has.
 *
 * Taken from [Clip.MIN_DURATION_US] by name rather than restated as a literal, because the two floors are
 * the same claim — below a tenth of a second there is nothing on screen to read, so a range that short is
 * a drag that overshot rather than an edit. The name is its own so a caption and a clip stay free to
 * diverge.
 */
const val MIN_TEXT_DURATION_US: Long = Clip.MIN_DURATION_US

/**
 * Adds a Document-scoped text overlay (FR-4.3).
 *
 * ### The id comes from the caller
 *
 * [effectId] is supplied rather than minted here, the rule every command in this package follows for a new
 * id: a command that invented one could not be compared, replayed or asserted on, and the caller's ids are
 * the ones a later "the caption I just added" has to name.
 *
 * ### Idempotent by id, which is what makes it safe to fire twice
 *
 * An id the stack already holds leaves the document alone — the rule [AddTrack] applies to a lane, and for
 * the same reason: a double tap, a replayed command or a retried intent must not append a second caption
 * on top of the first.
 *
 * ### Appended, not inserted
 *
 * At the END of the stack. Render order is stack order, and the caption a user has just added is the one
 * they expect over the ones already there — the reasoning [AddTrack] uses for putting a new lane on top.
 *
 * ### Two refusals, both of them states the user cannot see
 *
 * A blank [TextSpec.content] is refused rather than stored: an empty caption draws nothing and has no
 * bounds to press, so it would be an effect in the stack that the preview cannot show and the user cannot
 * find. A [timeRange] shorter than [MIN_TEXT_DURATION_US] is refused for the same shape of reason.
 *
 * [transform] defaults to a real caption band rather than to `TransformSpec()`, and the difference is
 * draggable: a default transform is the whole frame, which the box clamp pins to the centre. A caption
 * added without a position lands somewhere it can be dragged out of.
 */
data class AddTextOverlay(
    val effectId: String,
    val spec: TextSpec,
    val timeRange: TimeRange,
    val transform: TransformSpec = TextOverlayBox.DEFAULT.toTransform(),
) : EditCommand {
    override val label: String get() = "Add text"

    override fun apply(doc: EditDocument): EditDocument {
        // An id that is already taken is neither replaced nor duplicated.
        if (doc.effects.any { it.id == effectId }) return doc
        if (spec.content.isBlank()) return doc
        if (timeRange.durationUs < MIN_TEXT_DURATION_US) return doc
        val caption = AppliedEffect.Text(
            id = effectId,
            scope = EffectScope.Document,
            timeRange = timeRange,
            spec = spec,
            transform = transform,
        )
        return doc.copy(effects = doc.effects + caption)
    }

    /** No lane: a caption lives on the effect stack. See the file's KDoc. */
    override fun touchedTrackIds(document: EditDocument): Set<String> = emptySet()
}

/**
 * Moves a caption's box on the canvas (FR-4.3's "position by dragging on the preview").
 *
 * The whole box arrives rather than a delta, which is what keeps the command comparable and replayable:
 * re-applying it to the same document gives the same document, and a replay cannot depend on where the
 * caption happened to be when the gesture started. It is the same choice [SetTransform] makes for a clip.
 *
 * A transform the caption already holds is refused, so the last frames of a drag against the edge clamp —
 * where the finger keeps moving and the box does not — record nothing rather than a "Move text" entry
 * that changed no pixels.
 */
data class SetTextTransform(val effectId: String, val transform: TransformSpec) : EditCommand {
    override val label: String get() = "Move text"

    override fun apply(doc: EditDocument): EditDocument {
        val caption = doc.textOverlayById(effectId) ?: return doc
        if (caption.transform == transform) return doc
        return doc.withEffect(caption.copy(transform = transform))
    }

    override fun touchedTrackIds(document: EditDocument): Set<String> = emptySet()
}

/**
 * Sets a caption's start and end on the timeline (FR-4.3).
 *
 * ### Why two ends and not a [TimeRange]
 *
 * The values come from a control the user drags, and a drag OVERSHOOTS: it passes the other end, it goes
 * negative, it lands both edges on the same millisecond. [TimeRange] refuses all three by throwing, which
 * is right for a value a caller ASSERTS and wrong for a gesture — so this command CLAMPS, exactly as
 * [TrimClip] and [SetFades] do, and the caller reads what it actually got back out of the document.
 *
 * ### The clamp has two halves, and there is deliberately no ceiling
 *
 * The start never goes below zero, and the end never sits closer than [MIN_TEXT_DURATION_US] to the start.
 * There is NO clamp to the timeline's own length: a caption may run past the last clip, because the
 * render compiler intersects a Document-scoped overlay with the finished video anyway, and a project can
 * grow. Clamping here would silently shorten a caption the day the user appended footage to a project that
 * was briefly short of it.
 */
data class SetTextRange(val effectId: String, val startUs: Long, val endUs: Long) : EditCommand {
    override val label: String get() = "Text timing"

    override fun apply(doc: EditDocument): EditDocument {
        val caption = doc.textOverlayById(effectId) ?: return doc
        val range = clampedCaptionRange(startUs, endUs) ?: return doc
        if (caption.timeRange == range) return doc
        return doc.withEffect(caption.copy(timeRange = range))
    }

    override fun touchedTrackIds(document: EditDocument): Set<String> = emptySet()
}

/**
 * [startUs]..[endUs] as a range a caption may hold, or null when no range can.
 *
 * The null is the far end of a Long and not a realistic edit: [TimeRange] requires its end to EXCEED its
 * start, so a start with no room left above it has no legal range at all — and the command refuses rather
 * than letting a validation throw out of a function whose whole contract is that it never does (commands
 * are total; see [EditCommand]).
 */
internal fun clampedCaptionRange(startUs: Long, endUs: Long): TimeRange? {
    val start = startUs.coerceAtLeast(0L)
    if (start > Long.MAX_VALUE - MIN_TEXT_DURATION_US) return null
    return TimeRange(start, endUs.coerceAtLeast(start + MIN_TEXT_DURATION_US))
}
