"""COLMAP pose + IMU 融合校准：拿到 metric-scaled + gravity-aligned pose stream.

输入：
  - COLMAP SfM 输出（motion session, 138 frames）
  - 静态 session IMU：估计 gravity direction in cam frame
  - motion session IMU：位移积分作为 metric ground truth 校准 scale

输出：
  - metric + gravity-aligned all_frames_pose_metric.npz
  - 每帧 6DoF pose，单位米，世界系 Z 向上

方法：
  1. 从 static session 静态段的 accel 均值 → gravity_cam (方向 + 幅值 9.8m/s²)
  2. 世界系旋转：让 COLMAP 的重力方向对齐 -Z (up = +Z)
  3. Scale 估计：
     - motion session：对相邻帧对（COLMAP registered），取 COLMAP unit 位移 delta_c
     - 同时段 IMU：减重力后 accel 二次积分 metric 位移 delta_m
     - k = median(delta_m / delta_c)
  4. Apply: t_metric = k * R_align @ t_colmap, R_metric = R_align @ R_colmap
"""
import json
import os
import numpy as np

STATIC_SESSION  = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260707_180906_484"
MOTION_SESSION  = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260710_154536_478"
POSE_NPZ_IN     = os.path.join(MOTION_SESSION, "poses", "all_frames_pose.npz")
POSE_NPZ_OUT    = os.path.join(MOTION_SESSION, "poses", "all_frames_pose_metric.npz")


def _load_imu(path):
    accel_t, accel_v = [], []
    gyro_t,  gyro_v  = [], []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            j = json.loads(line)
            st = j["sensor_type"]
            ts = int(j["sensor_timestamp_ns"])
            v = j.get("values", [0, 0, 0])
            if st == "accelerometer":
                accel_t.append(ts); accel_v.append(v)
            elif st == "gyroscope":
                gyro_t.append(ts); gyro_v.append(v)
    return (np.array(accel_t, dtype=np.int64), np.array(accel_v, dtype=np.float64),
            np.array(gyro_t,  dtype=np.int64), np.array(gyro_v,  dtype=np.float64))


def estimate_gravity(static_session):
    """从静态 IMU 估计 gravity vector（相机/IMU 坐标系）。"""
    at, av, gt, gv = _load_imu(os.path.join(static_session, "imu.jsonl"))
    # 找 gyro |ω| < 0.03 rad/s 的段（静止）
    mag = np.linalg.norm(gv, axis=1)
    quiet = mag < 0.03
    quiet_ts = gt[quiet]
    if len(quiet_ts) < 100:
        # fallback: 全部
        quiet_ts = gt
    # 用同时段 accel 平均
    lo, hi = quiet_ts.min(), quiet_ts.max()
    mask = (at >= lo) & (at <= hi)
    g_cam = av[mask].mean(axis=0)
    g_mag = float(np.linalg.norm(g_cam))
    print(f"Gravity vector (IMU frame): [{g_cam[0]:+.4f}, {g_cam[1]:+.4f}, {g_cam[2]:+.4f}]  |g|={g_mag:.4f}")
    return g_cam / g_mag, g_mag


def _integrate_imu_metric_disp(accel_t, accel_v, gyro_t, gyro_v, gravity_unit_cam,
                                t_start_ns, t_end_ns):
    """给定时间窗口，IMU 两次积分得到 metric 位移（单位 m）。"""
    mask_a = (accel_t >= t_start_ns) & (accel_t <= t_end_ns)
    ts = accel_t[mask_a]; acc = accel_v[mask_a]
    if len(ts) < 4:
        return None
    # 减重力（相机系）
    acc_free = acc - 9.8 * gravity_unit_cam[None, :]
    # 位移积分（Trapezoidal）
    dt = np.diff(ts) / 1e9   # s
    # v[k+1] = v[k] + 0.5*(a[k]+a[k+1])*dt[k]
    v = np.zeros((len(ts), 3))
    for i in range(1, len(ts)):
        v[i] = v[i-1] + 0.5 * (acc_free[i-1] + acc_free[i]) * dt[i-1]
    # x[k+1] = x[k] + 0.5*(v[k]+v[k+1])*dt[k]
    x = np.zeros((len(ts), 3))
    for i in range(1, len(ts)):
        x[i] = x[i-1] + 0.5 * (v[i-1] + v[i]) * dt[i-1]
    return float(np.linalg.norm(x[-1] - x[0]))   # 端点位移 metric


