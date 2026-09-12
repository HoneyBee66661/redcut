#!/usr/bin/env bash
#
# tools/verify.sh — the local gate.
#
# Runs what CI runs, in the same order, so "green here" and "green in CI" mean the
# same thing:
#
#   1. tools/check-architecture.sh   no JDK, no network, fails in seconds
#   2. the pure-JVM test tier        :domain:* — the fast tier of spec §12.1
#   3. the fast-tier budget          the tier is only useful while it stays FAST
#
# Usage:
#   ./tools/verify.sh                             # warnings are warnings
#   ./tools/verify.sh -Predcut.warningsAsErrors   # exactly what CI adds
#
# Everything passed in is forwarded to Gradle, which is how CI can add its flags
# without a second copy of this script. Exit code is 0 only when all three pass.
#
# WHY THIS EXISTS
#
# The two jobs in .github/workflows/ci.yml are the project's real verification loop
# (§12.1), and a developer who cannot reproduce them locally pushes and waits instead —
# which is how a fast tier stops being run on every save, and how the checks that
# protect §4.1 (pure domain) and §6.8 (no Media3 outside :engine:media3) get skipped.
# Each of the three steps below closes a specific trap that has already bitten once:
#
#   * A bare `./gradlew <task>` failed at configuration because `allWarningsAsErrors`
#     was set from an absent provider. Fixed in build-logic; this script is the
#     loop that would have caught it.
#   * CI ran :domain:document:test only, so a green run said nothing about
#     :domain:render — the module holding every framing, timing and ordering rule.
#     Both tiers are listed here and in CI; add :core:common when it exists.
#   * The 15 s budget existed only in CI, so nothing stopped the "fast" tier from
#     quietly becoming slow on the machine where it is supposed to run most often.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

# Keep in step with the `domain-tests` job in .github/workflows/ci.yml.
PURE_TIER_TASKS=(:domain:document:test :domain:render:test :core:common:test)

# §12.1's target is 5 s; this is the ceiling CI enforces on the same numbers, and it
# is a floor on usefulness rather than a target. Raise it only with a measurement in
# the PR body explaining what got slower — a budget nobody measures is decoration.
# Overridable so the failure path itself can be exercised (see tools/README or the
# PR that added this script): REDCUT_FAST_TIER_BUDGET_S=0.1 ./tools/verify.sh
FAST_TIER_BUDGET_S="${REDCUT_FAST_TIER_BUDGET_S:-15}"

if ! command -v java >/dev/null 2>&1; then
    echo "java is not on PATH. The project builds with JDK 17:" >&2
    echo "  export JAVA_HOME=/path/to/jdk-17" >&2
    echo "  export PATH=\"\$JAVA_HOME/bin:\$PATH\"" >&2
    exit 2
fi

echo "== Architecture boundaries ==========================================="
bash tools/check-architecture.sh

echo
echo "== Pure-JVM test tier: ${PURE_TIER_TASKS[*]}"
./gradlew "${PURE_TIER_TASKS[@]}" --console=plain "$@"

echo
echo "== Fast tier budget (<= ${FAST_TIER_BUDGET_S}s of test time) ==================="
# Sums the JUnit `time` attribute of each test SUITE — `-m1` takes only the first
# match, which is the `<testsuite time=...>` element. Taking every match instead would
# add each suite's per-case times to the suite total and roughly double the number,
# which silently makes any budget ~2x too lenient. CI measures the same way.
total=$(for f in $(find . -path '*/build/test-results/*' -name '*.xml' 2>/dev/null); do
    grep -om1 'time="[0-9.]*"' "$f" 2>/dev/null || true
done | sed 's/time="//;s/"//' | awk '{s+=$1} END {printf "%.1f", s}')
total="${total:-0.0}"
echo "  test wall time: ${total}s"

if awk -v t="$total" -v b="$FAST_TIER_BUDGET_S" 'BEGIN { exit (t > b) ? 1 : 0 }'; then
    echo
    echo "PASSED - boundaries intact, pure tier green, fast tier within budget."
else
    echo
    echo "FAILED - the fast tier took ${total}s, over the ${FAST_TIER_BUDGET_S}s budget (spec §12.1)." >&2
    echo "The tier is only worth running on every save while it is fast. Look for I/O," >&2
    echo "a clock, a sleep, or work that belongs in the instrumented tier instead." >&2
    exit 1
fi
