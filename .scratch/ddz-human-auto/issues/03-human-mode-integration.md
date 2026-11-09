# Integrate human mode into the phone turn engine

Status: resolved
Blocked by: 01, 02

## Goal

Connect the human-mode contract, tracker, policy, DouZero advisor, and shared phone control into a complete automatically driven classic human game.

## Scope

- Add explicit human lobby/table eligibility checks.
- Share polling, countdown, tap confirmation, timeout, and safe-stop behavior with single-player mode.
- Automate call, double, lead, follow, pass, and result transitions.
- Validate phase, seat, hand count, expected remaining hand, history, and returned cards before every tap.
- Pause on unknown scenes, popups, app switches, lobby return, service failure, or unrecoverable drift.
- Add the stop and resume flows to the public tool response.

## Acceptance

- A human classic game can run through normal call, double, play, pass, and result phases on the current device layout.
- Unknown state never causes an automatic tap.
- `stop` prevents delayed polling from tapping.
- `resume` returns state first and only a later call performs the pending action.
- Single-player mode continues to pass its current tests.
