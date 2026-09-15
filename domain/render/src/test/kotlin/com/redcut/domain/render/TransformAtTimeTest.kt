package com.redcut.domain.render

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.Clip
import com.redcut.domain.document.FitMode
import com.redcut.domain.document.KeyframableProperty
import com.redcut.domain.document.Keyframe
import com.redcut.domain.document.TransformSpec
import org.junit.jupiter.api.Test

/**
 * The clip transform as a function of time (WS G1): the fold of a clip's static [TransformSpec] and its
 * key map that both render paths read.
 *
 * ### What these cases pin, and why the interpolation itself is not re-tested here
 *
 * The math is verified where it lives — `:core:common`'s `KeyframeInterpolationTest` — and the bridge
 * to the document's own key type is verified one level down in [KeyframeResolverTest]. What is left,
 * and what is genuinely new with this workstream, is the COEXISTENCE: a clip holds its transform twice
 * over, and every case below is a different way the two halves have to agree.
 *
 * - a property with no keys IS the static field, and must not be perturbed by a sibling that is keyed;
 * - one key is a constant, so "animated" and "static" are degrees of the same storage shape rather
 *   than two kinds of clip;
 * - the parts of the transform that CANNOT be interpolated — the flip switches and the fit mode —
 *   have to come through an animation untouched, because there is no key that could carry them.
 *
 * ### The last two cases are about totality, not arithmetic
 *
 * [transformAt] is called from a composable and from the export path, so a throw out of it is a crash
 * on a drag or a failed export halfway through — and there are two ways to reach one: an animation
 * that drives the crop's edges past each other, and a key holding a value that is not a number. Both
 * are constructible documents, so both are answered here rather than left to a caller's `try`.
 */
class TransformAtTimeTest {

    /**
     * Every field the keyframable five do NOT cover is set away from its default, so a fold that
     * dropped one shows up as a difference rather than as a coincidence.
     */
    private val resting = TransformSpec(
        cropLeft = 0.1f,
        cropTop = 0.2f,
        cropRight = 0.9f,
        cropBottom = 0.8f,
        rotationDegrees = 30f,
        flipHorizontal = true,
        flipVertical = true,
        fit = FitMode.FILL,
    )

    private fun clipWith(
        keyframes: Map<KeyframableProperty, List<Keyframe>>,
        transform: TransformSpec = resting,
    ): Clip = clip("c1", "s1", 0L, 2 * SEC).copy(transform = transform, keyframes = keyframes)

    @Test
    fun `a clip with no keys resolves to its static transform`() {
        val clip = clipWith(keyframes = emptyMap())

        // Every time, not just the playhead: with no keys there is nothing for the clock to change, and
        // the whole static half of this workstream (FR-3.5 to FR-3.8) is this case.
        assertThat(clip.transformAt(0L)).isEqualTo(resting)
        assertThat(clip.transformAt(SEC)).isEqualTo(resting)
        assertThat(clip.transformAt(9 * SEC)).isEqualTo(resting)
    }

    @Test
    fun `one key holds its value at every time`() {
        // Spec FR-3 note / §13.1: one key is a constant. This is what lets the model treat a keyed
        // property and a static one as the same shape instead of special-casing a single-key curve.
        val clip = clipWith(
            keyframes = mapOf(
                KeyframableProperty.CROP_LEFT to listOf(Keyframe(SEC, 0.25f)),
            ),
        )

        assertThat(clip.transformAt(0L).cropLeft).isEqualTo(0.25f)
        assertThat(clip.transformAt(SEC).cropLeft).isEqualTo(0.25f)
        assertThat(clip.transformAt(5 * SEC).cropLeft).isEqualTo(0.25f)
    }

    @Test
    fun `two keys interpolate linearly between them`() {
        val clip = clipWith(
            keyframes = mapOf(
                KeyframableProperty.ROTATION_DEGREES to listOf(
                    Keyframe(0L, 0f),
                    Keyframe(2 * SEC, 90f),
                ),
            ),
        )

        // The midpoint is the case a nearest-key implementation gets wrong and a linear one gets right.
        assertThat(clip.transformAt(0L).rotationDegrees).isEqualTo(0f)
        assertThat(clip.transformAt(SEC).rotationDegrees).isEqualTo(45f)
        assertThat(clip.transformAt(2 * SEC).rotationDegrees).isEqualTo(90f)
    }

    @Test
    fun `outside the key range the value clamps to the nearest key`() {
        val clip = clipWith(
            keyframes = mapOf(
                KeyframableProperty.CROP_TOP to listOf(
                    Keyframe(SEC, 0.3f),
                    Keyframe(2 * SEC, 0.6f),
                ),
            ),
        )

        // A keyframed property that starts mid-clip holds its first key's value for the run-up rather
        // than snapping to a value nothing asked for, and holds its last past the end for the same
        // reason. The negative time is the preview reading a playhead that has not reached the clip.
        assertThat(clip.transformAt(0L).cropTop).isEqualTo(0.3f)
        assertThat(clip.transformAt(-SEC).cropTop).isEqualTo(0.3f)
        assertThat(clip.transformAt(3 * SEC).cropTop).isEqualTo(0.6f)
    }

