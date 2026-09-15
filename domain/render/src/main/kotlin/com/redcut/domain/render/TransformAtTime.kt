package com.redcut.domain.render

import com.redcut.domain.document.Clip
import com.redcut.domain.document.KeyframableProperty
import com.redcut.domain.document.Keyframe
import com.redcut.domain.document.TransformSpec

/**
 * [transform] with the five keyframable properties replaced by their keyed values at [timeUs] — THE
 * one answer the preview and the export both read (spec §12.3, §13.1).
 *
 * [timeUs] is on the clip's own clock, microseconds from the start of the clip the keys belong to
 * ([Keyframe.timeUs]); for an export frame that is `frameUs - layer.timeRange.startUs`.
 *
 * ### Why this is a sibling of [KeyframeResolver] and not part of it
 *
 * [KeyframeResolver] answers "given these keys, what is this property worth at t". It knows nothing
 * about transforms, deliberately: it is the resolver every keyframed property will use. What it
 * cannot answer is the question the render paths actually ask — "what does this CLIP look like at t"
 * — because that mixes two storage shapes. A clip carries its transform twice over, on purpose: the
 * static [TransformSpec] is the value a property has when it has NO keys, and the key map overrides
 * that property where keys exist (the coexistence rule, `Clip.keyframes`'s own KDoc). A caller that
 * wants the whole transform has to fold the two together, and that fold is what this function is.
 *
 * ### The failure mode this exists to prevent
 *
 * A crop animated in preview but static in export — the defect [KeyframeResolver] was built for, one
 * level up. The resolver can be called correctly by both paths and the bug still happen, because the
 * callers disagree about WHICH TIME to ask at, or about whether to ask at all: the export reads the
 * layer's `transform`, the preview interpolates the clip's keys, and the two agree until the clip is
 * keyed. So the fold gets one home and both callers reach it by name — [Clip.transformAt] is the
 * preview's shape of the question (clip-local time from the playhead), [RenderLayer.Video.transformAt]
 * the export's (layer-local time from an output frame), and both land here.
 *
 * ### The no-keys fast path is the identity
 *
 * A clip with no keys returns its own [TransformSpec] — not an equal copy, the same value — so every
 * document written before keyframes renders exactly as it did, and the static half of this workstream
 * (FR-3.5–3.8: rotate, flip, crop, canvas fit) is this same code path rather than a parallel one. The
 * flip switches and the fit MODE ride through untouched in every case: a boolean and an enum cannot be
 * interpolated, so neither is a [KeyframableProperty], and [TransformSpec.copy] carries them.
 */
fun transformAt(
    transform: TransformSpec,
    keyframes: Map<KeyframableProperty, List<Keyframe>>,
    timeUs: Long,
): TransformSpec {
    // The static half, and the path every un-keyframed clip in every existing project takes. The same
    // value rather than a copy, so this cannot move a frame of anything already built.
    if (keyframes.isEmpty()) return transform

    val horizontal = cropPair(
        staticStart = transform.cropLeft,
        staticEnd = transform.cropRight,
        start = KeyframableProperty.CROP_LEFT.valueAt(transform, keyframes, timeUs),
        end = KeyframableProperty.CROP_RIGHT.valueAt(transform, keyframes, timeUs),
    )
    val vertical = cropPair(
        staticStart = transform.cropTop,
        staticEnd = transform.cropBottom,
        start = KeyframableProperty.CROP_TOP.valueAt(transform, keyframes, timeUs),
        end = KeyframableProperty.CROP_BOTTOM.valueAt(transform, keyframes, timeUs),
    )

    return transform.copy(
        cropLeft = horizontal.first,
        cropTop = vertical.first,
        cropRight = horizontal.second,
        cropBottom = vertical.second,
        rotationDegrees = KeyframableProperty.ROTATION_DEGREES
            .valueAt(transform, keyframes, timeUs),
    )
}

/**
 * [this] property of a clip at [timeUs]: its keyed curve where the clip has keys for it, and its
 * static field on [transform] where it does not.
 *
 * The empty-list check is not defensive noise. `KeyframeInterpolation` requires a non-empty key list,
 * and "the property is keyed but with nothing in it" is a shape a hand-built or hand-edited document
 * can hold — `SetKeyframes` removes the entry for an empty list, but it is not the only way a document
 * is made. Reading that as "not keyed" is the answer the coexistence rule already gives, without a
 * throw in between.
 */
fun KeyframableProperty.valueAt(
    transform: TransformSpec,
    keyframes: Map<KeyframableProperty, List<Keyframe>>,
    timeUs: Long,
): Float {
    val keys = keyframes[this].orEmpty()
    if (keys.isEmpty()) return staticValueIn(transform)
    return KeyframeResolver.resolve(keys, timeUs)
}

