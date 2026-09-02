# Wear OS and Android TV — a spike

*2026-09-03. `EXECUTION-PLAN.md` §Phase 6 lists "WearOS/TV spikes" in M4. A spike answers
what a thing would cost and what blocks it. It does not build the thing, and this document
does not recommend building either of them yet.*

Both are read against the app as it stands: `minSdk 26`, one `:app` module on Compose
Material 3, `:core:*` free of Android framework types, Media3 1.11.0 for playback, and
Room as the source of truth.

---

## Wear OS

### What a listener would get

The one thing a watch adds is **leaving the phone behind**. A walk, a run, a swim in a
waterproof case. Nothing else about a watch improves on a phone for this app: a 45 mm
screen is a worse place to browse a library, and the phone is already in the car.

So the product is narrow, and the narrowness is the good news. It is "carry on with the
book I was listening to, over Bluetooth headphones, with no phone present". That is one
screen and four controls.

### What already carries over

| Layer | Carries over | Why |
| --- | --- | --- |
| `:core:model` | Yes, unchanged | Pure Kotlin, no Android types, by contract |
| `:core:api` | Yes | Ktor on OkHttp runs on Wear |
| `:core:db` | Yes | Room runs on Wear |
| `:core:sync` | Mostly | `androidx.security:security-crypto` uses `AndroidKeyStore`, which Wear has |
| `playback` | Mostly | Media3 runs on Wear, and `MediaLibraryService` is the right shape |
| `:core:ui`, `:feature:*` | **No** | Compose Material 3 is not Wear Compose. Every screen is new |

The module layout is the reason this is a spike rather than a rebuild. Four of the seven
layers move across untouched, and that is what schema v1's `(serverId, userId)` keying and
the "no Android in `:core:*`" rule were for.

### The four things that actually block it

1. **A second UI, in a different toolkit.** Wear Compose is `androidx.wear.compose`, with
   its own `Scaffold`, its own list (`TransformingLazyColumn`), and a curved layout model.
   None of `:core:ui` or `:feature:*` applies. This is the largest single cost, and it is
   not reducible by sharing code — sharing a phone layout onto a watch is what makes Wear
   apps unusable.
2. **`minSdk`.** Wear OS 3 is API 30. The app is `minSdk 26`. A Wear variant needs its own
   `minSdk`, which means a second module rather than a second flavor of `:app`.
3. **Downloads, against a watch's storage.** A watch has a few gigabytes, and an
   audiobook is often more than one. `:core:download` and its storage cap carry over as
   code, but the *rules* do not: "download the next 3 in the series" is wrong on a watch.
   A watch wants one book, or one part of one book, and a rule nobody has designed yet.
4. **How the watch reaches the server.** Two answers, and they are different products.
   Over the watch's own Wi-Fi or LTE, the watch is a second client and everything in
   `:core:sync` applies — including the progress conflict rule, which is now correct
   across clocks and would be exercised properly for the first time. Through the phone's
   Data Layer, the watch is a remote control and none of `:core:sync` is needed on it.
   **The first is more work and the right answer**, because the point was to leave the
   phone behind.

### What it would cost

A rough shape, not an estimate to hold anybody to:

- A `:wear` module, its own manifest, its own `minSdk 30`. Small.
- One player screen and one "carry on" screen in Wear Compose. The bulk of the work.
- A download rule for a watch, and a storage cap that means something at that size.
- An on-watch sign-in. This is worse than it sounds: typing a server address on a watch is
  unreasonable, so it wants either the Data Layer to carry the account across from the
  phone, or the identity-provider flow, which now exists — a watch can open a browser.
- Its own Play listing, because a Wear app ships separately.

### What to check before starting

- Whether Media3's `MediaLibraryService` gets the Wear media controls for free, or whether
  Horologist's media toolkit is needed. Horologist is Apache-2.0, so the licence rule in
  `AGENTS.md` is satisfied either way, but it is another dependency to keep at latest.
- Whether a watch playing to Bluetooth headphones with the screen off keeps the service
  alive on the current Wear OS. This is the same class of question as "why playback stops"
  on a phone, and the answer will be worse on a watch.
- Battery. `docs/BACKLOG.md` already carries battery drain as a standing requirement. A
  watch makes it the deciding requirement.

### Verdict

**Worth doing, and not yet.** It is the only one of the two with a real listener behind it.
It should wait for two things that are already in the backlog: the "why playback stops"
diagnosis, because a watch will hit it harder, and one confirmed device pass on a phone,
because a second platform doubles the cost of every unproven claim.

---

## Android TV

### What a listener would get

Very little, and this is worth saying plainly rather than politely. Nobody sits in front of
a television to listen to an audiobook. The cases that exist are real but thin:

- A TV as the only always-on speaker in a room.
- A podcast while doing something else, on the screen that is already on.

Both are better served by casting from the phone, and casting is exactly what the M4 plan
records as blocked — `media3-cast` depends on a proprietary library, which the project's
GPL-compatible-dependency rule refuses. So "use a TV" and "cast to a TV" are both closed
for now, for different reasons.

### What would be needed

- `android.software.leanback` in the manifest, a TV banner, and a launcher entry.
- A **D-pad-first** browse UI. Not a rescaled phone layout: every list needs explicit
  focus handling, and Compose for TV (`androidx.tv.material3`) is a third toolkit after
  Material 3 and Wear Compose.
- Playback with the screen off, which a TV does not really do — a TV app that keeps
  playing with a black screen is fighting the platform's own idea of what a TV is for.
- Its own Play listing and its own store review, with TV-specific requirements.

### The blocker that is not technical

Three UI toolkits for one app, and the third one serves the fewest people. The phone app
has eleven screenshot baselines failing on one developer machine and four milestone exit
criteria that have never been verified on hardware. Adding a platform before that is
settled makes every one of those problems bigger.

### Verdict

**Do not build it.** Recorded as considered and declined, with the reason, so it does not
come back as an open question. If a room-speaker case ever matters, the thing to look at is
a free-software local-network handoff — the third option in the M4 plan's Chromecast
section — and not an Android TV app.

---

## What this spike changes today

Nothing in the code. Two entries in `docs/BACKLOG.md`, one open and one declined, and this
document as the reason for both.
