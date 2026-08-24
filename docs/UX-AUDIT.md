# UX audit

A full pass over every screen of the app for usability, tap economy, clarity and
delight. The audit ran on 20 August 2026 against commit `c06bd67`. Every file
reference points into the main tree at that commit, so a reference can drift as the
code changes.

Each finding gives the current behavior and the desired behavior. The audit found 82
findings: **12 high**, **43 medium** and **27 low**. Section 1 lists 10 things that
must not break while the rest gets fixed. Section 9 lists the decisions the audit
keeps: the docs already rejected some of these ideas with reasons, so the audit does
not raise them again.

[docs/UX-FIX-PLAN.md](UX-FIX-PLAN.md) turns the twelve highest findings into work
items, with the file collisions that decide what can run in parallel.

Method: six parallel code reviews (shell and navigation, browsing, item and queue
pages, player, settings and sign-in, visual polish), one cross-check against
`docs/BACKLOG.md`, `docs/FEEDBACK.md` and `docs/PLAN.md`, and manual inspection of
all 31 committed Roborazzi baselines. The code reviews cannot see pixels and the
baselines cannot see code, so some findings come only from the images: for example
the two conflicting numbers on the skip buttons.

## 1. What to keep

The audit found real craft. Do not lose these while fixing the rest.

- **The StatusStrip motion law.** "A message may appear, nothing else may move" — with a 400 ms appear gate, a 600 ms linger, reserved space near inputs, and polite live regions. It is documented, tested, and enforced. Most production apps never articulate this rule.

- **One-tap resume as the organizing principle of Home.** The Continue card makes the commonest journey cost one tap, and its transport button knows "loaded" from "not loaded" so it never re-buffers.

- **The undo architecture.** One jump type serves external position adoptions, smart rewind and podcast trims. Every automatic correction states its reason and carries Undo, with a position-history sheet as the safety net.

- **Deterministic rapid skips.** Three fast taps of −10 s move exactly 30 s: presses accumulate, the display answers instantly, one seek fires. Most players lose taps here.

- **Accessibility as craft.** The slider announces "3 h 12 m of 41 h 2 m", not a percentage. Cover progress bars speak. The fast-scroll rail merges 26 letters into one meaningful node.

- **The fast-scroll rail's honesty rules.** It appears only when the list is long, lettered, and alphabetically sorted — "it is a lie, not a shortcut" otherwise.

- **The settings surface.** Every setting is one declared entry with human synonyms ("2x", "dead air", "tunnel"), searchable, with its editor inline. Almost nothing is more than zero levels deep.

- **Radically honest feedback.** The user sees the literal payload that will be sent, composed by the same function that sends it. Consent is asked, never assumed.

- **The playback record reads like a diagnosis.** "Playback stopped 3 times today: once after the app was killed…" — headline first, evidence lines under it, claims scoped to what the evidence supports.

- **The trim section's honesty.** "Set for this show" vs "Following the default" in words and color, and an ad-skip switch that refuses to promise what it cannot do.

## 2. The twelve that matter most

Ranked by harm × frequency. Details live in the sections below.

1. The mini player exists only on the Home route — playback is invisible on seven other screens.

2. A tap on the "downloaded" tick deletes the download — instantly, beside Play, with no undo.

3. Queue "Clear" and row removal are one silent tap — the one list the listener builds by hand.

4. The player has no landscape layout — the transport falls off the screen.

5. Skip buttons draw two conflicting numbers — the configured seconds sit on icons with baked-in digits.

6. The trim-skip undo shows only inside the full player — the "every skip can be undone" promise fails everywhere else.

7. The Library grid has no empty state — search misses, filter misses and first sync all show blank space.

8. A tab switch discards both tabs' scroll positions.

9. Sign out is a single unguarded tap.

10. The login form ignores password managers — no autofill semantics, no show-password toggle.

11. Cold start flashes a dark window, then a blank frame — no splash API, no DayNight launch theme.

12. A podcast page has no primary play action — the most-visited page type buries its main verb.

## 3. App shell and navigation

Journey tap counts today: resume listening — 1 tap. Find a book and play it — 3 taps plus a query. Reach the queue from Home — 1 tap; from the player — several back presses plus a tap. No stranded back stacks were found.

### The mini player exists only on the Home route

**Severity: high.**

**Current.** The mini player is composed solely as the Home screen's bottom content. Item, episode, browse, collections, downloads, queue and settings screens show no playback state at all. The component's own KDoc claims "playback is always one tap away" — it is not.

**Desired.** Hoist the mini player into one shell-level scaffold around the NavHost. Hide it on the player and login routes only.

Where: `MainActivity.kt:246` · `PlayerScreen.kt:521`

### Cold start: dark flash, then a blank frame

**Severity: high.**

**Current.** `Theme.Lugu` extends the platform *dark* theme with no DayNight variant, so a light-mode start flashes dark. Then the app renders nothing during the auth check. The SplashScreen API is absent.

