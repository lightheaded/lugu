# Backlog — known unfinished work

Everything deliberately left undone, so nothing depends on someone remembering it.
Items originating from user testing live in [FEEDBACK.md](FEEDBACK.md) and are
cross-referenced here rather than duplicated. Items found by the UX audit of 20 August
live in [UX-AUDIT.md](UX-AUDIT.md), and the twelve highest of them have work items in
[UX-FIX-PLAN.md](UX-FIX-PLAN.md); the audit marks which of its findings this file
already records, so neither list repeats the other.

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
| **Inline errors still reflow the forms and the player** | **Decided and done 20 August, apart from one row kept below.** The decision the row asked for, made and written into the `StatusStrip` KDoc where the next person will reach for it: a message may appear, and nothing else may move. Which of the two mechanisms applies is decided by what the message is *about* and never by which screen it is on. A message about the screen, or about the outcome of an action, is an **overlay** — the strip in a `Box`, pinned under the top bar, holding no layout space — and that is what the player, the feedback form and the connection screen now use. A message about the input in front of you stays with that input in **space that is always reserved**: composed and hidden rather than added and removed, so the block is the same height in both states. The sign-in screen keeps its rejected-password line under the password box on those terms, and its plain-HTTP warning — a standing condition of the address rather than a message about an action — moves up under the address field with its space reserved and its wording untouched. A dialog was rejected: it stops a person to say something they can read in place. `StatusStrip` moved to a new `:core:ui` module on the way, because the feature modules are siblings and copying it would have been the `ContinueLabel` mistake again. Accessibility went with it: none of the four screens announced anything at all before, and every message that moved or gained reserved space is now a polite live region, with hidden text hidden from the screen reader too. **Still open:** the per-row download error in `DownloadsScreen`, which swaps a progress bar for a line of unknown length inside a list row — a substitution rather than an addition, and one that needs a decision about cutting the server's words before it can be fixed the same way |
| **The car's speed button does not say what speed it is on** | Reported 17 Aug. The button goes out with the fixed display name "Speed", built once in `LuguPlaybackService.carCommands`, so the car draws the same label at every rate and a driver learns what the press did by listening. Pushing a changing label is already possible — `pushNotificationLayout()` broadcasts the button list on demand — so the work is not the plumbing but two answers: whether the label names the rate in force or the rate a press moves to (Tom asked for the first and justified it with the second), and whether a head unit re-reads a custom action's label or caches it from the first connection. The second is only answerable in a car, and a cached label would mean a different shape of fix entirely |
| **"For you" in the car should hold what Continue holds** | Reported 17 Aug, **served 20 Aug and unconfirmed in a car.** Neither of the two guesses first recorded here was right. "For you" is a pane on Android Auto's *dashboard*, not a tab in the browse tree, and it is fed by the app through a root hint: `BrowserRoot.EXTRA_SUGGESTED`, which reaches a Media3 session as `LibraryParams.isSuggested`. It is not `EXTRA_RECENT`, which is the phone's resumption carousel and which lugu already answers through `onPlaybackResumption`. An app that answers nothing gets the pane filled from the top of its browse tree, which for lugu is the Continue *category* — something to open rather than something to play, and a precise explanation of a useless pane. `onGetLibraryRoot` now answers the hint with `lugu/suggested`, whose children are Continue's rows, and `AutoBrowseTreeTest` asserts both roots. What is left is the part no test can reach: whether the pane Tom sees is the pane this feeds. That check is in [qa/auto.md](qa/auto.md), and it needs a car. Tom's own argument — *"I never use the car UI to discover what I'm going to listen next. It's always to continue something."* — is why the fix is Continue's contents and nothing more |
| ~~**Next-in-series has never been watched working end to end**~~ | **Done 20 August**, and two things were missing rather than one. The test was the half that was written down. The other half was the fixture: `scripts/seed-test-server.sh` seeded no series of any kind — "The Breakwater" in it is an ffmpeg `album` tag on a single two-file book, not an Audiobookshelf series — and `nextInSeriesAfter` ignores a membership with no sequence, so a check written against the catalogue as it stood would have found nothing to continue *to* and passed for it. That is the larger part of why the join was never observed, and it had to be fixed first. The script now builds a real two-volume series, which Audiobookshelf takes from the folder layout `<author>/<series>/Vol. N - <title>` with no API call after the scan, exported as `lugu.test.seriesQuery`. `NextInSeriesTest` in `:app` then walks volume 1 to its last seconds, lets the audio run out, and asserts that volume 2 begins — and, with "ask before a suggestion" on, that volume 2 is loaded, cued at the head of the queue and silent. Writing it found a defect in that second branch: `playWhenReady` survives the end of a playlist, so a player that has run out of audio starts the next book the moment it is prepared, and cueing has to pause rather than merely not play. The next CI emulator run is the first time the test itself executes — it was written on a machine with no device attached |
| ~~**An episode page, with show notes**~~ | **Done 20 August.** The three decisions it was waiting on are all answered in FEEDBACK.md. A tap in the episode list opens the page and a play button to the right of the row plays the episode — the reverse of what was proposed here, and Tom's call. HTML is read by lugu itself: `parseShowNotes` in `:core:model` turns markup into text plus spans, and the spans become an `AnnotatedString` with real links; `HtmlCompat` was rejected because what it accepts is a property of the OS version, which would make the same feed render differently on two phones and move a screenshot baseline on an upgrade. The page sits on top of Home and never as a start destination, which is the item page's answer given the item page's way. The show's own description goes through the same renderer, so a podcast writing in `<p>` no longer shows its tags |
| ~~**The new-episode notification opens Home**~~ | **Done 20 August**, once the episode page existed for it to land on. The launcher intent is still the base — which is what keeps Home under the page, so back reaches the library on a cold tap — and the library item id and the episode id ride on it as extras that `MainActivity` reads the way it already reads a spoken request. One notification still covers a whole batch, and a tap on a batch opens the newest episode: that is the one its own text names first, and sending a batch to Home would be the old behaviour under a new name |
| **One author credited two ways is two authors** | The browse pages group on the stored string, so "Corven, James T. R." and "James T. R. Corven" are separate, and a multi-author credit is its own entry. The alternative is guessing at name order for every language a library might hold, which is a worse kind of wrong — this wants the server's own author records, not smarter parsing here |
| ~~**The grid still shows no progress for a podcast**~~ | **Fixed 17 August.** The decision recorded here — that a cover reading "60%" for a whole feed is worse than none — was the wrong one, and it was answering a question the app had already settled everywhere else: the browse, series, narrator and collection grids all read `ItemProgress.byItem`, which falls back to the most recently played episode, and only the library grid still looked progress up by an item-level key a podcast never has. So the bar is drawn, and what it means is stated rather than left to be inferred: the spoken description says "Latest episode 62% listened" where the number is an episode's. The same lookup had left the **"In progress" filter blind** to every podcast, which was the part with no defence at all. What a borrowed row is still not allowed to do is finish the feed — only an item with a row of its own can be Finished |
| ~~**Sort and filter on the downloads screen are not remembered**~~ | **Fixed 17 August** with two keys of its own, `downloadSort` and `downloadFilter`, rather than by sharing the grid's — which was the real objection and is now covered by a test that changing one leaves the other alone. The search box is deliberately still not remembered: an ordering is a decision about how a list is read, a search is a thing being looked for, and coming back to three of forty rows with a stale word in the box reads as lost data |
| **Ask the server to fetch podcast episodes it does not have yet** | Tom asked directly: *"I want to tell ABS server to download episodes that haven't been downloaded yet."* This is the **server-side** fetch — Audiobookshelf reading a podcast's RSS feed and pulling episodes into its own library folder — and not the phone-side download `:core:download` owns; the naming throughout keeps the two apart on purpose. The client and repository layers are done: `AbsPodcasts.kt` in `:core:api` wraps `GET /api/podcasts/:id/checknew`, `POST /api/podcasts/:id/download-episodes`, `GET /api/podcasts/:id/downloads`, `GET /api/podcasts/:id/clear-queue` and `POST /api/podcasts/feed`, all verified against the server source (no live server was reachable while this was built) rather than guessed at; `ServerEpisodeFetchRepository` in `:core:sync` wraps each call in a `Result`, matching `CollectionRepository`'s pattern for a shared, server-owned action with no offline retry queue — retrying a fetch request blindly risks a real duplicate on the server's side. **What is still missing is a button.** The natural home is the podcast page (`ItemDetailScreen.kt`), owned by another piece of work at the time this was built, so nothing calls `ServerEpisodeFetchRepository` yet. The wiring: a "Get new episodes" action on the podcast's own page (not a per-episode action — this asks the server about the whole feed), calling `checkAndFetchNewEpisodes(itemId)`, with the result surfaced through the screen's `StatusStrip` (an overlay, no layout shift) or a snackbar rather than a dialog — asking the server to fetch is not destructive, so it needs neither a confirmation nor an undo |

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
| **Skip intro and outro** — done 16 Aug, with adverts | app#749, 7 👍. Per-podcast trim offsets, remembered, so a fifteen-second sting is not heard three hundred times. Adverts ride the same mechanism *at the point of playing* and a different one at the point of finding: they are skipped only where the episode marks them with a chapter that names itself as advertising. Finding an unmarked advert needs fingerprinting against a database of known adverts, and a false positive eats a minute of the show — a skip that removes narration is worse than an advert that plays. Reasoning is on `object SkipRegions` |
| **Duck rather than cut for other audio** — done 16 Aug | app#1259. A navigation prompt should lower the book, not interrupt it |
| **xHE-AAC** | server#4236, 15 👍 and 38 comments, the most-discussed server enhancement of the year. Android 9 and later decode it natively, so a native client can play files the web player cannot — a real differentiator, provided the server serves the bytes rather than refusing to probe them |
| **Tasker-compatible intents** — done 16 Aug, see [automation.md](automation.md) | app#858, 21 👍. Exported play/pause intents. Small, and it wins the automation audience outright |
| **Media buttons on watches and remotes** | app#352 (17 comments, open since 2022, `help wanted`) and app#1048, where the headphone pause button rewinds instead of pausing. `MediaButtonClassifier` exists precisely to prevent the second; neither has been tested against real hardware. Belongs with the headset test matrix above |
| **Battery drain as a standing requirement** | app#1446 is the most-discussed Android bug ever filed against the official app, at 81 comments. Not a ticket to close — a thing to measure before each release, alongside the sensor and wake-lock rules already followed here |

