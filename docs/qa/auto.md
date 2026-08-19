# Android Auto manual QA checklist

The M3 exit criterion is "car use with the phone never unlocked". Everything below is a
way of testing that sentence. A car surface is not a small phone screen: the app cannot
draw anything, the driver cannot read a paragraph, and a failure has no visible error —
it is silence, or a list that never appears.

Run against the real server on a real phone. Where the phone matters (locked, dark,
process killed), it says so.

## Setting up the Desktop Head Unit

The DHU is the emulator of the head unit; it runs on the desktop and talks to the phone
over ADB. It is not a substitute for a real car, but it catches every structural failure.

1. Android Studio → SDK Manager → SDK Tools → **Android Auto Desktop Head Unit emulator**
2. On the phone: install Android Auto, then Settings → Apps → Android Auto → Additional
   settings → tap **Version** ten times to unlock developer mode
3. Android Auto → three dots → Developer settings → **Unknown sources** on (a debug build
   of lugu is not signed by the Play Store, so without this it will not be listed)
4. `adb forward tcp:5277 tcp:5277`
5. `$ANDROID_HOME/extras/google/auto/desktop-head-unit` (macOS/Linux)

If lugu never appears in the launcher, the fault is almost always one of three things:
`automotive_app_desc.xml` missing from the manifest, the legacy
`android.media.browse.MediaBrowserService` action missing from the service, or unknown
sources still off.

The first two are answerable from the phone in one command, and answering them first saves
hunting through Android Auto's settings for a fault that is not there:

```sh
adb shell cmd package query-services -a android.media.browse.MediaBrowserService \
  | grep -i lugu
adb shell dumpsys package io.github.lightheaded.lugu | grep -i "versionName\|versionCode"
```

A hit on the first means the installed build is advertising itself correctly and the fault
is on the Android Auto side — unknown sources, or its app list not rescanned since (force
stop Android Auto, or reboot). No hit means the build on the phone predates the car work,
whatever the store page says; check the version against the release that introduced it.
Android Auto shows no error either way, which is why this is worth checking before anything
else.

## The browse tree

**Most of this section is now automated.** `AutoBrowseTreeTest` binds to the playback
service as an ordinary Media3 `MediaBrowser` — which is exactly how Android Auto reaches
it — and asserts the structure below without a car, a head unit or a server. The lines
marked ✅ are checked on every CI run and are here for completeness rather than for doing
by hand. What no browser client can see is what the car *draws*, so the rest stays.

- [ ] lugu appears in the DHU app launcher with its icon and name
- [ ] ✅ The root shows **Continue** and **Libraries** always; **Up next**, **Latest
      episodes**, **Downloaded**, **Series** and **Podcasts** only when they have something
      in them. Full order: Continue, Up next, Latest episodes, Downloaded, Series,
      Podcasts, Libraries
- [ ] ✅ Every category opens, and none of them opens onto an empty list — **except
      Continue**, which is offered unconditionally and is legitimately empty for an account
      that has started nothing. It and Libraries are the way back to everything, so they are
      always there
- [ ] ✅ **Series** lists series, not books; opening one lists its books in reading order
      (`#2` before `#10`, which is the whole reason the sequence is stored separately)
- [ ] ✅ A podcast opens onto its episodes, newest first
- [ ] **A book started from the car plays at its remembered speed.** Set one to 1.5x on the
      phone, force-stop lugu, then start it from the car: a car hands back an id and nothing
      else, so the speed is applied where the session is rebuilt. At 1x, that is this path
- [ ] **Covers appear on rows**, and on the car's now-playing screen. If they do not, the
      rows are still readable and pressable — but blank tiles everywhere is a specific
      failure with a specific cause, not a slow network: the car fetches artwork in its own
      process, so a cover it cannot authenticate for is a cover it never gets. Artwork is
      served as `content://` through `CoverProvider` for exactly this reason. Check with
      `adb shell content read --uri content://io.github.lightheaded.lugu.covers/cover/<itemId>`,
      which should return image bytes rather than an error

## The dashboard's "For you" pane — added 20 August, and only a car can answer it

lugu now serves a second root. A host that sets `EXTRA_SUGGESTED` in its root hints gets a
root of its own, `lugu/suggested`, whose children are exactly what **Continue** holds, in
the same order and playable. Google's design guidance says Android Auto draws those items
in the "For you" pane on its dashboard — the screen the car shows when nothing plays — and
that a host with no answer fills the pane from the top of the browse tree instead. For lugu
that top row is Continue, a category rather than something to play, which is the reported
fault.

**Everything in that paragraph except lugu's own half is read off documentation.** The
mechanism is certain: the constant, the conversion into `LibraryParams.isSuggested` and the
answer back out are all in the Media3 1.11.0 source, and `AutoBrowseTreeTest` asserts both
roots. What is not certain is that the pane Tom is looking at is the pane this fills. No
browser client can see that, because no browser client is the dashboard.

- [ ] ✅ The suggestion hint answers with `lugu/suggested`, and that root holds Continue's
      rows — asserted without a car
- [ ] Start something on the phone so Continue is not empty, then plug in and look at the
      dashboard **before opening lugu**. The "For you" pane must offer that book or episode
      by name, and pressing it must start it where it was left
- [ ] Look at what the pane offered *before* this change went in, if it can still be
      remembered. A pane that read "Continue" and did nothing useful is the fault; a pane
      that already named a book is a sign this is not the surface Tom means
