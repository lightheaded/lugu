# lugu

A native Android client for [Audiobookshelf](https://www.audiobookshelf.org/).

**Pre-alpha.** Sign in, browse and search your libraries, play books and podcast
episodes, keep your position, queue what is next, download for offline listening, and
take it into the car. The rest of [docs/PLAN.md](docs/PLAN.md) is still ahead.

*lugu* is Estonian for "story".

lugu is an independent project. It is not affiliated with, nor endorsed by,
[Audiobookshelf](https://www.audiobookshelf.org/).

## Why

The self-hosted audiobook community has an excellent server and no excellent Android
client. The official app is a Capacitor/webview hybrid with architectural problems that
have outlived years of issue reports: lost positions, headset rewind inaccuracy, no
queue, Android Auto that needs the phone app open, weak offline support. lugu is the
attempt at the app a discerning listener can daily-drive without a single workaround —
native, offline-first, open source, no analytics.

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
- **Downloading ahead**, all off by default: what is queued, the next books in a series
  you are reading, the latest episodes of a podcast you are listening to — on unmetered
  networks only, because lugu deciding on its own to spend mobile data is not a thing it
  should ever do
- **A play queue**, with play-next, drag to reorder, and automatic continuation into the
  next book in a series or the next podcast episode
- **Android Auto**: a browse tree served from the local database, so it works before the
  phone app has been opened, plus voice search and chapter and speed controls in the car
- **Bookmarks**, synced to the server and written locally first, so one made in a tunnel
  is still a bookmark
- **Author, series and narrator pages**, computed locally — the server has no API that
  hands a client either of the first two
- **Podcast trimming**, per show: skip the intro, skip the outro, and skip adverts where
  the episode marks them with a chapter that names itself as one. Every skip says so and
  can be undone. An advert the episode does not mark cannot be found — that would need
  fingerprinting against a database of known adverts, and a skip that removes narration
  is worse than an advert that plays
- **Streaming that survives a dropout**: minutes of read-ahead rather than seconds,
  retries that come back when the network does, and streamed audio kept in a bounded
  cache of its own — never in the download cache, because a book you asked for must
  never be evicted to make room for one you merely streamed
- Chapter list, silence skipping, volume boost, a sleep timer that fades out, rewinds
  when you come back and can be extended with a shake
- Configurable transport: skip durations, which buttons appear where and **in what
  order**, what a headset's next and previous buttons do, speed presets, speed remembered
  per book and per podcast
- Search, sort, filter and multi-select on every long list
- Searchable settings
- Position kept on every pause, seek, track change and five-second tick; playback
  resumption after process death rebuilds from the database
- **Pull-before-push progress sync** — a newer position from another device wins, is
  shown to you, and can be undone; automatic pushes can never move the server backwards
- Durable outbox: progress changes are written locally first and drained by WorkManager
  with backoff, so being offline loses nothing
- Every large position change is recorded, so an accidental jump can always be undone
- **Live updates over Socket.IO**, so an edit made on the web appears rather than waiting
  for the next sync
- **Behind a proxy**: custom headers on every request (for Cloudflare Access and the like),
  a client certificate for mTLS, and a second address used when your own network answers
- **Collections**, browsable and editable
- **Automation intents** for Tasker and anything else that can send a broadcast — see
  [docs/automation.md](docs/automation.md)
- **A local record of why playback stopped** — see Privacy below

## Not yet

Chromecast, widgets, Wear OS, Android TV, OIDC sign-in and multi-server.

[docs/EXECUTION-PLAN.md](docs/EXECUTION-PLAN.md) has the order these land in, and
[docs/BACKLOG.md](docs/BACKLOG.md) lists everything knowingly unfinished, with the
reason — including what has been built but never yet run on real hardware, which is not
the same as done.

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

No analytics, ever. No advertising, no tracking, no usage statistics.

**Crash reporting is opt-in and off by default.** Leave it off — the default — and the
only host lugu talks to is the Audiobookshelf server you point it at; the reporting SDK
is never initialised, so it cannot run, phone home, or watch anything.

Turn it on in Settings → Diagnostics and a crash sends its stack trace, the app version
and the device model to a Sentry project hosted in the EU. It does not send your account,
your server address, your library, or what you were listening to. Turning it back off
stops it immediately, not at the next launch.

There is one more thing under Diagnostics: **why playback stopped**, a record of starts,
stops, errors and the app being killed. It is written to a file on the phone and nowhere
else. It is not telemetry and does not become telemetry — nothing reads it but you, unless
you deliberately attach it to a report. It exists because "it just stopped" has half a
dozen causes that look identical from the outside, and a diagnosis that only works for
people who opted into crash reporting is no diagnosis at all.

## License

Copyright (C) 2026 lightheaded.

GPL-3.0-or-later. See [LICENSE](LICENSE). Licenses of the libraries lugu ships with
are listed in the app under Settings → Open source licenses.
