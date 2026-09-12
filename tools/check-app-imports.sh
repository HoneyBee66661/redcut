#!/usr/bin/env bash
#
# tools/check-app-imports.sh — the check that would have caught the Phase 0.5 break
# before the push.
#
# WHY THIS EXISTS
#
# `BuildConfig` is GENERATED, and it is generated into the module's own package:
# `:app`'s lives at `com.redcut.app.BuildConfig`. A file in `com.redcut.app.logging`
# therefore cannot see it without an explicit import — and nothing on the development
# host catches that, because there is no Android SDK here, so `:app` is never compiled
# locally. The local gate was green, the push went out, and CI's `build` job failed on
# `TimberLogging.kt:53: Unresolved reference 'BuildConfig'` — a one-line bug that cost a
# full CI round trip.
#
# The general lesson does not have a cheap fix (an "Android tier compile" is an SDK this
# host does not have). This specific lesson does: a bare `BuildConfig` identifier in a
# file that is neither in the generating package nor importing it is ALWAYS a compile
# error in CI and NEVER a compile error here. That is a grep, so it belongs in the gate.
#
# Scans the working tree, not `git ls-files`, so a file is checked while it is still
# untracked — which is when the mistake is actually made.
#
# Exit code 0 when every reference is qualified (`com.redcut.app.BuildConfig`), imported,
# or in the generating package. No JDK, no network, no Android SDK.
set -euo pipefail

cd "$(dirname "$0")/.."

# The package `:app`'s BuildConfig is generated into. If a second module ever turns
# BuildConfig generation on, add its package here rather than loosening the check.
GENERATED_PACKAGE="com.redcut.app"

status=0
while IFS= read -r file; do
    # A bare `BuildConfig`, i.e. not preceded by a dot (which would make it part of a
    # qualified `com.redcut.app.BuildConfig`) and not followed by more name characters.
    if ! grep -qE '(^|[^.A-Za-z0-9_])BuildConfig([^A-Za-z0-9_]|$)' "$file"; then
        continue
    fi

    if grep -qE "^import +${GENERATED_PACKAGE}\.BuildConfig([^A-Za-z0-9_]|$)" "$file"; then
        continue
    fi

    if grep -qE "^package +${GENERATED_PACKAGE}[[:space:]]*$" "$file"; then
        continue
    fi

    echo "  $file: uses BuildConfig without importing ${GENERATED_PACKAGE}.BuildConfig"
    grep -nE '(^|[^.A-Za-z0-9_])BuildConfig([^A-Za-z0-9_]|$)' "$file" | sed 's/^/    /'
    status=1
done < <(find app core engine feature -name '*.kt' -type f 2>/dev/null | sort)

if [ "$status" -ne 0 ]; then
    echo ""
    echo "  Fix: add 'import ${GENERATED_PACKAGE}.BuildConfig'."
    echo "  BuildConfig is generated into the module's own package. Android Studio adds"
    echo "  this import on demand, and this host has no Android SDK to notice it missing."
fi

exit "$status"
