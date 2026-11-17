# 四川麻将出牌服务

给 Mika 的安卓端用的本机出牌建议服务：四川麻将血战到底，只有万、筒、条三门，没有字牌/花牌，
副露只有碰和杠（血战没有吃）。

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
.venv/bin/python -I server.py 3427        # 监听 0.0.0.0:3427,没有鉴权,只在家里的局域网用
.venv/bin/python -m unittest test_server  # 只跑这个目录的测试
```

`POST /discard` 的请求、成功返回、错误码见 `server.py` 开头。例：

```bash
curl -s http://127.0.0.1:3427/discard \
  -H 'Content-Type: application/json' \
  -d '{"hand":["1m","2m","3m","4m","5m","6m","7m","8m","9m","1p","1p","4p","6p","9p"],"que":"s"}'
# {"win": false, "discard": "9p", "shanten": 0, "ukeire": 4, "waits": ["5p"], "queLeft": 0, "options": [...]}
```

## 注意

- 只在家里的局域网用，没有鉴权；不要暴露到公网。
- `waits` / `ukeire` 是理论进张：只按自己的手牌和副露扣，不扣场上已经打出去的牌。
- 校验错误（`bad_tile`、`bad_que`、`bad_meld`、`not_drawn`、`wrong_count`、`too_many`）
  用 **HTTP 200** + `{"error","detail"}` 返回；只有请求体不是 JSON、路径不对才用 4xx/404。
  安卓端把非 2xx 当成连不上。
- 有定缺牌时先切定缺牌；返回的 `shanten` / `ukeire` 只按非定缺门的牌算，`queLeft` 是切牌前
  手里还有几张定缺牌。
- 七对按「4 张相同算 2 对」；有副露时不考虑七对。
