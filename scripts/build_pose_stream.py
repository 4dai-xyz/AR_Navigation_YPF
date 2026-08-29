"""从 COLMAP SfM 输出提取 138 帧 pose，按 timestamp 插值到全部 3411 帧。

输出：<session>/poses/all_frames_pose.npz
  - timestamps_ns: (N,) 每帧的 elapsed_realtime_ns
  - t_world_cam:   (N, 3)  相机中心（世界系）
  - R_world_cam:   (N, 3, 3) 相机朝向（世界系）
  - valid:         (N,) bool  该帧是否在 COLMAP 覆盖范围（外插的置 False）
  - registered:    (N,) bool  该帧是否直接来自 COLMAP（其余是插值）

坐标系约定（COLMAP）：
  相机系: x 右, y 下, z 前
  世界系: 任意，第一个 registered 帧为参考

后续 BEV 用法：相邻两帧 delta_T = inv(pose[i]) @ pose[i-1] 得到 ego 平面变换。
"""
import json
import numpy as np
import os
import sys

SESSION    = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260710_154536_478"
STATIC_SESSION = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260707_180906_484"
COLMAP_TXT = os.path.join(SESSION, "colmap", "sparse_txt")
TS_MAP     = os.path.join(SESSION, "colmap", "timestamps.txt")
FRAMES_CSV = os.path.join(SESSION, "frames.csv")
IMU_PATH   = os.path.join(SESSION, "imu.jsonl")
OUT_PATH   = os.path.join(SESSION, "poses", "all_frames_pose.npz")


def qvec_to_R(qw, qx, qy, qz):
    return np.array([
        [1-2*qy*qy-2*qz*qz, 2*qx*qy-2*qz*qw,   2*qx*qz+2*qy*qw],
        [2*qx*qy+2*qz*qw,   1-2*qx*qx-2*qz*qz, 2*qy*qz-2*qx*qw],
        [2*qx*qz-2*qy*qw,   2*qy*qz+2*qx*qw,   1-2*qx*qx-2*qy*qy],
    ])


def R_to_qvec(R):
    """Return (w, x, y, z) unit quaternion."""
    tr = R[0,0] + R[1,1] + R[2,2]
    if tr > 0:
        s = np.sqrt(tr + 1.0) * 2
        qw = 0.25 * s
        qx = (R[2,1] - R[1,2]) / s
        qy = (R[0,2] - R[2,0]) / s
        qz = (R[1,0] - R[0,1]) / s
    elif (R[0,0] > R[1,1]) and (R[0,0] > R[2,2]):
        s = np.sqrt(1.0 + R[0,0] - R[1,1] - R[2,2]) * 2
        qw = (R[2,1] - R[1,2]) / s
        qx = 0.25 * s
        qy = (R[0,1] + R[1,0]) / s
        qz = (R[0,2] + R[2,0]) / s
    elif R[1,1] > R[2,2]:
        s = np.sqrt(1.0 + R[1,1] - R[0,0] - R[2,2]) * 2
        qw = (R[0,2] - R[2,0]) / s
        qx = (R[0,1] + R[1,0]) / s
        qy = 0.25 * s
        qz = (R[1,2] + R[2,1]) / s
    else:
        s = np.sqrt(1.0 + R[2,2] - R[0,0] - R[1,1]) * 2
        qw = (R[1,0] - R[0,1]) / s
        qx = (R[0,2] + R[2,0]) / s
        qy = (R[1,2] + R[2,1]) / s
        qz = 0.25 * s
    q = np.array([qw, qx, qy, qz])
    return q / np.linalg.norm(q)


def slerp(q0, q1, t):
    """Spherical linear interpolation between two unit quats (w,x,y,z)."""
    dot = np.dot(q0, q1)
    if dot < 0:
        q1 = -q1
        dot = -dot
    if dot > 0.9995:
        # linear + normalize for near-identity
        q = q0 + t * (q1 - q0)
        return q / np.linalg.norm(q)
    theta = np.arccos(np.clip(dot, -1, 1))
    sin_t = np.sin(theta)
    return (np.sin((1 - t) * theta) / sin_t) * q0 + (np.sin(t * theta) / sin_t) * q1


def parse_images_txt(path):
    """返回 list[(name, qw, qx, qy, qz, tx, ty, tz)]，name 已按顺序。"""
    entries = []
    with open(path, "r", encoding="utf-8") as f:
        lines = [l for l in f if not l.startswith("#") and l.strip()]
    it = iter(lines)
    for hdr in it:
        toks = hdr.split()
        qw, qx, qy, qz = map(float, toks[1:5])
        tx, ty, tz = map(float, toks[5:8])
        name = toks[9]
        try:
            next(it)  # 跳 POINTS2D 行
        except StopIteration:
            pass
        entries.append((name, qw, qx, qy, qz, tx, ty, tz))
    return entries


