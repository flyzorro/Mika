"""扑克牌角预标注:截图 -> 点数候选块 -> 按外形聚类 -> 人工给每类起名 -> YOLO 格式标注。

    python -I prelabel.py cluster <帧目录> <工作目录>          # 出 clusters.json 和 clusters.png
    (看 clusters.png,写 <工作目录>/names.json:{"类号": "点数"},噪声类不写)
    python -I prelabel.py label <帧目录> <工作目录> <数据集目录>  # 出 images/、labels/、classes.txt

点数候选块:牌面(亮、不饱和)里的红或黑墨迹,正下方紧挨着另一块(花色)。大小王下面没有花色,这里认不到,要另外补。
"""
import json
import os
from collections import Counter
import shutil
import sys
from multiprocessing import Pool

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage as ndi

# PASS 是牌桌上「不出」的气泡(半透明底、白字),和牌一起认:谁不出看它在哪家头上
CLASSES = ["3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "SJ", "BJ", "PASS"]
PASS_TEMPLATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "templates", "pass.png")
PASS_SCORE = 0.8
SAME = 0.85


def ink_boxes(a):
    # 牌面颜色随特效变:刚出的牌泛暖黄、底牌动画时压暗偏蓝。只看相对亮度:比周围最亮处暗得多、周围又是亮的,就是墨迹
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    lum = a.mean(-1)
    around = ndi.maximum_filter(lum, size=25)
    ink = (lum < 0.62 * around) & (around > 150)
    red = (r - np.maximum(g, b)) > 40
    lab, _ = ndi.label(ink)
    boxes = []
    for i, sl in enumerate(ndi.find_objects(lab), 1):
        y0, y1, x0, x1 = sl[0].start, sl[0].stop, sl[1].start, sl[1].stop
        if y1 - y0 < 12 or x1 - x0 < 3 or y1 - y0 > 160 or x1 - x0 > 120:
            continue
        boxes.append([x0, y0, x1, y1, bool(red[sl][lab[sl] == i].mean() > 0.5)])
    # 10:同一行、同颜色、左右紧挨的两块并成一块
    boxes.sort(key=lambda b: (b[1] // 10, b[0]))
    out = []
    for bx in boxes:
        hit = next((p for p in out if p[4] == bx[4] and abs(p[1] - bx[1]) < 6 and abs(p[3] - bx[3]) < 6
                    and -8 < bx[0] - p[2] < 8), None)
        if hit:
            hit[0], hit[2], hit[3] = min(hit[0], bx[0]), max(hit[2], bx[2]), max(hit[3], bx[3])
        else:
            out.append(bx)
    return out


def ranks(a):
    boxes = ink_boxes(a)
    found = []
    for b in boxes:
        h = b[3] - b[1]
        below = [s for s in boxes if s is not b and 0 < s[1] - b[3] < 0.6 * h
                 and min(s[2], b[2]) - max(s[0], b[0]) > 0.4 * (s[2] - s[0])]
        if below and h >= 20:
            found.append(b)
    return found


def jokers(a):
    """王的牌角是竖排的「J O K 大/小」,下面没有花色:找竖直对齐、大小相近的一串(至少 4 块)。红是大王,黑是小王。"""
    small = [b for b in ink_boxes(a) if 10 <= b[3] - b[1] <= 45 and 6 <= b[2] - b[0] <= 45]
    small.sort(key=lambda b: b[1])
    found, used = [], set()
    for i, top in enumerate(small):
        if i in used:
            continue
        chain, cx, h = [i], (top[0] + top[2]) / 2, top[3] - top[1]
        for j in range(i + 1, len(small)):
            b, last = small[j], small[chain[-1]]
            if b[4] == top[4] and abs((b[0] + b[2]) / 2 - cx) < 8 and 0 <= b[1] - last[3] < 0.8 * h \
                    and abs((b[3] - b[1]) - h) < 0.5 * h:
                chain.append(j)
        if len(chain) >= 4:
            used.update(chain)
            bs = [small[k] for k in chain]
            found.append(("BJ" if top[4] else "SJ",
                          [min(b[0] for b in bs), bs[0][1], max(b[2] for b in bs), bs[-1][3], top[4]]))
    return found


def passes(a):
    """「不出」气泡:白字笔画和模板比(相关系数,实测有气泡 0.9 以上、没有的都不到 0.6)。框住两个字。"""
    import cv2
    t = (np.asarray(Image.open(PASS_TEMPLATE)) > 127).astype(np.float32)
    m = (a.min(-1) > 215).astype(np.float32)
    r = cv2.matchTemplate(m, t, cv2.TM_CCOEFF_NORMED)
    found = []
    while True:
        _, v, _, (x, y) = cv2.minMaxLoc(r)
        if v < PASS_SCORE:
            return found
        found.append(("PASS", [x, y, x + t.shape[1], y + t.shape[0], False]))
        r[max(0, y - 40):y + 40, max(0, x - 80):x + 80] = -1


def frame_extras(path):
    a = load(path)
    return jokers(a) + passes(a)


def signature(a, b):
    x0, y0, x1, y1, _ = b
    dark = a[y0:y1, x0:x1].astype(float).mean(-1) < 150
    v = np.asarray(Image.fromarray(dark.astype(np.uint8) * 255).resize((20, 30)), float).ravel()
    v -= v.mean()
    n = np.linalg.norm(v)
    return v / n if n else v


def frames_in(d):
    return sorted(f for f in os.listdir(d) if f.lower().endswith((".png", ".jpg")))


def load(path):
    return np.asarray(Image.open(path).convert("RGB")).astype(int)


def glyphs(path):
    a = load(path)
    return [(b, signature(a, b)) for b in ranks(a)]


def cluster(frames, work):
    os.makedirs(work, exist_ok=True)
    items, cents = [], []
    names = frames_in(frames)
    with Pool() as pool:  # 逐帧找墨迹最慢,按核并行;聚类本身很快,照顺序做
        found = pool.map(glyphs, [os.path.join(frames, f) for f in names])
    for f, gs in zip(names, found):
        for b, s in gs:
            score, j = max(((float(s @ c["s"]), j) for j, c in enumerate(cents) if c["red"] == b[4]), default=(0, -1))
            if score > SAME:
                cents[j]["m"].append(len(items))
            else:
                cents.append({"s": s, "red": b[4], "m": [len(items)]})
            items.append((f, b))
    json.dump({"items": items, "clusters": [{"red": c["red"], "m": c["m"]} for c in cents],
               "centroids": [c["s"].tolist() for c in cents]}, open(os.path.join(work, "clusters.json"), "w"))
    cols = 12
    sheet = Image.new("RGB", (cols * 80, (len(cents) + cols - 1) // cols * 100), "white")
    draw = ImageDraw.Draw(sheet)
    for j, c in enumerate(cents):
        f, b = items[c["m"][0]]
        tile = Image.open(os.path.join(frames, f)).convert("RGB").crop((b[0] - 2, b[1] - 2, b[2] + 2, b[3] + 2))
        x, y = j % cols * 80, j // cols * 100
        sheet.paste(tile.resize((48, 64)), (x + 4, y + 30))
        draw.text((x + 2, y + 2), f"#{j} n{len(c['m'])}", fill="blue")
        draw.text((x + 2, y + 14), f"h{b[3] - b[1]}", fill="gray")
    sheet.save(os.path.join(work, "clusters.png"))
    print(f"{len(items)} glyphs, {len(cents)} clusters -> {work}/clusters.png")


def label(frames, work, dataset):
    data = json.load(open(os.path.join(work, "clusters.json")))
    names = json.load(open(os.path.join(work, "names.json")))
    cls_of = {}
    for j, c in enumerate(data["clusters"]):
        if str(j) in names:
            for i in c["m"]:
                cls_of[i] = CLASSES.index(names[str(j)])
    per_frame = {}
    for i, (f, b) in enumerate(data["items"]):
        if i in cls_of:
            per_frame.setdefault(f, []).append((cls_of[i], b))
    names_ = frames_in(frames)
    with Pool() as pool:
        joker_hits = pool.map(frame_extras, [os.path.join(frames, f) for f in names_])
    for f, hits in zip(names_, joker_hits):
        for rank, b in hits:
            per_frame.setdefault(f, []).append((CLASSES.index(rank), b))
    print("extras:", dict(Counter(r for hits in joker_hits for r, _ in hits)))
    os.makedirs(os.path.join(dataset, "images"), exist_ok=True)
    os.makedirs(os.path.join(dataset, "labels"), exist_ok=True)
    with open(os.path.join(dataset, "classes.txt"), "w") as fh:
        fh.write("\n".join(CLASSES) + "\n")
    n = 0
    for f in frames_in(frames):
        w, h = Image.open(os.path.join(frames, f)).size
        shutil.copy(os.path.join(frames, f), os.path.join(dataset, "images", f))
        with open(os.path.join(dataset, "labels", os.path.splitext(f)[0] + ".txt"), "w") as fh:
            for c, (x0, y0, x1, y1, _) in per_frame.get(f, []):
                fh.write(f"{c} {(x0 + x1) / 2 / w:.6f} {(y0 + y1) / 2 / h:.6f} {(x1 - x0) / w:.6f} {(y1 - y0) / h:.6f}\n")
                n += 1
    print(f"{n} boxes in {len(frames_in(frames))} frames -> {dataset}")


if __name__ == "__main__":
    cmd, *args = sys.argv[1:]
    {"cluster": cluster, "label": label}[cmd](*args)
