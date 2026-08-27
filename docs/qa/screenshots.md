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
`./gradlew build` catches a visual regression on its own. A failure writes a side-by-side
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

### Fallback: record locally

```
./gradlew testDebugUnitTest -Proborazzi.record
```

Then look at `git diff` before committing, as above.

Record everything in one run rather than module by module, so the whole set stays taken
with the same Compose and Robolectric versions.

**Record on Linux, not on your Mac.** Roborazzi renders through Robolectric's native
graphics mode, which uses the host's own font and icon rasterizer — a baseline recorded
on macOS will not pixel-match the same screen rendered on CI's `ubuntu-latest` runner, and
`./gradlew build` will fail there even though nothing about the screen actually changed.
Record in an environment matching CI: `eclipse-temurin:21-jdk-jammy` on `linux/amd64`, with
Android SDK `platforms;android-37.0` and `build-tools;37.0.0`. If using a local Docker
container for this, copy the working tree into it first — never mount the live checkout
read-write for a throwaway build.

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
