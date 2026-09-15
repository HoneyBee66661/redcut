package com.redcut.domain.render

import com.redcut.domain.document.CanvasSpec
import com.redcut.domain.document.ColorAdjustSpec
import com.redcut.domain.document.KeyframableProperty
import com.redcut.domain.document.Keyframe
import com.redcut.domain.document.LutRef
import com.redcut.domain.document.SourceRef
import com.redcut.domain.document.TextSpec
import com.redcut.domain.document.TimeRange
import com.redcut.domain.document.TransformSpec
import kotlinx.serialization.Serializable

/**
 * The platform-neutral description of what the finished video looks like
 * (spec §4.3).
 *
 * The domain never emits a codec command, a GL call, or a file path — it emits
 * this. Adapters consume it: `:engine:media3` maps it onto a Media3 `Composition`
 * for *both* preview and export, and the C++ core (Phase 5) consumes the same
 * value through its own bindings. Because one value feeds both paths, preview /
 * export parity is structural rather than aspirational (§12.3).
 *
 * ### Invariants, enforced in the constructor
 *
 * These are checked here rather than trusted to the compiler, because a broken
 * graph is a bug that would otherwise surface as a corrupted export or a preview
 * that disagrees with it — far from the code that caused it (the same reasoning
 * as the document invariants in §7.2).
 *
 * - every layer covers a non-empty range, and no range falls below
 *   [com.redcut.domain.document.Clip.MIN_DURATION_US];
 * - the video layers are **contiguous and ordered**: each starts exactly where the
 *   previous one ended. MVP is ripple-only and gapless (FR-2), and this require()
 *   is what makes a stray gap in the timeline a crash in a unit test instead of a
 *   frozen frame in a user's export. Overwrite mode is v2; relaxing this line is
 *   a deliberate edit, not an accident;
 * - every transition joins two *adjacent* video layers, and its `startUs` is
 *   exactly the incoming layer's start (a dissolve that is not on a seam has no
 *   meaning).
 */
@Serializable
data class RenderGraph(
    /**
     * The document revision this graph was compiled from (spec §8.1). Recompiling
     * is triggered by this value changing; it is copied, never invented, so a
     * graph and the document it came from can never disagree about which edit
     * they describe.
     */
    val revision: Long,
    val canvas: CanvasSpec,
    /** Video layers in timeline order, then overlays in effect-stack order. */
    val layers: List<RenderLayer>,
    val output: OutputSpec,
    val transitions: List<Transition> = emptyList(),
    val audio: AudioGraph = AudioGraph(),
) {
    /** The base video layers, in playback order. Index 0 is the first clip. */
    val videoLayers: List<RenderLayer.Video> get() = layers.filterIsInstance<RenderLayer.Video>()

    /** Text and image overlays, in render order (last = topmost). */
    val overlayLayers: List<RenderLayer> get() = layers.filterNot { it is RenderLayer.Video }

    /** Total playback duration. Derived, never stored — same rule as the document. */
    val durationUs: Long get() = videoLayers.lastOrNull()?.timeRange?.endUs ?: 0L

    init {
        var cursor: Long? = null
        videoLayers.forEach { layer ->
            require(layer.timeRange.endUs - layer.timeRange.startUs >= MIN_LAYER_US) {
                "video layer for clip ${layer.clipId} is " +
                    "${layer.timeRange.endUs - layer.timeRange.startUs}us, below the floor"
            }
            val expected = cursor
            if (expected != null) {
                require(layer.timeRange.startUs == expected) {
                    "video layers must be contiguous: clip ${layer.clipId} starts at " +
                        "${layer.timeRange.startUs}us but the previous layer ended at ${expected}us"
                }
            }
            cursor = layer.timeRange.endUs
        }

        transitions.forEach { transition ->
            require(transition.fromIndex in videoLayers.indices) {
                "transition fromIndex ${transition.fromIndex} is out of range"
            }
            // Checked before `videoLayers[toIndex]` is touched: without this, a
            // transition pointing one layer past the end throws an
            // IndexOutOfBoundsException from inside the loop below instead of naming
            // the graph that is wrong.
            require(transition.toIndex in videoLayers.indices) {
                "transition toIndex ${transition.toIndex} is out of range"
            }
            require(transition.toIndex == transition.fromIndex + 1) {
                "transition must join adjacent layers, was " +
                    "${transition.fromIndex} -> ${transition.toIndex}"
            }
            val incoming = videoLayers[transition.toIndex]
            require(transition.startUs == incoming.timeRange.startUs) {
                "transition startUs ${transition.startUs} is not the seam at " +
                    "${incoming.timeRange.startUs} (clip ${incoming.clipId})"
            }
        }

        require(transitions.map { it.toIndex }.distinct().size == transitions.size) {
            "at most one transition per seam"
        }
    }

    companion object {
        /** Mirrors the document's minimum clip length; a layer may not be shorter. */
        const val MIN_LAYER_US: Long = 100_000L
    }
}

