package com.redcut.feature.editor.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.redcut.core.common.timeline.ClipRect
import com.redcut.core.common.timeline.ClipSpan
import com.redcut.core.common.timeline.TimelineGeometry
import com.redcut.core.common.timeline.TimelineZoom
import com.redcut.core.media.ThumbnailKey
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.TrackKind
import com.redcut.domain.document.reorderMarkerUs
import com.redcut.domain.document.reorderTargetIndex
import com.redcut.feature.editor.EditorIntent
import com.redcut.feature.editor.Selection
import com.redcut.feature.editor.ToolState
import com.redcut.feature.editor.clipIdOrNull
import com.redcut.feature.editor.laneSpans
import com.redcut.feature.editor.trackIdOrNull

/**
 * The timeline surface (spec §7.1): a custom Compose `Canvas`, not a row of composables.
 *
 * ### What is drawn, and what does the deciding
 *
 * Nothing here computes time or pixels. [TimelineGeometry] answers every question — where a clip is,
 * what a touch hit, which ruler ticks are visible, where the playhead goes — and that arithmetic is
 * tested in `:core:common`'s fast tier. [TimelineSlices] answers which frames a clip shows.
 * [TimelineDraw] paints them. This composable is the wiring: state in, draw calls out. That split is
 * why the timeline has tests at all — a `Canvas` that computed its own rectangles could only be
 * verified by looking at it.
 *
 * ### Lanes, and what is below the last one
 *
 * The document's tracks are drawn as LANES (schema v3): one band per track, stacked from
 * the top of the track area — below the ruler — one track height each. This surface
 * fills the container it is given rather than sizing itself to the lane count, so a
 * document with more tracks than fit shows the first ones and the rest are NOT drawn.
 * That is the honest behaviour for a timeline with no vertical scroll gesture: a lane
 * the user cannot scroll to is better absent than half-drawn.
 *
 * ### Gestures, and who owns which surface
 *
 * * **Ruler strip** (the top [RULER_HEIGHT_DP]): the playhead's own surface. Touching or dragging
 *   there scrubs.
 * * **A clip's edge zone** (the spec's 48 dp around a boundary, FR-2.1): a trim. Scrubbing lives on
 *   the ruler and trimming on the edges precisely because one surface cannot mean both.
 * * **The rest of the clip area**: pan scrolls, pinch zooms about the centroid, a tap selects.
 *
 * The arbitration between them is behaviour, and behaviour here is verified by a device pass (Phase
 * 1's exit criterion), not by CI: this host has no Android runtime, and no test can press a finger on
 * this Canvas. What CI proves is that it compiles and that the arithmetic underneath it is right.
 *
 * ### Why the viewport is view state, not UI state
 *
 * `zoom` and `scroll` live in `rememberSaveable` here rather than in `EditorUiState` (§7.2). They are
 * how the user is LOOKING at the document, like a scroll position in a list; putting them in the
 * state object would make every scroll pixel a state emission the whole editor recomposes for, and
 * `rememberSaveable` is what makes them survive a configuration change. The TOOL is the opposite and
 * lives in the state object: a trim in flight has already changed the document.
 */
