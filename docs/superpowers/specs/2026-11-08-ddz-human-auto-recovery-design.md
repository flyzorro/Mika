# 真人斗地主自动恢复设计

## 意图

让 Mika 在真人斗地主中持续自动完成我方叫分、加倍、出牌和不出。牌桌短暂识别失败、DouZero 历史暂时对不上、或模型需要重建状态时，系统自动恢复，不因重复初始化或等待超时进入游戏托管。

## 已确认问题

- `PhoneDdzTurn.startHuman()` 每次调用都会清空 `tracker`、`expectedHand` 和 `snapshot`。模型在暂停后重复调用 `start`，会丢失已经确认的出牌历史。
- DouZero 服务端按历史推算各家剩余牌数。历史被清空后，当前手牌数量与历史推算不一致，返回 "hand_count_mismatch"。
- 真人牌桌识别暂时落到 "unknown_scene" 时，工具立即进入 paused。模型恢复期间超过游戏约 15 秒出牌时限，游戏自动托管。
- 当前模型可以在工具暂停后继续发 `start` 或普通 `play`，缺少统一的恢复入口。

## 目标

- 真人模式的 `start` 幂等。已有同一牌局状态时，不清空 tracker。
- "unknown_scene"、短暂动画、弹窗关闭后，工具内部先做有限重试和稳定性等待。
- tracker 仍不可用时，由大模型读取完整牌桌并生成合法 `resume` 快照，再继续自动处理。
- 恢复前校验手牌、底牌、历史、阶段和座位；不可信时停止点击，避免误出牌。
- 恢复动作和 DouZero 错误都在 15 秒时限内完成，模型循环优先使用快速恢复路径。
- 对历史恢复、重复 `start`、手牌漂移、DouZero mismatch 增加回归测试。

## 非目标

- 不修改 DouZero 的牌力策略、权重或牌型规则。
- 不支持其他斗地主 App、变体或单机模式行为变化。
- 不在无法确认牌局状态时用猜牌强行继续。

## 方案

### A：只做有状态恢复

让 `startHuman` 幂等，保留 tracker；遇到识别失败时在工具内部等待并继续。实现简单、速度快，但无法修复已经损坏的历史。

### B：每次暂停都让大模型重建快照

暂停后读取整屏，调用 `resume`。可修复历史损坏，但识牌和模型调用耗时高，容易再次错过 15 秒。

### C：双层恢复，采用

先走 A：保留 tracker，内部等待稳定画面，快速恢复。A 失败后才走 B：让大模型生成完整快照，经过严格校验后调用 `resume`。快照仍不可信时停止点击并报告原因。

## 关键行为

1. 首次 `start(mode=human)`：确认真人经典牌桌，建立 tracker。
2. 后续重复 `start(mode=human)`：若 App、advisor、牌桌和当前局一致，只恢复当前 session，不重置 tracker。
3. "unknown_scene"：在工具内等待有限帧数，重新检查真人牌桌标记；恢复成功则继续，不把短暂动画交给模型。
4. tracker 未损坏但模型请求失败：快速重试一次；仍失败才进入快照恢复。
5. tracker 已损坏或历史 mismatch：返回可恢复状态，提示模型读取完整快照并调用 `resume`；禁止直接 `play` 或再次清空 session。
6. `resume` 成功后，下一次自动动作再次核对手牌、底牌、历史、阶段和座位。
7. 任何恢复路径都不能点击未知画面；游戏回大厅、切换 App、结算页等终止当前局。

## 验收标准

- 重复 `start` 不再使 `historyCount` 回到 0，不产生由 session 重置导致的 "hand_count_mismatch"。
- 短暂动画或牌桌标记丢失时，工具在游戏时限内自动恢复，不进入托管。
- 真实 "douzero_hand_count_mismatch" 时能进入快照恢复，而不是继续发送普通 `play`。
- 快照字段不完整、牌数不守恒、历史不合法、阶段或座位不匹配时，系统拒绝点击并说明原因。
- 单元测试覆盖上述路径；现有 Kotlin、DouZero 和 Mika CLI 测试全部通过。
- 至少完成一局真人牌局验收，记录每次 pause/recovery、DouZero 响应和最终结果。

## 实现范围

- `core/src/main/kotlin/com/wangzan101/mika/core/cap/phone/PhoneDdzTurn.kt`
- `core/src/main/kotlin/com/wangzan101/mika/core/cap/phone/DdzTracker.kt`
- 相关真人模式测试、DouZero 协议测试和提示词。
- 不修改 `tools/douzero` 策略代码，除非测试证明服务端协议本身有独立缺陷。

