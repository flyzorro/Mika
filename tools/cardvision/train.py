"""训练牌角检测。数据集是 YOLO 格式(images/、labels/、classes.txt),可以给多个。

    python -I train.py <输出.pt> <数据集目录>... [--epochs 20]

按帧序号每 20 帧一块,每 7 块抽 1 块做验证:相邻帧几乎一样,随机切会把答案漏进训练集;只留最后一段又全是结算页。
验证指标:框的查准/查全(IoU>0.5 且点数对),以及有牌的帧里点数集合完全正确的比例。
"""
import argparse
import hashlib
import math
import os
import random
import sys
from collections import Counter

import torch
from PIL import Image
from torchvision.transforms import ColorJitter
from torchvision.transforms.functional import to_tensor
from torchvision.ops import box_iou

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I 不把脚本目录放进 sys.path
import model as M  # noqa: E402

BLOCK, VAL_EVERY = 20, 7
WARMUP = 300


class Frames(torch.utils.data.Dataset):
    def __init__(self, items, augment):
        self.items, self.augment = items, augment
        # 刚出的牌泛暖黄、底牌动画时压暗偏蓝:训练时抖亮度和色调。不翻转,字是有方向的
        self.jitter = ColorJitter(brightness=0.35, contrast=0.3, saturation=0.3, hue=0.04)

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        img_path, label_path = self.items[i]
        im = Image.open(img_path).convert("RGB")
        if self.augment:
            im = self.jitter(im)
        w, h = im.size
        boxes, labels = [], []
        for line in open(label_path):
            c, cx, cy, bw, bh = line.split()
            cx, cy, bw, bh = float(cx) * w, float(cy) * h, float(bw) * w, float(bh) * h
            boxes.append([cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2])
            labels.append(int(c) + 1)
        return to_tensor(im), {
            "boxes": torch.tensor(boxes, dtype=torch.float32).reshape(-1, 4),
            "labels": torch.tensor(labels, dtype=torch.int64),
        }


def collate(batch):
    return tuple(zip(*batch))


def split(datasets):
    train, val = [], []
    for d in datasets:
        assert open(os.path.join(d, "classes.txt")).read().split() == M.CLASSES, d
        names = sorted(os.path.splitext(f)[0] for f in os.listdir(os.path.join(d, "images")))
        items = [(os.path.join(d, "images", n + ".png"), os.path.join(d, "labels", n + ".txt")) for n in names]
        for i, item in enumerate(items):
            (val if (i // BLOCK) % VAL_EVERY == VAL_EVERY // 2 else train).append(item)
    return train, val


@torch.no_grad()
def evaluate(model, data):
    model.eval()
    tp = fp = fn = exact = framed = 0
    for img, target in data:
        out = model([img.to(M.device())])[0]
        keep = out["scores"].cpu() >= 0.5
        pb, pl = out["boxes"].cpu()[keep], out["labels"].cpu()[keep]
        tb, tl = target["boxes"], target["labels"]
        matched = set()
        if len(pb) and len(tb):
            iou = box_iou(pb, tb)
            for i in range(len(pb)):
                j = int(iou[i].argmax())
                if iou[i, j] > 0.5 and pl[i] == tl[j] and j not in matched:
                    matched.add(j)
        tp += len(matched)
        fp += len(pb) - len(matched)
        fn += len(tb) - len(matched)
        if len(tl):  # 没牌的帧(结算、过场)不算:空对空不说明什么
            framed += 1
            exact += Counter(pl.tolist()) == Counter(tl.tolist())
    return {"precision": round(tp / max(tp + fp, 1), 4), "recall": round(tp / max(tp + fn, 1), 4),
            "exact_frames": round(exact / max(framed, 1), 4), "frames_with_cards": framed}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("datasets", nargs="+")
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch", type=int, default=2)
    args = ap.parse_args()
    torch.manual_seed(0)
    random.seed(0)
    train_items, val_items = split(args.datasets)
    print(f"train {len(train_items)} frames, val {len(val_items)} frames, device {M.device()}")
    loader = torch.utils.data.DataLoader(Frames(train_items, True), batch_size=args.batch, shuffle=True,
                                         num_workers=2, collate_fn=collate)
    val = Frames(val_items, False)
    net = M.build().to(M.device())
    params = [p for p in net.parameters() if p.requires_grad]
    opt = torch.optim.SGD(params, lr=0.005, momentum=0.9, weight_decay=1e-4)
    # 不预热直接上全学习率,第一个 epoch 里就会梯度爆炸成 NaN(2026-11-21 实测)
    total_steps = args.epochs * len(loader)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / WARMUP) * 0.5 * (1 + math.cos(math.pi * s / total_steps)))
    for epoch in range(args.epochs):
        net.train()
        total, skipped = 0.0, 0
        for imgs, targets in loader:
            imgs = [i.to(M.device()) for i in imgs]
            targets = [{k: v.to(M.device()) for k, v in t.items()} for t in targets]
            loss = sum(net(imgs, targets).values())
            if not torch.isfinite(loss):
                skipped += 1
                continue
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(params, 10.0)
            opt.step()
            sched.step()
            total += loss.item()
        print(f"epoch {epoch + 1}/{args.epochs} loss {total / len(loader):.4f} skipped {skipped} val {evaluate(net, val)}", flush=True)
    torch.save({"model": net.state_dict(), "classes": M.CLASSES}, args.out)
    print("saved", args.out, "sha256", hashlib.sha256(open(args.out, "rb").read()).hexdigest()[:16])


if __name__ == "__main__":
    main()