### Browsing, sleep timer and distribution

| Item | Note |
|---|---|
| **A–Z rail on the browse pages too** — done 16 Aug | The grid had one and the author, series and narrator lists did not. The rail indexes the list *after* the search box, so it cannot offer a letter the search has removed |
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
| ~~**"Which title does a Continue row show" is implemented twice**~~ | Done: `ContinueLabel` in `:core:model`, read by both the car's browse tree and the phone's shelf. The row is kept because the argument for collecting it is the argument against the next such split |
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
| **Socket.IO deltas are written but unproven** — now reachable, 16 Aug | Built 16 Aug against the server source rather than its docs, which say of themselves that they are unmaintained. Every event name was read out of `SocketAuthority.js` and the controllers. What no test *could* reach: that engine.io-client works against OkHttp 5 at runtime, that the handshake succeeds and `init` arrives, that the socket path is right through a reverse proxy, and that re-emitting `auth` every fifteen minutes keeps events flowing once the token behind them has rotated. CI now has a live server — `scripts/seed-test-server.sh` — so the first three are answerable by a test that nobody has written yet. The fourth needs fifteen minutes of wall clock and a rotated token, so it stays manual. The reverse-proxy leg is not covered either: the container is reached directly |
| **Reboot resumption never verified; process death now is** | Half of this closed on 17 Aug. `:harness` kills lugu's process mid-book and presses play, and it passes on an API 36 emulator against the seeded server: the same item back at 45209ms against 45239ms, at the 1.5x it was on. What is still untested is the **reboot** case — a device that restarts, a `BOOT_COMPLETED` receiver, and a session nothing has touched since — and the whole thing on real hardware rather than an emulator, where audio focus, a real headset and a real Bluetooth stack are involved |
| ~~**lugu registers no media button receiver on modern Android**~~ | **Fixed 16 Aug, and it was real.** Found by `:harness` before it could run its own resumption test: with lugu open, `dumpsys media_session` reported `mediaButtonReceiver=PendingIntent{… startForegroundService}` on API 26 and **null** on API 36, and no component of lugu's answered `android.intent.action.MEDIA_BUTTON` at all. Media3's contract for playback resumption is both halves or neither — "you need to declare a media button receiver **and** implement the onPlaybackResumption method" — and only the second had ever been written. Without the receiver Media3 falls back to a pending intent at the service, which the platform does not keep once the process is gone, so on a modern phone there was nothing for a headset press to arrive at after a kill. That is M0's central promise. Nothing caught it because nothing could: the existing test destroys the *service* from inside the app's own process, so a live session was always there to catch the button. The fix is the four-line receiver now in `:playback`'s manifest, and on 17 Aug it was **proven end to end**: with the receiver declared, `:harness` kills the process mid-book, sends `input keyevent 126`, and the same item comes back at 45209ms against 45239ms and at the 1.5x it was on, on API 26 and API 36 alike. Removing the key press makes that test fail, so the button is what does it. A second finding came with it — a media button after `am force-stop` woke lugu on two runs of the same emulator and not on a third, which is why the force-stop test asserts only that nothing comes back *wrong* |
| **`stopService` never destroyed the playback service** | Found 16 Aug on the first CI run that ever executed `PlaybackResumptionTest` — it had skipped for want of a server since the day it was written. Media3 keeps a `MediaSessionService` alive while its session is active, so the step the test is named after never happened: the player came back holding 4093ms, which a service that had been through `onCreate` again could not have done. The assertion that encoded the false assumption is gone and the KDoc now says what the test really establishes. The cold-start case belongs to `:harness`, which kills the process for real |
| ~~**A fresh sign-in mirrors nothing until the app is restarted**~~ | **Fixed 17 Aug, and it was worse than the first two readings of it.** `refresh()` syncs the libraries and then syncs the items of `selectedLibraryId` — which is null the first time it runs, because the collector that chooses a default waits on the libraries fetched two lines above. So `listOfNotNull(selectedLibraryId.value)` was empty and the first sync after signing in mirrored the libraries and **none of their items**. Measured on a device: `library` held 2 rows and `library_item` held 0, and it stayed 0 through opening the Library tab, because nothing calls `refresh()` again. Only restarting the app fixed it, by which point the selection had been persisted. A new account's first sight of lugu was an empty grid, and anything reading Room meanwhile — Home's shelves, the car's browse tree, `playFromSearch` behind "play X on lugu" — found nothing either. `refresh()` now falls back to the account's default library, or the first one. Verified on a device: items land within five seconds of signing in, on Home, with no tab tapped and no restart |
| **M0 QA checklist never run** | [qa/m0.md](qa/m0.md) is written but unexecuted by a person. Six of its lines are now machine checks — wrong password, sign-in filling the mirror, the library picker, rotation on eight screens, covers on disk, and the progress a tile shows — which narrows what a device pass is *for* rather than replacing it |
| ~~**Nothing mirrors the library when an account signs in**~~ | **Fixed 17 Aug**, and it is the other half of the row below. `refresh()` was made to mirror a library's items when nothing had been selected yet; nothing was made to *call* it. The only on-demand sync in the app belongs to `LibraryViewModel`, which is not built until the Library tab is composed — and signing in lands on Home. So a new account's Home was empty, and so was the car's browse tree and the search behind "play X on lugu", neither of which can tap a tab. The periodic reconcile hid it on a fresh install, where WorkManager runs the first period immediately; on any later sign-in `KEEP` means it does not run again for six hours. `SyncScheduler.syncNow` now enqueues a one-off reconcile at the moment the account is created — a worker rather than a coroutine, because a first sync of a large library outlives the screen that started it. **It came with a second bug and its fix.** Two mirror passes over one library now ran at once — the worker's and the Library tab's — and a pass sweeps every row of that library stamped before it started, so the two deleted each other's work. It appeared on the slower CI emulator as a title that never reached Room: an absence rather than a failure. The hazard was always there (the six-hourly reconcile could overlap a pull-to-refresh); one sign-in made it likely. `syncLibraryItems` now holds a lock per (account, library), and a second caller waits for the pass in flight rather than repeating it |

