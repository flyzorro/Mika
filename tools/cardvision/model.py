"""牌角检测模型:torchvision Faster R-CNN + MobileNetV3(BSD-3)。训练和服务共用。"""
import torch
import torchvision
from torchvision.transforms.functional import to_tensor

# PASS 是牌桌上「不出」的气泡(半透明底、白字),和牌一起认:谁不出看它在哪家头上
CLASSES = ["3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "SJ", "BJ", "PASS"]


def device():
    return torch.device("mps" if torch.backends.mps.is_available() else "cpu")


def build(pretrained_backbone=True):
    # 按原图尺寸检测:底牌的点数只有约 31 像素高,缩小了认不清
    return torchvision.models.detection.fasterrcnn_mobilenet_v3_large_fpn(
        weights=None,
        weights_backbone="IMAGENET1K_V1" if pretrained_backbone else None,
        num_classes=len(CLASSES) + 1,
        min_size=1080,
        max_size=2420,
    )


def load(path):
    ckpt = torch.load(path, map_location="cpu", weights_only=True)
    assert ckpt["classes"] == CLASSES, ckpt["classes"]
    m = build(pretrained_backbone=False)
    m.load_state_dict(ckpt["model"])
    return m.to(device()).eval()


@torch.no_grad()
def predict(model, image, min_score=0.5):
    """image: PIL RGB。返回 [{"rank", "box": [x0, y0, x1, y1], "score"}],按 y、x 排好。"""
    out = model([to_tensor(image).to(device())])[0]
    found = [
        {"rank": CLASSES[int(c) - 1], "box": [round(v, 1) for v in b.tolist()], "score": round(float(s), 3)}
        for b, c, s in zip(out["boxes"].cpu(), out["labels"].cpu(), out["scores"].cpu())
        if s >= min_score
    ]
    return sorted(found, key=lambda d: (d["box"][1] // 20, d["box"][0]))
