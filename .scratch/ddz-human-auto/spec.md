# 支持经典斗地主真人局全自动模式

Status: ready-for-agent

## Problem Statement

当前斗地主快速路径只支持从单机大厅启动的经典斗地主。它依赖单机模式标记，使用 DouZero 自动出牌；真人模式没有独立的启动、叫分、加倍、状态恢复和安全停止流程。用户希望在当前设备、当前游戏布局和经典斗地主玩法下，让真人对局也能够由 Mika 自动完成叫分、加倍和出牌，同时在视觉识别或局面重建不可靠时及时停止，避免继续基于错误状态点击。

## Solution

为 `phone_ddz_turn` 增加显式的真人局模式。真人局通过 `start` 明确选择 `mode=human` 后进入自动化会话，沿用现有手机快速回合控制和 DouZero 不完全信息推理能力，并增加真人局专用的状态追踪、保守叫分/加倍策略、完整局面快照恢复和紧急停止动作。

真人局只使用屏幕可见信息：自己的手牌、底牌、已打出的牌、各家的可见出牌和轮次。对手手牌继续由 DouZero 的局面重建逻辑补足，不把推测当成确定事实。状态不清、历史无法重放、手牌漂移、服务不可用或出现未识别场景时，系统进入暂停状态并把控制权交还给用户。

## User Stories

1. As a player in a classic human斗地主 game, I want to explicitly start human-game automation, so that the tool cannot accidentally take over a single-player or unsupported game.
2. As a player, I want to choose human mode through the existing `phone_ddz_turn` entry point, so that I do not need a second phone tool for the same game.
3. As a player, I want the tool to recognize the classic斗地主 table before acting, so that it does not tap an unrelated screen.
4. As a player, I want the tool to identify when it is my turn to call, so that it can make the call without waiting for model intervention.
5. As a player, I want conservative automatic calling, so that uncertain hand strength does not cause aggressive bids by default.
6. As a player, I want automatic doubling decisions, so that the whole normal decision sequence can run without manual intervention.
7. As a player, I want automatic first-play decisions, so that the tool can lead a trick when it is my turn.
8. As a player, I want automatic follow-play decisions, so that the tool can beat or pass on a visible opponent play.
9. As a player, I want the tool to continue using DouZero for play decisions, so that human games benefit from the same incomplete-information reasoning as single-player games.
10. As a player, I want the tool to use only visible cards and confirmed history, so that hidden opponent cards are never treated as known facts.
11. As a player, I want the tool to wait longer for a human opponent's play to settle, so that a slow opponent is not mistaken for a missing play.
12. As a player, I want the tool to validate the current phase, seat, hand count, and recent history before tapping, so that stale screenshots cannot trigger an illegal action.
13. As a player, I want the tool to detect hand drift after an automatic play, so that a rejected tap or misread card stops the automation instead of corrupting the rest of the game.
14. As a player, I want the tool to stop when the visible history cannot be reconstructed, so that it does not guess through an ambiguous or inconsistent game state.
15. As a player, I want an explicit pause result with a reason when automation stops, so that I know whether to correct recognition, restart the game, or take over manually.
16. As a player, I want to submit a complete current snapshot containing my hand, bottom cards, and visible history, so that the tool can rebuild the current game after a recognition failure.
17. As a player, I want snapshot recovery to replay and validate the entire known history, so that a successful recovery proves the current state is internally consistent.
18. As a player, I want recovery to report the current state before clicking, so that I can stop the automation before the next action if the screen has changed.
19. As a player, I want a `stop` action that immediately disables future automatic taps, so that I can take control at any time.
20. As a player, I want switching apps, returning to the lobby, or entering an unknown screen to stop the current automation session, so that a previous session cannot act in a new context.
21. As a player, I want unknown popups and unsupported scenes to return control to me, so that the automation never closes or navigates through an unfamiliar dialog.
22. As a player, I want human-mode failures to preserve the existing safe fallback behavior, so that the game remains playable manually after automation stops.
23. As a player, I want the existing single-player DouZero mode to keep working, so that adding human mode does not regress the current feature.
24. As a maintainer, I want shared waiting, tapping, timeout, and stop behavior in one round-control module, so that fixes apply consistently to single-player and human modes.
25. As a maintainer, I want human-specific tracking and bidding behind a separate adapter, so that incomplete-information logic does not leak into generic phone control.
26. As a maintainer, I want clear error reasons for history mismatch, hand drift, service failure, unknown scene, and rejected action, so that failures can be diagnosed without guessing.
27. As a maintainer, I want automated tests for normal human-game phases and recovery paths, so that changes to recognition and control remain reviewable.
28. As a player, I want the first version to target the current device and current game layout, so that the human-game behavior can be validated before expanding visual support to other devices.

## Implementation Decisions

