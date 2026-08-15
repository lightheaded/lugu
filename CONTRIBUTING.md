# Contributing

lugu is pre-alpha and moving fast, so the most valuable contribution right now is a
good bug report from real listening. The docs are the map: [docs/PLAN.md](docs/PLAN.md)
is where the project is going, [docs/BACKLOG.md](docs/BACKLOG.md) lists everything
knowingly unfinished — check it before reporting a gap as a bug.

## Bugs

Use the bug report template. The reports that get fixed fastest say what you did, what
happened, and what you expected — plus the app version (Settings shows it), your
Android version, and your server version. Positions and playback are the heart of this
app: for anything in that area, the exact sequence of pauses, seeks and reconnects is
the diagnosis.

## Features

Check [docs/PLAN.md](docs/PLAN.md) and [docs/BACKLOG.md](docs/BACKLOG.md) first — a
good deal of what you might ask for is already planned, and the backlog entry says why
it is not done yet. If it is genuinely new, open a feature request and say what you are
trying to do, not just what UI you want.

## Pull requests

Open an issue to discuss before writing anything substantial — the architecture makes
deliberate trade-offs (offline-first, the local database as the source of truth,
pure-Kotlin core modules) and a PR that fights them will not land. For the rest:

- Match the style of the code around you.
- Pure logic goes in `:core:model` or `:core:api` and gets unit tests. The rules that
  carry user promises (progress, chapters, rewind) are all tested; keep it that way.
- `./gradlew build` must pass — it runs the tests and lint that CI runs.
- Commit messages follow the pattern in `git log`: `type(scope): what changed and why`.

By contributing you agree your work is licensed under the project license,
GPL-3.0-or-later — the usual inbound = outbound arrangement, no CLA.

## Never in this repository

No server addresses, no credentials, no tokens, no captures with real ids. Dev
credentials belong in the gitignored `local.properties`; API captures go through
`scripts/capture-api.sh`, which redacts them.
