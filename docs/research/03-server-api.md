# Research: Audiobookshelf Server API — Capabilities, Gaps, Upstream Climate

*Compiled 2026-08-14. Current stable server: v2.36.0 (2026-07-27). 13.9k stars, 1,129 open issues, 162 open PRs / 1,096 merged.*

## Playback & progress

```
POST /api/items/:id/play[/:episodeId]       → creates PlaybackSession (server picks direct-play vs HLS transcode from client-declared codecs)
POST /api/session/:id/sync                  → periodic currentTime/timeListened sync
POST /api/session/local                     → upload one offline session (client-generated UUIDv4 id)
POST /api/session/local-all                 → batch offline session upload
GET/PATCH /api/me/progress/:itemId[/:episodeId]
POST /api/me/progress/batch
GET  /api/me/progress                       (v2.36.0)
```

- **No server-side conflict resolution — last write wins.** Confirmed race: official app pushes stale local progress over newer server progress on cold start (app #1022, #1059, #1161, #1182). Maintainer confirms cross-device lag is "inherent to the design" (discussion #5033); #3213 (Spotify-style continuous sync) open since v2.11 with no commitment.
- Client rule: **pull and compare before pushing** progress after app open/resume.
- MediaProgress is per-user-per-item; shared accounts collide (#4837).

## Queue

**No server-side queue/up-next concept at all.** Playlists exist (`/api/playlists`, per-user, ordered) but auto-advance is purely client-side. Open requests: server #2214, #3629, #4007, discussions #2035/#3645; app #416/#1164. ShelfPlayer (iOS) built queue + Up Next entirely client-side — the proven pattern. Playlist API can serve as coarse cross-device queue persistence.

## Discovery

```
GET /api/libraries/:id/personalized   → shelves (continue-listening, recently-added, ...)
GET /api/libraries/:id/search         → metadata substring match only
GET /api/libraries/:id/items?filter=&sort=&limit=&page=&minified=&include=
GET /api/libraries/:id/series ; /api/series/:id ; /api/authors/:id
GET /api/collections ; /api/playlists
```

- **No full-text/fuzzy search** (server #2544 labeled backlog / "not planned"). No recommendations beyond rule-based shelves.
- Pagination: `limit=0` returns ALL rows (footgun); `filter` is base64 `group.value`; three payload shapes (minified/full/expanded).
- No general API rate limiting (only login throttling: 10 attempts / 10 min).

## Downloads / offline

- `GET /api/items/:id/download` (zip if multi-file; needs `?token=` since it's a bare GET — with short-lived JWTs prefer own HTTP stack with auth header).
- `POST /api/tools/item/:id/encode-m4b` — server-side ffmpeg merge to single m4b (re-encodes, slow — #4169 requests stream-copy join); progress via websocket.
- Covers: `GET /api/items/:id/cover?width=` (clamped ≤4096px, disk-cached).

## Streaming

- Direct play = static file + HTTP byte-range → byte-precise seeking. **Prefer this always.**
- Transcode = HLS, `hls_time 6`, mpegts segments → ~6s seek granularity. Only fallback for undecodable codecs. Robust fallback-to-transcode needed (#1872, #2720: "standard-looking" files sometimes fail direct play).

## Auth (v2.26+ JWT model — mandatory)

- Access token ~1h; refresh token 30 days (v2.36.0); rotation grace period 10 min (configurable `REFRESH_TOKEN_GRACE_PERIOD`).
- Sessions table: `GET/DELETE /api/me/sessions[/:id]`, `POST /logout?allDevices=1`.
- Refresh tokens rejected for websocket auth (v2.36.0) — sockets need access tokens.
- Legacy permanent tokens removed — client must implement full JWT + refresh flow.
- **OIDC** fully supported (discovery, Authelia/Keycloak/Authentik/Pocket-ID/Google), auto-registration, group→permission claim mapping. Known rough edge: "No refresh token available" errors forcing server re-add.
- API keys: admin-generated, act-on-behalf-of-user, for automation not client auth.

## Websocket

Socket.IO; connect → emit `auth` with access token; re-auth on every reconnect. Events include `item_updated`, `item_removed` (+libraryId), `author_added`, progress/session updates, scan progress. **No queue/now-playing cross-device events.** Reference: advplyr/abs-socket-client-demo.

## Server-side vs client-side state

Server stores: MediaProgress, bookmarks, sessions, permissions, playlists, collections.
**NOT server-side: per-book playback speed** (#3485, #911, #1980 all open) — must persist locally. No `/api/me/settings` for client prefs.

## Data-quality warnings

- **Chapters unreliable**: sorted by internal ID not timestamp (#3007, #4603); multi-file m4b books lose chapters after volume 1 (#3083); embed tool writes them wrong (#676). → Always sort client-side by `start`; synthesize chapters when absent (#4225 requests this server-side, do it client-side).
- xHE-AAC and exotic codecs are ongoing bug areas (discussion #4258).

## Upstream contribution climate

- External PRs merge regularly (multiple contributor PRs merged within 2 weeks of research date: #5429, #5409, #5376, #5370). Small, well-scoped fixes land in days–weeks.
- **No public roadmap** (0 GitHub Projects). Big asks (server queue, per-book speed, full-text search) have multi-year-old open issues with no commitment.
- Repo policy: fully AI-generated PRs "may be closed without comment" — contributions must be genuinely human-reviewed and high quality.
- **Verdict**: no fork needed. Queue/speed/search are all solvable client-side (precedent: ShelfPlayer, Prologue). Contribute small fixes upstream (chapter ordering, sync guards); propose bigger APIs via discussion backed by a working client.

## To verify hands-on (against the live server)

- Definitive socket event catalog: grep `io.emit`/`socket.emit` in server source.
- Exact `POST /items/:id/play` request/response schema (deviceInfo, playMethod fields).
- HLS segment specifics in `server/utils/ffmpegHelpers.js`.
