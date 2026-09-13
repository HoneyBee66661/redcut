package com.redcut.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.redcut.core.media.PreviewFrames
import com.redcut.feature.editor.timeline.TimelineThumbnails
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The editor's two image loaders, as one collaborator.
 *
 * ### Why they are one thing
 *
 * The timeline's filmstrip and the stage's trim still ask the same question — "show me a frame of this
 * file at this time" — at two different sizes, and they are both answers to the screen's `suspend`
 * loaders.
 * Folding them together is not cosmetic: it is what keeps the ViewModel's constructor inside `detekt`'s
 * parameter limit without a `@Suppress`, and the limit was pointing at something real, which is that a
 * screen with seven injected collaborators is a screen doing seven things.
 *
 * The COMPOSE conversion (`asImageBitmap`) lives here rather than in each caller, so the boundary between
 * "a platform `Bitmap`" and "something a `Canvas` can draw" is crossed in exactly one place.
 */
@Singleton
class EditorImages @Inject constructor(
    private val thumbnails: TimelineThumbnails,
    private val previewFrames: PreviewFrames,
) {

    /** The ≤ 160 px frame a filmstrip slice shows (§9.3). */
    suspend fun timelineThumbnail(sourceId: String, uri: String, positionUs: Long): ImageBitmap? =
        thumbnails.image(sourceId, uri, positionUs)

    /**
     * The 640 px still the stage shows while a trim edge is being dragged (FR-2.1).
     *
     * The playhead's own frame no longer comes through here: it is the renderer's, drawn on a surface
     * (§8.4). This still is what answers "which frame is the edge at" — a position the composition does
     * not contain, because a trim edge is dragged into media the clip has trimmed away.
     */
    suspend fun previewFrame(uri: String, positionUs: Long): ImageBitmap? =
        previewFrames.frame(uri, positionUs)?.asImageBitmap()
}
