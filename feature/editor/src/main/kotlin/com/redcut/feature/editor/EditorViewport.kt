package com.redcut.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.redcut.domain.document.ViewportRect
import com.redcut.domain.document.snappedToCentre

/**
 * Preview viewport gesture handling and rect overlay (spec UI revision 2, §WS F / Tasks F3-F4).
 *
 * User's requirement (§1 row 12):
 * "zoom visual hanya pada area top 50% screen dengan catatan, clip sedang diselect.
 *  ada juga snap vertical horizontal center"
 *
 * Provides pinch-to-zoom (about the rect centre) and drag-to-pan gestures over the
 * preview stage. Renders the viewport rect outline and snap guide lines over the preview
 * frame when a clip is selected. When no clip is selected, gestures and overlay are completely
 * inert and invisible.
 */
@Composable
internal fun EditorViewport(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val selection = state.selection as? Selection.Clip
    val clip = selection?.let { state.document.clipById(it.clipId) }
    val isClipSelected = clip != null

    val initialRect = remember(clip?.id, clip?.transform, state.document.canvas) {
        clip?.let {
            ViewportRect.fromTransformSpec(it.transform, state.document.canvas)
        } ?: ViewportRect(canvasSpec = state.document.canvas)
    }

    var currentRect by remember(initialRect) { mutableStateOf(initialRect) }

    Box(
        modifier = modifier.viewportGestures(
            enabled = isClipSelected,
            initialRect = initialRect,
            onViewportChange = { rect ->
                currentRect = rect
                onIntent(EditorIntent.SetViewport(rect.centerX, rect.centerY, rect.zoom))
            },
        ),
    ) {
        content()
        if (isClipSelected) {
            ViewportOverlay(
                rect = currentRect,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Overlay rendering the viewport rectangle outline and snap lines over the preview.
 */
@Composable
internal fun ViewportOverlay(
    rect: ViewportRect,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val paint = remember(colors) {
        ViewportPaint(
            rectBorder = colors.primary,
            cornerHandle = colors.primary,
            snapLine = colors.tertiary,
        )
    }

    Canvas(modifier = modifier) {
        drawViewportOverlay(rect = rect, paint = paint)
    }
}

/**
 * Attaches pinch-to-zoom and drag-to-pan gesture detectors to the preview viewport.
 *
 * When [enabled] is false (i.e. nothing is selected), no gesture detector is attached
 * and the modifier is inert.
 */
internal fun Modifier.viewportGestures(
    enabled: Boolean,
    initialRect: ViewportRect,
    onViewportChange: (ViewportRect) -> Unit,
): Modifier {
    if (!enabled) return this

    return pointerInput(enabled, initialRect) {
        var currentRect = initialRect
        val frameWidthPx = size.width.toFloat()
        val frameHeightPx = size.height.toFloat()
        if (frameWidthPx <= 0f || frameHeightPx <= 0f) return@pointerInput

        detectTransformGestures { _, pan, zoom, _ ->
            val deltaX = pan.x / frameWidthPx
            val deltaY = pan.y / frameHeightPx
            val zoomed = currentRect.zoomedBy(zoom)
            val panned = zoomed.pannedBy(deltaX, deltaY)
            val snapped = panned.snappedToCentre(
                hasSelection = true,
                frameWidthPx = frameWidthPx,
                frameHeightPx = frameHeightPx,
            )
            currentRect = snapped
            onViewportChange(snapped)
        }
    }
}
