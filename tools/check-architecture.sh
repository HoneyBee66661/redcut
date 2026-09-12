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
#   §4.1 rule 2/3 Pure, feature, core and engine modules must not reach upward —
#                 dependencies point inward only. Cheap to state, easy to violate
#                 by accident (an import of a concrete engine type "just for now"),
#                 and expensive to unwind once three stages depend on it.
#
#   §4.1 rule 4   JNI is confined to :app and :engine:native. A second module
#                 calling System.loadLibrary is how a native crash turns into a
#                 mystery in a stack trace that names no C++ frame.
#
# WHY THIS IS A SCRIPT AND NOT A REVIEW CHECKLIST: these rules are individually
# cheap and collectively load-bearing, which is precisely the profile of a rule
# that gets skipped under deadline pressure (risk R11). A check that runs in
# `./gradlew check` catches the violation in the same commit that introduces it.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

# Modules that must stay pure Kotlin/JVM. No android.jar on the compile classpath.
PURE_MODULES=(domain/document domain/project domain/render core/common)

# Android modules with no render technology of their own.
CORE_MODULES=(core/media core/ui)

# Modules that own a rendering technology. Nothing above them may name them.
ENGINE_MODULES=(engine/media3 engine/native)

# The only two modules allowed to cross the JNI boundary (rule 4).
JNI_MODULES=(app engine/native)

# The one module allowed to speak Media3.
MEDIA3_OWNER="engine/media3"

violations=0

report() {
    # $1 = rule tag, $2 = file:line:text
    # Paths arrive from `grep -rn` on a relative path, so they carry a leading
    # ./. Strip it so a violation is reported against a path the reader can paste.
    local where="${2#"$ROOT"/}"
    where="${where#./}"
    printf '  [x] [%s] %s\n' "$1" "$where"
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
    module="${src_root#./}"
    module="${module%/src}"
    [ "$module" = "$MEDIA3_OWNER" ] && continue
    scan "rule-D1" 'import[[:space:]]+androidx\.media3' "$src_root"
done < <(find . -mindepth 3 -maxdepth 3 -type d -name src \
    -not -path './.git/*' -not -path './.worktrees/*' 2>/dev/null)

# The catalog itself must not hand Media3 to a module that should not have it:
# only :engine:media3 lists a media3 dependency in its own build file.
#
# The pattern deliberately matches a DEPENDENCY DECLARATION, not the bare word
# `media3`. KDoc in a feature build file ("... asks :engine:media3 for an
# export") is prose about the architecture, not a coupling to it — and a check
# that flags comments teaches people to delete comments.
#
# `project(":engine:media3")` is excluded too: that is a module edge, which rules
# 2/3 own. D1 is about Media3 *types* reaching a module that should not have the
# library on its classpath at all.
#
# The leading `(^|[{;[:space:]])` matters: Kotlin DSL puts a dependency on its own
# line, but `dependencies { implementation(...) }` is equally legal and a rule that
# can be sidestepped by reformatting the line is not a rule. Found by injecting the
# one-line form and watching the check pass.
MEDIA3_DEP_PATTERN='(^|[{;[:space:]])(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|androidTestImplementation|debugImplementation|ksp)[[:space:]]*\(.*(androidx\.media3|libs\.androidx\.media3|libs\.media3)'

for build_file in $(find . -mindepth 2 -maxdepth 3 -name build.gradle.kts \
    -not -path './build-logic/*' -not -path './.git/*' -not -path './.worktrees/*' 2>/dev/null); do
    # `find .` yields paths with a leading ./, so normalise before comparing:
    # without this the owner module does not match its own case and every
    # legitimate Media3 dependency in :engine:media3 is reported as a violation.
    rel="${build_file#./}"
    case "$rel" in
        "$MEDIA3_OWNER"/*) continue ;;
    esac
    scan "rule-D1" "$MEDIA3_DEP_PATTERN" "$build_file"
done

# --- Rule 1, transitively: purity is a property of the classpath ------------
echo "  Rule 1 (transitive): no pure module may depend on an Android module"
# The two checks above only see what a module WRITES. A module whose sources and
# build file are spotless still gets android.jar on its compile classpath if it
# depends on :core:ui or :core:media — and would pass both checks while quietly
# ceasing to be testable on a bare JVM. This is the one hole a per-module check
# cannot close on its own.
for module in "${PURE_MODULES[@]}"; do
    [ -d "$module" ] || continue
    scan "rule-1" 'project\(":core:(media|ui)"' "$module/build.gradle.kts"
done

# --- Rules 2 & 3: dependencies point inward --------------------------------
echo "  Rules 2/3: no outward dependencies from pure/feature/core modules"
for module in "${PURE_MODULES[@]}" feature/* "${CORE_MODULES[@]}"; do
    [ -d "$module" ] || continue
    scan "rule-2" 'project\(":(engine|feature):' "$module/build.gradle.kts"
done

echo "  Rule 3: no engine may depend on a feature"
for module in "${ENGINE_MODULES[@]}"; do
    [ -d "$module" ] || continue
    scan "rule-3" 'project\(":feature:' "$module/build.gradle.kts"
done

# :app is the composition root: it is depended UPON by nothing. An edge into it
# means some module is reaching past its own layer for the whole graph.
#
# :benchmark is the one module that points at :app, and it does so as
# `targetProjectPath = ":app"` — a string AGP reads, not a Gradle project edge. That
# distinction is deliberately left to this scan: if someone ever writes
# `project(":app")` in :benchmark, it becomes a dependency of a test module on the
# product graph for no reason, and it should fail here like anywhere else. The profile
# generator's instrumentation edge is test-only and packaged into nothing, which is why
# the form that exists is not this one.
echo "  Rule 4: nothing depends on :app"
while IFS= read -r build_file; do
    rel="${build_file#./}"
    case "$rel" in app/*) continue ;; esac
    scan "rule-4" 'project\(":app"\)' "$build_file"
done < <(find . -mindepth 2 -maxdepth 3 -name build.gradle.kts \
    -not -path './build-logic/*' -not -path './.git/*' -not -path './.worktrees/*' 2>/dev/null)

# JNI, same walk as rule D1 above so it cannot drift from how modules are found.
echo "  Rule 4: JNI stays in :${JNI_MODULES[0]} and :${JNI_MODULES[1]}"
while IFS= read -r src_root; do
    module="${src_root#./}"
    module="${module%/src}"
    case " ${JNI_MODULES[*]} " in
        *" $module "*) continue ;;
    esac
    scan "rule-4" 'System\.loadLibrary|external[[:space:]]+fun' "$src_root"
done < <(find . -mindepth 3 -maxdepth 3 -type d -name src \
    -not -path './.git/*' -not -path './.worktrees/*' 2>/dev/null)

echo "====================================================================="
if [ "$violations" -gt 0 ]; then
    printf 'FAILED - %d violation(s).\n\n' "$violations"
    echo "If a violation looks intentional, it is almost certainly not: the rules above"
    echo "exist to preserve the fast test tier and the deferred-native-core plan."
    echo "Re-read docs/VIDEO_EDITOR_MVP_SPEC.md §4.1 and §6.8 before changing this script."
    exit 1
fi

printf 'PASSED - boundaries intact.\n'
