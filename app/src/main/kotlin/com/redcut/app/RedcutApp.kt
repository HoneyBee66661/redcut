package com.redcut.app

import android.app.Application

/**
 * Process-wide entry point.
 *
 * Bare on purpose. Hilt's `@HiltAndroidApp` and the Timber tree land with Phase
 * 0.5, which is the point at which there is a dependency graph and a logging
 * target to attach them to. A class here now buys something concrete in the
 * meantime: the manifest reference, the application id and the process lifecycle
 * are exercised by the first CI `assembleDebug`, so Phase 0.5 is an edit to a
 * file that already exists rather than the introduction of the app shell.
 */
class RedcutApp : Application()
