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

## The browse tree

- [ ] lugu appears in the DHU app launcher with its icon and name
- [ ] The root shows **Continue** and **Libraries** always; **Up next**, **Downloaded**,
      **Series** and **Podcasts** only when they have something in them
- [ ] Every category opens, and none of them opens onto an empty list
- [ ] **Series** lists series, not books; opening one lists its books in reading order
      (`#2` before `#10`, which is the whole reason the sequence is stored separately)
- [ ] A podcast opens onto its episodes, newest first
- [ ] Covers appear on rows. If they do not, the rows are still readable and pressable

## Cold start, which is the real test

The phone will usually be freshly plugged in, the app not running, and the car may be in
a garage with no signal. This is the case that matters.

- [ ] `adb shell am force-stop io.github.lightheaded.lugu`, then open lugu in the DHU:
      the tree fills without the app being opened on the phone
- [ ] Repeat with the phone in airplane mode: the tree is identical, because it is served
      from Room
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

- [ ] The browse tree still loads at all. If it is empty or the app is refused, this is
      the change to suspect first — restoring the previous behaviour is granting
      `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` regardless of trust
- [ ] The custom buttons still appear and still work
- [ ] Voice search still returns results

## Before calling M3 done

- [ ] The whole checklist run once in the DHU
- [ ] The playing, queue and voice sections run once in an actual car
- [ ] Nothing above needed the phone to be unlocked