@Composable
internal fun TimelineCanvas(
    document: EditDocument,
    playheadUs: Long,
    selection: Selection,
    tool: ToolState,
    onIntent: (EditorIntent) -> Unit,
    onThumbnail: suspend (sourceId: String, uri: String, positionUs: Long) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    var zoomPxPerSecond by rememberSaveable {
        mutableFloatStateOf(TimelineZoom.DEFAULT.pixelsPerSecond)
    }
    var viewportWidthPx by remember { mutableFloatStateOf(0f) }

    // No stored scroll offset (UI revision 1): the playhead is the fixed line, so the offset that puts it
    // under the line is DERIVED from it. Storing both would be storing the same fact twice, and the two
    // could disagree — a scroll the user changed without the playhead moving is exactly the state the
    // revision removes.
    val layer = rememberTimelineLayer(
        document = document,
        playheadUs = playheadUs,
        zoomPxPerSecond = zoomPxPerSecond,
        viewportWidthPx = viewportWidthPx,
        density = density,
        onThumbnail = onThumbnail,
    )
    val paint = rememberTimelinePaint()
    val rulerHeightPx = RULER_HEIGHT_DP * density

    // The caption's lane (FR-4.3's card 4) under the last media lane; textLaneFor carries the "where"
    // rule, so only the reading is here.
    val textLane = remember(document) { textLaneFor(document, layer.geometry, rulerHeightPx) }
    // The reorder drag's state lives HERE rather than in `EditorUiState`: until the finger lifts the
    // document has not changed at all, and the marker is a drawing of where it would land. A drag in
    // flight is not an edit yet, so it must not be state the whole editor recomposes for.
    val (actions, reorderDrag) = rememberTimelineActions(
        document = document,
        selection = selection,
        geometry = layer.geometry,
        playheadUs = playheadUs,
        reorderTextLane = textLane,
        layer = layer,
        onIntent = onIntent,
        setZoomPxPerSecond = { zoomPxPerSecond = it },
    )

    // The caption lane's edge-drag callbacks (FR-4.3's card 4), mapped by [textTrimFor].
    val textTrim = textTrimFor(onIntent)

    Canvas(
        modifier = modifier.timelineSurface(
            geometry = layer.geometry,
            rulerHeightPx = rulerHeightPx,
            actions = actions,
            textLane = textLane,
            textTrim = textTrim,
            onViewportWidthPx = { viewportWidthPx = it },
        ),
    ) {
        // The text lane FIRST, so the playhead — drawn by drawTimeline — stays the topmost line on the
        // screen: its 3× height crosses this band the same way it crosses the tracks, and a lane drawn
        // after it would cut the line in two (FR-4.3's card 4: one caption per item, edges draggable,
        // body tappable).
        val marks = timelineMarks(document, playheadUs, selection, tool, reorderDrag.value)
        drawTextLane(
            lane = textLane,
            geometry = layer.geometry,
            paint = paint,
            marks = marks,
        )
        drawTimeline(
            layer = layer,
            paint = paint,
            marks = marks,
        )
    }
}

/**
 * What the draw pass marks as "where the user is": the playhead, the selected clip, the edge being
 * dragged, and the slot a reorder would drop into.
 *
 * A MAPPING rather than a layout, which is why it is its own function: interaction state in, the
 * drawing's own vocabulary out. The reorder marker is the part that needs the DOCUMENT — a drop lands
 * inside one lane's own order, so the lane is resolved from the clip being dragged — and keeping that
 * lookup in here leaves the composable as the wiring this file says it is: state in, draw calls out.
 *
 * The lane lookup is a stopgap the gesture layer is expected to take over: once a drag carries a
 * `(track, clip)` pair, the marker is arithmetic on the pair and nothing here needs the document.
 */
private fun timelineMarks(
    document: EditDocument,
    playheadUs: Long,
    selection: Selection,
    tool: ToolState,
    reorderDrag: ReorderDrag?,
): TimelineMarks = TimelineMarks(
    playheadUs = playheadUs,
    selectedClipId = selection.clipIdOrNull,
    // The lane the user is working with (§WS E / Task E1). Beside the clip's, not instead of it: the two
    // selections are mutually exclusive by construction, and each is read by the marks that draw it.
    selectedTrackId = selection.trackIdOrNull,
    trimmedClipId = (tool as? ToolState.Trimming)?.clipId,
    draggedEdge = (tool as? ToolState.Trimming)?.edge,
    draggedClipId = reorderDrag?.clipId,
    markerUs = reorderDrag?.let { drag ->
        document.trackIdOf(drag.clipId)?.let { trackId ->
            document.reorderMarkerUs(trackId, drag.clipId, drag.targetIndex)
        }
    },
    selectedTextId = (selection as? Selection.Text)?.effectId,
    textTrimmedId = (tool as? ToolState.TrimmingText)?.effectId,
    draggedTextEdge = (tool as? ToolState.TrimmingText)?.edge,
)

/** The reorder drag as the Canvas sees it: which clip, which slot, and where the finger last was. */
private data class ReorderDrag(val clipId: String, val targetIndex: Int, val lastScreenX: Float)

/**
 * The layer: everything the timeline derives from the document and the viewport, in one value.
 *
 * The pipeline is the point, and it is worth reading as a chain: clips → lanes (one per
 * track, with the domain's own starts) → geometry (zoom, scroll, density) → lane bands
 * with their culled clips → slices (which frames the filmstrip asks for) → images (the
 * ones that arrived), with the AUDIO lanes' ids riding along beside them (D3). Each step
 * is tested somewhere in the fast tier or in CI; this function is only their order.
 */