## M2 gaps

| Item | Note |
|---|---|
| **Offline playback has not been proven on hardware** | Downloading has now moved real bytes — a 629 MB book, downloaded and ready to play, 15 Aug. What is still untested is the other half: going offline for long enough to matter and confirming nothing is lost, and that the session replays on reconnect. Until then, "a week in airplane mode loses nothing" is a claim, not a result |
| **Transcoded items still cannot be downloaded — now deliberately** | Resolved 16 Aug as a refusal that explains itself rather than as a feature. Three independent reasons, any one sufficient: an HLS playlist is minted against a play session that expires and takes its segment URLs with it, so the download could not be keyed by item and track the way every other one is; a transcode has no size until it exists, so a truncated download is indistinguishable from a complete one and the failure surfaces in a tunnel; and it would be a re-encode, at a bitrate the server chose, of a file the server already holds intact. The productive direction was the other one — widening the supported mime types so fewer items transcode at all. Reasoning is on `DownloadRefusal.TranscodeOnly` |
| **`audio/x-aiff` is claimed but unverified** | It is in the supported-mime list and no AIFF extractor could be confirmed in Media3's published formats. Pre-existing, and the opposite mistake from the one just fixed: overselling makes the server hand over a file nothing can decode. Worth one test against a real AIFF file |
| ~~**Storage cap is checked, not enforced mid-download**~~ | Done 16 Aug. Enforced on the existing one-second progress sweep; a cap-reaching download is stopped and its row records why |
| **A streamed listen does not fill the download cache** — answered differently, 16 Aug | Streamed audio is now retained, but in a *second* bounded cache with an oldest-first evictor rather than in the download cache. Filling the download cache would have meant a download being evicted to make room for something merely streamed, which is exactly what makes an offline mode untrustworthy. The two figures are shown separately and never summed |
| **A downloaded book still waits on a progress pull before it plays** | Found 17 Aug by reading `MediaResolver`. Resolving from disk is correctly placed *before* the play-session request — that is what makes airplane mode work — but `progressRepository.startSession` runs before both, and its first act is a network read. The main OkHttp client sets no `callTimeout` (only `ConnectionProbe` does), so on a network that is connected but not working — a tunnel, a captive portal, a VPN half up — a book that is entirely on the phone waits out OkHttp's ten-second read timeout before a sound comes out. Airplane mode is fine, because that fails immediately; the in-between case is the bad one, and it is the case offline mode exists for. **Not fixed blind, deliberately.** The obvious fix — bound the pull with a short timeout — weakens pull-before-push, which is the rule that stops stale local progress overwriting a newer position from another device, and that is the single worst class of bug this client has (upstream app#1022, #1059, #1161, #1182). The better shape is to start the downloaded item immediately and let the pull land afterwards through the "moved from another device" notice that already exists with an Undo — but that means adopting a position *mid-listen*, which nothing here does yet. Wants a measurement on a real bad network before either is chosen |
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

