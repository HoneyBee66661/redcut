package com.redcut.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.redcut.domain.document.ClipEdge
import com.redcut.domain.document.PreviewTarget
import com.redcut.domain.document.previewTargetAt

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
 * 2. something under the playhead — the frame the playhead maps to ([PlayheadFrame], FR-2's "correct
 *    preview");
 * 3. neither — a sentence describing the stage, which is what a document with no clips gets.
 */

/**
 * The stage's body, which takes the room the timeline does not.
 *
 * An extension on `ColumnScope` because `Modifier.weight` only exists in a column or row: the weight is
 * the reason this cannot be a plain composable.
 */
@Composable
internal fun ColumnScope.StageBody(
    state: EditorUiState,
    onThumbnail: suspend (sourceId: String, uri: String, positionUs: Long) -> ImageBitmap?,
    onPreviewFrame: suspend (uri: String, positionUs: Long) -> ImageBitmap?,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val trimming = state.tool as? ToolState.Trimming
        val preview = remember(state.document, state.playheadUs) {
            state.document.previewTargetAt(state.playheadUs)
        }
        when {
            trimming != null -> EdgeFrame(
                state = state,
                trimming = trimming,
                onThumbnail = onThumbnail,
            )
            preview != null -> PlayheadFrame(target = preview, onPreviewFrame = onPreviewFrame)
            else -> Text(
                text = state.stage.detail,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The frame at the playhead (FR-2's "correct preview").
 *
 * `previewTargetAt` did the hard part: it mapped the playhead's TIMELINE position to a frame in the
 * SOURCE file, honouring the clip's trims, speed and direction. All that is left here is to ask for it
 * and to show it — and to re-ask when the playhead moves, which `LaunchedEffect(target)` does by
 * cancelling the previous request when the target changes: scrubbing fast leaves one decode in flight
 * rather than a queue of frames the user has already scrubbed past.
 *
 * A missing frame (still decoding, or a file that cannot be read) shows nothing rather than the last
 * frame: a stale frame under a moved playhead is a wrong answer, where a blank one is merely an
 * incomplete one.
 *
 * ### What this is not
 *
 * It is a still per playhead position, not playback. Continuous playback needs the composition path of
 * spec §8.4 (Phase 4.2) — this is what satisfies the exit criterion's word "scrub".
 */
@Composable
private fun PlayheadFrame(
    target: PreviewTarget,
    onPreviewFrame: suspend (uri: String, positionUs: Long) -> ImageBitmap?,
) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(target) {
        frame = onPreviewFrame(target.uri, target.sourceTimeUs)
    }
    frame?.let { image ->
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * FR-2.1's live preview: the frame the edge is being dragged to.
 *
 * While a trim is open the stage shows THE FRAME rather than a sentence about the stage — the user is
 * choosing an in- or out-point, and the only thing that answers "am I there yet" is the picture.
 *
 * ### The known cost
 *
 * This asks for a frame per drag update, and each one is a fresh decode (the cache key includes the
 * time, so every position is a miss). The broker's semaphores bound how many decodes run at once and
 * the store's LRU keeps the recent ones, so the UI degrades to a lagging frame rather than to jank —
 * but a fast drag is doing more decoding than it needs to, and `LaunchedEffect` cancelling the previous
 * request is the only throttling here. The real answer is the composition path's player, already
 * holding decoded frames, rather than a decoder asked for one picture at a time.
 */
@Composable
private fun EdgeFrame(
    state: EditorUiState,
    trimming: ToolState.Trimming,
    onThumbnail: suspend (sourceId: String, uri: String, positionUs: Long) -> ImageBitmap?,
) {
    val clip = state.document.clips.firstOrNull { it.id == trimming.clipId }
    val uri = clip?.let { c -> state.document.sources.firstOrNull { it.id == c.sourceId }?.uri }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(clip?.sourceId, uri, trimming.sourceTimeUs) {
        frame = if (clip != null && uri != null) {
            onThumbnail(clip.sourceId, uri, trimming.sourceTimeUs)
        } else {
            null
        }
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
