# Human history tracking and snapshot recovery

Status: resolved
Blocked by: 01

## Goal

Make the visible history tracker robust for slower human opponents and make a complete snapshot the recovery seam after recognition drift.

## Scope

- Add a human-mode settling policy distinct from single-player animation settling.
- Preserve only confirmed visible plays, passes, and seat transitions.
- Define snapshot validation and full history replay from a supplied hand, bottom cards, and history.
- Return structured recovery failures without mutating a runnable session.
- Add tracker and recovery tests for valid, invalid, ambiguous, and incomplete snapshots.

## Acceptance

- Slow human plays receive a longer bounded settling window.
- Invalid history, hand count, seat, and ambiguous cards are rejected.
- Valid snapshots rebuild the current state without a tap.
- Recovery leaves the next decision available for an explicit subsequent call.
- Existing tracker behavior remains unchanged for single-player mode.