- [ ] With nothing in progress, the pane is empty or absent — never an error, and lugu must
      stay in the launcher afterwards

**What a failure here means.** If the pane is unchanged, the hint was not the way in, and
there are two candidates left, and one command separates them. lugu writes no line when a
root is asked for, so add a temporary log of `params?.isSuggested` in `onGetLibraryRoot`,
drive once, and read `adb logcat`. A root request that never carries the flag means this
head unit or this Android Auto build does not ask for suggestions. A request that carries it
and a pane that stays wrong means the pane is not fed by the app at all — it is Android
Auto's own row, built from system history, and nothing in lugu can change it.

Neither failure costs anything already built. The suggested root is correct whatever draws
it, and it is served only to a host that asks for it, so nothing else can be affected.

## Cold start, which is the real test

The phone will usually be freshly plugged in, the app not running, and the car may be in
a garage with no signal. This is the case that matters.

- [ ] `adb shell am force-stop io.github.lightheaded.lugu`, then open lugu in the DHU:
      the tree fills without the app being opened on the phone
- [ ] Repeat with the phone in airplane mode: the tree is identical, because it is served
      from Room
- [ ] **In airplane mode, a downloaded book still shows its cover.** This is the check that
      says the picture travelled with the audio. Set it up properly or it proves nothing:
      download a book, then clear the app's *cache* (Settings → Apps → lugu → Storage →
      Clear cache, which empties the provider's own cache but not the stored covers), then
      airplane mode, then force-stop, then open lugu in the DHU. The downloaded book keeps
      its cover; a book that was never downloaded shows a blank tile, which is expected and
      is the remaining gap
- [ ] Delete that download and look again: the tile goes blank, because the cover is kept
      only for as long as the download is
- [ ] Repeat with the phone locked and the screen off throughout

## Playing

- [ ] Tapping a book in **Continue** starts it at the right position, not at zero
- [ ] Tapping a **Downloaded** book in airplane mode plays — this is the claim offline
      mode exists for, tested where it is most likely to be needed
- [ ] A podcast episode plays and is tracked as that episode, not as the podcast
- [ ] Position reached in the car is on the phone afterwards, and on the server once
      there is a connection

## Voice and search

- [ ] "Play *(a book you have)* on lugu" starts it
- [ ] The car's search box finds a book by title and by author
- [ ] A search with no match returns nothing rather than an error, and the app does not
      disappear from the launcher afterwards

## The custom buttons

- [ ] Previous chapter / next chapter appear in the transport and move by chapter
- [ ] On a book with no real chapters they move by the configured skip instead of doing
      nothing
- [ ] The speed button cycles through the presets and wraps at the end
- [ ] A speed set in the car is still set on the phone afterwards

## The queue, from the car

- [ ] **Up next** lists what was queued on the phone, in that order
- [ ] Playing something from it does not silently reorder the rest
- [ ] Letting a book finish in the car starts the next queued item without a touch
- [ ] With the queue empty, a finished book starts the next in its series (unless the
      setting is off), and a finished episode starts the next episode

## Failure modes to check on purpose

- [ ] Signed out: the tree shows one row saying to sign in on the phone, and lugu stays
      in the launcher rather than disappearing or looping
- [ ] Unplug and replug mid-playback: playback resumes, and the tree still browses
- [ ] Kill the app from the phone while the DHU is showing it, then press play in the
      DHU: playback resumes
- [ ] Watch `adb logcat | grep -i "media\|MediaBrowser"` for a repeating bind/unbind
      cycle (androidx/media#3158). Never returning an error from the root is what
      prevents it; a loop means something else is returning one

## The session's trust model — added 16 August, verify first

`onConnect` now hands an untrusted controller Media3's restricted command set rather than
the full one. That is Media3's own default since 1.11 for any app that does not override
`onConnect`, and Android Auto's host should be trusted — the platform's
`isTrustedForMediaControl` grants it to any enabled notification listener or holder of
`MEDIA_CONTENT_CONTROL`, and Auto is both. That has been read in the framework source and
never observed on a head unit, so it is a claim until this passes.

Only half of it can be automated, and the half that can is the half that was never in
doubt. `AutoBrowseTreeTest` asserts that a **trusted** controller gets the full command
set — but it cannot construct an untrusted one, and the reason is worth knowing rather than
rediscovering. Media3 never calls the platform's `isTrustedForMediaControl`; it carries its
own copy in `androidx.media3.session.legacy.MediaSessionManager`, which answers

```
uid == SYSTEM_UID || uid == Process.myUid() || STATUS_BAR_SERVICE || MEDIA_CONTENT_CONTROL
    || an enabled notification listener
```

Instrumentation runs *inside* the process under test, so `uid == Process.myUid()` is true
before anything else is consulted. Every controller a test can build is trusted, and no
amount of granting or revoking over shell subtracts from that. Producing an untrusted one
needs a second application id — the same separate test module the force-stop case wants.

- [ ] The browse tree still loads at all. If it is empty or the app is refused, this is
      the change to suspect first — restoring the previous behaviour is granting
      `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` regardless of trust
- [ ] ✅ The custom buttons still appear (asserted as *advertised*, which is what a head
      unit reads to draw them; that they still *work* is below)
- [ ] The custom buttons still work
- [ ] ✅ Voice search still returns results — by title and by author, and a miss returns
      nothing rather than an error

## Before calling M3 done

- [ ] The whole checklist run once in the DHU
- [ ] The playing, queue and voice sections run once in an actual car
- [ ] Nothing above needed the phone to be unlocked
