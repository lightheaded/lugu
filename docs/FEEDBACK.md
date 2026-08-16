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
| Notification button *icons* and *ordering* | done 16 Aug |
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

**The icons and the order are now ours too** (16 Aug). The buttons did the right thing but
still carried previous/next icons in Media3's order, because the default notification
provider builds previous / play-pause / next and offers no seek button to pick. A custom
layout replaces the provider's choice entirely: the buttons come from the settings list,
in the order chosen there, with icons that match the configured skip — a 30-second skip
gets the icon that says 30, and a duration with no matching icon gets the generic one,
because a button labelled 30 that moves 15 is worse than one with no number at all.

The order is set by the order of taps in Settings → Buttons, which is a slightly odd
interaction and the right trade for a list of four things: it answers "which" and "in what
order" with one gesture instead of adding a second control. The layout is pushed to live
sessions when the setting changes, which is the part the earlier attempt got wrong — a
notification that only picks up a preference on the next cold start reads as a setting
that does nothing.

## Settings

**Everything should be configurable**, and the settings page should be well-categorised
and **searchable**.

| Item | Status |
|---|---|
| Skip-back and skip-forward durations | done |
| Which buttons appear in the player UI | done |
| Which buttons appear in the notification | done |
| What headphone/headset buttons do | done 16 Aug |
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
| Author/series/narrator links | done 16 Aug |

**The pages came before the links**, which is why this took until now. Linking to a dead
end is worse than not linking, so the author, series and narrator on an item page stayed
plain text while there was nowhere for them to lead.

All three are computed from the local mirror rather than fetched. The server has an author
page and a series page in its own web client and no API that hands either to a client, so
grouping locally is both the only option and the faster one — and it means the pages work
with the network off, like everything else that reads from Room.

The series page is the one with a real decision in it: it groups on the parsed
`seriesTitle` and orders by the parsed `seriesSequence`, never on the raw `seriesName` the
server sends. That string has the number baked into it, so two books in one series do not
compare equal — grouping by it would group nothing, and ordering by it puts "#10" before
"#2".

## Shape of the app — home, libraries, and what belongs where

Reported 15 Aug, after M2. Four related asks, all about a library too big to work through
one row at a time.

| Item | Status |
|---|---|
| **Multi-select everywhere** — pick several episodes and download them in one go | done 15 Aug |
| **Sort and filter everywhere** — find an episode in a podcast with a thousand of them | done 15 Aug |
| **A "Home" or "Dashboard" separate from the library itself** | done 15 Aug |
| **Library selection does not scope what is shown** — podcasts appear when only audiobooks is selected | fixed 15 Aug |
| **Let a media type be switched off entirely**, so someone who never listens to podcasts never sees them | done 15 Aug |
| **An episode row shows only its title and length** — no date, no season or episode number | done 15 Aug |

**Multi-select.** Every action in the app was one row at a time, which is fine for a book
and wrong for a podcast: downloading eight episodes meant eight round trips through the
same menu. There is now a selection mode — long-press to enter it, then Download, Add to
queue, Play next and Remove download acting on the set — and it is one reducer and one bar
shared by the episode list, the queue and the downloads screen, rather than three
implementations that would drift. Selection is a property of a *list*, which is why the
queue enters it from a menu instead of a long-press: on that screen the long-press already
means drag, and the two gestures cannot both win.

**What an episode row says.** It showed a title and a duration, and that was all. It now
carries the **publication date** and the **season and episode number** where the feed has
them — `S2 E14 · 12 Mar · 48m`, with the number dropped when the feed does not number its
episodes. Those are how anyone decides which episode to play: a date says whether this is
the one from this week, and a number says where it sits in a run. Without them a screen of
similar-looking titles is guesswork.

Nothing needed fetching or migrating. `season`, `episodeNumber` and `publishedAtMs` were
already mirrored into Room by the item sync and already reached the screen; the row simply
did not draw them. Recent dates read as "Today", "Yesterday" and "3 days ago", because for
a podcast the useful question is almost always *how new is this*.

**Sort and filter.** The episode list had no search, no filter and no sort control. On a
podcast with a thousand episodes that is unusable. There is now one control — search, an
ordering, and a five-way filter — shared by the episode list, the library grid and the
downloads screen, with the ordering and filter remembered between visits because a list
someone has ordered is a decision, not a mode.