def _count_steps(accel_t, accel_v, gravity_unit_cam):
    """从加速度信号里数走路步数：低通滤波后找周期性峰。"""
    if len(accel_t) < 100:
        return 0, 0.0
    a_g = accel_v @ gravity_unit_cam - 9.8   # 垂直方向的动态加速度
    dt_s = float(accel_t[-1] - accel_t[0]) / 1e9

    # 简单移动平均低通（~0.1s 窗口）压掉传感器噪声
    fs = len(a_g) / max(dt_s, 1e-3)          # ~500-700 Hz
    win = max(3, int(fs * 0.08))             # ~80ms 平均
    kernel = np.ones(win) / win
    a_smooth = np.convolve(a_g, kernel, mode="same")

    # peak: 只要 +1.5 m/s² 以上, 且距离上一个峰至少 0.3s（≥ 1.7 steps/s 上限）
    peak_thresh = 1.5
    min_gap_s = 0.30
    min_gap_samples = int(fs * min_gap_s)
    n_steps = 0
    last_peak = -min_gap_samples
    for i in range(1, len(a_smooth) - 1):
        if (a_smooth[i] > peak_thresh
            and a_smooth[i] >= a_smooth[i-1]
            and a_smooth[i] >= a_smooth[i+1]
            and i - last_peak >= min_gap_samples):
            n_steps += 1
            last_peak = i
    return n_steps, dt_s


def estimate_scale(motion_session, gravity_unit_cam):
    """通过步频×步长估算 scale (v12 修法)。

    问题：原来的 _integrate_imu_metric_disp 假设窗口起始 v=0，走路中根本不成立，
    导致 IMU 位移严重低估 → k 也低估 5-10 倍 → 轨迹被压缩。

    新方法：数 IMU 里的步数，用 STEP_LENGTH_M 作为每步位移，得到全 session
    metric 位移；对比 COLMAP 轨迹长度得到 scale。
    对纯走路场景，比二次积分稳定得多。
    """
    STEP_LENGTH_M = 0.72   # 成年人平均步长

    d = np.load(POSE_NPZ_IN)
    ts_all   = d["timestamps_ns"].astype(np.int64)
    t_wc_all = d["t_world_cam"]
    valid    = d["valid"]
    registered = d["registered"]

    at, av, gt, gv = _load_imu(os.path.join(motion_session, "imu.jsonl"))

    # 1) 走了多少步 → metric 里程
    n_steps, dt_s = _count_steps(at, av, gravity_unit_cam)
    metric_distance = n_steps * STEP_LENGTH_M
    print(f"IMU step-count: {n_steps} steps over {dt_s:.1f}s "
          f"(cadence {n_steps/max(dt_s,1e-3):.2f} steps/s), "
          f"metric distance ≈ {metric_distance:.1f} m")

    # 2) COLMAP 里程（只在 registered 帧上累积轨迹长度）
    reg_idxs = np.where(registered)[0]
    print(f"registered frames: {len(reg_idxs)}")
    colmap_distance = 0.0
    for i in range(len(reg_idxs) - 1):
        a, b = reg_idxs[i], reg_idxs[i+1]
        if b - a > 60:  # 大间断跳过（模型断裂）
            continue
        colmap_distance += float(np.linalg.norm(t_wc_all[b] - t_wc_all[a]))
    print(f"COLMAP raw distance: {colmap_distance:.3f} unit")

    if colmap_distance < 1e-3:
        raise RuntimeError("COLMAP 累积轨迹太短")

    k = metric_distance / colmap_distance
    print(f"→ scale k = {k:.4f} m / COLMAP_unit")
    return k, d