@Composable
private fun rememberTimelineLayer(
    document: EditDocument,
    playheadUs: Long,
    zoomPxPerSecond: Float,
    viewportWidthPx: Float,
    density: Float,
    onThumbnail: suspend (sourceId: String, uri: String, positionUs: Long) -> ImageBitmap?,
): TimelineLayer {
    val clipsById = remember(document) { document.clips.associateBy { it.id } }
    // LANES, not one flat list (schema v3): every track's clips with the starts the DOMAIN
    // derives. The flat reading laid one track's clips after another's — true for a
    // document with one track and a lie about a document with two.
    val lanes = remember(document) { document.laneSpans() }
    val spans = remember(lanes) { lanes.flatMap { it.spans } }
    val spansByClip = remember(spans) { spans.associateBy { it.clipId } }
    // The AUDIO lanes' ids (D3): the draw pass reads audio-ness off the lane band it is already
    // iterating, and the filmstrip skips those lanes' clips — an audio source has no frames.
    // Keyed on the document like every projection above it. A document without AUDIO lanes
    // yields an empty set, and the flat reading's lane carries no track id at all, so both
    // draw as they always drew.
    val audioTrackIds = remember(document) {
        document.tracks
            .filter { it.kind == TrackKind.AUDIO }
            .map { it.id }
            .toSet()
    }
    // Two steps, because the scroll that centres the playhead is a function OF a geometry: build it at 0,
    // ask where the playhead should sit, then keep that offset. The alternative — a static helper taking
    // every input the geometry already holds — is the same arithmetic written twice.
    val unscrolled = TimelineGeometry(
        viewportWidthPx = viewportWidthPx,
        zoom = TimelineZoom(zoomPxPerSecond),
        scrollPx = 0f,
        density = density,
        // `lanes` and NOT `spans`: a caller passes one reading or the other, and this
        // caller has the document. Passing both would leave the flat members answering
        // about an empty list.
        lanes = lanes,
    )
    val geometry = unscrolled.copy(scrollPx = unscrolled.scrollCentering(playheadUs))
    // Per lane, so the draw pass gets a band and its clips as one value and cannot pair a
    // lane with another lane's clips. The flat list below is built here rather than carried
    // on the layer, because its two readers are per CLIP, not per lane — the filmstrip's
    // slice inputs and the gesture layer's spans — and they ask a clip id a question.
    val lanesOnScreen = geometry.visibleRectsByLane()
    val rects = lanesOnScreen.flatMap { it.rects }
    val requests = rememberSliceRequests(document, clipsById, geometry, rects, spans)
    val images = rememberThumbnails(requests, onThumbnail)
    return TimelineLayer(
        geometry = geometry,
        lanes = lanesOnScreen,
        slices = requests,
        images = images,
        clipsById = clipsById,
        spansByClip = spansByClip,
        audioTrackIds = audioTrackIds,
    )
}

/**
 * The surface: its size, and the gesture chain.
 *
 * One call rather than an inline chain, so the composable above reads as "state, then draw" and the
 * gesture ORDER stays stated in exactly one place ([timelineGestures]).
 *
 * `@Composable` because [timelineGestures] is one now: it reads the live mapping through
 * `rememberUpdatedState`, and reading a state is a composable act. The first version of this helper was
 * plain and CI said so — this is the kind of annotation a compiler enforces rather than a reviewer.
 */
@Composable
private fun Modifier.timelineSurface(
    geometry: TimelineGeometry,
    rulerHeightPx: Float,
    actions: TimelineGestures,
    textLane: TextLane?,
    textTrim: TextTrimGestures?,
    onViewportWidthPx: (Float) -> Unit,
): Modifier = this
    .fillMaxSize()
    .onSizeChanged { size -> onViewportWidthPx(size.width.toFloat()) }
    .timelineGestures(
        geometry = geometry,
        rulerHeightPx = rulerHeightPx,
        actions = actions,
        textLane = textLane,
        textTrim = textTrim,
    )

/**
 * The gesture handlers plus the reorder drag's in-flight state, as one remembered value.
 *
 * Extracted from [TimelineCanvas] because the composable crossed detekt's LongMethod limit once the
 * lane work joined it — the reorder drag's state must live next to the handlers that read and write
 * it (a drag in flight is not an edit yet, so it is not `EditorUiState`), and the pair is one thing
 * the canvas mounts. Returns the handlers and the drag together so the draw pass keeps reading the
 * same drag the gestures write.
 */
