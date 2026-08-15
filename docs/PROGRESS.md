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

## 2026-08-14 (evening) — M0 confirmed working on hardware

Tom signed in and played from his phone. The login failure was two stacked network
faults, neither in the app — both misconfigurations on the network path between the
phone and the server, both since fixed.

One real crash came out of first contact and is fixed: **podcasts crashed the
continue-listening shelf**. The query joined items to progress on `libraryItemId`
alone — a book has one progress row, a podcast has one per episode, so a podcast
returned once per episode played. The shelf keys by item id and Compose throws on a
duplicate key, so the app died the moment a podcast library finished syncing. Fixed by
grouping per item and ordering by `MAX(lastUpdateMs)`; four Room tests cover it and two
failed before the change.

Lesson worth keeping: **anything that fans out a join is a crash risk, not a display
bug**, because the UI keys lists by id. The repository now dedupes on the way out too.

## 2026-08-14 (later) — first M1 slice, and a real bug from first contact

Tom installed 0.1.0 and could not sign in. The server turned out to be fine; the
diagnosis and the two defects it exposed are worth recording.

- **Not our bug, but our fault how it read.** A proxy in front of the dev server
  answered every path with a plain `404 page not found`, and lugu printed that body
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

### M1 continued: chapters, per-book speed, serialized seeking

Confirmed working on Tom's device first, then extended:

- **Chapter navigation** with the behaviour people expect without naming it — once a
  few seconds into a chapter, "previous" restarts *it*; press again straight away and
  you step back one. Jumping back immediately would make restarting a chapter
  impossible. Player shows "Chapter 3 of 24 · 4:12 / 18:30", chapter-relative time
  being how listeners actually think about place.
- **Serialized seek queue.** Presses accumulate into one pending target instead of each
  reading the player's position, so three quick taps of -10s move exactly 30s rather
  than racing the player's unfinished seek. The display updates optimistically and one
  seek is issued once presses settle.
