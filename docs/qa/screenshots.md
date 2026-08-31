# Screenshot tests

A suite nobody knows how to update is a suite that gets deleted. This is how to update it.

## What is recorded

Every screenshot is taken twice, light and dark. A light-only baseline lets a dark-mode
regression through, and dark mode is when most of this app is used.

| Module | Baselines |
| --- | --- |
| `feature/library` | Home tab, Library tab, the grid with a selection running, a book page, a podcast page, an episode page, the queue |
| `feature/player` | The player, playing and paused |
| `feature/settings` | Settings, settings filtered by a search, a search that matches nothing |
| `app` | The "why playback stopped" record, full and empty |

Images live in each module's `screenshots/` directory and are committed. They are the
baseline; nothing else records what the app looked like.

## Running them

They are ordinary JVM unit tests — Roborazzi renders Compose through Robolectric's native
graphics, so no emulator and no device is involved.

```
./gradlew testDebugUnitTest
```

This **verifies** against the committed images and fails on any difference, which is why
`./gradlew build` catches a visual regression on its own.

That only holds because the root `build.gradle.kts` declares `<module>/screenshots` as an
input of the test task. The images sit outside `src/`, so without that declaration Gradle
holds the task up to date whatever they contain, and CI replays the cached pass. Do not
remove it. A green run with `FROM-CACHE` beside every test task verified no picture. A failure writes a side-by-side
comparison into `<module>/build/outputs/roborazzi/`; CI uploads that directory as an
artifact, because a red run nobody can see is a red run nobody fixes.

## Re-recording after an intentional change

The images must be recorded on Linux — see below. Two ways to get a Linux host: let CI
do it (recommended), or use a local container (fallback).

### Recommended: record on CI

`.github/workflows/record-baselines.yml` runs the record task on `ubuntu-latest`, the
same host `build` verifies against, and uploads the results as a workflow artifact. It
never runs on push or pull request — dispatch it by hand:

```
gh workflow run record-baselines.yml
```

Wait for the run to finish, then find it and download the artifact:

```
gh run list --workflow=record-baselines.yml --limit=1
gh run download <run-id> --name roborazzi-baselines
```

`gh run download` writes the images into the matching `screenshots/` directories under
the current folder. Move them over the committed copies if the paths differ, then look
at `git diff` before committing. Every changed image is a change to what the app looks
like — if one you did not expect has moved, that is the suite doing its job.

### Fallback: record in a local container

**Record on Linux, not on your Mac.** Roborazzi renders through Robolectric's native
graphics mode, which uses the host's own rasterizer. A baseline recorded on macOS does
not pixel-match the same screen on CI's `ubuntu-latest` runner, so `./gradlew build`
fails there even though the screen did not change. Measured on 31 August 2026, a plain
`./gradlew build` on an Apple Silicon Mac fails 16 screenshot assertions for this reason
alone.

A `linux/amd64` container on that same Mac is faithful. The whole suite passed inside one
against images recorded on the runner. Two constraints hold:

- The base must match the runner, which reports **Ubuntu 24.04.4 LTS**. Use
  `eclipse-temurin:21-jdk-noble`, not a jammy image.
- The platform must be `linux/amd64`. Robolectric ships `native/linux/x86_64` and no
  arm64 build, so an arm64 container cannot run these tests at all. Docker on Apple
  Silicon runs amd64 through Rosetta.

```dockerfile
FROM --platform=linux/amd64 eclipse-temurin:21-jdk-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl ca-certificates git \
 && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${PATH}"
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
 && curl -sSLo /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
 && unzip -q /tmp/clt.zip -d "${ANDROID_HOME}/cmdline-tools" \
 && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
 && rm /tmp/clt.zip
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true
RUN sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" > /dev/null
WORKDIR /work
```

Copy the tree in before you run it. **Never mount the live checkout read-write for a
throwaway build.**

```
git archive HEAD | tar -x -C "$SCRATCH/tree"
echo "sdk.dir=/opt/android-sdk" > "$SCRATCH/tree/local.properties"
docker volume create lugu-gradle-cache
docker run --rm --platform linux/amd64 \
  -v "$SCRATCH/tree":/work -v lugu-gradle-cache:/root/.gradle \
  lugu-ci-replica ./gradlew testDebugUnitTest -Proborazzi.record --no-daemon
```

The named volume keeps the Gradle cache between runs. A cold run takes about six
minutes, and a warm one about one minute.

Copy the images back, then look at `git diff` before committing, as above. Record
everything in one run rather than module by module, so the whole set stays taken with
the same Compose and Robolectric versions.

## Why the pictures are not what is on your phone

Two deliberate differences:

- **No dynamic colour.** lugu follows the wallpaper from Android 12 onwards, which is by
  definition not the same on two phones. The baselines use lugu's fallback palette, which
  is what runs below Android 12 and wherever dynamic colour is off. The light and dark code
  paths are the same either way.
- **No cover art.** Covers are fetched over the network, so every image renders the
  placeholder block instead. That is also the state a real screen is in for the first
  moment it appears.

## What is not covered

Every screen in lugu takes a Hilt view model. Most of those view models depend on final
classes over Room, DataStore and Ktor, which cannot be substituted, so most of these
pictures are of the screens' own components — `ItemCard`, `ShelfRowView`, `ListControlsBar`,
`SelectionBar`, `DownloadButton`, `RowActionsMenu`, `PlayerActionRow` — arranged the way the
screen arranges them, rather than of the screen composable itself. A change to the order of
blocks inside a screen file will not fail these.

Settings is the exception: its view model's dependencies are all constructible without Hilt,
so `SettingsScreenshotTest` drives the real `SettingsScreen`. Two things follow. Adding a row
that renders here changes these baselines and they must be re-recorded — see below. And the
test runs **signed out**, over an empty database, so anything inside `state.account?.let` is
absent from the pictures: a change to the account section may leave them untouched, which is
the suite being accurate rather than the suite missing it.

That real view model also makes this the one place a settings *crash* is caught. It builds a
`ConnectionPrefs` whose encrypted store cannot open under Robolectric, exactly as on a device
whose keystore is unusable — so a screen that does not survive that failure produces no
picture at all.

Making a screen's own content composable `internal` and stateless — taking a UI state and a
set of callbacks, with the view model wiring left in the public wrapper — would let the rest
be photographed as they really are. That is a change to production code and is not made
here.

## Adding a screen

1. Put the test in `src/test/`, in a file whose name ends in `ScreenshotTest.kt`.
2. Copy the `ScreenshotTheme` helper from a neighbouring test, so the palette matches.
3. Pin anything that would otherwise move: a clock, a time zone, a relative date. A picture
   that depends on the day it was taken fails on the next day.
4. Record, look at the image, then commit it.
