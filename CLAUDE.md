# CLAUDE.md — lugu

## Architecture

Offline-first Android app. The local Room database is the source of truth. Modules:

- `app` — activity, navigation, instrumented tests
- `core:model` — pure Kotlin data classes and logic, no Android
- `core:ui` — shared Compose components (`StatusStrip`, `ReservedMessage`, etc.)
- `core:api` — Audiobookshelf client
- `core:db` — Room database, DAOs, migrations
- `core:sync` — DataStore preferences, repository, sync logic
- `core:download` — download manager
- `playback` — Media3 playback service
- `feature:library` — Home and Library tabs, grid, shelves
- `feature:player` — player screen
- `feature:settings` — settings screen
- `harness` — separate app that kills lugu and watches what happens

## CI

`./gradlew build` runs unit tests, screenshot tests (Roborazzi), and lint. The
instrumented tests run on API 26 and API 36 emulators, plus a minified leg on API 36.

**`build` does not compile the instrumented sources.** Change a shared constructor or a
public signature and the `androidTest` source sets are consumers too, so add
`./gradlew compileDebugAndroidTestKotlin` — or `assembleDebugAndroidTest` — before you
push. On 3 September 2026 a green `build` went red on all three emulator legs for one
missing constructor argument in a test helper, and the same change had a second fault
underneath it that would have compiled: the helper planted a token for whichever account
was active, and the per-account token store had just made that the wrong one.

The instrumented job does NOT gate the release (emulator flakiness), but a failure
turns the run red and must be investigated.

A red instrumented job is a code bug until proved otherwise. Do not disable, skip,
or weaken a failing test — find what the test caught and fix the code. If the same
test fails across multiple API levels, the cause is in the app, not in the emulator.
See `docs/qa/instrumented.md §Reading a failure in CI` for the one exception: a red
job with no failing test means the emulator hung on shutdown, not a test failure.

Several of these tests are racy, so a re-run of the **same commit** can come back
green. That tells you the failure is not your change. It does not tell you the app is
sound — a defect that appears on some runs reaches a listener the same way. Read the
report, then re-run the same commit to learn which of the two you are looking at, and
record what you found in `docs/BACKLOG.md`. Never re-run to make a red job go away.

See `docs/qa/instrumented.md` for the full picture.

## Delivering a change

"Open the PR, merge and deliver" means this procedure. Run it on your own when a change is
done and its checks pass. Do not stop at a pushed branch to ask. It ends when the maintainer
confirms the new APK from Obtainium, the release is promoted, and the checkout is back on `main`.

Each build on `main` is published as a **prerelease**. Only an install with "Include
prereleases" on in Obtainium gets it, and the maintainer's install has it on. When the
maintainer confirms the build, `promote-release.yml` makes the same release the Latest one.
Then Obtainium's default setting and the stable link reach it. Obtainium reads the version
from the tag.

**The merge is a fast-forward push, never a GitHub merge.** History on `main` is linear,
and the maintainer signs every commit. The merge, squash and rebase buttons and `gh pr merge` make
commits that the maintainer did not sign. Never use them. Never force-push `main`. Never use `--no-verify`.

1. Run `./gradlew build` and `./gradlew compileDebugAndroidTestKotlin`. Both must pass.
2. If only a Roborazzi test fails on a macOS host, let CI decide. Never record baselines
   locally. Use `record-baselines.yml` (see `docs/qa/screenshots.md`).
3. Make each commit subject and body fit for a release note. The release job builds its
   notes from them (`AGENTS.md` → Releases).
4. Push the branch and open the PR. Use the `type(scope): ...` title style.
   End the body with the attribution line of the session.
5. Wait for the `build` check. If it is red, fix the code and push again.
6. Fetch `origin`. If `origin/main` is not an ancestor of the branch, rebase with signing on.
7. Make sure that each commit in `origin/main..HEAD` shows `G`.
8. Push the branch to `main`. GitHub then marks the PR as merged.
9. Watch the run on `main`. If `instrumented` is red, investigate it (see CI). It does not stop the release.
10. Make sure that a new prerelease has the tag `v<versionName>` and the asset `lugu-latest.apk`.
11. Give the maintainer the tag, and ask them to test the build. On the phone, Obtainium shows
    that version after "check for updates". After the install, Settings → About → Version
    shows `<versionName> (<versionCode>)`. The name must equal the tag without the `v`.