## Left behind by the 16 August sweep, streaming and podcast work

| Item | Note |
|---|---|
| **The deep buffer and the retained cache only take effect at the next service start** | A `LoadControl` is fixed at `ExoPlayer.Builder` and a `SimpleCache` holds a folder lock and a read index, so neither can be swapped under a running player. Changing either setting is honest about this rather than pretending to apply live. Rebuilding the player mid-book to apply one would cost the buffer and a re-prepare, which is a worse trade than waiting |
| ~~**`SpeedSettings.STEP` is declared and never used**~~ | **Wired 17 Aug, and the premise here was wrong.** Something did step a speed by repeated addition — the fine adjustment in the player's speed sheet — it just added a hardcoded `0.05f` instead of the constant. Which drifts: fifteen presses from 1.0 lands on 1.7499998, so a stepped speed and the identical-looking preset were different numbers. It also had no idea the range had ends, so presses below 0.5 and above 3.5 were accepted, clamped silently at the player and changed nothing on screen. Stepping is now computed in hundredths and both buttons switch off at the ends. The formatter was never at fault: it rounds |
| **`AutoDownloader` reads the primary series' sequence** | `bySeries` now returns real membership, but the download-ahead rule still filters on `library_item.seriesSequence`, which is re-derived for the *primary* series only. For a book in two series that may be the other series' number. Reading `ItemSeriesDao` would make it exact; nothing is worse than it was |
| ~~**A cap-stopped download offers a retry that will fail again**~~ | **Fixed 17 Aug**, and it was worse than recorded: the refusal `download()` returns was being discarded on the downloads screen, so the retry did not merely fail again — it failed *silently*, and a button that changes nothing reads as broken. The refusal already states its own arithmetic ("Needs 56 MB, and 7.6 GB of the 8 GB cap is already used"), which is the sentence that says what to change first, so showing it was the whole fix |
| **`EndItem` plus Undo cannot fully be undone** | A skip that ends an episode fires continuation, and the undo is a *seek*, so it is now guarded on the player still holding that episode. Where continuation has moved on, the progress row is restored — right for the next time that episode is opened — but the listener is not taken back to it. A proper fix wants a re-load entry point on `PlaybackConnection` rather than a seek |
| **A skip is recorded in the position history as a plain seek** | `onPositionDiscontinuity` writes `reason = "seek"` for every discontinuity including our own skips. The diary names them properly; the history does not, so a trim that is eating audio is harder to see there than it should be |
| **The "—" for a zero-or-less duration is now unpinned** | It was never a formatter behaviour and is now explicitly a call-site rule on three screens (podcast header, continue card, queue row). Correct placement, no test |
| **`createComposeRule` is deprecated across every screenshot test** | The replacement swaps `Unconfined` for `StandardTestDispatcher`, so it is a repo-wide migration with real behavioural consequences rather than an import change. Not started inside one module on purpose |
| **`onPlaybackResumption(session, controller)` is deprecated** | The three-argument version takes `isForPlayback`, which alters resumption semantics — the one path M0 rests on. Worth doing deliberately, with the device pass behind it |

