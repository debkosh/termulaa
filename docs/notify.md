# Phone notifications: `termulaa notify` and the app

**Date:** 2026-11-26 · **Status:** built in 0.7.0 (approved 2026-11-26)

## Problem

Sessions finish things or need a decision while Sudipta is away, and he only
finds out when he asks. His phone should tell him: a notification, and a short
spoken line when earphones are in.

## The shape

```
session / sessmark ──termulaa notify──> POST ntfy.sh/<topic>   (age-encrypted body)
                                               │
termulaa app ──WebSocket ntfy.sh/<topic>/ws?since=<last id>──┘  decrypt, notify, speak
```

termulaa's app subscribes to ntfy.sh itself, as an ntfy client of one topic.
No ntfy app, no UnifiedPush, no hub, no coordinator process.

## How ntfy works (read from its source, 2026-11-26)

ntfy-android at `63fc4c2` (Apache 2.0): 60 Kotlin files, about 15,700 lines.
Almost all of it is UI, accounts, attachments, icons, backups and UnifiedPush.
The delivery core is about 1,200 lines:

- **The protocol is tiny.** Publish is a plain `POST https://ntfy.sh/<topic>`
  with the message as the body. Subscribe is a WebSocket to `/<topic>/ws` or a
  stream of JSON lines from `/<topic>/json`; each line is an event (`open`,
  `keepalive`, `message`). `?since=<message id>` replays what was missed, and
  `?poll=1` makes it a one-shot fetch. ntfy.sh keeps messages 12 hours. The
  topic name is the only access control on the public server, so it is a long
  random secret.
- **Keeping it alive is the real work**, and the source calls it "a hot mess":
  - a foreground service of type `specialUse` (not `dataSync`, which Android 15
    stops after 6 hours a day), started sticky so Android restarts it;
  - a partial wake lock while a message is being handled;
  - reconnect with backoff (5 s up to 2 min) scheduled through exact alarms, so
    it fires in Doze;
  - a boot receiver, a periodic worker that restarts the service if it died,
    and a restart broadcast from `onDestroy`;
  - asking to be exempt from battery optimisation;
  - a periodic poll worker as the safety net, using `since=`.
- The Play build also uses Google's push for ntfy.sh keepalives; the F-Droid
  build does without, which is what we would copy.

## Options

| | termulaa subscribes itself (recommended) | ntfy app + UnifiedPush (draft 2) | Fork the ntfy app |
|---|---|---|---|
| Apps on the phone | one | two | one, but a 15,700-line fork |
| New phone code | ~500 lines: the delivery core, cut to one topic, no auth or attachments | register, one receiver | everything we add, on top of upstream churn |
| Keep-alive tricks | ported from ntfy (Apache 2.0, credited) | ntfy's | ntfy's |
| Server | ntfy.sh now; the protocol is small enough that memd could serve it later | ntfy.sh | ntfy.sh |

**Recommendation: termulaa subscribes itself.** The protocol is a few
endpoints, the keep-alive tricks are a known list we port with credit, and it
saves the second app and the UnifiedPush layer. A fork is rejected: we would
carry 15,000 lines to use 500.

termulaa's existing live-watch service is `dataSync`; this new service is
`specialUse`. They stay separate.

## Encryption

ntfy.sh is a public server, so messages are encrypted end to end with **age**.

- The app makes an age X25519 key pair and a random topic name on setup. The
  private key stays in its encrypted store.
- The app shows one **setup line** to copy: topic and public key. It goes in
  `$KEYKOSH_ROOT/termulaa/notify.env` on this Mac (sealed in the kosh), and the
  same way on the laptop.
- Senders hold only the public key: they can send, not read. The topic is the
  only secret; junk sent to it fails to decrypt and is dropped.
- Libraries: `filippo.io/age` in termulaa (Go), `kage` (the Kotlin age port)
  in the app.

## The message

Plain JSON, age-encrypted, then base64, posted as the body (well under ntfy's
4 KB message limit):

```json
{"v":1,"level":"decision","from":"project-b-release",
 "title":"ready to merge 0.9, needs your yes",
 "body":"tester passed all 42; one flaky retry",
 "link":"https://claude.ai/code/session_…","key":"project-b-release"}
```

- `level`: `info` (finished), `decision` (waiting on him), `blocker` (stopped,
  nothing else to do).
- `title`: one line, 80 characters at most, written to be heard. It is the
  spoken line.
- `body`, `link`: optional. `link` is the session's Remote Control URL; a tap
  opens the Claude app on it.
- `key`: defaults to `from`. A newer message with the same key replaces the
  old notification (Android notification tag).
- Cancel: `{"v":1,"cancel":"<key>"}` removes that notification.

## The sender: `termulaa notify`

```
termulaa notify <info|decision|blocker> "<title>" [-body ..] [-from ..] [-link ..] [-key ..]
termulaa notify -cancel <key>
termulaa notify setup "<endpoint> <age1…>"
```

