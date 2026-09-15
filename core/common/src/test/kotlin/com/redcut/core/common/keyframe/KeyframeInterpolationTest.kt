package com.redcut.core.common.keyframe

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The interpolation every keyframed property is resolved with — and therefore the value both preview
 * and export agree on (spec §12.3). These are the cases that must not drift: a constant, the clamp at
 * either end, and the straight line between two keys, where a rounding error would show up as a crop
 * that sits a hair off in the export but not the preview.
 */
class KeyframeInterpolationTest {

    private val oneSecond = 1_000_000L

    private fun key(timeUs: Long, value: Float) = KeyframeSample(timeUs, value)

    @Test
    fun `one key is a constant at every time`() {
        // The coexistence rule: a property keyed once holds that key's value forever, so "constant" and
        // "animated" are one storage shape. A single key must answer the same value everywhere, or the
        // resolver would treat "one key" as a degenerate two-key animation.
        val keys = listOf(key(oneSecond, 0.25f))

        assertThat(KeyframeInterpolation.linear(keys, 0L)).isEqualTo(0.25f)
        assertThat(KeyframeInterpolation.linear(keys, oneSecond)).isEqualTo(0.25f)
        assertThat(KeyframeInterpolation.linear(keys, 5 * oneSecond)).isEqualTo(0.25f)
    }

    @Test
    fun `before the first key the value is the first key's`() {
        // A crop that starts animating mid-clip must hold its starting value for the run-up, not snap to
        // a value nothing asked for or extrapolate backwards off the curve.
        val keys = listOf(key(2 * oneSecond, 0.5f), key(4 * oneSecond, 1.0f))

        assertThat(KeyframeInterpolation.linear(keys, 0L)).isEqualTo(0.5f)
        assertThat(KeyframeInterpolation.linear(keys, oneSecond)).isEqualTo(0.5f)
    }

    @Test
    fun `after the last key the value is the last key's`() {
        val keys = listOf(key(2 * oneSecond, 0.5f), key(4 * oneSecond, 1.0f))

        assertThat(KeyframeInterpolation.linear(keys, 4 * oneSecond)).isEqualTo(1.0f)
        assertThat(KeyframeInterpolation.linear(keys, 9 * oneSecond)).isEqualTo(1.0f)
    }

    @Test
    fun `between two keys the value moves linearly`() {
        // Halfway across the gap, half the distance; a quarter of the way, a quarter. The linear claim
        // in one line — the value at the midpoint of (0.5, 1.0) over 2 s is 0.75.
        val keys = listOf(key(0L, 0.5f), key(2 * oneSecond, 1.0f))

        assertThat(KeyframeInterpolation.linear(keys, oneSecond)).isEqualTo(0.75f)
        assertThat(KeyframeInterpolation.linear(keys, 500_000L)).isEqualTo(0.625f)
    }

    @Test
    fun `an unsorted key list is refused rather than silently misread`() {
        // The document keeps keys sorted and unique, so an unsorted list is a caller bug. Refusing it
        // here turns that bug into a fast, named failure instead of a crop that interpolates between the
        // wrong neighbours.
        val outOfOrder = listOf(key(4 * oneSecond, 1.0f), key(2 * oneSecond, 0.5f))

        assertThrows(IllegalArgumentException::class.java) {
            KeyframeInterpolation.linear(outOfOrder, oneSecond)
        }
    }

    @Test
    fun `an empty key list is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyframeInterpolation.linear(emptyList(), 0L)
        }
    }
}
