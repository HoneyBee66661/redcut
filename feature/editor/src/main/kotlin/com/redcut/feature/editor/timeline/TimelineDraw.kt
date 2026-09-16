package com.redcut.feature.editor.timeline

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redcut.core.common.timeline.ClipRect
import com.redcut.core.common.timeline.ClipSpan
import com.redcut.core.common.timeline.LaneClips
import com.redcut.core.common.timeline.LaneRect
import com.redcut.core.common.timeline.TimelineGeometry
import com.redcut.core.media.ThumbnailKey
import com.redcut.domain.document.Clip
import com.redcut.domain.document.ClipEdge
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The drawing primitives of the timeline.
 *
 * Split from [TimelineCanvas] by responsibility, and the split is worth explaining because it is
 * not cosmetic: the composable decides *what* to draw (state in, draw calls out) while these
 * functions know *how* a rectangle, a filmstrip slice and a playhead are painted. A file that did
 * both grew past the point where either half could be read in one go — and `detekt`'s function
 * count, which is what forced the split, is a proxy for that rather than the reason.
 *
 * Every function here takes the geometry rather than computing a position itself: the arithmetic is
 * tested in `:core:common`'s fast tier, and a draw function that did its own would put it beyond the
 * reach of any test.
 */

/** The colours the canvas draws with. Captured outside `DrawScope`, where `MaterialTheme` is legal. */
internal data class TimelinePaint(
    val clip: Color,
    val selectedClip: Color,
    val selectionBorder: Color,
    /**
     * The timeline's own grey: the ruler's ticks, a clip's top ticks, and the empty lane.
     *
     * One value for the furniture the DOCUMENT does not contain — the marks that measure time and the band
     * that says a track holds nothing here. They are the same fact at two sizes, and separate near-identical
     * theme roles would be three greys nobody can tell apart in a review or on a screen.
     */
    val ruler: Color,
    val playhead: Color,
    /** The edge a trim gesture is dragging (FR-2.1): brighter than the selection, because it is moving. */
    val trimEdge: Color,
    /** The slot a reorder drag would drop into (FR-2.7). */
    val reorderMarker: Color,
    /**
     * The audio body's waveform and label colour (D3).
     *
     * The container/content pairing a Material chip uses: the clip's fill is `surfaceVariant`, and
     * what an audio clip draws ON itself is `onSurfaceVariant`, so the motif reads against both the
     * resting and the selected fill. Deliberately NOT a gesture colour — the trim edge, the reorder
     * slot and the selection each own theirs, and a body that borrowed one would blur the signal
     * the gesture drew.
     */
    val audioWaveform: Color,
    /**
     * The text lane's body and its selected state (FR-4.3's card 4).
     *
     * A container/content pairing like the audio body's, in a hue of its own: a caption body that
     * borrowed the clip colour would read as footage, and one that borrowed the audio colour would read
     * as sound — the lane's whole point is that captions are NEITHER, so it carries its own fill the way
     * every other kind of lane does.
     */
    val textItem: Color,
    val textItemSelected: Color,
    /** The lane label's and item label's colour, over both the band and the bodies. */
    val onRuler: Color,
)

/**
 * The insertion marker for a reorder drag (FR-2.7): a bar where the clip would start.
 *
 * A bar rather than a highlight on the neighbouring clip, because the drop lands in the GAP that opens
 * up when the clip moves — and showing the gap is what makes "it will go here" unambiguous when the
 * neighbouring slots look alike.
 */
internal fun DrawScope.drawReorderMarker(screenX: Float, track: Track, color: Color) {
    drawLine(
        color = color,
        start = Offset(screenX, track.top),
        end = Offset(screenX, track.top + track.height),
        strokeWidth = REORDER_MARKER_PX,
    )
}

/** The clip track: everything below the ruler. */
internal data class Track(val top: Float, val height: Float)

/**
 * Everything the timeline derives from the document and the viewport.
 *
 * A value rather than free-floating locals, so the composable can read as "derive this, then draw it"
 * and the derivation lives in one readable pipeline: clips → lanes → geometry → lane bands with their
 * clips → slices → images. It is also what keeps the draw call's parameter list short enough to read.
 */
