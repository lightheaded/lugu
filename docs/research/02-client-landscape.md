# Research: Third-Party Audiobookshelf Client Landscape

*Compiled 2026-08-14.*

## Android clients

### Official app (advplyr/audiobookshelf-app)
NuxtJS + Capacitor hybrid, GPL-3.0. Daily commits but no tagged release since v0.14.0-beta (Aug 2024). 404 open issues, 81 open PRs. Gaps: no queue (#416), no Chromecast (#11 closed unimplemented), buggy Android Auto, no F-Droid, laggy cross-device sync, no per-book speed.

### Lissen (GrakovNe/lissen-android)
Native Kotlin + Compose + Media3. MIT, ~720 stars, releases every 2–5 days, Play + F-Droid. Praised: minimalist, clean, OIDC, Android Auto (rough: broken cover art in Auto, unconventional button layout, rewind restarts book — #254), offline downloads. Gaps: no queue (#242 open), no Chromecast, no Android TV (#376), deliberately minimal scope.

### Absorb (pounat/absorb)
Flutter, GPL-3.0, ~600 stars, weekly releases, Play + App Store. Most feature-complete third-party: Android Auto + CarPlay + Chromecast (Android only), 10-band EQ, admin controls, widgets, per-book speed memory, shake-to-reset sleep timer, stats. AI-assisted solo dev (community flags longevity risk). iOS parity lags.

### Buchable (Rdkang/Buchable, fork of Vito0912/abs_flutter)
Flutter. Queue support, fast multi-server switching, no-socket-dependency reconnect design (worth studying), 365-day stats, big-button "car mode" (not OS-level Auto). Fork stalled; original author moving to a successor ("yaabsa"). No Android Auto/Chromecast. Search capped at 25 results (ABS API constraint).

### Storii (likhithpraveenk/storii)
Flutter, GPL-3.0, active. Multi-user/multi-server, OIDC, Material You, series-aware browsing. No Chromecast, no Android Auto, no playlists yet.

### ShelfDroid (100nandoo/shelfdroid)
Native Kotlin, AGPL. Server-admin tools, downloads. No Auto, no cast, no queue.

### Stillshelf (kalzEOS/stillshelf)
Native-ish Android, GPLv3, tiny/sporadic. Also supports Navidrome. Fine-grain speed + audio boost praised. Community explicitly skeptical of AI-assisted weekend projects' longevity: "I'd give this project a year of maintenance before I would reasonably trust it."

### Swiftshelf-AndroidTV
Niche Android TV leanback client — signals unmet TV demand.

## iOS clients (feature inspiration)

- **ShelfPlayer** (Swift 6): deepest Apple integration (CarPlay, widgets, Siri), true multi-server, unified book+podcast **Up Next queue built entirely client-side**. Sold to Space Mushrooms Jul 2026; repo archived; now $5.99. Future uncertain.
- **Plappa**: also Jellyfin/Emby; downloads behind IAP.
- **Still**: minimalist, CarPlay, OIDC; closed source.
- **AudioBooth** (MPL-2.0, 376 stars): Apple Watch support; best-received newcomer.
- **Prologue** (Plex-based): widgets, per-book speed — repeatedly cited as the UX bar.

## Generic-player workaround (the bar for local playback UX)

Users still pair ABS with **Smart Audiobook Player** via Syncthing folder sync (server discussion #315) or WebDAV because SAP's local playback UX beats every ABS client. Open request for direct SAP integration (server #4699). Downsides they accept: no progress sync to server, no streaming, manual curation. **Beating SAP's local-playback feel while keeping ABS sync is the winning combination nobody offers.**

## Consensus & unfilled niches (Aug 2026)

Community framing: "only two fully-functional Android apps" (official + Lissen); Absorb praised once discovered; sentiment fragmented — nobody is the unambiguous best.

1. **No native-Kotlin app combines Android Auto + Chromecast + a real play queue.** Absorb (Flutter) has Auto+Cast but queue unclear; Lissen/ShelfDroid (native) lack cast and queue.
2. **Nobody solves cross-device progress-sync reliability** — needs client-side local-first conflict resolution.
3. **Android TV essentially unaddressed.**
4. **Trustworthy sustained maintenance is scarce** — mostly single-maintainer projects.
5. **True offline-first architecture** (vs download-then-hope) — only partially addressed by Buchable's no-socket design.
