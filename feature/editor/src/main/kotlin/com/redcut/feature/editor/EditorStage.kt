package com.redcut.feature.editor

import android.content.Context
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.redcut.core.media.PreviewRenderer
import com.redcut.core.media.PreviewState
import com.redcut.domain.document.ClipEdge
import com.redcut.domain.render.RenderGraph
import com.redcut.domain.render.TimelineCompiler
import kotlinx.coroutines.delay

/**
 * The stage: what the editor shows the user of their own video.
 *
 * Split out of `EditorScreen` when that file reached `detekt`'s function-count limit — and the limit
 * was pointing at something true, which is that the screen is LAYOUT (toolbar, tabs, timeline, history
 * bar) while the stage is CONTENT. The screen decides where the stage goes; this file decides what is
 * in it.
 *
 * The three states a stage can be in, in priority order:
 *
 * 1. a trim in flight — the frame at the edge being dragged ([EdgeFrame], FR-2.1);
 * 2. something on the timeline — the edit, rendered at the playhead ([PlayheadSurface], FR-2's
 *    "correct preview");
 * 3. neither — a sentence describing the stage, which is what a document with no clips gets.
 */

/** The debounce §8.1 puts between a revision change and rebuilding the preview. */
private const val PREVIEW_REBUILD_DEBOUNCE_MS = 120L

/**
 * The stage's body, which takes the room the timeline does not.
 *
 * The reason this cannot be a plain composable: it compiles the document. `TimelineCompiler.compile`
 * is pure and, in §8.1's words, cheap enough to run *"on every revision change without debouncing"* —
 * so the graph is derived here, from the document the screen already has, rather than passed down from
 * the ViewModel as a second copy of state.
 */
