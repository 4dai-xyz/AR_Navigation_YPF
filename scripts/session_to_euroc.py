"""把 Rokid session 转成 EuRoC MAV 格式（ORB-SLAM3 直接可用）。

输出布局：
  <session>/euroc/mav0/
    cam0/data/*.png            (每帧一个 png，文件名 = timestamp_ns)
    cam0/data.csv              (timestamp[ns], filename)
    imu0/data.csv              (timestamp[ns], wx, wy, wz, ax, ay, az)

用 elapsed_realtime_ns 作时间戳（IMU 同一时钟）
"""
import cv2
import json
import numpy as np
import os
import sys

SESSION = sys.argv[1] if len(sys.argv) > 1 else r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260707_163809_054"
CAM_STRIDE = 1     # 每帧都写（ORB-SLAM3 处理更多帧更稳）

OUT_ROOT = os.path.join(SESSION, "euroc")
CAM_DIR  = os.path.join(OUT_ROOT, "mav0", "cam0", "data")
IMU_DIR  = os.path.join(OUT_ROOT, "mav0", "imu0")


def load_imu(path):
    accel_t, accel_v = [], []
    gyro_t, gyro_v = [], []
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
            np.array(gyro_t, dtype=np.int64), np.array(gyro_v, dtype=np.float64))


def main():
    os.makedirs(CAM_DIR, exist_ok=True)
    os.makedirs(IMU_DIR, exist_ok=True)
    frames = np.loadtxt(os.path.join(SESSION, "frames.csv"),
                        delimiter=",", skiprows=1, dtype=np.int64)
    ts_cam = frames[:, 5]
    print(f"video frames listed in csv: {len(ts_cam)}")

    # IMU：以 gyro 时间为准，线性插值 accel
    print("Loading IMU ...", flush=True)
    accel_t, accel_v, gyro_t, gyro_v = load_imu(os.path.join(SESSION, "imu.jsonl"))
    lo, hi = max(gyro_t.min(), accel_t.min()), min(gyro_t.max(), accel_t.max())
    mask = (gyro_t >= lo) & (gyro_t <= hi)
    gyro_t_use = gyro_t[mask]
    gyro_v_use = gyro_v[mask]
    accel_interp = np.zeros((len(gyro_t_use), 3))
    for i in range(3):
        accel_interp[:, i] = np.interp(gyro_t_use, accel_t, accel_v[:, i])
    print(f"  merged IMU samples: {len(gyro_t_use)}")

    # 写 imu0/data.csv (EuRoC 格式含 header)
    imu_csv = os.path.join(IMU_DIR, "data.csv")
    with open(imu_csv, "w", encoding="utf-8") as f:
        f.write("#timestamp [ns],w_RS_S_x [rad s^-1],w_RS_S_y [rad s^-1],w_RS_S_z [rad s^-1],a_RS_S_x [m s^-2],a_RS_S_y [m s^-2],a_RS_S_z [m s^-2]\n")
        for i in range(len(gyro_t_use)):
            f.write(f"{int(gyro_t_use[i])},{gyro_v_use[i,0]:.9f},{gyro_v_use[i,1]:.9f},{gyro_v_use[i,2]:.9f},"
                    f"{accel_interp[i,0]:.9f},{accel_interp[i,1]:.9f},{accel_interp[i,2]:.9f}\n")
    print(f"  wrote: {imu_csv}")

    # 相机：抽帧写 png（ORB-SLAM3 要求灰度或彩色 png，命名 = timestamp_ns.png）
    print("Extracting frames ...", flush=True)
    cap = cv2.VideoCapture(os.path.join(SESSION, "video.mp4"))
    idx = 0
    written = 0
    cam_rows = []
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if idx % CAM_STRIDE == 0 and idx < len(ts_cam):
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            ts_ns = int(ts_cam[idx])
            name = f"{ts_ns}.png"
            cv2.imwrite(os.path.join(CAM_DIR, name), gray)
            cam_rows.append((ts_ns, name))
            written += 1
            if written % 500 == 0:
                print(f"  wrote {written} frames", flush=True)
        idx += 1
    cap.release()

    cam_csv = os.path.join(OUT_ROOT, "mav0", "cam0", "data.csv")
    with open(cam_csv, "w", encoding="utf-8") as f:
        f.write("#timestamp [ns],filename\n")
        for ts_ns, name in cam_rows:
            f.write(f"{ts_ns},{name}\n")
    print(f"  wrote: {cam_csv}")
    print(f"  camera frames: {written}")

    # 顺便写 timestamps.txt (ORB-SLAM3 examples 期望的格式：每行一个 ns，用于 mono_inertial_euroc)
    ts_txt = os.path.join(OUT_ROOT, "timestamps.txt")
    with open(ts_txt, "w", encoding="utf-8") as f:
        for ts_ns, _ in cam_rows:
            f.write(f"{ts_ns}\n")
    print(f"  wrote: {ts_txt}")

    print(f"\nDone. EuRoC dataset at: {OUT_ROOT}")


if __name__ == "__main__":
    main()
