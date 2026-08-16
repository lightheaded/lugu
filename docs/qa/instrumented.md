# Instrumented tests

These are the only tests that run on Android. Everything else in the repository runs on the
JVM, which means everything else stops at the point where the interesting failures start:
processes being killed, media buttons arriving at nothing, and SQLite being a different
build from the one the JVM links.

## Running them

```
./gradlew connectedDebugAndroidTest
```

Needs an emulator or a device attached. CI runs the same command on API 26 and API 36.

It is asked of the whole build rather than of the two modules that have these tests, so a
module that grows some later is picked up without anyone remembering to edit the workflow.
The root build script switches the instrumented-test APK off for modules with no
`src/androidTest`: without that, an empty module builds a test APK with no test runner in
it, and the run dies on the emulator with `ClassNotFoundException:
androidx.test.runner.AndroidJUnitRunner`, reported as "Instrumentation run failed due to
Process crashed". Adding a `src/androidTest` is all it takes to opt a module back in.

`:harness` is the exception to "the module installs what it tests", because it tests another
module: it depends on `:app:installDebug`, and orders that install *after* `:app`'s own
connected tests, which uninstall lugu when they finish. Both rules are in
`harness/build.gradle.kts`, so the command above is still the whole story.

## What runs without a server

| Test | Needs a server |
| --- | --- |
| `LaunchSmokeTest` | No |
| `core:db` `DeviceMigrationTest` | No |
| `PlaybackResumptionTest` | Yes |
| `harness` `the_harness_outlives_a_force_stop_of_lugu` | No |
| `harness` `..._after_the_process_is_killed` | Yes |
| `harness` `a_force_stop_never_resumes_the_wrong_thing` | Yes |

The two that need nothing are not filler. `LaunchSmokeTest` catches the three ways lugu can
fail to start that no unit test sees — Hilt's graph failing to build, WorkManager's
initialiser being removed without Hilt's replacement taking over, and R8 having stripped
something reached only reflectively. `DeviceMigrationTest` runs the whole 1→5 migration
chain against Android's own SQLite, which is the only place a migration is actually proved:
a migration that fails there is an app that will not open after an update.

`PlaybackResumptionTest` needs a server because a saved position only exists once something
has played. Without one it **skips** rather than fails, so a CI emulator with no server
stays green.

## Pointing the tests at a server

Nothing is committed. The tests read `BuildConfig` fields that come from the gitignored
`local.properties` — the same file that prefills the login screen on a debug build.

```properties
lugu.dev.serverUrl=...
lugu.dev.user=...
lugu.dev.pass=...
lugu.test.playQuery=...
```

`lugu.test.playQuery` is the title the playback tests ask the library for. It is separate
from the credentials on purpose: having a server is not the same as agreeing that a test may
play something on it, and whoever sets this chooses what.

Never put any of these anywhere else. Not in a workflow file, not in a comment, not in a
commit message.

## The kill, and the second process that does it

The plan asks for the app to be killed mid-book, then for play to be pressed on a headset.
`PlaybackResumptionTest` cannot do the first half and never could: instrumentation runs
**inside** the process under test, so `am force-stop` on this package would take the test
runner with it. What it does instead is destroy the playback service, which is what Android
does when it reclaims memory, and then reach the resumption path from Room exactly as a cold
start would. Same code under test; not the same kill.

`:harness` is the second process. It is an application module with its own application id
(`io.github.lightheaded.lugu.harness`) whose instrumented tests **target itself**, so ending
lugu's process leaves the runner standing. It depends on nothing of lugu's — not `:app`, not
`:playback`, not a shared constant — and talks to it only through surfaces that already
belong to other people: the launcher, the documented automation broadcasts, a media button,
and `dumpsys media_session`. A harness that imported the code under test could pass while
the thing a headset does still failed.

One run looks like this: open lugu, tap the prefilled sign-in button, ask for the title in
`lugu.test.playQuery` with a `PLAY_SEARCH` broadcast, skip ten minutes in, set a speed the
book was not already on, end the process, `input keyevent 126`, and read the session back.

### Two kills, and only one of them is the promise

