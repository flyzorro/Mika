# 四川麻将出牌服务

给 Mika 的安卓端用的本机出牌/定缺建议服务：四川麻将血战到底，只有万、筒、条三门，没有字牌/花牌，
副露只有碰和杠（血战没有吃）。

服务不再只按「向听小 → 进张多」推荐，而是按**期望得分**：每种打法对「快胡 / 清一色 / 碰碰胡 / 七对」
各估一次「能胡的概率 × 胡了值几倍」，取期望最高的目标。该冲清一色、七对、碰碰胡时就冲，
该快胡时就快胡。

普通型向听用 [`mahjong`](https://pypi.org/project/mahjong/) 2.2.0（MIT，日麻规则，记法 man/pin/sou）；
七对（含龙七对）、定缺、进张过滤和合法性校验都是这里自己写的。没有拷贝或 import
`AR-Mahjong-Assistant` 的代码。

## 搭建

```bash
cd tools/mahjong
uv venv --python python3.14 .venv
uv pip install --python .venv/bin/python mahjong==2.2.0
```

## 运行与测试

```bash
.venv/bin/python -I server.py 3428        # 监听 0.0.0.0:3428,没有鉴权,只在家里的局域网用
.venv/bin/python -m unittest test_server  # 只跑这个目录的测试
```

端口默认 **3428**（3427 被本机的 DouZero 服务占着）。

`POST /discard` 的请求、成功返回、错误码见 `server.py` 开头。例：

```bash
curl -s http://127.0.0.1:3428/discard \
  -H 'Content-Type: application/json' \
  -d '{"hand":["1m","2m","2m","3m","4m","5m","6m","7m","8m","9m","9m","5s","6s","7s"],"que":"p"}'
# {"win":false,"discard":"5s","target":"qingyise","fan":2,"value":0.62,
#  "shanten":2,"ukeire":25,"waits":["1m",...],"queLeft":0,"options":[...]}
```

`POST /que` 是开局的定缺建议（13 张；庄家 14 张）：

```bash
curl -s http://127.0.0.1:3428/que \
  -H 'Content-Type: application/json' \
  -d '{"hand":["1m","2m","3m","4m","5m","6m","7m","2p","3p","4p","1s","5s","9s"]}'
# {"que":"s","options":[{"que":"s","target":"fast","value":0.35},
#                       {"que":"p","target":"fast","value":0.08},
#                       {"que":"m","target":"qidui","value":0.0}]}
```

`draws` 是「自己还能摸几张」，1–30，默认 8；不带就是 8。返回里每个 option 和顶层都有：

- `target`：`fast`（快胡，普通型或七对）、`qingyise`（清一色）、`pengpeng`（碰碰胡）、`qidui`（七对）；
- `fan`：按 JJ麻将规则页算的番数（牌型番 + 清一色 + 断幺九 + 根）；
- `value`：期望倍数 = 赢牌概率 × 番数倍数（0 番 1 倍、1 番 2 倍、2 番 4 倍、3 番 8 倍，之后每番 +4）。

## 概率表

`prob_table.json` 是离线校准出来的表（向听 × 进张 × 还能摸几张 → 赢牌概率，千分比），
服务启动时只读表，不现场跑模拟。校准脚本是 `calibrate_prob.py`：

```bash
.venv/bin/python calibrate_prob.py --hands 12000 --seed 20261117
# 约 100 秒;--raw 输出不做平滑下限的纯模拟系数
```

脚本开头写清了模型假设、随机种子、样本量和「多向听平滑下限」的原因。
`C[s]` 不随向听增大而增大，所以同一进张、同一 draws 下，向听越少概率不会变小。

## 注意

- 只在家里的局域网用，没有鉴权；不要暴露到公网。
- `waits` / `ukeire` 是理论进张：只按自己的手牌和副露扣，不扣场上已经打出去的牌。
- 校验错误（`bad_tile`、`bad_que`、`bad_meld`、`not_drawn`、`wrong_count`、`too_many`、`bad_draws`）
  用 **HTTP 200** + `{"error","detail"}` 返回；只有请求体不是 JSON、路径不对才用 4xx/404。
  安卓端把非 2xx 当成连不上。
- 有定缺牌时先切定缺牌；返回的 `shanten` / `ukeire` 只按非定缺门的牌算，`queLeft` 是切牌前
  手里还有几张定缺牌。
- 七对按「4 张相同算 2 对」；有副露时不考虑七对。
- 概率是按「牌墙随机、摸到有用的就留下」的贪心打法模拟校准的；多向听目标加了单调平滑下限，
  否则 2 向听的清一色在 8 摸内只有约 6% 的纯赢面，会输给马上听牌的 1 倍小胡，和产品要的
  「该冲清一色」冲突。具体见 `calibrate_prob.py` 开头。
- 第一版的「按向听/进张推荐」已经改成期望；同一手牌推荐可能不同（例如 `1m-9m + 11p 4p 6p 9p`
  现在推荐切 `6p` 拼 32 张进张，而不是切 `9p` 单钓 5p）。
