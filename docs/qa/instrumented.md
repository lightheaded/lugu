# Instrumented tests

These are the only tests that run on Android. Everything else in the repository runs on the
JVM, which means everything else stops at the point where the interesting failures start:
processes being killed, media buttons arriving at nothing, and SQLite being a different
build from the one the JVM links.

## Running them

```
./gradlew connectedDebugAndroidTest
```

Needs an emulator or a device attached. CI runs this on API 26 and API 36, and a third leg
on API 36 against the shrunk build — see *Testing the build that actually ships* below.

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
| `AutoBrowseTreeTest` | No |
| `LibraryGridTest` | No |
| `RememberedListControlsTest` | No |
| `RotationTest` | No |
| `CoverCacheTest` | No |
| `core:db` `DeviceMigrationTest` | No |
| `SignInTest` | Yes |
| `PlaybackResumptionTest` | Yes |
| `NextInSeriesTest` | Yes, and a series on it |
| `harness` `the_harness_outlives_a_force_stop_of_lugu` | No |
| `harness` `..._after_the_process_is_killed` | Yes |
| `harness` `a_force_stop_never_resumes_the_wrong_thing` | Yes |
| `harness` `the_library_still_renders_after_a_force_stop_with_no_network` | Yes |

The ones that need nothing are not filler. `LaunchSmokeTest` catches the three ways lugu can
fail to start that no unit test sees — Hilt's graph failing to build, WorkManager's
initialiser being removed without Hilt's replacement taking over, and R8 having stripped
something reached only reflectively. `DeviceMigrationTest` runs the whole 1→5 migration
chain against Android's own SQLite, which is the only place a migration is actually proved:
a migration that fails there is an app that will not open after an update.

### Driving screens with no server behind them

`AutoBrowseTreeTest` established the pattern and three more tests now use it: seed Room
directly under a `serverId` no real account can have, point the account at `books.example`
so nothing can resolve, and assert against what the app draws. It is what lets a check about
the library grid run on a bare CI emulator, and it is honest — a tile that shows a progress
bar cannot have got it from a server that does not exist.

Two things had to be learned for it to work through the *UI* rather than through a
`MediaBrowser`:

- **A `server` row is not a signed-in account.** `isSignedIn` is `serverDao.active() != null
  && tokenStore.tokens() != null`, and the token lives in encrypted preferences. Seeding only
  Room gets you the login screen, and then every assertion fails at "there is no Library tab"
  — which is true and says nothing. `PlantedToken` writes a nonsense token and hands back
  whatever the device already held.
- **Launch the activity yourself.** `createAndroidComposeRule` launches before `@Before`
  runs, so the screen composes against an empty database and has nothing to bring it back:
  its first sync goes to an address that cannot resolve. These use `createEmptyComposeRule`
  and `ActivityScenario.launch` after seeding.

Anything these tests change on the device — the active account, the selected library, a sort
preference — is put back afterwards, so running them on a phone signed in to a real server
does not sign it out or re-order its library.

`PlaybackResumptionTest` needs a server because a saved position only exists once something
has played. Without one it **skips** rather than fails, so a developer with no container
running still gets a green suite.

That skip used to apply to CI too, which meant the most valuable test in the repository had
never once executed there — it looked green and proved nothing. CI now seeds its own server
before the emulator starts, and a step after the run **fails the job if any test skipped at
all**. A skip on a runner no longer means "unrunnable"; it means the wiring broke.

## The end of a book, and the one after it

`NextInSeriesTest` watches the join that nothing had ever watched: a book reaches its end,
and the next volume of its series begins. Every part of that path had a test and the join
had none — `nextInSeriesAfter` against Room, the memberships against the migrations, the
ask-first rule against `DefaultContinuationResolver` — and three suites that each stop one
step short of the other two prove nothing about the step between them.

Two things about it are deliberate.