@Composable
internal fun StageBody(
    state: EditorUiState,
    renderer: PreviewRenderer,
    onPreviewFrame: suspend (uri: String, positionUs: Long) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        // A `modifier` rather than a `ColumnScope` receiver: the stage now sits inside the TOP HALF's own
        // box, and a scope-extension would only compile where the parent happens to be a Column. Filling
        // whatever it is given is what this actually needs.
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val trimming = state.tool as? ToolState.Trimming
        // The document is the key, and that is §8.1's recompilation trigger rather than a cache
        // heuristic: every edit produces a new revision, so a new graph, so a new preview.
        val graph = remember(state.document) { TimelineCompiler.compile(state.document) }

        when {
            trimming != null -> EdgeFrame(
                state = state,
                trimming = trimming,
                onPreviewFrame = onPreviewFrame,
            )
            // An empty document has nothing to play, and a renderer attached to nothing would be a
            // black rectangle where the sentence should be.
            graph.videoLayers.isNotEmpty() -> PlayheadSurface(renderer = renderer, graph = graph)
            else -> Text(
                text = state.stage.detail,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The edit, rendered at the playhead (FR-2's "correct preview").
 *
 * The frame is the **renderer's**, drawn onto a `SurfaceView` — never a `Bitmap` handed through
 * Compose. That is §6.8 rule D5 stated as code: *"Preview is surface/texture-based from day one —
 * `SurfaceView` + `SurfaceTexture`, never `Bitmap` or Canvas rendering... You cannot retrofit a GL
 * pipeline onto a Canvas-based preview."* It is also what makes playback possible at all: a still per
 * playhead position is not a video, and `PreviewRenderer.play` is what turns the same surface into one.
 *
 * ### Which frame it shows
 *
 * The playhead is a position on the TIMELINE, and the frame it refers to lives in a SOURCE file — the
 * journey between them crosses everything the Cut stage can change. That mapping is not computed here,
 * nor anywhere else in the UI: the graph's layers already carry it (`sourceRange` against `timeRange`,
 * with each clip's speed and direction applied by the compiler), so the renderer is handed a timeline
 * position and the graph and resolves the rest (§8.1). A trim, a split or a speed change therefore
 * moves the frame by changing the graph the preview is built from, rather than by changing an
 * arithmetic that two layers could disagree about.
 *
 * ### When it is rebuilt, and when it is let go
 *
 * §8.1: *"Preview rebuild is debounced at 120 ms during drags to avoid thrashing the player."* The
 * debounce is the `delay` below, and `LaunchedEffect` cancelling the previous attempt is what makes it
 * a debounce rather than a queue of rebuilds that each arrive late.
 *
 * §9.1 asks for the decoder back when it is not in use, and `onWindowVisibilityChanged` is where this
 * reads "the app went to the background": the platform dispatches it down the view tree when the
 * hosting window stops being visible, so a backgrounded editor releases its decoder and takes a new one
 * when the user returns. That matters because §9.1's rule is that *"preview and export cannot run
 * simultaneously"* — a preview holding a codec behind an export is exactly the exhaustion the broker
 * exists to prevent.
 */
@Composable
private fun PlayheadSurface(renderer: PreviewRenderer, graph: RenderGraph) {
    val context = LocalContext.current
    val surfaceView = remember(context) { PreviewSurfaceView(context) }
    val preview by renderer.state.collectAsState()
    var windowVisible by remember(surfaceView) { mutableStateOf(true) }

    DisposableEffect(surfaceView) {
        surfaceView.onWindowVisibility = { visible -> windowVisible = visible }
        onDispose {
            // Leaving the stage is leaving the preview: the renderer goes back to holding nothing, and
            // the next visitor to this screen attaches again.
            surfaceView.onWindowVisibility = null
            renderer.release()
        }
    }

    LaunchedEffect(graph.revision, windowVisible) {
        if (!windowVisible) {
            renderer.release()
            return@LaunchedEffect
        }
        delay(PREVIEW_REBUILD_DEBOUNCE_MS)
        renderer.attach(surfaceView, graph)
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
        // A renderer that cannot draw says why, in place of the frame it cannot show: a file whose
        // codec this device does not have is a real answer the user can act on, and a silent black
        // stage is not.
        (preview as? PreviewState.Unavailable)?.let { unavailable ->
            Text(
                text = unavailable.reason,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The stage's surface, which says when its window stops being visible.
 *
 * A subclass rather than a plain `SurfaceView` because "the app is in the background" has to be
 * readable here without a `Lifecycle` dependency this module does not declare, and because this is the
 * platform's own signal for it: `onWindowVisibilityChanged` is dispatched down the view tree when the
 * hosting window is hidden or shown — the same event that destroys the surface a video would have been
 * rendered to.
 *
 * Nullable, and cleared from a `DisposableEffect`: a view that outlives the composition that made it
 * must not call back into state nobody is holding any more.
 */
private class PreviewSurfaceView(context: Context) : SurfaceView(context) {

    /** Called with `true` when the window hosting this view becomes visible. */
    var onWindowVisibility: ((Boolean) -> Unit)? = null

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        onWindowVisibility?.invoke(visibility == View.VISIBLE)
    }
}

/**
 * FR-2.1's live preview: the frame the edge is being dragged to.
 *
 * While a trim is open the stage shows THE FRAME rather than a sentence about the stage — the user is
 * choosing an in- or out-point, and the only thing that answers "am I there yet" is the picture.
 *
 * ### Why this one is still a decode, and why that is not a shortcut around the renderer
 *
 * A trim edge is dragged into media the clip does NOT contain: pulling the in-point back re-includes
 * frames that were trimmed away, and those are by definition not in the composition the renderer is
 * playing. So the player cannot answer this question, and a still decode is the honest instrument —
 * the same `MediaMetadataRetriever` the filmstrip uses, behind the same broker (§9.1), at the stage's
 * size ([com.redcut.core.media.PreviewFrames.PREVIEW_WIDTH_PX]) rather than the filmstrip's.
 *
 * It is therefore the one `Bitmap` left in the preview path, and rule D5 is about the preview SURFACE
 * rather than about this: the stage is a `SurfaceView` now, and a still of a frame the composition does
 * not contain is a picture rather than a frame in the pipeline.
 *
 * The cost is real and unchanged: this asks for a frame per drag update, and each one is a fresh
 * decode. The broker's semaphores bound how many run at once and the store's LRU keeps the recent ones,
 * so the UI degrades to a lagging frame rather than to jank.
 */
@Composable
private fun EdgeFrame(
    state: EditorUiState,
    trimming: ToolState.Trimming,
    onPreviewFrame: suspend (uri: String, positionUs: Long) -> ImageBitmap?,
) {
    val clip = state.document.clips.firstOrNull { it.id == trimming.clipId }
    val uri = clip?.let { c -> state.document.sources.firstOrNull { it.id == c.sourceId }?.uri }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(clip?.sourceId, uri, trimming.sourceTimeUs) {
        // `sourceTimeUs` is already the edge's position in the SOURCE file — the drag is reported in
        // source time (ToolState.Trimming), so nothing has to be mapped back here.
        frame = if (uri != null) onPreviewFrame(uri, trimming.sourceTimeUs) else null
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        frame?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = "Trimming ${if (trimming.edge == ClipEdge.IN) "in" else "out"} · " +
                "${trimming.sourceTimeUs / 1_000} ms",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
