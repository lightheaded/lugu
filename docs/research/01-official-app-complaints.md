# Research: Official Audiobookshelf Android App — Community Complaints

*Compiled 2026-08-14 from GitHub (advplyr/audiobookshelf-app + advplyr/audiobookshelf), Hacker News, Google Play. Reddit was not directly indexable in this pass; the HN thread (https://news.ycombinator.com/item?id=43933248) served as a comparable community-quote source.*

## Architecture context

The official app is **NuxtJS/Vue + Capacitor** (webview UI) with a native Kotlin layer for API/sync and ExoPlayer via `PlayerNotificationService.kt` for playback — a hybrid, not pure webview, but the data layer lives in the webview. No public maintainer statement about a native mobile rewrite was found (only a server web-client rewrite, unrelated). Tagged releases stuck at v0.14.0-beta since Aug 2024 despite daily commits; still "beta" on Play; iOS is TestFlight-only and capped at Apple's 10k-tester limit.

## 1. Android Auto — one of the most-reported categories (2022–2025)

- **#1491** — streaming content unavailable in Auto unless the phone app is already open/foregrounded; only downloads show otherwise; progress never syncs back from Auto sessions. Mirrored as server issue #4021.
- **#1081** — blank screen in Auto; workaround: open the phone app *before* connecting.
- **#1309** — "Start music automatically" resume works "less than 1/5 times"; reporter switched to Smart Audiobook Player.
- **#1059 / #1698** — switching books via Auto misattributes progress between books.
- **#1496** — regression removed Continue/Currently-Listening tabs in Auto.
- **#385** — Auto streams over cellular even when a local download exists.
- **#1406** — chapter-level controls in Auto: closed **as not planned**.
- **#1040** — random Auto crashes every ~20 min.
- **#612** — no pause on Bluetooth disconnect from car → silently keeps playing, loses place.
- **#333** — no playback speed control in Auto.

Maintainer responses essentially absent; #1406 closed "not planned" suggests limited appetite for deeper Auto UX.

## 2. Offline / downloads

- **#1167** — large books (43h, 2.45GB) hang at "Downloading... Processing"; download vanishes on app switch. Bulk downloads (30+) stall after ~100 files.
- **#1553** — crash on download button for certain books.
- **#828** — progress on downloaded books lost when reconnecting to server.
- **#290** — position not remembered for local downloads (long-standing).
- **#613** — no auto-download of latest N podcast episodes (all manual).
- **#1361** — downloaded podcasts marked completed when not / wrong start position.
- **#773** — crashes on locally-saved audiobooks (storage permissions lost).
- Discussion **#864** (6-week power-user report): no default download location, crashes force restart + reselect.
- HN pattern: multiple users use ABS only to download, then play files in **Smart Audiobook Player** because in-app local playback is unreliable / drains battery.

## 3. Playback state restoration

- **#1800** (Feb 2026, v0.11.0) — media notification vanishes ~2 min after pausing in background; a **regression** ("used to stay for hours"); compared unfavorably to Spotify.
- **#41** — loses position randomly (one of the oldest issues).
- **#578** — resume jumps to wrong time.
- **#804** — pause point vs resume point randomly differs (forward or backward).
- **#1298** — position not syncing/saving.
- HN: progress "very frequently off after a pause" — drift of 30–60 minutes reported.
- After a crash the app returns to the main menu, not the playing book.

Chronic category: spans issue #41 → #1800 (Feb 2026); never fully resolved, periodically regresses.

## 4. Rewind/skip inaccuracy from Bluetooth/headset

- **#1147 / #1048 / #622** — pause from certain BT headsets interpreted as pause → rewind → play (device-specific: broken on Edifier WH700NB, fine on Shokz/JBL). Root cause: headset firmware sends AVRCP pause/play in quick succession; app's auto-rewind-on-pause logic misfires.
- **#230** — audio skips back 5s on notification.
- **#1055** — auto-rewind only triggers after 10+ second pause; configurable but interacts badly with hardware.
- **#578** — seek-after-resume inaccuracy compounds across pause/resume cycles.

## 5. Queue / continuation — top-requested gap

- **#416** (Oct 2022) — queue audiobooks/podcast episodes; **highest-reaction open issue**. PRs #1923/#1880 open, unmerged.
- **#1164** — podcast "Add to Queue / Play Next" — open.
- Server **#2214** — "Play Next in Queue" — open.
- Server **#4007** + Discussion #2035 — autoplay next book in series — open, no commitment.
- PR **#1810** (merged) — auto-play next episode **only for podcast playlists**; doesn't extend to books/series.
- **#1778** — playlists fail to display in mobile app at all.
- Third-party clients (Absorb) market series auto-continue as a differentiator.

## 6. Discoverability / library UX

- Server **#4619** — global search & unified display across libraries — requested.
- Server **#5000** — search doesn't cover audiobook chapter titles.
- Search is metadata substring match only; no fuzzy/full-text (server #2544, labeled backlog/"not planned").
- No recommendations / "what next" feature exists anywhere in the ecosystem.

## 7. Everything else

- **Sleep timer**: #780 (resets every track on Chromecast), #545 (auto timer broken), #859 (shake-to-reset insensitive), server #2835 (timer loses track of time when casting), #1200 (temporarily disable auto timer).
- **Chromecast**: not supported in the mobile app at all (old #11 closed unimplemented); web-only, and buggy with sleep timer.
- **WearOS**: #676 open since 2023, no plans; third-party ShelfTime fills the gap.
- **Widgets**: none.
- **Per-book playback speed**: not persisted (server #1980, #2633); #792 playback bugs at >1x on downloads; #1148 bookmark timestamps wrong at speed ≠ 1x.
- **Battery drain**: reported on some devices (HN), attributed to the hybrid architecture.
- **Podcasts**: server #5110 (chapters lost on re-download), #3359/#3246 (episodes fail to download), #1263 (no direct-from-RSS streaming), #613 (no auto-download).
- **Cross-device sync lag**: must back out and re-enter a book to force refresh; server discussion #5033 confirms delay is "inherent to the design"; app issues #1022/#1059/#1161/#1182 show local stale progress overwriting newer server progress on cold-start races.

## Signal

The proliferation of third-party clients (Lissen, Absorb, ShelfTime, Plappa, ShelfPlayer, Prologue...) is itself evidence that the official app's gaps — queue, series continuation, per-book speed, state restoration, Auto reliability, WearOS, widgets — drive users away while they keep ABS as the server.
