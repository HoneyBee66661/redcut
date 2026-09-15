package com.redcut.domain.render

import com.redcut.domain.document.AppliedEffect
import com.redcut.domain.document.Clip
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.EffectScope
import com.redcut.domain.document.SourceRef
import com.redcut.domain.document.TimeRange

/**
 * The one function that turns an [EditDocument] into a [RenderGraph] — spec §4.3,
 * §8.1.
 *
 * Both the preview and the export path start from the value this produces, so
 * every framing, timing and ordering rule in the product lives here rather than in
 * the two adapters that would otherwise each grow their own copy of it (§12.3:
 * preview/export parity is a test, not a hope).
 *
 * ### Purity and totality are contractual, not aspirational
 *
 * `compile` is a pure function: same document in, same graph out. No clock, no
 * randomness, no cache, no field on this object. §8.1 lets the caller recompile on
 * *every* revision change precisely because that is cheap and free of side effects;
 * a cache here would buy microseconds and cost a correctness liability.
 *
 * It is also **total**: it never throws, for any [EditDocument] that can be
 * constructed. That is a stronger promise than the rest of the domain makes — the
 * document model validates its own invariants in `init` blocks — and it is
 * deliberate. The document is assembled by commands, deserialized from disk, and
 * (in Phase 5) handed to a native core; a compiler that threw would turn one stale
 * id in a saved project into a crash on open rather than a clip that quietly does
 * not appear. So anything that cannot be represented in the graph is DROPPED:
 * disabled clips, dangling source references, effects addressed to a clip that no
 * longer exists, a dissolve too short to be worth the machinery. There is no
 * `require`, no `check`, no `error`, and no `!!` anywhere in this file.
 *
 * ### Compiled time vs. documented time
 *
 * Two timelines exist and they can disagree. [EditDocument.timeline] keeps every
 * clip, disabled ones included. This compiler keeps only *live* clips and ripples
 * the survivors closed (MVP is ripple-only, FR-2), so a graph position is the sum
 * of the live clips before it. Only the compiled timeline is used below; the
 * document's own field is never consulted for placement, because a disabled clip
 * would otherwise leave a hole that [RenderGraph] rightly refuses to represent.
 */
object TimelineCompiler {

    /**
     * Compiles [document] for [output], defaulting to a preview at the document's
     * own canvas geometry ([OutputSpec.preview]).
     *
     * The default is expressed in terms of `document` rather than written as a
     * constant so that callers who only want "show me this edit" cannot accidentally
     * preview at a geometry the document does not use; an export that genuinely
     * targets other geometry passes an explicit [OutputSpec] (FR-5.1).
     *
     * The result carries [EditDocument.revision] and [EditDocument.canvas] verbatim.
     * The revision is copied and never invented — a downstream cache that sees it
     * unchanged is entitled to skip the recompile (§8.1), and that entitlement is
     * only safe if the value is the document's own.
     */
    fun compile(
        document: EditDocument,
        output: OutputSpec = OutputSpec.preview(document.canvas),
    ): RenderGraph {
        val slots = compiledSlots(document)
        val graphDurationUs = slots.lastOrNull()?.timeRange?.endUs ?: 0L

        val videoLayers = slots.mapIndexed { index, slot ->
            val clip = slot.clip
            // One fade pair feeds both the video and the audio edge (FR-3.4 asks for
            // them to be independent; the document model carries only one pair, so
            // inventing a second here would be a lie the mapper then has to respect).
            val fades = fadesFor(clip)
            RenderLayer.Video(
                clipId = clip.id,
                source = slot.source,
                sourceRange = TimeRange(clip.sourceInUs, clip.sourceOutUs),
                timeRange = slot.timeRange,
                speed = clip.speed,
                reverse = clip.reverse,
                transform = clip.transform,
                // The clip's keys, carried onto the layer UNMOVED (WS G1). The clamp is the one thing
                // that could have shifted the clock between the two: an effect's Clip-scoped range is
                // clamped to the layer's duration, but the layer's duration IS the clip's timeline
                // duration, so a key at local time t names the same moment in the render as it does in
                // the editor. Nothing in this function reads the map — the render paths do, through
                // `RenderLayer.Video.transformAt`, which is what keeps the preview's answer and the
                // export's answer the same function.
                keyframes = clip.keyframes,
                effects = colorChain(document.effects, slots, index),
                fades = fades,
                audio = AudioSpec(
                    gain = safeGain(clip.volume),
                    muted = clip.muted,
                    fades = fades,
                ),
            )
        }

        return RenderGraph(
            revision = document.revision,
            canvas = document.canvas,
            layers = videoLayers + overlayLayers(document, slots, graphDurationUs),
            output = output,
            transitions = dissolveTransitions(document.effects, slots),
            // Rule D4 / FR-3.2: per-clip gain, mute and fades already live on the
            // layers above, so the only things left here are the master gain and the
            // music bed. `EditDocument` has no `audioBed` field yet even though
            // FR-1.6 names one, and the graph must not pretend otherwise — an
            // invented bed would be a music track the user never added. The field
            // exists on [AudioGraph] so that adding it later is an additive model
            // change rather than a graph migration.
            audio = AudioGraph(),
        )
    }

