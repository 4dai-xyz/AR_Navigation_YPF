"""单帧冒烟测试：加载 SegFormer 模型，对第一个视频的第一帧做推理，输出预览图。"""
import os
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# 复用主脚本的模型加载与上色逻辑
import inference_segformer as ifs  # noqa: E402

INPUT_DIR = r"H:\ForPengfei\04142024\Front\1"
PREVIEW_OUT = r"H:\ForPengfei\04142024\Front\output\_smoke_segformer.jpg"


def main():
    files = sorted(
        f for f in os.listdir(INPUT_DIR)
        if f.lower().endswith(".mp4") and not f.startswith("._")
    )
    if not files:
        print("无 mp4 输入"); return
    video = os.path.join(INPUT_DIR, files[0])
    print(f"[Smoke] 视频: {video}")

    cap = cv2.VideoCapture(video)
    # 跳到第 100 帧取一帧更有内容的画面（开头常是黑屏/手部）
    cap.set(cv2.CAP_PROP_POS_FRAMES, 100)
    ok, frame = cap.read()
    cap.release()
    if not ok:
        print("读取失败"); return
    print(f"[Smoke] 帧尺寸: {frame.shape}")

    t0 = time.time()
    pred = ifs.segment_frame(frame)
    t1 = time.time()
    print(f"[Smoke] 推理耗时: {(t1-t0)*1000:.1f} ms")

    # 打印类别分布
    uniq, cnt = np.unique(pred, return_counts=True)
    total = pred.size
    print("[Smoke] 类别像素占比:")
    for u, c in sorted(zip(uniq, cnt), key=lambda x: -x[1])[:8]:
        print(f"  class {int(u):2d}  {c/total*100:5.2f}%")

    overlay = ifs.colorize(frame, pred)
    os.makedirs(os.path.dirname(PREVIEW_OUT), exist_ok=True)
    cv2.imwrite(PREVIEW_OUT, overlay)
    print(f"[Smoke] 预览图已写入: {PREVIEW_OUT}")


if __name__ == "__main__":
    main()
