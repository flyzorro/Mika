# 农民加倍校准（2026-11-16）

## 改了什么

- `Advisor.double` 对农民座位做条件抽样：每副随机发牌先判断地主那 20 张（17 张随机 + 3 张底牌）会不会叫；
  不会叫就丢掉重发。判断用 DouZero 地主模型对开局第一手所有合规出牌的最高估值，按 `BID_BATCH = 32` 副
  攒成一批一次前向（单副约 0.5 毫秒）。采纳不到 `ROLLOUT_MIN_GAMES`（40）局就返回不加倍。
- `/double` 新增 `seat="farmer"`：两个农民座位各算一遍、共用同一个时间预算，返回较低的赢面。
- 农民单独门槛 `FARMER_DOUBLE_WIN = 0.65`（下表校准），地主仍用 `DOUBLE_WIN = 0.8`。
- 手机端：农民手里 17 张 + 底牌 3 张也问服务；地主位置按对手牌背张数（20 张的是地主）给
  `landlord_up` / `landlord_down`，认不出给 `farmer`。农民只有服务明确说加才点黄色「加倍」，
  连不上/超时/报错/认不全一律蓝色「不加倍」，不退回 `HumanDdzPolicy.shouldDouble`。
  送服务前先查手牌 + 底牌：同一点数超过 4 张、或大小王重复就直接不加倍、不问服务。

## 条件抽样的门槛

`BID_VALUE = 0.0`。2026-11-16 用 260 副随机 20 张牌、每副 40 局自对弈标定（`condition=False` 的
`Advisor.double("landlord", ...)`）：

| 开局最高估值 | 实际地主赢面 | 样本 |
|---|---|---|
| -1.75 | 0.03 | 26 |
| -1.50 | 0.06 | 22 |
| -1.25 | 0.15 | 25 |
| -1.00 | 0.16 | 30 |
| -0.75 | 0.24 | 20 |
| -0.50 | 0.36 | 22 |
| -0.25 | 0.46 | 18 |
| 0.00 | 0.56 | 21 |
| 0.25 | 0.67 | 15 |
| 0.50 | 0.76 | 12 |
| 0.75 | 0.76 | 16 |
| 1.00 | 0.76 | 8 |
| 1.75 | 0.91 | 9 |

估值 0 附近实际赢面 0.56，和 `BID_WIN = 0.55` 对齐。另抽 100 副随机 17 张牌跑 `bid`（40 局），
实际叫牌率 26%；20 张估值 >= 0 的牌占约 33%，和叫牌率同一量级。

## 校准表

`calibrate_double.py --deals 2000 --workers 8 --games 12 --budget 1.0 --min-games 5`：
随机发牌只留地主会叫的 2000 副，两个农民座位共 4000 行；估值用新的 `double`（每座位最多 12 局），
实际输赢让三家 DouZero 把真实那副牌打完。估值按 0.05 一档（四舍五入）分桶：

| 估值 | 实际胜率 | 样本数 |
|---|---|---|
| 0.00 | 0.145 | 408 |
| 0.10 | 0.159 | 786 |
| 0.15 | 0.184 | 891 |
| 0.25 | 0.202 | 733 |
| 0.30 | 0.000 | 2 |
| 0.35 | 0.297 | 438 |
| 0.40 | 0.279 | 290 |
| 0.50 | 0.404 | 183 |
| 0.55 | 1.000 | 1 |
| 0.60 | 0.406 | 133 |
| 0.65 | 0.630 | 73 |
| 0.75 | 0.553 | 38 |
| 0.80 | 0.667 | 3 |
| 0.85 | 0.667 | 12 |
| 0.90 | 0.571 | 7 |
| 1.00 | 0.500 | 2 |

按门槛合并：

| 估值 >= | 样本 | 实际胜率 |
|---|---|---|
| 0.50 | 452 | 0.467 |
| 0.55 | 268 | 0.507 |
| 0.60 | 136 | 0.603 |
| 0.65 | 135 | 0.607 |
| 0.70 | 62 | 0.581 |
| 0.80 | 22 | 0.636 |

0.60 那一档本身只有 1 行（实际 1.000），不能算稳定；0.65 档有 73 行、实际 0.630，
合并 >= 0.65 的 135 行实际 0.607（bootstrap 95% 区间约 0.53–0.70）。所以取
`FARMER_DOUBLE_WIN = 0.65`：这是实际胜率稳定 >= 0.6 的最低估值档。

## 2026-11-16 九手牌新旧对照

旧值 = `condition=False`（和改前的无条件抽样同一批发牌，G2 的 0.74 与任务给的旧值一致）；
新值 = 条件抽样；每格最多 300 局 / 3 秒，括号里是 3 秒内实际采纳的局数。

| 局 | 座位 | 结果 | 旧估值 | 新估值（采纳局数） |
|---|---|---|---|---|
| G2 | landlord_up | 赢 | 0.74 | 0.48（93） |
| G3 | landlord_down | 输 | 0.55 | 0.20（84） |
| G4 | landlord_up | 赢 | 0.55 | 0.23（87） |
| G5 | landlord_down | 赢 | 0.71 | 0.34（59） |
| G6 | landlord_up | 赢 | 0.57 | 0.18（72） |
| G7 | landlord_up | 输 | 0.66 | 0.35（63） |
| G9 | landlord_up | 输 | 0.54 | 0.23（97） |
| G10 | landlord_down | 赢 | 0.71 | 0.32（76） |
| 平均 | | | 0.629 | 0.291 |

