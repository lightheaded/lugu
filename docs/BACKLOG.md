# Backlog — known unfinished work

Everything deliberately left undone, so nothing depends on someone remembering it.
Items originating from user testing live in [FEEDBACK.md](FEEDBACK.md) and are
cross-referenced here rather than duplicated.

Each entry says *why* it is not done, because that is what decides whether it still
matters later.

---

## From user feedback — not yet done

See [FEEDBACK.md](FEEDBACK.md) for the full reasoning behind each.

| Item | Why not yet |
|---|---|
| **Configurable headphone/headset buttons** | The classification logic exists (`MediaButtonClassifier`) but is not surfaced as choices. Needs a mapping of button gesture → action, and the classifier reading it |
| **Notification button icons and ordering** | The two side buttons now *do* the configured skip, but still carry previous/next icons, and their order is Media3's. Both need custom layouts (`CommandButton` + `setCustomLayout` + `onCustomCommand`) — the default provider builds previous / play-pause / next and offers no seek button to select |
| **Author / series / narrator links** | Those pages still do not exist, and linking to a dead end is worse than not linking. M2 got halfway: `seriesTitle` and `seriesSequence` are parsed and stored, so a series page now has something to render. Author and narrator have no equivalent yet |
| **Multi-select in every list** | Every action is one row at a time, so downloading eight episodes is eight trips through the same menu. Wants a selection mode shared by the episode list, the library grid and the queue — decided once, or it gets built three times. See [FEEDBACK.md](FEEDBACK.md#shape-of-the-app--home-libraries-and-what-belongs-where) |
| **Episode rows carry no date or number** | Title and duration only, which is not enough to pick an episode. Wants the publication date and the season/episode number where the feed has them. `season`, `episodeNumber` and `publishedAtMs` are already mirrored into Room and reach the screen — the row just does not draw them, so this is rendering, not plumbing. Applies to the car's episode list too |
| **Sort, filter and search in every list** | The episode list has no search, no filter, no sort and no paging, and renders every episode the server has — unusable on a podcast with a thousand. FTS already indexes episode titles and the plan's filter/sort UI was written for the grid; it should be one control used in both |
| **A Home separate from the library** | One screen does two jobs: shelves answer "what now", the grid answers "show me everything". They want to be separate destinations, with media types as tabs rather than chips sharing a screen with shelves that ignore them |
| **Shelves ignore the selected library** | Not a matter of taste — a real bug. The grid is scoped by `observeItems(account, libraryId)`, but `observeShelves(account)` and every shelf query filter on `serverId`/`userId` only, so podcasts sit on the shelves above a grid filtered to audiobooks. Whether shelves *should* span libraries is a real question; it has to be decided and shown, not left as an accident of two queries |
| **No way to switch off a media type** | Someone who never uses podcasts should not see them. A setting, reaching the tabs, the shelves, search and the car's browse tree — which currently offers a Podcasts node to anyone with a podcast library |

## Player and playback — M1 remainder

| Item | Note |
|---|---|
| Bookmarks | Server-synced; display must be speed-corrected |
| Silence skipping | `SilenceSkippingAudioProcessor` in the pipeline, with a toggle |
| Volume boost | Loudness enhancer for quiet recordings |
| Bluetooth-disconnect pause | Pause rather than duck, with per-device-class resume (car vs headphones — official app #612) |
| Chapter list UI | Prev/next and the current-chapter readout are done; there is no list to jump directly to a chapter |
| Sleep timer options in settings | Fade duration, shake-to-extend and its sensitivity, and rewind-on-wake are all designed but neither exposed nor implemented in the UI |
| Notification custom seek actions | Ties in with the ordering item above |
| Physical headset and car test matrix | The AVRCP thresholds are user-tunable by design as an escape hatch; the matrix doc does not exist |

## M0 gaps still open

| Item | Note |
|---|---|
| **Socket.IO delta updates** | The mirror is poll-and-sweep only. Edits and deletions made elsewhere take until the next sync to appear. Was M0 task 4 |
| **Process-death and reboot resumption never verified on hardware** | This is M0's central promise and the one path never exercised on a real device: kill the app mid-book, then press play on a headset |
| **M0 QA checklist never run** | [qa/m0.md](qa/m0.md) is written but unexecuted |

## M2 gaps

| Item | Note |
|---|---|
| **Offline playback has not been proven on hardware** | Downloading has now moved real bytes — a 629 MB book, downloaded and ready to play, 15 Aug. What is still untested is the other half: going offline for long enough to matter and confirming nothing is lost, and that the session replays on reconnect. Until then, "a week in airplane mode loses nothing" is a claim, not a result |
| **Transcoded (HLS) downloads** | A download assumes direct play — one file per track. An item the server will only transcode has no stable file URL to cache, so it cannot be downloaded at all yet, and nothing says so in the UI |
| **"Remove finished downloads" is ambiguous** | Reads as *finished downloading*. Should say it means books listened to the end, and spell out "After a week" rather than "After 7d" |
| **Tapping an episode opens the player still showing Play** | Playback does not start on the tap, so the button invites a press that arrives mid-load and cancels it. Should start playback on the tap, with an optimistic Pause covering whatever delay remains. See [FEEDBACK.md](FEEDBACK.md#starting-playback) |
| **Storage cap is checked, not enforced mid-download** | The estimate is charged against the cap before a download starts. A book much larger than its reported size can still overshoot; nothing aborts a download in flight |
| **Cache and Room can drift** | The cache is the truth about bytes and Room is the truth about state. `reconcile()` on start repairs the common case, but bytes evicted by the system outside the app would leave a row claiming "completed" |
| **No download progress in a notification per item** | One foreground notification covers all downloads. Fine for a few, vague for a queue of ten |

## M3 gaps

| Item | Note |
|---|---|
| **Nothing in M3 has been near a real head unit** | The browse tree, the custom buttons and voice search are all written against the documented contract and none has been run in the DHU, let alone a car. [qa/auto.md](qa/auto.md) is the procedure; it has never been executed |
| **Queue is not mirrored to a server playlist** | The plan offers an optional "▶ Up Next" playlist on the server. Deferred rather than half-built: it needs the playlist endpoints, a sync direction decision, and an answer for what happens when the same playlist is edited on the web. The queue is device-local and complete as it is |
| **Auto-download rules have never run against a real library** | The rules are unit-testable in principle and untested in practice; the worker fires every six hours on unmetered power, which is a slow way to find out it is wrong. Worth forcing once with `adb shell am broadcast` or by temporarily shortening the period |
| **New-episode detection needs one quiet pass to arm itself** | New is decided by comparing episode ids before and after a refresh, so the first refresh of any podcast establishes the baseline and reports nothing. Correct, but it means the feature looks broken for six hours after being switched on |
| **Continuation cannot be undone** | If lugu starts the next book on its own, the notice says so and the transport can stop it, but there is no "no, go back" that also removes it from history. Probably wants the same treatment as the jump undo |

## Known behaviour gaps

| Item | Note |
|---|---|
| Podcasts show no progress bar on the continue-listening shelf | Progress is per-episode; the shelf reads item-level progress and finds none. Cosmetic, but it makes the shelf look broken for podcasts |
| Losing connectivity mid-book stalls playback unless the book is downloaded | A downloaded book plays offline; a streamed one still stops when the connection does. Resuming a stream gracefully after a dropout is its own piece of work |
| Series shelves ignore a third of the library's series | Roughly a third of series entries have no parseable `#N`, so they are excluded from "Next in series" by design. Reading `GET /api/libraries/:id/series` would recover the real ordering for them |

## Architecture and tech debt

| Item | Note |
|---|---|
| **R8 / minification off for release builds** | Media3, Room and Hilt keep-rules are unproven here. Turning it on needs a real regression pass, since the failure mode is a runtime crash in a shipped build |
| `:core:queue` and `:core:testing` modules not created | M3 shipped the queue without either. `QueueRepository` lives in `:core:sync` with the other repositories, its DAO in `:core:db` with the other DAOs; a module holding one repository that depends on `:core:sync` anyway would be structure without substance. `QueueEntity` was already in schema v1, so the plan's real requirement — no migration for M3 — held |
| Speed formatting duplicated | `trimSpeed` in `:feature:player` and `formatSpeed` in `:feature:settings` do the same job. Wants one shared formatter, probably in `:core:model` alongside the other display helpers |
| `EncryptedSharedPreferences` / `MasterKey` deprecated | Still the practical option for encrypted token storage on Android; needs a replacement decision, not just a version bump |
| `hiltViewModel` deprecated | Moved to `androidx.hilt.lifecycle.viewmodel.compose`; mechanical import change across the feature modules |
| `MediaSession.ConnectionResult.AcceptedResultBuilder` deprecated | Mechanical, in `LuguPlaybackService` |
| Time formatting duplicated | `formatTime` in `:feature:player` and `formatDuration` in `:feature:library` overlap |

## Dev-process infrastructure — planned

Three pieces that exist to shorten the loop between a bug happening and it being
fixed. Researched 2026-08-15; the decisions below are made, the work is not done.
They are ordered by dependency: 2 is independent, 3 depends on 1.

### 1. Crash reporting — Sentry, opt-in, off by default — **DONE 2026-08-15**

Wired up: `sentry-android` 8.53.0 in `:app` only, `io.sentry.auto-init=false` in the
manifest, and `CrashReporting` initialising the SDK solely after consent. The toggle is
Settings → Diagnostics, off by default, stored in `CrashReportingPrefs`.

Decisions worth keeping:

- **Not `enabled = false`.** Sentry's docs say that "doesn't prevent all overhead from
  Sentry instrumentation", and whether a disabled SDK still opens a connection is not
  something their docs answer. Never initialising it is the only version of the claim
  that can be verified by reading the code.
- **`SharedPreferences`, not DataStore**, for the consent flag alone — it is read in
  `Application.onCreate` before anything may suspend, and the crash id is written from a
  process that is dying, where an asynchronous write does not land.
- **`autoSessionTracking` off.** It defaults to on and sends pings with no crash
  involved, which would have quietly falsified the README.
- **The Application observes the flag**, so withdrawing consent stops reporting at once
  rather than at the next launch, and `:feature:settings` never sees the Sentry
  dependency.
- **Accepted cost:** a crash before consent is lost. Documented and unavoidable.

The Gradle plugin was skipped: its job is uploading R8 mappings, and minification is off
(see *Architecture and tech debt*). Turning R8 on means adding the plugin at the same
time, or release stack traces arrive obfuscated.

`beforeSend` already records the crashing event's id, which is the hook item 3 needs —
Android has no `crashedLastRunEventId` (getsentry/sentry-java#2560, open since 2023).

### 2. Update channel — Obtainium, plus a per-build tagged release

**Why.** Every build currently ends in downloading an APK from Releases by hand and
tapping through an install. Turnaround is the whole point of daily-driving a pre-alpha.

**The blocker is in our own CI, not in Obtainium.** Obtainium takes the tracked
version from the **git tag name**. `ci.yml` pins the tag to `latest` forever, and
`versionName` is hardcoded `0.2.0-alpha01` in `app/build.gradle.kts` — so *both*
possible version sources are static and Obtainium would never see an update. The
documented escape hatch (`releaseTitleAsVersion` + `versionExtractionRegEx`) fails for
the same reason, since the release title interpolates that same static `versionName`;
and the maintainer declined to add richer parsing (Obtainium#1296), so regex detection
is the path of most resistance rather than the supported one.

Steps:

1. **Done 2026-08-15.** `ci.yml` publishes exactly one release per build, tagged
   `v<versionName>` and marked `--latest`; the rolling `latest` release is gone, and
   the stable link is now `/releases/latest/download/lugu-latest.apk`. `versionName`
   carries the CI run number so the tag and the installed version agree — with a
   static `versionName` a changing tag only trades "never updates" for "reinstalls
   forever". Releases are no longer marked `--prerelease`, because a repo where every
   release is a prerelease has no Latest for that link to resolve to. Confirmed
   beforehand on hardware: against the rolling tag Obtainium reported *"a
   pseudo-version is in use"* and `latest Installed / Latest`.
2. Bump `versionCode` per build. Not strictly required — Android only rejects
   *downgrades*, so an equal-`versionCode` same-signature reinstall is accepted — but
   it guards against shipping an out-of-order build and becomes mandatory if Play is
   ever used.
3. Install Obtainium, add the repo, pair Shizuku once for unattended silent installs.

**Constraint:** the repo must be public. Private-repo tracking is an open, unresolved
403 (Obtainium#2694, #2764); the PAT field is documented for rate limits, not private
access.

**Invariant:** the release signing key must never change, or in-place upgrade breaks
for every existing install.

**Fallback if the repo ever goes private again:** a self-hosted F-Droid repo published
from a separate public APK-only repo. More setup and a second key (the repo index
key), but F-Droid client ≥1.19 gives fully unattended background updates on Android
12+ with no Shizuku at all.

**Horizon:** Google's mandatory developer verification reaches sideloading in Sept 2026
(BR/ID/SG/TH) and globally in 2027. A free hobbyist tier covers up to 20 devices. Does
not affect this now; will eventually affect every channel except Play.

### 3. In-app feedback, including a post-crash prompt

**Why.** [FEEDBACK.md](FEEDBACK.md) is currently Tom typing up recollections after the
fact. Catching the detail at the moment of the failure is strictly better evidence, and
a crash the user can annotate ("it was the podcast, in the car, over Bluetooth") is
worth several that they cannot.

One `FeedbackScreen(prefill: CrashContext? = null)` with two entry points:

- **Settings → Send feedback**, `prefill = null`.
- **On launch**, when `Sentry.isCrashedLastRun()` is true — a banner: *lugu crashed
  last time. Want me to look into it?*

Both funnel into a single call —
`Sentry.feedback().capture(Feedback(comment).apply { associatedEventId = prefill?.eventId })`
— where a null id simply makes it standalone feedback.

**The gap to code around.** Android has no `crashedLastRunEventId`; the request has
been open since Feb 2023 with no PR (sentry-java#2560), though iOS has it. Hence the
`beforeSend` persistence in item 1 — roughly ten lines to bridge it.

Details that matter:

- Auto-attach app version, device and Android version, **whether playback was active
  and the player state** (the useful one for this app specifically), and the last N log
  lines.
- Put an expandable "exactly what gets sent" section above the Send button. That
  section is what makes the opt-in claim credible rather than decorative.
- Store an "already asked about this crash" flag keyed on the event id, so a crash
  loop does not nag on every launch.

Depends on item 1. Roughly half a day on top of it, most of it the Compose screen.

## Testing gaps

| Item | Note |
|---|---|
| No instrumented tests at all | The plan wants process-death and resumption tests as first-class CI citizens; there is no emulator matrix in CI yet |
| No screenshot tests | Roborazzi was planned from M1 |
| Sleep timer service integration untested | The arithmetic is well covered; the wiring that pauses and restores volume is not |
| `DownloadEngine` aggregation untested | The fold from per-file Media3 events to one item row — including the duration-weighted percentage used before file sizes are known — has no test. It needs a fake `DownloadIndex` |
| The offline resolution path is untested end to end | `ManifestBuilder` and the shelf and search queries are covered; `MediaResolver.resolveFromDownload` is not, because it needs the repository, the ledger and Room together |
