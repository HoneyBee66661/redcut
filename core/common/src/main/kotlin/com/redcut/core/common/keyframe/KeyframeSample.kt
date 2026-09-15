package com.redcut.core.common.keyframe

/**
 * One point on a keyframed property's curve: a value held at a moment in time.
 *
 * ### Why this type lives here and not in :domain:document
 *
 * :domain:document's [com.redcut.domain.document.Keyframe] is the WIRE type — the value a project file
 * stores, and therefore the one that carries `@Serializable`. This type is the MATH type: the smallest
 * thing the interpolation routines in [KeyframeInterpolation] can operate on. Keeping the two apart is
 * what lets the interpolation live in :core:common, which must not know about the document (spec §4.1
 * rule 1, and the dependency runs the other way — the same boundary [Timebase] records). The document's
 * keyed property is read as a list of these wherever a value is resolved, and no serialization concern
 * leaks into the pure math.
 *
 * [timeUs] is on the same axis the rest of the document keeps time in — microseconds from the start of
 * the thing the property belongs to (a clip, in the current model). [value] is the property's value at
 * that time, in the property's own units (a crop edge is a 0..1 fraction, rotation is degrees).
 */
data class KeyframeSample(
    val timeUs: Long,
    val value: Float,
)
