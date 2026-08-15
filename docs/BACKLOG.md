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
| **Searchable settings** | Categories exist and are the ordering search will index; search needs a flattened index of setting titles, subtitles and synonyms, plus a query UI |
| **Configurable headphone/headset buttons** | The classification logic exists (`MediaButtonClassifier`) but is not surfaced as choices. Needs a mapping of button gesture → action, and the classifier reading it |
| **Notification button *ordering*** | Only *visibility* is configurable today. Explicit ordering needs Media3 custom layouts (`CommandButton` + `setCustomLayout` + `onCustomCommand`), which is a chunkier change than advertising commands |
| **Author / series / narrator links** | Those pages do not exist. Linking to a dead end would be worse than not linking. Belongs with M2 discoverability |

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
| **Authenticated live API capture** | `scripts/capture-api.sh` exists and redacts properly, but has never been run — it needs credentials in `local.properties`. Payload shapes remain source-verified rather than capture-verified (see `research/05-api-live-notes.md`) |
| **Process-death and reboot resumption never verified on hardware** | This is M0's central promise and the one path never exercised on a real device: kill the app mid-book, then press play on a headset |
| **M0 QA checklist never run** | [qa/m0.md](qa/m0.md) is written but unexecuted |

## Known behaviour gaps

| Item | Note |
|---|---|
| Podcasts show no progress bar on the continue-listening shelf | Progress is per-episode; the shelf reads item-level progress and finds none. Cosmetic, but it makes the shelf look broken for podcasts |
| Losing connectivity mid-book stalls playback | No offline fallback until M2 downloads land |

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
| `ChapterAwarePlayer` untested | The class that stops the notification destroying a position has no test. It needs a fake `Player` to drive `seekToPrevious()` and assert it never lands at zero — worth doing given what it prevents |
| Sleep timer service integration untested | The arithmetic is well covered; the wiring that pauses and restores volume is not |