**Desired.** Adopt `core-splashscreen`: brand background (#2B211A) plus the launcher glyph, kept on screen with `setKeepOnScreenCondition` until the auth check ends. Give the theme a DayNight window background.

Where: `app/res/values/themes.xml:3` · `MainActivity.kt:207-211`

### A tab switch discards both tabs' scroll positions

**Severity: high.**

**Current.** The Home/Library switch is a plain `when (tab)`, so the hidden tab leaves composition and its lazy-list state dies with it. Deep in a 900-book grid, a glance at Home costs the position. Popping back from an item page keeps it — only the tab switch loses it.

**Desired.** Wrap tab content in `rememberSaveableStateHolder().SaveableStateProvider(tab)` so each tab keeps its state across switches.

Where: `HomeScreen.kt:160-181` · `LibraryScreen.kt:88`

### A tap on the Continue card body rebuffers the playing book

**Severity: medium.**

**Current.** The card body always calls `onPlay`, which re-resolves the session — the exact trap the adjacent button's comment routes around. Item-page Resume and queue-row taps share the exposure.

**Desired.** Guard once at the source: in `PlaybackConnection.play`, when the requested item and episode are already loaded, ensure playing and return. Every entry point becomes idempotent.

Where: `HomeScreen.kt:257-262` · `PlaybackConnection.kt:261-283`

### Player and item pages can stack duplicates

**Severity: medium.**

**Current.** Item → Player → title → Item → Play → Player leaves four entries. Back then walks through stale copies of the same global state. No player navigation uses `launchSingleTop`.

**Desired.** Navigate to the player with `launchSingleTop` plus `popUpTo` an existing player entry. When the player opens an item, pop to an identical entry directly below when one exists.

Where: `MainActivity.kt:277-279, 335-340`

### The queue is unreachable from the player

**Severity: medium.**

**Current.** "What plays after this" is a playback question, but the only entry to Up next is the Home top bar. From a deep stack the answer costs several back presses.

**Desired.** Add an Up-next affordance to the player's top bar or action row.

Where: `HomeScreen.kt:115` · `PlayerActionRow.kt`

### A long "Working" strip covers the library picker and cannot be put away

**Severity: medium.**

**Current.** Only Problem and Note strips dismiss. A first sync of a large library overlays the top of the content for minutes — on the Library tab, that is the library-picker chip row.

**Desired.** Let a long-running Working strip be dismissed (it returns on the next state change), or place the overlay below the chips on the Library tab.

Where: `StatusStrip.kt:167-168` · `HomeScreen.kt:186-192`

### The notification permission is requested on the login screen

**Severity: low.**

**Current.** The request fires at first composition — before sign-in, before anything played. It competes with the login form at the moment its value is least visible.

**Desired.** Ask on first play, when "lugu wants to show your playback controls" explains itself.

Where: `MainActivity.kt:75` · `NotificationPermission.kt:30-36`

### A notification tap that meets the login screen loses its target

**Severity: low.**

**Current.** The startup decision is made once at init. A user who taps an episode notification, sees login, and signs in lands on Home — the pending episode is consumed at the exact moment it becomes deliverable.

**Desired.** Hold the target until sign-in completes, then land on it.

Where: `MainActivity.kt:408-418` · `StartupViewModel.kt:30-34`

### The crash prompt speaks with two voices

**Severity: low.**

**Current.** "lugu crashed last time. Want me to look into it?" then a button "Tell me what happened" — two "me"s on opposite sides of the conversation. The popup also floats over the tab bar until answered.

**Desired.** One voice: "lugu crashed last time." with "Not now" / "Describe what happened". Anchor it above the bottom content.

Where: `CrashPrompt.kt:110-125`

## 4. Home and library browsing

### The Library grid has no empty state at all

**Severity: high.**

**Current.** A search with no matches, a filter with no matches ("Downloaded" before anything is downloaded is the likely first meeting), and a fresh sync all show identical blank space. Every sibling screen tells its emptinesses apart; the flagship list is the only mute one.

**Desired.** Distinguish the three: query → "Nothing matches '…'"; filter → "No downloaded books yet" with a one-tap "Show all"; genuinely empty → the sync wording Home already uses.

Where: `LibraryScreen.kt:173-217` · `contrast BrowseScreen.kt:306, DownloadsScreen.kt:197-206`

### Two identical shelf cards do different things on tap

**Severity: medium.**

**Current.** An in-progress card plays audio on tap; the card one shelf down opens a page. The only cue is a 3 dp progress sliver. An in-progress card also has *no* path to its detail page from Home — chapters and download live behind a Library search.

**Desired.** Overlay a small play glyph on cards whose tap makes sound, and give shelf cards long-press → open page, matching the gesture the grid teaches.

Where: `HomeScreen.kt:241-243` · `LibraryScreen.kt:328, 428-436`

### Downloaded state is invisible on every cover

**Severity: medium.**

**Current.** The view model tracks downloaded ids, but cards never show them. "Will this play on the plane?" needs the Downloaded filter or an item page — and Browse and Collection grids lack even the filter.

**Desired.** Draw a small corner badge (the existing DownloadDone glyph) on downloaded covers, on every grid that uses the card, with a spoken description.

Where: `LibraryViewModel.kt:180-185, 227-231` · `LibraryScreen.kt:320-398`

### A finished book looks like a 97% book

**Severity: medium.**

**Current.** `isFinished` exists and drives the filter, but the card draws only a full progress bar. The spoken description never says "finished" either.

**Desired.** Replace the bar with a corner tick when finished, and say "Finished" in the semantics.

Where: `LibraryScreen.kt:347-360` · `LibraryViewModel.kt:52, 60-68`

### Long-press means select on two screens and nothing on three

**Severity: medium.**

**Current.** The gesture toggles selection on the Library grid and Downloads, and dies silently on Browse groups, Collections, and shelves — the same card, the same finger, no answer.

**Desired.** Wire the same selection (and SelectionBar) into browse-group and collection grids. Same gesture, same meaning, every list. (The queue is exempt: long-press means drag there, by documented decision.)

Where: `BrowseScreen.kt:230-235` · `CollectionsScreen.kt:236-242` · `LibraryScreen.kt:201`

### Entering selection mode jolts the whole screen

**Severity: medium.**

**Current.** Selection swaps two control rows (~120 dp) for one, so the grid — including the card under the finger — leaps at the moment of long-press. The screen is otherwise scrupulous about not moving content.

**Desired.** Overlay the SelectionBar or match the swapped heights, so the pressed card stays put.

Where: `LibraryScreen.kt:135-157`

### The selection bar lives in a different place on each screen

**Severity: medium.**

**Current.** In-content below the picker chips on Library; replacing the top app bar on Downloads; a third copy on episode lists. One vocabulary, two grammars.

**Desired.** Standardize on the contextual top bar where a top bar exists; pin it above the tab content on the bar-less Library tab.

Where: `LibraryScreen.kt:136` · `DownloadsScreen.kt:102-115`

### Home has no pull-to-refresh; its sibling tab has one

**Severity: medium.**

**Current.** The same gesture works on Library and is dead on Home — the landing tab, where "why is my progress stale" is most asked.

**Desired.** Wrap Home's column in the same `PullToRefreshBox`; the view model is already in the shell.

Where: `HomeScreen.kt:96, 245` · `LibraryScreen.kt:168-172`

### Four icon-only selection actions, two of them near-twins

**Severity: medium.**

**Current.** Download, add-to-queue, mark-finished (`TaskAlt`) and mark-not-finished (`RemoveDone`) are unlabeled icons. The finished pair is a coin flip for sighted users; the screenshot confirms it. Eight slots also crowd narrow screens.

**Desired.** Keep the two commonest actions as icons with tooltips. Move the finished pair behind an overflow menu with text labels.

Where: `LibraryScreen.kt:273-293` · `ListControlsUi.kt:197-201`

### A permanently failing download can only be deleted through a hidden gesture

**Severity: medium.**

**Current.** A failed row offers only retry. Deletion requires knowing long-press selection exists. When the server supplies an error string, the "tap to try again" hint vanishes entirely.

**Desired.** Give failed rows retry *and* remove, and append the retry hint to the error rather than replacing it.

Where: `DownloadsScreen.kt:281, 296-303`

### Batch delete of downloads is instant and silent

**Severity: medium.**

**Current.** Select all → Delete drops gigabytes fetched for a trip with no confirmation, no undo, and no snackbar saying what happened — while Library batch actions always report.

**Desired.** Report "Deleted 12 downloads" with Undo; defer the file removal a few seconds to make the undo real.

Where: `DownloadsViewModel.kt:174-182` · `contrast LibraryViewModel.kt:337`

### A downloaded episode opens the show page, not the episode

**Severity: low.**

**Current.** Rows are keyed per episode, but every row opens the podcast page, and the user re-finds the episode.

**Desired.** Route rows with an episode id to the episode page.

Where: `DownloadsScreen.kt:213` · `DownloadsViewModel.kt:31`

### The search field misuses its label as a placeholder

**Severity: low.**

**Current.** "Search title, author, narrator, series" is the field's *label*; focused or filled, the whole sentence floats to the border and truncates — the Library baseline shows the literal string "Search title,".

**Desired.** Label "Search"; the long sentence becomes the placeholder, which disappears on typing as intended.

Where: `ListControlsUi.kt:89` · `library_tab_dark.png`

### "Continue listening" appears twice on the Home screen

**Severity: low.**

**Current.** The hero card is captioned "Continue listening" and the first shelf under it carries the same title — two adjacent blocks with one name and different tap behavior (the baseline shows both).

**Desired.** Keep the caption on the card; retitle the shelf ("Keep going", "In progress") or drop the card's currently-playing item from the shelf.

Where: `home_tab_dark.png` · `HomeScreen.kt`

### The browse links row clips at large font scale

**Severity: low.**

**Current.** Authors / Series / Narrators / Collections sit in a fixed Row. At font scale 1.5 on a narrow phone the last link falls off with no affordance it exists.

**Desired.** Use a LazyRow or FlowRow, like the filter chips below it.

Where: `LibraryScreen.kt:242-255`

### The search box earns its place on one screen and squats on another

**Severity: low.**

**Current.** Collections hides the box under nine entries; Browse always shows it, even for three narrators.

**Desired.** Apply `searchEarnsItsPlace` on Browse too. Same rule, both screens.

Where: `CollectionsScreen.kt:128` · `BrowseScreen.kt:89-103`

### The fast-scroll bubble can contradict the finger

**Severity: low.**

**Current.** The bubble shows the letter at the top of the viewport, not the letter under the finger. Dragging to Z whose section cannot reach the top shows "W" while the finger sits on Z.

**Desired.** Show the drag-chosen letter in the bubble; keep the list-derived letter for the rail's bold highlight only.

Where: `FastScrollRail.kt:126-133, 187-200` · `LibraryScreen.kt:106-108`

### Sort gives no feedback in the menu or the list

**Severity: low.**

**Current.** The open menu never marks the active choice (the chip it would confirm is covered by the menu). Choosing a sort keeps the raw scroll index, dropping the user at a meaningless spot in the new order.

**Desired.** Check the selected entry; scroll to top when the sort id changes. Keep position on filter changes, where context survives.

Where: `ListControlsUi.kt:135-145` · `LibraryViewModel.kt:296-298`

### TalkBack reads every card title twice

**Severity: low.**

**Current.** The cover image's description is the title, and the visible title text follows it.

**Desired.** Null the image description and merge the card's semantics.

Where: `LibraryScreen.kt:343, 382`

## 5. Book, podcast, episode and queue pages

### A tap on the "downloaded" tick deletes the download

**Severity: high.**

**Current.** In the complete state the whole button's click is remove — instant, no confirmation, no undo, and the only warning is TalkBack-only. It sits directly beside Play, so a misaimed Resume tap can silently delete a multi-gigabyte book. Mid-download, any tap cancels — including at 95%.

**Desired.** Keep the one-control lifecycle; make removal two-step. A snackbar with Undo fits the house style better than a dialog. Cancel-in-progress gets "Download cancelled — Resume".

Where: `ItemDetailScreen.kt:983-1003` · `ItemDetailViewModel.kt:363-368`

### Queue "Clear" and row removal are one silent tap

**Severity: high.**

**Current.** "Clear" wipes the queue immediately; the row X removes with no report; batch removal is equally silent. The file's own KDoc calls this "the one list in the app the listener composes themselves" — and it is the cheapest to destroy.

**Desired.** Snapshot and offer Undo in a snackbar for both (the view model already holds item and index). Undo keeps the common case one tap; it also clears the way for swipe-to-dismiss on rows later.

Where: `QueueScreen.kt:102, 321-323` · `QueueViewModel.kt:70-74, 106-114`

### A podcast page has no primary play action

**Severity: high.**

**Current.** The Play/Resume row is gated on `mediaType == BOOK`. On a podcast, the only play affordance is a 40 dp icon on an episode row — below the cover block, show notes, trim section and filter bar. A feed with a half-heard episode offers no "Continue" above the fold.

**Desired.** Give the podcast header a full-width primary button: "Resume ‹episode›" when any episode has progress, else "Play latest". One tap saved on every visit to the most-visited page type.

Where: `ItemDetailScreen.kt:230-255`

### Queue reorder: a decoy handle, no autoscroll, no accessible path

**Severity: medium.**

**Current.** The drawn handle is decorative; the real detector is long-press anywhere on the list, so grabbing the handle and dragging does nothing (its own contentDescription admits it). The drop target only resolves among visible rows, so a drag stalls at the viewport edge. TalkBack users cannot reorder at all. No haptic marks pick-up.

**Desired.** Make the handle a true drag initiator (no long-press); keep long-press-anywhere as the bonus. Autoscroll near the edges. Add "Move up / Move down" custom accessibility actions. Tick on pick-up and on row swap.

Where: `QueueScreen.kt:179-217, 246-333`

### "Mark as not finished" silently throws the position away

**Severity: medium.**

**Current.** The KDoc states the loss plainly; the menu item and the confirmation snackbar never do.

**Desired.** Preserve the position on un-finish, or say it where it can be acted on: "Marked as not finished — position reset" with Undo restoring the old progress.

Where: `ItemDetailViewModel.kt:400-413` · `ItemDetailScreen.kt:653-668`

### Episode-row controls sit under the 48 dp minimum

**Severity: medium.**

**Current.** Play, the download control and the overflow are 40 dp, shoulder to shoulder, and the miss either opens the wrong page or (previous finding) deletes a download.

**Desired.** Keep the 40 dp visuals; give each a 48 dp touch target via `minimumInteractiveComponentSize`.

Where: `ItemDetailScreen.kt:550, 619, 967`

### Long descriptions never collapse

**Severity: medium.**

**Current.** A podcast's three paragraphs of marketing copy sit above the trim section and every episode — a full screen of scrolling on each visit.

**Desired.** Collapse to ~4 lines with an animated inline "More".

Where: `ItemDetailScreen.kt:257-264`

### "Resume" lies on finished items and hides its promise

**Severity: medium.**

**Current.** The label is Resume whenever progress > 0 — a finished book offers to resume the epilogue's last seconds. The "2 h 13 m of 11 h 4 m" line floats separately above.

**Desired.** Three states: "Play", "Resume · 8 h 51 m left", "Play again" when finished. Put the figure in the button; the primary action carries its own promise.

Where: `ItemDetailScreen.kt:218-225, 239` · `EpisodeScreen.kt:200`

### An episode filter left on one show reshapes every other show

**Severity: medium.**

**Current.** Episode sort and filter persist globally. "Downloaded" left on for show A makes show B look near-empty next week; the empty state does not name the control that hid the rows.

**Desired.** Persist per item (the trim already does), or make the empty and count lines name the filter with a one-tap "Show all".

Where: `ItemDetailViewModel.kt:287-334` · `ItemDetailScreen.kt:300-318`

### The book page cannot say how the book is divided

**Severity: low.**

**Current.** Chapters exist only inside the player's sheet. The page cannot answer "how many chapters, how long" without starting playback.

**Desired.** A one-line "42 chapters" under the duration, optionally expandable.

Where: `ItemDetailScreen.kt` · `ChapterSheet.kt`

### The episode page has no artwork and no way to its show

**Severity: low.**

**Current.** Title, subline, buttons, notes — no cover, and from a notification cold-start there is no link to the show page to browse siblings.

**Desired.** A small cover beside the title block; make the show-title header navigate to the podcast page.

Where: `EpisodeScreen.kt:57-64, 161-171`

### The hero pages waste their upper half

**Severity: low.**

**Current.** The baselines show it: the book page strings author, narrator and series down the left with a screen-height of dead space; the podcast page renders a stray "—" where metadata is empty. The "—" for absent values is already a recorded fragility.

**Desired.** Tighten the header into one block beside the cover (author · narrator · series as wrapping rows). Render nothing for absent metadata.

Where: `item_book_dark.png` · `item_podcast_dark.png` · `BACKLOG.md ("— on three screens") known gap`

### Small geometry drift on item and queue screens

**Severity: low.**

**Current.** The list controls pad 16 dp inside an already-16 dp-padded list, so the search field sits 32 dp from the edge while rows sit at 16. Selection highlights stop at the content padding and read as inset stripes. The queue ordinal clips at three digits.

**Desired.** Make the bar's inner padding a parameter and zero it inside padded lists. Paint row backgrounds edge-to-edge and pad inside (the queue rows already do). Give the ordinal `widthIn(min)`.

Where: `ListControlsUi.kt:82` · `ItemDetailScreen.kt:165, 500-509` · `QueueScreen.kt:278-283`

### Trim offers presets only, capped at 90 s

**Severity: low.**

**Current.** Shows with two-minute preambles cannot be trimmed accurately.

**Desired.** Add a "Custom…" chip with a seconds field, or extend the ladder to 120/180.

Where: `ItemDetailScreen.kt:779-806` · `SkipRegions.kt:51`

### The queue screen carries dead settings plumbing

**Severity: low.**

**Current.** Three continue-behavior handlers and their state exist in the view model; the screen never uses them, and its empty state apologizes by pointing at Settings.

**Desired.** Surface the two continue toggles on the queue screen — the natural home — or delete the handlers.

Where: `QueueViewModel.kt:116-120` · `QueueScreen.kt:115-118`

## 6. Player

### The player has no landscape layout

**Severity: high.**

**Current.** One fixed column, no scroll, cover at 80% width and 1:1. In landscape the cover alone exceeds the viewport; slider, transport and action row are clipped and unreachable.

**Desired.** Cover left, controls right when width exceeds height (or via WindowSizeClass). At minimum, cap the cover height and make the column scrollable.

Where: `PlayerScreen.kt:275-298`

### The skip buttons draw two conflicting numbers

**Severity: high.**

**Current.** The configured seconds are painted over `Replay10`/`Forward30` — icons with digits baked into the vector. With the default 15 s back, the button shows a "10" with a 9 sp "15" on top. The player baseline shows the collision.

**Desired.** Use genuinely neutral arc icons (or a custom path) and keep the overlay; then the number can grow past 9 sp.

Where: `PlayerScreen.kt:416, 438, 679-691` · `player_dark.png`

### The trim-skip undo is visible only inside the full player

**Severity: high.**

**Current.** The undoable-jump flow's only consumer is the player screen's snackbar. On Home, on the lock screen, in another app, the listener hears the jump and sees nothing — and the notice can arrive minutes stale with no time attached. The README's promise ("every skip says so and can be undone") holds on one screen.

**Desired.** Host the jump snackbar at shell level, above the mini player, on every screen. When shown late, say when ("2 min ago"). Consider a transient line in the media notification.

Where: `PlayerScreen.kt:147-166` · `SkipRegionEnforcer.kt:48-62`

### Two time systems sit 24 dp apart, unlabeled

**Severity: medium.**

**Current.** The chapter readout is chapter-relative; the slider and its labels are book-relative. Nothing says which the drag moves, and on a 40-hour book the drag resolves to ~2 minutes per pixel. Scrubbing never previews the chapter you will land in.

**Desired.** Draw chapter ticks on the track, preview the landing chapter while scrubbing, and offer a chapter-relative seek mode (tap-to-toggle on the bar or a setting).

Where: `PlayerScreen.kt:343-383`

### The time labels are inert and ignore playback speed

**Severity: medium.**

**Current.** Elapsed and −remaining, not tappable, with no "ends at 22:41" and no speed-adjusted remaining — although `wallClockSecondsAt` exists and serves the sleep and bookmark sheets.

**Desired.** Make the right label cycle: remaining → remaining at speed → end-of-book clock time. Persist the choice.

Where: `PlayerScreen.kt:374-383` · `PlayerTime.kt:20`

### No gestures anywhere in the player

**Severity: medium.**

**Current.** No swipe-down to dismiss the full player, no swipe on the mini player, no hold-to-repeat on the skip buttons.

**Desired.** Swipe-down (predictive-back) dismissal; swipe-to-dismiss the mini player with an undoable stop; repeat-on-hold for the skips.

Where: `PlayerScreen.kt:261-269, 584-622`

### A speed change costs three interactions

**Severity: medium.**

**Current.** Chip → sheet → preset — and the sheet never closes itself, so a scrim tap follows.

**Desired.** Dismiss on a preset tap (keep it open for the ± stepper). Consider long-press on the chip cycling presets.

Where: `PlayerScreen.kt:196-203` · `PlayerActionRow.kt:48-52`

### The chapter readout does not look tappable

**Severity: medium.**

**Current.** The text is the only entry to the chapter sheet — no chevron, no icon, no role. Discoverability rests on accident.

**Desired.** A trailing chevron when a chapter list exists, plus `Role.Button`.

Where: `PlayerScreen.kt:327-334`

### The mini player's progress bar answers a question nobody asked

**Severity: medium.**

**Current.** The 2 dp bar shows whole-book progress while the subtitle names the chapter; on a long book the bar is visually static and unlabeled.

**Desired.** Show chapter progress to match the subtitle, or keep book progress and speak it ("43% of the book").

Where: `PlayerScreen.kt:536, 580-583`

### An armed sleep timer is invisible

**Severity: medium.**

**Current.** The bedtime icon tints; the remaining time lives only inside the sheet. "How long left?" costs a sheet open.

**Desired.** A countdown chip beside the icon, like the speed chip ("23 min").

Where: `PlayerActionRow.kt:54-64` · `SleepSheet.kt:147-153`

### Position history hides the timestamps it carries

**Severity: medium.**

**Current.** The model has `atMs` and a reason; rows show neither, so twenty near-identical jumps cannot be told apart. Each row is clickable *and* carries a Restore button doing the same thing.

**Desired.** Relative time and reason per row; one tap target.

Where: `PlayerScreen.kt:650-668` · `PlaybackConnection.kt:66-71`

### Bookmarks: the naming moment is three steps late

**Severity: low.**

**Current.** One-tap bookmarking is right, but naming needs sheet → edit → dialog — and the rename dialog opens without focus, keyboard, or an IME Done mapping.

**Desired.** A "Name" action on the confirmation snackbar opening the dialog directly, auto-focused, text selected, Done = save.

Where: `PlayerScreen.kt:465-475` · `BookmarkSheet.kt:189-219`

### Sheets disagree about how far they open

**Severity: low.**

**Current.** Chapter and bookmark sheets skip the half state; sleep, speed and history open half-height — the long sleep sheet needs a drag.

**Desired.** One policy; the sleep sheet at least skips the partial state.

Where: `ChapterSheet.kt:102` · `SleepSheet.kt:123` · `PlayerScreen.kt:641, 701`

### The transcoding note resurrects, and speaks developer

**Severity: low.**

**Current.** "Transcoding — seeking is less precise" returns on every player visit (its dismissal is a plain `remember`), and names the mechanism, not the consequence.

**Desired.** Hold the dismissal per item in the ViewModel. Reword to the consequence: "Converted for streaming — jumps may land a few seconds off."

Where: `PlayerScreen.kt:109, 517`

## 7. Settings, sign-in and auxiliary screens

### Sign out is a single unguarded tap

**Severity: high.**

**Current.** The button signs out and navigates away immediately — no confirmation, no statement of what survives. Clearing the playback record is confirmed; the most destructive action in the area is not.

**Desired.** Confirm, and state the consequences: what happens to downloads, that progress lives on the server, that headers are kept. This is the one place a dialog earns its stop.

Where: `SettingsScreen.kt:1110-1118` · `AuthRepository.kt:101-112`

### The login form ignores password managers and hides typos

**Severity: high.**

**Current.** No autofill semantics on any field, so Google/1Password/Bitwarden never offer to fill or save — on the screen with the least-memorable password. No show-password toggle either (the file imports the icons and uses them elsewhere); the certificate dialogs share the gap.

**Desired.** Add `ContentType.Username`/`Password` semantics and the standard eye toggle on all three password fields.

Where: `LoginScreen.kt:98-166, 331-342` · `ConnectionScreen.kt:381-392`

### The sign-in flow drops small stitches

**Severity: medium.**

**Current.** IME "Next" on the server field runs the probe but never advances focus. The probe's result (`serverVersionHint`) exists in state and is never rendered. While signing in, the fields stay editable and race the in-flight request.

**Desired.** Advance focus in onNext; show "Server found — v2.28" near the field; disable the fields while busy with a short status line.

Where: `LoginScreen.kt:104-108, 174-188` · `LoginViewModel.kt:29`

### The Connection screen cannot be found by settings search

**Severity: medium.**

**Current.** Searching "connection", "proxy", "certificate", "header" or "cloudflare" returns "Nothing matches" — exactly the words the screen's own doc says its users arrive with. The link hides inside the account entry's keywords.

**Desired.** Give Connection its own entry, or add its vocabulary to the account entry.

Where: `SettingsScreen.kt:1072-1078`

### Switch rows toggle only on the switch

**Severity: medium.**

**Current.** Title and switch are separate elements; the tap target is the ~48 dp switch at the far edge — the exact failure the file's own LinkRow comment warns about. TalkBack reads two nodes. The feedback screen's attach row shares the construction.

**Desired.** `Modifier.toggleable(role = Switch)` on the whole row, merged semantics, `onCheckedChange = null` on the Switch.

Where: `SettingsScreen.kt:1225-1242, 1668-1673` · `FeedbackScreen.kt:352-370`

### Connection errors leak machine text

**Severity: medium.**

**Current.** "Nothing answered within 4000 milliseconds … Reason given: java.net.ConnectException: …" on the test; the sign-in probe passes raw resolver errors verbatim. The 401/404/429 mappings are excellent; the transport family got away.

**Desired.** Translate the common families once — host not found, refused, timed out, TLS — into one plain clause each ("That address could not be found — check the spelling"), raw reason as a smaller second line. Format the deadline in seconds.

Where: `ConnectionViewModel.kt:153-155` · `AbsClient.kt:143` · `LoginViewModel.kt:126, 151`

### Deleting a custom header or certificate is instant and unrecoverable

**Severity: medium.**

**Current.** The bin icon deletes a masked credential with no confirmation and no undo. Behind Cloudflare Access, losing it means being locked out until the secret is dug out of the dashboard.

**Desired.** Confirm with the cost stated ("lugu cannot recover its value"), or offer a brief undo. Same for certificate Remove.

Where: `ConnectionScreen.kt:271-273, 342` · `LoginScreen.kt:261-263`

### Seventeen categories, one long scroll, no way to jump

**Severity: medium.**

**Current.** ~40 settings render inline across 17 categories. Search mitigates well, but a scanner without the right word faces screenfuls; Account — home of sign-out and Connection — is 15 categories down.

**Desired.** A category chip strip under the search box that scrolls to a section. Consider pinning Account higher.

Where: `SettingsScreen.kt:189-1207`

### Feedback is one-way and never says so

**Severity: medium.**

**Current.** No contact field, and "Sent. Thank you…" reads like the start of a correspondence. Users who expect an answer will conclude they were ignored.

**Desired.** One line before Send: "Feedback is anonymous and one-way — include an email in the text if you want a reply." Also state the redaction promise the code already keeps ("addresses and tokens are blanked before it leaves the phone").

Where: `FeedbackScreen.kt:196-208, 326-331, 442-445` · `FeedbackReport.kt:66-68`

### The playback record speaks UK time to everyone

**Severity: medium.**

**Current.** `Locale.UK` is hard-wired for times and dates — in the one screen that asks the user to correlate times with memory.

**Desired.** Locale-default formats, as the certificate expiry already does.

Where: `PlaybackRecordScreen.kt:305` · `PlaybackRecord.kt:212` · `contrast ConnectionScreen.kt:363`

### Settings hidden by a parent toggle vanish from search

**Severity: low.**

**Current.** "Default podcast speed" and "How hard to shake" only exist in the index while their parents are on — against the file's own principle that "a switch you cannot find again is not a switch".

**Desired.** Always index them; render them disabled (or auto-enable the parent) when reached via search.

Where: `SettingsScreen.kt:363-378, 612-630, 1367`

### Quiet successes and dead ends

**Severity: low.**

**Current.** Saving the second address gives no acknowledgment. The version row is inert though it exists for bug reports. The empty search state offers no way out. A slow Sentry start mislabels itself "this build has no crash reporting".

**Desired.** A one-line save note through the StatusStrip. Copy-on-tap for the version. A "Clear search" action in the empty state. A distinct timeout message ("still starting — try again in a moment").

Where: `ConnectionViewModel.kt:108-121` · `SettingsScreen.kt:139-148, 1182` · `FeedbackScreen.kt:164-191`

### The default-speed chips ignore the user's own presets

**Severity: low.**

**Current.** The row renders the fixed ladder only; edited presets from the entry directly below never appear, and a value that matches no chip shows nothing selected. Unit spacing also drifts across the screen ("30s", "5 s", "10 min").

**Desired.** Render the user's presets plus the current value as a chip when it matches nothing. Pick one unit convention.

Where: `SettingsScreen.kt:203, 574, 643, 751, 1435-1449`

### The login screen sits under the system bars

**Severity: low.**

**Current.** A raw scrolling column with `imePadding` only; with the advanced section open on a small screen, content runs under the status bar and behind the gesture bar. Everywhere else edge-to-edge is handled correctly.

**Desired.** `windowInsetsPadding(WindowInsets.safeDrawing)` on the column.

Where: `LoginScreen.kt:80-86`

## 8. Visual design, motion and delight

The app is functionally deep and visually inert. Nothing here blocks use; together these are the distance between "works" and "a delight".

### Dynamic color erases the brand on Android 12+, and the cover never colors anything

**Severity: medium.**

**Current.** SDK ≥ 31 always takes the wallpaper scheme; the warm amber/plum brand palette only survives on Android 8–11. Meanwhile the most natural color source in an audiobook app — the cover — seeds nothing.

**Desired.** Make dynamic color a setting (default on is fine) with the brand scheme as the alternative. Seed the player screen from the current cover (Palette / material-kolor) — a blurred or tinted backdrop behind the art is the single cheapest "this app is alive" moment available.

Where: `app/ui/Theme.kt:14-36` · `PlayerScreen.kt:552-563`

### No theme control, no true black

**Severity: medium.**

**Current.** The theme follows the system only; no light/dark override, no OLED black — in an app used in bed with a sleep timer.

**Desired.** A Theme setting (system / light / dark) plus a "True black" toggle remapping surfaces to #000.

Where: `Theme.kt:25-27` · `SettingsScreen.kt (no theme entry)`

### Zero haptic feedback in an app full of gestures

**Severity: medium.**

**Current.** No `HapticFeedback` usage exists in any UI code. The fast-scroll rail changes letters silently; queue drag gives no tick on pick-up or swap; bookmark add, selection toggles and chapter boundaries are all mute.

**Desired.** A letter tick on the rail, LongPress on drag pick-up, SegmentFrequentTick on row swap, Confirm on bookmark and selection. Small effort, large perceived-quality gain.

Where: `FastScrollRail.kt:126-133` · `QueueScreen.kt:180-217`

### Every transition is the stock crossfade; the cover never travels

**Severity: medium.**

**Current.** The NavHost declares no transitions; the tab switch is a hard cut; the cover appears at four sizes (grid, item header, mini player, full player) with no continuity. Predictive back previews a bland fade.

**Desired.** Directional slide+fade pairs for drill-in and pop; fade-through on the tab switch; `SharedTransitionLayout` carrying the cover from grid card to item header to player. This is the biggest "feels native vs feels default" lever available.

Where: `MainActivity.kt:217` · `HomeScreen.kt:163-183`

### Keyed lists never animate placement

**Severity: medium.**

**Current.** Fourteen lazy lists supply stable keys; none uses `Modifier.animateItem()`. Queue reorders snap when Room re-emits; filters teleport rows.

**Desired.** One modifier per site: queue, downloads, collections, browse, the grid.

Where: `LibraryScreen.kt:189` · `QueueScreen.kt:225` · `DownloadsScreen.kt:208`

### Covers are force-cropped to squares on the hero surfaces

**Severity: medium.**

**Current.** `aspectRatio(1f)` + Crop everywhere. Audiobook jackets are commonly 2:3; the crop removes title and author text from the artwork — worst on the player, the largest image in the app.

**Desired.** Respect the intrinsic ratio on the player and item header (Fit inside a max-height box, ratio remembered per item). Squares stay fine in grids.

Where: `PlayerScreen.kt:283-298` · `ItemDetailScreen.kt:170-178` · `LibraryScreen.kt:335-346`

### A failed cover is indistinguishable from a loading one

**Severity: medium.**

**Current.** The shared ImageLoader crossfades and disk-caches (good), but no call site sets an error or placeholder painter — a failed load leaves a bare grey rectangle forever. Five screens hand-roll the same clip+background recipe.

**Desired.** One `CoverImage` composable in core/ui: glyph fallback on error, tonal per-item placeholder while loading.

Where: `LuguApplication.kt:176-188` · `LibraryScreen.kt:341` · `PlayerScreen.kt:283`

### Loading states are bare text; play/pause never morphs; empty states are unstyled paragraphs

**Severity: low.**

**Current.** "Loading…" as literal centered text on item, episode, browse and collection pages. The app's most-pressed button hard-swaps its glyph in all three transports. Empty-state copy is excellent and rendered as raw body text.

**Desired.** Skeleton headers behind the StatusStrip's own 400 ms gate. An animated play↔pause glyph. A shared `EmptyState(icon, title, body, action?)` composable.

Where: `ItemDetailScreen.kt:153-156` · `PlayerScreen.kt:426, 618` · `QueueScreen.kt:115-118`

### Typography, shapes and the launcher glyph are stock

**Severity: low.**

**Current.** No Typography, no Shapes; corner radii drift 6/8/12 dp ad hoc. The wordmark is plain displaySmall. The launcher foreground's 3 px arc thins to invisibility at 24 dp (the monochrome layer itself is correctly plumbed).

**Desired.** A warm reading face for the wordmark, shelf titles and Now-playing title; one radius token; redraw the glyph at ≥6-unit stroke and test at 24 dp against both masks.

Where: `Theme.kt:37` · `LoginScreen.kt:89` · `drawable/ic_launcher_foreground.xml`

### Top bars never respond to scroll; strings never left Kotlin

**Severity: low.**

**Current.** No `scrollBehavior` on any of 15 Scaffold sites, so content slides under the status bar with no surface tint. All user-facing strings are Kotlin literals; strings.xml holds only the app name — localization is foreclosed silently.

**Desired.** Pinned scroll behavior on scrolling screens. Decide localization: record English-only as a scope decision, or start migrating strings module by module.

Where: `all Scaffold sites` · `app/res/values/strings.xml`

## 9. Decisions the audit honors

The docs record deliberate choices with reasons. This audit does not re-raise them, and fixes proposed above stay inside them — undo over dialogs wherever undo can be real.

**Not re-raised (rejected in docs):** dialogs for in-place information · remembering search text (a stale search reads as lost data) · tab switches in the back stack · long-press-select on the queue (long-press means drag there) · chapters as the car's queue · chapter-scoped notification progress · detecting unmarked adverts · paging long episode lists · a "Keep" button on position notices.

**Already in BACKLOG.md (confirmed, not new):** the "—" placeholder rule on three screens · per-row download errors vs the no-reflow rule · continuation cannot be undone · no per-item download-notification progress · offline covers for never-opened items · add-to-collection missing from the grid selection bar · the car speed button's fixed label.

**Fixed recently and verified still good:** the status strip replacing the shifting top-bar spinner · the honest "starting" play button · mini player and tab bar separation · the tappable now-playing cover · download refusals that show their arithmetic.
