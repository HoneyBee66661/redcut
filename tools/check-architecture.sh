#!/usr/bin/env bash
#
# Architecture boundary check — the machine-enforced half of the design.
#
# Rules enforced here (see docs/VIDEO_EDITOR_MVP_SPEC.md):
#
#   §4.1 rule 1   :domain:* and :core:common must have ZERO Android dependencies —
#                 not even android.net.Uri. This is what makes the timeline
#                 semantics testable in milliseconds (§12.1) and is the single
#                 highest-leverage constraint in the design.
#
#   §6.8 rule D1  No Media3 type (Composition, EditedMediaItem, GlEffect, ...) may
#                 appear outside :engine:media3. This is what keeps the deferred
#                 C++ render core a swap rather than a rewrite (§13.2).
#
#   §4.1 rule 2/3 Pure and feature modules must not reach into :engine:* or
#                 :feature:* — dependencies point inward only.
#
# WHY THIS IS A SCRIPT AND NOT A REVIEW CHECKLIST: these rules are individually
# cheap and collectively load-bearing, which is precisely the profile of a rule
# that gets skipped under deadline pressure (risk R11). A check that runs in
# `./gradlew check` catches the violation in the same commit that introduces it.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

# Modules that must stay pure Kotlin/JVM. No android.jar on the compile classpath.
PURE_MODULES=(domain/document domain/render core/common)

# The one module allowed to speak Media3.
MEDIA3_OWNER="engine/media3"

violations=0

report() {
    # $1 = rule tag, $2 = file:line:text
    printf '  [x] [%s] %s\n' "$1" "$2"
    violations=$((violations + 1))
}

scan() {
    # $1 = rule tag, $2 = regex, $3 = path
    local tag="$1" pattern="$2" path="$3"
    [ -e "$path" ] || return 0
    local hit
    while IFS= read -r hit; do
        [ -n "$hit" ] || continue
        report "$tag" "${hit#"$ROOT"/}"
    done < <(grep -rnE "$pattern" "$path" 2>/dev/null || true)
}

echo "== Architecture boundaries ==========================================="

# --- Rule 1: domain purity -------------------------------------------------
echo "  Rule 1: :domain:* and :core:common are pure JVM"
for module in "${PURE_MODULES[@]}"; do
    [ -d "$module" ] || continue

    # 1a. Source-level imports.
    scan "rule-1" '^[[:space:]]*import[[:space:]]+(android|androidx)\.' "$module/src"

    # 1b. Build-file level. An Android plugin or an androidx dependency puts
    #     android.jar on the compile classpath even when the sources look clean,
    #     so scanning sources alone would give false confidence.
    scan "rule-1" 'com\.android|androidx\.|redcut\.android' "$module/build.gradle.kts"
done

# --- Rule D1: Media3 isolation --------------------------------------------
echo "  Rule D1: no Media3 outside :$MEDIA3_OWNER"
# A git worktree checked out inside the repo (`./.worktrees/<task>`, what agent
# orchestration tools create by default) is a second copy of every source file, so
# scanning it double-counts and reports the COPY's files as violations of rules the
# real sources satisfy. Excluded here for the same reason `.git` is.
while IFS= read -r src_root; do
    module="${src_root#"$ROOT"/}"
    module="${module%/src/*}"
    [ "$module" = "$MEDIA3_OWNER" ] && continue
    scan "rule-D1" 'import[[:space:]]+androidx\.media3' "$src_root"
done < <(find . -mindepth 3 -maxdepth 3 -type d -name src \
    -not -path './.git/*' -not -path './.worktrees/*' 2>/dev/null)

# The catalog itself must not hand Media3 to a module that should not have it:
# only :engine:media3 lists a media3 dependency in its own build file.
for build_file in $(find . -mindepth 2 -maxdepth 3 -name build.gradle.kts \
    -not -path './build-logic/*' -not -path './.git/*' -not -path './.worktrees/*' 2>/dev/null); do
    rel="${build_file#"$ROOT"/}"
    case "$rel" in
        "$MEDIA3_OWNER"/*) continue ;;
    esac
    scan "rule-D1" 'media3' "$build_file"
done

# --- Rules 2 & 3: dependencies point inward --------------------------------
echo "  Rules 2/3: no outward dependencies from pure/feature modules"
for module in "${PURE_MODULES[@]}" feature/*; do
    [ -d "$module" ] || continue
    scan "rule-2" 'project\(":(engine|feature):' "$module/build.gradle.kts"
done

echo "====================================================================="
if [ "$violations" -gt 0 ]; then
    printf 'FAILED - %d violation(s).\n\n' "$violations"
    echo "If a violation looks intentional, it is almost certainly not: the rules above"
    echo "exist to preserve the fast test tier and the deferred-native-core plan."
    echo "Re-read docs/VIDEO_EDITOR_MVP_SPEC.md §4.1 and §6.8 before changing this script."
    exit 1
fi

printf 'PASSED - boundaries intact.\n'
