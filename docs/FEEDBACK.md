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
| Now-playing **cover** links to the item page too | done 16 Aug |

**The cover was the obvious tap and was not one** (found by Tom, 16 Aug). The title above it
already led to the book, but the cover is the biggest thing on the screen and is the picture
*of* the book — it is what a thumb goes to. It now leads exactly where the title does, which
for a podcast episode is the show's page, since that is where an episode lives.

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
| **The grid shows no progress for a podcast** | done 17 Aug |
| **Sort and filter on the downloads screen are not remembered** | done 17 Aug |

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

**A podcast cover had no progress bar.** Reported twice, and recorded in the backlog as a
decision rather than a bug — the argument being that a cover reading "60%" for a whole feed
is worse than none. That argument was wrong, and the app had already settled the question
everywhere else: the author, series, narrator and collection grids all fall back to the most
recently played episode, and only the library grid still looked progress up by an item-level
key that a podcast never has.

So the bar is drawn, and the thing the old argument was worried about is answered by saying
what the number means instead of leaving it to be guessed: a screen reader hears "Latest
episode 62% listened", where a book's says "62% listened". The bar had no spoken description
at all before this, so the one fact separating a part-heard book from an untouched one was
unavailable to anyone using TalkBack.

The half with no defence was the filter. With no item-level row, a part-heard podcast counted
as *not started*, so "In progress" — the one filter that exists to find what you are in the
middle of — hid every podcast in the library.

What a borrowed episode row still may not do is finish the feed. One finished episode of a
four-hundred-episode show does not make the show Finished, so the bar is drawn from the
episode and the Finished state is only ever an item's own.

**The downloads screen forgot its sort and its filter** on every visit. They were held in the
view model, which dies with the screen — deliberately, to avoid tying this screen's ordering
to the library grid's, since it sorts over bytes and download times the grid knows nothing
about. The answer was two keys of its own rather than no keys, and a test now asserts that
changing one leaves the other alone, because sharing them is the obvious wrong fix.

The search box is deliberately not remembered. An ordering is a decision about how a list is
read; a search is a thing being looked for, and coming back to three of forty downloads with
a stale word in the box reads as lost data.

**And a retry on that screen did nothing at all.** A download stopped by the storage cap keeps
the ordinary retry button, and pressing it is refused by the same cap before anything is
enqueued — but the refusal was being thrown away, so the screen did not change. A button that
visibly does nothing reads as broken. It now shows what the refusal says, which already states
its own arithmetic: "Needs 56 MB, and 7.6 GB of the 8 GB cap is already used."

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

## The spinner in the top bar, and the rule it broke

> *"I never want any UI to jump, as I've mentioned before. Currently there seems to be a
> loader for something (not clear what) inside the top bar on the right. I sometimes see a
> spinner there, which disappears. Maybe we could have a loading bar as a thin overlay under
> that bar, full-width. Would actually be cool to know what exactly is it loading or syncing
> or refreshing."*

Reported 17 Aug. The same rule as the section above, broken somewhere else — which is the
point worth taking from it: "do not make the UI jump" is not a fact about notices, and
fixing it notice by notice will keep producing this report.

| Item | Status |
|---|---|
| **The top bar's buttons shift when a sync starts and finishes** | fixed 17 Aug |
| **The spinner never said what was being loaded** | fixed 17 Aug |
| **A sync that finishes quickly flashes an indicator nobody can read** | fixed 17 Aug |
| **Sync, batch and error lines pushed the library grid up and down** | fixed 17 Aug |
| **The pull-to-refresh spinner answered gestures nobody made** | fixed 17 Aug |

**What was moving, and why it was not obvious.** A top bar's `actions` are a *right-aligned*
row. The spinner was the last thing in it, so every time it appeared the queue, downloads
and settings buttons slid left to make room, and every time it went they slid back. Nothing
was animating and nothing was wrong with the spinner itself — the movement came from three
buttons that had nothing to do with syncing. It happened on launch, because the library
mirrors itself the moment the app opens, so the app twitched before anyone had touched it.

