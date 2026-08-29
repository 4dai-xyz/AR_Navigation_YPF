"""同时加载 Cityscapes 与 ADE20K 模型，在选定帧上做三列并排对比。

输出：H:\\ForPengfei\\04142024\\Front\\output\\_compare\\sub{1|2|3}_frame{N}.jpg
布局：[原图 | Cityscapes 叠加 | ADE20K 叠加]
"""
import os

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")

import cv2
import torch
import numpy as np
from PIL import Image
from transformers import SegformerImageProcessor, SegformerForSemanticSegmentation

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
USE_FP16 = True
INFER_LONG_SIDE = 720

INPUT_ROOT = r"H:\ForPengfei\04142024\Front"
OUTPUT_DIR = r"H:\ForPengfei\04142024\Front\output\_compare"
SUBDIRS = ["1", "2", "3"]
FRAME_INDICES = [500, 5000, 12000]

# 类别映射
CITY_WALK   = {0, 1, 9}
CITY_PERSON = {11, 12}
ADE_WALK    = {3, 6, 9, 11, 13, 28, 29, 46, 52, 53, 54, 59, 96}
ADE_PERSON  = {12}

C_WALK, C_PERSON, C_OBST = (0, 255, 0), (0, 255, 255), (0, 0, 255)
A_WALK, A_PERSON, A_OBST = 0.35, 0.55, 0.20


def load(name_or_path):
    proc = SegformerImageProcessor.from_pretrained(name_or_path)
    m = SegformerForSemanticSegmentation.from_pretrained(name_or_path).to(DEVICE).eval()
    if USE_FP16 and DEVICE == "cuda":
        m = m.half()
    return proc, m


HERE = os.path.dirname(os.path.abspath(__file__))
print("[Init] Cityscapes b2 加载中 ...")
cs_proc, cs_model = load("nvidia/segformer-b2-finetuned-cityscapes-1024-1024")
print("[Init] ADE20K   b2 加载中 (本地路径) ...")
ade_proc, ade_model = load(os.path.join(HERE, "..", "models", "segformer-b2-ade20k"))
print(f"[Init] 完成, device={DEVICE}, fp16={USE_FP16}")


@torch.no_grad()
def segment(frame_bgr, proc, model):
    h, w = frame_bgr.shape[:2]
    rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
    scale = INFER_LONG_SIDE / max(h, w)
    if scale < 1.0:
        rgb_s = cv2.resize(rgb, (int(round(w * scale)), int(round(h * scale))),
                           interpolation=cv2.INTER_LINEAR)
    else:
        rgb_s = rgb
    inputs = proc(images=Image.fromarray(rgb_s), return_tensors="pt").to(DEVICE)
    if USE_FP16 and DEVICE == "cuda":
        inputs = {k: (v.half() if v.dtype == torch.float32 else v) for k, v in inputs.items()}
    logits = model(**inputs).logits
    up = torch.nn.functional.interpolate(logits, size=(h, w), mode="bilinear", align_corners=False)
    return up.argmax(dim=1)[0].cpu().numpy().astype(np.int32)


def colorize(frame_bgr, pred, walk_ids, person_ids):
    out = frame_bgr.copy()
    walk = np.isin(pred, list(walk_ids))
    person = np.isin(pred, list(person_ids))
    obst = ~(walk | person)
    for region, color, a in (
        (walk, C_WALK, A_WALK),
        (obst, C_OBST, A_OBST),
        (person, C_PERSON, A_PERSON),
    ):
        if not region.any():
            continue
        out[region] = (out[region].astype(np.float32) * (1 - a)
                       + np.array(color, dtype=np.float32) * a).astype(np.uint8)
    return out


def label_image(img, text):
    out = img.copy()
    cv2.rectangle(out, (0, 0), (640, 70), (0, 0, 0), -1)
    cv2.putText(out, text, (15, 50),
                cv2.FONT_HERSHEY_SIMPLEX, 1.4, (0, 255, 255), 3)
    return out


def first_video(sub):
    d = os.path.join(INPUT_ROOT, sub)
    if not os.path.isdir(d):
        return None
    for f in sorted(os.listdir(d)):
        if f.lower().endswith(".mp4") and not f.startswith("._"):
            return os.path.join(d, f)
    return None


def grab(video_path, frame_idx):
    cap = cv2.VideoCapture(video_path)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    if total <= 0:
        cap.release(); return None
    cap.set(cv2.CAP_PROP_POS_FRAMES, min(frame_idx, max(0, total - 1)))
    ok, frame = cap.read()
    cap.release()
    return frame if ok else None


def top_str(pred):
    uniq, cnt = np.unique(pred, return_counts=True)
    top = sorted(zip(uniq, cnt), key=lambda x: -x[1])[:5]
    return ", ".join(f"{int(u)}:{c/pred.size*100:.0f}%" for u, c in top)


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    for sub in SUBDIRS:
        v = first_video(sub)
        if not v:
            print(f"跳过 {sub}：无视频")
            continue
        print(f"== {sub}: {v}")
        for fi in FRAME_INDICES:
            frame = grab(v, fi)
            if frame is None:
                print(f"  无法读取帧 {fi}")
                continue
            cs_pred = segment(frame, cs_proc, cs_model)
            ade_pred = segment(frame, ade_proc, ade_model)
            cs_ovl = colorize(frame, cs_pred, CITY_WALK, CITY_PERSON)
            ade_ovl = colorize(frame, ade_pred, ADE_WALK, ADE_PERSON)

            h = frame.shape[0]
            sep = np.full((h, 8, 3), 255, dtype=np.uint8)
            row = np.hstack([
                label_image(frame, f"sub{sub} frame{fi} ORIG"),
                sep,
                label_image(cs_ovl, "Cityscapes"),
                sep,
                label_image(ade_ovl, "ADE20K"),
            ])
            out = os.path.join(OUTPUT_DIR, f"sub{sub}_frame{fi:05d}.jpg")
            cv2.imwrite(out, row)
            print(f"  写入: {out}")
            print(f"    CS  top: {top_str(cs_pred)}")
            print(f"    ADE top: {top_str(ade_pred)}")


if __name__ == "__main__":
    main()
