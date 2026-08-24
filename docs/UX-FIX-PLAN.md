# UX fix plan — the twelve highest findings

This plan turns the twelve highest findings of [docs/UX-AUDIT.md](UX-AUDIT.md) into
work items. Each item gives the desired end state, the approach, the files it owns and
the risk to watch. The plan exists to make parallel work safe: the *ownership* section
of each item is the contract that keeps two workers out of one file.

**Status: none of the twelve is implemented.** Agents started this work on 21 August
2026 and again on 24 August 2026. Both runs stopped before any agent wrote code, so
`main` is unchanged at `c06bd67`. Treat every item below as open.

Read [docs/FEEDBACK.md](FEEDBACK.md) before you start any item. It holds the design
laws these fixes must obey, and it records ideas the project rejected with reasons.

## The three laws every item obeys

1. **A message may appear, and nothing else may move.** Transient notices render
   through `core/ui/StatusStrip.kt`. Layout never shifts. No snackbars. No dialogs for
   information.
2. **Undo over confirmation.** A destructive action runs at once and offers undo. Undo
   restores the previous state exactly. Do not add a confirmation dialog.
3. **Offline first.** Every screen renders from the local Room database.

Sign-out is the one exception to law 2, because it drops tokens and cannot be undone.
Item 9 handles it inline, without a dialog.

## Ownership map

Two items must never edit one file at the same time. The map below assigns every file
to exactly one item. `PlayerScreen.kt` is the trap: it holds the full player *and* the
mini player, so items 1, 4 and 5 all appear to need it. Item 4 and item 5 own it. Item
1 reads it and edits the shell instead.

| Owner | Files it may edit |
|---|---|
| Item 1 | `app/` shell and navigation only |
| Items 4, 5 | `:feature:player` only |
| Items 2, 12 | `ItemDetailScreen.kt` and its state, plus `:core:download` |
| Item 3 | `QueueScreen.kt`, its view model, the queue repository |
| Item 7 | `LibraryScreen.kt` and its state, `ListControlsUi.kt` |
| Item 8 | `HomeScreen.kt` |
| Item 9 | `SettingsScreen.kt` |
| Item 10 | `LoginScreen.kt` |
| Item 6 | `SkipRegionEnforcer.kt`, the trim notice plumbing, the shell notice host |
| Item 11 | `themes.xml`, launch theme, `MainActivity.kt` startup path |

Nobody edits `core/ui/StatusStrip.kt`. Every item consumes it as it is.

## Order of work

Run the items in three waves. A wave starts after the previous wave merges.

- **Wave 1 (parallel, no shared files):** items 2, 3, 4, 5, 7, 8, 9, 10, 12.
- **Wave 2 (after wave 1 merges):** item 1. It hoists the mini player into the shell,
  so it wants the player module already settled.
- **Wave 3 (after item 1 merges):** items 6 and 11. Item 6 needs the shell-level notice
  host that item 1 establishes. Item 11 edits the same startup path as item 1.

## 1. Show the mini player on every screen

**Current.** `MainActivity.kt:246` composes the mini player as the Home screen's bottom
content. Seven other screens show no playback state. The KDoc of the component promises
that playback is always one tap away.

**Desired.** The shell renders the mini player on all routes except the full player and
the sign-in flow.

**Approach.** Move the mini player into the app-level `Scaffold` around the `NavHost`,
above the bottom navigation bar. Drive visibility from the current back-stack entry
route. Keep the existing show and hide animation. Keep the existing rule that hides it
when nothing is playable. Check how bottom padding reaches the screens today and reuse
that mechanism, so no feature screen needs an edit.

**Risk.** Content behind the bar. Verify the inset path before you change routes.

## 2. Make a deleted download undoable

**Current.** `ItemDetailScreen.kt:983-1003`. One tap on the tick beside Play deletes the
download at once, with no undo. While a download runs, one tap cancels it, even at 95%.

**Desired.** The delete still costs one tap. It becomes undoable.

**Approach.** Defer the file deletion. Mark the download pending-delete in
`:core:download`, show an undo notice through the StatusStrip pattern, and remove the
files only after the undo window ends. Undo inside the window restores at once, because
nothing was deleted yet. While pending-delete, the item renders as not downloaded. A
tap on a running download still cancels, and undo re-enqueues it. Make the control say
what a tap does: "Delete download" or "Cancel download", not "Downloaded".

**Risk.** A pending-delete download must not play, and must not count as free space.

## 3. Make queue "Clear" and row removal undoable

**Current.** `QueueScreen.kt:102` empties the whole queue in one silent tap.
`QueueScreen.kt:321-323` removes one row the same way. The queue is the one list the
listener builds by hand.

**Desired.** Both actions still act at once, and both offer undo.

**Approach.** Snapshot the queue before the change. Show a notice through the
StatusStrip pattern: "Cleared queue (N items) — Undo" and "Removed <title> — Undo".
Undo restores the snapshot: same items, same order, same positions. Follow the timing of
the existing undo pattern. Do not add a selection mode; `docs/FEEDBACK.md` rejected
long-press-select on the queue, because long-press means drag there.

## 4. Give the player a landscape layout

**Current.** `PlayerScreen.kt:275-298` lays out one portrait column. In landscape the
transport clips off the bottom of the screen.

**Desired.** Cover art and titles on one side, seek bar and transport on the other.
Everything is visible on a phone in landscape, with no clipping and no scrolling.

**Approach.** Factor the shared pieces into composables, then compose them twice. Detect
orientation with the pattern the codebase already uses, or with `LocalConfiguration` or
`BoxWithConstraints`. Keep chapters, sleep timer, speed, queue entry and the trim notice
reachable in both orientations.