internal data class TimelineLayer(
    val geometry: TimelineGeometry,
    /**
     * One entry per lane, in the document's order: the band, and the clips to draw inside it.
     *
     * Bands and clips TOGETHER, because a rect says nothing about where it is drawn
     * vertically — a pass holding only a flat rect list would have to ask a second time
     * which lane it was iterating, and could answer that differently from the pass that
     * built them. That is the bug that appears only on the first project with two tracks,
     * which is the project this reading is for.
     */
    val lanes: List<LaneClips>,
    val slices: List<SliceRequest>,
    val images: Map<ThumbnailKey, ImageBitmap>,
    /**
     * The clips and their spans, carried along because the gesture layer needs the same maps the
     * filmstrip does: turning a screen position into a source time means asking the clip under the
     * finger ([Clip.sourceTimeFor]) and subtracting where its span starts.
     */
    val clipsById: Map<String, Clip>,
    val spansByClip: Map<String, ClipSpan>,
    /**
     * The ids of the document's AUDIO lanes (D3).
     *
     * Audio-ness is a fact about the TRACK a clip sits on — the kind lives on the domain's track,
     * and a clip on its own says nothing about whether it holds picture or sound — while the lane
     * band the draw pass iterates carries only the track's id. The kinds therefore ride along
     * here, resolved to ids once per document, and the lane loop asks its band's id this one
     * question instead of reaching for the document.
     *
     * Defaulted EMPTY, which means "no audio lanes": the flat reading a caller without a document
     * draws, and every document that predates audio, answer false per lane and render exactly as
     * they always did.
     */
    val audioTrackIds: Set<String> = emptySet(),
)

/**
 * What the current gesture is showing: the playhead, the edge being trimmed, the clip being dragged
 * and the slot it would land in.
 *
 * Every field but the playhead is null when its gesture is not running, which is why they are grouped
 * — five unrelated parameters trailing a draw call is how a signature stops being read.
 */
internal data class TimelineMarks(
    val playheadUs: Long,
    val selectedClipId: String? = null,
    val trimmedClipId: String? = null,
    val draggedEdge: ClipEdge? = null,
    val draggedClipId: String? = null,
    val markerUs: Long? = null,
    /**
     * The text lane's marks (FR-4.3's card 4): the selected caption, the caption whose edge is being
     * dragged, and which edge. Defaulted null, which is "no text marks" — the flat reading a caller
     * without a text lane draws, and every document without captions answers per lane with no change.
     */
    val selectedTextId: String? = null,
    val textTrimmedId: String? = null,
    val draggedTextEdge: ClipEdge? = null,
)