/**
 * One visual element of the graph.
 *
 * [timeRange] is always **timeline** time (absolute, from the start of the
 * finished video), for every layer kind. That is what lets the mapper hand the
 * whole list to Media3 as a sequence without translating anything.
 *
 * ### Divergence from the spec sketch: no `enabled` flag
 *
 * The §4.3 sketch carries `enabled` on every layer. This type does not, because
 * the compiler *resolves* enablement rather than forwarding it: a disabled clip
 * is dropped from the graph (and the timeline ripples closed, consistent with
 * FR-2.7), a disabled effect never reaches a chain. A flag the compiler has
 * already decided would give the mapper a second source of truth for a decision
 * that is not its to make.
 */
@Serializable
sealed interface RenderLayer {
    val timeRange: TimeRange

    /**
     * One clip of the base video track.
     *
     * [clipId] is a deliberate addition to the spec sketch: the graph must be able
     * to name its own layers back to the document, or the timeline UI cannot
     * highlight "the clip under the playhead" and effect editing cannot resolve
     * "which layer does this clip own". Without it, that mapping is done by
     * positional inference in three different places.
     */
    @Serializable
    data class Video(
        val clipId: String,
        val source: SourceRef,
        /** Which slice of [source] to read, in SOURCE time. */
        val sourceRange: TimeRange,
        /** Where that slice lands on the timeline, in TIMELINE time. */
        override val timeRange: TimeRange,
        val speed: Float = 1f,
        val reverse: Boolean = false,
        val transform: TransformSpec = TransformSpec(),
        /** Ordered effect chain for this layer; render order = list order (FR-4.7). */
        val effects: List<RenderEffect> = emptyList(),
        /** Video fades, clamped by the compiler to the layer's duration. */
        val fades: FadeSpec = FadeSpec(),
        val audio: AudioSpec = AudioSpec(),
        /**
         * The clip's keyframed properties, carried onto the layer so the transform can be read PER
         * FRAME (WS K / WS G1).
         *
         * [transform] stays what it always was — the value a property holds when it has no keys — and
         * this map overrides the properties it names. That is the clip's own coexistence rule
         * (`Clip.keyframes`) restated at graph level, and it is restated rather than flattened into a
         * per-frame transform because a layer is a VALUE, not a frame: the graph has no frame rate and
         * no clock, so baking one time into it would freeze the animation at whichever moment happened
         * to be compiled — and would give the preview and the export two different frozen moments to
         * disagree about, which is exactly the defect this workstream exists to close.
         *
         * The map travels WHOLE, unfiltered. Today every [KeyframableProperty] is a transform
         * property, so there is nothing to filter; when effect parameters join the enum, whoever
         * teaches the graph about them splits the map then, with the layer type that owns them in
         * hand, rather than this line guessing at a boundary that does not exist yet.
         *
         * Read it through [transformAt], never field by field: that function is the one place the
         * static and the keyed halves are folded together.
         *
         * Defaulted empty, so a layer built by hand — and every fixture and test written before this
         * field existed — carries no keys and renders exactly as it did.
         */
        val keyframes: Map<KeyframableProperty, List<Keyframe>> = emptyMap(),
    ) : RenderLayer {
        init {
            require(speed > 0f) { "speed must be > 0, was $speed" }
        }
    }

    /** A text overlay (FR-4.3). */
    @Serializable
    data class Text(
        val content: TextSpec,
        val transform: TransformSpec = TransformSpec(),
        override val timeRange: TimeRange,
    ) : RenderLayer

    /** An image / sticker overlay (FR-4.4). [source] is resolved from the document. */
    @Serializable
    data class Image(
        val source: SourceRef,
        val transform: TransformSpec = TransformSpec(),
        /** 0f..1f. */
        val opacity: Float = 1f,
        override val timeRange: TimeRange,
    ) : RenderLayer {
        init {
            require(opacity in 0f..1f) { "opacity must be in 0f..1f, was $opacity" }
        }
    }
}