## Left behind by the auto-play work

| Item | Note |
|---|---|
| **Android 12 exactly gives a chosen device no name** | `AssociationInfo` and its display name arrived in Android 13; Android 12 has only `getAssociations()` and a list of addresses. Devices chosen there show as "Bluetooth device", and two of them are indistinguishable in the list. Deliberately not filled in with part of the address, which is displayed and recorded |
| **A device with no classic Bluetooth profile will not be offered** | The association request filters on `BluetoothDeviceFilter`, which is classic-only. An LE-audio-only headset would not appear in the picker. Nothing tested this either way; adding `BluetoothLeDeviceFilter` alongside is a one-line change once there is a device to try it on |
| **Two notifications for a moment, in one case** | If a book is already loaded but paused when the device connects, the waiting notification and the player's own are both up until playback starts. Media3's id was deliberately not reused: sharing it would give one tidy notification that Media3 could overwrite mid-wait, silently taking the "Not now" button with it |
| **The output arriving is not quite the policy having moved** | A book now starts when `AudioManager` reports a listenable output rather than after a fixed delay, which is the right signal and the available one. It is not the *exact* signal: what would be is `ExoPlayer.setPreferredAudioDevice`, pinning the AudioTrack to the device that connected so routing cannot lag at all. That is a real API and a small change, and it is not in because there is no device here to prove it does not pin playback to a headset that later goes away. The one-second default exists to cover the gap in the meantime; item 7 of [qa/autoplay.md](qa/autoplay.md) is the measurement that says whether it is needed |
| **Nothing tells the UI a start is pending** | The wait is only visible in the notification. A listener with the app open sees nothing until the book begins. Not obviously wrong — the app being open is the case where pressing play is easy — but it is a gap |
| **The companion observation is re-armed hopefully rather than knowingly** | `startObservingDevicePresence` is re-issued on app start and on boot, because there is no way to ask the system whether an observation is still live. Re-issuing is documented as harmless. If it turns out not to survive something else — a force stop, an update — there is no signal that would say so |