/** The whole timeline: one pass per lane, then the ruler, the marker and the playhead. */
internal fun DrawScope.drawTimeline(
    layer: TimelineLayer,
    paint: TimelinePaint,
    marks: TimelineMarks,
) {
    val rulerHeight = RULER_HEIGHT_DP.dp.toPx()
    // FIXED height, not `size.height - rulerHeight` (UI revision 2: "tinggi track body timeline fixed, tidak
    // fitting container"). The canvas may be taller than the track; the leftover is empty lane, drawn as
    // lane — a taller screen should show more track, not a taller clip. It also stops the playhead's 3×
    // height from growing with the window until it crosses the whole screen.
    val track = Track(top = rulerHeight, height = layer.geometry.trackHeightPx)
    // ONE tick selection, drawn twice (UI revision 2, task A3): along the ruler strip, and along each clip's
    // top edge. Asked for once here rather than by each caller, because "the same ticks" is the requirement —
    // a clip's marks that came from a second call could only ever be accidentally identical.
    val ticks = layer.geometry.rulerTicks()

    // ONE iteration per LANE, and BOTH halves come from the same entry: the band from the
    // lane's own `topPx`/`bottomPx`, the clips from that lane's own culled rects. So lane
    // 2's clips are drawn inside lane 2 and cannot land in lane 1, and where a band sits is
    // the geometry's `laneTopPx` rather than a counter kept here. `topPx` is 0 on the first
    // lane, which is why a one-track document still draws the single band it always drew.
    //
    // The ruler is drawn AFTER the bands, where it used to be drawn between them: the two
    // occupy disjoint y ranges — the strip ends at `rulerHeight`, every band starts there —
    // so the order cannot change a pixel, and drawing it once outside the loop keeps it one
    // tick selection rather than one per lane.
    layer.lanes.forEach { lane ->
        val band = Track(
            top = rulerHeight + lane.lane.topPx,
            height = lane.lane.bottomPx - lane.lane.topPx,
        )
        // Audio-ness is the LANE's fact (D3), read off the id the band carries: a track the
        // document marked AUDIO draws its clips as sound. The flat reading has no track id at
        // all, and a null id is the video rendering every document drew before audio existed.
        val trackId = lane.lane.trackId
        val isAudio = trackId != null && trackId in layer.audioTrackIds
        // The lane first, so the clips land ON a track instead of floating on the window's
        // background, and so the culling is invisible: what is not drawn as a clip is still
        // drawn as lane (task A5).
        drawLane(lane.lane, layer.geometry, band, paint.ruler)
        lane.rects.forEach { rect ->
            // Two ids, because the outline means "the clip the user is working with": selected, or under
            // the finger during a drag, which has not selected it yet.
            val picked = marks.selectedClipId == rect.clipId ||
                marks.draggedClipId == rect.clipId
            drawClip(
                rect = rect,
                slices = layer.slices.filter { it.clipId == rect.clipId },
                images = layer.images,
                selected = picked,
                draggedEdge = marks.draggedEdge?.takeIf { marks.trimmedClipId == rect.clipId },
                topTicks = layer.geometry.ticksForClipTop(ticks, rect),
                geometry = layer.geometry,
                track = band,
                paint = paint,
                isAudio = isAudio,
            )
        }
    }
    drawRuler(ticks, layer.geometry, rulerHeight, paint.ruler)
    // The marker and the playhead are anchored to the FIRST lane, which is the whole track
    // area a one-track document has. Which lane a reorder drag's clip belongs to is not in
    // the marks yet (see TimelineMarks); the marker previews a slot, and this is where it
    // is drawn until the pair travels with the drag.
    marks.markerUs?.let { us ->
        val screenX = layer.geometry.pxFor(us) - layer.geometry.visibleStartPx
        drawReorderMarker(screenX, track, paint.reorderMarker)
    }
    drawPlayhead(layer.geometry, track, marks.playheadUs, paint.playhead)
}

/**
 * Ruler ticks.
 *
 * [ticks] comes in rather than being asked for here, because the selection is shared with the marks drawn on
 * each clip's top edge ([drawClipTopTicks]): one selection, two drawings.
 */
internal fun DrawScope.drawRuler(
    ticks: List<Long>,
    geometry: TimelineGeometry,
    rulerHeight: Float,
    color: Color,
) {
    ticks.forEach { tickUs ->
        val x = geometry.pxFor(tickUs) - geometry.visibleStartPx
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, rulerHeight),
            strokeWidth = RULER_TICK_WIDTH_PX,
        )
    }
}

/**
 * One clip: its background, its filmstrip, and its selection outline.
 *
 * The filmstrip is clipped to the clip's rectangle, so a slice cannot bleed over a neighbour however
 * the clip has been zoomed or scrolled.
 *
 * ### The two bodies (D3)
 *
 * [isAudio] picks the body: an AUDIO lane's clip has no frames to show — an audio clip holds sound,
 * not pictures, so its thumbnails are never even requested — and draws as sound instead, in
 * [drawAudioBody]. Every other clip draws the filmstrip it always drew, which is also what the
 * default is: a caller that does not know a lane's kind renders video, the reading the timeline had
 * before audio existed.
 */