/**
 * One entry in a layer's effect chain, as data (spec §6.8 rule D2).
 *
 * Effects are declarative *parameters*, never constructed shader objects or
 * lambdas: the C++ `EffectChainCompiler` must see the whole chain at once in
 * order to fuse N effects into a single fragment pass, and a callback model
 * cannot be fused.
 *
 * [timeRange] is **layer-local** (0 = the layer's own start), not timeline time.
 * That keeps a layer self-contained: the mapper renders a layer without needing
 * to know where on the timeline it sits, and the native core receives
 * `(layer, localTime)` instead of having to reconstruct an absolute origin.
 */
@Serializable
sealed interface RenderEffect {
    /** Range within the owning layer. */
    val timeRange: TimeRange

    /** LUT grade. [strength] blends result against source: 1 = fully applied. */
    @Serializable
    data class Lut(
        val ref: LutRef,
        override val timeRange: TimeRange,
        val strength: Float = 1f,
    ) : RenderEffect {
        init {
            require(strength in 0f..1f) { "strength must be in 0f..1f, was $strength" }
        }
    }

    /**
     * Manual colour adjustment.
     *
     * A *total* adjustment ([ColorAdjustSpec.isIdentity]) is never placed in a
     * chain — the compiler drops it, because a no-op colour pass still costs a
     * full-frame shader run (see the note on [ColorAdjustSpec]).
     */
    @Serializable
    data class Adjust(
        val spec: ColorAdjustSpec,
        override val timeRange: TimeRange,
    ) : RenderEffect
}

/**
 * Fade in / out, in milliseconds.
 *
 * Both edges are held in one value because they are always clamped together: a
 * fade pair longer than its clip is scaled to exactly fill it (see
 * [TimelineCompiler]). Kept as `Long` milliseconds to match
 * [com.redcut.domain.document.Clip], which stores fades the same way.
 */
@Serializable
data class FadeSpec(
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
) {
    init {
        require(fadeInMs >= 0L) { "fadeInMs must be >= 0, was $fadeInMs" }
        require(fadeOutMs >= 0L) { "fadeOutMs must be >= 0, was $fadeOutMs" }
    }

    /** True when this fade pair would change nothing. */
    val isIdentity: Boolean get() = fadeInMs == 0L && fadeOutMs == 0L

    val totalMs: Long get() = fadeInMs + fadeOutMs
}

/** Per-layer audio: gain, mute, fades (FR-3.2 – FR-3.4). */
@Serializable
data class AudioSpec(
    /** 0f..2f (FR-3.2: 0–200 %). */
    val gain: Float = 1f,
    val muted: Boolean = false,
    val fades: FadeSpec = FadeSpec(),
) {
    init {
        require(gain in 0f..MAX_GAIN) { "gain must be in 0f..$MAX_GAIN, was $gain" }
    }

    /** True when this layer contributes no samples at all. */
    val isSilent: Boolean get() = muted || gain == 0f

    companion object {
        /** FR-3.2 ceiling. */
        const val MAX_GAIN: Float = 2f
    }
}

/**
 * A seam transition between two adjacent video layers.
 *
 * Indices are into [RenderGraph.videoLayers], not into `layers`, so inserting an
 * overlay can never invalidate a transition.
 *
 * ### Open item (Phase 3, FR-4.5 is priority *Should*)
 *
 * A true cross-dissolve plays the outgoing tail and the incoming head *at the
 * same time*, which needs media beyond the trim points ("handles") that the
 * document does not guarantee. The graph states the intent declaratively and the
 * mapper owns realisation (Media3: overlapping items + an alpha ramp); when
 * handles are unavailable it degrades to a plain fade pair.
 *
 * [durationUs] never changes the timeline's timing: the document is the only
 * source of truth for where clips sit, and a transition that re-timed the
 * timeline would make the ruler, the graph, and `EditDocument.durationUs`
 * disagree.
 */
@Serializable
sealed interface Transition {
    val fromIndex: Int
    val toIndex: Int

    /** Timeline time of the seam; always the incoming layer's start. */
    val startUs: Long
    val durationUs: Long

    @Serializable
    data class Dissolve(
        override val fromIndex: Int,
        override val toIndex: Int,
        override val startUs: Long,
        override val durationUs: Long,
    ) : Transition {
        init {
            require(durationUs > 0L) { "durationUs must be > 0, was $durationUs" }
        }
    }
}
