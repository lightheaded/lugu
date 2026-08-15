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
| **Nothing in M2 has run on hardware** | Downloads, the cache and offline playback are unit-tested but have never moved a real byte of a real book. Until that happens, "a week in airplane mode loses nothing" is a claim, not a result. This is the single largest untested surface in the project |
| **Auto-download rules** | Queue contents, next N in a series, latest N podcast episodes. The manual path and its storage accounting had to be right first, and the series data M2 added is what these rules will read |
| **Transcoded (HLS) downloads** | A download assumes direct play — one file per track. An item the server will only transcode has no stable file URL to cache, so it cannot be downloaded at all yet, and nothing says so in the UI |
| **Storage cap refuses everything on a fresh 8 GB allowance** | Reported by Tom, 15 Aug — the first real use of downloads on hardware. The estimate reads a whole-item `media.size` (on a podcast, the entire feed) instead of summing the manifest actually being fetched, and `usedBytes` reads the cache directory rather than the completed rows the Downloads screen shows. See [FEEDBACK.md](FEEDBACK.md#downloads); the refusal should also print its arithmetic |
| **"Remove finished downloads" is ambiguous** | Reads as *finished downloading*. Should say it means books listened to the end, and spell out "After a week" rather than "After 7d" |
| **Tapping an episode opens the player still showing Play** | Playback does not start on the tap, so the button invites a press that arrives mid-load and cancels it. Should start playback on the tap, with an optimistic Pause covering whatever delay remains. See [FEEDBACK.md](FEEDBACK.md#starting-playback) |
| **Storage cap is checked, not enforced mid-download** | The estimate is charged against the cap before a download starts. A book much larger than its reported size can still overshoot; nothing aborts a download in flight |
| **Cache and Room can drift** | The cache is the truth about bytes and Room is the truth about state. `reconcile()` on start repairs the common case, but bytes evicted by the system outside the app would leave a row claiming "completed" |
| **No download progress in a notification per item** | One foreground notification covers all downloads. Fine for a few, vague for a queue of ten |

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
| `:core:queue` and `:core:testing` modules not created | `QueueEntity` is in the schema so M3 needs no migration; the modules would be dead weight until then |
| Speed formatting duplicated | `trimSpeed` in `:feature:player` and `formatSpeed` in `:feature:settings` do the same job. Wants one shared formatter, probably in `:core:model` alongside the other display helpers |
| `EncryptedSharedPreferences` / `MasterKey` deprecated | Still the practical option for encrypted token storage on Android; needs a replacement decision, not just a version bump |
| `hiltViewModel` deprecated | Moved to `androidx.hilt.lifecycle.viewmodel.compose`; mechanical import change across the feature modules |
| `MediaSession.ConnectionResult.AcceptedResultBuilder` deprecated | Mechanical, in `LuguPlaybackService` |
| Time formatting duplicated | `formatTime` in `:feature:player` and `formatDuration` in `:feature:library` overlap |

## Dev-process infrastructure — planned

Three pieces that exist to shorten the loop between a bug happening and it being
fixed. Researched 2026-08-15; the decisions below are made, the work is not done.
They are ordered by dependency: 2 is independent, 3 depends on 1.

### 1. Crash reporting — Sentry, opt-in, off by default

**Why.** The app crashes after playing for a while (Tom, 15 Aug) and nothing survives
to say what happened. A long-session Media3 failure is most likely an ANR or an OOM,
and neither is something a `try`/`catch` ever sees — which is the argument for a real
reporter rather than more logging.

**Why Sentry.** It is the only free option that can attach a user's comment to a
*specific* crash event (`associatedEventId`), which is what makes item 3 below work.
EU (Germany) data storage is confirmed available on the free Developer plan.

Steps:

1. Create the Sentry org **with EU data storage selected at creation**. The region
   appears to be fixed once the org exists — Sentry's help-centre articles on this are
   login-walled and could not be read directly, so treat it as a one-way door and
   choose deliberately rather than planning to migrate.
2. Add `sentry-android` + the Sentry Gradle plugin to `libs.versions.toml`. Costs
   roughly 2 MB of APK. The plugin's mapping upload is moot until R8 is turned on.
3. `AndroidManifest.xml`: `io.sentry.auto-init` = `false`. **Do not** reach for
   `enabled = false` instead — Sentry's own docs say it "doesn't prevent all overhead
   from Sentry instrumentation", and whether the SDK still opens connections in that
   state is unconfirmed. Deferred init is the only documented way to ship a genuine
   nothing-until-consent guarantee.
4. Consent toggle in `:feature:settings`, DataStore-backed, default false, with search
   synonyms ("crash", "diagnostics", "privacy"). `SentryAndroid.init()` runs only once
   it is true.
5. Options at init: `sendDefaultPii = false` (already the default), `attachScreenshot`
   and `attachViewHierarchy` false (already the default), and
   **`autoSessionTracking = false` explicitly** — it defaults to `true` and sends
   session pings with no crash involved, which would quietly break the claim in 7.
6. `beforeSend`: when `event.isCrashed`, persist `event.eventId` to prefs. That
   callback runs in the crashing process before it dies, and it is the only way to
   recover the id later — see item 3.
7. Amend the README Privacy section: "No telemetry, no analytics, no crash reporting"
   → no analytics, opt-in crash reporting off by default. The locked decision and the
   executing-agent guardrail in [EXECUTION-PLAN.md](EXECUTION-PLAN.md) are already
   amended to match.
8. Once the repo is public, apply for the Sentry open-source grant (5M errors/month,
   no term limit). **Caveat:** the stated guidance is "a friendly license like Apache
   or MIT", and whether **GPL-3.0 copyleft qualifies is unstated by Sentry either
   way** — ask in the application rather than assuming, and do not make the plan
   depend on the grant. The free Developer plan (5k errors/month) is already far
   beyond what this app will produce.

**Accepted cost:** a crash occurring before consent is given is lost. Documented and
unavoidable — "the SDK can catch errors and crashes only after you've initialized it".

**Reversible later:** GlitchTip or Bugsink self-hosted use the same SDK with a
different DSN. One catch — GlitchTip silently drops the feedback API, which would cost
the crash↔comment link in item 3.

**Rejected.** *Firebase Crashlytics*: cannot attach a user comment to the crash being
sent — the value lands on the *next* report (firebase-ios-sdk#6431) — and it would
block F-Droid permanently. *ACRA*: fully FLOSS and has a comment dialog built in, but
captures Java/Kotlin exceptions only, so it would miss the ANR and native cases that
are the leading suspects here.

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