internal fun DrawScope.drawClip(
    rect: ClipRect,
    slices: List<SliceRequest>,
    images: Map<ThumbnailKey, ImageBitmap>,
    selected: Boolean,
    draggedEdge: ClipEdge?,
    topTicks: List<Long>,
    geometry: TimelineGeometry,
    track: Track,
    paint: TimelinePaint,
    isAudio: Boolean = false,
) {
    val left = rect.startPx - geometry.visibleStartPx
    clipRect(
        left = left,
        top = track.top,
        right = left + rect.widthPx,
        bottom = track.top + track.height,
    ) {
        drawRect(
            color = if (selected) paint.selectedClip else paint.clip,
            topLeft = Offset(left, track.top),
            size = Size(rect.widthPx, track.height),
        )
        if (isAudio) {
            drawAudioBody(
                left = left,
                widthPx = rect.widthPx,
                track = track,
                seed = rect.clipId.hashCode(),
                paint = paint,
            )
        } else {
            slices.forEach { slice -> drawSlice(slice, images, geometry, track) }
        }
        if (selected) {
            drawRect(
                color = paint.selectionBorder,
                topLeft = Offset(left, track.top),
                size = Size(rect.widthPx, track.height),
                style = Stroke(width = SELECTION_BORDER_PX),
            )
            // A stripe along the top as well as the outline. The device pass asked for a clearer
            // indicator, and the reason the outline was not enough is worth writing down: a clip is
            // mostly a thumbnail, so a 3 px border on a bright frame reads as part of the picture, and
            // "which clip am I editing?" is the one question this has to answer at a glance.
            drawRect(
                color = paint.selectionBorder,
                topLeft = Offset(left, track.top),
                size = Size(rect.widthPx, SELECTION_STRIPE_PX),
            )
        }
        // After the selection stripe rather than under it: the clip being cut is usually the selected one, and
        // a time reference that disappears exactly then is the one case the user asked for it in.
        drawClipTopTicks(topTicks, geometry, track, paint.ruler)
        // The edge being dragged, drawn INSIDE the clip's own clipping: the moment a trim shortens a
        // clip to nothing, its edge line would otherwise scribble over the neighbour.
        draggedEdge?.let { edge -> drawTrimEdge(edge, left, rect.widthPx, track, paint) }
    }
}

/**
 * An AUDIO clip's body (D3): the flat fill [drawClip] already laid down, the waveform motif that
 * says SOUND, and the label that names it.
 *
 * ### The waveform is synthesized, never decoded
 *
 * There is no sample access in the draw path, and the body must not gain one: a body that read the
 * mixer would redraw on every audio change and put playback behind drawing. The bars are
 * pseudo-levels from a [Random] seeded by the clip's own id, so the same clip draws the same shape
 * at every zoom and in every session, and the bar COUNT comes from the clip's drawn width — which
 * is its duration at the current zoom. A wider clip is more sound, not a different sound: zooming
 * re-samples the motif more finely without re-rolling it, and a trim changes only the density.
 *
 * ### The label
 *
 * The plan asked for "a labelled lane — the label is what proves the track is there": the band is
 * drawn whether or not a clip sits in it, and the label is what tells the user what the band is
 * FOR. DrawScope has no text primitive, so the label goes through the native canvas with a paint
 * of its own — one per clip per pass, a rounding error next to the decodes the video pass asks for,
 * and the reason no signature grows to carry a TextMeasurer here.
 */
private fun DrawScope.drawAudioBody(
    left: Float,
    widthPx: Float,
    track: Track,
    seed: Int,
    paint: TimelinePaint,
) {
    // The wave's band sits below the label's line and clears the body's bottom edge, so the motif
    // and the label do not fight for the same pixels; the clip's own clipping bounds everything.
    val bandTop = track.top + WAVE_BAND_TOP_DP.dp.toPx()
    val bandBottom = track.top + track.height - WAVE_BAND_BOTTOM_DP.dp.toPx()
    val middle = (bandTop + bandBottom) / 2f
    val amplitude = (bandBottom - bandTop) / 2f
    drawLine(
        color = paint.audioWaveform,
        start = Offset(left, middle),
        end = Offset(left + widthPx, middle),
        strokeWidth = WAVE_CENTERLINE_WIDTH_PX,
    )
    // One bar per WAVE_BAR_PITCH_PX, capped so a long clip at a high zoom cannot turn one body
    // into hundreds of draw calls in one pass (NFR-8). Past the cap the bars SPREAD OUT rather
    // than stop, so the body reads as sound across its whole width whatever the zoom.
    val barCount = (widthPx / WAVE_BAR_PITCH_PX).toInt().coerceIn(1, WAVE_MAX_BARS)
    val step = widthPx / barCount
    val levels = Random(seed)
    repeat(barCount) { bar ->
        val level = WAVE_MIN_LEVEL + levels.nextFloat() * (WAVE_MAX_LEVEL - WAVE_MIN_LEVEL)
        val halfHeight = level * amplitude
        val x = left + step * bar + step / 2f
        drawLine(
            color = paint.audioWaveform,
            start = Offset(x, middle - halfHeight),
            end = Offset(x, middle + halfHeight),
            strokeWidth = WAVE_BAR_WIDTH_PX,
        )
    }
    val label = Paint().apply {
        isAntiAlias = true
        textSize = AUDIO_LABEL_TEXT_SIZE_SP.sp.toPx()
        color = paint.audioWaveform.toArgb()
    }
    drawContext.canvas.nativeCanvas.drawText(
        AUDIO_CLIP_LABEL,
        left + AUDIO_LABEL_INSET_DP.dp.toPx(),
        track.top + AUDIO_LABEL_BASELINE_DP.dp.toPx(),
        label,
    )
}