/**
 * The value [this] property holds on [transform] when it has no keys.
 *
 * The only place the five keyframable properties are mapped onto their [TransformSpec] fields. A
 * `when` over the enum rather than a lookup table, so adding a property to [KeyframableProperty] fails
 * to compile here rather than silently resolving to a value no one chose.
 */
fun KeyframableProperty.staticValueIn(transform: TransformSpec): Float = when (this) {
    KeyframableProperty.CROP_LEFT -> transform.cropLeft
    KeyframableProperty.CROP_TOP -> transform.cropTop
    KeyframableProperty.CROP_RIGHT -> transform.cropRight
    KeyframableProperty.CROP_BOTTOM -> transform.cropBottom
    KeyframableProperty.ROTATION_DEGREES -> transform.rotationDegrees
}

/**
 * The clip's transform at [localUs], in microseconds from the START OF THE CLIP.
 *
 * The preview's shape of the question (spec §13): the playhead is a timeline position and the keys are
 * on the clip's own clock, so the caller makes the one subtraction that turns the first into the
 * second — the same subtraction the transport already makes to decide where a key would land.
 */
fun Clip.transformAt(localUs: Long): TransformSpec = transformAt(transform, keyframes, localUs)

/**
 * The layer's transform at [localUs], in microseconds from the START OF THE LAYER.
 *
 * The export's shape of the question: a frame's timeline time is `timeRange.startUs + localUs`. The
 * keys on a layer are the clip's own, carried by the compiler without moving, so a layer's local clock
 * IS the clip's local clock — which is what makes the preview's answer and the export's answer the
 * same function of the same number of microseconds into the same clip.
 */
fun RenderLayer.Video.transformAt(localUs: Long): TransformSpec =
    transformAt(transform, keyframes, localUs)

/**
 * A resolved crop pair, bounded to the frame and legal for [TransformSpec] to hold.
 *
 * Three things stand between "two interpolated floats" and "a crop rect the renderer can draw", and
 * this is the one place all three are settled. None of them may throw out of here: the two callers are
 * a composable and the export mapper, so an exception in the first is a crash on a drag and in the
 * second a failed export halfway through.
 *
 * - **Out of frame.** The crop edges are FRACTIONS of the frame by definition
 *   (`KeyframableProperty.CROP_LEFT`'s own KDoc), but a key is a bare Float and nothing stops one
 *   holding 1.5. They are bounded to `0..1` here — the same bound the viewport applies when it turns a
 *   gesture into a crop (`ViewportRect.toTransformSpec`).
 * - **Crossed.** Interpolation is linear per property and knows nothing about the pair, so an
 *   animation can drive the right edge past the left. [TransformSpec] refuses that in its constructor,
 *   correctly — a rect with a negative width is not a crop — so the crossing is resolved as what it
 *   geometrically is: the rect has CLOSED, and it is held at the narrowest rect that still has a
 *   width. It is anchored at the left/top edge because that is the edge the defaults anchor the rect
 *   to, and anchoring there keeps the value continuous as the right edge crosses (the sliver stays
 *   under the edge that did the crossing) instead of snapping back to a width the rect had frames ago.
 * - **Not a number.** Nothing in the document model forbids a NaN key, and NaN survives every
 *   comparison and every `coerceIn`, so it would reach the constructor's `require` and throw from
 *   there. That edge falls back to the static value — the "unchanged" reading, the policy
 *   [TimelineCompiler] takes for the same hazard on a clip's gain.
 */
private fun cropPair(
    staticStart: Float,
    staticEnd: Float,
    start: Float,
    end: Float,
): Pair<Float, Float> {
    val left = cropEdge(start, staticStart)
    val right = cropEdge(end, staticEnd)
    if (right > left) return left to right

    // `left` is inside 0..1, so pulling it down by the minimum span cannot leave the frame and the
    // right edge lands back at or below the ceiling. Both bounds hold by construction, not by hope.
    val anchored = left.coerceAtMost(CROP_MAX - MIN_CROP_SPAN)
    return anchored to (anchored + MIN_CROP_SPAN)
}

/** [value] bounded to the frame, or [fallback] when it is not a number. */
private fun cropEdge(value: Float, fallback: Float): Float =
    (if (value.isNaN()) fallback else value).coerceIn(CROP_MIN, CROP_MAX)

/** The frame's own bounds, which a crop edge is a fraction of. */
private const val CROP_MIN = 0f
private const val CROP_MAX = 1f

/**
 * The narrowest rect a keyed crop is allowed to close to: a ten-thousandth of the frame.
 *
 * Small enough that a rect held here is not a crop anyone would author, and so is visibly the
 * degenerate case rather than a value mistaken for a real one; large enough to survive Float
 * arithmetic at the frame's scale instead of rounding away to a zero width.
 */
private const val MIN_CROP_SPAN = 0.0001f
