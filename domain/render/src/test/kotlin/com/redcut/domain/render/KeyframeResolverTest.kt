package com.redcut.domain.render

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.Keyframe
import org.junit.jupiter.api.Test

/**
 * The domain-level resolver both render paths share (WS K).
 *
 * The interpolation itself is verified where it lives — :core:common's `KeyframeInterpolationTest` —
 * so these cases are about the BRIDGE: that the resolver turns the document's serializable [Keyframe]s
 * into the same answers, which is the contract preview and export both depend on. One key is a constant,
 * and the ends clamp — the two behaviours whose loss would silently turn an animation into a jump.
 */
class KeyframeResolverTest {

    private val oneSecond = 1_000_000L

    @Test
    fun `one key is a constant at every time`() {
        // The coexistence rule (spec §13.1): a single key means the property holds that value forever.
        val keys = listOf(Keyframe(oneSecond, 0.25f))

        assertThat(KeyframeResolver.resolve(keys, 0L)).isEqualTo(0.25f)
        assertThat(KeyframeResolver.resolve(keys, oneSecond)).isEqualTo(0.25f)
        assertThat(KeyframeResolver.resolve(keys, 5 * oneSecond)).isEqualTo(0.25f)
    }

    @Test
    fun `the value clamps to the nearest key outside the range`() {
        val keys = listOf(Keyframe(0L, 0.5f), Keyframe(2 * oneSecond, 1.0f))

        assertThat(KeyframeResolver.resolve(keys, -1L)).isEqualTo(0.5f)
        assertThat(KeyframeResolver.resolve(keys, 0L)).isEqualTo(0.5f)
        assertThat(KeyframeResolver.resolve(keys, 3 * oneSecond)).isEqualTo(1.0f)
    }
}
