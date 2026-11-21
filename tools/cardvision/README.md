# 扑克牌角检测

从游戏截图里找出每张看得见的牌的点数（`3`–`10`、`J`、`Q`、`K`、`A`、`2`、`SJ`、`BJ`，不分花色），给 `tools/douzero` 的 `/cards` 用。
第一个用到它的是 JJ 斗地主（`cn.jj`）。模型和游戏无关，换一款游戏只需要补数据。

模型是 torchvision 的 Faster R-CNN + MobileNetV3（BSD-3），按原图尺寸检测，因为底牌的点数只有 30 像素左右高。

## 搭建

```bash
cd tools/cardvision
uv venv --python python3.14 .venv
uv pip install --python .venv/bin/python numpy scipy pillow opencv-python-headless torch==2.15.0 torchvision
```

截图、标注、权重都**不进仓库**（截图里有别家玩家），放在 `~/data/cardvision/`。

## 做数据

1. 录屏抽帧：
   `scrcpy --no-playback --no-control --no-audio --max-fps=30 --record=rec.mkv`，
   再 `ffmpeg -i rec.mkv -vf fps=2 frames/f%04d.png`。
2. 聚类：`.venv/bin/python -I prelabel.py cluster frames work`，得到 `work/clusters.png`。
   每类是一种点数在一种颜色下的样子，也有场景噪声。
3. 看图写 `work/names.json`（`{"类号": "点数"}`，噪声类不写），然后执行
   `.venv/bin/python -I prelabel.py label frames work ~/data/cardvision/<名字>`，得到 YOLO 格式的 `images/`、`labels/`、`classes.txt`。
4. 在 X-AnyLabeling 里打开数据集，检查、补漏。预标注认不到大小王（下面没有花色），底牌在动画压暗时也会漏标。

## 训练

```bash
.venv/bin/python -I train.py ~/data/cardvision/runs/<名字>.pt ~/data/cardvision/<数据集>... --epochs 20
```

每个数据集按帧序号留最后 15% 做验证。打印的指标有：框的查准率和查全率（IoU>0.5 且点数对才算对），以及整帧点数集合完全正确的比例。
训练完会打印权重 sha256 的前 16 位，写进 `tools/douzero/README.md`。
