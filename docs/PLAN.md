# Project Plan: A Best-in-Class Android Client for Audiobookshelf

*Status: draft v1 — 2026-08-14. Evidence base: [docs/research/](research/) (community complaints, client landscape, server API, tech stack).*

## 1. Vision

The self-hosted audiobook community has a great server and no great Android client. The official app is a Capacitor/webview hybrid with chronic, architectural problems (state loss, Android Auto requiring the phone app open, no queue after 4 years of requests). Third-party clients each fix a slice; none combines native playback excellence, true offline-first operation, Android Auto that just works, and a real queue. Users literally sync files out of ABS to play them in Smart Audiobook Player.

**Goal: the app a discerning listener daily-drives without a single workaround.** Native Android, open source, privacy-respecting, personal-use-first, then Play Store + IzzyOnDroid/F-Droid.

Scope from day one: **audiobooks and podcasts**. Android-first; iOS later only if cheap (architecture keeps that door open).

## 2. What we must beat (research summary)

Every headline gripe is validated by widespread community complaints:

| Gripe | Evidence | Root cause in official app |
|---|---|---|
| State lost after app closed | app#1800, #41, #578, #804, #1298; HN reports of 30–60 min drift | Webview owns the data layer; position persistence and Media3 playback-resumption not implemented properly; regressions unnoticed |
| Headset rewind inaccuracy | app#1147, #1048, #622, #230, #578 | Auto-rewind-on-pause logic misinterprets rapid AVRCP pause/play pairs from certain headsets; seek not exact |
| No queueing | app#416 (top-requested since 2022), #1164; server#2214, #4007 | No server queue concept; official app never built one client-side |
| Weak offline | app#1167, #828, #290, #773, #613; users defecting to Smart Audiobook Player | Online-first architecture; downloads bolted on; progress reconciliation races (stale local overwrites server) |
| Android Auto | app#1491, #1081, #1309, #1059, #385, #1406 (closed "not planned") | Browse tree fed by the webview data layer → nothing works unless the phone app is open; downloads ignored in favor of streaming |
| Discoverability | server#2544 (full-text search "not planned"), #4619, #5000 | Server search is substring-only; no client-side index; no recommendations anywhere |

Plus community pain we adopt as requirements: sleep timer reliability (esp. with Cast), per-book speed memory, Chromecast at all (absent from official mobile app), bookmark math at speed ≠ 1x, WearOS, widgets, battery drain.

**Competitive gap nobody fills:** a *native* app with Android Auto + Chromecast + real queue + offline-first sync. Lissen (native) lacks queue/cast; Absorb (Flutter) is closest in features but non-native with the Flutter audio stack's documented seek/Auto limitations; everything else is further behind. Detail: [research/02-client-landscape.md](research/02-client-landscape.md).

## 3. Tech stack decision

**Kotlin + Jetpack Compose + Media3 (ExoPlayer), single native Android app, structured KMP-ready.** Full comparison: [research/04-tech-stack.md](research/04-tech-stack.md).

Why not cross-platform UI:
- Flutter's `audio_service` still sits on deprecated `MediaSessionCompat` APIs (not `MediaLibraryService`), and `just_audio` doesn't expose ExoPlayer exact seeking — both are direct hits on our two hardest requirements (Auto, headset seek accuracy).
- React Native's track-player Media3 rewrite shipped without Android Auto and remains immature.
- Media3 1.11 (Aug 2026) even extracts m4b chapter atoms natively now. The native stack keeps getting these wins for free.

How we keep iOS cheap anyway (**KMP-ready, not KMP-yet**):
- Module layout separates pure-Kotlin logic from Android: `:core:model`, `:core:api` (Ktor + kotlinx.serialization), `:core:db` (Room, which is KMP-capable), `:core:sync`, `:core:queue` — no Android framework types in these.
- `:playback` (Media3, 100% Android), `:feature:*` (Compose UI), `:app`.
- If iOS ever happens: add KMP targets to core modules, write AVPlayer + MPNowPlayingInfoCenter/CarPlay glue and SwiftUI or CMP UI. Bounded scope; precedent: Campfire.

Key libraries: Media3 ≥1.11 (session, exoplayer, cast, datasource-okhttp, ui-compose), Room (+FTS4/5), WorkManager, DataStore, Ktor client + OkHttp engine, Socket.IO client, Coil, Hilt (or kotlin-inject if we want KMP-pure DI), Horologist (Wear, later), Glance (widgets).