The sorting and filtering *policy* lives in `:core:model` rather than in each screen. The
alternative is three implementations that quietly disagree about what "in progress" means,
which is the sort of inconsistency nobody reports as a bug and everybody notices.

Paging was deliberately not added. A `LazyColumn` already composes only what is on screen,
so a thousand rows costs a thousand small objects and nothing else; the problem was never
the rendering, it was that there was no way to narrow the list.

**Home versus library.** The single screen did two jobs. The computed shelves — Continue,
Next in series, Almost finished — answer "what should I play now"; the grid answers "show
me everything". They are now two tabs of one destination: Home is the shelves plus a
one-tap resume for the most recent thing, and Library is the browse with the picker, the
search and the sort. Tab state deliberately does not enter the back stack — switching tabs
is not something anyone wants to press Back through.

The resume affordance is the answer to the most common complaint about every client of
this server: getting back to what you were listening to took five taps. It now takes one.

**Which was also the bug.** The confusion was not a matter of taste — the screen genuinely
mixed libraries. The grid was scoped by the selected library (`observeItems(account,
libraryId)`), but every shelf above it was scoped to the account only, since each shelf
query filtered on `serverId`/`userId` and nothing else. Selecting the audiobook library
filtered the grid and left podcasts on the shelves directly above it.

Every shelf query now takes a library id, and the caller has to pass one or explicitly
pass none. Whether shelves *should* be per-library is a real question — "continue
listening" arguably spans everything — so it is a setting rather than a decision imposed
on everyone, and when the shelves are spanning everything the screen says so. The
selection itself moved into `LibraryPrefs`, because two screens depending on a value one
of them owns is how they end up disagreeing for a frame.

**Switching a type off.** A media type can now be switched off in Settings → Library. It is
filtered at `observeLibraries`, which is the single place every surface reads from, so it
reaches the tabs, the shelves, search and the car's browse tree by construction rather than
by remembering to do it four times. Nothing is deleted and syncing carries on, so switching
it back on is instant rather than a resync.

**Podcasts on the continue shelf.** Not reported, but found while fixing the above: a
podcast never showed progress on Home, because progress is stored per episode and the shelf
read the item-level row that a podcast does not have. The shelf now falls back to the most
recently updated episode of that podcast — which is also the episode the resume affordance
plays, since for a podcast "continue listening" means the episode you were on.

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
| Tapping a podcast episode opens the player but the button still reads **Play** | fixed 15 Aug |

Tapping an episode in the list is unambiguous: it means *play this*. The player opened, but
the transport still showed Play, so the natural next move was to press it — and pressing it
during the gap either did nothing or paused the playback that had just started. A control
that invites a press it cannot honour is worse than a slow one.

Playback was in fact already being started on the tap; the button was the thing that was
wrong. `PlaybackConnection` now holds a "starting" flag from the moment `play()` is called
until the player genuinely reports playing, and reports `isPlaying` as true throughout, so
the transport shows Pause for the whole load.

This is a promise the UI makes on the player's behalf, which means it has to be surrendered
honestly: the flag is cleared on failure and on any explicit pause, or it becomes a lie
that leaves a Pause button on a player that is not playing.

The remaining delay is still worth measuring. Resolving an episode goes out to the server
for a play session before the player has anything to hold, and if the episode is already
downloaded that round trip should not be on the critical path at all.

## Always one press from resuming

Reported 16 Aug, and the largest of these is not a feature request but praise with a gap in
it: *this was one of my biggest gripes with the official app — after it was closed, after a
while or sometimes right away, I had to find again what I had been playing.*

| Item | Status |
|---|---|
| The mini player and the tab bar read as one slab | fixed 16 Aug |
| **The Continue card's play button never changes state** — cannot pause from there | fixed 16 Aug |
| **Arm the last-played item when the app opens**, so play is one press away | done 16 Aug — opt in |
| **The media notification disappears after a pause** | fixed 16 Aug |
| **Continue listening should be per episode**, not per podcast | done 16 Aug |

