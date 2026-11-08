# DouZero 出牌服务

给 `phone_ddz_turn` 用的本机出牌建议服务，基于 [DouZero](https://github.com/kwai/DouZero)（Apache-2.0）。
只用于斗地主经典版的单机模式。

## 搭建

```bash
cd tools/douzero
uv venv --python python3.14 .venv
uv pip install --python .venv/bin/python douzero==1.1.0 gitpython torch
```

权重不进仓库。官方 Google Drive 目录已失效，ADP 版取自 Hugging Face 的 `palemoky/douzero-baselines`；
它的 WP 版与 `tianqiraf/DouZero_For_HappyDouDiZhu` 里的逐字节一致，据此认为是官方原版的镜像。

```bash
mkdir -p baselines/douzero_ADP
for s in landlord landlord_down landlord_up; do
  curl -L -o baselines/douzero_ADP/$s.ckpt \
    https://huggingface.co/palemoky/douzero-baselines/resolve/main/checkpoints/douzero_ADP/$s.ckpt
done
shasum -a 256 baselines/douzero_ADP/*.ckpt
```

SHA-256 前 16 位应为：`landlord` 6f2971813495e9c5，`landlord_down` c173dbe30258f19b，`landlord_up` 7fb7c495ff095db2。
权重按纯张量加载（`weights_only=True`），不执行 checkpoint 里的代码。

## 运行与测试

```bash
.venv/bin/python -I server.py 3426        # 监听局域网 3426 端口,没有鉴权,只在家里的网络用
.venv/bin/python -m unittest test_server   # 只凭可见信息重建的局面,出牌要与完整局面一致
```

`POST /act` 的参数见 `server.py` 开头。识别到的出牌历史在 DouZero 的牌局里重放，对不上就返回
`history_mismatch` 一类的错误，调用方把这一手交还模型。

三家手里合起来剩 20 张以内时，`endgame.py` 把别家牌的各种分法摆开、按明牌算到底，有赢面更大的一手就换掉 DouZero 的选择
（1.5 秒算不完就照用 DouZero）。算不了时还有两条硬规则：地主只剩一张、我是最后能管的农民就要管；队友只剩三张以内不压队友。