**The end is a real end.** The volume is walked forward to its last few seconds and then
left alone, so what the service reacts to is the player running out of audio. Posting
`STATE_ENDED` at it would prove the listener works and say nothing about whether anything
ever calls it. The walk is `ProcessDeathResumptionTest`'s, in short steps with the position
rechecked after each one, for the reason commit dfde4e1 records.

**It fails rather than skips when the fixture is missing.** That is the opposite of the rule
every other test here follows, and the fixture is why: the seeded catalogue had no series at
all until this was written, so a check that skipped for want of one would have gone on
reporting exactly what the missing fixture already reported — nothing, in green.

It lives in `:app` rather than `:harness` because the join needs no kill and does need Room:
the two volumes are named by their **sequence** in `item_series`, the cued one is asserted at
the head of the `queue` table, and `askBeforeSuggestion` is turned on and put back in lugu's
own DataStore. A black box could only report that *something* started playing.

## Pointing the tests at a server

Nothing is committed. The tests read `BuildConfig` fields that come from the gitignored
`local.properties` — the same file that prefills the login screen on a debug build.

```properties
lugu.dev.serverUrl=...
lugu.dev.user=...
lugu.dev.pass=...
lugu.test.playQuery=...
lugu.test.seriesQuery=...
```

The same five values are also read from the environment as `LUGU_DEV_SERVER_URL`,
`LUGU_DEV_USER`, `LUGU_DEV_PASS`, `LUGU_TEST_PLAY_QUERY` and `LUGU_TEST_SERIES_QUERY`.
**The environment wins where both exist**, which is how you point a machine that already
has a server configured at a throwaway container for one command:

```sh
LUGU_DEV_SERVER_URL=http://10.0.2.2:13378 LUGU_DEV_USER=… LUGU_DEV_PASS=… \
LUGU_TEST_PLAY_QUERY="Lighthouse Wakes" LUGU_TEST_SERIES_QUERY="Riverton" \
./gradlew connectedDebugAndroidTest
```

This started out the other way round, on the reasoning that a stray variable should not be
able to redirect your own tests. It was changed because that reasoning cost more than it
was worth: diagnosing a sync failure meant running the suite against a container while
`local.properties` pointed somewhere else, and there was no way to say so for one
invocation. An explicit variable beating an ambient file is also the ordinary precedence
everywhere else.

Both `:app` and `:harness` read them the same way, and they must stay that way — the app
signing into one server while the harness plays a title from another is the failure this
warns against.

The release build ignores both and always compiles these in empty — a shipped APK never
carries an address.

### Getting a server without having one

```sh
scripts/seed-test-server.sh
```

Builds one from nothing: an Audiobookshelf container, a generated root account, two
libraries and a small invented catalogue — a chaptered 90-second book, a two-file book so
that "crosses a file boundary" has a boundary, a two-volume series so that "the next
volume began" has a next volume, and three podcast episodes. It prints the five properties
to paste into `local.properties` and takes about ninety seconds. Stop it with
`docker rm -f lugu-test-abs`.

The series is the newest of those and the one that was missing longest. Audiobookshelf
takes both the series and the volume number from the folder layout —
`<author>/<series>/Vol. N - <title>` — so the scan alone is enough and no call after it is
needed. That matters more than it sounds: an `album` tag is not a series, and the catalogue
had only ever had tags. `nextInSeriesAfter` ignores a membership with no number, so a
next-in-series test against the old fixture would have found nothing to continue to and
passed for it.

The library is sine waves and the invented names AGENTS.md reserves. No real title,
author, address or credential is ever involved, and the password is generated per run.

**From an emulator the address is `http://10.0.2.2:13378`, not localhost** — inside an AVD,
localhost is the AVD.

Reaching it over plain HTTP needed a debug-and-minified manifest overlay until 17 August,
because Android refuses cleartext from API 28. That overlay is gone: lugu now permits
cleartext in the main manifest for every build, having answered the question it was
deliberately not answering — a great many Audiobookshelf servers are plain HTTP on a home
network, and refusing them outright made lugu unusable for their owners with an error that
blamed the server. The decision moved into the app, where the sign-in screen states what a
plain-HTTP address costs before the password is sent.