    /**
     * The compiled placement of one live clip.
     *
     * A private type exists so that every rule below can talk about "the clip, the
     * source it resolved to, and where it lands *after* ripple-closing" as one
     * value. Threading three parallel lists through the same six helpers is how a
     * clip and its source get out of step, and the resulting bug is a frame from the
     * wrong file rather than a compile error.
     */
    private data class CompiledSlot(
        val clip: Clip,
        val source: SourceRef,
        /** TIMELINE-absolute, on the compiled (rippled) timeline — not the document's. */
        val timeRange: TimeRange,
    ) {
        val clipId: String get() = clip.id
        val durationUs: Long get() = timeRange.durationUs
    }

    /**
     * The live clips, in document order, laid out contiguously from 0.
     *
     * A clip is live when it is enabled AND its `sourceId` resolves. A disabled clip
     * is not in the render at all, and a clip whose source is not in
     * `document.sources` cannot be decoded; both are DROPPED and the timeline ripples
     * closed rather than leaving a gap. MVP is ripple-only (FR-2), and [RenderGraph]
     * rejects a non-contiguous layer list, so holding a slot open for a dead clip
     * would turn a missing source into a build failure instead of a shorter video.
     *
     * The source is resolved *once*, here, and carried on the slot. That is what lets
     * the rest of the file use the non-null value the liveness check already
     * produced, instead of re-looking it up and reaching for `!!` to re-tell the
     * compiler something this function has already established.
     */
    private fun compiledSlots(document: EditDocument): List<CompiledSlot> {
        var cursor = 0L
        return buildList {
            document.clips.forEach { clip ->
                val source = document.sourceById(clip.sourceId)
                if (!clip.enabled || source == null) return@forEach
                val durationUs = clip.timelineDurationUs
                // Totality guard. `Clip` puts no ceiling on `sourceOutUs`, so a long
                // trim combined with a tiny speed saturates `timelineDurationUs` at
                // Long.MAX_VALUE. Once the cursor is that far out there is no room
                // for another layer, and adding anyway would wrap the end negative
                // and make TimeRange throw. Stopping keeps the emitted layers a
                // contiguous prefix — the only shape RenderGraph accepts — and every
                // later clip fails the same test, so nothing is skipped over.
                if (cursor > Long.MAX_VALUE - durationUs) return@forEach
                val end = cursor + durationUs
                add(CompiledSlot(clip, source, TimeRange(cursor, end)))
                cursor = end
            }
        }
    }

