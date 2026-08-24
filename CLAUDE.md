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

The instrumented job does NOT gate the release (emulator flakiness), but a failure
turns the run red and must be investigated.

A red instrumented job is a code bug until proved otherwise. Do not disable, skip,
or weaken a failing test — find what the test caught and fix the code. If the same
test fails across multiple API levels, the cause is in the app, not in the emulator.
See `docs/qa/instrumented.md §Reading a failure in CI` for the one exception: a red
job with no failing test means the emulator hung on shutdown, not a test failure.

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
