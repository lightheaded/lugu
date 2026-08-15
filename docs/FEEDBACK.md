# Tom's user-testing feedback

Running record from daily driving. Each item says what was asked for and why, because
the reasoning is what should survive — the specific fix may change.

Status: `todo` · `doing` · `done` · `deferred`

Anything still open here is also collected in [BACKLOG.md](BACKLOG.md), alongside the
rest of the project's unfinished work.

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
| Notification's two side buttons skip by the configured seconds by default | done — see below |
| Notification button *icons* and *ordering* | todo — needs Media3 custom layouts; see [BACKLOG.md](BACKLOG.md) |
| Skip durations configurable, not hardcoded 10/30 | done |

**The notification jumped by ten minutes** (found by Tom, 15 Aug). Its side buttons were
remapped to chapter navigation unconditionally, and a book with no real chapters gets
*synthetic* ten-minute chapters — so one tap moved ten minutes. On a multi-file book the
step was a whole file, which is no better.

They now skip by the configured seconds unless chapter buttons are explicitly asked for.
The earlier attempt to control this by *withdrawing* the previous/next commands did not
work: Media3's default notification builds its layout from previous / play-pause / next
and has no seek button to fall back to, so withdrawing them removes the buttons rather
than changing them — and available commands are read when a controller connects, with
nothing firing a change when a setting moves. Switching behaviour while leaving the
commands advertised is deterministic and needs no cooperation from the notification.

The buttons still carry previous/next *icons*. Fixing that, and the ordering, needs a
Media3 custom layout — the same piece of work, still outstanding.

## Settings

**Everything should be configurable**, and the settings page should be well-categorised
and **searchable**.

| Item | Status |
|---|---|
| Skip-back and skip-forward durations | done |
| Which buttons appear in the player UI | done |
| Which buttons appear in the notification | done |
| What headphone/headset buttons do | todo — classifier logic exists, not surfaced |
| Categorised settings screen | done |
| Searchable settings | done — matches synonyms too, so "data" finds Wi-Fi-only and "rewind" finds skip-back |

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
| Author/series/narrator links | todo — still no author or series *page* to link to. M2 added series awareness (a parsed series title and number, and a "Next in series" shelf), so the data now exists; the pages do not |

## Notices (rewound, jumped)

Automatic corrections must be announced — a silent one is indistinguishable from the app
losing your place. But the notice must not **make the UI jump**, and it must go away on
its own.

| Item | Status |
|---|---|
| Rewind notice as an overlay rather than inline content | done |
| "Jumped" notice as an overlay too | done — this one was still inline and still reflowing the screen (reported 15 Aug) |
| Notices disappear on their own | done — 10s by default |
| Timeout configurable | done — 4/7/10/15/30s, under Settings → Notices |

The "jumped" notice was missed the first time round: only the *rewound* notice was moved
to a snackbar, while the jump banner stayed inline `Column` content and went on pushing
the cover art and transport down as it appeared and back up as it went.

Both now use `SnackbarDuration.Indefinite` with an explicit timeout rather than a
built-in duration, because Material offers only four and ten seconds and neither is
configurable — and a notice carrying an Undo has to stay up long enough to read a
timestamp and decide. Letting it time out keeps the new position, which is what the old
"Keep" button did, so that button is gone.

## Starting playback

| Item | Status |
|---|---|
| Tapping a podcast episode opens the player but the button still reads **Play** | todo — reported 15 Aug |

Tapping an episode in the list is unambiguous: it means *play this*. The player opens, but
the transport still shows Play, so the natural next move is to press it — and pressing it
during the gap either does nothing or pauses the playback that just started. A control that
invites a press it cannot honour is worse than a slow one.

The fix Tom asked for, in his order of preference:

1. **Actually start playing on the tap.** Preferred, and the honest fix: the button reads
   Play because nothing is playing yet.
2. **Failing that, show Pause optimistically** from the moment the tap is handled, and only
   fall back to Play if the load genuinely fails.

The second is a smaller change but it is a promise the UI cannot always keep, so it should
be the fallback for the part of the delay that cannot be removed, not the whole answer. The
delay itself is worth measuring before either: resolving an episode goes out to the server
for a play session before the player has anything to hold, and if the episode is already
downloaded that round trip should not be on the critical path at all.

## Downloads

| Item | Status |
|---|---|
| **Storage cap said "exceeded" immediately, on an 8 GB cap with nothing downloaded** | todo — reported 15 Aug, not yet reproduced or fixed |
| "Remove finished downloads" reads as *finished downloading*, not *finished listening* | todo — reword |

**The cap refusal is the serious one.** A refusal on an empty 8 GB allowance means the
arithmetic is wrong, and the wrongness is in the worst possible place: the check that
decides whether the app will download anything at all. Getting it wrong in this direction
makes offline mode simply unavailable, with a message that blames the listener's settings.

The check is `usedBytes + estimatedBytes > cap`. The cap itself is right (8 GiB, and the
picker writes the same units it reads), so one of the two other numbers is inflated.
Suspects, in the order they should be tested:

1. **`media.size` on a podcast is the whole feed, not the episode.** The estimate prefers
   the server's reported size and only falls back to duration × bitrate. On a podcast that
   field covers every episode the server holds, so downloading one 40-minute episode is
   charged as the entire archive — which clears 8 GB on any long-running show. This is the
   likeliest cause and the easiest to confirm.
2. **`media.size` on a book may cover more than the audio** — an ebook, or files flagged
   `exclude` that will never be fetched. Smaller error, same direction.
3. **`usedBytes` comes from `SimpleCache.cacheSpace`, which counts the whole cache
   directory.** If anything other than completed downloads has landed there, it is charged
   against the allowance. Note this is a *different* number from the one the Downloads
   screen displays, which sums the Room rows — so the screen can read near-empty while the
   check reads full, which is exactly what a report of "it says I'm over and I'm not"
   looks like.

Two fixes are wanted regardless of which suspect it is: the estimate must be the sum of
the tracks *in the manifest being downloaded* (which is already built, and already knows
about `exclude` and about single episodes) rather than a whole-item field, and the refusal
must state the actual numbers — "needs 12 GB, 8 GB allowed, 0 GB used" — because a refusal
that shows its arithmetic reports its own bug, while this one just looks like a setting the
listener chose badly. The live server was not reachable when this was written, so the
suspects above are unverified.

**The auto-delete wording**: "Remove finished downloads" is ambiguous in the one way that
matters, since *finished* can mean finished downloading — which would read as "delete
things the moment they arrive". It should say plainly that it means books you have listened
to the end of, and the choices should say "After a week" rather than "After 7d".

## Earlier findings

- **Notification rewind reset the book to zero, unrecoverably.** Fixed: transport
  buttons remapped away from `seekToPrevious()` (which seeks to position 0 on a
  single-file book), and schema v2 records position history so any jump can be undone.
- **Podcasts crashed the continue-listening shelf** through a fan-out join producing
  duplicate Compose keys. Fixed and covered by Room tests.
- **Login failure** was never the app: two stacked network misconfigurations between
  the phone and the server, both since fixed. The app now reports a wrong address as
  such instead of echoing a proxy's raw 404.