任务给的旧值列表（赢 0.74 0.72 0.68 0.56 0.50，输 0.67 0.55 0.55，平均约 0.62）和这里复算的
旧值整体一致；个别手（如 G4）差 0.1 左右，是当时那几次跑法/局数不同，旧算法本身没变。

按校准后的 0.65 门槛，这九手的新估值都不到，都会点蓝色「不加倍」。它们的新估值落在校准表
0.18–0.48 的档位，那些档的实际胜率只有 0.15–0.40；也就是说这九手的旧估值不只是整体高 0.1，
条件抽样后它们本来就不该加倍。这是本次校准最重要的发现。

2026-11-14 那手 0.93 的农民牌：git log / 测试 / 文档里只找得到 commit `79fb1771` 的说明
（"DouZero rated the hand 0.93"），没有手牌和底牌，找不到可复算的牌，没编。

## 3 秒预算内实际采纳了多少局

- 九手牌（300 局目标、3 秒）：59–97 局。
- 典型随机农民牌（3 秒）：71–175 局。
- 一手特别强的农民牌（`BJ SJ 2 2 2 2 A A A K K Q Q J 10 9 8`，底牌 3 4 5）：57–58 局。
  强农民牌意味着地主 20 张偏弱、条件抽样丢掉的多，采纳局数会少；仍能过 40 局下限。
- 采纳不到 40 局（如预算为 0）时 `double` 返回 `(0.0, played)`，端点据此点「不加倍」。

## TDD 红 → 绿

### 服务端

改前先加测试，跑 `python -m unittest test_server.DoubleTest`，红在导入：

```
ImportError: cannot import name 'FARMER_DOUBLE_WIN' from 'server'
```

改后：

```
Ran 12 tests in 20.737s
OK
```

新增/改动的测试：`test_farmer_threshold_is_separate_from_the_landlord_one`、
`test_conditioning_on_a_landlord_who_would_call_lowers_the_farmer_estimate`、
`test_only_deals_whose_landlord_would_call_are_played_out`、
`test_the_farmer_seat_returns_the_lower_of_the_two_farmers`、
`test_a_farmer_deal_that_cannot_be_accepted_is_not_doubled`；农民强弱两手改用
`FARMER_DOUBLE_WIN`。

### 手机端

改前三手红（`./gradlew :core:test`）：

```
PhoneDdzTurnTest > humanDeclinesWithoutAskingWhenTheBottomConflictsWithTheHand FAILED
    java.lang.AssertionError at PhoneDdzTurnTest.kt:2969

PhoneDdzTurnTest > humanFarmerDeclinesOnTheBlueButtonWhenDouZeroSaysNotToDouble FAILED
    java.lang.AssertionError at PhoneDdzTurnTest.kt:2934

PhoneDdzTurnTest > humanFarmerDoublesOnTheYellowButtonWhenDouZeroSaysSo FAILED
    java.lang.AssertionError at PhoneDdzTurnTest.kt:2891

3 tests completed, 3 failed
```

原因分别是：老逻辑农民不问服务、点了蓝色；服务说不加时没送座位；手牌和底牌冲突时老逻辑仍问服务
并点了黄色。改后这几手全绿，另外新增 `humanFarmerAsksTheFarmerSeatWhenTheLandlordPositionIsUnknown`
（认不出地主位置时送 `farmer`）。`humanDeclinesOnTheBlueButtonWhenTheDoubleServiceIsUnreachable`
改成用 `double_farmer_rule_double`（手里有 3 的炸弹、两个王，旧规则 `HumanDdzPolicy.shouldDouble`
会返回 true）：服务连不上时仍然只点蓝色，证明农民没有退回旧规则。

测试用的四张合成截图都是从现有真机帧上按像素复制出来的，因为现有素材里没有这些组合：
`double_conflict.png`（农民底牌认成 2 2 8、手里三个 2）、`double_landlord_conflict.png`
（地主底牌认成 2 2 2、手里三个 2）、`double_no_seat.png`（把地主 20 张的读数盖掉）、
`double_farmer_rule_double.png`（把一个 4 换成 3，手里有 3 的炸弹）。提示词和 prompt-surface 金样同步更新。

## 测试命令

- `cd tools/douzero && .venv/bin/python -m unittest test_server`（本 worktree 没有 .venv 时用
  `/Users/zky/orca/workspaces/mika/ddz-act-budget/tools/douzero/.venv/bin/python`）
- `./gradlew :core:test`：本机 Node 会打 `[UNDICI-EHPA]` 警告，`ReadScriptEnforcementTest` /
  `SelectorPolicyTest` 解析不了输出；默认 512M 的 test worker 跑到 `PhoneDdzTurnTest` 还会 OOM。
  用 `NODE_NO_WARNINGS=1` 加一个 `maxHeapSize = "3g"` 的 init script 后全绿（`BUILD SUCCESSFUL`，
  0 failed）。这两点都是本机环境，和本次改动无关。
