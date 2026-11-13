# Human mode: room=master landed in 经典至尊场 — cause, diagnostics, and room check

Status: resolved
Blocked by: none

## Goal

Explain why `room=master` games ended up at 经典至尊场 on 2026-11-13, keep permanent diagnostics for
the room tap, and make the tool stop before bidding or playing when the game's room does not match
the requested `room`.

## Evidence (fixtures-master3x, 2026-11-13, 2412×1080)

- `084020_723.png` / `084155_262.png` (human room list, before the tap): the second-row first card is
  purple, spans x 656–1085, centre ≈ (870, 723); `(872,723)` reads RGB (86,61,180) / (114,90,205),
  i.e. the purple 大师场 card the task describes. The second-row second card (x 1151–1570, red with
  a bright yellow border) is the recommended 至尊场.
- `084157_315.png` (matching, ~2 s after the room list) and `084028_480.png` (table) both print
  经典至尊场 at the bottom centre (x 1136–1283, y 1035–1062).
- `083955_038.png` … `084006_858.png` are tables from the same run: all print 经典至尊场 too.
- `ddztrace-master3x.log`: 08:40:26 and 08:42:02 show the first `DDZTRACE` of new games (17-card
  hand); there is no room tap line because #51 logged none. The screenshots bracket the tap but do
  not capture the tap itself.

## Cause

The tool's side is correct: `humanClassicRooms` recognises the page, `awaitHumanEntry` taps the
requested fixed point through `tapOnTheWay` → `ScreenPort.quickTap`, and `AndroidScreen.quickTap`
passes the pixels unchanged to `a11y.gesture(displayId, …)` (no scaling, no letterbox: `startHuman`
already refuses frames that are not 2412×1080). On the captured room lists `(872,723)` is the purple
大师场 card, so the tool tapped the right card once.

What remains is game-side: after the master tap the matching screen and table both name 经典至尊场
within about 2–5 seconds, with no error from the game and no other tool taps in the log. The most
consistent explanation is that the game seated this high-bean account (3.05–3.21亿) in its
recommended 至尊场 instead of the tapped 大师场. A tap-time screenshot is missing, so this is the
strongest available conclusion, not a proof; the coordinates were deliberately not changed, and the
new room check turns the outcome into a visible `room_mismatch` instead of a silent wrong-room game.

## What changed

- `DdzRoomLabel` reads the bottom-centre 经典××场 label. It locates the five glyphs, normalises the
  middle two to 20×20 and compares them with embedded exemplars from the real frames: 普通场,
  至尊场, 土豪场. Anything else (大师场, 精英场, a covered label, rows not read) returns null, which
  is treated as "cannot tell", never as a mismatch.
- `PhoneDdzTurn.startHuman` records the requested `room` for `auto=true` and checks the label when
  the matching screen or table appears, before installing the session; the check also runs at the
  top of every human `execute`, inside `awaitDecision` before any bid/play, and on the entry loop.
  On mismatch it sets `paused`, taps nothing, and returns error `room_mismatch` with `room` (the
  label read), `requested` and `expected`; every later call except `start`/`stop` repeats that error,
  so `resume`/`play` cannot continue.
- Diagnostics: when the room card is tapped it prints
  `DDZTRACE room_tap room=<room> x=<x> y=<y>` (same `println("DDZTRACE …")` convention as the
  existing debug branches) and leaves the next frames in `DdzFrameDump` under tag `room_tap`
  (`/sdcard/Android/data/<pkg>/files/ddzdump/`). The dump is permanent, not TEMP.
- Tool prompt: added the `room_mismatch` meaning (stop, do not pick another room, do not retry
  `start`, report exactly) and regenerated the prompt-surface snapshots.

## Acceptance

- `masterRoomOnAZhizunTableStopsBeforeBiddingOrPlaying`: 至尊 table + `room=master` → `room_mismatch`,
  `room=至尊场`, no taps at all.
- `masterRoomStopsAtTheMatchingScreenWhenItSaysZhizun`: tap only the 大师 card (872,723), then stop on
  the matching label; no card taps.
- `tuhaoRoomOnATuhaoTableKeepsPlaying`: 土豪 table + `room=tuhao` → no mismatch, play continues.
- `aCoveredRoomLabelDoesNotStopTheGame`: covered label → no `room_mismatch`.
- `DdzRoomLabelTest`: reads 至尊场 (table + matching), 土豪场, 普通场; returns null for an unknown page,
  a covered label, and a frame that did not read the label rows.
- `./gradlew :core:test --tests 'com.wangzan101.mika.core.cap.phone.*'`: 471 tests in the combined
  run with the prompt suites, 0 failures; `PhoneDdzTurnTest` 198, `DdzRoomLabelTest` 3.
- Prompt snapshots: `./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' -PupdateSnapshots`.

## Comments

- 2026-11-13: Implemented on `flyzorro/ddz-room-mismatch`; no changes to #48/#50 entry guards or room
  recognition, and no changes to play, tracker, or simple rules.
