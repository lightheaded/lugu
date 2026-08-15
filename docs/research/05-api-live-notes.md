# API notes — verified against server source

*Started 2026-08-14 during M0. Server branch: `advplyr/audiobookshelf@master` (v2.36 era).*

**Status: authenticated capture run against a live 2.36.0 server on 2026-08-15** (a
real book library and a podcast library). The item, library, series and playback
shapes below are now confirmed against real responses rather than read out of the server
source. Anything still marked **UNVERIFIED** has not had a real request/response pair.

The capture corrected two assumptions M2 had already been built on; both are recorded
under "Item payload" below, because both would have shipped as bugs.

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

## Item payload (captured live, 2.36.0)

```
GET /api/items/:id?expanded=1
  media: {id, coverPath, duration, size, numAudioFiles, numChapters, numTracks,
          metadata, chapters[], audioFiles[], tracks[], tags[]}
  media.tracks[]:      {index, startOffset, duration, contentUrl, mimeType, codec, ino, …}
  media.audioFiles[]:  {index, ino, duration, mimeType, codec, exclude, bitRate, …}
```

Two corrections to what lugu assumed while M2 was being written:

1. **`media.tracks` is the playable timeline; `media.audioFiles` is not.** The server has
   already computed `startOffset` on `tracks`, and has already dropped files flagged
   `exclude`. Building a download manifest from `audioFiles` means re-deriving offsets by
   hand *and* risking a file the server would never play — which would surface as a
   stretch of wrong audio partway through a book. `ManifestBuilder` now prefers `tracks`
   and keeps `audioFiles` only as a fallback.
2. **`contentUrl` is confirmed as `/api/items/:id/file/:ino`**, relative to the server
   root, on both book tracks and podcast episode tracks. This was the shape the whole
   offline manifest rests on.

Also observed:

- `audioFiles[].index` is 1-based on books and **null** on podcast episode files.
- A podcast episode carries its own single `audioTrack` with a `contentUrl`, so an
  episode download does not need the `ino` path at all.
- The minified list payload has no `tracks`, `audioFiles` or `chapters` — only
  `numAudioFiles`/`numChapters` counts. Downloading therefore needs the expanded fetch.

## Series metadata (captured live, 2.36.0)

`metadata.series` came back **empty on every item** in the list payload; the only series
information an item carries is `metadata.seriesName`, a single string of the form
`"Example Series #10"`. `GET /api/libraries/:id/series` lists the series themselves,
but the per-item sequence still only exists inside that string.

Measured across the library: about a third of items have a `seriesName`, and **roughly
two-thirds of those carry a parseable `#N`**. The rest are genuine series with no
volume number, or metadata noise ("Unabridged").

Two consequences, both now in the code:

- The sequence has to be parsed out of the name (`core/model/Series.kt`) and stored as a
  number. A long-running series here holds "#19", "#21" and "#29" alongside a "#10", so
  ordering a series by its name string would put #10 before #2 and
  recommend the wrong book — not a hypothetical.
- Items whose sequence will not parse are left out of "next in series" rather than
  guessed at. That is roughly a third of series items here, and the alternative is
  confidently recommending a volume out of order.

## Still to capture live

1. Socket.IO event catalogue — grep `io.emit` / `socket.emit` in the server source and
   confirm against a real connection. M0 uses polling and a stale-sweep instead, so
   deletions and edits made elsewhere take until the next sync to appear.
2. A real `/play` response for a multi-file book and for a podcast episode, saved as a
   test fixture in the repo (no server address or ids from the live instance).
3. HLS specifics from `server/utils/ffmpegHelpers.js` and an actual transcode session.
4. Behaviour of `PATCH /api/me/progress` when `lastUpdate` is older than the server's —
   the plan proposes a server-side guard for this upstream; worth measuring first.
