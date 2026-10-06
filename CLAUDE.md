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

## Commit messages

Follow the pattern in `git log`: `type(scope): what changed and why`.

## Secrets

Never commit server addresses, credentials, tokens, or captures with real ids. Dev
credentials go in the gitignored `local.properties`. See `CONTRIBUTING.md`.
