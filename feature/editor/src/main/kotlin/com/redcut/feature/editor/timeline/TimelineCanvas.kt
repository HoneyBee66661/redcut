package com.redcut.feature.editor.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.redcut.core.common.timeline.spansOf
import com.redcut.core.media.ThumbnailKey
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.reorderMarkerUs
import com.redcut.domain.document.reorderTargetIndex
import com.redcut.feature.editor.EditorIntent
import com.redcut.feature.editor.Selection
import com.redcut.feature.editor.ToolState
import com.redcut.feature.editor.clipIdOrNull
import com.redcut.feature.editor.toClipTimings

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

    // The reorder drag's state lives HERE rather than in `EditorUiState`: until the finger lifts the
    // document has not changed at all, and the marker is a drawing of where it would land. That is the
    // same reasoning as the viewport below, from the other direction — a drag in flight is not an edit
    // yet, so it must not be state the whole editor recomposes for or history can see.
    var reorderDrag by remember { mutableStateOf<ReorderDrag?>(null) }
    val reorderGestures = buildReorderGestures(
        document = document,
        geometry = layer.geometry,
        current = { reorderDrag },
        setDrag = { reorderDrag = it },
        onIntent = onIntent,
    )
    val actions = timelineGestureHandlers(
        geometry = layer.geometry,
        onIntent = onIntent,
        clipsById = layer.clipsById,
        spansByClip = layer.spansByClip,
        playheadUs = playheadUs,
        setZoomPxPerSecond = { zoomPxPerSecond = it },
        reorder = reorderGestures,
        // The tap rule is stateful: the first tap on a clip selects it, and a tap on the already
        // selected clip seeks (device pass, second round).
        selectedClipId = (selection as? Selection.Clip)?.clipId,
    )

    Canvas(
        modifier = modifier.timelineSurface(
            geometry = layer.geometry,
            rulerHeightPx = rulerHeightPx,
            actions = actions,
            onViewportWidthPx = { viewportWidthPx = it },
        ),
    ) {
        drawTimeline(
            layer = layer,
            paint = paint,
            marks = timelineMarks(document, playheadUs, selection, tool, reorderDrag),
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
    trimmedClipId = (tool as? ToolState.Trimming)?.clipId,
    draggedEdge = (tool as? ToolState.Trimming)?.edge,
    draggedClipId = reorderDrag?.clipId,
    markerUs = reorderDrag?.let { drag ->
        document.trackIdOf(drag.clipId)?.let { trackId ->
            document.reorderMarkerUs(trackId, drag.clipId, drag.targetIndex)
        }
    },
)

/** The reorder drag as the Canvas sees it: which clip, which slot, and where the finger last was. */
private data class ReorderDrag(val clipId: String, val targetIndex: Int, val lastScreenX: Float)

/**
 * The layer: everything the timeline derives from the document and the viewport, in one value.
 *
 * The pipeline is the point, and it is worth reading as a chain: clips → spans (prefix-summed start
 * positions) → geometry (zoom, scroll, density) → rects (which clips are on screen and how wide) →
 * slices (which frames the filmstrip asks for) → images (the ones that arrived). Each step is tested
 * somewhere in the fast tier or in CI; this function is only their order.
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
    val spans = remember(document) { spansOf(document.toClipTimings()) }
    val spansByClip = remember(spans) { spans.associateBy { it.clipId } }
    // Two steps, because the scroll that centres the playhead is a function OF a geometry: build it at 0,
    // ask where the playhead should sit, then keep that offset. The alternative — a static helper taking
    // every input the geometry already holds — is the same arithmetic written twice.
    val unscrolled = TimelineGeometry(
        viewportWidthPx = viewportWidthPx,
        spans = spans,
        zoom = TimelineZoom(zoomPxPerSecond),
        scrollPx = 0f,
        density = density,
    )
    val geometry = unscrolled.copy(scrollPx = unscrolled.scrollCentering(playheadUs))
    val rects = geometry.visibleRects()
    val requests = rememberSliceRequests(document, clipsById, rects, spans)
    val images = rememberThumbnails(requests, onThumbnail)
    return TimelineLayer(
        geometry = geometry,
        rects = rects,
        slices = requests,
        images = images,
        clipsById = clipsById,
        spansByClip = spansByClip,
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
    onViewportWidthPx: (Float) -> Unit,
): Modifier = this
    .fillMaxSize()
    .onSizeChanged { size -> onViewportWidthPx(size.width.toFloat()) }
    .timelineGestures(geometry = geometry, rulerHeightPx = rulerHeightPx, actions = actions)

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

/** The seven timeline colours, read from the theme where reading it is legal. */
@Composable
private fun rememberTimelinePaint(): TimelinePaint = TimelinePaint(
    clip = MaterialTheme.colorScheme.surfaceVariant,
    selectedClip = MaterialTheme.colorScheme.primaryContainer,
    selectionBorder = MaterialTheme.colorScheme.primary,
    ruler = MaterialTheme.colorScheme.outlineVariant,
    playhead = PLAYHEAD_RED,
    trimEdge = MaterialTheme.colorScheme.tertiary,
    reorderMarker = MaterialTheme.colorScheme.secondary,
)

/**
 * The visible clips, as slice inputs.
 *
 * `sourceTimeAt` is the clip's OWN mapping (spec §5.1 keeps speed and reverse in the domain), so the
 * filmstrip and the renderer cannot disagree about which frame a timeline position refers to.
 */
@Composable
private fun rememberSliceRequests(
    document: EditDocument,
    clipsById: Map<String, com.redcut.domain.document.Clip>,
    rects: List<ClipRect>,
    spans: List<ClipSpan>,
): List<SliceRequest> {
    val sourcesById = remember(document) { document.sources.associateBy { it.id } }
    return TimelineSlices.requests(
        rects.mapNotNull { rect ->
            val clip = clipsById[rect.clipId] ?: return@mapNotNull null
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
 * The playhead's red (UI revision 1, asked for by name).
 *
 * A literal rather than a theme colour, and named rather than inline: a playhead that shifted with the
 * theme would stop being the one fixed reference on the screen, and a colour nobody can find by name is a
 * colour the next reader will re-invent slightly differently.
 */
// Named components rather than the 0xFFFF2A2A literal: detekt reads a packed hex colour as a magic
// number, and the components say what the colour IS (#FF2A2A) without fighting the linter.
private val PLAYHEAD_RED = Color(red = 0xFF, green = 0x2A, blue = 0x2A)