def align_gravity(gravity_unit_cam, R_wc_all, t_wc_all, sample_indices):
    """把 COLMAP 世界系旋转，使 gravity 方向（在相机系是 gravity_unit_cam）在世界系里
    对齐到 +Z。返回 R_world_new_from_old（3x3）。

    做法：在每 registered 帧上，把 gravity_unit_cam 变换到 COLMAP 世界系 → 平均得到
    world_gravity_dir。然后构造旋转矩阵让该向量对齐到 +Z。
    """
    # 每 registered 帧：R_wc @ ... wait, R_wc 是 camera in world 的姿态
    # gravity in world = R_wc @ gravity_in_cam
    # 相机在世界的姿态用 R_world_cam：p_world = R_world_cam @ p_cam
    # 所以 gravity_world = R_world_cam @ gravity_cam
    grav_world = np.zeros(3)
    for i in sample_indices:
        grav_world += R_wc_all[i] @ gravity_unit_cam
    grav_world /= len(sample_indices)
    grav_world /= np.linalg.norm(grav_world)
    print(f"COLMAP world gravity direction: [{grav_world[0]:+.3f}, {grav_world[1]:+.3f}, {grav_world[2]:+.3f}]")

    # 构造旋转 R_align: 让 grav_world → +Z 方向
    # 用 Rodrigues 公式
    z_axis = np.array([0.0, 0.0, 1.0])
    v = np.cross(grav_world, z_axis)
    s = np.linalg.norm(v)
    c = float(grav_world @ z_axis)
    if s < 1e-9:
        R_align = np.eye(3) if c > 0 else np.diag([1.0, -1.0, -1.0])
    else:
        vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
        R_align = np.eye(3) + vx + vx @ vx * ((1 - c) / (s * s))
    return R_align


def main():
    print("=== 1. Gravity from static session ===")
    grav_cam, g_mag = estimate_gravity(STATIC_SESSION)

    print("\n=== 2. Metric scale from motion session IMU ===")
    k, d = estimate_scale(MOTION_SESSION, grav_cam)

    ts_all   = d["timestamps_ns"].astype(np.int64)
    t_wc_all = d["t_world_cam"]
    R_wc_all = d["R_world_cam"]
    valid    = d["valid"]
    registered = d["registered"]
    reg_idxs = np.where(registered)[0]

    print("\n=== 3. Gravity align ===")
    R_align = align_gravity(grav_cam, R_wc_all, t_wc_all, reg_idxs)
    print(f"R_align =\n{R_align}")

    # apply: metric + gravity-aligned
    print("\n=== 4. Apply calibration ===")
    R_metric = np.zeros_like(R_wc_all)
    t_metric = np.zeros_like(t_wc_all)
    for i in range(len(t_wc_all)):
        R_metric[i] = R_align @ R_wc_all[i]
        t_metric[i] = k * (R_align @ t_wc_all[i])

    # save
    os.makedirs(os.path.dirname(POSE_NPZ_OUT), exist_ok=True)
    np.savez(POSE_NPZ_OUT,
             timestamps_ns=ts_all,
             R_world_cam=R_metric,
             t_world_cam=t_metric,
             valid=valid,
             registered=registered,
             scale_m_per_unit=k,
             gravity_cam=grav_cam,
             R_align=R_align)
    print(f"\nsaved: {POSE_NPZ_OUT}")
    print(f"  scale k = {k:.4f} m / COLMAP_unit")
    if reg_idxs.size >= 2:
        span_m = float(np.linalg.norm(t_metric[reg_idxs[-1]] - t_metric[reg_idxs[0]]))
        span_s = (ts_all[reg_idxs[-1]] - ts_all[reg_idxs[0]]) / 1e9
        print(f"  trajectory span (metric): {span_m:.2f}m over {span_s:.1f}s")


if __name__ == "__main__":
    main()