This is what CI now does before every emulator run, which is why these tests stopped
skipping there.

## Testing the build that actually ships

R8 is on for release, and until the shrunk build could be run on a device, nothing proved
its keep rules — `assembleRelease` proves only that nothing is missing at *compile* time,
while everything R8 breaks fails at runtime and only in a minified build.

The `minified` build type is `release` with `initWith`, so it cannot drift from what ships:
same R8, same resource shrinking, same keep rules. It differs in the three things that have
to differ — debug signing so no keystore is needed, its own application id so it can sit
beside a debug install, and a test server to reach.

```sh
./gradlew connectedMinifiedAndroidTest -Plugu.testMinified \
  -Pandroid.testInstrumentationRunnerArguments.annotation=io.github.lightheaded.lugu.BlackBox
```

The property switches `testBuildType`; without it instrumented tests run against `debug` as
before. CI runs one leg this way on API 36, because what it catches is a missing keep rule
rather than a platform difference.

### Only the black-box tests run there, and that is not a choice

The androidTest APK is compiled separately from the app and loaded into its process, so
every name it uses has to still exist in the app under that name. On this build type
lugu's classes are renamed — that is the point — so a test that constructs a `LuguDatabase`
or parses a `BrowseNode` cannot resolve it and dies before asserting anything. Those tests
run on `debug`, where nothing is renamed, which is the right place for them.

`@BlackBox` marks the ones that treat lugu as a black box; CI selects on the annotation.
Today that is `LaunchSmokeTest`, which is a small set and the right one: it launches the
app, so it exercises Hilt's graph, WorkManager's initialiser and the whole startup path
under R8.

### What this leg proves, and what it does not

**Proves:** lugu's own code survives R8. That is where the risk named in the backlog lives —
Room's generated DAOs, Hilt's components, reflectively resolved serializers and a media
service the system binds by name are all lugu's classes.

**Does not prove:** that shrinking third-party libraries is safe. `proguard-minified-rules.pro`
keeps everything outside lugu's package, because the test APK references those names and R8
was removing them one at a time — `androidx.tracing.Trace` first, which killed the
instrumentation process before a single test loaded, then `kotlin.Lazy`, then Media3's
`MediaBrowser`, then Compose's `InfiniteAnimationPolicy`. Chasing that list is unbounded.
Release still shrinks libraries and nothing here checks it; that gap is the price of
instrumenting a minified build at all, and it is better written down than forgotten.

None of those rules reach release. Verified on the built APKs: the release APK still has
`androidx.tracing.Trace` stripped and `CoverStore` renamed.

`lugu.test.playQuery` is the title the playback tests ask the library for. It is separate
from the credentials on purpose: having a server is not the same as agreeing that a test may
play something on it, and whoever sets this chooses what.

`lugu.test.seriesQuery` is the same consent for a whole series, and it is a second key
rather than a second use of the first because it costs more. `NextInSeriesTest` runs one
volume to its end and lets the next one begin, so it moves the position of two books, and
it puts both of them back to unstarted before it starts — on the server as well as in Room,
because `nextInSeriesAfter` skips a volume anybody has already opened. Without that reset
the test would pass once and report "no next volume" forever after.

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

One run looks like this: open lugu, tap the prefilled sign-in button, open the Library tab
and wait for the title to appear on it, ask for that title with a `PLAY_SEARCH` broadcast,
put the book back to its beginning and walk it forward to forty-five seconds, set a speed it
was not already on, end the process, `input keyevent 126`, and read the session back.

### It moves the position of the title you nominate

The book is rewound to its start and walked forward every time, so that a run does not
inherit wherever the run before it stopped. **Whatever place you had in the title named by
`lugu.test.playQuery` is overwritten**, and on an Audiobookshelf server progress is held
server-side, so a fresh install does not undo it. That is the third reason that key is
separate from the credentials.