- **Per-book speed memory** in DataStore. The server has no field for it and three open
  requests asking for one (#3485, #911, #1980). DataStore rather than Room on purpose:
  it is not synced data and nothing joins against it, so a Room column would have bought
  nothing and cost a schema migration on an already-installed device. Setting a book
  back to 1× forgets the override rather than pinning it.

### Data loss from the notification — found in user testing, fixed

Tom pressed rewind on the notification and it **reset the book to zero**, with no way
back. Two separate defects, both now fixed:

1. **The button did the wrong thing.** Media3's stock transport is built for music: on a
   single-file audiobook (one MediaItem, forty hours) `seekToPrevious()` seeks to
   position zero, and on a multi-file book "next" jumps to the next *file*, which is not
   a chapter. The lock screen therefore offered a one-tap button that destroyed the
   listener's place — and the resulting position was persisted and synced like any
   ordinary seek. `ChapterAwarePlayer` now wraps the player for the session and remaps
   previous/next to chapter navigation, falling back to a plain skip.
2. **There was no way back.** The database only ever held *current* progress, so an
   accidental jump was permanent. Schema v2 adds `position_history`: every move of 45s
   or more is recorded, a jump over two minutes offers an immediate undo, and the player
   has a "Where you were" sheet to restore any recent position.

The migration is additive and its output is asserted against the schema Room generates
for the same entity — a hand-written DDL that disagrees crashes the app on upgrade,
after the user has already installed over a working version. That test compares parsed
column and index metadata rather than raw CREATE text, because SQLite ignores
whitespace and a textual comparison fails on formatting while passing real faults.

Still outstanding for M1 and everywhere else: see [BACKLOG.md](BACKLOG.md), which
collects every deliberately-unfinished item with the reason it was left.

## 2026-08-15 — M2: offline-first

Phase 4 of [EXECUTION-PLAN.md](EXECUTION-PLAN.md). Downloads, offline playback,
full-text search and computed shelves.

### The live API capture, run at last

Credentials arrived mid-session, so the authenticated capture — open since M0, and the
last **UNVERIFIED** marker on the payload shapes — finally ran against the real 2.36.0
server. It immediately contradicted two assumptions the download code had already been
written on:

1. **`media.tracks`, not `media.audioFiles`, is the playable timeline.** The server has
   already computed `startOffset` on `tracks` and has already dropped files flagged
   `exclude`. Building a manifest from `audioFiles` meant re-deriving offsets by hand
   *and* risking a file the server refuses to play spliced into the middle of a book —
   which would have surfaced as a stretch of wrong audio partway through, with every
   later offset shifted. Fixed before it shipped.
2. **`metadata.series` is empty; the only series information is a string.** Series
   membership arrives as `"Example Series #10"` and nothing else. Measured over the
   real library: about a third of items have a series name and two-thirds of those
   carry a parseable number.

Both are written up in [research/05-api-live-notes.md](research/05-api-live-notes.md).
The capture script also had to be fixed first — it was redacting `contentUrl`, the single
shape it most needed to confirm — and now closes the session it opens, because it runs
against a real account and an abandoned session shows up as a book someone never played.

### Downloads

- **`:core:download`**, built on Media3's `DownloadService` and a `SimpleCache`. The hard
  parts — resuming a part-finished file after process death, honouring network and
  charging requirements, restarting after reboot — are already solved there, and a
  two-gigabyte book that restarts because the phone slept is the failure that matters.
- **The evictor is a no-op, deliberately.** An LRU evictor would silently delete a book
  someone downloaded on purpose to make room for one they merely streamed. Space is
  bounded by refusing new downloads over the cap instead, which is visible and
  actionable. Playback reads the cache **read-only**, so streaming never half-fills it
  with fragments that count against the cap and look like a download.
- **A downloaded item never opens a play session at all.** The manifest written at
  download time carries the URLs, offsets and cache keys, so playback resolves entirely
  from Room. That is what makes airplane mode work, and it also makes pressing play on a
  downloaded book instant rather than a round trip that could only return URLs already
  held. Offline listening lands in the ledger as a local session and replays through
  `/api/session/local-all` on reconnect.
- **Cached bytes are keyed by item and track, not by URL**, so moving a server to a new
  address does not orphan every download on the phone.

### Search and shelves

- **FTS4 index** over title, subtitle, author, narrator, series and description, written
  wherever items are written. Not an external-content table: Room does not generate the
  triggers that would keep one in sync, and a stale index returns yesterday's library.
- The query sanitiser is the load-bearing part. `MATCH` is a query *language*, and the
  search box runs on every keystroke — so half-typed input is the normal case, not the
  edge case. Anything unexpressible falls back to a substring scan rather than throwing.
- **Six computed shelves**, all local SQL, all working with no network: Continue,
  Next in series, Almost finished, Downloaded, Pick it back up, Short listens.

### A design error the tests caught

"Next in series" grouped items by `seriesName` — which is `"Example Series #2"`, *including the
number*, so two books in one series never compared equal and the shelf was always empty.
The fix is a separate `seriesTitle` column alongside `seriesSequence`: one identifies
the series, the other orders it. Ordering by the name string would have been worse than
empty — a real library holds "#19", "#21" and "#29" in one long series, and text ordering
puts "#10" before "#2", so the shelf would have confidently recommended the wrong volume.

Items whose sequence will not parse are **left out rather than guessed at**. That is
about a third of the series entries here, and the alternative is a spoiler.

### Also

- **Searchable settings** (Tom's feedback). Each setting is declared as an entry with its
  own synonyms, so adding one makes it findable automatically — an index maintained
  separately from the UI goes stale the first time someone forgets, and a search that
  silently omits a setting is worse than no search. Searching "data" or "mobile" finds
  Wi-Fi-only; "rewind" finds skip-back; "2x" finds speed.
- Downloads settings: Wi-Fi only, only while charging, storage cap, and an opt-in
  reclaim of finished downloads that is off by default and deletes files only, never
  progress or history.
- Schema v3, additive, with the FTS index backfilled in the migration so search works on
  the train rather than after the next sync. Both non-idempotent statements (ALTER TABLE,
  and an FTS insert with no unique constraint to conflict on) are guarded by hand and
  tested by running the migration twice.

### Not done in this phase

- **Auto-download rules** (next N in a series, latest N podcast episodes) — the manual
  path and its storage accounting needed to be right first.
- **Nothing here has run on hardware yet.** Downloads, offline playback and the cache
  were built and unit-tested but not driven on a real device against a real book; that
  and the M0 QA checklist remain the largest untested surface. See [BACKLOG.md](BACKLOG.md).

### The notification jumped ten minutes — found in user testing, fixed

Reported while M2 was being committed. The notification's side buttons were remapped to
chapter navigation *unconditionally*, and a book with no real chapters is given synthetic
ten-minute chapters — so one tap moved ten minutes. On a multi-file book the step was a
whole file instead. Either way the control someone reaches for to catch a missed sentence
was throwing them minutes out of place.

They now skip by the configured seconds unless chapter buttons were explicitly asked for.
The in-app chapter buttons are unaffected: they go through `PlaybackConnection`, not
through the system transport, so an explicit chapter button still navigates chapters.

The earlier design tried to control this by *withdrawing* the previous/next commands when
chapter buttons were not wanted, expecting the system to offer seek buttons instead. It
does not — Media3's default notification builds its layout from previous / play-pause /
next and has no seek button to fall back to, so withdrawing those commands removes the
buttons rather than changing them. And available commands are read when a controller
connects, with nothing here firing a change when a setting moves, so the withdrawal was
not reliably seen at all. Keeping the commands advertised and switching what they *do* is
deterministic and needs no cooperation from the notification provider.

`ChapterAwarePlayer` now has the tests it should have had. It is the class that stands
between the notification and someone's place in a book, and it has now caused two
separate user-visible faults — a rewind that reset a forty-hour book to zero, and this.
Both were behaviours no type checker could object to. Eight tests, including one that
reproduces this bug exactly and one that pins the original data loss.

### The "jumped" notice was still reflowing the screen

Also reported during the same session. The earlier round moved the *rewound* notice to a
snackbar but left the jump banner as inline `Column` content — so it went on pushing the
cover art and the whole transport down as it appeared and back up as it went. Announcing
a correction was a bigger interruption than the correction.

Both notices are now overlays, and both auto-dismiss. They use
`SnackbarDuration.Indefinite` with an explicit timeout rather than a built-in duration,
because Material offers only four and ten seconds, neither is configurable, and a notice
carrying an Undo has to stay up long enough to read a timestamp and decide. Ten seconds
by default, settable under Settings → Notices. Letting it time out keeps the new
position — exactly what the old "Keep" button did — so that button is gone and Undo is
the snackbar's action.

A lesson worth keeping: *"make it a toast"* was applied to the notice that prompted the
feedback and not to its twin. When feedback names a pattern, the fix is the pattern
everywhere it applies, not the one instance that was pointed at.

## 2026-08-15 (later) — downloads on hardware, and M3

### The first real download, and the two bugs it found

Tom downloaded a 629 MB book on his phone. It worked, and it surfaced two faults
neither of which the unit tests could have caught, because both were about numbers the
server supplies.

**The storage cap refused podcast episodes on an 8 GB allowance.** The estimate preferred
the item's own `media.size`, which on a podcast is the *entire feed* — a capture from the
live server settled it: tapping one 56 MB episode of a 327-episode show charged 18.36 GB.
Both of the two largest feeds in the library, 18.36 GB and 8.81 GB, were undownloadable
an episode at a time. On a book the same field is wrong more quietly, counting the ebook
and any file flagged `exclude`.

Every track and audio file carries `metadata.size` — its own byte count — so the manifest
now records a size per track and the estimate is their sum: exactly the files about to be
fetched. Two things went in alongside, because the bug was reported the way it was for a
reason. The refusal states its arithmetic now rather than asserting the allowance is
full; a refusal that shows its numbers reports its own bug, and the old one just looked
like a setting chosen badly. And the storage readout reads the same number the check
does, instead of summing Room rows while the check read the cache — two numbers for one
quantity is how a correct refusal gets reported as a lie.

**Progress was invisible in the app** while the notification showed it moving.
`DownloadManager.Listener` fires when a download changes *state* and never once in
between, so the row written at "queued" sat at 0% until the file finished; the
notification looked fine because `DownloadService` polls on its own timer. The engine
polls too now, but only while a file is actually downloading.

### M3: the queue

The queue table has been in the schema since v1, unused. Play next and Add to queue on
every book and episode, a reorderable Up next screen, and end-of-book continuation that
runs in the playback service rather than the UI — the moment it matters is a book
finishing in a car, where no UI exists.

The queue wins over any rule: an entry there is an instruction, and an instruction
outranks a guess. Only when it is empty do the continuation rules run — the next
unstarted volume of a series, or the next episode published — and what they produce is
marked a suggestion, announced with its reason, and subject to "ask before starting
something new".

Queue rows store identity only and join against the mirror for display, so a rename on
the server cannot leave a stale title and the screen works offline. Two things the tests
caught: renumbering sorted by the *old* positions, which silently undid every drag; and
re-queuing something already queued now moves it rather than adding a second copy.

### M3: Android Auto

A browse tree served entirely from Room — Continue, Up next, Downloaded, Series,
Podcasts, Libraries. A car connects the moment the phone is plugged in, often before a
sync has run and sometimes in a garage with no signal, so a tree that needed the server
would be empty exactly when it is needed most. Categories with nothing in them are left
out rather than shown empty.

Tapping a row resolves the whole session from an id alone, through the same path the
phone uses, so a downloaded book starts instantly with no connection. Voice search uses
the same FTS index the search box does, from the car and from Assistant.

Chapter previous/next and a speed cycle are session commands in the car's transport,
because a car's transport knows nothing about either and both are what an audiobook
listener reaches for. Chapter buttons fall back to the configured skip on an unchaptered
book, so they never appear broken.

Never returning an error from the browse root is deliberate — that is what puts Android
Auto into a bind-retry loop (androidx/media#3158). Signed out, the root is a valid tree
holding one row saying to sign in on the phone.

`MissingMediaBrowserServiceIntentFilter` is suppressed in the app manifest with a note.
The check reads that file, and the service is declared in `:playback`, where it already
advertises the action; the merged manifest carries it.

### M3: downloading ahead, and new episodes

Auto-download rules — the queue, the next N in a series being read, the latest N
episodes of a podcast being listened to — each separately switchable and all off by
default. This is the app spending someone's storage on a prediction, and every prediction
here is about something already chosen. The worker runs on unmetered networks only,
regardless of the Wi-Fi-only setting: that setting governs downloads someone asked for,
and lugu deciding on its own to spend mobile data is not a thing it should do.

Library sync mirrors items but not episodes, so podcasts are now refreshed in the
background too — only ones being listened to, since Audiobookshelf has no subscription
concept to read and pulling every episode of every podcast on a timer answers a question
nobody asked. New episodes are found by comparing ids before and after rather than by
publish date, so a back-catalogue episode added late still counts as new and a clock
disagreeing with the server cannot make it wrong. One quiet notification for the batch,
off by default.

### Deviation from the plan

`:core:queue` was still not created. `QueueRepository` sits in `:core:sync` with the
other repositories and the DAO logic in `:core:db` with the other DAOs; a module holding
one repository that depends on `:core:sync` anyway would be structure without substance.
The plan's actual requirement — no migration for M3 — held: `QueueEntity` was already in
schema v1.

### Next

1. Run [qa/auto.md](qa/auto.md) in the DHU, then in a car. Nothing in M3 has been near a
   real head unit.
2. Daily-drive M2: download a long book, go offline, confirm it plays and that the
   session replays on reconnect.
3. Run the M0 QA checklist ([qa/m0.md](qa/m0.md)) — process death and reboot resumption
   are still the promises never exercised on hardware.
4. Socket.IO delta updates.
