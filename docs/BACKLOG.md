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

## Testing gaps

| Item | Note |
|---|---|
| No instrumented tests at all | The plan wants process-death and resumption tests as first-class CI citizens; there is no emulator matrix in CI yet |
| No screenshot tests | Roborazzi was planned from M1 |
| Sleep timer service integration untested | The arithmetic is well covered; the wiring that pauses and restores volume is not |
| `DownloadEngine` aggregation untested | The fold from per-file Media3 events to one item row — including the duration-weighted percentage used before file sizes are known — has no test. It needs a fake `DownloadIndex` |
| The offline resolution path is untested end to end | `ManifestBuilder` and the shelf and search queries are covered; `MediaResolver.resolveFromDownload` is not, because it needs the repository, the ledger and Room together |
