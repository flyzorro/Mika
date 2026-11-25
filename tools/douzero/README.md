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

## 认牌 `/cards`（JJ 斗地主）

`POST /cards` 的请求体是一张截图的 JPEG 原始字节，返回 `{"cards": [{"rank", "box": [x0, y0, x1, y1], "score"}], "ms"}`：
截图里每张看得见的牌各一项（只有点数，不分花色）。模型在 `tools/cardvision`，要多装 torchvision 和 pillow：

```bash
uv pip install --python .venv/bin/python torch==2.15.0 torchvision==0.29.2 pillow
DDZ_CARDS_WEIGHTS=~/data/cardvision/runs/jj-v2.pt .venv/bin/python -I server.py 3426
```

权重不进仓库，按 `tools/cardvision/README.md` 训练。当前用的 `jj-v2.pt` 的 SHA-256 前 16 位是 `e340332e0a650188`。
没设 `DDZ_CARDS_WEIGHTS` 时 `/cards` 回 503 `cards_unavailable`，其余接口照常工作。
在 M3 Max 的 MPS 上，第一次请求要加载模型，约 1 秒；之后每张 2408×1080 的截图约 60ms。

`POST /act` 的参数见 `server.py` 开头。识别到的出牌历史在 DouZero 的牌局里重放，对不上就返回
`history_mismatch` 一类的错误，调用方把这一手交还模型。

出牌先过滤双王作带牌的非法动作，再取 DouZero 评分最高的一手。残局允许时，
`endgame.best` 用最多 2.5 秒按历史似然计算各出法的胜率。模型首选在最高胜率的 1 个百分点内就保留，
超出时改选该范围内模型评分最高的一手。搜索超时仍用模型首选。