It is also not paranoia. The first version of this test skipped ten minutes forward in one
broadcast; against the ninety-second book the seed script builds, that ran off the end,
which stopped playback, which meant the speed set next was never reported by a session that
was no longer playing. The book was then stored *finished*, and the next test resumed it at
its end, where it stopped immediately and reported that nothing had started playing. One
wrong constant, two failures that looked unrelated, and a poisoned server that outlived the
uninstall between them.

### Two kills, and they do not behave the same

- **The process dies** — `run-as … kill -9`, falling back to `am crash`. The package's state
  is untouched, and the media button is expected to bring the book back. Asserted strictly:
  same item, within tolerance of the same position, at the same speed.
- **`am force-stop`** — asserted narrowly, and measurement is why. Force stopping also puts
  the package into the **stopped state**, which the platform holds until a person launches
  the app, and on Android 15 and later it [cancels the app's pending
  intents](https://developer.android.com/about/versions/15/behavior-changes-all) as it
  enters that state. Observed on one API 36 emulator within the same hour: a media button
  after a force stop *did* wake lugu on two runs and *did not* on a third, on a fresh
  install. Neither outcome is lugu's to control, so neither can be the assertion. What
  would be lugu's fault is coming back **wrong**, and that is checked on whatever does come
  back; nothing coming back is logged and passed.

### Offline, without a touch on the radio

`OfflineLibraryTest` takes the last M0 sign-in line a machine can take: force-stop lugu,
take the network away, open it again, and the library must still render with no sign-in
screen.

The radio is what stopped this check before. The two legs cannot toggle airplane mode the
same way:

- `cmd connectivity airplane-mode enable` starts at API 30. The API 26 leg does not have it.
- `settings put global airplane_mode_on 1` only moves a flag. The radios follow the
  `AIRPLANE_MODE` broadcast, which is protected: the platform takes it from root and from
  the system, and the shell is neither, on API 26 as well. So the write alone leaves a
  different state on each leg.
- `svc wifi disable` leaves mobile data up, and an emulator has mobile data.
- Doze, battery saver and Data Saver exempt the foreground app, which is the app under test.

A check that runs on one leg and skips on the other is worse than the manual line, because
CI fails every skip. So the harness cuts the network itself. `OfflineVpnService` opens a VPN
that names lugu alone and never reads the tunnel, so lugu loses every route to every
network, by the same route on both legs. The shell grants the `ACTIVATE_VPN` app op, and the
helper asks `VpnService.prepare` after each spelling of the command. The consent is
measured, not guessed from the API level.

It is also the safer route. Only lugu enters the tunnel, so adb, the emulator and every
other test keep their network, and the platform closes the tunnel when the harness process
dies. A test that turned a radio off can leave the whole device offline for the next job.

Three things it does not prove, and the manual line keeps all three:

- **Airplane mode itself.** The radios stay on and `Settings.Global.AIRPLANE_MODE_ON` stays
  at 0, so no code that reads that flag runs.
- **No network at all.** lugu still sees a default network, and every request on it fails.
  That is the harder case for a screen that must come out of the database, and it is not the
  state of a network that went away.
- **The full library.** The check asserts one known title from `lugu.test.playQuery`, and it
  only reads that title, so it moves nobody's place in a book. A count of a real shelf cannot
  go in this repository, so "full" stays a manual claim.

The account is real, which the trap above makes necessary: a seeded `server` row without a
token is not a signed-in account, and lugu's encrypted preferences belong to lugu. So this
check needs a server and skips without one. CI seeds one.

The network cut is asserted **after** the render, not before it. That closes the one hole
that would make a pass meaningless: a tunnel that went away, and a server that answered.

### Why `dumpsys media_session` and not a `MediaController`

A controller would give typed values instead of parsed text. It would also *bind the
service*, which starts lugu's process — so a media button that did nothing at all would be
followed by a controller quietly bringing the app back, and the test would pass having
proved the opposite of what it claims. Reading the system's own record touches nothing. It
is also the channel the recipe below uses, so a failure can be reproduced by hand.

Two things about reading it, both learnt by running it. Android 16 writes
`state=PLAYING(3)` where older releases write `state=3`, so a parser that reads only the
bare integer returns nothing at all — which is indistinguishable from an app that is not
running, and would have made every test here green and blind.

And the published position is a stamp with a time on it rather than the position now,
because a session publishes on events rather than on a timer. The obvious move is to carry
it forward by the elapsed time, and that is the wrong instrument for this assertion: on one
API 26 run the session went quiet for ten seconds, the arithmetic invented fifteen seconds
of progress the player had not made, and the book duly "resumed fifteen seconds behind" a
position it had never reached. The tests compare **what the platform said**, which a seek
always makes it say again; a stale reading of that errs towards an earlier position, which
loosens the "behind" check and tightens the "ahead" one, and both are the safe direction.

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

### What a green run proves, in numbers

The resumption test writes its own evidence to logcat under `LuguHarness`, on a pass as
well as a failure, because "it resumed" and "it resumed where it was" are different claims:

```
I LuguHarness: killed lugu's process 19887 while it was playing
I LuguHarness: resumed 9c46b6de at 45209ms x1.5; was 9c46b6de at 45239ms x1.5
```

That is API 36 against the seeded server: the process gone, a media button, and the same
item back thirty milliseconds from where it stopped, at the speed it was being listened to.
API 26 lands in the same place. The identity is a digest of the title rather than the
title, so this line is safe to paste anywhere.

Two things were checked before that pass was believed. Removing the `input keyevent 126`
makes the test fail — nothing resumes on its own within sixty seconds, so the button is
what does it, not a service the system restarts by itself. And the speed asserted is never
the speed already in force: the harness picks 1.5 or 1.2, whichever the book is *not* on,
so `x1.5` on both sides of that line is a value that had to survive the kill.

It is also what proves the media button receiver in `:playback`'s manifest. Until that
receiver was declared, the platform had nothing to deliver a headset press to once lugu's
process was gone — `dumpsys media_session` reported `mediaButtonReceiver=null` on API 36,
and `adb shell cmd package query-receivers -a android.intent.action.MEDIA_BUTTON` listed no
component of lugu's. The fix was four lines of manifest and, until this test ran, an
argument rather than a fact.

## Reading a failure in CI

The emulator job uploads `**/build/reports/androidTests/connected/**` as an artifact. Start
there rather than in the log: the HTML report has the stack trace and the device's own
logcat excerpt for each failure.

### A red job with a real failing test

A named test with a real assertion is the app's problem until a control says otherwise.
Read the report first. Then re-run **the same commit**, changing nothing.

That re-run is the whole test. A regression fails again. A race does not.

Measured on 31 August 2026, across four runs:

| Run | Commit | App code | Result |
| --- | --- | --- | --- |
| 33362397704 | `44f95df` | baseline | green |
| 33364948344 | `dc4d316` | identical to the row above | red, speed lost |
| 33366874023 | `99a8fb6` | speed fix | red, two other tests |
| 33366874023, re-run | `99a8fb6` | identical to the row above | green |

The second row is the proof. `dc4d316` changes markdown, PNG files and a test-task input
declaration, so nothing in it reaches the APK. The emulator ran the same binary that had
just passed, and it failed.

Three different assertions failed across those runs:

```
the remembered speed was lost, expected 1.5
expected to be at least: 29885, but was 19829
the expected item was not loaded after 60000ms
```

None of them is emulator noise. Each one is real state, read at a moment the test was
allowed to read it. The first was traced to its cause and fixed. The other two are open,
and [../BACKLOG.md](../BACKLOG.md) holds what is known about each.

**Do not read this section as permission to dismiss a red leg.** It says the opposite of
that. A race is a defect that appears on some runs, which is worse than one that appears
on every run, because it reaches a listener the same way and hides from the suite. What
the re-run buys is the difference between "my change broke this" and "this was already
broken", and both of those need fixing.

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