**Nothing conditional lives in that row now.** That is the actual fix and it is structural:
with no state in the actions, there is no arrangement for a state change to alter.

**The line under the bar.** Taken as Tom described it — full width, thin, and drawn *over*
the top of the content rather than above it, so it costs nothing in layout. Three things
now share it, because all three were separately shifting the grid: what a sync is doing,
what a batch action just did ("Marked 3 items"), and why either failed. The batch and error
lines used to sit between the filter chips and the first row of covers, which meant marking
three books finished pushed the whole grid down and then let it spring back.

**It says what it is loading.** "Checking the server", then "Syncing *Audiobooks*", then
"Syncing Audiobooks — 240 of 1,100" with a real progress bar once the server has said how
many there are, then "Syncing where you got to" while listening positions reconcile. The
bar is indeterminate for the first second and determinate after, rather than pretending to
know a total it has not been told.

**And it says nothing at all about a quick one.** Work has to last 400ms before the line is
drawn. The sync on launch usually finds nothing changed and is over well inside that — which
is exactly the case that had been flashing a spinner for long enough to notice and not long
enough to read. Once drawn it stays 600ms after the work ends, because a bar that vanishes
the instant it fills reads as a glitch rather than as a finish. Both numbers are pinned by
tests that drive the clock by hand, so "a sync that took 200ms" is stated rather than raced
for.

**A failure is the one thing that does not leave on its own**, since it is still true when
it stops being new. It waits to be tapped away, in the same place, coloured as a problem.

**One more thing found while in there.** Pull-to-refresh was wired to *any* sync, including
the automatic one, so the pull spinner appeared on launch with nobody's finger on the
screen. It now answers only a real pull; everything else is the line's job.

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

## An episode page, and where a notification should land

Reported 17 Aug: *I got a "new episode" notification, tapped it, and it took me to "home" of
the app. I want it to take me to the episode page. We don't have an episode page yet, do we?
I'd like to see show notes and decide whether I'd like to hear the episode from there.*

| Item | Status |
|---|---|
| **The new-episode notification opens Home** | todo |
| **There is no episode page** | todo — correct, there is not |
| **Show notes are not shown anywhere** | todo |

**The notification is the small half.** `NewEpisodeNotifier` builds its tap action from
`getLaunchIntentForPackage`, which is the bare launcher intent: it says "open lugu" and
carries nothing about which episode caused it. So it lands wherever the app would have
landed anyway, and the notification's whole point — *this* episode, now — is lost at the
moment it is acted on. It cannot be fixed on its own, because there is nowhere for it to go.

**There is no episode page, and an episode's own description is already on the phone.** The
sync mirrors `description` onto every episode row and nothing has ever drawn it. The item
page renders the *show's* description; the episode list draws a title and a subline of date,
number and length. So the show notes for every episode of every followed podcast are
sitting in Room, unread.

Three things this needs decided before it is built, none of them large but none of them
obvious:

- **What a tap in the episode list means.** Today it is unambiguous and deliberately so:
  tapping an episode plays it, and that was itself a fix — the player used to open showing
  Play, inviting a press it could not honour. An episode page makes a tap ambiguous again,
  so the page needs its own way in (the row's overflow, or a chevron) rather than stealing
  the tap.
- **Show notes are HTML.** A podcast feed's description is markup with links in it, and the
  show description is currently rendered with a plain `Text`, so a feed that uses `<p>` and
  `<a>` shows its tags. Whether that is already visibly wrong on the show's own page is
  worth looking at while this is open; either way an episode page cannot ship rendering raw
  markup, and links in show notes are usually the point of reading them.
- **Where the page goes in the back stack**, since it is reachable from a notification with
  the app not running. Landing on it from cold with no way back to the library is the
  failure mode; the item page already solves this and the answer should be the same one.

The play affordance belongs on that page too — the request is to *decide* from there, which
means the decision and the action are in one place.

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

## Starting a book when the headphones connect

Asked on 16 Aug, and worth quoting because the request already contained its own
specification: *for the official app I use Tasker to start playing x seconds after the
headset with a given name or MAC connects. Can we build that in, and always start whatever
was last playing?*

| Item | Status |
|---|---|
| **Start playing when a chosen device connects** | done 16 Aug |
| **Only for named devices**, not any headphones | done 16 Aug |
| **Wait a configurable few seconds first** | done 16 Aug, then replaced by watching for the switchover — see below |
| **Always the last thing played**, from cold | done 16 Aug |
| **Show the app version** | done 16 Aug, Settings → About |
| The waiting notification stays on screen after the book starts | fixed 16 Aug — see below |
| "Not now" is hidden until the notification is expanded | fixed 16 Aug — see below |
| Only one earbud of a pair starts a book | fixed 16 Aug — see below |

### The first device pass, and three things it found

Reported 16 Aug, from a real pair of Jabra Elite 10 Gen 2 earbuds. All three are the kind of
fault only hardware produces.

**The waiting notification never went away.** It sat under the player for the rest of the
session. The removal was there and did nothing, for a reason worth writing down: the waiting
notification is the one handed to `startForeground`, and a service's current foreground
notification cannot be cancelled — the system holds it on screen for as long as the service
is foreground under that id. It only becomes removable once `startForeground` has been called
again with the player's id. The code asked Media3 to post and then cancelled immediately,
racing a post that had not happened yet and losing every time. It now waits for the player's
notification to actually appear before taking its own down.

**"Not now" needed a two-finger pull to reveal, by which time the moment had passed.** In
Android's ordinary notification layout the action buttons live in the expanded view, and the
channel was low importance, so the notification arrived collapsed with the button behind a
gesture that takes longer than the thing it interrupts. The channel is now high importance,
which brings the notification forward with the button already showing — and makes no sound,
no vibration, and only alerts on the first post of a countdown. Coming forward and making a
noise are separate things, and only the first is wanted.

**Only the left earbud started a book.** Correctly diagnosed on the spot: each side is its own
device with its own address, under the same name. Every side has to be chosen, because on
Android 12 and later only an associated device is observed at all — the right one connecting
did not reach lugu, so there was nothing to match against and no cleverness available at the
moment of connection. What could be fixed is everything around that: the list now groups the
sides of one pair into a single row marked **Both sides**, removing it removes both, and the
text under the picker says that earbuds appear once per side and both want adding. Two rows
with the same name and no way to tell them apart was the real defect.

**The existing setting looked like this and was not.** "Resume when headphones reconnect"
continues something a *disconnection* interrupted: it needs the player still loaded, a
disconnect on record as the cause, and half an hour or less since. It cannot start a book
with the app closed and the process dead, which is the whole of what was being asked for,
and it fires for any headphones rather than a chosen pair. Both settings now exist, and
say plainly which is which.

**The delay was the interesting part of the request, and the follow-up was better still:**
*the delay is a workaround for the audio channel switchover — can we detect when it has
switched and start then?* Yes, and it is the right question. A headset announces itself
before the audio route has moved, and a book started inside that gap plays its first
sentence to the room; a timer is a guess at how long that takes, on hardware that varies.

The platform reports output devices arriving, so **the switchover is watched for rather
than guessed at**. On a fast headset a book now starts sooner than any delay anybody would
have configured; on a slow one it waits longer than most people would have set, and is
right. If no audio output turns up within twenty seconds the start is abandoned — plenty
of Bluetooth devices are not audio devices, and a watch connecting should end there.

The setting survives as a deliberate *extra* on top, defaulting to one second, because a
device appearing in the output list and the audio policy having finished moving are not
quite the same moment. **None is a choice**, for the case the follow-up named: wanting the
book as early as it can possibly start. Nobody is guessing at the switchover either way.

**What Tasker could not offer, and this does, is a way to say no.** The waiting notification
carries a "Not now" button, and refusing suppresses the rest of that connection's events for
a minute — because connecting a headset fires several of them, seconds apart, and without
that the next one would restart the countdown a moment after it was cancelled. A cancel
button that visibly does not work is worse than none.

**Four things are never played over**: a call in progress, another app already holding the
audio, a device that is no longer there, and one the audio never moved to at all. The
record says which of them refused, because the question this feature generates is always
"why did it not start" — and the last two are deliberately separate lines, since a headset
that dropped out and a smartwatch that was never going to play anything are different
problems wearing the same shape.

**The MAC address never appears anywhere.** On Android 12 and later lugu never learns it:
the system shows its own device picker, and what comes back is an association, not a device
list. On older versions it is read from the paired list but is only ever a key — the
playback diary, which the feedback screen can send, records the device's *name*. An address
identifies hardware a person carries around and has no business in a bug report.

**On Android 12 and later this could not have been built the obvious way.** A receiver for
the Bluetooth connection broadcast needs a runtime permission whose prompt talks about
determining the relative position of nearby devices, and — separately and fatally — an app
in the background is not allowed to start a playback service, with a Bluetooth broadcast
absent from the documented exemptions. It would have been delivered the broadcast and then
refused the service: a feature that looks like it works and never plays anything. The
companion-device association is the supported path and grants exactly the exemption
required. Below Android 12 neither restriction exists, so the straightforward version is
also the correct one, and that is what runs there.

## lugu was not in the car at all

Reported 16 Aug: *I can't see lugu on my Android Auto at all, and I can't add it as an app.*

| Item | Status |
|---|---|
| Diagnosed — Android Auto's **unknown sources** was off | done 16 Aug |
| The requirement is stated where someone installing will read it | done 16 Aug — README |
| The two `adb` commands that separate the causes | done 16 Aug — [qa/auto.md](qa/auto.md) |

Not a defect, and worth recording anyway, because **every** person who installs lugu from
Releases will hit it. Android Auto lists only apps installed from the Play Store, and says
nothing when it hides one: the app is missing from the car and missing from the "customise
launcher" list, which looks exactly like an app with no car support. Turning on unknown
sources is a developer setting behind ten taps on a version number, and Android Auto does
not rescan afterwards without being force-stopped.

Everything on lugu's side was checked first and was correct — the automotive descriptor, the
legacy `android.media.browse.MediaBrowserService` action, and a session that accepts the
projection host — which is what made it certain the fault was outside. That check is now two
commands in the QA doc rather than a hunt, since the same three causes will come up again on
the next phone.

## The car, driven for real

Reported 16 Aug, from an actual drive with the Desktop Head Unit's verdict finally checked
against a windscreen. Recorded here with the praise as well as the faults, because what is
already right is the thing most likely to be broken by accident later.

| Item | Status |
|---|---|
| **No cover images anywhere in the car** | fixed 16 Aug |
| "Not now" should dismiss the notification | fixed 16 Aug |
| Tapping the notification should cancel and dismiss | done 16 Aug |
| Continue section, and its ordering by recency | working — *"I love it"* |
| Responsiveness — playback starts the instant play is pressed | working — *"this is already a win"* |

**Covers were blank everywhere — every browse row and the now-playing screen.** The cause is
worth writing down because nothing about it is visible from inside the app: Android Auto
fetches artwork **in its own process**. Handed an `https://` link to
`/api/items/:id/cover`, it makes an anonymous request, is refused, and draws a blank tile.
lugu's own screens show covers perfectly, because they go through the app's OkHttp client
where the token is attached. There was nothing to see in a log, because from lugu's side
nothing happened at all.

Artwork now goes out as a `content://` URI served by `CoverProvider`, so reading it comes
back into lugu, where the authentication is. The session's bitmap loader had to be stated
explicitly as well — the notification loads its own artwork in-process, and a loader that
only speaks http would have drawn nothing while the car drew everything.

The one thing this did not fix at the time: **covers were not part of a download**, so a car
in a garage with no signal still got blank tiles for books that were entirely on the phone.
Since closed — a download now stores its cover beside its audio, and both the car and the
phone's own screens read it before they consider the network. What is left is an item that
was never downloaded *and* never looked at, which has no picture anywhere to show.

**"Not now" left the notification on screen.** A second bug in the same area as the one fixed
earlier that day, with a different cause: the prompt was only taken down when the player had
nothing loaded, so cancelling with a book already sitting paused from earlier left the
countdown up. Refusing and cancelling now both remove it immediately — nothing is coming to
replace it, so there is nothing to wait for.

**And then a deliberate change on top:** *let's make tapping the notification cancel the
auto-play and dismiss it.* Tapping a notification usually opens the app, and that is the
right default nearly everywhere — but this one lives for a second and asks a single question,
and opening lugu is not an answer to it. The whole notification now says no. Swiping it away
does too, because dismissing a prompt and then having the book start anyway would make the
gesture a lie. The labelled button stays: a tap target nobody can see is not an offer.

## The car, a second drive — the speed button, and what the car chooses to show

Reported 17 Aug, from the same car. Two faults, one confirmation, and one question about a
feature that may already work and has never been watched working.

| Item | Status |
|---|---|
| **The speed button does not say what speed it is on** | recorded 17 Aug — backlogged |
| **"For you" is useless; it should hold what Continue holds** | recorded 17 Aug — backlogged, and not yet known to be ours to change |
| Cover images in the car | working — *"we have images in Android Auto"* |
| Continue, complemented by next-in-series | *"amazing"* — and unverified end to end; see below |

**The speed button is a cycle with nothing written on it.** It goes out as a
`CommandButton` whose display name is the fixed word "Speed", built once in
`LuguPlaybackService.carCommands`, so the car draws the same label at 0.8× as at 2.0×.
Pressing it changes the speed correctly and remembers it, and there is no way to tell from
the button what it just did or what it will do next — which in a car means finding out by
listening to a sentence at the wrong speed and pressing again, several times, at exactly
the moment attention is worth the most.

The fix is small and the design question inside it is not. Mechanically the label is already
pushable: `pushNotificationLayout()` broadcasts the button list to every controller, so
rebuilding the speed button with the current rate in its name and pushing on every speed
change is the whole of it. What has to be decided is *which* speed the word names — the one
playing now, or the one a press moves to. Tom asked for the current speed ("e.g. 1.2x") and
explained it by the other one ("hard to understand what speed you are switching to"), and
those are not the same button. A label that reads "1.2×" and lands on 1.4× when pressed
answers where you are; a label that reads "1.4×" answers where the press goes but never
tells you where you are. Naming the current rate is the better of the two, because the rate
in force is a fact about what you are hearing and the next preset is guessable from it once
the presets are known — but this is a judgement, and it should be checked in the car rather
than settled here. What must also be checked there: whether a head unit re-reads a custom
action's label when the session pushes a new one, or caches it from the first connection.
If it caches, the label cannot carry the number and the answer is a different shape
entirely.

**"For you" is not lugu's.** Nothing in this app builds a node by that name — the browse
tree's root offers Continue, Up next, Latest episodes, Downloaded, Series, Podcasts and
Libraries, and the code is a single list in `BrowseTree.rootChildren`. So the section is the
host's, and before anything is promised the first job is to find out which of two surfaces
it is, because only one of them can be fed from here:

- **The recent/resumption root.** A media session can serve a separate root for "what would
  you resume", which Android Auto uses to draw a tile before anything is browsed. lugu
  serves no such root today — there are no content-style or recent-root hints anywhere in
  the service — and if this is what fills "For you", the fix is ours and is roughly the
  Continue node under a different id.
- **Android Auto's own suggestions.** If instead it is the launcher's media row, built by
  the system from its own history, there is no hook at all and the honest answer to Tom is
  that it cannot be changed from inside lugu.

The ask itself is the part worth keeping whichever way that goes, because it is a statement
about what a car is for: *"I never use the car UI to discover what I'm going to listen next.
It's always to continue something."* That is already why the browse tree opens with Continue
rather than with a library, and it is an argument for spending nothing further on discovery
surfaces in the car — no recommendations, no "recently added", no browsing shelves — beyond
what is needed to reach a specific thing on purpose.

**Next-in-series is implemented and has never been watched working.** Tom's own caveat —
*"although I dunno if it works yet"* — is fair, and the record can be exact about it. A book
ending with an empty queue resolves through `QueueRepository.next`, which returns a
`NextUp.Suggested` carrying the reason "Next in *series*"; `DefaultContinuationResolver`
then either starts it or, if the ask-first setting is on, cues it at the head of the queue
and puts the reason on screen. The query underneath it, `nextInSeriesAfter`, has its own
Room test, and the series membership it reads has been through a migration with tests of its
own. What has no coverage is the join: a real book reaching its end and the next one
starting, which lives only in [qa/auto.md](qa/auto.md) as an unticked manual line. So the
parts are proven and the whole is not, and "amazing" is currently praise for something
nobody has seen happen. That is the gap to close — and it is a good candidate for
automation, since a book can be seeked to its final seconds without waiting out a book.

## Connecting to a plain-HTTP server

Not reported by Tom — his server is behind https — and found while pointing the tests at a
container. Recorded here because it is the worst kind of defect this record exists to catch:
one that made lugu unusable for a whole class of people, silently, and blamed their server
for it.

| Item | Status |
|---|---|
| **A plain-HTTP server could not be reached at all** | fixed 17 Aug |
| The failure blamed the server rather than the app | fixed 17 Aug |

Android refuses cleartext by default from API 28, and the refusal happens *below* the HTTP
client: the socket never opens. Audiobookshelf is overwhelmingly self-hosted at home on
`http://192.168.x.x:13378`, so for those owners a correct address and a running server
reported "could not reach that server" — which reads as the server being down, and is not
fixable from their side at all.

A network security config cannot express "whatever the user configures": its domain list is
fixed at build time and there is no runtime API to add to it. So the platform switch is open
and the *policy* is the app's, stated where it can be about the address actually in use. The
sign-in screen says, before the password is sent, that a plain-HTTP address carries the
password, the token and everything listened to in the clear.

Inline, not a dialog. This is the ordinary way the software is run and lugu must not obstruct
it — what it must not do is let the password go out silently. Certificate trust is untouched:
an https address is still verified against the system store, and this must never become a
"trust everything" config.

## A way out to the web client

> *I think we need a passthrough "go to web client" until we have feature parity.*

Taken as asked. Hiding the client that can do the rest, while lugu cannot, is the worse of
the two options — a missing feature should mean a detour rather than a dead end.

Settings offers the server's own web client under the account, and a book's page links to
that book's page there. The route was read from the Audiobookshelf source rather than
guessed, because a wrong link is the kind that fails quietly: it opens a browser on a page
that is not there and reports nothing back.

The caveat is stated before the link is followed, not discovered after it. A browser has its
own cookies, so a first visit may land on the login page — annoying, but obvious and
recoverable. The two that are neither are **lugu's custom proxy headers and its client
certificate**: both live in this app and cannot be handed to another one, so for a server
behind an identity-aware proxy the browser is turned away before it ever reaches
Audiobookshelf, and the refusal comes from the proxy, in the proxy's words, which will not
mention lugu. The row says so whenever either is configured.

## Earlier findings

- **Notification rewind reset the book to zero, unrecoverably.** Fixed: transport
  buttons remapped away from `seekToPrevious()` (which seeks to position 0 on a
  single-file book), and schema v2 records position history so any jump can be undone.
- **Podcasts crashed the continue-listening shelf** through a fan-out join producing
  duplicate Compose keys. Fixed and covered by Room tests.
- **Login failure** was never the app: two stacked network misconfigurations between
  the phone and the server, both since fixed. The app now reports a wrong address as
  such instead of echoing a proxy's raw 404.