12. When the maintainer confirms the build, run `promote-release.yml` for that tag. Make sure
    that the release is no longer a prerelease and that it is the Latest release.
    If the maintainer finds a fault, do not promote. Fix it, and deliver the fix as a new prerelease.
13. Clean up. Delete the branch on `origin`.
14. If the work ran in a worktree, remove the worktree and its local branch.
15. In the main checkout, switch to `main` and fast-forward it to `origin/main`.

```sh
gh pr create -R lightheaded/lugu --base main --head <branch>
gh pr checks <n> -R lightheaded/lugu --watch
git fetch origin && git merge-base --is-ancestor origin/main HEAD || git -c commit.gpgsign=true rebase origin/main
git log --format='%h %G? %s' origin/main..HEAD
git push origin HEAD:main
gh run list -R lightheaded/lugu --branch main -L 1
gh run watch <id> -R lightheaded/lugu --exit-status
gh release list -R lightheaded/lugu -L 3
gh release view v<versionName> -R lightheaded/lugu --json tagName,isPrerelease,assets
gh workflow run promote-release.yml -R lightheaded/lugu -f tag=v<versionName>   # after the maintainer confirms
gh release view -R lightheaded/lugu --json tagName,isPrerelease                  # Latest is now that tag
git push origin --delete <branch>
git worktree remove <worktree path> && git branch -D <branch>   # from the main checkout
git switch main && git pull --ff-only
```

`versionName` is `<versionBase>.<run number>`, for example `0.2.0-alpha01.98`. The stable
link is `https://github.com/lightheaded/lugu/releases/latest/download/lugu-latest.apk`. It
points at the newest promoted release, never at a prerelease.

Promotion builds nothing: the APK that was tested is the APK that ships. It rewrites the
notes so that they start at the previous release. Thus somebody who skips the prereleases
still reads every change they get. Promotion with no tag takes the newest prerelease.

`ci.yml` cancels a running run on the same ref. Thus a second push to `main` during a run
cancels the first, and the first gets no release. If the signing keystore secret is missing,
the release job passes but publishes nothing. In both cases, step 10 finds no new tag.

## Where the maintainer's rules are written

**Write each rule that the maintainer gives about how to work into this file or into
`AGENTS.md`, and commit it with the work.** An agent's own memory can keep a copy too, but
that copy is not enough by itself: the repository is what the next session reads.

## Sorts and filters go into every list

**A new sort or filter option goes into every list that has list controls.** If one of the
lists cannot have it, the change says why. On 6 October 2026, "Recently played" reached the
Library grid only. The episode lists read `EpisodeSort`, not `ItemSort`, and Downloads keeps
its own list, so the maintainer found the gap on the phone.

Before you call the change done, search for `ListControlsBar(`, `SortOption(` and
`ListFilter.entries`, and check each result. Today there are four lists:

| List | Sorts | Filters |
|---|---|---|
| Library grid (`LibraryScreen.kt`) | `ItemSort` | `ListFilter.entries` |
| One podcast's episodes (`ItemDetailScreen.kt`) | `EpisodeSort` | `ListFilter.entries` |
| Episodes view (`AllEpisodesScreen.kt`) | `EpisodeSort` | `ListFilter.entries` |
| Downloads (`DownloadsScreen.kt`) | `DOWNLOAD_SORTS` | `DOWNLOAD_FILTERS` |

Each list remembers its own sort and filter in `LibraryPrefs`. A new list gets its own keys.

## Play never waits on the network for a downloaded item

