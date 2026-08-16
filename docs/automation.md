# Driving lugu from an automation app

lugu accepts a small set of broadcast intents, so Tasker, Automate, MacroDroid, a
launcher shortcut, `adb`, or anything else that can send a broadcast can control
playback. Everything here is a transport command: play, pause, skip, speed, the sleep
timer, and starting a book by name.

A broadcast receiver was chosen rather than an activity so that a routine never steals
focus. Pausing a book from a Bluetooth trigger does not raise a window over what you were
reading, and does not take over the display in a car.

**Nothing comes back.** The receiver sets no result code and no result data. It cannot be
used to read your library, your server, or anything about your account — it only acts.
That is also why it needs no permission: these are the same verbs the notification and a
headset button already send, and there is nothing here to read.

## The two rules that make it work

**1. Namespace.** Every action name starts with the application id:

| Build | Prefix |
| --- | --- |
| Release | `io.github.lightheaded.lugu` |
| Debug | `io.github.lightheaded.lugu.debug` |

So the play action is `io.github.lightheaded.lugu.action.PLAY` on a release build and
`io.github.lightheaded.lugu.debug.action.PLAY` on a debug one. The debug build carries an
`applicationIdSuffix`, and the actions follow it deliberately: the two builds can be
installed side by side, and each answers only to its own actions. A routine written
against one will not drive the other. Everything below writes the release prefix; add
`.debug` to it if that is the build you have.

**2. Address the broadcast.** Since Android 8, a manifest-declared receiver does not
receive implicit broadcasts. The action alone is not enough — the sending app must also
name the package or the component, or nothing will happen at all:

- package `io.github.lightheaded.lugu`
- class `io.github.lightheaded.lugu.automation.AutomationReceiver`

In Tasker this is the *Package* and *Class* fields of the *Send Intent* action, with
*Target* set to *Broadcast Receiver*. With `adb` it is the `-n` flag.

## The actions

| Action (after the prefix) | Extras | What it does |
| --- | --- | --- |
| `.action.PLAY` | none | Resumes what is loaded. Never pauses, so a routine that fires twice is harmless. |
| `.action.PAUSE` | none | Pauses. |
| `.action.PLAY_PAUSE` | none | Toggles. |
| `.action.SKIP_FORWARD` | `seconds` | Seeks forward. |
| `.action.SKIP_BACK` | `seconds` | Seeks back. |
| `.action.NEXT_CHAPTER` | none | Next chapter, or a 30-second skip in an item with no chapters. |
| `.action.PREVIOUS_CHAPTER` | none | Restarts this chapter, or steps back to the previous one if you have only just entered it. |
| `.action.SET_SPEED` | `speed` | Sets the playback speed and remembers it for this book, exactly as the speed sheet does. |
| `.action.SLEEP_TIMER` | `minutes`, `chapters` or `end_of_chapter` | Arms the sleep timer. |
| `.action.SLEEP_CANCEL` | none | Cancels the sleep timer. |
| `.action.PLAY_SEARCH` | `query` | Finds the best match in the offline index and plays it. |

### Extras

| Extra | Type | Range | If it is left out |
| --- | --- | --- | --- |
| `seconds` | number | above 0, up to 3600 | Your own skip amounts from Settings are used, so an automation moves as far as the button in the app. |
| `speed` | number | 0.5 to 3.5 | Nothing happens; the speed has to be stated. |
| `minutes` | number | 1 to 1440 | — |
| `chapters` | number | 1 to 99 | — |
| `end_of_chapter` | flag | `true` | — |
| `query` | text | up to 200 characters | Nothing happens. |

Numbers may be sent as integers, as floats, or as text. `"30"` and `30` mean the same
thing, because most automation apps send text whether or not you asked them to. A flag
accepts `true`, `1`, `yes` and `on`, and their opposites.

### What happens to a value that makes no sense

Nothing. A command whose extras cannot be honoured is dropped, and playback carries on
exactly as it was. Specifically:

- A `seconds` of `0`, a negative number, or something that is not a number at all is
  refused rather than reinterpreted. The direction lives in the action name, so a
  negative skip is a mistake in the routine, not an instruction to go the other way.
- A `speed` outside 0.5 to 3.5 is clamped into it, so a variable that drifted to `4`
  still plays. A `speed` of zero or below is refused: zero reads as "stop", and quietly
  turning it into the slowest playback would answer a different question.
- `SLEEP_TIMER` needs exactly one of `minutes`, `chapters` and `end_of_chapter`. Sending
  two is an ambiguous instruction and arms nothing — picking one of them by a rule you
  cannot see is how a routine ends up doing something its author cannot explain.
- An empty or overlong `query` is not a search.

Sleep timer counts behave the same way they do in the app: `chapters` is counted from
where the book is now, so skipping ahead leaves one fewer to go, and asking for more
chapters than remain means the end of the book.

## A worked example

Pause the book when the car's Bluetooth disconnects, then arm a two-chapter sleep timer
at bedtime.

With `adb`, to try the actions before wiring them into anything:

```sh
# Pause.
adb shell am broadcast \
  -a io.github.lightheaded.lugu.action.PAUSE \
  -n io.github.lightheaded.lugu/.automation.AutomationReceiver

# Sleep after two more chapters.
adb shell am broadcast \
  -a io.github.lightheaded.lugu.action.SLEEP_TIMER \
  -n io.github.lightheaded.lugu/.automation.AutomationReceiver \
  --ei chapters 2

# Skip back 45 seconds.
adb shell am broadcast \
  -a io.github.lightheaded.lugu.action.SKIP_BACK \
  -n io.github.lightheaded.lugu/.automation.AutomationReceiver \
  --ei seconds 45

# Start a book by name.
adb shell am broadcast \
  -a io.github.lightheaded.lugu.action.PLAY_SEARCH \
  -n io.github.lightheaded.lugu/.automation.AutomationReceiver \
  --es query "lighthouse wakes"
```

`--ei` sends an integer, `--ef` a float, `--es` text and `--ez` a boolean.

In Tasker, the same sleep timer as a task:

1. Add an action: *System* → *Send Intent*.
2. **Action**: `io.github.lightheaded.lugu.action.SLEEP_TIMER`
3. **Extra**: `chapters:2`
4. **Package**: `io.github.lightheaded.lugu`
5. **Class**: `io.github.lightheaded.lugu.automation.AutomationReceiver`
6. **Target**: *Broadcast Receiver*

Leave *Mime Type*, *Data* and *Category* empty. Then give the task whatever profile you
like — a time, a Bluetooth device, an NFC tag.

## Things worth knowing

- `PLAY` resumes what is already loaded. If nothing is, it has nothing to resume; use
  `PLAY_SEARCH` to start something.
- `PLAY_SEARCH` answers from the same offline index the search box uses, and plays the
  first match rather than offering a list — a routine cannot read a list of results.
- The first command after lugu has been idle for a long time may take a moment while the
  playback service starts, and Android restricts what an app may start from the
  background: a play command sent after a long idle can be refused by the system. When
  that happens nothing crashes, the command simply does nothing.
- These actions are part of what lugu offers deliberately, so they will not be renamed
  without a note in the release. Anything else you find in the manifest is not a
  promise.
