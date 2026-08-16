# Backlog — known unfinished work

Everything deliberately left undone, so nothing depends on someone remembering it.
Items originating from user testing live in [FEEDBACK.md](FEEDBACK.md) and are
cross-referenced here rather than duplicated.

Each entry says *why* it is not done, because that is what decides whether it still
matters later.

Items sourced from the official app's issue tracker carry their issue number and their
thumbs-up count. That count is evidence of demand and nothing more — it does not decide
what belongs in this app, and several of the highest-voted items are recorded here as
declined for exactly that reason.

---

## From user feedback — not yet done

See [FEEDBACK.md](FEEDBACK.md) for the full reasoning behind each.

| Item | Why not yet |
|---|---|
| **One author credited two ways is two authors** | The browse pages group on the stored string, so "Corven, James T. R." and "James T. R. Corven" are separate, and a multi-author credit is its own entry. The alternative is guessing at name order for every language a library might hold, which is a worse kind of wrong — this wants the server's own author records, not smarter parsing here |
| **The grid still shows no progress for a podcast** | Home falls back to the most recent episode's progress; the grid does not. A cover reading "60%" for a whole feed is arguably worse than none, so this is a decision to take rather than an oversight to fix |
| **Sort and filter on the downloads screen are not remembered** | `LibraryPrefs` has keys for the grid and the episode list only, and reusing either would tie two unrelated screens together |

## From the upstream issue review

Read on 15 August 2026 across `advplyr/audiobookshelf-app` (404 open) and
`advplyr/audiobookshelf` (968 open), filtered to what an independent Android client can
deliver against today's public API. Counts are thumbs-up on the opening post.

**Most of this shipped on 16 August.** Rows below marked *done* are kept rather than
deleted, because the demand figure is the argument for why they were worth doing and the
next person will want it. What is still open is marked as such.