## Left behind by the car covers

| Item | Note |
|---|---|
| **An item never downloaded and never looked at still has no cover offline** | What is left of the gap that `CoverStore` closed. A downloaded item keeps its picture and a browsed one keeps a cached copy, so what remains is a book that is neither: on a phone with no signal it shows a blank tile and has to be recognised by its title. Fetching covers for a whole library ahead of time is the only thing that would close it, and that is a lot of storage spent on a case the titles already cover |
| **Downloaded covers are not charged against the storage cap** | The cap governs the Media3 download cache, and the readout beside it is that cache's own byte count — the two agreeing is what makes a refusal explicable. A cover is tens of kilobytes, so four hundred of them is a few megabytes, and adding that to a figure the cap does not govern would put a second number next to the cap for the sake of rounding error. Deliberate; worth revisiting only if covers stop being small |
| **The cover provider is exported, and has to be** | A browse result carries no URI permission grant, so there is no way to hand Android Auto a scoped read. What that opens is small — an app that already knows an Audiobookshelf item id can fetch that item's cover art, and nothing enumerates ids — but it is a surface, and it is only there because the media browser API has no narrower door |
| **Nothing proves the car actually gets the bytes** | The provider's URI shape and its refusals are unit-tested; that Android Auto resolves a `content://` artwork URI at all is not, and cannot be without a car or the DHU. [qa/auto.md](qa/auto.md) carries the `adb shell content read` check as the closest thing to a proof from the phone side |

## Known behaviour gaps

| Item | Note |
|---|---|
| ~~Podcasts show no progress bar on the continue-listening shelf~~ | Fixed 16 Aug by the per-episode continue shelf: the row now names its own episode and carries that episode's progress and duration |
| Losing connectivity mid-book stalls playback unless the book is downloaded — largely fixed 16 Aug | A deep read-ahead buffer, a retry ladder widened to five attempts over thirty seconds, a reconnect trigger, and a bounded cache so a re-prepare replays from disk rather than re-fetching. **None of it has met a real tunnel**; see the device pass |
| Series with no volume numbers are still left out of "Next in series" | Membership now comes from the server's own join table, which recovered every book that was excluded for being in *two* series. What is left is a series nobody numbered at all, and the server cannot order those either — its listing sorts on the sequence strings, so with none to sort it returns the order the scanner inserted the rows in. That order lays a series page out, and is deliberately not allowed to recommend a next book |
| Series listings are heavy and rate-limited to five minutes | `GET /api/libraries/:id/series` echoes the documented `minified` parameter without reading it, exactly as the collections listing does, and sends every member of every series as a complete item payload. Unlike collections its paging is real, so lugu walks it fifty series at a time, tied to a library sync rather than to opening a book page |

## Architecture and tech debt

