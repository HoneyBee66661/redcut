package com.redcut.feature.export

import com.redcut.domain.document.CanvasSpec
import com.redcut.domain.render.ExportPresets
import com.redcut.domain.render.OutputSpec

/**
 * The two export sizes FR-5.1 allows — 1080p and 720p, and nothing else — as the sheet's choice.
 *
 * "Nothing else" is the requirement, not a starting point: the spec's non-goals list *"resolutions
 * beyond 720p/1080p"*, so an enum with a third entry is the sheet growing a bug. The geometry is
 * NOT stored here: a choice resolves through [ExportPresets] against the project's canvas at
 * compile time (FR-5.7), which is what makes a landscape project export landscape without this
 * screen ever learning what orientation means.
 */
enum class ExportResolution(val label: String) {
    HIGH_1080P("1080p"),
    STANDARD_720P("720p"),
    ;

    /**
     * The [OutputSpec] this choice compiles the graph at: a fixed FR-5.1 resolution in the canvas's
     * orientation, with FR-5.6's bitrates already on it.
     */
    fun preset(canvas: CanvasSpec): OutputSpec = when (this) {
        HIGH_1080P -> ExportPresets.at1080p(canvas)
        STANDARD_720P -> ExportPresets.at720p(canvas)
    }
}
