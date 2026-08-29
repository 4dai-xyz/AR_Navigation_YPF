"""从 60s 静止 session 估计 IMU 噪声参数（Kalibr 需要）。

用 Allan variance 短时近似：
  - 白噪声 σ_n = std(sample) (in each axis, then averaged)
  - Bias 随机游走 σ_bw：滑动平均 mean 的 std / sqrt(T_window)

这是简化估计，比正规 imu_utils 少精度但够用。
最好用连续 2h 静置数据；60s 已知偏保守但可作为 Kalibr 起点。
"""
import json
import os
import numpy as np

STATIC_SESSION = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260707_180906_484"
IMU_PATH = os.path.join(STATIC_SESSION, "imu.jsonl")
OUT_PATH = os.path.join(STATIC_SESSION, "imu_noise.yaml")


def load_group(path, sensor_type):
    ts, vals = [], []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            j = json.loads(line)
            if j["sensor_type"] != sensor_type: continue
            ts.append(j["sensor_timestamp_ns"])
            vals.append(j["values"])
    return np.array(ts, dtype=np.int64), np.array(vals, dtype=np.float64)


def estimate(ts_ns, vals, sensor_hz):
    """返回：白噪声 σ_n (per axis平均) 和 bias RW σ_bw。"""
    # 白噪声：每轴 std，然后平均
    sigma_n_per_axis = vals.std(axis=0)
    sigma_n = sigma_n_per_axis.mean()

    # Bias 随机游走：把序列分块（每块 1s）算 mean，再算 mean 序列的 std
    block_size = int(sensor_hz)  # 1s
    n_blocks = len(vals) // block_size
    if n_blocks < 5:
        return sigma_n, None
    means = np.array([vals[i*block_size:(i+1)*block_size].mean(axis=0) for i in range(n_blocks)])
    # 一阶差分近似增量
    diffs = np.diff(means, axis=0)
    sigma_bw_per_axis = diffs.std(axis=0)
    sigma_bw = sigma_bw_per_axis.mean()   # per √Hz 未换算
    # 换算到 continuous-time random walk: σ_bw / sqrt(1s) = σ_bw (since 1s block)
    return sigma_n, sigma_bw


def main():
    print(f"读取: {IMU_PATH}")
    for stype, label in [("gyroscope", "gyro"), ("accelerometer", "accel")]:
        ts, vals = load_group(IMU_PATH, stype)
        if len(vals) < 100:
            print(f"[{label}] 样本不足"); continue
        dt = np.median(np.diff(ts)) / 1e9
        hz = 1.0 / dt
        sigma_n, sigma_bw = estimate(ts, vals, hz)
        # 单位：gyro rad/s；accel m/s^2
        # Kalibr 需要 continuous-time：sigma_c = sigma_n * sqrt(dt) ？
        # 实际 Kalibr yaml 用的是 discrete stds * sqrt(dt) → 连续单位
        # 简化：直接给 discrete std 供 Kalibr 作为初值
        print(f"\n[{label}] 采样率 {hz:.1f} Hz，样本 {len(vals)}")
        print(f"  σ_n (discrete): {sigma_n:.6f}   (per-axis: {vals.std(axis=0)})")
        print(f"  连续时间 σ_n:   {sigma_n * np.sqrt(dt):.6e}   (× √dt with dt={dt:.4e}s)")
        if sigma_bw:
            print(f"  σ_bw (bias RW rate): {sigma_bw:.6f}")
        mean_val = vals.mean(axis=0)
        print(f"  均值 (可作为 bias 初值): {mean_val}")

    # 写 Kalibr yaml
    ts_g, vals_g = load_group(IMU_PATH, "gyroscope")
    ts_a, vals_a = load_group(IMU_PATH, "accelerometer")
    dt_g = np.median(np.diff(ts_g)) / 1e9
    dt_a = np.median(np.diff(ts_a)) / 1e9

    gyro_sigma_c = vals_g.std(axis=0).mean() * np.sqrt(dt_g)
    accel_sigma_c = vals_a.std(axis=0).mean() * np.sqrt(dt_a)
    # bias RW 从 1s 均值差分估
    def bw(vals, hz):
        n = int(hz)
        blocks = len(vals) // n
        if blocks < 5: return 1e-4
        m = np.array([vals[i*n:(i+1)*n].mean(axis=0) for i in range(blocks)])
        return np.diff(m, axis=0).std(axis=0).mean()
    gyro_bw = bw(vals_g, 1.0/dt_g)
    accel_bw = bw(vals_a, 1.0/dt_a)

    with open(OUT_PATH, "w", encoding="utf-8") as f:
        f.write("# Kalibr IMU intrinsics (auto-estimated from 60s static session)\n")
        f.write("# 单位:\n")
        f.write("#   accelerometer_noise_density  = m/s^2 / sqrt(Hz)\n")
        f.write("#   gyroscope_noise_density      = rad/s / sqrt(Hz)\n")
        f.write("#   accelerometer_random_walk    = m/s^3 / sqrt(Hz)\n")
        f.write("#   gyroscope_random_walk        = rad/s^2 / sqrt(Hz)\n")
        f.write(f"accelerometer_noise_density: {accel_sigma_c:.6e}\n")
        f.write(f"accelerometer_random_walk:   {accel_bw:.6e}\n")
        f.write(f"gyroscope_noise_density:     {gyro_sigma_c:.6e}\n")
        f.write(f"gyroscope_random_walk:       {gyro_bw:.6e}\n")
        f.write(f"rostopic:                    /imu0\n")
        f.write(f"update_rate:                 {1.0/np.median([dt_g, dt_a]):.1f}\n")
    print(f"\nKalibr IMU yaml 写入: {OUT_PATH}")


if __name__ == "__main__":
    main()
