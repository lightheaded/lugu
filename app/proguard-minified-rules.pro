# Extra rules for the `minified` build type only. Never applied to release.
#
# `minified` exists to run instrumented tests against the app as R8 leaves it, because
# until it existed R8 had been on for a day and had executed nowhere. This file is the
# price of that, and it is worth stating exactly what it costs.
#
# ## Why any of this is needed
#
# The androidTest APK is compiled separately, against unobfuscated names, and then loaded
# into the app's process. Anything it references has to still be there, under that name.
# R8 shrinking the app breaks that, one class at a time, and each fix reveals the next:
#
#   androidx.tracing.Trace       AndroidJUnitRunner.onCreate — died before any test loaded
#   kotlin.Lazy / kotlin.LazyKt  renamed and inlined out of the app; the tests write `by lazy`
#   androidx.media3.session.*    referenced by the browse-tree tests
#   androidx.compose.ui.platform.InfiniteAnimationPolicy   Compose's test integration
#
# Each was measured on a device, not guessed. Chasing them individually is unbounded — the
# list is every library any test touches, forever — so the rule is stated once instead.
#
# ## What is kept, and what is still tested
#
# Everything **outside** lugu's own package keeps its name. lugu's own code is still
# shrunk, optimised and obfuscated exactly as release does it — and that is where the risk
# named in the backlog actually lives: Room's generated DAOs, Hilt's component graph,
# reflectively resolved serializers, and a media service the system binds by name are all
# lugu's classes, not library classes.
#
# So this leg proves: **lugu's own code survives R8.** `LaunchSmokeTest` passing here is
# that claim — the app launches, Hilt builds its graph, WorkManager's initialiser is
# reached, and the signed-out screen renders, all under R8.
#
# What it does not prove: that shrinking third-party libraries is safe. Release still
# shrinks them and nothing here checks that. That gap is real, and it is the unavoidable
# price of instrumenting a minified build at all.
#
# ## Which tests can run here
#
# Only the ones that treat lugu as a black box — see the `BlackBox` annotation. A test that
# names a lugu class (`LuguDatabase`, `BrowseNode`) cannot resolve it, because the app
# renamed it and the separately-compiled test APK still asks for the old name. Those tests
# run on `debug`, where nothing is renamed, and that is the right place for them.
-keep class !io.github.lightheaded.lugu.** { *; }

# Keeping the libraries makes R8 resolve references it previously shrank past — Tink's
# optional Google-HTTP-client paths, and others like them. They are absent at runtime in
# release too and nothing calls them; release simply never had to look.
-dontwarn **