| Item | Note |
|---|---|
| **R8 runs on an emulator now, but not yet on a phone** — narrowed 16 Aug | Turned on 15 August with hand-written keep rules for Media3, Room, Hilt, kotlinx-serialization, Ktor, OkHttp, Coil and Sentry, and for a day nothing executed them: `assembleRelease` proves only that nothing is missing at compile time. A `minified` build type — `release` by `initWith`, so it cannot drift — now runs the instrumented suite under R8 on every CI run, which reaches Hilt's graph, Room's generated code and the media service by the paths a shrunk build actually takes. What that still cannot reach is the entry point the *system* binds by name on a real device, so **[qa/autoplay.md](qa/autoplay.md) on a signed release APK is still owed** — along with sign in, play streamed, play downloaded, open Android Auto, change a setting |
| ~~**A plain-HTTP server on the LAN cannot be reached at all**~~ | **Fixed 17 Aug.** The decision this was waiting for got taken, and the shape it took was not the per-server opt-in guessed at here. A network security config cannot express "whatever the user configures" — its domain list is fixed at build time with no runtime API to add to it — so the platform switch is opened in the main manifest for every build and the policy moved into the app, where it can be about the address actually in use. The sign-in screen states inline, before the password is sent, that a plain-HTTP address carries the password, the token and everything listened to in the clear. Inline rather than a dialog: this is the ordinary way the software is run, and obstructing it would be answering a real problem with a worse one. Certificate trust is untouched — an https address is still verified against the system store, and this must never become a "trust everything" config. The debug-only overlay that permitted `10.0.2.2` is gone with it |
| ~~**Release stack traces are obfuscated with nowhere to send the mapping**~~ | Done: `ci.yml` publishes `mapping.txt` as a release asset beside the APK it belongs to, named with the same version. Retracing is by hand, which is the trade for not needing a Sentry auth token in CI |
| `:core:queue` and `:core:testing` modules not created | M3 shipped the queue without either. `QueueRepository` lives in `:core:sync` with the other repositories, its DAO in `:core:db` with the other DAOs; a module holding one repository that depends on `:core:sync` anyway would be structure without substance. `QueueEntity` was already in schema v1, so the plan's real requirement — no migration for M3 — held |
| ~~Speed formatting duplicated~~ | Done 16 Aug — `:core:model/Formatting.kt`, along with the clock and length formatters and the Continue-row rule |
| `EncryptedSharedPreferences` / `MasterKey` deprecated | Still the practical option for encrypted token storage on Android; needs a replacement decision, not just a version bump |
| ~~`hiltViewModel` deprecated~~ | Done 16 Aug. The new artifact already arrives through `hilt-navigation-compose`, so it was a pure import change across thirteen files |
| ~~`MediaSession.ConnectionResult.AcceptedResultBuilder` deprecated~~ | Done 16 Aug. Only the single-argument *constructor* was deprecated, not the builder. The two-argument form is trust-aware, so the session now hands an untrusted controller Media3's restricted command set — its own default since 1.11 for anything that does not override `onConnect`. **Confirm car browse in the DHU**: Android Auto's host is trusted through the platform's `isTrustedForMediaControl`, but that is read rather than observed |
| ~~Time formatting duplicated~~ | Done 16 Aug, same file. Collected rather than merged: a *place* takes colons and a *length* takes units, and a dense line and a roomy one stay different functions |

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

### 3. In-app feedback, including a post-crash prompt — **DONE 2026-08-15**

`FeedbackScreen` and `PlaybackRecordScreen` both ship and are reachable from `MainActivity`;
the crash prompt is `CrashPrompt`. The plan below is kept as the record of why it is shaped
the way it is.

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
| ~~Process death itself is still not covered~~ | The second process exists: `:harness` is an application module with its own application id whose instrumented tests target *itself*, so it can end lugu's process and still be alive to say what happened. Closed on 17 Aug, when the seeded server let its resumption test run for the first time: process killed mid-book, `input keyevent 126`, same item back at 45209ms against 45239ms and at the 1.5x it was on. Removing the key press makes it fail, which is what says the button is doing the work. See [qa/instrumented.md](qa/instrumented.md), including why the strict test kills the process rather than force-stopping it, and what it does to the position of the title you nominate |
| **The offline-first line is the next one worth automating, and was not** | "Force-stop, airplane mode, reopen: the full library still renders and does not show the login screen" is the last M0 sign-in check a machine could take, and `:harness` is the right place — it force-stops lugu already. What stopped it is that the two legs cannot toggle the radio the same way: `cmd connectivity airplane-mode` arrived in API 30, and the older route (`settings put global airplane_mode_on` plus a broadcast) needs a permission shell does not have on newer versions. Cutting data another way — `svc wifi disable` — is a different claim from being offline. A test that works on one leg and is skipped on the other is worse than the manual line, because CI fails any skip |
| **Four screens are now driven for real, without a server** | `LibraryGridTest`, `RememberedListControlsTest`, `RotationTest` and `SignInTest` seed Room and drive the app, which is a different kind of coverage from the screenshot baselines below: it asserts what a screen *does* rather than what it looks like. The pattern has two sharp edges, both written down in [qa/instrumented.md](qa/instrumented.md) — a seeded `server` row is not a signed-in account without a token, and the compose rule must not launch the activity before the seeding |
| **Screenshot baselines cover components, not most screens** | 23 baselines in both themes, verified on every `./gradlew build`. But only Settings drives its real screen: every other screen takes a Hilt view model over final Room, DataStore and Ktor classes that cannot be faked, so those baselines are of the screens' own components arranged as the screen arranges them. A change to the order of blocks *inside* a screen file will not fail them. Making each screen's content a stateless `internal` composable would close it |
| **The Roborazzi Gradle plugin cannot be applied** | 1.46.1 reaches for AGP's `TestedExtension`, which AGP 9.3.1 removed, so applying it fails configuration. Its only job is setting two system properties, which the root build file now sets directly — so verification is the default and re-recording is `-Proborazzi.record`. Revisit when Roborazzi supports AGP 9 |
| Sleep timer service integration untested | The arithmetic is well covered; the wiring that pauses and restores volume is not |
| ~~`DownloadEngine` aggregation untested~~ | Done 16 Aug, and the premise here was wrong: no fake `DownloadIndex` was needed. Extracting the pure fold into `DownloadAggregation` reached the part that can actually be wrong, and a fake would have been the harder path — a Media3 `Download` is only obtainable through a real `DownloadManager`, which wants a cache directory, a database and a thread pool |
| The offline resolution path is untested end to end | `ManifestBuilder` and the shelf and search queries are covered; `MediaResolver.resolveFromDownload` is not, because it needs the repository, the ledger and Room together |

