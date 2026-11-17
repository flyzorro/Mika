# 斗地主 DouZero：把叫分 / 加倍用到现场模拟和残局搜索里

日期：2026-11-17 · 分支：`flyzorro/ddz-auction-prior`

## 结论

- `POST /act` 新增可选字段 `auction`（各座位最后叫分 + 加没加倍）。字段格式不对就整块忽略、照常出牌、不报错；不带时行为与改动前完全一样。
- 看不见的对手按「叫了三分 / 加了倍的那家牌更好」加权：现场模拟的发牌改成带权拒绝采样，残局搜索的似然在同一个对数空间里乘上证据。
- 只给看不见的对手加权；我自己的叫分 / 加倍不参与。`/bid`、`/double`、手机端（Kotlin）和 DouZero 权重都没动。

## 改了哪些文件

| 文件 | 改动 |
| --- | --- |
| `tools/douzero/server.py` | 协议说明、`Auction` / `parse_auction`、开局牌力度量、带权发牌、`likelihood` 加证据、handler 透传 |
| `tools/douzero/test_server.py` | 新增 4 个测试类：`AuctionProtocolTest`、`AuctionSamplingTest`、`AuctionRolloutTest`、`AuctionEndgameTest` |
| `tools/douzero/calibrate_auction.py` | 新增标定脚本（与 `calibrate_double.py` 同风格） |

## 协议

```json
"auction": {"bid": {"landlord": 3},
            "double": {"landlord": true, "landlord_down": false, "landlord_up": null}}
```

- `bid`：各座位最后叫的分（0..3），没有 / null = 不知道。v1 只用地主的叫分，而且只有叫 3 分算证据。
- `double`：各座位是否加倍（true / false），null / 缺省 = 不知道。
- 座位名不在 `landlord` / `landlord_down` / `landlord_up` 里、叫分不在 0..3、类型不对（`"3"`、`1.5`、`true` 当叫分，`1` 当加倍）→ 整个 `auction` 当没带，照常出牌，不报错。
- 全是 unknown 的空 `auction` 也规范成 `None`。

## 实现

1. **牌力**：`opening_state(seat, hand)` 造出「这家开局拿着整手牌」的 info set（自己领出、没有历史、其他两家只按张数），用这家自己的 DouZero 模型给所有合法出牌打分，取最高分。地主 20 张（含底牌）、农民 17 张；手里剩的 + 已经出过的就是开局整手牌。
2. **证据权重**：`P = sigmoid((s - T) / τ)`；叫三分用 `T_bid`，加倍用 `T_dbl[座位类型]`，没加倍用 `1 - P`。多条证据的似然相乘（对数相加）。
3. **现场模拟**：`Advisor.sample_hands` 在没有证据时直接调用原来的 `sample_hands`（同一个种子一手不差）；有证据时随机发一手 → 按权重（0..1 的乘积）接受，不接受重发，最多 `AUCTION_MAX_TRIES = 8` 次，超过或到模拟截止时刻就收下最后一手。采样时间算在原来的 2.5 秒预算里。
4. **残局搜索**：`Advisor.likelihood` 在原有的 log 似然上，逐座位把该分法下对手开局整手牌的牌力证据加进去；没有出牌历史、只有 auction 时也生效。
5. **排除自己**：`Auction.seats(me)` 只返回「有证据且不是我」的座位。

## TDD 红 → 绿

### 切片 1：协议（`AuctionProtocolTest`）

先写测试，跑红：

```
test_a_valid_auction_reaches_the_advisor ... ERROR
test_the_advisor_accepts_an_auction ... ERROR

AttributeError: 'NoneType' object has no attribute 'bid'

AttributeError: module 'server' has no attribute 'parse_auction'
```

实现后 4 个测试全绿：

```
test_a_malformed_auction_is_ignored_without_an_error ... ok
test_a_valid_auction_reaches_the_advisor ... ok
test_no_or_empty_auction_is_the_same_as_none ... ok
test_the_advisor_accepts_an_auction ... ok
Ran 4 tests ... OK
```

### 切片 2：加权发牌（`AuctionSamplingTest`）

先写测试，跑红：

```
AttributeError: 'Advisor' object has no attribute 'opening_strength'
AttributeError: 'Advisor' object has no attribute 'sample_hands'
Ran 3 tests ... FAILED (errors=3)
```

实现 `opening_strength` + 带权拒绝采样后绿（断言：地主叫 3 分 + 加倍时，200 次发牌的地主牌力均值比不加权高 > 0.05；无 auction 同种子逐手相同；自己的证据不参与）：

```
test_landlord_bid_and_double_push_the_deal_towards_a_stronger_landlord ... ok
test_my_own_bid_does_not_weight_the_deal ... ok
test_without_auction_the_same_seed_deals_the_same_hands ... ok
Ran 3 tests ... OK
```

实测（seed 1 第 10 手、我是地主下家）：不加权地主牌力均值 2.584，加权 3.016，差 0.432。

### 切片 3：现场模拟（`AuctionRolloutTest`）

先把 `close_rollout` 临时改回原来的 `sample_hands`（模拟「还没接上」），跑红：

```
FAIL: test_a_landlord_who_bid_three_and_doubled_lowers_my_rollout_win_rate
AssertionError: 0.65 not less than 0.6
Ran 1 test ... FAILED (failures=1)
```

接上带权发牌后绿（固定局数、只断言方向）：

```
test_a_landlord_who_bid_three_and_doubled_lowers_my_rollout_win_rate ... ok
Ran 1 test ... OK
```