    /**
     * The fade pair for one clip, scaled to fit its layer (FR-3.4).
     *
     * Fades longer than the clip they belong to are not user error worth refusing —
     * they are what "fade in and out over this clip" degenerates to once the trim
     * gets short. Scaling BOTH proportionally, rather than clamping one and letting
     * the other stand, is what keeps the pair honest: clamping would silently turn a
     * 50/50 fade into a 100/0 one and the user would watch the fade-out they asked
     * for simply vanish. `scaledOut = layerMs - scaledIn` rather than its own
     * division guarantees the two sum to exactly `layerMs` even when the division
     * truncates, which is why the arithmetic is integer throughout.
     *
     * The coercions are the other half of the contract. `Clip.fadeInMs` and
     * `Clip.fadeOutMs` are the only Clip fields with no `require()` in the document
     * model — unlike `sourceInUs` or `speed` — so a negative or absurd pair can
     * exist. [FadeSpec] does validate, so copying them raw could throw. Coercing here
     * keeps the compiler total rather than teaching it to trust a model that does
     * not validate these three fields.
     */
    private fun fadesFor(clip: Clip): FadeSpec {
        val inMs = clip.fadeInMs.coerceAtLeast(0L)
        val outMs = clip.fadeOutMs.coerceAtLeast(0L)
        // The layer's length in whole milliseconds; FadeSpec counts milliseconds, so
        // this is the budget the pair has to fit inside. Integer division is intended.
        val layerMs = clip.timelineDurationUs / MILLIS_TO_MICROS

        // Does the pair already fit? Tested without ever forming a sum that could
        // overflow: `inMs + outMs` is evaluated only once both are known to be at
        // most layerMs, so the sum stays below 2 * layerMs.
        if (inMs <= layerMs && outMs <= layerMs && inMs + outMs <= layerMs) {
            return FadeSpec(inMs, outMs)
        }

        val scaledIn = scaleProduct(inMs, layerMs, saturatingAdd(inMs, outMs))
        return FadeSpec(scaledIn, layerMs - scaledIn)
    }

    /**
     * `Clip.volume` coerced into what [AudioSpec] accepts (FR-3.2: 0–200 %).
     *
     * `Clip.volume` is one of the three Clip fields the model does not validate, so
     * it can hold anything a Float can — NaN included. NaN matters: Kotlin's
     * `coerceIn` answers NaN with NaN, because every comparison against NaN is false,
     * and `AudioSpec`'s `require(gain in 0f..2f)` would then throw. Guarding it is
     * the difference between a compiler that is total and one that is total except
     * for a value nobody thought to construct. Unity is the fallback because it is
     * also `Clip.volume`'s default: an unreadable gain should render as "unchanged",
     * not as silence the user never asked for.
     */
    private fun safeGain(volume: Float): Float =
        if (volume.isNaN()) 1f else volume.coerceIn(0f, AudioSpec.MAX_GAIN)

    /**
     * The index of the emitted layer a clip id refers to, or -1.
     *
     * First match wins. `EditDocument.clips` is the flattened list of every track's
     * clips and does not forbid two sharing an id, but an effect names a single clip, so it must resolve to a
     * single layer — applying a Clip-scoped effect to every layer that happens to
     * share an id would turn one user action into N visible ones.
     */
    private fun clipSlotIndex(slots: List<CompiledSlot>, clipId: String): Int =
        slots.indexOfFirst { it.clipId == clipId }

    /**
     * The colour chain for the video layer at [index] (spec §6.8 rules D2/D3, FR-4.7).
     *
     * Built by walking `document.effects` in order and keeping whatever applies, which
     * is the whole of "render order is stack order": the graph's list order *is* the
     * document's list order, with no sort step in between that could drift from it.
     *
     * No-ops are dropped rather than forwarded. An identity [ColorAdjustSpec] or a
     * zero-strength LUT changes nothing on screen but costs a full-frame shader run
     * every frame, and an entry the renderer has to execute in order to discover that
     * it does nothing is worse than no entry at all.
     */
    private fun colorChain(
        effects: List<AppliedEffect>,
        slots: List<CompiledSlot>,
        index: Int,
    ): List<RenderEffect> = effects.mapNotNull { effect ->
        if (!effect.enabled) return@mapNotNull null
        val range = localRange(effect, slots, index) ?: return@mapNotNull null
        when (effect) {
            is AppliedEffect.Lut ->
                if (effect.strength == 0f) {
                    null
                } else {
                    RenderEffect.Lut(effect.ref, range, effect.strength)
                }
            is AppliedEffect.Adjust ->
                if (effect.spec.isIdentity) null else RenderEffect.Adjust(effect.spec, range)
            // Text, Image and Dissolve are not chain entries: they become overlay
            // layers and transitions instead, and treating them as chain entries would
            // ask the colour pipeline to draw something it has no shader for.
            else -> null
        }
    }

