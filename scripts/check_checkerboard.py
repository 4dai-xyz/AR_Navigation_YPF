"""快速扫描标定视频，统计棋盘角点检测率。

用户使用 A4 棋盘。假设几种常见格局尝试检测，选检出率最高的。
"""
import cv2
import numpy as np
import os
import sys

SESSION = sys.argv[1] if len(sys.argv) > 1 else r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260707_180906_484"
VIDEO = os.path.join(SESSION, "video.mp4")
OUT_DIR = os.path.join(SESSION, "checkerboard_check")
os.makedirs(OUT_DIR, exist_ok=True)

# 尝试的棋盘内角点组合（Kalibr 常见）
BOARD_CANDIDATES = [
    (9, 6),   # 10x7 格 → 9x6 内角
    (8, 6),
    (7, 6),
    (8, 5),
    (7, 5),
    (6, 4),
]
SAMPLE_STRIDE = 30    # 每 30 帧检测一次（60fps → 0.5s 一采）


def try_detect(gray, size):
    ret, corners = cv2.findChessboardCorners(
        gray, size,
        flags=cv2.CALIB_CB_ADAPTIVE_THRESH | cv2.CALIB_CB_NORMALIZE_IMAGE | cv2.CALIB_CB_FAST_CHECK
    )
    return ret, corners


def main():
    cap = cv2.VideoCapture(VIDEO)
    if not cap.isOpened():
        sys.exit("cannot open video")
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    print(f"video frames: {total}   stride: {SAMPLE_STRIDE}")

    # 第一遍：在少数几帧上试每种 pattern，找最匹配
    print("\n阶段1: 找最佳内角点数（在均匀抽 10 帧上试）")
    probe_indices = np.linspace(total // 4, 3 * total // 4, 10, dtype=int)
    scores = {size: 0 for size in BOARD_CANDIDATES}
    for i in probe_indices:
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(i))
        ok, frame = cap.read()
        if not ok: continue
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        for size in BOARD_CANDIDATES:
            ret, _ = try_detect(gray, size)
            if ret:
                scores[size] += 1
    print("  尝试结果 (10 帧中命中):")
    for size, s in sorted(scores.items(), key=lambda x: -x[1]):
        print(f"    {size[0]}x{size[1]}  {s}/10")

    best_size = max(scores, key=scores.get)
    if scores[best_size] == 0:
        print("\n⚠️  所有常见规格都检测不到棋盘！可能：")
        print("    - 棋盘规格不在候选列表（请告知实际内角点数）")
        print("    - 棋盘反光 / 光线差 / 未在画面中")
        print("    - 棋盘不平整")
        return
    print(f"\n选用: {best_size}")

    # 第二遍：完整扫描
    print("\n阶段2: 全视频扫描检出率")
    cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
    detected = 0
    total_sampled = 0
    good_sample_frames = []
    idx = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if idx % SAMPLE_STRIDE == 0:
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            ret, corners = try_detect(gray, best_size)
            total_sampled += 1
            if ret:
                detected += 1
                if len(good_sample_frames) < 6:
                    # 亚像素精化后绘制
                    corners_ref = cv2.cornerSubPix(gray, corners, (11, 11), (-1, -1),
                                                   (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.01))
                    vis = frame.copy()
                    cv2.drawChessboardCorners(vis, best_size, corners_ref, ret)
                    cv2.putText(vis, f"frame {idx}", (20, 40),
                                cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 255, 255), 2)
                    fpath = os.path.join(OUT_DIR, f"detected_{idx:06d}.jpg")
                    cv2.imwrite(fpath, vis)
                    good_sample_frames.append(idx)
        idx += 1
    cap.release()
    rate = detected / total_sampled * 100 if total_sampled else 0
    print(f"  检出率: {detected}/{total_sampled}  ({rate:.1f}%)")
    print(f"  示例保存到: {OUT_DIR}")

    print("\n评估：")
    if rate >= 60:
        print("  ✅ 良好，可直接进入 Kalibr 标定")
    elif rate >= 30:
        print("  ⚠️  中等，建议标定时增加匹配观测但可尝试")
    else:
        print("  ❌ 太低，建议重录或换更大棋盘")


if __name__ == "__main__":
    main()
