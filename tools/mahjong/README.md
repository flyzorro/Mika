# 四川麻将出牌服务

给 Mika 的安卓端用的本机出牌/定缺建议服务：四川麻将血战到底，只有万、筒、条三门，没有字牌/花牌，
副露只有碰和杠（血战没有吃）。除了给局面要建议的 `/discard`、`/que`，还有直接吃一张
JJ麻将主屏截图的 `/hint`：服务认出手牌、自己的碰杠、定缺、牌墙剩余和游戏的「荐」，再给建议。

服务不再只按「向听小 → 进张多」推荐，而是按**期望得分**：每种打法对「快胡 / 清一色 / 碰碰胡 / 七对」
各估一次「能胡的概率 × 胡了值几倍」，取期望最高的目标。该冲清一色、七对、碰碰胡时就冲，
该快胡时就快胡。

概率分两层：`prob_table.json` 只用向听/进张/draws 做**粗排**；排在前面的 4 个（切牌 × 目标）
再对这手牌本身做固定种子的蒙特卡洛（牌墙 = 108 − 自己的牌，按目标贪心切牌，看 draws 摸内
能不能胡），最终排序和返回的 `value` 用模拟结果。

普通型向听用 [`mahjong`](https://pypi.org/project/mahjong/) 2.2.0（MIT，日麻规则，记法 man/pin/sou）；
七对（含龙七对）、定缺、进张过滤和合法性校验都是这里自己写的。没有拷贝或 import
`AR-Mahjong-Assistant` 的代码。

## 搭建

```bash
cd tools/mahjong
uv venv --python python3.14 .venv
uv pip install --python .venv/bin/python mahjong==2.2.0 pillow numpy
```

`/hint` 要有 `pillow` 和 `numpy`（读 PNG、比模板）；`/discard` 和 `/que` 只用标准库。

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
  -d '{"hand":["1m","2m","2m","3m","4m","5m","6m","7m","8m","9m","9m","5s","6s","7s"],"que":"p","draws":14}'
# {"win":false,"discard":"5s","target":"qingyise","fan":2,"value":0.94,
#  "shanten":2,"ukeire":25,"waits":["1m",...],"queLeft":0,"options":[...]}

# 同一手牌 draws=6 时反过来推荐马上听牌的 2m:
# {"win":false,"discard":"2m","target":"fast","fan":0,"value":0.35,
#  "shanten":0,"ukeire":8,"waits":["3m","6m","9m"],...}
```

`POST /que` 是开局的定缺建议（13 张；庄家 14 张）：

```bash
curl -s http://127.0.0.1:3428/que \
  -H 'Content-Type: application/json' \
  -d '{"hand":["1m","2m","3m","4m","5m","6m","7m","2p","3p","4p","1s","5s","9s"]}'
# {"que":"s","options":[{"que":"s","target":"fast","value":0.09},
#                       {"que":"p","target":"fast","value":0.03},
#                       {"que":"m","target":"qidui","value":0.0}]}
```

`draws` 是「自己还能摸几张」，1–30，默认 8；不带就是 8。返回里每个 option 和顶层都有：

- `target`：`fast`（快胡，普通型或七对）、`qingyise`（清一色）、`pengpeng`（碰碰胡）、`qidui`（七对）；
- `fan`：按 JJ麻将规则页算的番数（牌型番 + 清一色 + 断幺九 + 根）；
- `value`：期望倍数 = 这手牌的蒙特卡洛赢牌率 × 番数倍数（0 番 1 倍、1 番 2 倍、2 番 4 倍、
  3 番 8 倍，之后每番 +4）。draws≤14 时每个候选 200 局，draws>14 时 100 局；`/que` 是 70/50 局。

`POST /hint` 直接收一张 **2412×1080 的整屏 PNG 或 JPEG**（base64，按文件头识别），
认局面再复用 `/discard`、`/que`：

```bash
# 手机上截一张主屏,转成 base64
BASE64=$(base64 -i 主屏.png | tr -d '\n')
curl -s http://127.0.0.1:3428/hint \
  -H 'Content-Type: application/json' \
  -d "{\"image\":\"$BASE64\"}"
# 轮到出牌:
# {"state":"turn","hand":["1m","2m","4m",...],"melds":[],"que":"p","left":39,
#  "draws":10,"jian":null,"advice":{"win":false,"discard":"1m",...}}
# 定缺阶段:
# {"state":"que_select","hand":[...],"advice":{"que":"p","options":[...]}}
```

`state` 有五种：

- `turn`：手牌张数 + 3 × 碰杠组数 = 14，且还没胡。`advice` 就是 `/discard` 的返回；
  `draws = max(1, ⌈left ÷ 4⌉)`；`jian` 是屏上橙色「荐」指的那张，没有就是 `null`。
- `win`：认出 14 张已经胡了，没有 `advice`。
- `que_select`：屏上有「请选择定缺的花色」，`advice` 是 `/que` 的返回。
- `wait`：不是用户出牌（别人出牌、动画、结算、大厅）。牌桌画面会带上认得出的
  `hand` / `melds` / `que` / `left`，大厅和结算只有 `state`。
- `unsure`：有一张牌、一组碰杠、定缺或牌墙剩余认不出；`reason` 是 `tile` / `meld` /
  `que` / `left`。这一手不给建议。认不出的牌面会存进 `unsure/`，攒够了补模板。

`/hint` 的错误用 HTTP 200 + `{"error","detail"}`：图片解不开或格式不是 PNG/JPEG
`bad_image`，分辨率不是 2412×1080 `unsupported_screen`。

### 认牌

- 手牌按固定格子切：右对齐到 x=2004，每张宽 123.5px；刚摸的那张单独在
  x=2043–2166；牌面 y=885–1057。**哪格有牌看模板得分，不看桌布颜色**：正常牌
  ≥0.90；空格子最高 0.52（新手场 0.21、500 场 0.26、结算 0.52），所以 ≥0.60 算有牌。
  被抬起的牌（玩家点选、碰/过提示）在 0–60px 内上下搜索（实测抬 30px、52px）。
  每张切成 40×64、减均值归一化，和 `templates/hand/` 里的模板点积，**≥ 0.90 才算认出**。
  模板是这台手机上截下来的 27 种牌面（三门各 9 张；认不出会返回 `unsure`）。
  缩放用 BILINEAR（比 LANCZOS 抗 JPEG 块效应）：测试集手牌最低分 PNG **0.9626**
  （绿桌布）/ **0.9873**（500 场紫灰桌布），q85 JPEG **0.9634** / **0.9867**。
  发黄光/带黄点的牌最低只有 **0.912–0.941**（`fixtures/near_miss/`），而空槽/桌面
  最高 0.515，所以阈值取 0.90，留 0.38 余量。
- 自己的碰杠在 x 456 起、每组 276px 宽：3 张白面是碰；中间抬起的是杠；抬起的牌
  只露 1 张正面、左右是绿牌背的是暗杠。牌面用 `templates/meld/` 的模板比：明面模板
  `flat_*` 现在覆盖 1p、4m、6m、6p、7m、8m（杠）、8s，暗杠模板 `ankong_*` 只覆盖 1m；
  没模板的牌返回 `unsure`（`reason` 为 `meld`）。
- 牌墙剩余是左上角绿框里的数字，用连通域切成 1–2 位，再和 `templates/digit/`
  （PNG）与 `templates/digit_jpeg/`（q85 JPEG）的 0–9 灰度模板比；JPEG 最低分
  **0.9905**，PNG **1.0**。
- 定缺优先读左下角头像旁的角标（`templates/que/` 里的万/筒/条），读不到再看手里
  哪门有粉色小猪头。
- 「荐」是 y 约 830–905 的橙色圆标，按 x 找它正下方的那张牌。
- 定缺阶段的牌会被黄光罩住，按固定牌面位置切，避开光晕。

测试集在 `fixtures/`：`frames/` 里 13 帧 2412×1080 整屏 PNG（碰、明杠、暗杠、「荐」、
定缺阶段、他人出牌、结算、大厅），`labels.json` 是人工标注，`4s_live.png` 是一张带定缺
小猪头的四条手牌牌面（和模板不是同一张裁图，专门验证模板能泛化）。`test_server.py` 里有
逐帧对照，以及分辨率/坏图/认不出/性能用例。

## 概率表

`prob_table.json` 是离线校准出来的粗排表（向听 × 进张 × 还能摸几张 → 赢牌概率，千分比），
服务启动时只读表，不现场跑大模拟。表只用纯模拟拟合结果，没有人为抬高/压低的下限。
校准脚本是 `calibrate_prob.py`：

```bash
.venv/bin/python calibrate_prob.py --hands 12000 --seed 20261117
# 约 100 秒
```

脚本开头写清了模型假设、随机种子和样本量。`C[s]` 不随向听增大而增大，所以同一进张、
同一 draws 下，向听越少概率不会变小。粗排之后的前 4 个候选还会做这手牌的蒙特卡洛，
表只决定「先模拟哪些候选」。

## 注意

- 只在家里的局域网用，没有鉴权；不要暴露到公网。
- `waits` / `ukeire` 是理论进张：只按自己的手牌和副露扣，不扣场上已经打出去的牌。
- 校验错误（`bad_tile`、`bad_que`、`bad_meld`、`not_drawn`、`wrong_count`、`too_many`、`bad_draws`）
  用 **HTTP 200** + `{"error","detail"}` 返回；只有请求体不是 JSON、路径不对才用 4xx/404。
  安卓端把非 2xx 当成连不上。
- 有定缺牌时先切定缺牌；返回的 `shanten` / `ukeire` 只按非定缺门的牌算，`queLeft` 是切牌前
  手里还有几张定缺牌。
- 七对按「4 张相同算 2 对」；有副露时不考虑七对。
- 概率按「牌墙随机、摸到有用的就留下、按目标贪心切牌」模拟：粗排表用 12000 手、seed 20261117；
  最终 `value` 用固定种子对这副牌模拟（draws≤14 200 局、>14 100 局），同一请求结果完全相同。
- 推荐会随剩牌数变化：同一手牌 `1m 2m 2m 3m 4m 5m 6m 7m 8m 9m 9m 5s 6s 7s`、定缺 `p`，
  `draws=14` 推荐切 `5s` 冲清一色（期望约 0.94 倍），`draws=6` 只推荐切 `2m` 马上听牌
  （期望约 0.35 倍）。交叉点大约在 11 摸。