    /**
     * Where [effect] lands inside the layer at [index], in LAYER-LOCAL time, or null
     * when it does not touch that layer at all (spec §4.3, rule D2).
     *
     * The two scopes mean two different clocks, and conflating them is the bug this
     * function exists to prevent. A Clip-scoped `timeRange` is already relative to
     * its clip, so it is only clamped to the layer. A Document-scoped one is absolute
     * timeline time, so it is intersected with the layer and then rebased. A
     * [RenderEffect.timeRange] is layer-local by definition, and both the mapper and
     * the native core depend on that to render a layer without knowing where on the
     * timeline it sits.
     *
     * An empty result is a DROP and never a zero-length range: [TimeRange] cannot
     * represent one, and "the effect applies for no time at all" is precisely the
     * case where skipping it is indistinguishable from running it.
     */
    private fun localRange(
        effect: AppliedEffect,
        slots: List<CompiledSlot>,
        index: Int,
    ): TimeRange? = when (val scope = effect.scope) {
        is EffectScope.Clip ->
            if (index != clipSlotIndex(slots, scope.clipId)) {
                null
            } else {
                clampToLayer(effect.timeRange, slots[index].durationUs)
            }

        EffectScope.Document -> {
            val layer = slots[index].timeRange
            intersect(effect.timeRange.startUs, effect.timeRange.endUs, layer.startUs, layer.endUs)
                ?.let { TimeRange(it.startUs - layer.startUs, it.endUs - layer.startUs) }
        }
    }

    /**
     * [range] clamped to `[0, layerDurationUs)`.
     *
     * A Clip-scoped range is authored against the clip's duration in the *document's*
     * terms, which can exceed the compiled layer's when speed or the 100 ms floor
     * moved the edges. The layer is what will actually be rendered, so the effect is
     * clamped to it rather than left naming time that does not exist. The lower bound
     * needs no clamp: [TimeRange] already refuses a negative start.
     */
    private fun clampToLayer(range: TimeRange, layerDurationUs: Long): TimeRange? {
        val start = range.startUs.coerceAtMost(layerDurationUs)
        val end = range.endUs.coerceAtMost(layerDurationUs)
        return if (end > start) TimeRange(start, end) else null
    }

    /**
     * Text and image overlays, in effect-stack order (FR-4.3, FR-4.4, FR-4.7).
     *
     * A second pass over the same list, concatenated *after* the video layers,
     * because an overlay is by definition drawn over the composited clip. Emitting
     * them in document order preserves stack order among themselves, so "bring this
     * caption forward" stays a list reorder and nothing here has to model depth.
     */
    private fun overlayLayers(
        document: EditDocument,
        slots: List<CompiledSlot>,
        graphDurationUs: Long,
    ): List<RenderLayer> = document.effects.mapNotNull { effect ->
        if (!effect.enabled) return@mapNotNull null
        val range = overlayRange(effect, slots, graphDurationUs) ?: return@mapNotNull null
        when (effect) {
            is AppliedEffect.Text ->
                RenderLayer.Text(effect.spec, effect.transform, range)

            is AppliedEffect.Image -> {
                // A sticker whose source was deleted has nothing to draw. Resolving it
                // here, rather than handing the mapper an unresolvable SourceRef, is
                // what keeps a dangling id a missing sticker instead of a failed
                // export — the same policy every other dangling reference gets.
                val source = document.sourceById(effect.sourceId) ?: return@mapNotNull null
                RenderLayer.Image(source, effect.transform, effect.opacity, range)
            }

            else -> null
        }
    }