Reference codebases to study/borrow from (GPL-compatible): **Voice** (GPLv3 — playback service, sleep timer, chapters), Lissen (MIT — ABS API integration), official app (GPL-3.0 — API/sync semantics).

## 4. Architecture

### 4.1 Offline-first core (the foundation everything else stands on)

**The local Room database is the single source of truth for the UI. The server is a sync target, not a dependency.**

- Full library metadata mirror: paged sync of all items (minified payloads), series, authors, collections, playlists, progress, bookmarks → Room. Covers cached on disk.
- Delta updates via Socket.IO events (`item_updated`, `item_removed`, progress events); periodic reconciliation sweep via WorkManager; explicit pull-to-refresh.
- Every screen renders from Room instantly — cold app start shows the full library with zero network, whether online or not. This single decision fixes: offline UX, Android Auto cold start, app startup speed, and network-blip resilience.
- All mutations (progress, bookmarks, finished flags, playlist edits) write locally first, then enqueue to a durable **outbox** flushed by WorkManager with retry/backoff. Airplane-mode for a week = zero data loss.

### 4.2 Progress sync engine (fixing the #1 trust-killer)

Server is last-write-wins with zero conflict resolution, and the official app is known to push stale local progress over newer server progress (research/03, §Playback). Our rules:

1. **Pull-before-push**: on app/session start, fetch server progress for the item before any local push; if server `lastUpdate` > our last-known and position differs materially, adopt server position (with an undo affordance: "Moved to 12:34:56 from your other device — tap to go back").
2. **Session ledger**: every listening session (device, start/end positions, timestamps) is stored locally and uploaded via `/api/session/local(-all)`. The ledger doubles as user-visible listening history and a recovery tool ("jump back to where I was yesterday at 21:00" — beats everyone's accidental-seek recovery).
3. **Never regress silently**: an automated push may only move server progress forward in time-of-update; backwards jumps require explicit user action.
4. Progress persisted locally on every pause, seek, chapter change, 5s tick, and service teardown.

### 4.3 Playback engine (`:playback`)

Single `MediaLibraryService` hosting ExoPlayer — one brain serving app UI, notification, Android Auto, Wear, widgets, Cast:

- **State restoration**: `onPlaybackResumption()` returns last item + exact position from Room → headset play button resumes correct book at correct position after process death *and reboot*, without opening the app. Explicit regression tests for the known Media3 pitfalls (#493, #1070, #1933) and for notification persistence while paused (official app's #1800).
- **Exact seek**: direct-play original files whenever the device decodes them (byte-range = sample-accurate); `SeekParameters.EXACT`; HLS transcode only as codec fallback, with our own absolute-position bookkeeping.
- **Headset/AVRCP correctness**:
  - Media button events debounced/classified: pause+play pairs within a threshold are treated as a single pause glitch, never as pause→rewind→play (official app's #1048).
  - Smart rewind computed *once at resume* from wall-clock pause duration (0s under 30s pause, scaling to configurable max after hours), applied atomically with the play command, visible in UI ("rewound 15s").
  - Seek-back from any surface applies optimistically to the reported position instantly; serialized seek queue so rapid presses accumulate deterministically (3× skip-back = exactly 3×10s).
- **Chapters**: normalize server chapters (sort by `start` — server bug #3007), fall back to Media3 1.11 embedded-chapter extraction, fall back to synthetic chapters every N minutes. Chapter-relative and absolute time display everywhere.
- **Audio pipeline**: Sonic speed (0.5–3.5x, 0.05 steps), `SilenceSkippingAudioProcessor`, volume boost; **per-book speed memory** (local, since server has no field), optional per-narrator/per-library defaults.
- **Sleep timer**: end-of-chapter aware (re-arms correctly on chapter skip), shake-to-extend with adjustable sensitivity, fade-out, "rewind on wake" option, works identically while casting (timer lives app-side, not in the pipeline — fixes official #780/#2835 class of bugs).
- Pause (not just duck) on BT disconnect; configurable resume-on-reconnect per device class (car vs headphones — fixes #612).

### 4.4 Queue & continuation (`:core:queue`)

Entirely client-side (server has no queue; ShelfPlayer proved this pattern):

- Persistent ordered queue in Room: books, podcast episodes, mixed. "Play next" / "Add to queue" everywhere (long-press, swipe).
- **Auto-continuation providers** append virtually when queue runs dry: next book in series → next unfinished in playlist/collection → next podcast episode (user-configurable, off-able). End-of-book flow: mark finished, cover-art transition, optional pause-for-confirmation ("continue series in 10s").
- Queue surfaced in Android Auto ("Up next"), notification, widget.
- Optional cross-device durability: mirror to a server playlist (`▶ Up Next`), clearly best-effort.

### 4.5 Android Auto

- Browse tree served by `MediaLibraryService` **from Room** — works with the phone app never opened, offline or online, killing the official app's #1 Auto failure mode by construction.
- Tree: Continue Listening / Up Next (queue) / Downloaded / Series / Podcasts / Libraries — capped depths, big art, offline-badge.
- **Local-file-first source selection** at play time (never stream what's downloaded — fixes #385) — this rule lives in the playback layer, so it applies to every surface.
- Custom commands: chapter prev/next, speed cycling (fixes "not planned" #1406, #333).
- Progress from Auto sessions flows through the same sync engine (fixes #1491/#1059).
- Mitigate known Media3 idle-bind loop (#3158). Physical-headunit + DHU testing in CI checklist.

### 4.6 Discoverability

- **Local FTS index** (Room FTS4/5) over title, subtitle, author, narrator, series, description, genres, tags, **chapter titles**, podcast episode titles/descriptions → instant-as-you-type fuzzy full-text search, offline, across all libraries. Beats the server's substring search (declared "not planned" to improve upstream) without touching the server.
- Server `personalized` shelves + local computed shelves: *Next in series*, *Almost finished*, *Pick it back up* (abandoned 2+ weeks), *Short listens* (< 8h), *New in your favorite genres/authors*.
- Rich filter/sort: duration, narrator, year, progress state, downloaded, genre; series-first browsing with per-series progress bars.
- Listening stats (local ledger + server sessions): streaks, hours per week/genre, finished-per-year — respectfully, no gamification spam.

### 4.7 Downloads

- Media3 `DownloadService` + WorkManager constraints (Wi-Fi-only option, charging, storage caps), OkHttp datasource with **auth headers** (avoids expiring `?token=` URLs mid-download; refresh handled by our authenticator).
- Multi-file books stored as-is; optional server-side m4b merge trigger (`encode-m4b`) exposed as a power-user option.
- **Auto-download rules**: everything in queue; next N in an in-progress series; latest N episodes per subscribed podcast (fixes #613); auto-delete finished after X days (configurable).
- Robust for 40h/2.5GB books: resumable, verified sizes, no "Processing" purgatory (official #1167); download state visible and cancellable per item.

### 4.8 Auth & servers

- Full JWT access/refresh flow (v2.26+ model), proactive refresh, single-flight refresh mutex; sessions listed in-app with remote logout; socket re-auth on reconnect (access token only — v2.36 rule).
- OIDC via Custom Tabs with discovery; handle the known "no refresh token" re-add bug gracefully (auto re-auth flow instead of "remove your server").
- **Multi-server + multi-user scoping in the data model from day one** (every row keyed by serverId+userId) — UI can come later; retrofitting the schema would be brutal.
- Self-signed cert support, custom headers (reverse-proxy users), server URL fallbacks (LAN/WAN).

### 4.9 Casting

media3-cast `CastPlayer` with automatic local↔remote handoff; custom `MediaItemConverter` (cover, chapter metadata); direct-play URLs with token to the receiver; position sync and sleep timer stay app-side. (Official mobile app has no cast at all; Absorb is the only client that does.)

## 5. Server/upstream strategy

**No fork.** Everything above is achievable client-side against the current API — that's by design, and validated by iOS clients that already did it. Detail: [research/03-server-api.md](research/03-server-api.md).

Upstream contributions, in order of acceptance likelihood (external PRs merge in days–weeks when small and clean; big features sit for years; AI-slop PRs are explicitly unwelcome — ours will be genuine and reviewed):

1. **Bug fixes with clear repro**: chapter ordering (#3007/#4603), multi-file m4b chapter extraction (#3083). Cheap goodwill + fixes our data quality at the source.
2. **Small API additions**: per-item playback speed / free-form client-prefs blob on MediaProgress (3 open issues asking); progress-update guard (reject pushes with stale `lastUpdate` unless forced) — directly reduces the sync-race class of bugs for *every* client. Propose via discussion first, referencing our shipped client-side implementation as evidence.
3. **Long-shot proposals** (discussion-first, no dependency on acceptance): server-side queue entity; FTS search. We ship client-side regardless; if upstream ever lands, we adopt.

## 6. Product principles

- **Trust first**: never lose position, never lose a download, never silently jump. Every automatic correction is visible and undoable.
- **Instant**: cold start to playing < 2s; all lists render from local DB with zero spinners for cached data.
- **Privacy**: no analytics; no third-party network calls except the user's server (and Cast/Auto system services). Crash reporting is opt-in and off by default — the SDK is not initialised at all until consent, so an untouched install contacts nothing but the server. Amended 2026-08-15: this said "local-export only", which was overtaken by the need to diagnose crashes on a phone that is being daily-driven.
- **Longevity signals** (the community's stated #1 fear with new clients): clean architecture, real test suite, CI, documented contribution guide, conventional releases.
- Accessibility (TalkBack on all controls), Material 3 + dynamic color, generous typography for the player screen.

## 7. Milestones

**M0 — Walking skeleton (daily-drivable ASAP)**
Project scaffolding, CI, license; JWT auth vs own server; library metadata mirror into Room; browse + play (stream, direct-play) books & podcast episodes; position persistence + `onPlaybackResumption`; progress sync with pull-before-push. *Exit: I daily-drive it for streaming.*

**M1 — Playback excellence**
Chapters (normalize/embedded/synthetic); speed + silence skip + boost + per-book speed; smart rewind + AVRCP debounce; exact-seek + serialized seek queue; sleep timer (chapter-aware, shake); bookmarks; polished notification/Android 13 controls; BT disconnect pause. Hardware test matrix. *Exit: playback beats Smart Audiobook Player feel.*

**M2 — Offline-first**
Downloads (books + podcasts) with rules & storage management; offline playback + session ledger + local-session upload; outbox sync; FTS search; computed shelves & filters. *Exit: one-week airplane test loses nothing.*

**M3 — Auto & queue**
MediaLibraryService browse tree (cold-start, offline-aware, local-first); queue + play-next + series/podcast auto-continuation; Auto custom commands; podcast auto-download. *Exit: car use with phone never unlocked; series flows hands-free.*

**M4 — Delight**
Chromecast; widgets (Glance); stats; multi-server UI; OIDC; Material polish pass; optional WearOS (Horologist) and Android TV spikes.

**M5 — Release**
Play Store (closed → open testing), IzzyOnDroid, then F-Droid (reproducible builds); README/screenshots/website; issue templates; announce to r/audiobookshelf + community Discord.

Sequencing note: M0's architecture (Room-as-truth, outbox, serverId scoping, session ledger) is where the plan wins or loses — everything later leans on it. Podcasts ride along from M0 (browse/stream/progress), gaining parity features per milestone.

## 8. Engineering practices

- **License: GPL-3.0** (recommended) — matches ecosystem, F-Droid-compatible, lets us port code from Voice and the official app, prevents proprietary forks. (MIT/Apache-2 would forbid borrowing from Voice/official app.)
- All commits signed (1Password SSH signing, already enforced globally); conventional commits; trunk-based with PRs once public.
- CI (GitHub Actions): build, unit tests, lint/detekt, screenshot tests (Roborazzi), instrumented media tests on emulator matrix incl. **process-death and playback-resumption tests** as first-class CI citizens.
- Test priorities mirror the failure modes we're fixing: sync-conflict property tests, seek-accuracy tests against real m4b fixtures, Auto browse-tree contract tests (DHU), AVRCP event-sequence unit tests.
- Dev config (server URL, credentials) via gitignored `local.properties`/env — **no personal hostnames or secrets in the repo, issues, or published docs, ever**.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Media3 version-to-version regressions (resumption, Auto) | Pin + upgrade deliberately; regression suite for resumption/Auto before each bump |
| ABS auth/API changes (JWT model still evolving) | Version-gate features on `/status` server version; track releases; we already target the v2.36 model |
| Chapter/codec data quality in the wild | Defensive normalization + synthetic fallback; transcode fallback path always tested |
| Solo-maintainer burnout (community's top concern) | Small core kept boring and well-tested; docs enable contributors; scope discipline via this plan |
| BT headset diversity | Physical device matrix; debounce thresholds user-tunable as escape hatch |
| Upstream indifference | Nothing in the plan depends on upstream acceptance |

## 10. Open items

- ~~**App name**~~ → **lugu** (Estonian: "story"); package id `io.github.lightheaded.lugu`. Resolved 2026-08-14.
- ~~Confirm license choice~~ → **GPL-3.0-or-later**, in the repo. Resolved 2026-08-14.
- Hands-on API verification against the live server: socket event catalog, `/play` payload schema, HLS specifics (listed in research/03). *Still open* — M0 was built against the server source rather than a live capture; see [PROGRESS.md](PROGRESS.md).
