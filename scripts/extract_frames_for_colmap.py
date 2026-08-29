"""从 Rokid 眼镜 session 抽帧，为 COLMAP Sequential SfM 准备输入。

选帧策略：按时间均匀间隔（默认每 15 帧一张，60fps 下 = 0.25s/张），
控制总量到 200 张左右。太多 SfM 慢，太少匹配不足。

输出目录：<session>/colmap/images/frame_XXXXXX.jpg
同时生成 <session>/colmap/timestamps.txt 记录每张图对应的 elapsed_realtime_ns，
后续 pose 可以按时间戳对齐。
"""
import os
import shutil
import numpy as np
import cv2

SESSION = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260710_154536_478"
VIDEO   = os.path.join(SESSION, "video.mp4")
FRAMES_CSV = os.path.join(SESSION, "frames.csv")

STRIDE = 15                # 每 N 帧抽 1
JPEG_QUALITY = 92
OUT_DIR = os.path.join(SESSION, "colmap", "images")


def main():
    if os.path.exists(OUT_DIR):
        shutil.rmtree(OUT_DIR)
    os.makedirs(OUT_DIR, exist_ok=True)

    # 加载 frames.csv 以对齐每帧时间戳
    frames = np.loadtxt(FRAMES_CSV, delimiter=",", skiprows=1, dtype=np.int64)
    # 使用 elapsed_realtime_ns（列 5）作 IMU 同步时基
    ts_all = frames[:, 5]

    cap = cv2.VideoCapture(VIDEO)
    if not cap.isOpened():
        raise RuntimeError(f"cannot open {VIDEO}")
    fps = cap.get(cv2.CAP_PROP_FPS)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    print(f"video: {VIDEO}  fps={fps:.2f}  total_frames={total}")
    print(f"frames.csv rows: {len(ts_all)}")

    ts_out_path = os.path.join(SESSION, "colmap", "timestamps.txt")
    kept = []
    idx = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if idx % STRIDE == 0 and idx < len(ts_all):
            name = f"frame_{idx:06d}.jpg"
            cv2.imwrite(os.path.join(OUT_DIR, name),
                        frame, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
            kept.append((name, int(ts_all[idx])))
        idx += 1
    cap.release()

    with open(ts_out_path, "w", encoding="utf-8") as f:
        f.write("filename\telapsed_realtime_ns\n")
        for name, ts in kept:
            f.write(f"{name}\t{ts}\n")
    print(f"\n共保存 {len(kept)} 张到: {OUT_DIR}")
    print(f"时间戳表: {ts_out_path}")
    if kept:
        span_s = (kept[-1][1] - kept[0][1]) / 1e9
        print(f"时长跨度: {span_s:.2f}s   平均间隔: {span_s / max(1, len(kept)-1)*1000:.1f}ms")


if __name__ == "__main__":
    main()