**The notification is the load-bearing one.** `MediaSessionService` leaves the foreground
when playback pauses and Android then reclaims the notification, usually within a couple of
minutes and sometimes at once — so a paused book becomes something you have to go and find,
which is precisely the complaint. Upstream has the same thing open twice (app#1800,
app#1571).

It is now a choice of three rather than a switch with an opinion baked in, because the
opposite complaint is equally real and was raised in the same breath: *some people might
find it annoying, like Spotify taking over playback when headphones are connected — that's
super annoying.*

- **Only while playing** — goes as soon as playback stops. The quietest, and what the system
  does by default.
- **Until you dismiss it** — the new default. Stays after a pause until it is swiped, which
  is what every other media app does.
- **Always ready to resume** — also loads the last thing played when lugu opens, so a headset
  button works without opening anything first.

The third one earns its own paragraph, because the line it must not cross is thin. Arming is
**not** resuming: lugu loads the item, paused, at its stored position, and waits. It never
starts playing on its own. That is a different act from resuming when headphones connect,
which has its own switch and is off by default — and the distinction is the whole reason the
two are not one setting.

**Per-episode continue listening.** The shelf grouped by item, which is the right question
for a book and the wrong one for a podcast: three part-heard episodes of one show collapsed
into a single card, and reaching the other two meant going to the show's page and finding
them. The shelf now lists what is being *listened to* rather than which items are in
progress, so each episode is its own entry, named by the episode with the show beneath it.

That also fixed a latent unit error. A podcast entry's progress must be measured against the
episode's duration, not the feed's — the two differ by orders of magnitude, so reading the
wrong one reports three minutes into a three-hundred-hour feed. The shelf row now carries
the played duration explicitly rather than leaving each screen to guess which it meant.

**The Continue card** compares on the item *and* the episode before deciding it is the thing
playing, since a podcast now has several entries on that shelf and they must not all light up
together.

## Playback stops on its own

Reported 15 Aug: playback stops occasionally, and there is no way to tell whether the app
crashed or simply stopped.

| Item | Status |
|---|---|
| **Playback stops without being asked to** | instrumented 15 Aug — cause not yet known |
| No way to find out why after the fact | fixed 15 Aug |

The honest position is that we did not know why, and could not have found out. Every cause
looks identical from the outside: the process being reclaimed by the system, audio focus
lost to another app, an unsuitable output after a Bluetooth switch, a network stall leaving
the player idle with nothing retrying, a crash, or the sleep timer doing exactly what it was
told. Guessing between those and shipping a fix for the wrong one is worse than waiting.

So the first thing built was the record rather than a fix. **Settings → Diagnostics → Why
playback stopped** is a local, always-on log of starts, stops, errors, suppression reasons,
the service being created and destroyed, and the app being swiped away. It is written to a
file, which matters more than it sounds: the case with the least evidence is the process
being killed, and a file is what survives that. An unexplained gap followed by a fresh
"process started" line *is* the diagnosis.

Two deliberate choices:

- **It is local and it is not telemetry.** Crash reporting is opt-in and off by default and
  that is not going to change, so a diagnosis that only worked for people who switched on
  telemetry would be no diagnosis at all. Nothing leaves the phone unless it is deliberately
  attached to a report.
- **It rides along, never alone.** When crash reporting *is* on, the recent entries are
  attached to an outgoing crash as breadcrumbs, so a report arrives with the history that
  led to it instead of a stack trace with no context.

Upstream has the same complaint open twice, and both are informative. app#1530 ("randomly
stops playing") is unresolved, and app#204 ("playback closes when connecting to car
Bluetooth") has been open since 2022 marked *unable to reproduce* — which is what happens
when nobody can see what the app was doing at the time.

## Downloads

| Item | Status |
|---|---|
| **Storage cap said "exceeded" immediately, on an 8 GB cap** | fixed 15 Aug — cause confirmed against the live server |
| **A download showed no progress in the app, only in the notification** | fixed 15 Aug |
| "Remove finished downloads" reads as *finished downloading*, not *finished listening* | fixed 15 Aug |

**The cap refusal is the serious one.** A refusal on a nearly empty 8 GB allowance means
the arithmetic is wrong, and the wrongness is in the worst possible place: the check that
decides whether the app will download anything at all. Getting it wrong in this direction
makes offline mode simply unavailable, with a message that blames the listener's settings.

The check is `usedBytes + estimatedBytes > cap`. The cap itself was right, so one of the
other two numbers was inflated — and a capture from the live server settled it in one
call. **`media.size` on a podcast is the whole feed.** The estimate preferred that field,
so tapping one 56 MB episode of a 327-episode show charged 18.36 GB against an 8 GB cap.
Every podcast in the library over about 8 GB was undownloadable, one episode at a time,
and the two biggest are 18.36 GB and 8.81 GB. On a book the same field is wrong more
quietly: it counts the ebook and any file flagged `exclude`, neither of which is fetched.

The fix is the one the manifest was already in a position to give. Every track and audio
file carries `metadata.size` — its own byte count — so the manifest now records a size per
track and the estimate is their sum: exactly the files about to be downloaded, nothing
else. Where a server records no size, bitrate × duration stands in, and only then the old
duration guess.

Two things went in alongside it, because the bug was reported the way it was for a reason:

- **The refusal states its arithmetic** — "Needs 56 MB, and 600 MB of the 8 GB cap is
  already used" — rather than asserting that the allowance is full. A refusal that shows
  its numbers reports its own bug; the old one just looked like a setting chosen badly.
- **The storage readout and the cap check now read the same number.** The readout summed
  the Room rows while the check read `SimpleCache.cacheSpace`, so the screen could show
  near-empty while the check saw full. Two numbers for one quantity is how a correct
  refusal gets reported as a lie.

**Progress was invisible in the app** while the notification showed it moving, which is a
strange thing to watch on a 629 MB book. Media3's `DownloadManager.Listener` fires when a
download changes *state* and never once in between, so the row written at "queued" stayed
at 0% until the file finished; the notification looked fine because `DownloadService`
polls on its own timer. The engine now polls too, but only while a file is actually
downloading — a download parked waiting for Wi-Fi costs nothing, and the state change that
resumes it starts the polling again.

**The auto-delete wording** was ambiguous in the one way that matters, since *finished* can
mean finished downloading — which reads as "delete things the moment they arrive". It now
says "Delete books you have listened to", explains that it applies once you reach the end,
and offers "After a week" rather than "After 7d". A setting whose worst reading is
destructive has to be worded for that reading.

## Podcast trimming, and adverts

Asked on 16 Aug alongside a batch of backlog items: *skip intro and outro — and maybe skip
for ads too, if it's the same mechanism.*

| Item | Status |
|---|---|
| **Skip a podcast's intro and outro**, per show | done 16 Aug |
| **Skip adverts** | done 16 Aug, for marked adverts only — see below |

**It is the same mechanism at the point of playing, and a different one at the point of
finding**, and the distinction is what decides what can honestly be offered.

An intro and an outro are fixed offsets from the ends of an episode. The same sting opens
every episode of a show, so one number covers all of them forever. An advert is somewhere
in the middle, at a different place and a different length every week — no fixed offset can
find one.

So adverts are skipped where the episode *says* where they are: a chapter whose title names
it as advertising. A useful number of shows ship those markers, because the same markers
drive the chapter list in every podcast app, and lugu already parses them — so this costs a
title match and nothing else.

What is deliberately not attempted is finding an *unmarked* advert. That needs audio
fingerprinting against a database of known adverts — a different kind of program, with a
network service behind it, and a false positive silently eats a minute of the show. A skip
that removes narration is worse than an advert that plays.

The title match is on the whole title with punctuation stripped, so "[Ad]" and "Sponsor
Message" match while "Adam's Return" and "Broad Strokes" do not. A substring match on "ad"
would have skipped both of those, and that is the direction that matters.

**Every skip announces itself and can be undone**, through the same notice the app already
uses for a position it corrected — a skip is exactly the case where a silent correction and
a lost minute of audio are indistinguishable from the listener's side. The notice now names
the cause, so it reads "Skipped the intro from 0:00 to 0:15" rather than leaving the numbers
to speak for themselves.

**Trim belongs to the show, not to the episode**, stored beside the per-podcast speed and
for the same reason: setting it per episode would mean setting it again every week. A
podcast's own page shows whether it is following the global default or carries its own,
because a show trimmed to nothing and a show following a default of nothing hold identical
numbers and behave differently the moment that default moves.

## Earlier findings

- **Notification rewind reset the book to zero, unrecoverably.** Fixed: transport
  buttons remapped away from `seekToPrevious()` (which seeks to position 0 on a
  single-file book), and schema v2 records position history so any jump can be undone.
- **Podcasts crashed the continue-listening shelf** through a fan-out join producing
  duplicate Compose keys. Fixed and covered by Room tests.
- **Login failure** was never the app: two stacked network misconfigurations between
  the phone and the server, both since fixed. The app now reports a wrong address as
  such instead of echoing a proxy's raw 404.