@Composable
private fun rememberTimelineActions(
    document: EditDocument,
    selection: Selection,
    geometry: TimelineGeometry,
    playheadUs: Long,
    reorderTextLane: TextLane,
    layer: TimelineLayer,
    onIntent: (EditorIntent) -> Unit,
    setZoomPxPerSecond: (Float) -> Unit,
): Pair<TimelineGestures, State<ReorderDrag?>> {
    // Held as the State OBJECT, not delegated with `by`: the pair this returns hands the caller the
    // drag STATE (it reads `reorderDrag.value` when it draws the marker), so it must be the State and
    // not the unwrapped value — otherwise the marker never recomposes as the drag moves.
    val reorderDrag = remember { mutableStateOf<ReorderDrag?>(null) }
    val reorderGestures = buildReorderGestures(
        document = document,
        geometry = geometry,
        current = { reorderDrag.value },
        setDrag = { reorderDrag.value = it },
        onIntent = onIntent,
    )
    val actions = timelineGestureHandlers(
        geometry = geometry,
        onIntent = onIntent,
        clipsById = layer.clipsById,
        spansByClip = layer.spansByClip,
        playheadUs = playheadUs,
        setZoomPxPerSecond = setZoomPxPerSecond,
        reorder = reorderGestures,
        textLane = reorderTextLane,
        selectedClipId = (selection as? Selection.Clip)?.clipId,
        selectedTrackId = selection.trackIdOrNull,
        selectedTextId = (selection as? Selection.Text)?.effectId,
    )
    return actions to reorderDrag
}

/**
 * Turns finger positions into a slot, using the document's own arithmetic.
 *
 * `reorderTargetIndex` is the whole reason a drag can be this thin: it answers "if I let go here, which
 * index does that mean", including the awkward cases (dropping past the end, dropping onto the clip's
 * own old slot). The gesture layer supplies positions; the document decides what they mean; this
 * function is the join between them — and the slot it reports is the same number the command will be
 * given, which is what stops the clip landing somewhere the marker never pointed at.
 */
private fun buildReorderGestures(
    document: EditDocument,
    geometry: TimelineGeometry,
    current: () -> ReorderDrag?,
    setDrag: (ReorderDrag?) -> Unit,
    onIntent: (EditorIntent) -> Unit,
): ReorderGestures {
    fun targetAt(clipId: String, screenX: Float): Int {
        // The same lane lookup the marker uses, and for the same reason: a drop lands in one track's
        // order. A clip id no track holds has no slot to land in, so the answer is 0 and the drop is a
        // no-op rather than a guess.
        val trackId = document.trackIdOf(clipId) ?: return 0
        return document.reorderTargetIndex(
            trackId,
            clipId,
            geometry.usFor(geometry.contentPxFor(screenX)),
        )
    }

    return ReorderGestures(
        start = { clipId, screenX ->
            setDrag(
                ReorderDrag(
                    clipId = clipId,
                    targetIndex = targetAt(clipId, screenX),
                    lastScreenX = screenX,
                ),
            )
        },
        update = { screenX ->
            current()?.let { drag ->
                setDrag(
                    drag.copy(targetIndex = targetAt(drag.clipId, screenX), lastScreenX = screenX),
                )
            }
        },
        end = {
            // One command, one history entry: a reorder is atomic, so there is nothing to preview frame
            // by frame the way a trim has to.
            current()?.let { drag ->
                onIntent(
                    EditorIntent.ApplyReorder(drag.clipId, drag.targetIndex),
                )
            }
            setDrag(null)
        },
        cancel = { setDrag(null) },
    )
}

/** The nine timeline colours, read from the theme where reading it is legal. */
@Composable
private fun rememberTimelinePaint(): TimelinePaint = TimelinePaint(
    clip = MaterialTheme.colorScheme.surfaceVariant,
    selectedClip = MaterialTheme.colorScheme.primaryContainer,
    selectionBorder = MaterialTheme.colorScheme.primary,
    ruler = MaterialTheme.colorScheme.outlineVariant,
    playhead = PLAYHEAD_RED,
    trimEdge = MaterialTheme.colorScheme.tertiary,
    reorderMarker = MaterialTheme.colorScheme.secondary,
    audioWaveform = MaterialTheme.colorScheme.onSurfaceVariant,
    textItem = MaterialTheme.colorScheme.secondaryContainer,
    textItemSelected = MaterialTheme.colorScheme.primaryContainer,
    onRuler = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * The visible clips, as slice inputs — the window included.
 *
 * `sourceTimeAt` is the clip's OWN mapping (spec §5.1 keeps speed and reverse in the domain), so the
 * filmstrip and the renderer cannot disagree about which frame a timeline position refers to.
 *
 * The two window fields are the geometry's own culling window ([TimelineGeometry.cullStartPx],
 * [TimelineGeometry.cullEndPx]), which is the margin [TimelineGeometry.visibleRects] already culled
 * these rects by. They are read HERE rather than re-derived at the call site, and they are passed
 * on rather than left to default: the default means "the whole clip", which is right for a caller
 * with no viewport and wrong for this one — the filmstrip would then ask for every slice of a
 * ten-minute clip to draw one screen of it. Nothing outside the window can be drawn, so nothing
 * outside it is requested.
 *
 * ### The clips that ask for nothing (D3)
 *
 * A clip whose track is AUDIO is excluded before any slice arithmetic runs: an audio source has
 * no frames to decode, so no [SliceRequest] and no [ThumbnailKey] is ever built for it and the
 * thumbnail loader is never asked. The audio-ness it reads is the TRACK's (`Track.kind`), the
 * same fact the draw pass reads off the lane band — and the body those clips draw instead is a
 * synthesized waveform, not a strip of missing thumbnails.
 */
