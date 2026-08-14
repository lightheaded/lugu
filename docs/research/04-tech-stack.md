# Research: Tech Stack for a World-Class Audiobook Player

*Compiled 2026-08-14. Requirements: rock-solid background audio + state restoration, exact seek in 10–40h files, Android Auto, offline downloads, Chromecast, audio processing (silence skip, speed, boost).*

## Verdict

**Native Kotlin + Jetpack Compose + Media3/ExoPlayer (MediaLibraryService), structured KMP-ready** is the only stack with zero asterisks on all six hard requirements. Flutter and RN both have concrete, sourced disqualifiers for "world-class."

## A. Native Android (Kotlin + Compose + Media3) — winner

Proof by existing apps:
- **Voice** (PaulWoitaschek/Voice, GPLv3) — near-exact checklist match: Kotlin, Compose, Media3, MediaLibraryService Auto, shake-to-extend sleep timer, skip-silence, per-book speed, volume boost, auto-rewind, m4b chapters. **Best open-source reference implementation.**
- **Lissen** — Kotlin/Compose/Media3, actively maintained ABS client.
- **AntennaPod** — migrating to Media3; its Auto edge-case issues (#8267, #7410) are a useful pitfall list.

Per requirement:
1. **Background/restoration**: MediaLibraryService foreground service; `MediaSession.Callback.onPlaybackResumption()` restores item+position from headset play after process death and even reboot. Pitfalls to test per Media3 version: #493 (KEY_EVENT_PLAY_PAUSE), #1249 (lifecycle timing), #1933 (STATE_ENDED), #1070 (regression). Media3 1.9.0 (Dec 2025) enabled wake lock by default + `StuckPlayerException` stall recovery.
2. **Exact seek + chapters**: **Media3 1.11.0 (Aug 11, 2026!) natively extracts QuickTime/Nero chapter atoms from m4b/m4a** as `Chapter` metadata — no hand-parsing. `SeekParameters.EXACT` gives frame-accurate seek on progressive MP4 (cheap for audio-only). `seekBack()/seekForward()` update reported position optimistically. SeekParameters do NOT help HLS (segment-boundary seeking ~6s) → prefer direct play.
3. **Auto/controls/Wear/widgets**: MediaLibraryService is the first-party Auto path (register both Media3 + legacy intent filters). Live bug to mitigate: #3158 (create/destroy loop when Auto binds while idle). Android 13+ custom notification actions via media-button preferences + custom commands. WearOS via Horologist. Media3 1.10 shipped Material3 Compose player composables.
4. **Downloads**: Media3 DownloadManager + DownloadService + WorkManagerScheduler.
5. **Cast**: media3-cast `CastPlayer` rewritten in 1.9.0 for automatic local↔remote transitions; custom `MediaItemConverter` for audiobook metadata.
6. **Processing**: stock `SilenceSkippingAudioProcessor` + `SonicAudioProcessor` (speed w/o pitch shift); shake-to-extend is app-level SensorManager code (see Voice).

**AVRCP caveat (any stack)**: headset-firmware-level quirks (stale positions, dropped mappings) require a physical BT device test matrix. Media3 1.11.0 still patching this surface (AVRCP browsing fix for API 36–37).

**HLS position caveat (any stack)**: HLS seeks snap to segment starts; reported position can start seconds in. Direct-play the original file whenever the device can decode it.

## B. KMP + Compose Multiplatform — the "cheap iOS later" architecture

- KMP stable since 2023; CMP for iOS stable May 2025; production-ready consensus in 2026.
- **Campfire** (r0adkll/Campfire) — ABS client in KMP/CMP (Circuit, Ktor, SQLDelight, Store5) — direct precedent, though WIP.
- Pattern: expect/actual at the *player* boundary — Media3 100% native on Android, AVPlayer later on iOS. Shared: networking, sync, DB, domain. iOS incremental cost = AVPlayer wrapper + MPNowPlayingInfoCenter/MPRemoteCommandCenter + CarPlay. Bounded scope, not a second app.

## C. Flutter — disqualified for world-class

- `audio_service` still rides **deprecated MediaBrowserServiceCompat/MediaSessionCompat**, not MediaLibraryService — misses all ongoing Media3 1.9–1.11 fixes (migration acknowledged as an unstarted "small overhaul").
- `just_audio` **does not expose ExoPlayer's exact-seek/index-seeking** (issue #650: "prioritizes seek speed over accuracy") — precisely the headset skip-back failure mode we must avoid.
- Patching both = doing native Media3 engineering under a Dart facade.

## D. React Native — weakest

`react-native-track-player` v5 rewrote onto Media3 but **shipped alpha without Android Auto** (added in later point releases); short Auto track record; every Media3 capability gated on a small OSS team's bridge updates; API 35+ floor; notification inconsistencies on Android 15–16.

## Recommendation

Kotlin + Compose + Media3, modularized so domain/data layers are pure-Kotlin (Ktor, kotlinx.serialization, Room-KMP-compatible or SQLDelight) with the player fully native. Do not add iOS targets until wanted; do not choose Flutter/RN as an iOS shortcut — the audio-fidelity and Auto gaps cost more than a second native UI would.

To verify hands-on: Pocket Casts' current Media3 status; Campfire's actual feature depth; Media3 1.11 chapter extraction against real m4b files from the library.
