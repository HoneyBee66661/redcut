package com.redcut.feature.editor

/**
 * The three stages of the product (spec §4.2, §7.1).
 *
 * A UI concept and nothing more — spec §1.1 is explicit that "stage boundaries are a UI
 * concern, not a data concern", which is why this lives in the feature that draws the
 * stage bar rather than in `:domain:document`. Adding a fourth stage is a UI change:
 * no schema migration, no document revision, no recompile.
 *
 * [detail] is the placeholder body text each stage shows until the phase named in it
 * lands. It is deliberately data on the enum rather than a `when` in the screen: the
 * screen then has no per-stage branch to forget to update, and deleting this field when
 * the stages have real bodies is a compile error at exactly one place.
 */
enum class Stage(
    val label: String,
    val detail: String,
) {
    Cut(
        label = "Cut",
        detail = "Import, trim, split, reorder.\n" +
            "The timeline canvas lands in Phase 1.5; trim and split gestures in 1.6-1.9.",
    ),
    Edit(
        label = "Edit",
        detail = "Speed, volume, fades, rotate, crop, reverse.\n" +
            "The inspector framework lands in Phase 2.1; the tools in 2.2-2.5.",
    ),
    Effect(
        label = "Effect",
        detail = "LUTs, colour, text, image overlays, cross-dissolve.\n" +
            "The native GL effect pipeline lands in Phase 3.1-3.9.",
    ),
}
