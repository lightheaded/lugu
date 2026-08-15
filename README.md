# lugu

A native Android client for [Audiobookshelf](https://www.audiobookshelf.org/).

**Pre-alpha.** Sign in, browse and search your libraries, play books and podcast
episodes, keep your position, and download for offline listening. Everything else in
[docs/PLAN.md](docs/PLAN.md) is still ahead.

*lugu* is Estonian for "story".

## Why

The self-hosted audiobook community has an excellent server and no excellent Android
client. The official app is a Capacitor/webview hybrid with architectural problems that
have outlived years of issue reports: lost positions, headset rewind inaccuracy, no
queue, Android Auto that needs the phone app open, weak offline support. lugu is the
attempt at the app a discerning listener can daily-drive without a single workaround —
native, offline-first, open source, zero telemetry.

The evidence behind that claim is in [docs/research/](docs/research/); the plan is
[docs/PLAN.md](docs/PLAN.md).

## What works today

- Sign in to a server with username and password (full JWT access/refresh flow,
  proactive single-flight refresh — v2.26+ servers only)
- Library mirror into a local Room database; every screen renders from the database,
  so a cold start shows your library with no network
- **Full-text search** across titles, authors, narrators, series and descriptions,
  matching as you type
- **Computed shelves**, all local: Continue listening, Next in series, Almost finished,
  Downloaded, Pick it back up, Short listens
- Playback via Media3/ExoPlayer with a `MediaLibraryService`: notification controls,
  exact seeking, chapter navigation, multi-file books handled as one timeline
- **Downloads and offline playback.** A downloaded book opens no server session at all —
  the URLs, track offsets and chapters are on the phone, so it plays in airplane mode
  and the listening replays to the server when there is a connection again
- Configurable transport: skip durations, which buttons appear in the player and the
  notification, speed presets, speed remembered per book and per podcast
- Searchable settings
- Position kept on every pause, seek, track change and five-second tick; playback
  resumption after process death rebuilds from the database
- **Pull-before-push progress sync** — a newer position from another device wins, is
  shown to you, and can be undone; automatic pushes can never move the server backwards
- Durable outbox: progress changes are written locally first and drained by WorkManager
  with backoff, so being offline loses nothing
- Every large position change is recorded, so an accidental jump can always be undone

## Not yet

Auto-download rules, Android Auto, queue and auto-continuation, bookmarks, Chromecast,
widgets, author and series pages. [docs/EXECUTION-PLAN.md](docs/EXECUTION-PLAN.md) has
the order these land in, and [docs/BACKLOG.md](docs/BACKLOG.md) lists everything
knowingly unfinished, with the reason.

## Install

Grab the APK from [Releases](../../releases). It is signed with a stable key, so later
builds install over the top.

Requires Android 8.0 (API 26) or newer and an Audiobookshelf server on v2.26 or newer
(the JWT auth model; older servers used permanent tokens and are not supported).

## Build

```
./gradlew assembleDebug
```

Needs JDK 17+ and the Android SDK (compileSdk 37). Optionally put dev credentials in a
gitignored `local.properties` to prefill the login screen on debug builds:

```
lugu.dev.serverUrl=https://your-server.example
lugu.dev.user=you
lugu.dev.pass=...
```

Nothing from `local.properties` reaches a release build, and no server address or
credential ever belongs in this repository.

## Architecture

Kotlin, Jetpack Compose, Media3. The module layout keeps pure logic free of Android
types so a shared-core iOS port stays possible:

| Module | What lives there |
|---|---|
| `:core:model` | Domain types and the sync/chapter rules (pure Kotlin, unit-tested) |
| `:core:api` | Audiobookshelf HTTP client, DTOs, auth and token refresh (pure Kotlin) |
| `:core:db` | Room schema — every user-scoped row keyed by `(serverId, userId)` |
| `:core:sync` | Library mirror, progress engine, session ledger, outbox, workers |
| `:core:download` | Download service and cache; the manifest that makes a book playable offline |
| `:playback` | `MediaLibraryService`, ExoPlayer, timing across multi-file books |
| `:feature:*` | Compose UI: library, player, settings |
| `:app` | Application, navigation, theme, DI wiring |

The local database is the source of truth; the server is a sync target.

## Privacy

No telemetry, no analytics, no crash reporting. The only host lugu talks to is the
Audiobookshelf server you point it at.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
