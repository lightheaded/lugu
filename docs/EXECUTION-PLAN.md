# lugu — execution plan (v1)

*2026-08-14. Hand-off plan: an executing agent/model should be able to build lugu from this document plus the referenced research. Read `docs/PLAN.md` and all of `docs/research/*` before writing code.*

## Context

Greenfield open-source Android client for Audiobookshelf, replacing the official Capacitor/webview app for Tom's daily use and eventually the community's. Strategy and evidence:

- `docs/PLAN.md` — product/architecture plan (approved 2026-08-14)
- `docs/research/01-official-app-complaints.md` — validated pain points with issue numbers
- `docs/research/02-client-landscape.md` — competitor gaps
- `docs/research/03-server-api.md` — ABS server API surface, auth model, sync races, upstream climate
- `docs/research/04-tech-stack.md` — stack decision evidence

### Locked decisions

| Decision | Value |
|---|---|
| Name | **lugu** (Estonian: "story/tale"); store listing "lugu for Audiobookshelf"; lowercase always |
| Package / appId | `io.github.lightheaded.lugu` |
| License | GPL-3.0 (gates borrowing code from Voice/official app; confirm at repo publication) |
| Stack | Kotlin + Jetpack Compose + Media3 (≥1.11), single native app, KMP-ready module layout |
| Source of truth | Local Room DB; server is a sync target |
| Scope | Audiobooks + podcasts from day one; Android-first, iOS only if cheap later |
| Repo | this repo → GitHub `lightheaded/lugu` (public; confirm with Tom before first push) |
| Privacy | No analytics, ever. **Opt-in crash reporting, off by default** (amended 2026-08-15; was "zero telemetry" — see [BACKLOG.md](BACKLOG.md#1-crash-reporting--sentry-opt-in-off-by-default)). No personal hostnames/secrets in repo, issues, commits, or docs — ever |
| Commits | All signed (1Password SSH signing, global git config; verify with `git log --show-signature`); conventional commits |

### Dev server access

Development runs against Tom's personal ABS instance (v2.36.0-era: JWT access ~1h + refresh 30d tokens; legacy tokens removed). Server URL and credentials go in gitignored `local.properties` (keys: `lugu.dev.serverUrl`, `lugu.dev.user`, `lugu.dev.pass`), surfaced to debug builds via `BuildConfig`. Where to obtain them is documented in Tom's private vault note for this project — ask Tom; never write hostnames or credentials into this repo.

---

## Phase 0 — Repo bootstrap

1. Mark naming resolved: `docs/PLAN.md` §10 (name → **lugu**, package id → `io.github.lightheaded.lugu`); update Tom's vault project note checkboxes.
2. `git init`; `.gitignore` (Android Studio template + `local.properties`, `.env*`); `LICENSE` (GPL-3.0); minimal `README.md` ("lugu — a native Android client for Audiobookshelf. Pre-alpha."); first signed commit: docs only (`docs: add project plan and research`).
3. Create GitHub repo `lightheaded/lugu` — **confirm with Tom before pushing** (push makes it public).
4. CI (GitHub Actions): `./gradlew build lint detekt testDebugUnitTest` on PR + main; ktlint/spotless from the start.

## Phase 1 — Scaffolding (start of M0)

Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`). Latest stable AGP/Kotlin at execution time; `minSdk 26`, `targetSdk` latest stable; Java 17 toolchain.

Modules — KMP-ready: `:core:*` contains no Android framework types (pure Kotlin + KMP-capable libs only):

```
:app                  — Compose activity, navigation, DI wiring
:core:model           — domain models (LibraryItem, Book, Podcast, Episode, Chapter, Progress, Session, QueueItem…)
:core:api             — Ktor client (OkHttp engine), ABS endpoints, DTOs (kotlinx.serialization), token refresh
:core:db              — Room (KMP-capable setup): entities, DAOs, FTS; every user-scoped row keyed (serverId, userId)
:core:sync            — library mirror, outbox, progress engine, socket client
:core:queue           — queue + auto-continuation providers
:playback             — Android-only: MediaLibraryService, ExoPlayer, seek/rewind logic, sleep timer
:feature:library      — browse/search/shelves UI
:feature:player       — player screen, chapters, bookmarks UI
:feature:settings     — servers, auth, playback prefs
:core:testing         — fixtures (incl. real small m4b test files), fakes
```

Key dependencies: media3 (exoplayer, session, ui-compose, datasource-okhttp; cast added in M4), room (+ktx, fts), ktor-client-okhttp, kotlinx-serialization, kotlinx-coroutines, hilt (in `:app`/`:playback`/features only — `:core:*` stays DI-framework-free, constructor injection), socket.io-client, coil-compose, workmanager, datastore, turbine + junit5 for tests, roborazzi (from M1).

## Phase 2 — M0: walking skeleton

*Exit: Tom daily-drives it for streaming.* Ordered, commit-sized tasks, each with tests:

1. **API verification spike** → `docs/research/05-api-live-notes.md`: capture real JSON against the live server for login, refresh, `/api/libraries`, `/api/libraries/:id/items?minified=1`, `POST /api/items/:id/play` (deviceInfo/playMethod fields), `/api/session/:id/sync`; enumerate socket events (grep server source for `io.emit` if needed). Research doc 03 lists exactly what's unverified.
2. **Auth** (`:core:api`): username/password login → access+refresh tokens in encrypted DataStore; single-flight proactive refresh (mutex; refresh under 5 min remaining); Ktor plugin attaching bearer; clean re-login flow on refresh failure. OIDC deferred to M4.
3. **Room schema v1** (`:core:db`): `Server`, `User`, `LibraryItemEntity` (core columns + full minified payload as JSON column for the long tail), `EpisodeEntity`, `ChapterEntity` (sorted-by-start invariant enforced at write — server bug #3007), `ProgressEntity`, `SessionLedgerEntity`, `OutboxEntity`, `QueueEntity` (schema now, used in M3).
4. **Library mirror** (`:core:sync`): initial paged full sync (always explicit `limit`, never 0), then socket deltas (`item_updated`/`item_removed`) + periodic WorkManager reconciliation + pull-to-refresh. Covers via Coil, disk cache keyed by item+updatedAt.
5. **Playback service** (`:playback`): `MediaLibraryService` + ExoPlayer; OkHttp datasource with auth header; direct-play first, HLS fallback from `/play` response; `SeekParameters.EXACT`; position persisted to Room on pause/seek/discontinuity/5s tick/teardown; `onPlaybackResumption()` restores from Room — test process death **and** reboot.
6. **Progress engine** (`:core:sync`): pull-before-push on session start (adopt newer server position with visible undo); periodic session sync while streaming; session-ledger rows; outbox flush with backoff. Property tests for the conflict rules (automatic pushes never regress progress).
7. **Minimal UI**: server login; library grid + Continue Listening shelf (`/personalized` + local); item detail; player screen (play/pause, seek bar, ±10/30s, global speed for now); podcast episode list. Material 3, dynamic color, correct dark theme.
8. **Manual QA checklist** → `docs/qa/m0.md`: kill app mid-play → headset play resumes at the right position; reboot → notification resumption; airplane-mode progress kept then synced; two-device pull-before-push scenario.

## Phase 3 — M1: playback excellence

*Exit: beats Smart Audiobook Player feel.*

- Chapters: normalized server chapters → Media3 1.11 embedded extraction fallback → synthetic N-minute fallback; prev/next; chapter-relative + absolute time display.
- Pipeline: Sonic speed 0.5–3.5× (0.05 steps) with **per-book memory** (client-side; server has no field), `SilenceSkippingAudioProcessor` toggle, volume boost.
- **Smart rewind**: computed once at resume from wall-clock pause duration (0 under 30 s → configurable max), applied atomically with play, shown in UI.
- **AVRCP debounce**: classify media-button sequences; pause+play inside threshold = pause glitch, never a rewind trigger. Regression-test with synthesized KeyEvent sequences — this is the flagship headset gripe (official app #1048).
- Serialized seek queue: rapid presses accumulate deterministically; optimistic position updates.
- Sleep timer: durations + end-of-chapter (re-arms across chapter skips), shake-to-extend with sensitivity setting, fade-out, rewind-on-wake. Reference Voice's implementation (GPL-compatible).
- Bookmarks (server-synced, speed-corrected display); BT-disconnect pause (not duck) with per-device-class resume; polished Media3 notification with custom seek actions.
- Physical headset/car test matrix doc; debounce threshold user-tunable as escape hatch.

## Phase 4 — M2: offline-first

*Exit: a week in airplane mode loses nothing.*

- Downloads: Media3 `DownloadService` + WorkManager constraints (Wi-Fi-only, charging, storage cap); auth via headers, never `?token=` URLs; resumable, size-verified; per-item progress UI; proven on 40 h / 2.5 GB books.
- Auto-download rules: queue contents, next N in an in-progress series, latest N episodes per podcast; auto-delete finished after X days.
- Offline playback → session ledger → `/api/session/local-all` upload on reconnect (batched, idempotent via client UUIDs).
- FTS: Room FTS table over title/subtitle/author/narrator/series/description/genres/tags/chapter titles/episode metadata; instant-as-you-type search.
- Computed shelves: Next in series, Almost finished (>90 %), Pick it back up (14 d stale), Short listens; filter/sort UI (duration, narrator, year, progress, downloaded).

## Phase 5 — M3: Android Auto + queue

*Exit: car use with the phone never unlocked.*

- Auto browse tree served from Room (Continue / Up Next / Downloaded / Series / Podcasts / Libraries): cold-start capable, offline-aware badges; register both Media3 and legacy `MediaBrowserService` intent filters; mitigate the Media3 idle-bind loop (androidx/media#3158); DHU test procedure → `docs/qa/auto.md`.
- Local-file-first source selection in the playback layer — no surface ever streams what's downloaded.
- Queue: persistent mixed queue (Room); Play next / Add to queue everywhere; drag-reorder screen; auto-continuation providers (series → playlist/collection → podcast latest), each user-toggleable; end-of-book flow with optional confirmation pause; optional mirror to a server playlist "▶ Up Next".
- Auto custom commands: chapter prev/next, speed cycle.
- Podcast auto-download + new-episode notifications.

## Phase 6 — M4/M5 (summarized; re-plan on arrival)

- **M4**: Chromecast (`media3-cast`, custom `MediaItemConverter`, sleep timer/position app-side), Glance widgets, stats screens (session ledger), multi-server UI, OIDC via Custom Tabs, polish pass, WearOS/TV spikes.
- **M5**: Play Store (closed → open testing), IzzyOnDroid, F-Droid reproducible builds, screenshots/website/issue templates, community announcement. Upstream PRs per `PLAN.md` §5 (chapter-order fix first).

## Verification (continuous)

- CI green on every PR: build, lint, detekt, unit tests.
- First-class regression tests mirroring the failure modes lugu exists to fix: process-death/resumption (instrumented: `adb shell am kill` + media-button event), AVRCP-sequence unit tests, sync-conflict property tests, seek accuracy against real m4b fixtures in `:core:testing`.
- Manual milestone QA checklists in `docs/qa/`; Tom daily-drives from M0 exit onward against his live server.
- Each milestone's exit criterion (`PLAN.md` §7) verified before moving on.

## Guardrails for the executing agent

- Read `docs/research/*` and `docs/PLAN.md` first — they carry the endpoint details, known server bugs (chapter ordering, `limit=0` footgun, sync races) and Media3 pitfall issue numbers this plan assumes.
- Never commit secrets or personal hostnames; `local.properties` only. Verify the first commit is signed before proceeding; conventional commit messages throughout.
- lowercase `lugu` everywhere (repo, package, UI wordmark); "lugu for Audiobookshelf" only in store-listing contexts.
- No analytics dependencies, ever. Crash reporting is the one exception, and only on the terms in [BACKLOG.md](BACKLOG.md#1-crash-reporting--sentry-opt-in-off-by-default): opt-in, off by default, nothing initialised before consent. A default-on reporter contradicts a claim the README makes against the official app.
- When ABS server behavior is ambiguous, verify against the live dev server and record findings in `docs/research/05-api-live-notes.md`.
