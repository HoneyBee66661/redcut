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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.redcut.core.common.timeline.ClipRect
import com.redcut.core.common.timeline.ClipSpan
import com.redcut.core.common.timeline.TimelineGeometry
import com.redcut.core.common.timeline.TimelineZoom
import com.redcut.core.common.timeline.spansOf
import com.redcut.core.media.ThumbnailKey
import com.redcut.domain.document.EditDocument
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
    var scrollPx by rememberSaveable { mutableFloatStateOf(0f) }
    var viewportWidthPx by remember { mutableFloatStateOf(0f) }

    val clipsById = remember(document) { document.clips.associateBy { it.id } }
    val spans = remember(document) { spansOf(document.toClipTimings()) }
    val spansByClip = remember(spans) { spans.associateBy { it.clipId } }
    val geometry = TimelineGeometry(
        viewportWidthPx = viewportWidthPx,
        spans = spans,
        zoom = TimelineZoom(zoomPxPerSecond),
        scrollPx = scrollPx,
        density = density,
    )
    val rects = geometry.visibleRects()
    val requests = rememberSliceRequests(document, clipsById, rects, spans)
    val images = rememberThumbnails(requests, onThumbnail)
    val paint = rememberTimelinePaint()
    val rulerHeightPx = RULER_HEIGHT_DP * density

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size -> viewportWidthPx = size.width.toFloat() }
            .timelineGestures(
                geometry = geometry,
                rulerHeightPx = rulerHeightPx,
                actions = timelineGestureHandlers(
                    geometry = geometry,
                    onIntent = onIntent,
                    clipsById = clipsById,
                    spansByClip = spansByClip,
                    setScrollPx = { scrollPx = it },
                    setZoomPxPerSecond = { zoomPxPerSecond = it },
                ),
            ),
    ) {
        val rulerHeight = RULER_HEIGHT_DP.dp.toPx()
        val track = Track(top = rulerHeight, height = (size.height - rulerHeight).coerceAtLeast(0f))
        val trimming = tool as? ToolState.Trimming

        drawRuler(geometry, rulerHeight, paint.ruler)
        rects.forEach { rect ->
            drawClip(
                rect = rect,
                slices = requests.filter { it.clipId == rect.clipId },
                images = images,
                selected = selection.clipIdOrNull == rect.clipId,
                draggedEdge = trimming?.takeIf { it.clipId == rect.clipId }?.edge,
                geometry = geometry,
                track = track,
                paint = paint,
            )
        }
        drawPlayhead(geometry, playheadUs, paint.playhead)
    }
}

/** The six timeline colours, read from the theme where reading it is legal. */
@Composable
private fun rememberTimelinePaint(): TimelinePaint = TimelinePaint(
    clip = MaterialTheme.colorScheme.surfaceVariant,
    selectedClip = MaterialTheme.colorScheme.primaryContainer,
    selectionBorder = MaterialTheme.colorScheme.primary,
    ruler = MaterialTheme.colorScheme.outlineVariant,
    playhead = MaterialTheme.colorScheme.error,
    trimEdge = MaterialTheme.colorScheme.tertiary,
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