- Extend the existing `phone_ddz_turn` tool with an explicit session mode: single-player or human-game. Human mode must be selected at start and must not be inferred solely from the screen.
- Keep one external tool entry point and one shared round-control module for polling, countdown budgeting, taps, result confirmation, timeout handling, safe stopping, and handoff to the user.
- Keep single-player and human-game policies behind separate adapters. Single-player keeps its current single-player lobby and DouZero takeover behavior; human mode gets its own lobby/table eligibility checks and state transitions.
- Model the human session with explicit states for idle, lobby, calling, doubling, playing, result, paused, and stopped. Only confirmed decision states may issue taps.
- Add automatic calling and doubling through a conservative, deterministic policy. The first version does not train or load a separate bidding model.
- Continue using the existing DouZero service for human-game play decisions. The service receives the player's current hand, bottom cards, and confirmed visible history, then reconstructs the unknown hands as incomplete information.
- Do not treat inferred opponent cards as observed cards. Only recognized plays, passes, the player's hand, and confirmed phase transitions enter the canonical history.
- Give human opponents a longer settling window than single-player animation, while still honoring the current player's turn countdown. A single fixed polling limit must not be reused for both cases.
- Validate phase, seat, turn ownership, hand count, expected remaining hand, confirmed history, and returned cards before every automatic tap.
- Preserve the current fail-safe behavior: when the state cannot be confirmed, pause automation and return a structured reason. Do not close popups, navigate back to the lobby, reconnect, restart a game, or guess through an unknown screen.
- Add `resume` as a snapshot-recovery action. The snapshot contains the complete current hand, bottom cards, and visible play history. Recovery replays and validates the entire supplied history before changing the session back to a runnable state.
- A successful recovery returns the reconstructed phase and state without clicking. The next explicit tool call performs the pending call, doubling, pass, or play.
- Add `stop` as an idempotent emergency action. It clears the active automation session and prevents subsequent polling loops from tapping until a new explicit `start`.
- Keep the current device and current classic-game layout as the first-version visual scope. Relative layout and multi-resolution support remain later work.
- Keep the existing DouZero HTTP interface unchanged. The human/single mode distinction belongs to the phone-side session and adapter, not to the model service protocol.
- Keep the current local or explicitly configured advisor model. This feature does not add authentication, public service exposure, or a new deployment model for the advisor.
- This work spans more than eight existing or new modules and tests because it changes the tool state model, shared round control, human-game adapter, recovery contract, and regression coverage. It should be implemented in independently reviewable slices without changing the public behavior of single-player mode until the human-mode path is complete.

## Testing Decisions

- Tests should cross the highest practical seam: invoke the phone-game tool with synthetic screen state and fake advisor responses, then assert returned phase, error, stop state, and recorded taps. Tests should avoid asserting private helper calls or implementation-specific polling counters.
- Extend the existing phone-game acceptance tests to cover human-mode startup, explicit mode selection, calling, doubling, leading, following, passing, result, and next-game transitions.
- Add behavior tests for the conservative calling and doubling policy using representative hand features and assert the selected visible button, not internal rule branches.
- Add tests for slow opponent plays and settling windows. The observable result should be continued waiting while the game remains stable, followed by a pause when the configured recovery budget is exhausted.
- Add tests for history mismatch, unreadable opponent play, hand drift, rejected play, unavailable advisor, unknown scene, unexpected popup, app switch, lobby return, and game result.
- Add snapshot recovery tests that submit a complete valid snapshot and assert that the next result exposes the reconstructed decision without a tap. Add invalid snapshot cases for illegal history, wrong hand count, wrong seat, inconsistent remaining cards, and ambiguous cards.
- Add stop tests that assert `stop` is idempotent and that no delayed polling loop can tap after stop has been requested.
- Keep all existing single-player tracker, card-recognition, phone-turn, and DouZero server tests passing. The new tests must prove that human mode does not change the single-player path.
- Add a manual acceptance pass on the current device covering a real classic human game: start, call, double, lead, follow, pass, opponent delay, one intentional recognition pause, snapshot resume, stop, and return to lobby.
- Treat an automatic tap in an unknown scene, after stop, or after a failed snapshot rebuild as a test failure even if the game would have accepted the tap.

## Out of Scope

- Other games, other applications, or a general-purpose visual automation framework.
- Other斗地主 variants, including laizi, competition modes,残局, or rules that differ from the current classic game.
- Support for arbitrary devices, screen sizes, DPI values, skins, or future layouts in the first version.
- Automatic handling of login, matchmaking, ads, popups, disconnections, reconnects, lobby navigation, or starting a new game.
- A learned bidding or doubling model.
- Reading or inferring hidden opponent cards as authoritative information.
- A second public or remote advisor service, authentication, deployment packaging, or cloud inference.
- Fully unattended recovery after an error.
- Changes to the existing single-player user flow beyond the shared internals needed to prevent regressions.

## Further Notes

The current implementation already has useful seams in the phone turn tool, card recognizer, tracker, advisor client, and DouZero server. The main design risk is the assumption that the human table uses the current device's recognizable layout. If that assumption fails, the correct follow-up is a human-table screen adapter rather than adding more unconditional coordinates to the existing recognizer.

The feature is intended as a local, explicitly started experiment on the current device. The game platform's rules and the consequences of automated play remain outside this implementation's control; the tool should not silently broaden its scope or start automation without an explicit human-mode start.
