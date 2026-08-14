# Progress log

## 2026-08-14 — M0 walking skeleton

Phase 0–2 of [EXECUTION-PLAN.md](EXECUTION-PLAN.md) implemented and building.

### Done

- Repo bootstrap: git, GPL-3.0, `.gitignore`, README, CI (build + test + lint, signed
  release APK published as a rolling `latest` GitHub release).
- Gradle scaffolding: version catalog, nine modules, `minSdk 26`, Java 17 target.
- `:core:model` — domain types plus the two rule sets that carry the plan's promises,
  both pure and unit-tested: `ProgressConflictResolver` (pull-before-push, forward-only
  automatic pushes) and `Chapters` (sort-by-start normalisation, synthetic fallback).
- `:core:api` — Ktor client for the v2.26+ JWT model: login with `x-return-tokens`,
  `/auth/refresh` via `x-refresh-token`, proactive single-flight refresh, one 401 retry.
  DTOs are tolerant of unknown keys by design.
- `:core:db` — Room schema v1, every user-scoped row keyed `(serverId, userId)`.
  Chapters can only be written through `replaceForItem`, which enforces sort-by-start.
- `:core:sync` — library mirror (paged, explicit `limit`, stale sweep), progress engine,
  session ledger, superseding outbox, WorkManager drain and reconcile workers.
- `:playback` — `MediaLibraryService` + ExoPlayer, `SeekParameters.EXACT`, OkHttp
  data source with header auth, absolute-vs-track timing, `onPlaybackResumption` rebuilt
  from Room, position persisted on pause/seek/transition/5 s tick/teardown.
- `:feature:*` and `:app` — login, library grid with continue-listening and search,
  item detail with podcast episodes, player screen with the jump-undo banner, mini player.

### Deviations from the plan, and why

- **`:core:queue` and `:core:testing` not created.** `QueueEntity` is in schema v1 as the
  plan requires, so M3 needs no migration; the module itself would be dead weight until
  then. Test fixtures live in module-local test source sets for now.
- **`:core:db` and `:core:sync` are Android libraries, not pure Kotlin.** Room's standard
  setup and WorkManager need Android artifacts. No Android types appear in their public
  API, so the KMP path is unchanged in substance. `:core:model` and `:core:api` are pure
  Kotlin as specified (the JWT expiry parser is hand-rolled to avoid `java.util.Base64`).
- **AGP 9 / Kotlin 2.4 / compileSdk 37.** Hilt 2.60 requires AGP 9, and AGP 9 rejects the
  standalone `kotlin-android` plugin in favour of its built-in Kotlin support; the
  androidx Hilt artifacts then require compileSdk 37. This chain is forced, not chosen.
- **R8 off for release builds.** Media3/Room/Hilt keep-rules are unproven here, and a
  first testable alpha matters more than APK size. Turn on with M1.
- **API verification spike (task 1) not run against a live server.** No server URL or
  credentials were available in this session. The endpoint shapes were instead verified
  against the Audiobookshelf server source on `master` — login/refresh token handling,
  `AudioTrack.contentUrl` (`/api/items/:id/file/:ino`), `session/local-all` body shape
  (`{deviceInfo, sessions}`), and the sync payload fields. `docs/research/05-api-live-notes.md`
  still needs writing from a real session.

## 2026-08-14 (later) — first M1 slice, and a real bug from first contact

Tom installed 0.1.0 and could not sign in. The server turned out to be fine; the
diagnosis and the two defects it exposed are worth recording.

- **Not our bug, but our fault how it read.** `the internal host` had a DNS misconfiguration
  while the proxy configuration only listed the internal entrypoints, so the public
  proxy answered every path with a plain `404 page not found`. lugu printed that body
  on the sign-in screen. Two fixes: sign-in now probes `/status` first so a wrong
  address can never present as wrong credentials, and the probe checks
  `app == "audiobookshelf"` rather than merely parsing — every field of
  `ServerStatusDto` has a default, so any JSON at all used to pass.
- **First live capture** recorded in [research/05-api-live-notes.md](research/05-api-live-notes.md):
  `/status` on 2.36.0, and confirmation that `POST /login` and `POST /auth/refresh`
  really are at the server root as the source implied.
- `scripts/capture-api.sh` captures the authenticated endpoints too, reading
  credentials from the gitignored `local.properties` and redacting the server address,
  ids and tokens from its output. Running it is the last piece of Phase 2 task 1.

### M1 started: smart rewind and AVRCP classification

Both pure and fully unit-tested, which is the point — they are the fixes for the
headset complaints and they cannot be tested by hand reliably.

- `SmartRewind` computes the rewind **once at resume** from the real pause duration.
  Rewinding at pause time is what makes a position drift backwards on every pause
  (app #1147, #622). Nothing under 30 s, logarithmic up to a configurable maximum,
  and the amount is announced in the UI — an invisible automatic correction is
  indistinguishable from a bug.
- `MediaButtonClassifier` folds a PAUSE followed within 500 ms by a PLAY into a single
  `PauseGlitch`, so a stuttering headset never arms a rewind (app #1048). A property
  test drives fifty glitch pairs through it and asserts the total rewind is zero.

Still outstanding for M1: chapters UI, sleep timer, bookmarks, per-book speed,
serialized seek queue, silence skipping.

### Next

1. Run the M0 QA checklist ([qa/m0.md](qa/m0.md)) against the live server — especially
   process death, reboot resumption, and the two-device conflict case.
2. Record real API captures in `docs/research/05-api-live-notes.md`.
3. Socket.IO delta updates (M0 task 4 is currently poll-and-sweep only).
4. Then M1: chapters UI, smart rewind, AVRCP debounce, sleep timer, per-book speed.
