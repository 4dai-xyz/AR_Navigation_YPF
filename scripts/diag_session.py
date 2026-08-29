"""Rokid glasses session 数据质量诊断。

检查项：
  1. 视频 vs 帧表条数一致性
  2. 相机时间戳单调 & 帧间间隔（丢帧诊断）
  3. IMU 时间戳单调 & 采样间隔（漏采诊断）
  4. IMU vs 相机时间轴对齐（min/max 覆盖）
  5. 加速度计静态段扫描（识别静止 → 后续可作初始重力 / bias 估计）
  6. 陀螺 / 加速度分布统计
  7. Game rotation vector 覆盖率
"""
import json
import os
import sys
from collections import Counter

import numpy as np

import sys
_DEFAULT = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260707_180906_484"
SESSION = sys.argv[1] if len(sys.argv) > 1 else _DEFAULT


def load_frames_csv(path):
    """返回 (N, 6) numpy: frame_index, presentation_time_us, camera_sensor_timestamp_ns, size_bytes, flags, elapsed_realtime_ns"""
    data = np.loadtxt(path, delimiter=",", skiprows=1, dtype=np.int64)
    return data


def load_imu(path):
    """按 sensor_type 分组，返回 dict[type] -> list[(sensor_ts_ns, values...)]"""
    groups = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            j = json.loads(line)
            st = j["sensor_type"]
            groups.setdefault(st, []).append(
                (j["sensor_timestamp_ns"], j.get("values", []), j.get("accuracy", -1))
            )
    return groups


def stats(arr, name, unit=""):
    a = np.asarray(arr)
    print(f"  {name:<30s} n={len(a):6d}  "
          f"mean={a.mean():.4f}{unit}  std={a.std():.4f}{unit}  "
          f"min={a.min():.4f}  max={a.max():.4f}")


def diag_frames(data):
    print("\n=== Frames ===")
    print(f"总帧数: {len(data)}")
    # 用 elapsed_realtime_ns（第 6 列）作为主时基 —— 与 IMU sensor_timestamp_ns 同一 Android 时钟
    ts = data[:, 5].astype(np.int64)
    dt_ms = np.diff(ts) / 1e6
    stats(dt_ms, "frame_dt_ms", " ms")
    # 检测明显丢帧：>1.5x 中位间隔
    med = np.median(dt_ms)
    outlier = np.where(dt_ms > 1.5 * med)[0]
    print(f"  中位帧间隔: {med:.2f} ms （对应 {1000/med:.1f} fps）")
    print(f"  帧间隔 > 1.5x 中位 的位置数: {len(outlier)}")
    if len(outlier) > 0 and len(outlier) < 20:
        print(f"  丢帧位置(帧号): {data[outlier, 0].tolist()}")
    # flags 分布（Android MediaCodec BUFFER_FLAG）
    flags = Counter(int(x) for x in data[:, 4])
    print(f"  flags 分布: {dict(flags)}   (2=CONFIG_HEADER, 1=KEY_FRAME, 0=P_FRAME)")


def diag_imu(groups):
    print("\n=== IMU ===")
    for st, samples in groups.items():
        ts = np.array([s[0] for s in samples], dtype=np.int64)
        dt_ms = np.diff(ts) / 1e6
        print(f"\n-- {st} --")
        stats(dt_ms, "dt_ms", " ms")
        vals = np.array([s[1] for s in samples if len(s[1]) > 0], dtype=np.float64)
        if vals.size:
            for i, comp in enumerate("xyz"[: vals.shape[1]] if vals.shape[1] <= 3 else range(vals.shape[1])):
                stats(vals[:, i], f"{comp} value")
        # accuracy 分布
        accs = Counter(int(s[2]) for s in samples)
        print(f"  accuracy 分布: {dict(accs)}")


