package com.redcut.domain.render

import com.redcut.core.common.keyframe.KeyframeInterpolation
import com.redcut.domain.document.Keyframe

/**
 * The ONE resolver a keyframed property's value is read through — the guarantee that preview and export
 * cannot disagree about it (spec §13, Phase 3 exit criterion: *"effect timing is identical after
 * export"*, and §12.3: parity is a test, not a hope).
 *
 * ### The failure mode this exists to prevent
 *
 * A crop animated in preview but static in export. Both paths need "what is this property worth at time
 * t", and if each path grew its own answer — the preview interpolating from the clip's keyframes, the
 * export reading the static transform — they would agree while the clip is not keyframed and part
 * company the moment it is, with no test that noticed. This object is the single answer: [resolve] is
 * the only function that turns a key list and a time into a value, and both the preview path and the
 * export pipeline call it.
 *
 * ### Why it is a thin wrapper over :core:common's interpolation
 *
 * The interpolation itself is pure math and lives in :core:common (`KeyframeInterpolation.linear`),
 * which must not know about the document. This object is the bridge: it takes the domain's serializable
 * [Keyframe], hands the math tier the same key as its own sample type ([Keyframe.toSample]), and is the
 * one place both render paths reach for. That keeps the interpolation total over the document's type and
 * keeps the "which path calls it" question to a single, greppable answer.
 *
 * A single key is a constant, the ends clamp to the first and last key's value, and keys must be stored
 * sorted and unique — all the contract of `KeyframeInterpolation.linear`, which [resolve] preserves.
 * The static-vs-keyed coexistence (a property with no keys IS the static transform value) is the
 * consumer's decision: this resolver answers "given the keys, what is the value", and a caller with no
 * keys does not ask it.
 */
object KeyframeResolver {

    /** The value of the keyframed property at [timeUs], under linear interpolation. */
    fun resolve(keys: List<Keyframe>, timeUs: Long): Float =
        KeyframeInterpolation.linear(keys.map { it.toSample() }, timeUs)
}
