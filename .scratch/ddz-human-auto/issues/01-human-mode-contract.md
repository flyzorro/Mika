# Human mode contract and session states

Status: ready-for-agent
Blocked by: none

## Goal

Define the phone tool contract for explicit human-game mode without changing existing single-player behavior.

## Scope

- Add explicit human/single mode selection to `start`.
- Add `stop` and `resume` actions and validate their arguments.
- Define the session states and structured pause/stop reasons needed by callers.
- Extract a small policy seam for automatic calling and doubling.
- Implement a conservative deterministic bidding policy for the current classic game.
- Add focused behavior tests at the existing phone-tool seam.

## Acceptance

- Human mode cannot start without an explicit mode and advisor URL.
- `stop` is idempotent and prevents future automatic work.
- `resume` accepts a complete snapshot shape without clicking.
- Calling/doubling decisions are deterministic and conservative.
- Existing single-player argument behavior and tests remain compatible.

## Notes

Use the existing phone tool and test harness. Keep the public surface small; do not duplicate the phone tool.