/**
 * An empty track lane, drawn as a band (UI revision 2, task A5).
 *
 * This and [TimelineGeometry.visibleRects]'s culling are a pair, and the pair is the point: the clips that
 * are on screen are drawn, everything else is drawn as LANE, and the body therefore never has a region that
 * is neither. The user's words for the second half were *"clip body off screen not rendered for
 * optimization"*; without this half, culling would leave a hole where a clip is merely off screen.
 *
 * Painted in the timeline's structural grey rather than in a colour of its own, and deliberately NOT in the
 * clip colour: a clip's fill is the same theme role as `TimelinePaint.clip`, so a lane in it would make a
 * clip's rectangle invisible until its thumbnails arrived. The lane is the timeline's furniture, like the
 * ticks that share the colour, and not content.
 */
private fun DrawScope.drawLane(
    lane: LaneRect,
    geometry: TimelineGeometry,
    track: Track,
    color: Color,
) {
    drawRect(
        color = color,
        topLeft = Offset(lane.startPx - geometry.visibleStartPx, track.top),
        size = Size(lane.widthPx, track.height),
    )
}

/**
 * The ruler's ticks, repeated along a clip's TOP EDGE (UI revision 2, task A3).
 *
 * The user: *"beri ruler indikator waktu juga di top clip untuk memudahkan cut ops dan keyframing"* — a cut
 * point has to be readable against a time where the cut is made, not only in the strip above the body. The
 * marks ARE the ruler's ([TimelineGeometry.ticksForClipTop]), so the two readings are the same time at the
 * same pixel, and zooming in tightens both together.
 *
 * Short marks rather than full-height lines: what is under them is the clip the user is looking at, and a
 * line across it would compete with the two edges a trim drags.
 */
private fun DrawScope.drawClipTopTicks(
    ticks: List<Long>,
    geometry: TimelineGeometry,
    track: Track,
    color: Color,
) {
    ticks.forEach { tickUs ->
        val x = geometry.pxFor(tickUs) - geometry.visibleStartPx
        drawLine(
            color = color,
            start = Offset(x, track.top),
            end = Offset(x, track.top + CLIP_TOP_TICK_PX),
            strokeWidth = RULER_TICK_WIDTH_PX,
        )
    }
}

/**
 * The line under the finger during a trim.
 *
 * Drawn rather than annotated: the clip's rectangle has already changed size (the document is being
 * previewed live), so the line is what says WHICH edge is moving — at a glance, without reading a
 * number.
 */
private fun DrawScope.drawTrimEdge(
    edge: ClipEdge,
    left: Float,
    widthPx: Float,
    track: Track,
    paint: TimelinePaint,
) {
    val x = if (edge == ClipEdge.IN) left else left + widthPx
    drawLine(
        color = paint.trimEdge,
        start = Offset(x, track.top),
        end = Offset(x, track.top + track.height),
        strokeWidth = TRIM_EDGE_PX,
    )
}

/**
 * One thumbnail, aspect-preserving and centred vertically.
 *
 * Preserving the aspect ratio matters more than filling the track: a 16:9 frame stretched into a
 * 96 dp track would show every clip squeezed, which reads as a problem with the media rather than
 * as a layout choice.
 */