    @Test
    fun `a property without keys keeps its static value while another one animates`() {
        val clip = clipWith(
            keyframes = mapOf(
                KeyframableProperty.CROP_LEFT to listOf(
                    Keyframe(0L, 0f),
                    Keyframe(2 * SEC, 0.4f),
                ),
            ),
        )

        val halfway = clip.transformAt(SEC)

        // The keyed half moved...
        assertThat(halfway.cropLeft).isEqualTo(0.2f)
        // ...and the four properties with no keys are the clip's own values, untouched. This is the
        // coexistence rule as a picture: an animated crop does not disturb the resting rotation, and a
        // clip that keys only its crop still crops where the user left the other three edges.
        assertThat(halfway.cropTop).isEqualTo(resting.cropTop)
        assertThat(halfway.cropRight).isEqualTo(resting.cropRight)
        assertThat(halfway.cropBottom).isEqualTo(resting.cropBottom)
        assertThat(halfway.rotationDegrees).isEqualTo(resting.rotationDegrees)
    }

    @Test
    fun `the flip switches and the fit mode survive an animation untouched`() {
        val clip = clipWith(
            keyframes = mapOf(
                KeyframableProperty.CROP_LEFT to listOf(
                    Keyframe(0L, 0f),
                    Keyframe(2 * SEC, 0.4f),
                ),
                KeyframableProperty.ROTATION_DEGREES to listOf(
                    Keyframe(0L, 0f),
                    Keyframe(2 * SEC, 180f),
                ),
            ),
        )

        // A boolean and an enum cannot be interpolated, so neither is a KeyframableProperty and there
        // is no key that could carry them. [TransformSpec.copy] is the only thing that gets them from
        // the static spec to the resolved one, and a fold that rebuilt the spec field by field instead
        // of copying it would silently reset the user's flip and their canvas fit.
        val halfway = clip.transformAt(SEC)

        assertThat(halfway.flipHorizontal).isTrue()
        assertThat(halfway.flipVertical).isTrue()
        assertThat(halfway.fit).isEqualTo(FitMode.FILL)
    }

    @Test
    fun `a keyed crop driven past its right edge stays a rect the model accepts`() {
        // The left edge is animated past the static right edge, which linear interpolation is perfectly
        // happy to do and TransformSpec's constructor is right to refuse: a rect with a negative width
        // is not a crop. Reading the transform must not throw.
        val clip = clipWith(
            keyframes = mapOf(
                KeyframableProperty.CROP_LEFT to listOf(
                    Keyframe(0L, 0f),
                    Keyframe(2 * SEC, 0.95f),
                ),
            ),
        )

        val crossed = clip.transformAt(2 * SEC)

        assertThat(crossed.cropRight).isGreaterThan(crossed.cropLeft)
        // Held OPEN at the minimum span rather than closed to nothing or snapped back to a width the
        // rect had several frames ago: the crossing reads as the rect closing, which is what the user
        // authored, and it stays a legal rect at every instant on the way there.
        assertThat(crossed.cropRight - crossed.cropLeft).isLessThan(0.001f)
        assertThat(crossed.cropLeft).isGreaterThan(0.9f)
    }

    @Test
    fun `a key that is not a number falls back to the static edge`() {
        // Nothing in the document model forbids a NaN key, and NaN survives every comparison and every
        // coerceIn — so without a guard it reaches TransformSpec's require and throws from there, at a
        // place that names neither the key nor the clip. The static edge is the "unchanged" reading.
        val clip = clipWith(
            keyframes = mapOf(
                KeyframableProperty.CROP_LEFT to listOf(Keyframe(0L, Float.NaN)),
            ),
        )

        val resolved = clip.transformAt(SEC)

        assertThat(resolved.cropLeft).isEqualTo(resting.cropLeft)
        assertThat(resolved.cropRight).isEqualTo(resting.cropRight)
    }

    @Test
    fun `a property keyed with an empty list reads as its static value`() {
        // SetKeyframes removes the entry for an empty list, but it is not the only way a document is
        // made, and the interpolation requires a non-empty key list. "Keyed with nothing" reads as "not
        // keyed" — the answer the coexistence rule already gives — rather than as a throw.
        val clip = clipWith(
            keyframes = mapOf(KeyframableProperty.CROP_LEFT to emptyList()),
        )

        assertThat(clip.transformAt(SEC)).isEqualTo(resting)
    }
}
