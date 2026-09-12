# :app R8 rules.
#
# Empty by design, not by omission. The release build type enables minify +
# resource shrinking (spec NFR-7: APK under 25 MB), and R8 without rules is a
# supported configuration — everything the app needs is discovered from the
# classes it keeps. Rules get added when a *specific* shrink-induced break is
# observed (reflection, JNI, serialization), never preemptively: a keep rule
# nobody justified is how release builds quietly stop shrinking.
#
# The first ones expected here are for the JNI bridge (Phase 3.2) and possibly
# kotlinx.serialization's generated serializers used from :domain:project (4.7).
