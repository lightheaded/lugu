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

## 2026-08-15 (later still) — the shape of the app, the rest of M1, and finding out why playback stops

A large pass that clears the whole of the navigation feedback from daily driving, finishes
the M1 playback features that were designed and never built, and answers a new report —
*playback stops occasionally and I cannot tell whether it crashed* — with a record rather
than a guess.

### Home, the library, and a filter that was not filtering

The single screen was doing two jobs: the computed shelves answer "what should I play now"
and the grid answers "show me everything". They are now two tabs of one destination, with
the mini player above the tab bar so it survives the switch, and a one-tap resume for the
most recent thing at the top of Home — which is the answer to the complaint every client
of this server attracts, that getting back to your book takes five taps.

Underneath the taste question was a real bug. The grid was scoped by the selected library
while every shelf above it was scoped to the account, so picking the audiobook library
filtered the grid and left podcasts on the shelves directly above it. Every shelf query now
takes a library id and the caller has to pass one or explicitly pass none. Whether shelves
*should* span libraries is a genuine question — "continue listening" arguably does — so it
is a setting, and when they are spanning everything the screen says so rather than leaving
it to be inferred.

The selection itself moved into `LibraryPrefs`. Two screens depending on a value that one
of them owns is how they end up disagreeing for a frame, and the visible symptom of that is
shelves that span every library and then snap.

A media type can now be switched off entirely. It is filtered at `observeLibraries`, the
single place every surface reads from, so it reaches the tabs, the shelves, search and the
car's browse tree by construction rather than by being remembered in four places. Nothing
is deleted and syncing carries on, so it is instantly reversible.

### One control for every long list

Search, ordering and a five-way filter, shared by the episode list, the library grid and
the downloads screen, with a selection mode shared by the same three. Both are built once
in `:core:model` and `ListControlsUi` rather than three times, because three
implementations disagree — quietly, about what "in progress" means, which is the kind of
inconsistency nobody reports and everybody notices.

Episode rows finally say what an episode is: `S2 E14 · 12 Mar · 48m`, with recent dates as
"Today" and "3 days ago". None of that needed fetching or migrating; it was already in Room
and already reaching the screen, and the row simply did not draw it.

Paging was deliberately not added to the episode list. `LazyColumn` already composes only
what is visible, so a thousand episodes was never the rendering problem it looked like —
the problem was having no way to narrow the list.

While fixing the shelves, a bug nobody had reported: a podcast never showed progress on the
continue shelf, because progress is per episode and the shelf read the item-level row a
podcast does not have. It now falls back to the most recently updated episode, which is
also the episode the resume affordance plays.

### The rest of M1

Bookmarks, synced to the server and written locally first, so one made in a tunnel is a
bookmark rather than a failed button. The server addresses a bookmark by `(item, time)`
with no id of its own, which makes the *time* the identity — so it is rounded to whole
seconds at the boundary, and a delete leaves a tombstone rather than a hole the next pull
would fill back in. Displayed times are the audio position, because that is what the
scrubber shows and the only figure that stays put when the speed changes.

Also: a chapter list to jump directly rather than stepping through; silence skipping;
volume boost through the platform loudness enhancer; pause and resume on route changes with
the car and headphones as separate switches, because a car connecting usually means the
engine started and headphones reconnecting usually does not mean carry on right now; and
the sleep timer options that had been designed and stored but never implemented — fade,
rewind-on-wake, and shake-to-extend with the accelerometer registered only while the timer
is armed, since a permanently registered sensor is a battery bug.

### Why playback stops

Reported this session, and the honest answer was that we could not have known. The process
being reclaimed, audio focus lost, an unsuitable output after a Bluetooth switch, a network
stall leaving the player idle with nothing retrying, a crash, and the sleep timer doing
exactly what it was told all look identical from outside.

So the first thing built was the record. `PlaybackDiary` writes starts, stops, errors,
suppression reasons and the service's lifecycle to a file — a file specifically, because
the case with the least evidence is the process being killed, and an unexplained gap
followed by a fresh "process started" line *is* the diagnosis. Settings → Diagnostics reads
it back with an interpreted summary.

It is local and always on. Crash reporting is opt-in and off by default and will stay that
way, so a diagnosis that only worked for people who had switched on telemetry would be no
diagnosis at all. When crash reporting *is* on, the recent entries ride along with an
outgoing crash as breadcrumbs — never on their own.

Upstream has this complaint open twice, and the second one is instructive: app#204,
"playback closes when connecting to car Bluetooth", has been open since 2022 marked
*unable to reproduce*. That is what happens when nobody can see what the app was doing.

### In-app feedback, and R8

The post-crash prompt and feedback screen from the backlog are built, with the "exactly
what gets sent" section above the Send button — the element that makes the opt-in claim
credible rather than decorative.

R8 is on for release builds with hand-written keep rules. This is the change in this pass
with the least evidence behind it: a clean `assembleRelease` proves only that nothing is
missing at compile time, and every path R8 can break — Room's generated code, Hilt's graph,
kotlinx-serialization's reflectively resolved serializers, Media3's service — fails at
runtime and only in a release build. A device pass on a signed release APK is owed.

### Next

1. **Read the diary after a real stop.** It is a tool that has not been used yet, not a
   tool that is known to work.
2. **A release-build device pass**, now that R8 is on. Sign in, stream, play offline, open
   Android Auto, change a setting.
3. Run [qa/auto.md](qa/auto.md) in the DHU, then in a car.
4. The upstream issue review, and whichever of its proposals are accepted.

## 2026-08-16 — the controls, the pages behind the links, and a library you can move through

Three pieces that had been open a long time, none of them new work in the sense of new
capability — all of them finishing something started earlier and left visibly half-done.

### The transport controls, at last

The oldest item on the feedback list. The notification's side buttons have done the right
thing for a while — the configured skip, rather than the chapter navigation that once
moved ten minutes per tap — but they still wore Media3's previous and next icons, in
Media3's order, because the default notification provider builds previous / play-pause /
next and offers no seek button to select.

A custom layout replaces the provider's choice outright. The buttons come from the setting,
in the order set there, with icons matching the configured skip where one exists and the
generic icon where none does: a button labelled 30 that moves 15 is worse than one with no
number on it. The layout is pushed to live sessions when the setting changes, which is the
part the previous attempt got wrong — available commands are read when a controller
connects, and a notification that only picks up a preference at the next cold start reads
as a setting that does nothing.