## Left behind by the 27 August parallel run

| Item | Detail |
| --- | --- |
| ~~**A deferred download delete leaks disk if the process dies**~~ | **Fixed 27 Aug.** Item 2 marks a completed download `pending_delete` and finalises it when the undo window closes. `finalizeDeferred` had exactly one caller: the undo coroutine in `ItemDetailViewModel`. A process death inside the window, or a view model cleared when the listener left the page, stranded the row. That state was invisible in three directions at once: `observeAll` and `observeForItem` excluded it, so no screen showed it; `unfinished()` excluded it, so the engine never touched it; and `pendingDeleteBytes()` was subtracted from the cap, so the space read as free. `DownloadRepository.sweepPendingDeletes()` now reads every `pending_delete` row across every account with the new `DownloadDao.pendingDelete()` query, and finalises each one. It runs from `LuguApplication.onCreate`, beside the existing `reconcile()` and `sweepFinished()` calls, rather than from `DownloadEngine.reconcile()` itself: `DownloadEngine` holds `DownloadDao` but not `DownloadRepository`, and `DownloadRepository` already holds `DownloadEngine`, so giving the engine the repository too would have been a real Hilt cycle. `removeRow` needed no `ActiveAccount` in the first place — every field its `delete` call reads is already on the row — so it lost the parameter |
| **Item 4 names a player affordance that does not exist** | The plan text for the landscape layout says to keep "queue entry" reachable in both orientations. There is no queue entry in the player, in either orientation: `PlayerActionRow` has no queue button and `MainActivity` passes no `onOpenQueue` callback. Adding one needs a new `PlayerScreen` parameter wired from the shell, plus a button in `PlayerActionRow` — which is photographed, so it is baseline-blocked |
| **The mini player now has two code paths** | The shell renders it on every route except Home, the player and sign-in. Home keeps its own `bottomContent = { MiniPlayer(...) }`, because Home's tab bar must stay the floor of the screen and absorb the gesture inset. Correct today, and one component in two places. Unify when `HomeScreen.kt` is next open |
| **The player has no mini-player show and hide animation to keep** | Item 1 says to keep the existing animation. There is none, in `MiniPlayer`, `MiniPlayerBar` or `HomeScreen`. None was invented. Worth adding on purpose, or worth deleting the promise from the plan |
| **Two visible fixes wait on the first real baseline record** | The download control still reads "Downloaded" rather than "Delete download" or "Cancel download" (only its content description was corrected), and the Library search placeholder still truncates to "Search title,". Both live in photographed components, so both need `record-baselines.yml` to have run once |
| **`record-baselines.yml` has never run** | Written, never dispatched. Its artifact path glob and its `-Proborazzi.record` reach across modules were verified by reading, not by a run |
| **Item 6's trim-skip undo does not reach the notification** | Item 6 of `UX-FIX-PLAN.md` raised the trim-skip and large-seek undo to a shell snackbar host in `MainActivity.kt`, so it now shows on the mini player and on every screen. The notification is a third surface named by the item text, and it is not covered. A media notification has no snackbar. Its buttons come from a fixed layout — `LuguNotificationProvider.kt` and `NotificationLayout.kt`, built from `PlayerSettings.notificationButtons` — with no field for a message like "Skipped the intro" and no plain-text control at all. An "Undo" button is possible in principle: a `CommandButton` swapped into the session's layout only while `pendingJump` holds a value, removed again on the same `noticeSeconds` timeout the other two surfaces use, and a new custom command for `LuguPlaybackService.onCustomCommand` to answer. That button would carry an icon and a content description, not a line of text a listener glances at, which changes what the button asks the listener to already know before they press it. Left undone: the change reaches the notification builder and the session's command negotiation together, and needs a real device to confirm the button reads clearly and does in fact disappear again, which this pass could not run |
