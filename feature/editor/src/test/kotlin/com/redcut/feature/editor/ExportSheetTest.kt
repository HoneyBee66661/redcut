package com.redcut.feature.editor

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.CanvasSpec
import org.junit.Test

/**
 * The export sheet's resolution rule (FR-5.1), tested as plain JVM code.
 *
 * No ViewModel, no dispatcher and no Compose: "720p and 1080p, and nothing else" is a fact about frame
 * sizes, and these two functions are the only place that decides which sizes the sheet may offer. The
 * other half of the same rule — opening the sheet, holding the pick, refusing anything else — is verified
 * through the ViewModel in [EditorViewModelTest], which is where the state it guards actually lives.
 */
class ExportSheetTest {

    @Test
    fun `the sheet offers 720p and 1080p, and nothing else`() {
        // The spec lists "resolutions beyond 720p/1080p" among its NON-GOALS, so this assertion is the
        // requirement rather than a snapshot: a third entry added later has to argue with it first.
        assertThat(exportResolutionsFor(CanvasSpec.PORTRAIT_1080))
            .containsExactly(CanvasSpec.PORTRAIT_720, CanvasSpec.PORTRAIT_1080)
            .inOrder()
    }

    @Test
    fun `the sizes follow the project's orientation`() {
        // An export that silently flips a landscape project into portrait is a worse bug than a missing
        // option, so the orientation comes from the document's canvas (FR-5.7) rather than from a pair of
        // portrait constants — the same two sizes, in the frame the project is already in.
        assertThat(exportResolutionsFor(CanvasSpec.LANDSCAPE_720))
            .containsExactly(CanvasSpec.LANDSCAPE_720, CanvasSpec.LANDSCAPE_1080)
            .inOrder()
    }

    @Test
    fun `a project starts on the size it already is`() {
        // The common case is "export what I have been looking at", so the sheet opens on the project's own
        // size and the other one is a single tap away.
        assertThat(defaultExportResolutionFor(CanvasSpec.PORTRAIT_720))
            .isEqualTo(CanvasSpec.PORTRAIT_720)
        assertThat(defaultExportResolutionFor(CanvasSpec.LANDSCAPE_1080))
            .isEqualTo(CanvasSpec.LANDSCAPE_1080)
    }
}