Two things the review settled that are worth keeping. The four highest-demand mobile asks
— a play queue (99, open since 2022), auto-downloading episodes (45), deleting finished
downloads (41) and multi-select (27) — are already built, so the direction is right. And
several of upstream's worst recurring bugs are ones lugu cannot have: downloads orphaned
by a changed server address and attributed to the wrong user (app#1386, app#1320) because
every row here is keyed by server and user id rather than by URL; the shared-storage
permission crashes (app#773, app#1143, app#1273 — 83 comments) because the cache is
app-private; and the out-of-memory kills (app#1668, app#1433) because this is not a
Capacitor app. Those are worth *not* regressing into.

### Connectivity and access

The most under-served area upstream, and all of it is HTTP-client configuration rather
than anything the server has to agree to.

| Item | Note |
|---|---|
| **Custom headers on every request** — done 16 Aug | app#254, 38 👍 and 78 comments — the single best value-to-effort item in the review. Cloudflare Access service tokens (`CF-Access-Client-Id` / `-Secret`) and every header-auth reverse proxy are simply unusable without it. Headers belong with the account, not the app, since they are per-server; and they are credentials, so they go where the tokens go and never into a log, a crash report or the playback record |
| **A LAN address as well as a WAN one** — done 16 Aug | app#209, 34 👍. A reverse proxy is materially slower than a direct connection, so people want a second address used when on known networks. Only safe because progress here is keyed by server and user id rather than by connection — the bug that makes this dangerous upstream (app#1401) is one lugu does not have |
| **Client certificates (mTLS)** — done 16 Aug | app#1419, 14 👍. The connection fails outright with no way to supply one. Needs a key-store picker and an OkHttp `SSLSocketFactory`, and the certificate is a credential like any other |
| **Never gate a request on our own guess about connectivity** — audited 16 Aug, nothing found | app#1702. Upstream's reachability check reports offline over Tailscale and WireGuard, silently disabling sync for VPN users. lugu should try the request and let it fail rather than deciding in advance — worth an explicit audit that nothing does the latter |

### The car

Upstream's least stable surface: eight open bugs and four regressions closed in recent
months. app#475 (CarPlay, 97 👍) is the clearest evidence that in-car listening is the
dominant context, even though it is not our platform.

| Item | Note |
|---|---|
| **Oldest-first episode order, per podcast** — done 16 Aug | app#473 and server#1321, 43 👍 together. Continuation always takes the newest unplayed episode, which is right for a news show and wrong for a serial. The choice belongs to the podcast, not to a global setting |
| **The current chapter in the car** — done 16 Aug | app#489, 8 👍. There is empty space in the Auto player where the chapter title should be, and the chapter is the only "where am I" a driver can use |
| **A latest-episodes node spanning every podcast** — done 16 Aug | app#679 and server#1516. There is no cross-feed view of what is new, in the car or on the phone. lugu already refreshes followed podcasts, so the data is local |
| **Chapters as the car's queue** — declined 16 Aug | app#1673. Media3 builds the platform queue from the *player's timeline*, one entry per media item, and turns "skip to queue item" back into a seek on that timeline. A book already occupies that timeline, and every recorded, synced and resumed position is mapped through it — so chapters-as-queue means making the timeline a list of chapters, which is the same lie the notification's progress bar refuses, and worse, because a queue takes seeks back in. Chapter commands stay reachable from the car and the chapter is now named in the metadata; what is lost is jumping to an arbitrary chapter from the head unit's own list. Reasoning is in `CarNowPlaying.kt` |
| **No sleep timer while driving** — done 16 Aug, suspended rather than cancelled | app#1478. A timer that fires mid-journey is a bug wearing a feature's clothes. One rule, given that car mode is already detected |
| **The Auto bugs upstream keeps re-opening** | app#1570 (Continue tab missing), app#1482 (wrong total length), app#1491 (only downloads visible unless the app is running). The browse tree is served from Room, so the last should be impossible here — but that is a claim until a head unit says otherwise. Fold into [qa/auto.md](qa/auto.md) |

### Playback and media controls

| Item | Note |
|---|---|
| **A paused notification that survives** | app#1800 and app#1571, 21 comments. Upstream's disappears a couple of minutes after pausing, so resuming means reopening the app. Already listed under *Why playback stops* as a risk here too; the fix is a foreground-service lifecycle decision, and the diary will say whether we have the problem |
| **A configurable rewind-after-pause curve** | app#205, 20 comments of people disagreeing about the right number — which is itself the argument for making it a setting rather than picking one |
| **Skip intro and outro** | app#749, 7 👍. Per-podcast trim offsets, remembered, so a fifteen-second sting is not heard three hundred times |
| **Duck rather than cut for other audio** — done 16 Aug | app#1259. A navigation prompt should lower the book, not interrupt it |
| **xHE-AAC** | server#4236, 15 👍 and 38 comments, the most-discussed server enhancement of the year. Android 9 and later decode it natively, so a native client can play files the web player cannot — a real differentiator, provided the server serves the bytes rather than refusing to probe them |
| **Tasker-compatible intents** — done 16 Aug, see [automation.md](automation.md) | app#858, 21 👍. Exported play/pause intents. Small, and it wins the automation audience outright |
| **Media buttons on watches and remotes** | app#352 (17 comments, open since 2022, `help wanted`) and app#1048, where the headphone pause button rewinds instead of pausing. `MediaButtonClassifier` exists precisely to prevent the second; neither has been tested against real hardware. Belongs with the headset test matrix above |
| **Battery drain as a standing requirement** | app#1446 is the most-discussed Android bug ever filed against the official app, at 81 comments. Not a ticket to close — a thing to measure before each release, alongside the sensor and wake-lock rules already followed here |

### Browsing, sleep timer and distribution

| Item | Note |
|---|---|
| **A–Z rail on the browse pages too** | The grid has one; the author, series and narrator lists do not, and a library with four hundred authors needs it just as much. They have a search box in the meantime |
| **Edit collections from the phone** — done 16 Aug | app#207, 6 👍. Read-only on mobile upstream, though the API allows writing |
| **Confirm covers are cached to disk** | app#907, 7 👍. Coil caches by default, but "by default" is an assumption, and a library that re-fetches every cover feels slow in exactly the way people describe |
| **Sleep after N chapters** — done 16 Aug | app#202, 6 👍. Chapter count rather than clock time, which is how people actually decide when to stop |
| **The sleep timer must survive a pause** — done 16 Aug | app#1317. Pausing silently cancels it upstream. A rule worth writing down before it gets written wrong |
| **F-Droid** | app#58, 45 👍, open since December 2021 and never delivered. A large cohort will not install from Play. Needs reproducible builds, which a Kotlin build gets nearly for free — upstream is blocked by a Nuxt toolchain that emits non-deterministic filenames (app#1388), which is exactly the kind of problem this project does not have |

### Considered and declined

Recorded so the same question is not re-litigated in six months.

| Item | Why not |
|---|---|
| Ratings out of five (app#236, 73 👍; server#1153, 47 👍) | The third-highest request in the app repo and it needs somewhere on the server to put the rating. A local-only rating vanishes with the phone, which is worse than none |
| Ebook and comic reading (app#1009, app#772, app#800, app#1107 — 83 👍 together) | lugu is an audio player. A reader would roughly double the surface area to serve a different need |
| CarPlay, App Store, AltStore (app#475, app#541, app#1346 — 128 👍) | iOS. Worth noting only as evidence of how much of upstream's attention iOS consumes |
| UPnP, DLNA, Sonos (app#1424, app#1506) | Real demand, but a second transport stack with its own failure modes. Chromecast in M4 first, and only revisit if that lands cleanly |
| Kobo sync (server#3504, 249 👍) | The highest-voted open issue in either repo, and entirely server-side. It says where the community's mass is, not what this client should do |
| **Chapter-scoped notification progress** (app#239, 11 👍) | Attempted 16 Aug and withdrawn. The bar is drawn from the platform media session's playback state — one position and duration, read by the notification, the player screen, the scrubber and a car's seek bar alike, with no per-controller value and no hook that runs only on the way to the notification. Reporting chapter-relative numbers means a second, disagreeing notion of where the book is, in exactly the place `AbsoluteTiming` and `ChapterAwarePlayer` exist to keep single — which is how a resumed book starts in the wrong chapter. Not worth a bar that moves. The chapter title is in the notification text instead, where it costs nothing. Worth knowing that the complaint is narrower than it looks: on a multi-file book the bar already spans the current file rather than the whole book, so the forty-hour bar is the single-file case. Reasoning is in `LuguNotificationProvider`'s KDoc |

Android TV (app#606) and Wear OS (app#676) are already M4 spikes and are not repeated here.

## Left behind by the notification and continue-listening work

| Item | Note |
|---|---|
| **Notification persistence is weaker before Android 14** | From API 34 a foreground-service notification can be swiped, so the service is pinned while a book is paused and the notification stays until dismissed. Below that it cannot be swiped at all — which is exactly why Media3 detaches on a pause — so pinning would trade a notification that vanishes for one that cannot be got rid of, which is the worse complaint. On 8 to 13 persistence therefore rests on the service no longer stopping itself when the app is swiped away, plus Media3's own ten-minute foreground window. How long that lasts is the system's judgement, not ours |
| **"Which title does a Continue row show" is implemented twice** | `ContinueRows` in `:playback` for the car and `ShelfCard` in `:feature:library` for the phone both decide that an episode names itself and the show becomes the subtitle. Same rule, two homes, and they can drift. It belongs in `:core:model` with the other display helpers |
| **An armed item does not hold the notification open** | Deliberate: nobody has asked for it yet, and it will be armed again the next time the app opens. Worth revisiting if it turns out that an armed book with no notification is indistinguishable from no book at all |

## Left behind by the 16 August work

Each of these is a consequence of something that landed, and each is written down because
the person who finds it will otherwise think it an oversight.

| Item | Note |
|---|---|
| **`SleepMode.EndOfChapter` can be stepped over** | It resolves the boundary from the *current* position on every tick, which is deliberate — skipping a chapter should re-arm against the new one. The cost is that the position only moves in tick-sized steps, so at 1.5× the loop can see 599.7 and then 600.2, by which time the target is the next chapter's end. `SleepCountdown` in `:playback` compensates with a one-tick tolerance; the model itself still has the sharp edge, and anything else that reads it will hit the same thing. `SleepMode.Chapters` had the far worse version of this — a target that receded exactly as fast as it was approached, so it could never come due — and that one is fixed at the source |
| **Downloaded manifests and car artwork do not follow the LAN address** | Both use the account's primary address, baked in at queue time or built for the browse tree. They still carry headers and the token, so they work; they just do not get the faster route. Streamed audio and player artwork do follow it |
| **No `io.socket` code has ever run against a live server** | See the M0 row above. R8 keep rules are now in place, which is the failure that would have been hardest to attribute |
| **Collections cannot be edited offline, by design** | A collection is shared state with an order the server owns, and there is no right merge of two offline reorderings — a replayed "add" would silently undo somebody else's removal. An edit made offline fails immediately with a reason rather than being queued |
| **Adding to a collection is not in the grid's multi-select bar** | Only on the item page. The server has `/batch/add` and `/batch/remove` endpoints if this is wanted |
| **Collection listings are heavy and rate-limited to five minutes** | The server's library-collections endpoint is always fully expanded — `minified` is documented but never read by the handler — and runs to several megabytes on an ordinary library. So it is tied to deliberate acts rather than to opening a book page |

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
| **Socket.IO deltas are written but unproven** | Built 16 Aug against the server source rather than its docs, which say of themselves that they are unmaintained. Every event name was read out of `SocketAuthority.js` and the controllers. What no test can reach: that engine.io-client works against OkHttp 5 at runtime, that the handshake succeeds and `init` arrives, that the socket path is right through a reverse proxy, and that re-emitting `auth` every fifteen minutes keeps events flowing once the token behind them has rotated. A live server settles all four in a minute |
| **Process-death and reboot resumption never verified on hardware** | This is M0's central promise and the one path never exercised on a real device: kill the app mid-book, then press play on a headset |
| **M0 QA checklist never run** | [qa/m0.md](qa/m0.md) is written but unexecuted |

## M2 gaps

| Item | Note |
|---|---|
| **Offline playback has not been proven on hardware** | Downloading has now moved real bytes — a 629 MB book, downloaded and ready to play, 15 Aug. What is still untested is the other half: going offline for long enough to matter and confirming nothing is lost, and that the session replays on reconnect. Until then, "a week in airplane mode loses nothing" is a claim, not a result |
| **Transcoded items still cannot be downloaded — now deliberately** | Resolved 16 Aug as a refusal that explains itself rather than as a feature. Three independent reasons, any one sufficient: an HLS playlist is minted against a play session that expires and takes its segment URLs with it, so the download could not be keyed by item and track the way every other one is; a transcode has no size until it exists, so a truncated download is indistinguishable from a complete one and the failure surfaces in a tunnel; and it would be a re-encode, at a bitrate the server chose, of a file the server already holds intact. The productive direction was the other one — widening the supported mime types so fewer items transcode at all. Reasoning is on `DownloadRefusal.TranscodeOnly` |
| **`audio/x-aiff` is claimed but unverified** | It is in the supported-mime list and no AIFF extractor could be confirmed in Media3's published formats. Pre-existing, and the opposite mistake from the one just fixed: overselling makes the server hand over a file nothing can decode. Worth one test against a real AIFF file |
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
| **Process death itself is still not covered** | Instrumented tests now exist and run on an API 26/36 emulator matrix, but instrumentation runs *inside* the process under test, so `am force-stop` on this package kills the test runner. The resumption test destroys the playback *service* instead — which is what Android actually does when reclaiming memory — and reaches `onPlaybackResumption` from Room as a cold start would. A real kill needs a second process: a `com.android.test` module with its own application id. The adb recipe for doing it by hand is in [qa/instrumented.md](qa/instrumented.md) |
| **Screenshot baselines cover components, not most screens** | 23 baselines in both themes, verified on every `./gradlew build`. But only Settings drives its real screen: every other screen takes a Hilt view model over final Room, DataStore and Ktor classes that cannot be faked, so those baselines are of the screens' own components arranged as the screen arranges them. A change to the order of blocks *inside* a screen file will not fail them. Making each screen's content a stateless `internal` composable would close it |
| **The Roborazzi Gradle plugin cannot be applied** | 1.46.1 reaches for AGP's `TestedExtension`, which AGP 9.3.1 removed, so applying it fails configuration. Its only job is setting two system properties, which the root build file now sets directly — so verification is the default and re-recording is `-Proborazzi.record`. Revisit when Roborazzi supports AGP 9 |
| Sleep timer service integration untested | The arithmetic is well covered; the wiring that pauses and restores volume is not |
| ~~`DownloadEngine` aggregation untested~~ | Done 16 Aug, and the premise here was wrong: no fake `DownloadIndex` was needed. Extracting the pure fold into `DownloadAggregation` reached the part that can actually be wrong, and a fake would have been the harder path — a Media3 `Download` is only obtainable through a real `DownloadManager`, which wants a cache directory, a database and a thread pool |
| The offline resolution path is untested end to end | `ManifestBuilder` and the shelf and search queries are covered; `MediaResolver.resolveFromDownload` is not, because it needs the repository, the ledger and Room together |
