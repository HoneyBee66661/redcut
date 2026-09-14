#!/usr/bin/env python3
#
# tools/check-test-names.py — the check that would have caught the two test names
# that have now cost this repo two separate CI round trips.
#
# WHY THIS EXISTS
#
# A backticked Kotlin function name is copied VERBATIM into the JVM method name, and
# the JVM's class-file format reserves punctuation there: `.` is the package
# separator, `;` `[` `/` `<` `>` are the array, generic and descriptor markers, and
# Kotlin refuses `:` and `\` on top of those. So `fun \`... is 33.3667 seconds\`` is
# not a style problem, it is a COMPILE error, and the gate dies before a single test
# runs:
#
#   core/common/.../TimebaseTest.kt:88:9 Name contains illegal characters: .
#
# The trap is that nothing else in the loop can see it. ktlint and detekt parse the
# name as an opaque string and pass; the pure-JVM tier never starts, because the
# module does not compile; and the only feedback is a CI run minutes later, on a rule
# that is the Kotlin compiler's rather than Android's — so it is equally invisible on
# a host WITH a full toolchain. Two instances have already done exactly that:
#
#   * `at 30000 over 1001 fps, 1000 frames is 33.3667 seconds`  — the dot in 33.3667
#   * `an hour of 23.976 reads as an hour of timecode, ...`     — the dot in 23.976
#
# The general lesson ("do not put punctuation in a test name") has no cheap fix. This
# specific one does: the reserved set is fixed, the name sits on one line, and a file
# that fails this check can never compile. That is a grep, so it belongs in the gate,
# beside the other checks that catch what only CI would otherwise report.
#
# Scans the working tree rather than `git ls-files`, so a file is checked while it is
# still untracked — which is when the mistake is actually made. A worktree checked out
# inside the repo (`./.worktrees/<task>`) is a second copy of every source file, so it
# is skipped for the same reason `.git` is.
#
# Exit code 0 when every backticked name is a legal JVM method name. No JDK, no
# network, no Android SDK — python3 and a regex.
import os
import re
import sys

# Reserved in a JVM method name. `.` `;` `[` `/` `<` `>` are the class-file format's
# own separators and markers; Kotlin rejects `:` and `\` as well.
ILLEGAL = ".;[]/<>:\\"

# Directories holding either build output or a second copy of the tree, never source.
SKIP_DIRS = {"build", ".git", ".gradle", ".worktrees"}

# `fun \`name\`` — the name cannot itself contain a backtick, so `[^`]*` is exact.
BACKTICKED_FUN = re.compile(r"\bfun\s+`([^`]*)`")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def kotlin_files():
    """Every *.kt file under the repo root, sorted, build output excluded."""
    for dirpath, dirnames, filenames in os.walk(ROOT):
        # In place, so os.walk does not descend into them; sorted, for stable output.
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for filename in sorted(filenames):
            if filename.endswith(".kt"):
                yield os.path.join(dirpath, filename)


def main():
    checked = 0
    failing = 0

    for path in kotlin_files():
        rel = os.path.relpath(path, ROOT)
        try:
            with open(path, encoding="utf-8") as handle:
                lines = handle.readlines()
        except OSError as error:
            print(f"  cannot read {rel}: {error}", file=sys.stderr)
            continue

        for lineno, line in enumerate(lines, 1):
            for name in BACKTICKED_FUN.findall(line):
                checked += 1
                # Each offending character is its own finding, because the compiler
                # names one at a time; ILLEGAL's order keeps the output deterministic.
                bad = [char for char in ILLEGAL if char in name]
                if not bad:
                    continue
                failing += 1
                for char in bad:
                    print(f"{rel}:{lineno}: illegal character '{char}' in the test name: {name}")

    if failing:
        print("")
        print("  Fix: the name becomes the JVM method name verbatim, so spell the")
        print("  punctuation out in words — `33.3667` becomes `33 seconds and a third`,")
        print("  `23.976` becomes `the film rate`. A name that fails here cannot compile.")
        print(f"FAILED — {failing} name(s) cannot compile")
        return 1

    print(f"OK — {checked} backticked names checked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