## 5. Draw one number on a skip button

**Current.** `PlayerScreen.kt:679-691` uses `Icons.Replay10` and `Icons.Forward30`. The
digits 10 and 30 are part of those glyphs, and the configured seconds are drawn as text
on top. A listener who configures 15 and 45 seconds sees two different numbers on one
button. The player baseline shows the collision.

**Desired.** One number, and it is the configured one.

**Approach.** Draw a digit-free skip glyph: an arc with an arrowhead, mirrored for back
and forward, with the configured seconds centered inside it. Match the size, the tint
and the touch target of the neighboring transport icons. The content description states
the real seconds.

## 6. Show the trim-skip undo everywhere

**Current.** `PlayerScreen.kt:147-166` with `SkipRegionEnforcer.kt:48-62`. A trim skip
announces itself and offers undo only inside the full player. The README promises that
every skip can be undone.

**Desired.** The notice and its undo appear wherever the listener is: mini player,
another screen, or the notification.

**Approach.** Raise the trim event to the shell notice host that item 1 establishes.
Keep the undo window identical in every surface. Do this item after item 1.

## 7. Give the Library grid three empty states

**Current.** `LibraryScreen.kt:173-217` renders blank space for an empty grid. A first
sync, a search that matched nothing and a filter that excludes everything look the same.

**Desired.** Three distinct quiet states, chosen from data the screen already holds:

- A sync in progress says the library is syncing. A truly empty library says so.
- A search with no matches names the query and offers "Clear search".
- A filter that hides everything says so and offers "Clear filter".

**Approach.** Reuse any existing empty-state idiom in the codebase before you invent
one. Keep it typographically quiet. Fix the search field in the same pass:
`ListControlsUi.kt:89` uses a long label as a placeholder, and the label truncates to
"Search title," in the Library baseline. Do not change how search text persists;
`docs/FEEDBACK.md` decided against remembering it.

## 8. Keep the scroll position of both tabs

**Current.** `HomeScreen.kt:160-181` switches tab content with a `when`, so the hidden
tab leaves composition and its lazy-list state dies. A glance at the other tab costs the
position in a long grid.

**Desired.** Each tab keeps its own scroll position across a switch and across a
configuration change.

**Approach.** Hoist one lazy-list state or lazy-grid state per tab above the `when`. Use
`rememberSaveable` with the matching saver, so rotation and process recreation keep the
position. Do not add a per-tab back stack; `docs/FEEDBACK.md` rejected that.

## 9. Guard sign-out without a dialog

**Current.** `SettingsScreen.kt:1110-1118`. One tap signs out. Tokens are dropped and the
session ends. A slip while scrolling settings is not recoverable.

**Desired.** Sign-out needs deliberate intent, inline, with no modal dialog.

**Approach.** The first tap arms the row: the label becomes "Tap again to sign out",
tinted with the error color, and the row disarms itself after a few seconds. The second
tap inside that window signs out. Announce the armed state for accessibility. If the row
gets a consequence line, read the sign-out code first and write only what is true about
downloads and local data.

## 10. Let a password manager fill the sign-in form

**Current.** `LoginScreen.kt:98-166`. The fields carry no autofill semantics, so
password managers cannot fill them. The password field has no show-password toggle. The
keyboard does not walk the form or submit it.

**Desired.** Autofill works, the password can be revealed, and the keyboard walks the
form.

**Approach.** Mark the user name and password fields with the content-type semantics of
the Compose version in `gradle/libs.versions.toml`. Add a trailing icon that toggles
password visibility, with correct content descriptions. Set the IME actions: server URL
to Next, user name to Next, password to Done, and let Done submit through the same code
path as the button. Give the server URL field `KeyboardType.Uri` and no autocorrect.
Keep the debug prefill from `local.properties` working.

## 11. Remove the dark flash at cold start

**Current.** `themes.xml:3` extends the platform dark theme with no DayNight variant, so
a light-mode start flashes dark. `MainActivity.kt:207-211` then renders nothing during
the auth check. The SplashScreen API is absent.

**Desired.** One calm start in both themes, with no blank frame.

**Approach.** Adopt `core-splashscreen`. Show the brand background and the launcher
glyph, and hold it with `setKeepOnScreenCondition` until the auth check ends. Give the
launch theme a DayNight window background.

## 12. Give a podcast page a primary play action

**Current.** `ItemDetailScreen.kt:230-255` gates the hero play row on
`mediaType == BOOK`. A podcast page offers no primary play action, so the listener must
scroll into the episode list.

**Desired.** A podcast page plays with one tap, and the button says what it will play.

**Approach.** Continue the episode already in progress. If there is none, play the newest
unfinished episode. Label the button with the target, for example "Continue: <episode
title>" truncated, or "Play latest episode". Reuse the play wiring of the episode rows.

## Verification

- Build and unit tests: `./gradlew assembleDebug` and `./gradlew testDebugUnitTest`.
  Run these on the merge, not inside every parallel worker. Several Gradle daemons at
  once exhaust the memory of a development machine.
- Screenshot baselines: several of these items change what a screen looks like, so
  baselines need a re-record. **Record them on Linux, never on a Mac.** See the
  screenshot section of [AGENTS.md](../AGENTS.md); a baseline recorded on macOS fails
  verification in CI even when the screen is correct.
- Items 4, 5 and 11 need a real device or emulator to judge. A landscape layout, a
  drawn glyph and a launch animation cannot be proven by reading code.
