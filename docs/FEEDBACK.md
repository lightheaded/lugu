# Tom's user-testing feedback

Running record from daily driving. Each item says what was asked for and why, because
the reasoning is what should survive — the specific fix may change.

Status: `todo` · `doing` · `done` · `deferred`

## Transport controls — priority order

Actual usage, most to least frequent:

1. **Seek back 10–30 s** to re-listen to something missed. This is the dominant action.
2. **Finding a specific place**, forward or backward — scrubbing, longer jumps.
3. **Chapter skips** — rare, but wanted occasionally.

The UI *and* the notification must be laid out in that order of prominence. The current
layout treats chapter skip as a peer of seek, which over-serves the rarest action.

| Item | Status |
|---|---|
| Seek back/forward as the primary pair either side of play/pause | done |
| Chapter skip present but visually secondary | done |
| Notification ordered the same way, seek before chapter | done |
| Skip durations configurable, not hardcoded 10/30 | done |

## Settings

**Everything should be configurable**, and the settings page should be well-categorised
and **searchable**.

| Item | Status |
|---|---|
| Skip-back and skip-forward durations | done |
| Which buttons appear in the player UI | done |
| Which buttons appear in the notification | done |
| What headphone/headset buttons do | todo |
| Categorised settings screen | done |
| Searchable settings | todo |

## Speed

| Item | Status |
|---|---|
| Default speed for everything | done |
| Option to set audiobooks and podcasts separately | done |
| Remember speed per book | done |
| Remember speed per **podcast series**, not just per episode | done |
| Configurable speed presets | done |
| Speed buttons take too much screen space — move out of the main player | done |
| "2.0x" chip renders vertically (text wrapping) on Tom's phone | done |

The per-podcast-series point is the subtle one: an episode is the wrong unit, because a
listener picks a speed for a *narrator*, and a podcast's narrator is constant across
episodes. Speed is therefore keyed on the podcast's library item, not the episode.

## Navigation

Links should be consistent **everywhere**, not just on one screen:

- Book title → book page
- Author → author page
- Series → series page
- Narrator → narrator listing

| Item | Status |
|---|---|
| Now-playing title links to the item page | done |
| Author/series/narrator links | deferred — needs author and series pages, which do not exist yet (M2 discoverability) |

## Rewind notice

The smart-rewind notice is wanted, but the current inline implementation **makes the UI
jump** as it appears and disappears. It should be a toast-like overlay that does not
affect layout.

| Item | Status |
|---|---|
| Rewind notice as a snackbar rather than inline content | done |

## Earlier findings

- **Notification rewind reset the book to zero, unrecoverably.** Fixed: transport
  buttons remapped away from `seekToPrevious()` (which seeks to position 0 on a
  single-file book), and schema v2 records position history so any jump can be undone.
- **Podcasts crashed the continue-listening shelf** through a fan-out join producing
  duplicate Compose keys. Fixed and covered by Room tests.
- **Login failure** was never the app: a DNS misconfiguration for an internally-routed
  service, then lugu missing from the VPN configuration. The app now reports a
  wrong address as such instead of echoing a proxy's raw 404.