同一个候选 `3 4 5 6 7` 的模拟赢面：不加权 0.65 → 加权 0.42。

### 切片 4：残局似然（`AuctionEndgameTest`）

先写测试，跑红（`likelihood` 还忽略 auction，两种分法权重都是 1.0）：

```
FAIL: test_a_strong_landlord_split_gets_more_weight_without_any_history
AssertionError: 1.0 not greater than 1.0 : 地主叫 3 分 + 加倍时，地主牌强的分法权重应该更高
Ran 3 tests ... FAILED (failures=1)
```

实现后绿（空 history、只有 auction；另外验证自己的证据不算、叫 0/1/2 不算）：

```
test_a_strong_landlord_split_gets_more_weight_without_any_history ... ok
test_my_own_evidence_is_not_used ... ok
test_other_bid_levels_are_not_evidence ... ok
Ran 3 tests ... OK
```

### 全部新测试

```
Ran 11 tests in 17.800s
OK
```

## 标定

脚本：`tools/douzero/calibrate_auction.py`。随机发 4000 副牌（seed 20261117，地主 4000 手、两个农民座位共 8000 手），用各座位自己的模型算开局牌力，8 个进程 3.3 秒跑完：

```
landlord_q35 -0.9325  landlord_q50 -0.5206  landlord_q65 -0.0206  landlord_q80 0.5950
farmer_q50  -0.4287  farmer_q80   0.2370
AUCTION_BID_T = -0.0206   AUCTION_DOUBLE_T = {"landlord": 0.5950, "farmer": 0.2370}
AUCTION_TAU   = 0.4560    （地主 q35~q65 分位间距的一半）
边际概率: P(叫三分)=0.385, P(地主加倍)=0.242, P(农民加倍)=0.291
```

落进代码的常量（四舍五入）：

```python
AUCTION_MAX_TRIES = 8
AUCTION_BID_T = -0.021
AUCTION_DOUBLE_T = {"landlord": 0.595, "farmer": 0.237}
AUCTION_TAU = 0.456
```

- `T_bid` = 地主牌力 q65（叫三分的手牌约占 35%）。
- `T_dbl["landlord"]` = 地主 q80；`T_dbl["farmer"]` = 两个农民座位合起来的 q80（加倍约占 20%）。
- `τ` = 地主 q35~q65 间距的一半；logistic 较宽时边际概率会略高于 35% / 20%，门槛与方向不变。

## 耗时影响

同一台机器、正常的 2.5 秒模拟预算（不是固定局数）：

| 局面 | 不加权模拟局数 | 加权后模拟局数 | 变化 |
| --- | --- | --- | --- |
| seed 1 第 10 手（我农民，顶两手 2 个候选） | 110 / 106 / 111（均 110） | 83 / 81 / 84（均 82.7） | 少约 25% |
| `LATE`（我地主下家，后半局 8 个候选） | 244 / 237（均 240.5） | 115 / 115（均 115） | 少约 52% |

- 拒绝采样平均每次发牌试 **2.48** 次（83 次发牌共调用 206 次 `sample_hands`）。
- 纯发牌耗时：不加权约 0.02 ms/次，加权约 5.96 ms/次（2.48 次 × 一次前向）。
- 单手决策的总耗时不变：两端都卡在 2.5 秒模拟预算 / 3.2 秒总预算内。
- 残局似然：400 种分法、一个证据座位约 0.36 秒（造 400 个开局局面 0.07 秒 + 批量前向 0.29 秒）；实测的残局例子仍在 1.5 秒预算内（0.04~0.33 秒），预算不够时按原设计退回不加权。

## 跑过的测试

```bash
cd tools/douzero
DOUZERO_WEIGHTS=/Users/zky/code/mika/tools/douzero/baselines/douzero_ADP \
  /Users/zky/code/mika/tools/douzero/.venv/bin/python -m unittest \
  test_server.AuctionProtocolTest test_server.AuctionSamplingTest \
  test_server.AuctionRolloutTest test_server.AuctionEndgameTest -v
# Ran 11 tests ... OK

DOUZERO_WEIGHTS=... .venv/bin/python -m unittest \
  test_server.EndgameTest test_server.CloseCallRolloutTest test_server.ActBudgetTest \
  test_server.HalvingScheduleTest test_server.ServerTest -v
# Ran 29 tests in 46.125s ... OK
```

（没有跑整个 `test_server`。）

## 已知限制 / 没做

- 手机端（Kotlin）这次不改，也认不出对手叫了几分 / 加没加倍，所以上线后要等下一单手机开始发 `auction`；服务端已经准备好，不带字段时行为不变。
- 牌力度量用的是 DouZero 模型对开局所有合法出牌的最高分。发现一个模型外推的边界情况：20 张牌里几乎全是 2 / A / K / Q（例如四张 2 + 四张 A + 四张 K + 四张 Q）时，模型在真实 `GameEnv` 开局也给出 -157 这种离谱分数，会把「极强牌」误判成弱牌。这种牌出现概率极低，目标场景（王炸 / 炸弹 + 高牌）在正常范围内（实测强牌 3.8~8.0）；如果以后要处理，可以换成一个更便宜的单调计数度量（例如按高牌 / 炸弹加权的点数），但这次按任务要求保留 DouZero 度量。
- 残局似然在预算不够时会连同 auction 证据一起退回不加权（沿用原来 `Timeout` 的兜底），不是只丢掉出牌似然。
- `close_rollout` 现在需要 `Advisor` 注入的 `agent.advisor`（`Advisor.__init__` 里填）；直接裸造 `Agent` 再带 auction 调 `close_rollout` 不属于公开路径。
