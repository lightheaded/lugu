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
4. Leave the extra wait at one second for the first run. Playback does not wait a fixed
   time — it waits for the audio to move to the device, and this is added on top.

## The pass

| # | Do this | Expect |
|---|---|---|
| 1 | Play a book briefly, pause, then swipe the app away and `adb shell am force-stop` it. Connect the headset | A notification comes forward — over whatever is on screen, silently — naming the device and showing **Not now** without being expanded. It reads "waiting for the audio to switch over" until the route moves, then counts down the extra second; the book starts at the position it stopped at, **and at the speed it was last played at** |
| 1b | Watch the shade once the book is playing | The waiting notification is **gone**, replaced by the player's. Exactly one lugu notification, not two. This one failed the first device pass: the waiting notification is what holds the service in the foreground, and it cannot be removed until the player's has taken over |
| 2 | Repeat, and press **Not now** during the countdown | Nothing plays. The notification goes. It does not come back a few seconds later when the audio profile connects — that is the suppression window, and it is the most likely thing to be wrong |
| 2b | Repeat with the **phone locked**, and tap the notification body rather than the button | It should cancel there and then. If it instead asks for a PIN, fingerprint or face first, the body tap is not usable in the case this feature is *for* — headphones going on while the phone is in a pocket — and the labelled button is the only control that works. This is the question that decides whether "Not now" is redundant or load-bearing |
| 2c | Repeat with the phone locked, and swipe the notification away | Same again: nothing plays. Then consider whether it *should* — clearing notifications is a habit, and a swipe meant as tidying would cancel a start that was wanted |
| 3 | Start music in another app, then connect the headset | Nothing happens at all. The service is never started, so there is no notification either |
| 4 | Take a call, then connect the headset | Nothing plays. The record says "a call was in progress" |
| 5 | Connect the headset and immediately switch it off again | Nothing plays. The record says "the device had disconnected again" |
| 6 | Connect a *different* headset, one not in the list | Nothing at all — no notification, no service, nothing in the record |
| 7 | Set the extra wait to **none**, and connect | Plays the moment the audio has switched. Listen hard for the first word: if it is clipped or comes out of the phone's speaker, the output arriving is running ahead of the policy actually moving, and one second is the right default. If it is clean over several tries on more than one headset, the default should be none |
| 7b | Connect a Bluetooth device that is **not** an audio device — a watch, a keyboard — after adding it to the list | The notification says it is waiting, then gives up after about twenty seconds. The record says "the audio never switched over to it", not that the device disconnected |
| 8 | **Restart the phone**, do not open lugu, and connect the headset | It still works. This is the one that proves the observation was re-armed on boot rather than only on app start |
| 9 | With a book already playing, connect the headset | Playback carries on untouched. No second notification |
| 10 | Remove the device from the list, then connect it | Nothing happens. Then check Settings → Apps → lugu → the system's own companion-device list, if the phone exposes one: the association should be gone, not merely unused |
| 11 | **With earbuds**: add one side, then wear only the *other* side and connect | Nothing plays, and nothing is in the record — the other side was never chosen and is not observed. Then add it too: the list collapses to one row reading **Both sides**, and either ear now starts a book |
| 12 | Remove that one row | Both sides go, and both associations with them. A pair is one thing to its owner |

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
