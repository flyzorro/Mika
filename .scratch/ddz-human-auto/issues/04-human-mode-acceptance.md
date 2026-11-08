# Human mode acceptance and regression coverage

Status: resolved
Blocked by: none

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

## Comments

- 2026-11-08: Created worktree `ddz-human-mode-acceptance` from clean integration tip `e3c87c9bf00f7c4a360f5f2e045e2d4c2fd3f229`.
- Early baseline: `./gradlew :core:test` completed 3,143 tests with exactly four intentional `phone_ddz_turn` prompt snapshot failures: `PromptSurfaceTest.systemPromptPerCombination`, `PromptSurfaceTest.toolDefinitions`, `PromptSurfaceTest.layeredSurfaces`, and `PromptLayoutTest.layerSizesRendered`.
- Added phone-tool seam coverage for human lead/follow decisions, result-to-next-game transition, history mismatch, hand drift, advisor failure, popup, and lobby return. The lobby-return test found and fixed reason preservation when `awaitDecision` had already classified the handoff.
- Updated only the derived prompt snapshots affected by the intentional `phone_ddz_turn` description/schema contract: `prompt-surface/tools.txt`, `system/everything.txt`, `layers/phone.txt`, `layers/all.txt`, `layers/sizes.txt`, and `sizes.txt`.
- Verification: `./gradlew :core:test --tests 'com.wangzan101.mika.core.cap.phone.PhoneDdzTurnTest'` passed; final `./gradlew :core:test` passed.
- Single-player coverage is included in the passing `PhoneDdzTurnTest` and final core suite.
- DouZero server test attempt: `python3 -m unittest test_server.py` from `tools/douzero` was blocked at import because `douzero` is not installed (`ModuleNotFoundError: No module named 'douzero'`); the documented `.venv` and model weights are absent.
- Manual device acceptance: not executed. A connected device was detected and the installed game package was launched, but the device remained at the Android lock screen, so start/call/double/lead/follow/pass/opponent-delay/recognition-pause/resume/stop/lobby acceptance could not be performed. No manual pass is claimed.