    /**
     * Where an overlay sits on the TIMELINE, or null when it is not visible at all.
     *
     * A Clip-scoped overlay is authored relative to its clip, so it is rebased onto
     * that clip's *compiled* start — the rippled position — and then clamped to the
     * clip's compiled layer. Rebasing onto the document's own position would drift
     * the overlay by the length of every disabled clip before it, which is exactly
     * the class of bug the compiled timeline exists to prevent.
     *
     * A Document-scoped overlay is already absolute and is intersected with the
     * finished video, so an overlay running past the last clip becomes a layer that
     * is clamped to the video rather than one the graph claims exists after it ended.
     */
    private fun overlayRange(
        effect: AppliedEffect,
        slots: List<CompiledSlot>,
        graphDurationUs: Long,
    ): TimeRange? = when (val scope = effect.scope) {
        is EffectScope.Clip -> {
            val index = clipSlotIndex(slots, scope.clipId)
            if (index < 0) {
                null
            } else {
                val layer = slots[index].timeRange
                intersect(
                    saturatingAdd(layer.startUs, effect.timeRange.startUs),
                    saturatingAdd(layer.startUs, effect.timeRange.endUs),
                    layer.startUs,
                    layer.endUs,
                )
            }
        }

        EffectScope.Document ->
            intersect(effect.timeRange.startUs, effect.timeRange.endUs, 0L, graphDurationUs)
    }

    /**
     * The dissolves, at most one per seam (FR-4.5, spec §4.3).
     *
     * A dissolve describes a *seam*, so each effect must be resolved into a pair of
     * adjacent layer indices — and there are two ways to name that seam. A
     * Document-scoped effect names it by absolute time, and the only absolute times
     * that are seams are the starts of layers other than the first: layer 0's start is
     * the head of the video, where there is nothing to dissolve from, so it is
     * excluded. A Clip-scoped effect names it by clip, and the seam is that clip's
     * *end*, i.e. the start of whatever follows. A clip with nothing after it has no
     * seam, so a dissolve addressed to the last clip is DROPPED rather than folded
     * backwards into the one before it, which would move the effect to a place the
     * user did not point at.
     *
     * The requested duration is capped by both neighbouring layers, because the
     * dissolve runs across the seam and can only be as long as the shorter of the two
     * pieces of footage it joins. Below [RenderGraph.MIN_LAYER_US] it is dropped
     * rather than shortened: a 30 ms cross-fade reads as a glitch, and it costs the
     * same machinery as a real one, so the floor is a floor and not a clamp.
     *
     * Only the first effect to resolve to a seam is emitted. Two transitions on one
     * seam is not a richer effect but an ambiguous one, and [RenderGraph] rejects it
     * outright — so the ambiguity is settled here, in document order.
     */
    private fun dissolveTransitions(
        effects: List<AppliedEffect>,
        slots: List<CompiledSlot>,
    ): List<Transition> {
        val claimedSeams = mutableSetOf<Int>()
        return effects.mapNotNull { effect ->
            if (!effect.enabled || effect !is AppliedEffect.Dissolve) return@mapNotNull null

            val fromIndex = when (val scope = effect.scope) {
                is EffectScope.Clip ->
                    clipSlotIndex(slots, scope.clipId).takeIf { it >= 0 && it + 1 < slots.size }

                EffectScope.Document ->
                    // The effect names the seam by absolute time, and the only absolute
                    // times that are seams are the starts of layers other than the
                    // first — `it >= 1` excludes the head of the video, where there is
                    // nothing to dissolve from. That start belongs to the INCOMING
                    // layer, so the outgoing one is `it - 1`; using `it` would place the
                    // transition one seam later than the user pointed at, which is a
                    // dissolve that renders at the wrong cut and reports the wrong
                    // start time back to the timeline UI.
                    slots.indices.firstOrNull {
                        it >= 1 && slots[it].timeRange.startUs == effect.timeRange.startUs
                    }?.minus(1)
            } ?: return@mapNotNull null

            val outgoing = slots[fromIndex]
            val incoming = slots[fromIndex + 1]
            val effectiveUs = minOf(
                saturatingMultiply(effect.durationMs, MILLIS_TO_MICROS),
                outgoing.durationUs,
                incoming.durationUs,
            )
            if (effectiveUs < RenderGraph.MIN_LAYER_US) return@mapNotNull null
            // Claimed only once a transition is actually emitted. A dissolve dropped
            // for being too short never became one, so it does not get to veto a later
            // effect at the same seam that would have been long enough.
            if (!claimedSeams.add(fromIndex)) return@mapNotNull null

            Transition.Dissolve(
                fromIndex = fromIndex,
                toIndex = fromIndex + 1,
                startUs = incoming.timeRange.startUs,
                durationUs = effectiveUs,
            )
        }
    }

