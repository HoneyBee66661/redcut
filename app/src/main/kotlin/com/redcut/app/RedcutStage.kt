package com.redcut.app

/**
 * The three stages of the product (spec §4.2, Phase 0 exit criterion).
 *
 * Its own file rather than a companion to [StageShell] because detekt's
 * MatchingDeclarationName rule is right about this: a file that declares exactly
 * one type should be named after it, and a shell composable is not a stage model.
 */
internal enum class RedcutStage(
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