`am force-stop` is not what happens when Android reclaims memory from a book that is
playing. It additionally puts the package into the **stopped state**, and the platform's rule
is that only a person launching the app takes it out again — on Android 15 and later the
system also [cancels every pending intent the app
owns](https://developer.android.com/about/versions/15/behavior-changes-all) the moment it
enters that state, the one the media session gave the system to receive media buttons
included. A media button that does nothing after a force stop is therefore the system
working as designed, and no amount of correctness in lugu changes it.

So the harness kills two ways:

- **The process dies** — `run-as … kill -9`, falling back to `am crash`. The package's state
  is untouched, and the media button is expected to bring the book back. Asserted strictly:
  same item, within tolerance of the same position, at the same speed.
- **`am force-stop`** — asserted narrowly, because the platform may legitimately refuse to
  wake anything. What would be lugu's fault is coming back *wrong*, so that is what is
  checked on whatever does come back, and nothing coming back is logged rather than failed.

### Why `dumpsys media_session` and not a `MediaController`

A controller would give typed values instead of parsed text. It would also *bind the
service*, which starts lugu's process — so a media button that did nothing at all would be
followed by a controller quietly bringing the app back, and the test would pass having
proved the opposite of what it claims. Reading the system's own record touches nothing. It
is also the channel the recipe below uses, so a failure can be reproduced by hand.

Two things the parser has to get right, both found by running it: Android 16 writes
`state=PLAYING(3)` where older releases write `state=3`, and the published position is a
stamp with a time on it rather than the position now, so it is extrapolated the way the
platform's own clients extrapolate it. Comparing raw stamps instead lets a reading that is
thirty seconds stale look like a resumption that jumped thirty seconds forward.

### What the harness never learns

The server address, the username and the password do not cross into it. Its `BuildConfig`
carries a boolean saying whether all three are set, and the title to play — nothing else.
lugu signs itself in from its own `BuildConfig`, and the harness taps the button that is
already filled in. Item titles read back out of `dumpsys` are compared as short digests and
never printed, because a failure message ends up in a CI log and nothing in this repository
may name what is on somebody's shelf.

### Doing it by hand

```sh
# with something playing
adb shell 'run-as io.github.lightheaded.lugu.debug /system/bin/kill -9 $(pidof io.github.lightheaded.lugu.debug)'
# then press play on a headset, or:
adb shell input keyevent 126
adb shell dumpsys media_session | grep -A12 'package=io.github.lightheaded.lugu.debug'
```

Playback should resume the same item within a few seconds of where it stopped, at the speed
it was on. If it starts from zero, or starts something else, that is the bug the whole app
exists to avoid.

`am force-stop` used to be the first line here, and it is the wrong instruction for this
check: it tests the platform's stopped state rather than lugu's resumption. Use it to prove
the process really is gone, not to expect a media button to bring it back.

### One thing worth checking before trusting a green run

With lugu open and a session held, the platform records what it should send a media button
to when the app is gone:

```sh
adb shell dumpsys media_session | grep -E 'mediaButtonReceiver|Last MediaButtonReceiver'
```

On API 26 this reads `PendingIntent{… startForegroundService}`. On API 36 it reads `null`,
and `adb shell cmd package query-receivers -a android.intent.action.MEDIA_BUTTON` lists no
component of lugu's at all — Media3 falls back to a foreground-service pending intent when
no `MediaButtonReceiver` is declared in the manifest, and newer platforms do not keep one.
Nothing registered means nothing to send the key to once the process has gone, which would
make resumption after a real kill impossible on a modern phone no matter what the resolver
does. This has not been proved end to end — it needs a server, and the two tests that need
one have never run — but it is the first thing to look at if
`a_media_button_resumes_the_same_book_after_the_process_is_killed` goes red.

## Reading a failure in CI

The emulator job uploads `**/build/reports/androidTests/connected/**` as an artifact. Start
there rather than in the log: the HTML report has the stack trace and the device's own
logcat excerpt for each failure.

### A red job with no failing test

The job is capped at **20 minutes**, and one of the ways it reaches that cap is not a test
failure at all. The emulator can hang on the way *out*: on 16 August the API 26 leg reached
`BUILD SUCCESSFUL in 2m 12s` with every test green, ran `adb emu kill`, printed

```
INFO | Wait for emulator (pid 2888) 20 seconds to shutdown gracefully before kill
```

and then never killed it. It happened on two consecutive runs. The first one ran for 99
minutes and stopped only because the next push cancelled it — before the timeout existed,
the ceiling was GitHub's default of six hours.

So when this job goes red, **check where the log stops before assuming a test broke**. If
the last real line is `BUILD SUCCESSFUL`, the tests passed and the emulator failed to exit;
nothing in this repository caused it and nothing here can fix it. Re-run the job. If it
becomes frequent rather than occasional, the emulator-runner's own shutdown handling is the
thing to replace, not anything under test.

There is no AVD snapshot cache to suspect, and that is deliberate — see the comment in
`.github/workflows/ci.yml`. Cold-booting costs about a minute per leg, which is roughly what
restoring the cache cost anyway, and a saved machine image restored into a runner image that
moves weekly had been the first suspect for two separate failures without ever being proved
guilty of either.
