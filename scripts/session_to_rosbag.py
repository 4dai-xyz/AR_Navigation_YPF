"""把 Rokid session (video.mp4 + imu.jsonl + frames.csv) 转为 Kalibr 用的 rosbag1 (.bag)。

Topics:
  /cam0/image_raw  sensor_msgs/Image (mono8, 20 Hz 抽样)
  /imu0            sensor_msgs/Imu   (~250 Hz, accel 线性插值到 gyro 时间戳)

时基：统一用 elapsed_realtime_ns（IMU sensor_timestamp_ns 就是这个时钟）。
"""
import json
import os
import sys
import numpy as np
import cv2

from rosbags.rosbag1 import Writer
from rosbags.typesys import Stores, get_typestore
from rosbags.typesys.stores.ros1_noetic import (
    sensor_msgs__msg__Image as Image,
    sensor_msgs__msg__Imu as Imu,
    std_msgs__msg__Header as Header,
    builtin_interfaces__msg__Time as Time,
    geometry_msgs__msg__Vector3 as Vector3,
    geometry_msgs__msg__Quaternion as Quaternion,
)

SESSION = sys.argv[1] if len(sys.argv) > 1 else r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260707_182922_085"
BAG_OUT = os.path.join(SESSION, "kalibr.bag")

VIDEO      = os.path.join(SESSION, "video.mp4")
FRAMES_CSV = os.path.join(SESSION, "frames.csv")
IMU_JSONL  = os.path.join(SESSION, "imu.jsonl")

# 相机抽样到 20 Hz (60fps → 每 3 帧 1 张)。Kalibr 官方推荐 20Hz 输入
CAM_STRIDE = 3


def ns_to_ros_time(ns):
    return Time(sec=int(ns // 1_000_000_000), nanosec=int(ns % 1_000_000_000))


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


def interp_accel_to_gyro(accel_t, accel_v, gyro_t):
    """线性插值 accel 到 gyro 时间戳；截断到有效范围。"""
    lo = max(gyro_t.min(), accel_t.min())
    hi = min(gyro_t.max(), accel_t.max())
    mask = (gyro_t >= lo) & (gyro_t <= hi)
    gyro_t_use = gyro_t[mask]
    # 每轴独立插值
    out = np.zeros((len(gyro_t_use), 3))
    for i in range(3):
        out[:, i] = np.interp(gyro_t_use, accel_t, accel_v[:, i])
    return mask, out


def main():
    if os.path.exists(BAG_OUT):
        os.remove(BAG_OUT)

    typestore = get_typestore(Stores.ROS1_NOETIC)

    # ---- IMU ----
    print("Load IMU ...")
    accel_t, accel_v, gyro_t, gyro_v = load_imu(IMU_JSONL)
    print(f"  accel: {len(accel_t)}  gyro: {len(gyro_t)}")
    mask, accel_interp = interp_accel_to_gyro(accel_t, accel_v, gyro_t)
    gyro_t_use = gyro_t[mask]
    gyro_v_use = gyro_v[mask]
    print(f"  合并后 IMU 消息数: {len(gyro_t_use)}")

    # ---- Camera timestamps ----
    print("Load frames.csv ...")
    frames = np.loadtxt(FRAMES_CSV, delimiter=",", skiprows=1, dtype=np.int64)
    ts_cam = frames[:, 5]     # elapsed_realtime_ns
    print(f"  total video frames: {len(ts_cam)}")

    # ---- 写 bag ----
    print(f"Writing bag: {BAG_OUT}")
    with Writer(BAG_OUT) as writer:
        # 注册两个 topic 的 msg 类型
        img_conn = writer.add_connection(
            "/cam0/image_raw",
            Image.__msgtype__,
            typestore=typestore,
        )
        imu_conn = writer.add_connection(
            "/imu0",
            Imu.__msgtype__,
            typestore=typestore,
        )

        # IMU 全部写
        zeros9 = [0.0] * 9
        for i in range(len(gyro_t_use)):
            t_ns = int(gyro_t_use[i])
            stamp = ns_to_ros_time(t_ns)
            hdr = Header(stamp=stamp, frame_id="imu0", seq=i)
            msg = Imu(
                header=hdr,
                orientation=Quaternion(x=0.0, y=0.0, z=0.0, w=1.0),
                orientation_covariance=np.array(zeros9),
                angular_velocity=Vector3(x=float(gyro_v_use[i, 0]), y=float(gyro_v_use[i, 1]), z=float(gyro_v_use[i, 2])),
                angular_velocity_covariance=np.array(zeros9),
                linear_acceleration=Vector3(x=float(accel_interp[i, 0]), y=float(accel_interp[i, 1]), z=float(accel_interp[i, 2])),
                linear_acceleration_covariance=np.array(zeros9),
            )
            data = typestore.serialize_ros1(msg, Imu.__msgtype__)
            writer.write(imu_conn, t_ns, data)

        print(f"  wrote IMU messages: {len(gyro_t_use)}")

        # 相机帧
        cap = cv2.VideoCapture(VIDEO)
        idx = 0
        wrote = 0
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if idx % CAM_STRIDE == 0 and idx < len(ts_cam):
                gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
                h, w = gray.shape
                t_ns = int(ts_cam[idx])
                stamp = ns_to_ros_time(t_ns)
                hdr = Header(stamp=stamp, frame_id="cam0", seq=wrote)
                msg = Image(
                    header=hdr,
                    height=h, width=w,
                    encoding="mono8",
                    is_bigendian=0,
                    step=w,
                    data=np.frombuffer(gray.tobytes(), dtype=np.uint8),
                )
                data = typestore.serialize_ros1(msg, Image.__msgtype__)
                writer.write(img_conn, t_ns, data)
                wrote += 1
            idx += 1
        cap.release()
        print(f"  wrote camera frames: {wrote}")

    size_mb = os.path.getsize(BAG_OUT) / 1e6
    print(f"\nDone: {BAG_OUT}  ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
