package com.redcut.domain.document

import com.redcut.core.common.keyframe.KeyframeSample
import kotlinx.serialization.Serializable

/**
 * One key on a keyframed property's curve: a value held at a moment in time.
 *
 * This is the WIRE type — the value a project file stores, and therefore the serializable one. The
 * interpolation that reads it lives in :core:common as [KeyframeSample] and
 * `KeyframeInterpolation`; the resolver that bridges the two is in :domain:render, which is the one
 * place both preview and export ask "what is this property worth now" (spec §12.3).
 *
 * [timeUs] is on the same clock as the rest of the document — microseconds from the start of the clip
 * the property belongs to — and [value] is in the property's own units.
 */
@Serializable
data class Keyframe(
    val timeUs: Long,
    val value: Float,
) {
    /** The same key as the math tier's [KeyframeSample], for the resolver to interpolate. */
    fun toSample(): KeyframeSample = KeyframeSample(timeUs, value)
}

/**
 * The clip properties that can be keyframed, in this build.
 *
 * ### Which properties, and why these FIRST
 *
 * The keyframe workstream's decision (spec §13.1) is to open with the TRANSFORM / CROP block only, and
 * to bring effect parameters (brightness, LUT strength, …) later. Crop is the property whose animation a
 * viewer can actually see — a crop rect moving across a clip is the demo the MVP ships — and it is also
 * the one whose keys are pure [Keyframe.Float]s with no shape of their own, so it needs nothing beyond a
 * key list. The transform block's flip switches and fit MODE are not here: a boolean or an enum cannot be
 * interpolated, and keyframing them would need a different key shape, which this build does not carry.
 *
 * Rotation is included with the crop edges because it is a float in the same transform block and animates
 * the same way; effect parameters join the enum when their lane lands them in the model.
 *
 * ### Why an enum and not strings
 *
 * The compiler is the reason. A string key would let two callers disagree about how a property is named —
 * "cropLeft" vs "crop_left" — and the disagreement would surface as a key that is never read, silently
 * dropping the user's animation. An enum closes the vocabulary to the five properties this build can
 * actually render keyframed, and a property that is not keyframable simply has no value here, which is
 * the whole point: the UI reads THIS enum to decide what the transport's diamond can key (it cannot
 * fabricate a property the model does not name). New properties extend the enum, and the additive rule
 * keeps old files readable (a future enum value is a wire value, so it is added deliberately, the same
 * rule [TrackKind] records).
 */
@Serializable
enum class KeyframableProperty {
    /** The left edge of the visible crop, as a 0..1 fraction. */
    CROP_LEFT,

    /** The top edge of the visible crop, as a 0..1 fraction. */
    CROP_TOP,

    /** The right edge of the visible crop, as a 0..1 fraction. */
    CROP_RIGHT,

    /** The bottom edge of the visible crop, as a 0..1 fraction. */
    CROP_BOTTOM,

    /** The clip's rotation, in degrees. */
    ROTATION_DEGREES,
}
