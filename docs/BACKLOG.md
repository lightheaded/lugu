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
| **Selection mode does not reach the library grid** | The episode list, the queue and the downloads screen share one selection mode; the grid does not have it yet, and "mark finished" is the action still missing from all of them (upstream app#1297) |
| **The grid still shows no progress for a podcast** | Home falls back to the most recent episode's progress; the grid does not. A cover reading "60%" for a whole feed is arguably worse than none, so this is a decision to take rather than an oversight to fix |
| **Sort and filter on the downloads screen are not remembered** | `LibraryPrefs` has keys for the grid and the episode list only, and reusing either would tie two unrelated screens together |

## Player and playback — M1 remainder

| Item | Note |
|---|---|
| Notification custom seek actions | Ties in with the ordering item above |
| Physical headset and car test matrix | The AVRCP thresholds are user-tunable by design as an escape hatch; the matrix doc does not exist |
| Bookmarks on a podcast episode | Audiobookshelf addresses a bookmark by library item alone, so there is nowhere to put an episode's. Local-only bookmarks would be bookmarks that vanish on a new phone (server #884 asks for the same thing) |
| Configurable rewind-after-pause curve | Smart rewind scales with time away, which is the right shape; the thresholds are not adjustable. Upstream app#205, 20 comments of people disagreeing about the right number — which is itself the argument for making it a setting |

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
| **Storage cap is checked, not enforced mid-download** | The estimate is charged against the cap before a download starts. A book much larger than its reported size can still overshoot; nothing aborts a download in flight |
| **A streamed listen does not fill the download cache** | Streaming and downloading already share one cache, but a book listened to over the network is not retained, so listening ahead does not pre-warm anything. Upstream calls the split between the two concepts the root cause of much of its download trouble (app#1371) |
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

## Why playback stops — open

Reported 15 August. The record now exists; the cause does not.

| Item | Note |
|---|---|
| **The root cause is still unknown** | Every candidate — the process being reclaimed, audio focus lost, an unsuitable output after a Bluetooth switch, a network stall leaving the player idle, a crash — looks identical from outside. The diary distinguishes them, so the next step is to read it after it happens rather than to guess and ship |
| **The record has never been read in anger** | Written and unit-tested, but no real stop has been diagnosed with it yet. Until one has, it is a tool that has not been used, not a tool that works |
| **A paused notification may still vanish** | Media3 leaves the foreground state when playback pauses, and Android may then reclaim the notification. Upstream has this open twice (app#1800, app#1571): resuming means reopening the app. Not yet observed here, and worth watching for in the diary before changing anything |
| **A car that neither projects nor sets car mode reads as headphones** | Telling a car from headphones properly needs `BluetoothClass`, which needs `BLUETOOTH_CONNECT` on Android 12+ — a runtime permission prompt for a resume rule. The current answer infers it from the connected car-projection controller or `UiModeManager`, which covers Android Auto and misses a plain Bluetooth car stereo. Only affects which *resume* switch applies; pausing is unaffected |
| **The foreground-service refusal is recorded, not handled** | `onForegroundServiceStartNotAllowedException` writes a diary line. What it should *do* — retry, or fall back to a plain notification — depends on when it actually happens, which is not yet known |

## Known behaviour gaps

| Item | Note |
|---|---|
| Podcasts show no progress bar on the continue-listening shelf | Progress is per-episode; the shelf reads item-level progress and finds none. Cosmetic, but it makes the shelf look broken for podcasts |
| Losing connectivity mid-book stalls playback unless the book is downloaded | A downloaded book plays offline; a streamed one still stops when the connection does. Resuming a stream gracefully after a dropout is its own piece of work |
| Series shelves ignore a third of the library's series | Roughly a third of series entries have no parseable `#N`, so they are excluded from "Next in series" by design. Reading `GET /api/libraries/:id/series` would recover the real ordering for them |

## Architecture and tech debt

| Item | Note |
|---|---|
| **R8 is on, and has never run on a device** | Turned on 15 August with hand-written keep rules for Media3, Room, Hilt, kotlinx-serialization, Ktor, OkHttp, Coil and Sentry. A clean `assembleRelease` proves only that nothing is missing at compile time — every path R8 can break fails at runtime and only in a release build. **A device pass on a release APK is owed before the next release is treated as trustworthy**: sign in, play a streamed book, play a downloaded one, open Android Auto, and change a setting |
| **Release stack traces are obfuscated with nowhere to send the mapping** | R8 renames everything, so a crash report from a release build is unreadable until `mapping.txt` reaches Sentry. The Gradle plugin does this but fails the build without an auth token. Either gate the plugin on the token in CI, or attach `mapping.txt` to the GitHub release and retrace by hand |
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