**A downloaded book or episode starts from Room at once. Nothing on the play path waits
for the server** (maintainer, 19 November 2026: "it should sync the book before we start,
so we never wait"). Bring the data up to date *before* the press — `RealtimeSync.catchUp`
runs when the app comes on screen and when the socket connects. Anything that must still
ask the server runs after the start, as `checkPositionLate` does, and must keep
pull-before-push safe: hold the item's pushes until the answer is in.

## A session that ended never blocks the downloads

**When the sign-in lapses, the library still opens, downloaded items still play, and the
sign-in is offered and can be dismissed** (maintainer, 25 November 2026: "when the session
is expired, I should still be able to listen to offline files"). A lapsed account keeps its
row, its mirror and its downloads, so:

- The start destination is Home whenever an account is active, tokens or not
  (`AuthRepository.hasActiveAccount`). Only a device with no account starts on sign-in.
- The sign-in screen is pushed on top of the screen in use, once per lapse for each launch
  of the activity (a rotation does not push it again). "Not now" and Back both return to
  the screen under it. Only the sign-in that the lapse pushed has "Not now" (a nav argument
  marks it), also when it was pushed over Accounts. A sign-in somebody opened from the
  Accounts screen has none, and Back returns to Accounts.
- Nothing on the play path of a downloaded item may need a token. A send that a lapse
  blocks stays in the outbox, is not counted as an attempt, and goes out after the next
  sign-in.
- A screen that cannot sync, or an action that fails, says that the session ended and
  that the downloads still play. It never blocks the screen. Only a real lapse
  (`AuthExpiredException.Reason.ENDED`) says this, never a refresh race or no account.
- **Newer listening wins** (maintainer, 25 November 2026). When a sweep meets a position
  that is not sent yet and a server copy from another device, the side written later
  wins. A newer phone position, a finished mark included, stays and goes to the server. An
  older one is replaced and goes into the history as "From another device". The two clocks
  differ, so the phone wins only when it is newer by more than
  `ProgressConflictResolver.NEWER_LISTENING_MARGIN_MS`. The rule is
  `ProgressConflictResolver.sweepVerdict`. The check at a session start still decides by
  origin, and the sweep records the copy it judged so that the two agree.

## Compose overlay rule

**A clickable overlay must not cover fixed interactive controls.** `StatusStrip` is
clickable when it shows an error or a note. It intercepts every touch under it.

On screens with fixed controls at the top (chips, browse links, filter bars), place
the `StatusStrip` inside a `Box` that wraps only the scrollable content — below the
fixed controls in the `Column`. On screens with no fixed top controls, the overlay
can cover the full content area.

This rule broke CI for four days (20–24 August 2026) when the strip sat over the
library picker chips. `LibraryGridTest` guards this: it taps a library picker chip,
which fails when the strip covers the chips. See `StatusStrip.kt`'s doc comment for
the full placement contract.

## The UX target

**The target is world-class UX: clear, modern, snappy and logical** (maintainer,
19 November 2026). Judge a change to a screen against those four words before you call it
done. `docs/UX-AUDIT.md` holds the findings and their status, and `docs/FEEDBACK.md` holds
the design laws. The checkup of 19 November added these working rules:

- **Every destructive action offers Undo, on every path to it.** A download removed on the
  podcast page had Undo, and the same tick on the episode page, in the Episodes view and on
  Downloads did not. When you add Undo to an action, search for every caller of that action.
- **An Undo must not overwrite newer work.** Give the undo the state it expects to find, and
  do nothing when the state moved on. `ProgressRepository.restore` is the pattern.
- **No screen says "empty" before the database answered.** A state starts with
  `loaded = false`, and a `stateIn` start value never counts as an answer.
- **A text field reads its text from a flow that the field itself feeds.** State that is
  built on a background dispatcher arrives late, and a field that reads it loses keys.
- **List work runs off the main thread.** Sort and filter in a `combine` with
  `flowOn(Dispatchers.Default)`. Playback writes progress every few seconds, and each write
  shapes the list again.
- **`PlayerUiState.isPlaying` is the intent to play, and `isAudible` is sound.** The button
  follows intent, so a stream that stalls still shows Pause.
- **A change that only reads well is not checked.** No emulator runs on the development
  machine. Say what was not seen on a device, and let the maintainer look at it.

## Commit messages

Follow the pattern in `git log`: `type(scope): what changed and why`.

## Secrets

Never commit server addresses, credentials, tokens, or captures with real ids. Dev
credentials go in the gitignored `local.properties`. See `CONTRIBUTING.md`.
