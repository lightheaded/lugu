# CLAUDE.md — lugu

## Architecture

Offline-first Android app. The local Room database is the source of truth. Modules:

- `app` — activity, navigation, instrumented tests
- `core:model` — pure Kotlin data classes and logic, no Android
- `core:ui` — shared Compose components (`StatusStrip`, `ReservedMessage`, etc.)
- `core:api` — Audiobookshelf client
- `core:db` — Room database, DAOs, migrations
- `core:sync` — DataStore preferences, repository, sync logic
- `core:download` — download manager
- `playback` — Media3 playback service
- `feature:library` — Home and Library tabs, grid, shelves
- `feature:player` — player screen
- `feature:settings` — settings screen
- `harness` — separate app that kills lugu and watches what happens

## CI

`./gradlew build` runs unit tests, screenshot tests (Roborazzi), and lint. The
instrumented tests run on API 26 and API 36 emulators, plus a minified leg on API 36.

**`build` does not compile the instrumented sources.** Change a shared constructor or a
public signature and the `androidTest` source sets are consumers too, so add
`./gradlew compileDebugAndroidTestKotlin` — or `assembleDebugAndroidTest` — before you
push. On 3 September 2026 a green `build` went red on all three emulator legs for one
missing constructor argument in a test helper, and the same change had a second fault
underneath it that would have compiled: the helper planted a token for whichever account
was active, and the per-account token store had just made that the wrong one.

The instrumented job does NOT gate the release (emulator flakiness), but a failure
turns the run red and must be investigated.

A red instrumented job is a code bug until proved otherwise. Do not disable, skip,
or weaken a failing test — find what the test caught and fix the code. If the same
test fails across multiple API levels, the cause is in the app, not in the emulator.
See `docs/qa/instrumented.md §Reading a failure in CI` for the one exception: a red
job with no failing test means the emulator hung on shutdown, not a test failure.

Several of these tests are racy, so a re-run of the **same commit** can come back
green. That tells you the failure is not your change. It does not tell you the app is
sound — a defect that appears on some runs reaches a listener the same way. Read the
report, then re-run the same commit to learn which of the two you are looking at, and
record what you found in `docs/BACKLOG.md`. Never re-run to make a red job go away.

See `docs/qa/instrumented.md` for the full picture.

## Compose overlay rule

**A clickable overlay must not cover fixed interactive controls.** `StatusStrip` is
clickable when it shows an error or a note. It intercepts every touch under it.

On screens with fixed controls at the top (chips, browse links, filter bars), place
the `StatusStrip` inside a `Box` that wraps only the scrollable content — below the
fixed controls in the `Column`. On screens with no fixed top controls, the overlay
can cover the full content area.

This rule broke CI for four days (20–24 August 2026) when the strip sat over the
library picker chips. `LibraryGridTest` guards this: it taps a library picker chip,
which fails when the strip covers the chips. See `StatusStrip.kt`'s doc comment for
the full placement contract.

## Commit messages

Follow the pattern in `git log`: `type(scope): what changed and why`.

## Secrets

Never commit server addresses, credentials, tokens, or captures with real ids. Dev
credentials go in the gitignored `local.properties`. See `CONTRIBUTING.md`.