One thing on that list was attempted and withdrawn: a chapter-scoped progress bar in the
notification (upstream app#239). The bar is not part of the notification — it is drawn
from the platform media session's playback state, one position and duration read by the
notification, the player screen, the scrubber and a car's seek bar alike, with no
per-controller value. Reporting chapter-relative numbers there means a second, disagreeing
notion of where the book is, in exactly the place `AbsoluteTiming` and `ChapterAwarePlayer`
exist to keep single — which is how a resumed book starts in the wrong chapter. The setting
was written, found unshippable, and removed rather than left reading from nothing. The
chapter title goes in the notification text instead, where it costs nothing.

Headset buttons became configurable at the same time. `MediaButtonClassifier` has existed
since M1, resolving the pause-then-play glitch that causes phantom rewinds, but nothing
ever asked what the buttons should *do*. Now next and previous can each be a skip, a
chapter jump, the next item, or nothing. Never `seekToPrevious()`, which on a single-file
book seeks to zero — the incident that started this whole line of work.

### Author, series and narrator pages

*Links should be consistent everywhere* was among the first things asked for, and until now
the author and narrator on an item page were plain text, because linking to a dead end is
worse than not linking.

All three pages are computed from the local mirror. The server has an author page and a
series page in its own web client and no API that hands either to a client, so grouping
locally is both the only option and the faster one — and it means these work with the
network off, like everything else that reads from Room.

The series page carries the decision worth recording: it groups on the parsed
`seriesTitle` and orders by the parsed `seriesSequence`, never on the `seriesName` the
server sends. That string has the number baked into it, so two books in one series do not
compare equal — grouping by it groups nothing, and ordering by it puts "#10" before "#2".
Both columns were added in M2 for the "Next in series" shelf; this is the second thing they
have paid for.

Grouping is on the stored string, which means an author credited two ways is two authors
here. That is a real limitation and the alternative is guessing at name order for every
language a library might contain, which is a worse kind of wrong.

### A library that can be moved through

Natural sorting, so "Book 2" comes before "Book 10" — the same rule `seriesSequence`
already applies, finally applied to plain titles, where there is no column to parse into.
Selection mode on the library grid, which was the one list that did not have it, now with
mark-finished among its actions. An A–Z rail for long grids, shown only when the ordering
is actually alphabetical, because an A–Z rail over a list sorted by date is a lie. And two
preferences that make the shell somebody's own: which tab opens, and which shelves appear
and in what order.

The shelf ordering stores names rather than positions, so a shelf added in a later version
is one the stored order has never heard of and falls back to where its author put it,
rather than silently taking someone else's place.

### Next

1. **Read the diary after a real stop**, and **a release-build device pass** — both still
   owed from the previous entry, and neither is something more code can discharge.
2. Run [qa/auto.md](qa/auto.md) in the DHU, then in a car.
3. Socket.IO deltas: M0's last unfinished piece, and the reason an edit made on the web
   takes until the next sync to appear.
4. Custom HTTP headers, once the release build has been proven on a device — it changes
   the auth path, and debugging that against an unverified obfuscated build would be two
   unknowns at once.

## 2026-08-16 (later) — the harness, the last of M0, and the reach

Six threads at once, and the through-line is that most of them are about *evidence* rather
than features: three phases had shipped in three days with R8 newly on and nothing ever run
on a device.

### A harness, at last

Screenshot baselines for every screen in both themes, verified on every `./gradlew build`
rather than behind a task somebody has to remember. Instrumented tests on an API 26 and 36
emulator matrix — the two ends, because that is where the failures are: 26 is where the
background-execution limits the playback service is built around first landed and the
oldest SQLite the migrations must open on, and 36 is where the newest foreground-service
rules decide whether a media session may start itself.

Two honest limits, both written into the backlog rather than glossed. **Process death still
is not covered**, because instrumentation runs inside the process under test and killing
the package kills the runner; the test destroys the playback *service* instead, which is
what Android actually does when reclaiming memory, and a real kill needs a second process.
And **only Settings drives its real screen** — every other screen takes a Hilt view model
over final Room, DataStore and Ktor classes that cannot be faked, so those baselines cover
the screens' own components arranged as the screen arranges them.

The Roborazzi Gradle plugin could not be applied at all: 1.46.1 reaches for AGP's
`TestedExtension`, which AGP 9.3.1 removed. Its whole job is setting two system properties,
so the root build file sets them directly — which made verification the default rather than
opt-in, a better outcome than the plugin would have given.

### M0 is finally finished

Socket.IO deltas: an edit or deletion made on the web now lands in the mirror rather than
waiting for the next sweep. The event names were read out of the server's own source, not
its API documentation, which says of itself that it is unmaintained — and that was the
right call, since three of the names in the brief were wrong. There is no batch removal
event; `item_removed` carries `{id, libraryId}`; and `user_item_progress_updated`'s outer
`id` is the *progress row's* id, so reading it as an item id would have sent every
re-fetch to an item that does not exist.

Two rules make it safe. An event is a hint, never a payload to write: it yields ids, and
the existing item fetch does the rest, because a partial parse that puts a half-populated
row over a good one is worse than no live updates. And progress goes through
`ProgressRepository.startSession`, the same call a real session start makes, so
`ProgressConflictResolver` still decides — a socket update that bypassed it would have
reintroduced exactly the bug M0 was built to prevent. The item in the player is never
touched from here at all: the server echoes progress back to the device that caused it, so
acting on one during playback would have the app racing its own writes.

### Reach: proxies, certificates, and a second address

Custom headers on every request, which is the most-demanded thing in the whole upstream
tracker and the reason nobody behind Cloudflare Access can use the official app. They reach
all three paths — the API, the media data source and cover loading — which is the part that
would have been easy to get two-thirds right, and they are settable before the first login,
because somebody behind an identity-aware proxy cannot get as far as being asked for a
password without them.

A second address for the local network, decided by **racing it with a short timeout** and
never by inspecting the network. Reading the current Wi-Fi network's name needs the location
permission on Android 10 and later, and asking a listener for their location so a book loads
faster is not a trade worth offering. This is only safe at all because progress here is keyed
by server and user id rather than by connection — which is the bug that makes the same
feature dangerous upstream.

Client certificates, installed per call through `Interceptor.Chain.withSslSocketFactory`, so
mTLS works on the shared client without rebuilding it and losing its connection pool.

The connectivity audit found nothing to fix, which is the answer that was wanted: there is no
`ConnectivityManager` check anywhere deciding in advance whether a request is worth making.

### Transcodes, said out loud

An item the server will only transcode still cannot be downloaded — but it now says so, and
says why, instead of a button that quietly achieves nothing. Three independent reasons are
written where the next person will find them: the playlist is minted against a session that
expires and takes its URLs with it; a transcode has no size until it exists, so a truncated
download is indistinguishable from a complete one; and it is a re-encode of a file the server
already holds intact.

The productive half was the other direction. The supported-format list was underselling what
Media3 can decode, so items were being transcoded that never needed to be — and the download
path and the playback path now read one list, because two that can disagree would produce a
download refusing exactly what playback then plays directly.

### The car, and the small things

Podcasts can run oldest-first, per podcast, because no client can tell a serial from a news
show by looking at the feed. The chapter title is in the session metadata where a head unit
draws it. A "Latest episodes" node spans every followed podcast. The sleep timer is
*suspended* rather than cancelled while a car is connected, and re-armed from the current
position when it disconnects, because resuming the old countdown would stop the audio the
instant the car was left.

Chapters-as-the-car's-queue was declined, for the same reason the chapter-scoped progress bar
was declined yesterday: Media3 builds the queue from the player's timeline, and making that a
list of chapters is a second, disagreeing notion of where the book is.

And a finding worth more than any of them. **Ducking never worked, and no setting could have
made it.** Media3 pauses rather than ducks whenever the content type is `SPEECH`, which lugu
has declared since M0 — so every navigation prompt has been stopping the book outright. It is
now a choice, made by selecting the content type.

Also: Tasker-compatible intents with documentation, sleep-after-N-chapters, collections that
can be edited, and an exported receiver that answers nothing back — which is what makes
exporting it without a permission defensible rather than merely convenient.

### A bug the work found in yesterday's work

`SleepMode.Chapters` could not fire. It resolved the count from the *current* position, so
ordinary playback into the next chapter pushed the target along by a whole chapter: the timer
receded exactly as fast as it was approached and came due only when the book ran out of
chapters. Its own test asserted that behaviour and called it "skipping shortens the count",
which is why it read as correct — the test never distinguished skipping from playing. Fixed
at the source, and the test now pins the thing that matters.

### Next

1. **A device pass on a release build.** Owed since R8 went on, and now the only way to
   settle a growing list: that the socket connects at all, that headers reach a real proxy,
   that the LAN race behaves on a real network, and that R8 has not quietly broken a
   reflective path.
2. Run [qa/auto.md](qa/auto.md) in the DHU. Six of the car items can only be confirmed there.
3. Read the playback diary after a real stop.
4. A `com.android.test` module, so process death can be tested from a second process.

---

## 2026-08-16 (later still) — the debt sweep, a stream that survives, and what a podcast trims

Three pieces asked for in one go: collapse the duplicated display logic, make a streamed
book survive losing its connection, and finish the podcast and browse polish — including
skipping adverts, "if it's the same mechanism".

### The sweep

Four formatters in three modules had already begun to disagree: a length read "1 h 20 min"
on the player and "1h 20m" in a list, a speed was "2" on one chip and "2x" on another.
They are now in `:core:model/Formatting.kt` — collected rather than merged, because the
difference between a *place* (colons) and a *length* (units), and between a roomy line and
a dense one, is real. What was not worth keeping is each module deciding that on its own.

The Continue-row rule that the car and the phone had each written out separately is now
`ContinueLabel` in the same file, which is where the backlog said it belonged.

Writing the tests turned up a defect in the speed formatter, though not one anybody has
seen. It truncated, so a value like 1.7999992 printed as "1.79x" and 1.9999990 as "1.99x" —
a chip disagreeing with the button just pressed. It rounds now. Nothing in the app produces
such a value today, because `SpeedSettings.STEP` is declared and never used and every speed
arrives from a clean preset; the constant is on the debt list.

Also swept: `hiltViewModel` (the new artifact already arrives through
`hilt-navigation-compose`, so it was a pure import change across thirteen files),
`AcceptedResultBuilder`'s deprecated single-argument constructor, `Icons.Filled.PlaylistPlay`,
and the storage cap, which is now enforced *during* a download rather than only estimated
before one.

### A stream that survives a dropout

The leading fixable candidate for "it stopped and I did not touch it". Three things were
missing, and each was necessary on its own.

**Nothing buffered ahead.** The player was built with no `LoadControl` at all, so it held
Media3's default — tens of seconds, which a tunnel drains. Spoken word is the one case
where buffering deep is nearly free: at audiobook bitrates, minutes of audio are a couple
of megabytes. It now reads ahead by a configurable five minutes, under a byte ceiling that
encodes an assumption of 256 kbps, plus a heap-eighth and an absolute cap so a
high-bitrate file cannot turn the setting into an out-of-memory kill.

**Nothing came back when the network did.** `PlaybackRetryPolicy` existed and was wired,
but gave up after three attempts inside about seven seconds — a tunnel, and nothing longer.
A `ConnectivityManager` callback now re-prepares on reconnect, but only when playback
stopped for a reason the network explains and the listener had not asked it to stop. Getting
that wrong is worse than not doing it: a book that starts playing in a pocket an hour after
being paused is the Spotify complaint this project exists to avoid. The ladder also widened
to five attempts over thirty seconds, on the argument that the callback only fires when the
*default network changes* — the flaky-cell case, where the phone insists it is still online,
has nothing but the ladder.

**Nothing that had been fetched survived a re-prepare.** Streaming deliberately never wrote
to the download cache, which is right — a download is user-owned and must never be evicted
to make room for something merely streamed. So streamed audio now goes to a *second*,
bounded cache with an oldest-first evictor, and the playback source chains: download cache
first and read-only, retained cache second and writable, network last. The two figures are
never added together; the downloads screen shows retained bytes on their own line, marked
as outside the cap.

Both of these are fixed when the service starts — a `LoadControl` is set at player
construction and a `SimpleCache` holds a folder lock — so they take effect at the next
service start rather than immediately. That is stated rather than faked.

### What a podcast trims, and the honest answer about adverts

Asked as "skip intro and outro — and maybe ads too, if it's the same mechanism". It is, at
the point of playing, and it is not, at the point of finding — and the difference decides
what can be promised.

An intro and an outro are fixed offsets from the ends of an episode, so one number covers
every episode of a show forever. An advert is somewhere in the middle, at a different place
and length every week; no offset can find one. So adverts are skipped where the episode
*marks* them, through a chapter whose title names it as advertising — markers a useful
number of shows ship, because the same ones drive every podcast app's chapter list, and
which lugu already parses. Finding an *unmarked* advert needs audio fingerprinting against
a database of known adverts, and a false positive silently eats a minute of the show. A
skip that removes narration is worse than an advert that plays.

The matcher is whole-title with punctuation stripped, so "[Ad]" and "Sponsor Message" match
while "Adam's Return" and "Broad Strokes" do not. Both directions are tested; the second
direction is the one that matters.

`SkipRegions` in `:core:model` computes the regions — clamped, merged, and dropped entirely
if they would swallow the episode, because an intro set for hour-long episodes would
otherwise skip a forty-second trailer end to end and read as a broken file.
`SkipRegionEnforcer` in `:playback` decides when one is acted on, which is the question with
all the ways of being wrong in it:

- **Podcasts only, in the type rather than in a comment.** `SkipPlan`'s constructor is
  private and its factory refuses anything with no episode id. A book's chapters are its
  content, and an intro offset against chapter one is forty seconds of narration gone.
- **A manual seek wins.** A listener who scrubs back into the intro stays there. Without
  it the enforcer is a loop nobody can escape — and it is also what makes Undo work, since
  Undo seeks back *into* the region just skipped.
- **The outro at the tail ends the episode rather than seeking to its last sample.** Those
  are not the same act: seeking there leaves the queue, the continuation and the progress
  sync all unrun, and the episode sits at its very end marked unfinished forever.

Trim is per show, stored beside the per-podcast speed and for the same reason: the sting
belongs to the show, and setting it per episode would mean setting it again every week. The
podcast's own page distinguishes "following the default" from "set for this show" in copy
and in colour, because a show trimmed to zero and a show following a default of zero carry
the same numbers and behave differently the moment the default moves.

### The series bug nobody was looking for

The brief was the recorded gap — that roughly a third of series entries have no parseable
`#N` and are excluded from "Next in series" — and the fix was expected to be the library's
series endpoint. What turned up first was worse than exclusion.

The server joins *every* series a book is in into one string: `"The Breakwater #1, The
Tidelands #3"`. The existing parse read the trailing number and called everything before it
the name — so a two-series book was not merely missing from a shelf, it was **inventing a
series that nothing else is in, at a number belonging to a different one**. It then labelled
that book "Book 3" on The Breakwater's own page: the other series' position, on the one
screen whose entire job is putting a series in order.

Series membership is now its own table (schema v6), populated from three sources in order of
authority: the library-series listing, the expanded item's structured `series` array, and —
only for items no server source has spoken for — the old regex. Where the series name is
already known, the sequence is recovered *anchored on that name*, which resolves both the
two-series string and a series whose own name contains a comma. An upgrade backfills every
existing parse, so nothing changes visibly until the first sync replaces it.

Two findings from reading the server source that are worth keeping, because both contradict
what we expected:

- The structured `series` array is **never** in a minified payload, only an expanded one. An
  earlier note in this repo recorded it as "empty on every item", which was a correct
  observation of a list response and a wrong conclusion about the API.
- **A series with no sequences does not have an order the server knows.** Its comparator has
  nothing to compare and the array comes back in scanner order. So the server's rank orders
  *series browse pages*, where the reader sees the whole list and chooses — but it is not
  allowed to qualify a series for "Next in series", where an order derived from nothing is
  precisely the spoiler the rule against guessing exists to prevent.

`sequence` is also free text on the server, read there as `CAST(sequence AS FLOAT)` — which
silently makes "Book Two" into zero. lugu still parses strictly and returns null instead.

### Also

The A–Z rail now runs down the author, series and narrator pages, indexing the list *after*
the search box so it can never offer a letter the search has removed.

### A bug the integration found

`undoJump` reverted the correct progress row and then seeked whatever was loaded. The notice
sits on screen long enough for the item to change underneath it — an episode ending hands
over to the next, and a skip that ends an episode does exactly that — so pressing Undo
dragged the *new* episode to a position belonging to the old one. The same class of bug as
the notification rewind that started this project, this time caused by the button offered to
undo one. The revert still always happens, because it is addressed to the jump's own item;
the seek is now guarded on the player still holding it.

The jump notice also carries a reason now, so a skip reads "Skipped the intro from 0:00 to
0:15" rather than "Jumped from 0:00 to 0:15" — a true account that explained none of it.

### Next

1. **A device pass on a release build**, still owed and now carrying more: the socket, real
   proxy headers, the LAN race, R8's reflective paths, notification persistence on both
   sides of Android 14, and now the deep buffer against a real tunnel and the retained
   cache's eviction at its bound.
2. Run [qa/auto.md](qa/auto.md) in the DHU — and confirm car browse specifically, because
   the session's connection result is now trust-aware.
3. Read the playback diary after a real stop.

## 2026-08-16 (last) — a book that starts itself, and a version you can quote

Two asks. The small one first: **the app version now appears in Settings → About**, read
from the package manager rather than `BuildConfig` — the settings module's own constant is
the library's and carries no version at all, and even in `:app` the constant is what was
compiled rather than what is installed. It reads `1.4 (7)`, and a debug build says so in
the name.

### Starting a book when the headphones connect

Asked as: *for the official app I use Tasker to start playing x seconds after the headset
with a given name or MAC connects — can we build that in, and always start whatever was
last playing?*

The setting that looks like this already existed and is not it. "Resume when headphones
reconnect" continues something a *disconnection* interrupted — it needs the player still
loaded, a disconnect on record as the cause, and half an hour or less since — and it fires
for any headphones at all. What was asked for starts from nothing: app closed, process
dead, hours since anybody listened, and only for a named device. Both now exist and the
settings say which is which.

### Why this could not be a Bluetooth receiver

The obvious implementation is a manifest receiver for `ACL_CONNECTED` and a check on the
device address. On Android 12 and later it fails twice over, and the second failure is the
one that would have wasted the day:

1. Reading which device connected needs `BLUETOOTH_CONNECT`, a runtime permission whose
   prompt offers to *find, connect to and determine the relative position of nearby
   devices*. `AudioRouteWatcher` had already refused that trade for a smaller feature.
2. **An app in the background may not start a foreground service, and a Bluetooth broadcast
   is not one of the documented exemptions.** The receiver would be delivered the broadcast
   and then refused the service — a feature that appears to work and never plays anything.

Checked against the documentation rather than assumed, because the memory of an exemption
existing was quite strong and it is not on the list.

The companion-device association answers both. The system shows its own picker, so lugu
learns about one device and asks for no Bluetooth permission at all; associating grants
`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, which *is* on the exemption
list; and `CompanionDeviceService` is bound by the platform when the device turns up, which
is what starts a dead process. Below Android 12 neither restriction exists and
`BLUETOOTH` is a normal permission, so the straightforward receiver is also the correct
implementation there, and that is what runs — with the paired list as its picker.

### The wait, and the way out of it

The delay in the original Tasker rule looks like a workaround and is not: a headset
announces itself before the audio route has moved, and audio started inside that gap goes
to the phone's speaker. It shipped as a setting, defaulting to five seconds.

Then the better question: *the delay is a workaround for the switchover — can we detect
when it has switched and start then?* Yes. `AudioManager` reports output devices arriving,
which is the same permission-free callback `AudioRouteWatcher` already uses, so the
switchover is now **waited for rather than guessed at**. On a fast headset that is sooner
than any delay anybody would have configured; on a slow one it is later, and correct. A
timer was the wrong instrument — a guess at a number the phone already knows.

The setting survives as a deliberate extra on top, default one second, because a device
appearing in the output list and the audio policy having finished moving are not quite the
same moment. None is a choice, which is the point: somebody who wants their book as early
as it can start can have that without also getting a clipped first sentence, because
nothing is guessing at the switchover any more. What would remove even that second is
`ExoPlayer.setPreferredAudioDevice`, pinning the track to the device that connected; it is
in the backlog rather than in, because there is no hardware here to prove it does not pin
playback to a headset that later walks away.

If no output turns up within twenty seconds the start is abandoned, and that is a refusal
of its own rather than "the device disconnected" — plenty of Bluetooth devices are not
audio devices, and a watch connecting is a different problem wearing the same shape.

The wait is spent usefully: what to play is resolved during it, and only loaded into the
player at the end. That split is deliberate. Loading earlier would make Media3 post a
notification of its own, and there would be two on screen arguing about which is holding
the service in the foreground.

Which is the other half of this. The service is started with `startForegroundService`, so a
notification has to go up within a few seconds **including on the paths that end in
refusing** — the notification is posted first and before anything else can fail. It carries
a "Not now" button, which is something the Tasker rule could not offer, and refusing
suppresses that connection's remaining events for a minute. Connecting a headset fires
several of them seconds apart; without the suppression the next one restarts the countdown
just after the cancel, which reads as a button that does not work.

Four things are never played over — a call, another app already holding the audio, a device
that has gone again, and one the audio never moved to — and the record says which refused,
because the question this feature generates is always "why did it not start".

### The address never leaves the device

On Android 12 and later lugu never learns it: the association carries a display name, and
that is what is stored and what the diary records. On older versions it comes from the
paired list and is only ever a key. The playback diary can be sent from the feedback
screen, and an address identifies hardware a person carries around.

### Found on the way

A book loaded straight into the player comes up at whatever speed the player holds, which
on a fresh service is 1x. `PlaybackConnection.play` reads the remembered speed; nothing on
the resumption path did. Fixed for the auto-play path, where the ordering is ours;
`onPlaybackResumption` has the same gap and is in the backlog.

### Next

1. **A device pass on a release build**, still owed and now carrying the whole of this: the
   association picker, a real headset connecting from cold, "Not now", a reboot to prove the
   observation is re-armed, and above all **item 7**: whether the extra second on top of the
   switchover is needed at all, which is a measurement rather than a judgement. The
   procedure is [qa/autoplay.md](qa/autoplay.md).
2. Run [qa/auto.md](qa/auto.md) in the DHU.
3. Read the playback diary after a real stop.

## 2026-08-16 (afterwards) — a green run, and a cover that opens the book

### The instrumented job was failing on modules that have no tests

CI had been red since `f67844a6` for two unrelated reasons. The first — screenshot baselines
recorded on macOS never pixel-matching CI's Linux runner — was fixed by re-recording them
on Linux, and the `build` job went green with it.

The second was in the instrumented job, and the error blamed the wrong thing. `:core:sync`
and `:core:download` both failed with "Instrumentation run failed due to Process crashed",
which reads as a test crashing. Neither module has a single instrumented test. What actually
happens is that AGP builds, installs and starts an instrumented-test APK for *every* Android
module, and for a module with no `src/androidTest` nothing ever put `androidx.test.runner`
on the classpath — so the APK it installs does not contain the runner named in its own
manifest, and the process dies with `ClassNotFoundException` before a single test can be
counted. An empty module failing loudly is noise that hides a real failure.

The obvious fix is to name the two modules that do have instrumented tests in the workflow's
`connectedDebugAndroidTest` command. It was not taken. It works today and quietly stops
running the third module somebody adds next year, and a test suite that silently stops being
run is worse than one that was never written. Instead the root build script switches the
instrumented-test APK off for any module with no `src/androidTest` directory, and CI carries
on asking the whole build for it. Adding a test directory opts a module back in, with
nothing to remember.

### And behind it, a real failure it had been hiding

API 26 went green. API 36 did not, and for the first time the run got far enough to say
something true: `LaunchSmokeTest` failing with "No compose hierarchies found in the app",
which reads as the app not starting — the exact thing that test exists to catch.

The app was starting. The logcat shows `MainActivity` reaching RESUMED and being paused two
tenths of a second later, behind `GrantPermissionsActivity`. lugu asks for the notification
permission on first launch, on purpose, because the media notification *is* the player for
most of a listening session; a freshly created emulator takes that dialog every run, and the
dialog is a separate activity sitting over the app with nothing of ours left resumed to look
at. Android 13 is where that permission starts existing, which is precisely why only the API
36 leg saw it and why it looked like a platform problem rather than a first-launch one.

The tests now grant it before the activity launches. Nothing about the product changed: the
dialog is correct behaviour on a real phone, and it is only a test that mistook it for a
crash. Two red jobs for two unrelated reasons, one of them only visible once the other was
out of the way — which is the argument for never leaving a suite red, since the second
failure had been sitting behind the first the whole time.

### The cover is a link now

The now-playing title has led to the book since M1; the cover under it did not, and the cover
is the biggest thing on the screen and is the picture *of* the book. It now goes exactly
where the title goes — for a podcast episode, the show's page, because that is where an
episode lives. Clipped before it is made clickable, so the ripple follows the rounded corners
rather than the square behind them.

### Still owed

The device pass on a release build, unchanged and still carrying the whole of the auto-play
work — above all [qa/autoplay.md](qa/autoplay.md) item 7, whether the extra second on top of
the audio switchover is needed at all.

## 2026-08-16 (the first device pass) — three faults only hardware could find

Auto-play met a real pair of headphones for the first time. Everything structural worked —
the association, the cold start with the process dead, the switchover detection — and three
things were wrong that no emulator and no unit test would ever have shown.

### The waiting notification would not go away

It sat under the player for the rest of the session. The line removing it had been there
since the first commit and had never once worked.

The waiting notification is the one passed to `startForeground`, and **a service's current
foreground notification cannot be cancelled** — the system holds it on screen for exactly as
long as the service is foreground under that id. It becomes an ordinary, removable
notification only after `startForeground` has been called again with Media3's id. The code
asked Media3 to post and cancelled on the next line; the post is built asynchronously, so the
cancel was racing something that had not happened yet and lost every time. It now waits for
the player's notification to appear before removing its own, with a timeout as the safety net
rather than the mechanism.

Worth noting what the original comment got right and wrong. Its instinct — hand the
foreground over before letting go of it — was exactly correct, and is why this was never a
crash or a dropped service. It just assumed `triggerNotificationUpdate` was synchronous.

### "Not now" was behind a gesture longer than the thing it interrupts

Reported as: *I have to scroll down with two fingers to reveal it, and the time has gone past
by then.* In Android's ordinary notification layout the action buttons live in the **expanded**
view. The channel was low importance, so the notification arrived collapsed, and the offer to
stop a book starting in one second was two gestures deep.

The channel is now high importance, which brings the notification forward with its button
already showing. Coming forward and making a noise are separate things and only the first is
wanted, so it has no sound and no vibration, and only the first post of a countdown alerts at
all. The channel id had to be versioned to do it: importance belongs to the listener once a
channel exists, and `createNotificationChannel` cannot raise it afterwards — which is correct,
since that is exactly how an app would undo somebody turning it down.

### Only one earbud started a book

*I connected my Jabra Elite 10 Gen 2, but only the left one triggers the auto-play. I think
they have separate MAC addresses, but the same name.* Right on both counts.

There is no fix at the moment of connection, and it is worth being plain about why: on
Android 12 and later only an **associated** device is observed, so the right earbud connecting
never reached lugu at all. There was nothing to match against — no event, no address, no name.
Matching on the name instead would have changed nothing, because nothing was called.

What was genuinely broken was everything around it. Both sides can be chosen, and could
before — but they listed as two rows with the same name and no way to tell them apart, which
looks like a bug and makes "remove" a guess. The sides of one pair are now one row marked
**Both sides**, removing it removes both, and the text under the picker says that earbuds
appear once per side and both want adding. The grouping is a pure function in `:core:model`
with a test that pins the important part: grouping is for reading, and must never make one
side match for the other.

### Next

The rest of [qa/autoplay.md](qa/autoplay.md), now with the three cases this pass produced —
including **item 7**, still the measurement that decides whether the extra second on top of
the switchover is needed at all.

## 2026-08-16 (in the car) — covers that never arrived, and a notification that means no

Android Auto driven for real, with a video of the screen to check against. The structure was
right — the tree, the Continue section ordered by recency, and playback starting the instant
play is pressed, which is the thing the whole offline-first design was for. Two faults.

### Every cover in the car was blank

Browse rows and the now-playing screen alike, and nothing in any log.

**Android Auto fetches artwork in its own process.** Handed `https://…/api/items/:id/cover`,
it makes an anonymous request, gets a 401, and draws a blank tile. lugu's own screens show
covers because they go through the app's OkHttp client, where an interceptor attaches the
token — invisible from the inside, and total from the outside. From lugu's side nothing
happened at all, which is why this was reported by a person in a car rather than by anything
here.

Artwork now goes out as a `content://` URI served by `CoverProvider`, so reading it comes back
into this process where the authentication is, and what crosses the boundary is a picture.
The provider fetches through the same client as everything else, caches to disk, and prunes
oldest-first.

Two things had to be right beyond the provider itself. It is **exported**, because a browse
result cannot carry a URI permission grant — there is no scoped way to hand a car a read, so
the surface is stated plainly in the class rather than hidden: an app that already knows an
item id can fetch that item's cover, `query` enumerates nothing, and no token or address is
reachable through any path. And the session's **bitmap loader is now stated rather than
inherited**: the notification loads its artwork in *this* process, so a loader that only spoke
http would have drawn nothing on the phone while the car drew everything.

### "Not now" left the notification on screen

A second bug in the same place as the morning's, with a different cause. The prompt was only
taken down when the player had nothing loaded — so cancelling while a book sat paused from
earlier left the countdown up. The two ways out of the wait want opposite handling, and
confusing them was the whole of it: when a book starts, something is coming to replace the
notification and the foreground has to change hands first; when nothing starts, nothing is
coming, and waiting for it leaves a question on screen that has already been answered.

On top of that, a deliberate change: **tapping the notification now cancels**. Tapping a
notification usually opens the app, and that is right nearly everywhere — but this one lives
for a second and asks one question, and opening lugu is not an answer to it. Swiping it away
cancels too, because dismissing a prompt and then having the book start anyway would make the
gesture a lie. The labelled button stays, since a tap target nobody can see is not an offer.
Lint objects to a content intent that reaches a service; the objection is right for an ongoing
notification and wrong for a prompt, and the suppression says so.

### Next

Covers still are not part of a download, so a car with no signal and a cold cache gets blank
tiles for books that are fully on the phone. That is in the backlog and is the one place the
offline claim is currently thinner than it reads.

## 2026-08-16 — the picture goes with the audio, and a door out

Three things, all of them gaps between what lugu claims and what it does.

### A download now includes the cover

Everything else about a downloaded book worked in a tunnel; its picture did not. The audio was
on the phone and the artwork was never fetched to disk with it, so a fully downloaded book in a
garage showed an empty square — worst in the car, where the tile *is* how a book is picked at a
glance.

`CoverStore` fetches the cover when a download is queued and holds it in `filesDir`, not
`cacheDir`: a cache may be reclaimed at any moment, and the one moment that matters is the one
with no network to re-fetch from. It is written when the download is and dropped when the last
download for that item goes, so nothing has to expire it — a podcast with a dozen episodes has
one cover, and deleting the eleventh episode leaves it alone. Startup sweeps orphans, which is
the honest way to catch a sign-out rather than hanging a delete off every path that might be
one.

Two readers, both already existing. `CoverProvider` answers from the store before it considers
the network, which is what fixes the car. The phone's own screens go through a Coil
interceptor: every screen already passes a cover URL, so recovering the item id from that URL
and swapping in the file is one place instead of six view models each doing a disk check on the
main thread.

Tightening fell out of it. The provider is exported, so the item id in a `content://` URI is
whatever another process wrote — and it becomes part of a file name. A segment of `..` would
have named a file outside the cache directory. `CoverId` now says what an id is, both callers
ask it, and the awkward cases are settled in a unit test.

### A book that starts by itself starts at its own speed

`applyRememberedSpeed` existed and one path called it. The other two did not: a headset press
through `onPlaybackResumption`, and a car handing back an id through `onSetMediaItems`. Both
came up at whatever speed the player held, which on a fresh service is 1x — so a 1.5x book
played at 1x for whoever was not looking at the screen. All three call it now, before the items
are handed back, since playback parameters belong to the player rather than to an item and
survive the list being replaced. Doing it afterwards would mean a word or two at the wrong
speed.

### Somewhere to go for what lugu cannot do yet

Until there is parity, hiding the client that *can* do the rest is the worse of the two
options. Settings offers the server's web client, and a book's page links to its own page
there.

The caveat is the whole feature, so it is stated before the link is followed rather than
discovered after: a browser has its own cookies, and — the part that is neither obvious nor
recoverable — lugu's custom proxy headers and client certificate cannot be handed to another
app. For a server behind an identity-aware proxy the browser is turned away before it reaches
Audiobookshelf at all, and the refusal comes from the proxy, in the proxy's words, which will
not mention lugu. The row says so when either is configured.

### Next

The device pass on a signed release build, which now gates more than it did: whether the extra
second before playback is needed, whether "Not now" is redundant, and whether the car actually
draws a downloaded cover with the network off.

## 2026-08-17 — the first CI run with a server, and the three things it caught

The seeded Audiobookshelf container landed on 16 August. On 17 August it ran. Every finding
below came from it. Two of the three were faults in the app. The third was a test that never
did the thing its name claimed.

### A test that destroyed nothing

`PlaybackResumptionTest` reported "expected to be at most: 1 but was: 4093". The assertion
said that a reconnected controller finds an empty player. The reasoning was that a destroyed
service builds a fresh `ExoPlayer` in `onCreate`. That is right about the service and wrong
about the premise. Media3 keeps a `MediaSessionService` alive while its session is active.
So `stopService` destroyed nothing, and the player kept its position.

So the step the test is named after never ran, on any machine, since the day somebody wrote
it. Nobody knew, because the test skipped for want of a server. A skip looks exactly like a
pass in a green run.

The false assertion is gone rather than loosened. What remains is true and worth the test.
The position survives the release and the re-attach of a session. A media button through
`AudioManager` reaches lugu's session and resumes the right item. The cold-start case
belongs to `:harness`, which kills the process for real. The KDoc says that now.

### A sign-in that mirrored the libraries and none of their items

The next failure was an empty Room. It got three readings. Only the third survived the
logcat: a race, then a slow mirror, then a sync that nothing started.

`LibraryViewModel` starts the only on-demand sync in the app. Nothing builds that view model
until the Library tab is composed. A sign-in lands on Home. The test sat on Home and waited
for a sync with no reason to start. It taps Library first now, which is what a person does.
A car cannot, so the gap went into the backlog as a decision rather than a note.

Under it was the real fault. `refresh()` syncs the libraries, then syncs the items of
`selectedLibraryId`. That selection is null on the first run. The collector that picks a
default waits for the libraries fetched two lines above it. So the list of libraries to
mirror was empty. Nothing called `refresh()` again.

Three readings from outside were each wrong, so the next step was a measurement. On a
device, against a throwaway server: `library` held 2 rows and `library_item` held 0. It
stayed 0 through a visit to the Library tab. A restart of the app made it 2, because the
selection was persisted by then. That is what made the fault look first like a race and then
like a slow sync.

It reached past the grid. Home's shelves, the Android Auto browse tree and `playFromSearch`
all read Room. All three found nothing. A person can restart the app. A car cannot.

`refresh()` now falls back to the account's default library, or to the first one. Verified
on a device: items arrive within five seconds of a sign-in, on Home, with no tab tapped and
no restart.

### The harness skipped past the end of a ninety-second book

Both playback tests failed, in two places, for one reason. The harness skipped ten minutes
forward to get deep into the book. The book CI seeds is ninety seconds of sine wave.

The skip ran off the end. Playback stopped. A session that no longer played never reported
the speed set immediately afterwards. That is what "lugu never reported the speed the
harness asked for" really said. The server then stored the item as finished, and the next
test resumed it at its end. One wrong constant, two failures that looked unrelated.

The leak is worse than a leak between tests. Progress lives on the Audiobookshelf server. So
the poisoned position came back down after an uninstall and a reinstall. On a fresh install,
before a single test touched it, `dumpsys media_session` read `state=STOPPED(1),
position=90000`.

The setup now assumes nothing about where the book is:

- It waits for the title **on screen**, and it opens the Library tab on the way.
  `PLAY_SEARCH` answers from Room. Before the first sync, the right answer is that no such
  book exists. `:app` waits on Room directly. The harness cannot see another app's Room, so
  it waits for what a person sees.
- It waits for a book that is **loaded**, not one that plays. A book left at its end loads,
  plays out what is left, and stops.
- It rewinds to the start, presses play, and walks forward in fifteen-second steps to
  forty-five seconds. It verifies after each step that the book still plays. A book too
  short to reach the target says so, rather than an end that gets blamed on the speed.
- Tolerances match that target: 20s behind, 30s ahead, against 45s. Nothing on the
  resumption path applies a rewind. `SmartRewind` is sized from the length of a pause, and a
  dead process no longer knows one. Only the five-second persistence tick puts the position
  behind.

One more fault came out of the numbers rather than the color of the run. The published
position is a stamp with a time on it. The first version carried it forward by the elapsed
time. On one API 26 run the session went quiet for ten seconds. The arithmetic invented
fifteen seconds of progress, and the book "resumed fifteen seconds behind" a position it
never reached. The seek loop read the same estimate, so the loop was able to reach the
target with no seek landed at all.

Every comparison now uses what the platform reported. A stale reading errs towards an
earlier position. That loosens the "behind" check and tightens the "ahead" one, and both are
the safe direction. The spread across runs fell from fifteen seconds to under a tenth of
one.

**The resumption works, and this is the first time anything showed it.** On an API 36
emulator, against the seeded server:

    I LuguHarness: killed lugu's process 19887 while it was playing
    I LuguHarness: resumed 9c46b6de at 45209ms x1.5; was 9c46b6de at 45239ms x1.5

The process gone, `input keyevent 126`, and the same item back thirty milliseconds from
where it stopped, at the speed it was on. API 26 lands in the same place. That line is
written on a pass as well as on a failure. "It resumed" and "it resumed where it was" are
different claims, and only the second is the promise. The identity is a digest of the title,
so it names nothing from anybody's shelf.

The test was checked before it was believed. Without the key press it fails, and nothing
comes back inside sixty seconds. So the button does the work, not a service that the system
restarts by itself. The speed asserted is never the one already in force: the harness picks
1.5 or 1.2, whichever the book is not on.

This is the proof that the media button receiver of 330fd87 was missing. Before that
receiver was declared, a headset press had nowhere to arrive once the process was gone.

One finding stays unasserted. A media button after `am force-stop` woke lugu on two runs of
one emulator and not on a third, against a fresh install. Neither outcome is lugu's to
control. So the test asserts only that whatever comes back is the right book at the right
place.

### Two things around the server, not in it

`scripts/seed-test-server.sh` refuses to hijack a container that belongs to another
checkout. The container name is fixed and `ABS_ROOT` is not. So two roots on one machine
fought over one name, and the loser was never told. A run from the second root replaced the
first root's server. It also left the first root's stored password on an account that no
longer existed.

What that looks like downstream is nothing like what it is. The sign-in fails and the
library comes back empty. The harness then reports that the title in `lugu.test.playQuery`
was not on screen, which reads as a fault in the test. It cost two runs, and it very nearly
bought a wrong conclusion about work that was correct. The script now compares the running
container's `/config` mount against the root it was asked for. If they differ, it stops,
prints both paths, and names the two ways out.

And `docs/automation.md` stated the precedence backwards. It said the file wins over the
environment, and both `:app` and `:harness` do the opposite. The code changed and the
sentence about it did not. A review caught it, which is the only way a wrong sentence in a
document ever gets caught.

## 2026-08-17 (later) — what daily driving turned up

Nine faults from Tom's own use, none of them found by a test.

### A podcast tile showed no progress, and the filter was blind to it

Progress is stored per (item, episode) pair, and a podcast holds no row at the item level.
The library grid looked progress up by that key alone, so a feed's key matched nothing. The
browse, series, narrator and collection grids were already fixed. They read
`ItemProgress.byItem`, which falls back to the most recently played episode. The library
grid was the last screen that did it the old way.

The same lookup left the "In progress" filter blind. A part-heard podcast has no item-level
row, so it counted as untouched. The one filter that exists to find what you are in the
middle of hid it.

A borrowed row must not decide that the *feed* is finished. `LibraryRow` now knows whether
its progress is its own or an episode's. The bar is drawn either way, and only an item with
a row of its own can be Finished.

The bar also carried no content description. A screen reader heard nothing about the one
fact that separates a part-heard book from a new one. It says "62% listened" now. Where the
number belongs to an episode it says "Latest episode 62% listened", because the plain
version reads as a claim about a whole show.

The record here was wrong, not merely incomplete. The backlog called the missing bar a
deliberate refusal to draw "60% of a feed". The app already settled that question on every
other grid.

### The downloads screen forgot its ordering and hid its refusals

Sort and filter lived in a `MutableStateFlow` that dies with the screen. `LibraryPrefs` now
carries `downloadSort` and `downloadFilter`. They are two keys of its own rather than the
grid's, because a visit to Downloads must not re-order somebody's library. A test pins that:
a change to one leaves the other alone.

The search box is deliberately not remembered. An ordering is a decision about how a list is
read. A search is a thing looked for, and three of forty rows behind a stale word reads as
lost data.

A refused retry said nothing. The storage cap stops a download and keeps the ordinary retry
affordance. The same cap then refuses the retry. The screen threw the refusal away, so the
button read as broken. The refusal is now a snackbar, at the row that was tapped rather than
above forty rows. It quotes its own arithmetic: "Needs 56 MB, and 7.6 GB of the 8 GB cap is
already used."

The chip that reads "In progress" on the grid means part-listened. On the downloads screen
the same chip answers a question about bytes. It reads "Downloading" there now. The words
belong to the screen and the filter policy stays in `:core:model`, which is the split that
was already right.

### A plain-HTTP server was unreachable

Audiobookshelf is mostly self-hosted, at home, on a LAN, over plain HTTP. Android refuses
cleartext from API 28, and it refuses below the HTTP client. The socket is blocked before a
request exists. So a correct address and a running server reported "could not reach that
server", which reads as a server that is down.

A network security config cannot express "whatever the user configures", because its domain
list is fixed at build time. The platform switch is open now and the policy lives in the
app, where it can be about the address in use. The sign-in screen states the cost before the
password goes out. A plain-HTTP server carries the password, the token and every listen in
the clear. The warning is inline, where the address was typed, and not a dialog. This is the
ordinary way the software runs, and lugu must not obstruct it.

What lugu must not do is let the password go out silently. Certificate trust is untouched.
An `https` address is still verified against the system trust store, and the config must
never become "trust everything". The debug-and-minified manifest overlay that permitted
cleartext to 10.0.2.2 is gone, with its source-set wiring and its lint suppression.

`ServerUrl` also lowercases the scheme. An auto-capitalized "Https://" is the ordinary way
to arrive. "That does not look like a server address" blames the listener for their
keyboard.

### The playback speed drifted off its own grid

The fine adjustment added a hardcoded 0.05 to a float on every press. Fifteen presses from
1.0 reached 1.7499998. So a stepped speed was a different number from the identical-looking
preset. The step is computed in hundredths now and snaps to the grid.

It also did not know the range had ends. A press below 0.5 or above 3.5 was accepted,
clamped at the player, and changed nothing on screen. A button that answers nothing reads as
a broken sheet rather than as a limit. Both buttons switch off with nowhere left to go.

`−` and `+` inside an `IconButton` gave a screen reader one character to read. They carry
"Slower" and "Faster" now, and the number between them reads as a speed.

`SpeedSettings.STEP` existed for this and was never used. The backlog said "wire it or
delete it". It is wired.

### Four manual checks became machine checks

None of the four needs a server. `RotationTest` puts eight screens through the destroy and
rebuild of a rotation. It asserts that each is still the screen afterwards, because a
NavHost that restarts at Home passes "it did not crash". The item page is the interesting
one, because its argument must come back out of saved state.

Covers on disk are asserted on the loader the app draws with. "Coil caches by default" is
not a decision anybody can point at, and a deleted cache block looks like the upstream
complaint app#907. A wrong password says so, against the seeded server. And a sign-in fills
the mirror, asserted against Room, because Room is the claim.

The position slider gained a state description in the same pass. A `Slider` announces a
percentage, and on a forty-hour book "43 percent" is four hours wide. It reads the two
figures printed either side of the bar instead.

### Three smaller ones, and two about the tests

An empty Home pointed at an empty Library tab. "The Library tab has everything on the
server" is right once the mirror arrives. It is wrong for the minute after a sign-in, which
is the minute where somebody has nothing else to judge lugu by. Home counts what is mirrored
for the account — one counted query — and says the library is on its way.

Download-ahead read the wrong series' numbering. `library_item.seriesSequence` is re-derived
for the primary series only. So for a book in two series it holds the other series'
position. `bySeriesNumbered` asks the join table, which is the only place a series' own
sequence lives. The test uses a book that is #2 of one series and #1 of another, and asserts
that the primary column was not consulted.

"Tell me about new episodes" looked broken for six hours. New is decided by a comparison of
episode ids across a refresh. The first refresh sets the baseline and reports nothing. The
row says so now.

Two test faults, both real outside CI. `RotationTest` clicked a book called "Lighthouse
Wakes", and so does the catalogue the seeder builds. On a device with that catalogue
mirrored, "exactly one node" was two. The fixtures use invented names now, and a constant
says why.

The seeded-Room tests reset three preferences to a default rather than to the value they
found: the selected library and two sort keys. On CI that is nothing. On a phone it leaves
its owner's library picker on something they did not choose.

The last M0 sign-in line stays manual, and the reason is recorded. `cmd connectivity
airplane-mode` arrived in API 30, and the older route needs a permission the shell no longer
has. A test that runs on one leg and skips on the other is worse than a manual line, because
CI fails on any skip.

## 2026-08-17 (last) — a sign-in that mirrors, the race it exposed, and a line that moves nothing

### Something now calls refresh()

The morning fix made `refresh()` mirror a library's items when nothing was selected yet. It
did not make anything call `refresh()`. So a new account's Home was still empty. There were
no shelves, nothing to resume, and no sign that anything was on its way. The car's browse
tree and `playFromSearch` found the same nothing, and neither can tap a tab.

The periodic reconcile hid this on a fresh install, where WorkManager runs the first period
straight away. On any later sign-in the worker is already enqueued.
`ExistingPeriodicWorkPolicy.KEEP` then means it does not run again for up to six hours.

`SyncScheduler.syncNow` enqueues a one-off reconcile at the moment the account is created.
It is a worker rather than a coroutine on the sign-in screen. A first sync of a large
library outlives the screen that starts it, and this one survives a dead process.

### The race that fix made likely

The API 26 leg then failed with "Lighthouse Wakes never reached Room after 180000ms". That
is an honest description of a race.

A mirror pass stamps every row it writes with its own start time. It then deletes everything
in that library older than that stamp. This is how a book deleted on the server leaves the
phone with no event to carry the news. It is correct exactly once at a time.

Two passes that overlap delete each other's work. The later pass stamps a row at its own
start. The earlier pass restamps it with an earlier time. The later pass then reads the row
as stale and removes it. The earlier pass moved on already and never puts it back. The item
is simply missing until the next full pass.

The hazard was always there, because the six-hourly reconcile can overlap a pull-to-refresh.
It became likely on 17 August, when a sign-in started a sync of its own beside the Library
tab's. It surfaced as an absence, with nothing failing anywhere. That is why it is worth a
lock rather than a retry.

`syncLibraryItems` holds a lock per (account, library). A second caller waits for the pass
in flight and takes its word. It waits rather than skips, because the caller asked for a
mirror and must be able to read one. It takes the first pass's word rather than repeats the
work, because a full pass over a large library is minutes of somebody's data.
`LibraryMirrorSweepTest` writes the interleaving out at the DAO level. It does not test the
lock, and it is why the lock cannot be removed.

### A line that says what loads, and moves nothing

The spinner in the top bar broke a rule Tom stated before: the UI must never jump. A top
bar's actions are a right-aligned row. So a spinner at the end of it slid the queue,
downloads and settings buttons left, and let them slide back on the way out. Nothing about
the spinner animated the layout. Three unrelated buttons did. It happened on launch, before
anybody touched the app, because the library mirrors itself the moment the app opens.

Nothing conditional lives in that row now, and that is the actual fix. With no state in the
actions, a state change has no arrangement to alter.

In its place is a full-width line under the top bar. It is drawn over the content rather
than above it, so it costs nothing in layout. Three things that each shifted the library
grid now share it: what a sync does, what a batch action did, and why either failed. The
batch and error lines used to sit between the filter chips and the first row of covers. So
three books marked finished pushed the whole grid down and let it spring back.

It says what it fetches, which the spinner never did. First "Checking the server", then
"Syncing <library>". Then a count and a real progress bar, once the server states how many
items exist. Then "Syncing where you got to" while positions reconcile. It stays
indeterminate until the total is known, instead of a pretense of knowledge.

And it says nothing at all about quick work. Work must last 400ms before the line is drawn.
The launch sync usually finds nothing changed and ends well inside that, which is exactly
the flicker in the complaint. Once drawn it stays 600ms after the work ends, so a filled bar
is seen to finish rather than seen to vanish. Tests drive the clock by hand and pin both. A
failure is the one kind that does not leave on its own, because it is still true when it
stops being new.

It waits to be tapped away. Two more came out of the same place. The collections screen
swapped its refresh button for a spinner, which moved the control that was just pressed. The
button stays put and greys now. And pull-to-refresh was wired to any sync, including the
automatic one, so its spinner answered a gesture nobody made. It answers only a real pull.

### A second drive, and two pages that do not exist

The car's speed button carries the fixed word "Speed". It reads the same at 0.8x as at 2.0x,
so a driver learns what a press did from a sentence at the wrong speed. The label is already
pushable, because the button list is broadcast on demand.

What is recorded is the part that is not mechanical. Does the label name the rate in force,
or the rate a press moves to? Does a head unit re-read a custom action's label, or cache it
from the first connection? Only a car can answer the second question, and a cached label
needs a different fix entirely.

"For you" is not lugu's. Nothing here builds a node by that name, so the section belongs to
the host. The entry names the two surfaces it can be, and which one we can feed. The reason
behind the ask survives either answer. The car is used to continue something, never to
discover, which argues against any further spend on discovery there.

Covers in the car are recorded as working, from the fix made on the previous drive.
Next-in-series is recorded as unproven. Each of its parts has a test. Its join — a book that
ends, and the next one that starts — exists only as an unticked manual line.

A new-episode notification opens Home, and the ask behind it is an episode page with show
notes. Both are recorded rather than built. The notification is a two-line fix that cannot
be made alone. `getLaunchIntentForPackage` is the bare launcher intent and carries nothing
about the episode. And there is nowhere for it to go.

One fact is worth having before that page is designed. An episode's own description is
already mirrored by the item sync and was never drawn. So the show notes for every episode
of every followed podcast sit on the phone, unread.

### Next

Four things, in the order they are worth doing.

The status line stopped at two screens, and that was deliberate. The sign-in screen, the
connection screen, the feedback form and the player still grow a line and push their content
down. The decision comes first. Does the line generalize past the two screens it is on? A
form is the one place where a message under its own field is defensible. The player is not a
form.

Next-in-series, end to end, in `:harness`. A book can be seeked to its final seconds, so the
check costs no more than any other instrumented test.

The episode page, with show notes, which then gives the notification somewhere to land.
Three decisions come with it, and they are in the backlog.

The car items and the M0 device pass wait for a drive and a signed release build. Nothing
else can answer the speed label or "For you".

## 2026-08-27 (the second parallel run) — a car button that had to be drawn, and a crash on the splash screen

Eight agents, four at a time, each in its own worktree with its own files. Six delivered a
commit. Two lost their budget, and both had saved edits, so the work survived in the
worktree and the orchestrator recovered it. That is the rule from the first run, proved a
second time: an agent that reads for a long time and commits nothing leaves nothing.

### The car speed button, and why the last fix changed nothing

Tom reported it from a real car. The button still showed an icon where the official app
shows "1.2x". The previous attempt had put the rate in the button's display name, so the
backlog recorded the open question as a choice between two labels. Both were wrong, because
the label was never the mechanism.

Android Auto draws a custom action as its **icon**. The display name reaches the host as a
name for a screen reader and for a tooltip. No display name can ever put text on that
button, so an app that shows a rate in a car draws the characters inside the icon.

Media3 1.11.0 already ships those icons. `CommandButton.ICON_PLAYBACK_SPEED_0_5` through
`_2_0` are vector drawables whose paths are the digits themselves, and
`media3_icon_playback_speed_1_2.xml` draws `1`, `.`, `2` and `x`. `CommandButton.Builder`
resolves the constant to a drawable id in this app's own resource table. So the fix is a map
from a rate to a constant, and no image is built at run time.

The negative result is worth more than the fix, because it stops the obvious re-attempt. A
run-time bitmap can never reach a car button. A projection host is a legacy controller, and
a `PlaybackStateCompat.CustomAction` carries an integer resource id in the app's own package
and nothing else. No `content://` grant changes that.

Seven rates carry digits and the player's fine adjustment moves in steps of 0.05, so a rate
such as 1.35x falls back to the plain icon. A wrong number on a car button is worse than no
number. A new `Player.Listener` pushes the layout on every rate change, so the player
screen, the notification and the car cannot disagree about the rate in force.

One question is still open and only a car answers it: whether a head unit re-reads a custom
action's icon on a pushed layout, or caches it from the first connection. If it caches, the
fix needs a different shape. That check is in `qa/auto.md`.

### A crash that no launch could clear

The deprecated `EncryptedSharedPreferences` was recorded as a decision waiting to be taken.
The decision turned out to be the smaller half of the item. The larger half was a live crash.

`EncryptedSharedPreferences.create` declares two checked exceptions, and Kotlin does not
force a catch. The token store called it from a `by lazy`, `AuthRepository.isSignedIn` called
the store, and `StartupViewModel` called that in an unguarded coroutine. A device restore or
a lock-screen change replaces the master key, which arrives as
`KeyPermanentlyInvalidatedException`. So the app died on the splash screen, on every launch,
and no launch could clear it, because the unreadable file stayed where it was.

The storage now answers a broken file with nothing rather than a throw. It repairs in two
steps — a new file, then a new master key — and it keeps the stored bytes on a storage error,
because a full disk must not cost a thirty-day session. A real loss is reported, and the
sign-in screen says why it asks instead of looking like the app forgot you.

The decision on the library itself: keep it. It is deprecated as a whole rather than in
parts, 1.1.0 is its last stable release, and AndroidX has no successor, so no version bump
ends this. A plain keystore key is the eventual answer, and it moves key generation, the
initialization vector, rotation and the invalidated-key case into the app. That path had to
work before a migration rather than after one. It works now, under the library that is here.

### Offline, without touching the radio

The last M0 sign-in line a machine could take was the offline one, and the radio was the
blocker: `cmd connectivity airplane-mode` starts at API 30, and the older route needs a
protected broadcast that the shell cannot send on either level. A test that skips one leg is
worse than the manual line, because CI fails any skip.

The harness cuts the network itself instead. A VPN that names lugu alone and never reads the
tunnel takes every route from that one app, by the same route on API 26 and API 36. Only lugu
enters the tunnel, so adb and every other job keep their network, and the platform closes the
tunnel with the harness process. The claim is narrower than airplane mode in three ways, and
all three are written down.

### What the pictures caught, and what a measurement caught instead

The podcast page and the player both gained a control, so five baselines moved. Both were
looked at as rendered images before anything was recorded, which is the lesson from the
inverted skip arrow: a picture caught what a code review missed.

The Downloads row went the other way. Its promise is that a failed download changes no
height, and that is arithmetic rather than appearance. So it is measured: four states in one
composition, all equal in height, including a failure of three sentences. A measurement
states the claim directly and needs no baseline on any host.

### Next

The download control still reads "Downloaded" rather than what a press does. It is the last
of the visible fixes, and it waits on a baseline record like the rest.

The sleep timer's restore is still unreached. The volume at the stopping tick is now proved
to be silence, so the restore is provably necessary, and it is four lines inside private
methods of a service that needs a device. The seam is named in the backlog.

`LibraryContent` is the pattern for the other screens. One screen is now photographed as the
screen rather than as its parts, and the same extraction closes the same gap everywhere else.

The car, a signed release build and a real tunnel still owe answers that no test can give.

## 2026-09-02 — two clocks in one comparison, and a speed nothing read

Three items: the two process-death races left open on 31 August, and a report from Tom
that a remembered speed is sometimes forgotten after an app update. Two of the three came
back to a fault of the same shape — a value that was right, read by something that asked
the wrong question of it.

### The book that resumed thirty seconds behind

The guess written down on 31 August was that a position is published before it is written,
the way the speed race worked. That was wrong, and no timer window explains thirty seconds.

`ProgressEntity.lastUpdateMs` is documented as the server's `lastUpdate`. `record` wrote
`System.currentTimeMillis()` into it on every local tick. So the column held a server
timestamp on an adopted row and a device timestamp on a local one, and
`ProgressConflictResolver` decided every conflict with `server.lastUpdateMs >
local.lastUpdateMs`. The answer to that comparison is clock skew, not who listened last.

With the server's clock ahead of the emulator's, a stale server position won every
conflict. The harness left the book at 49.9s. The outbox had last flushed 19.8s. So
pull-before-push read the server, found 19.8s stamped later than anything local, and
adopted lugu's own echo. That is "expected to be at least: 29885, but was 19829".

The rule now asks a question one clock can answer: did the server's copy come from
somewhere other than here. It is settled two ways, and neither leaves the server's clock.
The server's revision differs from the one this device last read. Or the position is not
the one the server accepted from here.

The second half is not redundant, and the reason is a property of the API.
`PATCH /api/me/progress/:id` answers with an empty body, so a push never learns the stamp
it earned. A stamp that always looks behind cannot mean "somebody else wrote this". What a
push does tell us is the position the server now holds, and that is enough to recognise
our own copy coming back. Two new columns hold it.

**Three more symptoms had the same cause, and each looked like its own bug.** The push
guard compared the same two clocks, and it ran in the branch that had just decided local
wins — so on a device running behind the server, lugu refused every push it had decided to
make, and progress silently stopped syncing outward. The login sweep compared them too, so
a week of offline listening went to whichever machine had the faster clock. And the
finished-download sweep reads the same column against a device-clock cutoff, which errs
towards deleting late rather than early, so that one is a note rather than a fix.

One decision inside the fix is worth keeping, because the obvious version of it is a
regression. `lastUpdateMs` is left holding the listening time, from whichever device did
the listening. Stamping an adopted row with this device's clock reads as tidier and would
flatten the Continue shelf every time a login sweep re-read the server, because every row
would get the same number. Two clocks meeting in an ordering key is tolerable. Two clocks
meeting in a conflict key is the defect. The column now says which it is.

### The speed that was never lost

Tom reported that after an update the speed is sometimes forgotten. It was in DataStore
the whole time. Nothing read it.

`applyRememberedSpeed` carries a doc comment naming the three ways an item reaches the
player. There were five. The two added since never asked what speed the item should be at,
so each took whatever the player happened to hold, which on a service that has just
started is 1x.

An app update is a force stop, so the next open is a fresh process. With "Always ready to
resume" on, arming loads the last book into a player holding no speed, and the press that
follows plays it at 1x. That is the report, and the setting is the "sometimes".

The other path was the end-of-book continuation, where the next volume in a series arrived
at 1x for the same reason. A test now sets the speed on volume **two** while volume one is
still playing, so the number it asserts cannot have been inherited from the player.

The lesson is about the comment rather than the code. A doc comment that enumerates its
callers is a comment that goes stale silently, and this one went stale twice.

### The cued volume that never loaded, still unattributed

The third item is not solved, and the run that found it cannot be re-read: CI keeps no
logcat from the instrumented legs. What the reading found is why there was nothing to
read.

`continueToNext` recorded nothing unless it succeeded. A book with nothing after it, a
resolve that returned null and a resolve that threw all left the same silence. From
outside, a queue that stops looks exactly like a queue that was empty. And it had one
attempt: the decision comes from Room, but the URLs need a play session, and the moment
this runs is the moment a phone is least likely to have a good connection. A book ends
when it ends, tunnel or not.

Both are fixed — every outcome is now a diary line that says which of the three it was,
and three attempts over about six seconds, abandoned the moment the listener starts
something else. The row stays open, because a fix that makes the next occurrence legible
is not the same as a diagnosis.

### Next

CI keeps no logcat from the instrumented legs. That is the single thing that would have
made this a diagnosis rather than two fixes and a note, and it is cheap.

The arming path's speed is still uncovered by any test, and it needs a device: the setting,
a process death and an app in the foreground cannot be staged together here.

The baseline recording route is unblocked. `origin/main` now carries
`record-baselines.yml`, so the download control that still reads "Downloaded" can be
fixed and photographed.
