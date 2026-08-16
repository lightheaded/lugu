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

## What runs without a server

| Test | Needs a server |
| --- | --- |
| `LaunchSmokeTest` | No |
| `core:db` `DeviceMigrationTest` | No |
| `PlaybackResumptionTest` | Yes |

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

## The one thing these tests cannot do

The plan asks for the app to be killed mid-book, then for play to be pressed on a headset.
The second half is covered. The first half is not, and cannot be from here.

Instrumentation runs **inside** the process under test. `am force-stop` on this package
takes the test runner with it, and there is no second process to issue it from — a
`com.android.test` module with its own application id would provide one, and that is the
change to make if this becomes worth automating.

What `PlaybackResumptionTest` does instead is destroy the playback service, which is what
Android actually does when it reclaims memory, and then reach the resumption path from
`Room` exactly as a cold start would. It is the same code under test; it is not the same
kill.

To do the real thing by hand:

```sh
# with something playing
adb shell am force-stop io.github.lightheaded.lugu.debug
# then press play on a headset, or:
adb shell input keyevent 126
adb shell dumpsys media_session | grep -A5 lugu
```

Playback should resume the same item within a few seconds of where it stopped. If it starts
from zero, or starts something else, that is the bug the whole app exists to avoid.

## Reading a failure in CI

The emulator job uploads `**/build/reports/androidTests/connected/**` as an artifact. Start
there rather than in the log: the HTML report has the stack trace and the device's own
logcat excerpt for each failure.
