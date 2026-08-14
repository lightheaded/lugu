# API notes — verified against server source

*Started 2026-08-14 during M0. Server branch: `advplyr/audiobookshelf@master` (v2.36 era).*

**Status: endpoint paths capture-verified against a live 2.36.0 server; payload shapes
still source-verified.** The shapes below were read out of the server's own code, and
each entry names the file it came from. Unauthenticated endpoints have since been
confirmed live (below). Anything marked **UNVERIFIED** still needs a real
request/response pair — that is the remaining part of Phase 2 task 1 in
[../EXECUTION-PLAN.md](../EXECUTION-PLAN.md), and it needs credentials in
`local.properties` to finish.

## Live capture — unauthenticated (server 2.36.0)

```
GET /status  → 200
{"app":"audiobookshelf","serverVersion":"2.36.0","isInit":true,
 "language":"en-us","authMethods":["local"],
 "authFormData":{"authLoginCustomMessage":""}}

POST /login         (empty body) → 400   endpoint exists at the server root
POST /auth/refresh  (no token)   → 401   endpoint exists at the server root
```

Confirms the three auth paths lugu uses. The `app` field is the identity check the
login probe now relies on: parsing alone is not identity, because every field of
`ServerStatusDto` has a default and any JSON would satisfy it.

`authMethods: ["local"]` means username/password is the only method on this server —
OIDC is deferred to M4 anyway.

## Auth

`server/Auth.js`

```
POST /login                       body: {username, password}
     header x-return-tokens: true → refresh token comes back in the body
                                    instead of an httpOnly cookie
     → {user: {id, username, accessToken, refreshToken, mediaProgress: [...]},
        userDefaultLibraryId, serverSettings, ...}

POST /auth/refresh                header x-refresh-token: <token>
     → same user-shaped response with a rotated pair
```

Notes:

- Without `x-return-tokens`, the refresh token is set as a cookie — unusable for a
  native client, so lugu always sends the header.
- The refresh endpoint prefers `x-refresh-token` over the cookie, and sending the header
  is also what makes it return the rotated refresh token in the body.
- `user.mediaProgress` on the login response carries the whole progress table. lugu
  seeds Room from it, so continue-listening renders before any further request.
- **UNVERIFIED**: whether the rotation grace period (`REFRESH_TOKEN_GRACE_PERIOD`,
  default 10 min) lets a retried refresh reuse the previous token. lugu assumes not and
  stores whatever it is handed.

## Playback session

`server/managers/PlaybackSessionManager.js`, `server/objects/files/AudioTrack.js`

```
POST /api/items/:id/play[/:episodeId]
     body: {deviceInfo: {deviceId, clientName, clientVersion, manufacturer, model,
                         sdkVersion},
            supportedMimeTypes: [...], mediaPlayer, forceDirectPlay, forceTranscode}
     → PlaybackSession: {id, libraryItemId, episodeId, displayTitle, displayAuthor,
                         coverPath, duration, playMethod, startTime, currentTime,
                         chapters: [{id, start, end, title}],
                         audioTracks: [{index, startOffset, duration, title,
                                        contentUrl, mimeType, codec, metadata}]}
```

- `contentUrl` for direct play is `/api/items/${itemId}/file/${audioFile.ino}` —
  confirmed in `AudioTrack.js`. Relative to the server root, so it needs joining with
  the base URL.
- `playMethod`: 0 direct play, 1 direct stream, 2 transcode, 3 local. Direct play is
  selected by `libraryItem.media.checkCanDirectPlay(...)` against `supportedMimeTypes`.
- **UNVERIFIED**: the HLS `contentUrl` shape when the server falls back to transcoding,
  and whether the playlist URL needs the same bearer header as the file endpoint.

## Progress and sessions

`server/managers/PlaybackSessionManager.js`

```
POST /api/session/:id/sync        body: {currentTime, timeListened, duration}
POST /api/session/local-all       body: {deviceInfo: {...}, sessions: [LocalSession]}
                                  → {results: [{id, success, error?}]}
GET/PATCH /api/me/progress/:itemId[/:episodeId]
GET  /api/me                      → user incl. the full mediaProgress array
```

- `syncSession` reads exactly `currentTime`, `timeListened` and `duration` from the body;
  `duration` has been optional since v2.15.1 but is used when present.
- The batch endpoint is an **object with a `sessions` key**, not a bare array — worth
  stating because the singular `/api/session/local` takes the session object directly.
- Sessions are keyed on the client-generated id, which is what makes replaying a
  half-failed offline upload safe.
- **UNVERIFIED**: whether a `local-all` upload moves `MediaProgress` forward on its own,
  or whether the client must also PATCH progress. lugu currently does both, which is
  harmless but possibly redundant.

## Still to capture live

1. Socket.IO event catalogue — grep `io.emit` / `socket.emit` in the server source and
   confirm against a real connection. M0 uses polling and a stale-sweep instead, so
   deletions and edits made elsewhere take until the next sync to appear.
2. A real `/play` response for a multi-file book and for a podcast episode, saved as a
   test fixture in the repo (no server address or ids from the live instance).
3. HLS specifics from `server/utils/ffmpegHelpers.js` and an actual transcode session.
4. Behaviour of `PATCH /api/me/progress` when `lastUpdate` is older than the server's —
   the plan proposes a server-side guard for this upstream; worth measuring first.
