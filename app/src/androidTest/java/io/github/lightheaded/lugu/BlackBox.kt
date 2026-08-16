package io.github.lightheaded.lugu

/**
 * Marks a test that never names one of lugu's own classes, and can therefore run against
 * the shrunk build as well as the debug one.
 *
 * The distinction is not stylistic. The androidTest APK is compiled separately from the
 * app and loaded into its process, so every name it uses has to still exist in the app
 * under that name. On the `minified` build type lugu's own classes are renamed — that is
 * the point of the build type — so a test that constructs a `LuguDatabase` or parses a
 * `BrowseNode` cannot resolve it and fails before it asserts anything. Those tests are not
 * worse; they simply belong on `debug`, where nothing is renamed.
 *
 * What is left for the shrunk build is the black box: launch the app, drive it through the
 * surfaces anything else on the device would use, and look at what comes back. That is a
 * narrow set, and it is exactly the set that answers the question the build type exists
 * for — does the app R8 produced still run at all.
 *
 * CI selects on this annotation for the minified leg. Adding it to a test that reaches
 * into lugu's code will not fail the build here; it will fail on the device, with a
 * `NoClassDefFoundError` naming the class you reached for.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class BlackBox
