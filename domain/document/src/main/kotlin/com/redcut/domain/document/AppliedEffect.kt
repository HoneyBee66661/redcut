package com.redcut.domain.document

import kotlinx.serialization.Serializable

/**
 * Where an effect applies (spec §5).
 *
 * Scope is a property of the effect rather than a nesting relationship, which is
 * what makes "move this effect from the clip to the whole document" a one-field
 * change instead of a structural edit. Effects are never nested inside clips.
 */
@Serializable
sealed interface EffectScope {
    /** Applies across the whole timeline. */
    @Serializable
    data object Document : EffectScope

    /** Applies to a single clip, identified by [clipId]. */
    @Serializable
    data class Clip(val clipId: String) : EffectScope
}

/**
 * A reference to a LUT asset, by opaque id.
 *
 * Deliberately NOT a path or a Uri: the domain is pure JVM (spec §4.1 rule 1), so
 * resolving this into something loadable is `:core:media`'s job. See §5.1 on why
 * LibreCuts' `android.net.Uri` in the domain is the mistake being avoided.
 */
@Serializable
data class LutRef(
    val id: String,
    val displayName: String = "",
)

/**
 * Brightness / contrast / saturation / temperature (spec §5, FR-4).
 *
 * Every field is a delta with 0 meaning "unchanged", rather than an absolute
 * multiplier. That makes identity detectable ([isIdentity]) and lets the render
 * compiler drop the whole adjustment when nothing was touched — which matters
 * because a no-op colour pass still costs a full-frame shader run.
 */
@Serializable
data class ColorAdjustSpec(
    /** -1f..1f. 0 = unchanged. */
    val brightness: Float = 0f,
    /** -1f..1f. 0 = unchanged. */
    val contrast: Float = 0f,
    /** -1f..1f. -1 = fully desaturated, 0 = unchanged. */
    val saturation: Float = 0f,
    /** -1f..1f. -1 = cooler, 0 = unchanged, +1 = warmer. */
    val temperature: Float = 0f,
) {
    init {
        require(brightness in -1f..1f) { "brightness must be in -1f..1f, was $brightness" }
        require(contrast in -1f..1f) { "contrast must be in -1f..1f, was $contrast" }
        require(saturation in -1f..1f) { "saturation must be in -1f..1f, was $saturation" }
        require(temperature in -1f..1f) { "temperature must be in -1f..1f, was $temperature" }
    }

    /** True when this adjustment would change nothing. */
    val isIdentity: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f && temperature == 0f
}

/** Horizontal alignment of a text overlay. */
@Serializable
enum class TextAlignment { START, CENTER, END }

/**
 * A static text overlay (spec §5, FR-4.3). No keyframes in MVP (§1.3).
 *
 * [colorArgb] is an Int rather than a Compose `Color` on purpose: Compose is an
 * Android/UI dependency and this module must stay on the JVM.
 */
@Serializable
data class TextSpec(
    val content: String,
    val fontSizeSp: Float = 48f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val alignment: TextAlignment = TextAlignment.CENTER,
) {
    init {
        require(fontSizeSp > 0f) { "fontSizeSp must be > 0, was $fontSizeSp" }
    }
}

/**
 * One entry in the document's effect stack (spec §5).
 *
 * The stack is a flat ordered list held on [EditDocument], with render order
 * equal to list order. Nesting effects inside clips was rejected because both
 * reordering the stack and re-scoping an effect are then list operations rather
 * than structural edits.
 *
 * [timeRange] is expressed within the scope: for [EffectScope.Document] it is an
 * absolute timeline range, for [EffectScope.Clip] it is relative to that clip.
 */
@Serializable
sealed interface AppliedEffect {
    val id: String
    val scope: EffectScope
    val timeRange: TimeRange
    val enabled: Boolean

    /** Colour grade from a LUT. [strength] blends it against the ungraded source. */
    @Serializable
    data class Lut(
        override val id: String,
        override val scope: EffectScope,
        override val timeRange: TimeRange,
        val ref: LutRef,
        override val enabled: Boolean = true,
        /** 0f..1f. 1 = fully applied. */
        val strength: Float = 1f,
    ) : AppliedEffect {
        init {
            require(strength in 0f..1f) { "strength must be in 0f..1f, was $strength" }
        }
    }

    /** Manual colour adjustment, combined with any LUT in stack order. */
    @Serializable
    data class Adjust(
        override val id: String,
        override val scope: EffectScope,
        override val timeRange: TimeRange,
        val spec: ColorAdjustSpec,
        override val enabled: Boolean = true,
    ) : AppliedEffect

    /** Text overlay. [transform] positions it in the canvas. */
    @Serializable
    data class Text(
        override val id: String,
        override val scope: EffectScope,
        override val timeRange: TimeRange,
        val spec: TextSpec,
        override val enabled: Boolean = true,
        val transform: TransformSpec = TransformSpec(),
    ) : AppliedEffect

    /** Image overlay (sticker / watermark). [sourceId] resolves via the document's sources. */
    @Serializable
    data class Image(
        override val id: String,
        override val scope: EffectScope,
        override val timeRange: TimeRange,
        val sourceId: String,
        override val enabled: Boolean = true,
        val transform: TransformSpec = TransformSpec(),
        /** 0f..1f. */
        val opacity: Float = 1f,
    ) : AppliedEffect {
        init {
            require(opacity in 0f..1f) { "opacity must be in 0f..1f, was $opacity" }
        }
    }

    /**
     * Cross-fade transition.
     *
     * [durationMs] is a stored duration, not a derived one, because a dissolve is
     * a property of the seam between two clips rather than of either clip alone.
     */
    @Serializable
    data class Dissolve(
        override val id: String,
        override val scope: EffectScope,
        override val timeRange: TimeRange,
        val durationMs: Long,
        override val enabled: Boolean = true,
    ) : AppliedEffect {
        init {
            require(durationMs > 0L) { "durationMs must be > 0, was $durationMs" }
        }
    }
}
