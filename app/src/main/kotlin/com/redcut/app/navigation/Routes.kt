package com.redcut.app.navigation

/**
 * The app's routes (spec §7.1).
 *
 * String routes rather than Navigation's type-safe destinations, deliberately and
 * temporarily: the stages are NOT routes (spec §7.2 — the stage is UI state, so
 * switching stages does not navigate), which leaves four destinations that take no
 * arguments. Type-safe navigation earns its annotation processing when routes start
 * carrying project ids; the moment one does, this becomes the wrong file for it and the
 * migration is four lines.
 */
internal object Routes {
    const val HOME = "home"
    const val EDITOR = "editor"
    const val EXPORT = "export"
    const val SETTINGS = "settings"
}