def parse_timestamps_map(path):
    """返回 dict[filename] -> elapsed_realtime_ns"""
    m = {}
    with open(path, "r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i == 0: continue
            toks = line.strip().split("\t")
            if len(toks) == 2:
                m[toks[0]] = int(toks[1])
    return m


def load_gyro(path):
    """加载 gyroscope 数据 (t_ns, ω_xyz rad/s)."""
    ts, ws = [], []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            j = json.loads(line)
            if j.get("sensor_type") != "gyroscope":
                continue
            ts.append(int(j["sensor_timestamp_ns"]))
            ws.append(j.get("values", [0, 0, 0]))
    return np.array(ts, dtype=np.int64), np.array(ws, dtype=np.float64)


def load_game_rotation(path):
    """加载 game_rotation_vector: 返回 (t_ns, quat[w,x,y,z]) — Android 无漂移融合姿态."""
    ts, qs = [], []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            j = json.loads(line)
            if j.get("sensor_type") != "game_rotation_vector":
                continue
            ts.append(int(j["sensor_timestamp_ns"]))
            q = j.get("quaternion", [1, 0, 0, 0])  # [w, x, y, z]
            qs.append(q)
    return np.array(ts, dtype=np.int64), np.array(qs, dtype=np.float64)


def estimate_gyro_bias(gyro_t, gyro_v):
    """静态段的 gyro 均值即 bias。找 |ω| < 0.05 rad/s 的段."""
    mag = np.linalg.norm(gyro_v, axis=1)
    quiet = mag < 0.05
    if quiet.sum() < 100:
        return np.zeros(3)
    return gyro_v[quiet].mean(axis=0)


def rodrigues(axis, angle):
    """Rodrigues 公式: axis 是单位向量, angle 弧度."""
    K = np.array([[0, -axis[2], axis[1]],
                  [axis[2], 0, -axis[0]],
                  [-axis[1], axis[0], 0]])
    return np.eye(3) + np.sin(angle) * K + (1 - np.cos(angle)) * (K @ K)


def drift_to_axis_angle(R):
    """将旋转矩阵分解为 axis + angle (rad)."""
    cos_theta = float(np.clip((np.trace(R) - 1) / 2.0, -1.0, 1.0))
    theta = float(np.arccos(cos_theta))
    if theta < 1e-9:
        return np.array([1.0, 0.0, 0.0]), 0.0
    sin_theta = np.sin(theta)
    axis = np.array([R[2, 1] - R[1, 2],
                     R[0, 2] - R[2, 0],
                     R[1, 0] - R[0, 1]]) / (2 * sin_theta)
    return axis / (np.linalg.norm(axis) + 1e-12), theta


def integrate_gyro_delta(gyro_t, gyro_v_debiased, t_start_ns, t_end_ns):
    """从 t_start 到 t_end 积分陀螺仪, 得到 delta rotation (R_cam_start_from_cam_end)."""
    lo_i = int(np.searchsorted(gyro_t, t_start_ns))
    hi_i = int(np.searchsorted(gyro_t, t_end_ns))
    if hi_i - lo_i < 2:
        return np.eye(3)
    ts = gyro_t[lo_i:hi_i+1]
    ws = gyro_v_debiased[lo_i:hi_i+1]
    R = np.eye(3)
    for i in range(1, len(ts)):
        dt = float(ts[i] - ts[i-1]) / 1e9
        if dt <= 0 or dt > 0.1:  # 跳过大 gap
            continue
        w_avg = 0.5 * (ws[i-1] + ws[i])
        angle = float(np.linalg.norm(w_avg) * dt)
        if angle > 1e-9:
            axis = w_avg / (np.linalg.norm(w_avg) + 1e-12)
            R = R @ rodrigues(axis, angle)
    return R


def main():
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)

    entries = parse_images_txt(os.path.join(COLMAP_TXT, "images.txt"))
    ts_map = parse_timestamps_map(TS_MAP)

    # 组装 COLMAP 已 register 的 (timestamp_ns, R_wc, t_wc) 数组
    # COLMAP 里 T_wc 表示 world->camera：p_cam = R_wc * p_world + t_wc
    # 相机中心 c_world = -R_wc^T @ t_wc
    # 对 BEV 累积，我们需要 T_world_cam (camera pose in world)
    known = []
    for name, qw, qx, qy, qz, tx, ty, tz in entries:
        if name not in ts_map: continue
        R_wc = qvec_to_R(qw, qx, qy, qz)
        t_wc = np.array([tx, ty, tz])
        R_cw = R_wc.T                   # camera -> world rotation
        t_cw = -R_wc.T @ t_wc           # camera position in world
        known.append((ts_map[name], R_cw, t_cw))

    known.sort(key=lambda x: x[0])
    ts_known = np.array([k[0] for k in known], dtype=np.int64)
    R_known  = np.array([k[1] for k in known])
    t_known  = np.array([k[2] for k in known])
    q_known  = np.array([R_to_qvec(R) for R in R_known])
    print(f"COLMAP registered frames: {len(known)}")
    print(f"time span: {(ts_known[-1] - ts_known[0])/1e9:.2f}s")

    # 全部帧时间戳
    frames = np.loadtxt(FRAMES_CSV, delimiter=",", skiprows=1, dtype=np.int64)
    ts_all = frames[:, 5]
    N = len(ts_all)
    print(f"total video frames: {N}")

    # v3→v4: game_rot_vec 在 Rokid 上是设备 frame (device-to-camera 有未知固定旋转),
    # 强行套用反而破坏 gyro 已捕获的物理转弯. 回退到 raw gyro 积分 (相机 frame 直接).
    print("\n=== gyro 相机系预积分 ===")
    gyro_t, gyro_v = load_gyro(IMU_PATH)
    static_gyro_t, static_gyro_v = load_gyro(os.path.join(STATIC_SESSION, "imu.jsonl"))
    bias = estimate_gyro_bias(static_gyro_t, static_gyro_v)
    print(f"gyro: {len(gyro_t)} samples, bias = [{bias[0]:+.4f}, {bias[1]:+.4f}, {bias[2]:+.4f}] rad/s")
    gyro_v_deb = gyro_v - bias[None, :]

    kf0_gyro_idx = int(np.searchsorted(gyro_t, ts_known[0]))
    R_cum = np.zeros((len(gyro_t), 3, 3))
    R_cum[kf0_gyro_idx] = np.eye(3)
    for k in range(kf0_gyro_idx + 1, len(gyro_t)):
        dt = float(gyro_t[k] - gyro_t[k-1]) / 1e9
        if dt <= 0 or dt > 0.1:
            R_cum[k] = R_cum[k-1]
            continue
        w = 0.5 * (gyro_v_deb[k-1] + gyro_v_deb[k])
        angle = float(np.linalg.norm(w) * dt)
        if angle > 1e-9:
            ax = w / (np.linalg.norm(w) + 1e-12)
            R_cum[k] = R_cum[k-1] @ rodrigues(ax, angle)
        else:
            R_cum[k] = R_cum[k-1]
    print(f"pre-integrated {len(gyro_t) - kf0_gyro_idx} gyro samples")

    def R_at(ts):
        k = int(np.searchsorted(gyro_t, ts))
        k = max(kf0_gyro_idx, min(k, len(gyro_t) - 1))
        return R_cum[k]

    # 对每个 ts_all[i]，找 known 中相邻两帧, 用 gyro 积分补齐 R, 线性插值补齐 t
    R_all = np.zeros((N, 3, 3))
    t_all = np.zeros((N, 3))
    valid = np.zeros(N, dtype=bool)
    registered = np.zeros(N, dtype=bool)

    for i, ts in enumerate(ts_all):
        if ts < ts_known[0] or ts > ts_known[-1]:
            R_all[i] = np.eye(3)
            t_all[i] = 0
            valid[i] = False
            continue

        j = int(np.searchsorted(ts_known, ts))
        if j >= len(ts_known):
            j = len(ts_known) - 1

        if ts_known[j] == ts:
            registered[i] = True
        # v4: R = COLMAP kf0 姿态 @ gyro 从 kf0 累积到 ts 的相机系 delta
        R = R_known[0] @ R_at(int(ts))
        if ts_known[j] == ts:
            t = t_known[j]
        else:
            j0, j1 = j - 1, j
            alpha = (ts - ts_known[j0]) / (ts_known[j1] - ts_known[j0])
            t = (1 - alpha) * t_known[j0] + alpha * t_known[j1]
        R_all[i] = R
        t_all[i] = t
        valid[i] = True

    print(f"valid poses: {valid.sum()} / {N}   registered: {registered.sum()}")
    np.savez(OUT_PATH,
             timestamps_ns=ts_all,
             R_world_cam=R_all,
             t_world_cam=t_all,
             valid=valid,
             registered=registered)
    print(f"saved: {OUT_PATH}")


if __name__ == "__main__":
    main()