internal fun DrawScope.drawSlice(
    slice: SliceRequest,
    images: Map<ThumbnailKey, ImageBitmap>,
    geometry: TimelineGeometry,
    track: Track,
) {
    val image = images[slice.key] ?: return
    val aspectHeight = slice.widthPx * image.height / image.width
    val top = track.top + (track.height - aspectHeight) / 2f
    drawImage(
        image = image,
        dstOffset = IntOffset(
            x = (slice.leftPx - geometry.visibleStartPx).roundToInt(),
            y = top.roundToInt(),
        ),
        dstSize = IntSize(
            width = slice.widthPx.roundToInt(),
            height = aspectHeight.roundToInt(),
        ),
    )
}

/**
 * The playhead: the fixed reference line of UI revision 1.
 *
 * ### Three times the track, top-aligned, and red
 *
 * The user's spec, and each part earns its place. **Three times the track's height** because a line the
 * height of the track disappears into the filmstrip it crosses — this one starts at the track's top and
 * reaches well past the clips, so it is findable at a glance. **Top-aligned** to the track rather than to
 * the canvas, so it is the same line whatever is above it.
 *
 * **Red**, asked for by name, and deliberately NOT a theme colour: a playhead that changed with the theme
 * (or with the dimmed state of a disabled control near it) stops being the fixed reference the revision
 * makes it. It is also the one element on screen that is not derived from the document — the tracks move
 * under it — and its colour says so.
 *
 * The line is drawn where the geometry maps the playhead, which under the revision is the viewport's centre
 * whenever the timeline is long enough to scroll that far (`centredPlayheadPx`), and drifts towards an edge
 * only at the ends.
 */
internal fun DrawScope.drawPlayhead(
    geometry: TimelineGeometry,
    track: Track,
    playheadUs: Long,
    color: Color,
) {
    geometry.playheadPx(playheadUs)?.let { x ->
        drawLine(
            color = color,
            start = Offset(x, track.top),
            end = Offset(x, track.top + track.height * PLAYHEAD_TRACK_MULTIPLE),
            strokeWidth = PLAYHEAD_WIDTH_PX,
        )
    }
}

private const val RULER_TICK_WIDTH_PX = 1f
private const val SELECTION_BORDER_PX = 3f
private const val SELECTION_STRIPE_PX = 6f

/** How far a clip's top-edge tick reaches down the clip (task A3). A mark, not a division. */
private const val CLIP_TOP_TICK_PX = 6f
private const val PLAYHEAD_WIDTH_PX = 3f

/** UI revision 1: the line reaches three track-heights down, so it reads over the filmstrip. */
private const val PLAYHEAD_TRACK_MULTIPLE = 3f
internal const val TRIM_EDGE_PX = 4f
private const val REORDER_MARKER_PX = 6f

/**
 * The audio body's geometry (D3), in the same raw pixels as the strokes above.
 *
 * The band insets are the one exception and are in dp, because they are POSITIONS inside the
 * fixed 56 dp track — a stroke's width in raw px is density-independent by design, a position
 * inside the track is not, and a band that did not scale with the lane would drift into the
 * label at one density and leave the bottom bare at another.
 */
private const val WAVE_BAND_TOP_DP = 22f
private const val WAVE_BAND_BOTTOM_DP = 8f
private const val AUDIO_LABEL_INSET_DP = 8f
private const val AUDIO_LABEL_BASELINE_DP = 16f

/** The waveform motif: pitch, cap, stroke, and the level range the bars swing across. */
private const val WAVE_BAR_PITCH_PX = 6f
private const val WAVE_MAX_BARS = 128
private const val WAVE_BAR_WIDTH_PX = 2f
private const val WAVE_MIN_LEVEL = 0.2f
private const val WAVE_MAX_LEVEL = 1f
private const val WAVE_CENTERLINE_WIDTH_PX = 1f

/**
 * The audio label: the word the plan asked for ("a labelled lane — the label is what proves the
 * track is there"), and where its line sits: size in sp so it follows the user's font scale,
 * baseline in dp like the band above, so label and wave keep the same geometry everywhere.
 */
private const val AUDIO_CLIP_LABEL = "AUDIO"
private const val AUDIO_LABEL_TEXT_SIZE_SP = 10f
