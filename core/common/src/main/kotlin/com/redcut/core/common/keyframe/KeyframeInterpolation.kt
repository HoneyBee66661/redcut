package com.redcut.core.common.keyframe

/**
 * How a keyframed property is evaluated at a moment in time.
 *
 * ### The contract the caller must hold
 *
 * [linear] takes the keys in ASCENDING [KeyframeSample.timeUs] order, with no two keys on the same time.
 * The document keeps each property's keys sorted and unique when it stores them (a key is added at the
 * playhead in time order), so every caller that feeds this function a stored property already satisfies
 * the requirement; the `require` below is the guard that turns a malformed list into a fast failure in a
 * unit test rather than a flickering crop in the renderer.
 *
 * ### The interpolation
 *
 * Linear, and nothing else yet. Linear is the recommendation the keyframe spec settled on first (§13.1:
 * *"linear to start, eased later"*): it is the interpolation that needs no curve shape in the model, so
 * it is the one that can ship without a schema addition, and an eased curve can be added later as a
 * second method without changing what a key IS.
 *
 * Between two neighbours the value moves in a straight line from [KeyframeSample.value] of the left key
 * to that of the right key as time crosses the gap. Before the first key the value is the FIRST key's —
 * a keyframed crop that starts mid-clip holds its starting value for the run-up, rather than snapping to
 * a value nothing asked for. After the last key the value is the LAST key's, for the same reason.
 *
 * ### One key is a constant
 *
 * The coexistence rule for "a single value and a keyframed property" (spec FR-3 note, §13.1): a property
 * keyed with exactly one key is a CONSTANT — it holds that key's value at every time, and evaluating it
 * is indistinguishable from a static property. That is what lets the model treat "animated" and
 * "constant" as degrees of the same thing rather than two storage shapes, and it is the reason the
 * document keeps the static property beside the key map instead of replacing it (see
 * [com.redcut.domain.document.Clip.keyframes]): a keyframed property overrides the static value where it
 * has keys, and a property with no keys IS the static value, with no special case in the resolver.
 */
object KeyframeInterpolation {

    /**
     * The value of the keyframed property at [timeUs], under linear interpolation.
     *
     * [samples] must be non-empty and strictly increasing in time (see the class KDoc). A single sample
     * yields its own value at every time. The result is deterministic for a given list and time, which is
     * the property preview/export parity depends on (spec §12.3): the same resolved value feeds both
     * paths, and this function is the thing that makes that a promise rather than a hope.
     */
    fun linear(samples: List<KeyframeSample>, timeUs: Long): Float {
        require(samples.isNotEmpty()) { "a keyframed property needs at least one key, was empty" }
        require(samples.zipWithNext().all { it.first.timeUs < it.second.timeUs }) {
            "keys must be strictly increasing in time, was ${samples.map { it.timeUs }}"
        }
        if (samples.size == 1) return samples[0].value

        val first = samples.first()
        val last = samples.last()
        if (timeUs <= first.timeUs) return first.value
        if (timeUs >= last.timeUs) return last.value

        var index = 1
        while (samples[index].timeUs <= timeUs) index++
        val right = samples[index]
        val left = samples[index - 1]
        val span = right.timeUs - left.timeUs
        val t = (timeUs - left.timeUs).toDouble() / span
        return left.value + (right.value - left.value) * t.toFloat()
    }
}