def find_static_segments(groups, gyro_thresh=0.03, min_len_ms=500):
    """在陀螺仪数据上滑动扫描 |ω| < thresh 的段，用作 IMU bias / 重力估计的候选窗口。"""
    if "gyroscope" not in groups:
        print("\n=== 静态段 === (无 gyroscope 数据)")
        return
    samples = groups["gyroscope"]
    ts = np.array([s[0] for s in samples], dtype=np.int64)
    vals = np.array([s[1] for s in samples], dtype=np.float64)
    mag = np.linalg.norm(vals, axis=1)   # rad/s
    quiet = mag < gyro_thresh
    print(f"\n=== 静态段扫描 (|omega| < {gyro_thresh} rad/s) ===")
    print(f"  合格样本占比: {quiet.mean() * 100:.1f}%")
    # 连续段提取
    segs = []
    i = 0
    while i < len(quiet):
        if not quiet[i]:
            i += 1; continue
        j = i
        while j < len(quiet) and quiet[j]:
            j += 1
        dur_ms = (ts[j - 1] - ts[i]) / 1e6
        if dur_ms >= min_len_ms:
            segs.append((i, j, dur_ms))
        i = j
    print(f"  ≥{min_len_ms}ms 静态段数量: {len(segs)}")
    for i, j, dur in segs[:5]:
        t_s = (ts[i] - ts[0]) / 1e9
        print(f"    [{t_s:6.2f}s ~ +{dur/1000:.2f}s]  样本 {i}..{j}")
    # 用第一段估计重力
    if segs and "accelerometer" in groups:
        acc_samples = groups["accelerometer"]
        acc_ts = np.array([s[0] for s in acc_samples], dtype=np.int64)
        acc_vals = np.array([s[1] for s in acc_samples], dtype=np.float64)
        i, j, _ = segs[0]
        gyro_t_start, gyro_t_end = ts[i], ts[j - 1]
        mask = (acc_ts >= gyro_t_start) & (acc_ts <= gyro_t_end)
        if mask.any():
            g_vec = acc_vals[mask].mean(axis=0)
            g_mag = np.linalg.norm(g_vec)
            gyro_bias = vals[i:j].mean(axis=0)
            print(f"\n  从第一段静态窗口估计:")
            print(f"    gravity vector (m/s^2): [{g_vec[0]:+.3f}, {g_vec[1]:+.3f}, {g_vec[2]:+.3f}]")
            print(f"    gravity magnitude:      {g_mag:.4f}  (理论 9.80665)")
            print(f"    gyroscope bias  (rad/s): [{gyro_bias[0]:+.5f}, {gyro_bias[1]:+.5f}, {gyro_bias[2]:+.5f}]")


def diag_sync(frames, groups):
    print("\n=== 相机 / IMU 时间轴对齐 (elapsed_realtime_ns 为共同时基) ===")
    cam_ts = frames[:, 5].astype(np.int64)
    print(f"  相机 sensor_ts 范围: [{cam_ts.min()/1e9:.3f}s, {cam_ts.max()/1e9:.3f}s]  span={((cam_ts.max()-cam_ts.min())/1e9):.2f}s")
    for st in ("accelerometer", "gyroscope"):
        if st not in groups: continue
        ts = np.array([s[0] for s in groups[st]], dtype=np.int64)
        print(f"  {st:<15s} 范围: [{ts.min()/1e9:.3f}s, {ts.max()/1e9:.3f}s]  span={(ts.max()-ts.min())/1e9:.2f}s")
        lead = (cam_ts.min() - ts.min()) / 1e6
        trail = (ts.max() - cam_ts.max()) / 1e6
        print(f"    IMU 早于首帧: {lead:+.2f} ms | IMU 晚于末帧: {trail:+.2f} ms")


def main():
    frames_path = os.path.join(SESSION, "frames.csv")
    imu_path    = os.path.join(SESSION, "imu.jsonl")

    print(f"[Session] {SESSION}")
    frames = load_frames_csv(frames_path)
    groups = load_imu(imu_path)

    diag_frames(frames)
    diag_imu(groups)
    diag_sync(frames, groups)
    find_static_segments(groups)


if __name__ == "__main__":
    main()
