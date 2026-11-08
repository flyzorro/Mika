# Human mode acceptance and regression coverage

Status: pending
Blocked by: 03

## Goal

Verify the integrated feature at the highest practical seams and close the local specification with evidence.

## Scope

- Add missing acceptance tests for all human-mode phases and failure paths.
- Verify the current single-player suite and DouZero server suite.
- Run the current-device manual acceptance flow when a device test is available.
- Record test commands, results, and remaining limitations in this ticket.

## Acceptance

- Human-mode startup, call, double, lead, follow, pass, result, stop, pause, and resume are covered.
- History mismatch, hand drift, advisor failure, unknown scene, popup, app switch, and lobby return are covered.
- Existing single-player behavior is green.
- The final review has no unresolved correctness or safety findings.
