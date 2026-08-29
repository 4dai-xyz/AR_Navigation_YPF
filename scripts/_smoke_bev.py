"""对几个代表帧跑 seg + depth + BEV，生成 4 列预览图：
原图 | 分割叠加 | 深度 | 合成(含 BEV 雷达图小窗)
"""
import os
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import inference_bev as ib  # noqa: E402

INPUT_ROOT = r"H:\ForPengfei\04142024\Front"
OUT_DIR    = r"H:\ForPengfei\04142024\Front\output\_smoke_bev"
SAMPLES = [
    ("1", 500),
    ("1", 12000),
    ("2", 5000),
    ("3", 500),    # 大量行人帧（19% person 像素）
    ("3", 5000),
    ("3", 12000),
]


def first_video(sub):
    d = os.path.join(INPUT_ROOT, sub)
    if not os.path.isdir(d):
        return None
    for f in sorted(os.listdir(d)):
        if f.lower().endswith(".mp4") and not f.startswith("._"):
            return os.path.join(d, f)
    return None


def grab(p, idx):
    cap = cv2.VideoCapture(p)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    cap.set(cv2.CAP_PROP_POS_FRAMES, min(idx, max(0, total - 1)))
    ok, f = cap.read()
    cap.release()
    if not ok:
        return None
    # 与 inference_bev.OUTPUT_MAX_LONG_SIDE 行为对齐
    h, w = f.shape[:2]
    if max(w, h) > ib.OUTPUT_MAX_LONG_SIDE:
        s = ib.OUTPUT_MAX_LONG_SIDE / max(w, h)
        f = cv2.resize(f, (int(round(w * s) / 2) * 2, int(round(h * s) / 2) * 2),
                       interpolation=cv2.INTER_AREA)
    return f


def label(img, text):
    out = img.copy()
    cv2.rectangle(out, (0, 0), (520, 60), (0, 0, 0), -1)
    cv2.putText(out, text, (10, 45), cv2.FONT_HERSHEY_SIMPLEX, 1.2,
                (0, 255, 255), 3)
    return out


def depth_to_color(d):
    d_min, d_max = float(d.min()), float(d.max())
    n = ((d - d_min) / (d_max - d_min + 1e-6) * 255).astype(np.uint8)
    return cv2.applyColorMap(n, cv2.COLORMAP_INFERNO)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for sub, idx in SAMPLES:
        v = first_video(sub)
        if not v:
            print(f"sub{sub}: 无视频"); continue
        frame = grab(v, idx)
        if frame is None:
            print(f"sub{sub} frame{idx}: 读取失败"); continue
        print(f"-- sub{sub} frame{idx}")

        t = time.time()
        pred = ib.segment_frame(frame); t_seg = (time.time() - t) * 1000
        t = time.time()
        depth = ib.estimate_depth(frame); t_dep = (time.time() - t) * 1000
        t = time.time()
        overlay = ib.colorize(frame, pred)
        bev = ib.compute_bev(pred, depth)
        composed = ib.composite_bev(overlay, bev)
        t_post = (time.time() - t) * 1000

        print(f"   seg={t_seg:.1f}ms  depth={t_dep:.1f}ms  post={t_post:.1f}ms")

        dcol = depth_to_color(depth)
        h = frame.shape[0]
        sep = np.full((h, 6, 3), 255, dtype=np.uint8)
        row = np.hstack([
            label(frame,    f"sub{sub} f{idx} ORIG"),
            sep,
            label(overlay,  "SEG"),
            sep,
            label(dcol,     "DEPTH"),
            sep,
            label(composed, "+BEV"),
        ])
        out = os.path.join(OUT_DIR, f"sub{sub}_f{idx:05d}_bev.jpg")
        cv2.imwrite(out, row)
        print(f"   写入: {out}")


if __name__ == "__main__":
    main()
