# :redcut_core — the C++ core's build.
#
# Phase 0.4 builds the SEAM, not the core: one library, two functions, and the
# build correctness that is expensive to discover late (page alignment, warning
# discipline, symbolication). The render/audio/analysis trees in spec §6.5 land in
# Phase 5, behind this same library name — so the flags below are the ones the real
# core will be compiled with, exercised from the first day they can be.
cmake_minimum_required(VERSION 3.22)
project(redcut_core CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(redcut_core SHARED
    jni/JniOnLoad.cpp
    util/seam.cpp)

target_include_directories(redcut_core PRIVATE include)

# -Werror from the first line of C++ ever written here, rather than from the first
# line somebody remembers to add it: a native warning that is tolerated for a year
# is a native warning nobody can turn on later.
#
# -fno-exceptions / -fno-rtti: smaller and faster, and it forces error handling to
# be explicit (spec §6.7 — C++ never throws; functions return status values). Both
# are asserted by compiling the seam with them, which is the only way they get
# tested before the core exists.
#
# -ffast-math is deliberately NOT here yet: it is safe for the graphics/audio math
# in spec §6.5 and meaningless for the two functions below, and a flag whose
# absence nothing can observe is a flag that would rot. It arrives with the code
# that depends on it.
target_compile_options(redcut_core PRIVATE
    -Wall -Wextra -Werror
    -fno-exceptions -fno-rtti)

find_library(log-lib log)
target_link_libraries(redcut_core ${log-lib} android)

# 16 KB page size alignment — REQUIRED for Android 15+ devices (spec §6.5, risk R5).
# A .so built without this fails to LOAD on those devices: not a crash, a load
# error, which is the kind of shipping blocker that is invisible on every older
# device you own. CI asserts the resulting LOAD segment alignment is 0x4000.
target_link_options(redcut_core PRIVATE "-Wl,-z,max-page-size=16384")