- `-from` defaults to the tmux window name, else the host name. termulaa knows
  nothing about Claude: the session's name, its link and its key come from
  the caller (`bin/notify` and `bin/sessmark` in my-claude-setup fill them in
  from the session's own record, with the session id as the key).
- `setup` writes `notify.env` (`NOTIFY_ENDPOINT`, `NOTIFY_RECIPIENT`, mode
  0600) to `$KEYKOSH_ROOT/termulaa/`, else `~/.termulaa/`.
- Encrypts, POSTs, exits. No state, no daemon. It is already installed and
  auto-updated on this Mac and the laptop.

## When sessions send

1. **Needs a decision: the ❓ mark.** `sessmark stop` already detects a final
   reply that asks him something. It starts its usual detached waiter, and
   after 3 minutes runs `termulaa notify decision` with the question as title.
   His next prompt (`sessmark hook`) replaces the waiter, as it does today, and
   sends `--cancel` only if the notification already went. At the keyboard the
   phone stays quiet; walked away, it buzzes. It also works for app-started
   sessions the mark can't rename. Ops and orchestrators stay excluded.
2. **Done and blocked: the session sends.** One rule in the global CLAUDE.md:
   `info` when work he asked for finished and he is probably away; `blocker`
   when you stopped with nothing cheap left; never `decision`, the mark does
   that. Same key, so a blocker replaces an earlier question.
3. **Ops** sends like any session, for things it notices. It does not relay:
   relaying through a session costs tokens, adds delay, and stops when ops is
   busy.
4. **Subagents and headless runs** report to their parent, which sends.

## The app

- **Settings > Session notifications:** "Turn on" makes the key pair and
  topic; then the `termulaa notify setup "…"` command with a Copy button,
  "Send a test" (the phone seals a message to itself), the speech switches,
  and buttons for the battery exemption and exact alarms when missing.
- **Listening service:** the ported delivery core. Its permanent notification
  sits in a minimised channel.
- **One channel per level:** Finished (no sound), Needs your decision
  (heads-up), Blocked (heads-up, a long triple vibration; pick a sound for it
  in Android's settings). Android's settings silence any of them.
- **Quiet hours are Android's Do Not Disturb schedule.** No logic of ours. If
  he wants blockers through at night, he lets the Blocker channel override Do
  Not Disturb.
- **Speaking**, with Android text-to-speech, only when all hold:
  - the level is one he chose to hear;
  - earphones are the output: wired, USB, Bluetooth or LE audio; the phone
    speaker never talks;
  - Do Not Disturb is off, no call is in progress, the channel isn't muted.
  It ducks music, says "<from>: <title>", and restores.
- **Buttons:** every notification carries "Stop voice". The listening
  notification carries All / Important / None (the current one ticked):
  speak everything, only decisions and blockers, or nothing. They set the
  three speak switches in Settings; any other mix shows as "as set in
  Settings".
- **Voice settings:** voice (the phone's voices in its language, offline ones
  first), speed, volume (a share of the media volume), and "Pause music while
  speaking" (off: music is lowered under the voice). "Play a sample" speaks on
  the current output, earphones or not.
- **Heard means done:** a notification spoken to the end is removed; one that
  was stopped, muted or not spoken stays. Before speaking, markup symbols are
  dropped and links become "a link".
- **Speech engine:** Android's own TextToSpeech, which on his phone is Google's
  speech engine with on-device voices (no network needed for those).
- **Missed messages:** reconnect with `since=<last id>`, plus the poll worker.

## Not now

- History, rate limits, dedupe beyond replace-by-key, digests: add one only
  when a real annoyance shows up.
- **Reply from the phone** (asked 2026-11-26, later): hold to talk or type and
  send from the notification, delivered into the session. Needs a return path
  (phone → a second topic, sealed to a key on the computer → a listener there
  that types into the session's pane).
- memd serving the ntfy protocol instead of ntfy.sh: possible later; the app
  would only change its base URL.

## Updates

The app now updates itself like etchosts: the amarit Android library reads
`releases-android.json` from the latest release (`android/amarit.json`), and
the release workflow writes that manifest beside the APK. `versionName` is the
repo's `VERSION` and `versionCode` amarit's `major*1000000 + minor*1000 +
patch`, so the app and the binaries share one version. Releases happen when
`VERSION` moves. The first update-capable build (0.7.0) is installed by hand
once; Settings > About > "Check for updates" checks on demand.

## Tests

- Go: `notify_test.go` decrypts what `termulaa notify` posts, checks the
  4 KB ceiling, cancel, and bad arguments.
- App, JVM: `InboxTest` decrypts a message made by the Go side, and covers
  replace, cancel, duplicates, junk, stale messages and the speech switch.
- App, device: `PushEndToEndTest` runs the real service against ntfy.sh. With
  `-Pandroid.testInstrumentationRunnerArguments.fromComputer=1` it also waits
  for a `termulaa notify info … -key computer` sent from a computer.
- Not covered by tests: speech (needs earphones on a real phone) and a night
  of Doze on a real phone.

## Defaults taken (his to change)

1. Decisions and blockers are both spoken; two switches in Settings turn
   either off.
2. Blockers respect Do Not Disturb. To let them through at night, allow the
   "Blocked" channel to override Do Not Disturb in Android's settings.
3. Untested on his phone's make: if notifications arrive late, allow
   "Let it run in the background" in Settings first.