@Composable
private fun rememberSliceRequests(
    document: EditDocument,
    clipsById: Map<String, com.redcut.domain.document.Clip>,
    geometry: TimelineGeometry,
    rects: List<ClipRect>,
    spans: List<ClipSpan>,
): List<SliceRequest> {
    val sourcesById = remember(document) { document.sources.associateBy { it.id } }
    return TimelineSlices.requests(
        rects.mapNotNull { rect ->
            val clip = clipsById[rect.clipId] ?: return@mapNotNull null
            // An audio clip sits on an AUDIO lane and its source has no frames: skipping here is
            // what keeps onThumbnail from ever being called for one (D3).
            if (document.trackOf(clip.id)?.kind == TrackKind.AUDIO) return@mapNotNull null
            val source = sourcesById[clip.sourceId] ?: return@mapNotNull null
            val span = spans.firstOrNull { it.clipId == rect.clipId } ?: return@mapNotNull null
            ClipSliceInput(
                clipId = rect.clipId,
                sourceId = clip.sourceId,
                uri = source.uri,
                leftPx = rect.startPx,
                widthPx = rect.widthPx,
                durationUs = span.durationUs,
                sourceTimeAt = clip::sourceTimeAt,
                windowStartPx = geometry.cullStartPx,
                windowEndPx = geometry.cullEndPx,
            )
        },
    )
}

/**
 * Loads the requested thumbnails, keyed on the slice keys.
 *
 * Keyed on the KEYS rather than on the requests: scrolling changes where a slice is drawn but not
 * which frame it shows, and reloading every thumbnail per scroll pixel is the jank NFR-8 is about.
 */
@Composable
private fun rememberThumbnails(
    requests: List<SliceRequest>,
    onThumbnail: suspend (sourceId: String, uri: String, positionUs: Long) -> ImageBitmap?,
): Map<ThumbnailKey, ImageBitmap> {
    val keys = requests.map { it.key }
    var images by remember { mutableStateOf<Map<ThumbnailKey, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(keys) {
        images = requests
            .mapNotNull { request ->
                onThumbnail(request.sourceId, request.uri, request.positionUs)
                    ?.let { request.key to it }
            }
            .toMap()
    }
    return images
}

/** The ruler strip's height. 24 dp is a finger's worth of target above the clips. */
internal const val RULER_HEIGHT_DP = 24f

/**
 * The caption lane's edge-drag callbacks (FR-4.3's card 4): the four moments, mapped to intents.
 *
 * Extracted from [TimelineCanvas] so the composable reads its gestures as one line — the inlined
 * construction pushed the composable over detekt's LongMethod limit, and the four mappings are the
 * lane's own contract, not the canvas's layout.
 */
private fun textTrimFor(onIntent: (EditorIntent) -> Unit): TextTrimGestures = TextTrimGestures(
    begin = { effectId, edge, us -> onIntent(EditorIntent.BeginTextTrim(effectId, edge, us)) },
    update = { us -> onIntent(EditorIntent.UpdateTextTrim(us)) },
    end = { onIntent(EditorIntent.EndTextTrim) },
    cancel = { onIntent(EditorIntent.CancelTextTrim) },
)

/**
 * The playhead's red (UI revision 1, asked for by name).
 *
 * A literal rather than a theme colour, and named rather than inline: a playhead that shifted with the
 * theme would stop being the one fixed reference on the screen, and a colour nobody can find by name is a
 * colour the next reader will re-invent slightly differently.
 */
// Named components rather than the 0xFFFF2A2A literal: detekt reads a packed hex colour as a magic
// number, and the components say what the colour IS (#FF2A2A) without fighting the linter.
private val PLAYHEAD_RED = Color(red = 0xFF, green = 0x2A, blue = 0x2A)
