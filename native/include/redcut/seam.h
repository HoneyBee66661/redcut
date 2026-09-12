// The seam between Kotlin and C++ (spec §6.5).
//
// Right now this is a proof, not a core: `add` and `kVersionCode` exist to force
// every load-bearing part of the JNI boundary to be exercised early — explicit
// RegisterNatives, a version handshake that fails loudly instead of at first use,
// and a build that produces a 16 KB-aligned library. Phase 5 replaces the bodies
// and keeps the contract.
#pragma once

namespace redcut::seam {

/// The native ABI version.
///
/// Bumped whenever a registered native signature changes (added, removed, or
/// re-typed). Kotlin compares it against its own expectation at startup, so a
/// stale .so in an APK fails on launch with a message naming both versions,
/// instead of throwing NoSuchMethodError from inside a frame callback.
///
/// This is versioned separately from the project: the Kotlin and C++ halves of the
/// boundary can legitimately drift within one APK (a stale build artifact, a
/// missing `clean`, a bad merge of generated output), and that drift is invisible
/// to every other check the build performs.
inline constexpr int kVersionCode = 1;

/// The seam proof. Phase 5 receives real work here.
[[nodiscard]] int add(int a, int b) noexcept;

}  // namespace redcut::seam
