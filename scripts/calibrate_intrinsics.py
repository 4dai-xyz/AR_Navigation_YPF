"""从动作 session 视频里自动标定相机内参（OpenCV，无需 Kalibr）。

输出：
  - 相机内参 yaml（fx, fy, cx, cy, distortion coeffs）
  - 每帧重投影误差图
  - 用于 Kalibr Cam-IMU 联合标定的 camchain-cam.yaml

注意：SQUARE_SIZE_MM 影响 tvec 的物理单位（=Kalibr 需要 metric）；
内参 fx/fy/cx/cy 不依赖此值。请把打印后实测格子边长填进来。
"""
import cv2
import numpy as np
import os
import sys
import json

SESSION       = sys.argv[1] if len(sys.argv) > 1 else r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260707_182922_085"
VIDEO         = os.path.join(SESSION, "video.mp4")
OUT_DIR       = os.path.join(SESSION, "intrinsics")
os.makedirs(OUT_DIR, exist_ok=True)

BOARD         = (8, 6)          # 内角点 (cols, rows)
SQUARE_SIZE_MM = 25.0           # ⚠️ 打印后实测填入
SAMPLE_STRIDE = 30              # 60fps → 2Hz 采样，全长视频约 185 帧

# 相机模型：pinhole + 5 参数 radtan（k1, k2, p1, p2, k3）
# 若想用 rational_model 或 fisheye，见下方注释


def build_object_points():
    """生成模板 3D 点（Z=0 平面），单位 mm。"""
    objp = np.zeros((BOARD[0] * BOARD[1], 3), np.float32)
    objp[:, :2] = np.mgrid[0:BOARD[0], 0:BOARD[1]].T.reshape(-1, 2)
    objp *= SQUARE_SIZE_MM
    return objp


def collect_corners(video):
    """扫描视频，收集每张有效帧的图像角点。"""
    cap = cv2.VideoCapture(video)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    objp_template = build_object_points()

    objpoints, imgpoints, used_frames = [], [], []
    idx = 0
    W = H = None
    print(f"扫描 {total} 帧, stride={SAMPLE_STRIDE}", flush=True)
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if idx % SAMPLE_STRIDE == 0:
            if idx % (SAMPLE_STRIDE * 20) == 0:
                print(f"  processed frame {idx}/{total}, hits so far: {len(objpoints)}", flush=True)
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            H, W = gray.shape[:2]
            ret, corners = cv2.findChessboardCorners(
                gray, BOARD,
                flags=cv2.CALIB_CB_ADAPTIVE_THRESH | cv2.CALIB_CB_NORMALIZE_IMAGE
            )
            if ret:
                corners_ref = cv2.cornerSubPix(
                    gray, corners, (11, 11), (-1, -1),
                    (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.01),
                )
                objpoints.append(objp_template.copy())
                imgpoints.append(corners_ref)
                used_frames.append(idx)
        idx += 1
    cap.release()
    print(f"用于标定的帧数: {len(objpoints)}")
    return objpoints, imgpoints, used_frames, (W, H)


def main():
    objpoints, imgpoints, frames_used, (W, H) = collect_corners(VIDEO)
    if len(objpoints) < 20:
        sys.exit(f"角点帧太少（{len(objpoints)}），需要至少 20")

    # 主标定
    flags = 0
    print(f"\n标定中... 输入分辨率: {W}x{H}, 帧数: {len(objpoints)}")
    ret, K, dist, rvecs, tvecs = cv2.calibrateCamera(
        objpoints, imgpoints, (W, H), None, None, flags=flags
    )
    fx, fy, cx, cy = K[0, 0], K[1, 1], K[0, 2], K[1, 2]
    k1, k2, p1, p2, k3 = dist.ravel()[:5]

    # 每帧重投影误差
    per_frame_err = []
    for objp, imgp, rv, tv in zip(objpoints, imgpoints, rvecs, tvecs):
        proj, _ = cv2.projectPoints(objp, rv, tv, K, dist)
        err = np.linalg.norm(imgp.reshape(-1, 2) - proj.reshape(-1, 2), axis=1)
        per_frame_err.append(err.mean())
    per_frame_err = np.array(per_frame_err)

    print("\n===== 标定结果 =====")
    print(f"RMS 重投影误差 (整体): {ret:.4f} px")
    print(f"帧均重投影误差: mean={per_frame_err.mean():.4f}px  median={np.median(per_frame_err):.4f}px  max={per_frame_err.max():.4f}px")
    print(f"\nK =")
    print(f"  fx = {fx:.4f}    fy = {fy:.4f}")
    print(f"  cx = {cx:.4f}    cy = {cy:.4f}")
    print(f"畸变 (radtan5):  k1={k1:+.5f}  k2={k2:+.5f}  p1={p1:+.5f}  p2={p2:+.5f}  k3={k3:+.5f}")

    # 保存结果 (JSON + Kalibr 格式 yaml)
    result = {
        "session": os.path.basename(SESSION),
        "image_size": [W, H],
        "board": {"cols_inner": BOARD[0], "rows_inner": BOARD[1], "square_mm": SQUARE_SIZE_MM},
        "num_frames_used": len(objpoints),
        "rms_reproj_error_px": float(ret),
        "K": K.tolist(),
        "distortion_radtan5": [float(k1), float(k2), float(p1), float(p2), float(k3)],
        "fx": float(fx), "fy": float(fy), "cx": float(cx), "cy": float(cy),
    }
    json_path = os.path.join(OUT_DIR, "camera_intrinsics.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)
    print(f"\n保存: {json_path}")

    # Kalibr camchain 格式（cam-imu 联合标定时需要）
    yaml_path = os.path.join(OUT_DIR, "camchain-cam.yaml")
    with open(yaml_path, "w", encoding="utf-8") as f:
        f.write("cam0:\n")
        f.write("  camera_model: pinhole\n")
        f.write(f"  intrinsics: [{fx:.6f}, {fy:.6f}, {cx:.6f}, {cy:.6f}]\n")
        f.write("  distortion_model: radtan\n")
        f.write(f"  distortion_coeffs: [{k1:.6f}, {k2:.6f}, {p1:.6f}, {p2:.6f}]\n")
        f.write(f"  resolution: [{W}, {H}]\n")
        f.write("  rostopic: /cam0/image_raw\n")
    print(f"Kalibr camchain: {yaml_path}")


if __name__ == "__main__":
    main()
