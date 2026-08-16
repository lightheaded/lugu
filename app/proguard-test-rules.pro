# R8 rules for the *instrumented test* APK, not for lugu.
#
# When the build type under test has minification on, AGP shrinks the androidTest APK as
# well. That is not what the `minified` build type is for. The point of it is to run the
# suite against the app exactly as R8 leaves it — what the test harness is compiled to is
# nobody's concern, and shrinking it tests androidx.test rather than lugu.
#
# Leaving it on is not merely pointless, it is actively wrong. The first run of the
# minified leg died before a single test loaded:
#
#   java.lang.NoClassDefFoundError: Failed resolution of: Landroidx/tracing/Trace;
#       at androidx.test.runner.AndroidJUnitRunner.onCreate(r8-map-id-...)
#   Caused by: java.lang.ClassNotFoundException: androidx.tracing.Trace
#
# `AndroidJUnitRunner` reaches for `androidx.tracing.Trace` in `onCreate`, R8 could not see
# that reference from the test APK's entry points, and removed it — so the instrumentation
# process crashed at bind time and the run reported "Starting 0 tests" rather than an error
# anyone could act on.
#
# Keeping specific classes would fix that one crash and leave the next one waiting: every
# class the runner reaches reflectively is the same bug, and the list is androidx.test's to
# know, not ours. So the harness is left alone entirely.
-dontshrink
-dontoptimize
-dontobfuscate