    /**
     * The overlap of two half-open ranges, or null when they do not overlap.
     *
     * Returns null rather than a zero-length [TimeRange] because the type cannot hold
     * one (`endUs > startUs`), and because "they do not overlap" is the answer every
     * caller actually wants: it is the DROP condition for a scoped effect or overlay.
     */
    private fun intersect(startA: Long, endA: Long, startB: Long, endB: Long): TimeRange? {
        val start = maxOf(startA, startB)
        val end = minOf(endA, endB)
        return if (end > start) TimeRange(start, end) else null
    }

    /**
     * `a + b`, saturating at [Long.MAX_VALUE] instead of wrapping. Both arguments are
     * non-negative at every call site (they come from [TimeRange], which refuses a
     * negative start).
     *
     * Only reachable with a document nobody meant to build, but wrapping there would
     * turn a huge offset into a negative one, and a negative time is the one value
     * that would make the ordinary `end > start` checks elsewhere in this file read
     * as *valid*. Saturating keeps a degenerate case degenerate, so it is dropped
     * like any other range the graph cannot represent.
     */
    private fun saturatingAdd(a: Long, b: Long): Long =
        if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    /**
     * `a * b`, saturating at [Long.MAX_VALUE]. Same reasoning as [saturatingAdd]:
     * `AppliedEffect.Dissolve.durationMs` is only required to be positive, so
     * `durationMs * 1000` can overflow, and a wrapped negative duration would be
     * dropped by the length floor only *after* it had a chance to look like a real
     * value on the way there.
     */
    private fun saturatingMultiply(a: Long, b: Long): Long {
        if (a <= 0L || b <= 0L) return 0L
        return if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    }

    /**
     * `a * b / c` for non-negative longs: exact, and it cannot overflow.
     *
     * The plain expression is preferred — it is exact, and every realistic fade lands
     * in it — but `Clip` puts no ceiling on its fade fields, so `a * b` can overflow
     * into a negative product and yield a nonsense fade. The fallback goes through
     * Double, which cannot overflow; it loses low-order precision, which is
     * immaterial at the magnitudes that reach it (a clip measured in geological time)
     * and is still perfectly deterministic, which is what purity actually requires.
     *
     * The result is clamped to `0..b`: `a <= c` holds for every caller, so the true
     * quotient is at most `b`, and the clamp only ever absorbs the fallback's
     * rounding.
     */
    private fun scaleProduct(a: Long, b: Long, c: Long): Long {
        if (a <= 0L || b <= 0L || c <= 0L) return 0L
        val product = if (a <= Long.MAX_VALUE / b) {
            a * b / c
        } else {
            (a.toDouble() * b.toDouble() / c.toDouble()).toLong()
        }
        return product.coerceIn(0L, b)
    }

    /** Milliseconds to microseconds, for [AppliedEffect.Dissolve.durationMs]. */
    private const val MILLIS_TO_MICROS: Long = 1000L
}
