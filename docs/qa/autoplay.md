# Starting a book when a device connects — the device pass

Everything about this feature happens where no test can watch: a headset connecting, a
process the system starts on lugu's behalf, and a foreground service being permitted or
refused. The rules are unit-tested — `AutoPlayTest` covers matching, the stored record, the
cancel suppression and every refusal — but *whether the phone ever calls us at all* is the
part that matters, and only a device answers it.

Run this on a **signed release build**, not a debug one. The two things most likely to fail
here — R8 stripping a service the system reaches reflectively, and a background
foreground-service start being refused — behave differently in release.

## Setting it up

1. Settings → Headphones and car → **Start playing when a device connects**.
2. **Choose a device**. On Android 12 and later this opens the *system's* picker, not
   lugu's. The headset must be switched on and connected, or it will not be in the list.
3. The device appears by name. On Android 12 exactly it will read "Bluetooth device", which
   is expected and is in the backlog.
4. Leave the wait at five seconds for the first run.

## The pass

| # | Do this | Expect |
|---|---|---|
| 1 | Play a book briefly, pause, then swipe the app away and `adb shell am force-stop` it. Connect the headset | A notification appears naming the device and counting down; the book starts after the wait, at the position it stopped at, **and at the speed it was last played at** |
| 2 | Repeat, and press **Not now** during the countdown | Nothing plays. The notification goes. It does not come back a few seconds later when the audio profile connects — that is the suppression window, and it is the most likely thing to be wrong |
| 3 | Start music in another app, then connect the headset | Nothing happens at all. The service is never started, so there is no notification either |
| 4 | Take a call, then connect the headset | Nothing plays. The record says "a call was in progress" |
| 5 | Connect the headset and immediately switch it off again | Nothing plays. The record says "the device had disconnected again" |
| 6 | Connect a *different* headset, one not in the list | Nothing at all — no notification, no service, nothing in the record |
| 7 | Set the wait to none, and connect | Plays as good as immediately. Listen for the first word: if it is clipped or comes out of the phone's speaker, that is the gap the wait exists for, and it is the measurement that says what the default should be |
| 8 | **Restart the phone**, do not open lugu, and connect the headset | It still works. This is the one that proves the observation was re-armed on boot rather than only on app start |
| 9 | With a book already playing, connect the headset | Playback carries on untouched. No second notification |
| 10 | Remove the device from the list, then connect it | Nothing happens. Then check Settings → Apps → lugu → the system's own companion-device list, if the phone exposes one: the association should be gone, not merely unused |

## Reading the record

Settings → Diagnostics → why playback stopped. Every outcome writes a line:

- `auto-play triggered` — the device was recognised and the wait started
- `auto-play refused` — with the reason, which is the whole point of the line
- `auto-play started playback`
- `auto-play cancelled by the listener`

If **nothing at all** appears when a chosen device connects, the trigger never ran, and the
question is whether the system is calling `AutoPlayCompanionService` — not whether lugu
decided against playing.

```sh
adb logcat | grep -iE "companion|CompanionDevice|ForegroundServiceStartNotAllowed"
```

A `ForegroundServiceStartNotAllowedException` means the association's exemption did not
apply. That is the failure this design exists to avoid, and it would mean the association
was lost rather than the code being wrong — check the association still exists before
changing anything.

## What is not covered here

Android 11 and earlier take the other path entirely — a manifest receiver and the paired
device list, with no association involved. Nothing in this pass exercises it, and no device
running it has been to hand. See the backlog.
