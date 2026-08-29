import cv2
import numpy as np
import glob
import os

# ================= 配置区 =================
# 1. 你的标定图片存放路径
IMAGE_DIR = r"G:\kejicompany\calib_images\*.jpg" 

# 2. 棋盘格内部角点的尺寸 (列数, 行数) - 注意：是内部交叉点个数！
CHECKERBOARD = (8, 6) # 如果你是 9x7 的格子，就填 (8, 5)

# 3. 棋盘格真实世界里每个小黑白格子的边长（毫米）
SQUARE_SIZE = 26.0 
# ==========================================

def calibrate_fisheye():
    print(">>> 开始读取图片并寻找角点 (这可能需要十几秒)...")
    
    # 亚像素级角点优化的停止条件
    subpix_criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.1)
    
    # 准备 3D 真实世界坐标
    objp = np.zeros((1, CHECKERBOARD[0] * CHECKERBOARD[1], 3), np.float32)
    objp[0,:,:2] = np.mgrid[0:CHECKERBOARD[0], 0:CHECKERBOARD[1]].T.reshape(-1, 2) * SQUARE_SIZE

    objpoints = [] # 真实世界中的 3D 点
    imgpoints = [] # 图像平面中的 2D 角点

    images = glob.glob(IMAGE_DIR)
    if not images:
        return print(f">>> 错误：未在 {IMAGE_DIR} 找到任何图片，请检查路径。")

    success_count = 0
    gray_shape = None

    for fname in images:
        img = cv2.imread(fname)
        if img is None: continue
        
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        gray_shape = gray.shape[::-1]

        # 寻找棋盘格角点
        ret, corners = cv2.findChessboardCorners(gray, CHECKERBOARD, 
            cv2.CALIB_CB_ADAPTIVE_THRESH + cv2.CALIB_CB_FAST_CHECK + cv2.CALIB_CB_NORMALIZE_IMAGE)

        if ret:
            success_count += 1
            objpoints.append(objp)
            # 进一步精细化角点位置
            corners_subpix = cv2.cornerSubPix(gray, corners, (3, 3), (-1, -1), subpix_criteria)
            imgpoints.append(corners_subpix)
            print(f"  [成功] 找到角点: {os.path.basename(fname)}")
        else:
            print(f"  [跳过] 未能完全识别角点: {os.path.basename(fname)} (可能边缘太模糊或截断)")

    if success_count < 10:
        print(f"\n>>> 警告：只成功提取了 {success_count} 张图的角点，标定结果可能不准。建议至少需要 15 张以上。")
    else:
        print(f"\n>>> 总计成功提取 {success_count} 张图像的角点，开始计算鱼眼矩阵...\n")

    # 鱼眼标定标志位 (解决超广角畸变的核心)
    flags = cv2.fisheye.CALIB_RECOMPUTE_EXTRINSIC | cv2.fisheye.CALIB_CHECK_COND | cv2.fisheye.CALIB_FIX_SKEW

    K = np.zeros((3, 3))
    D = np.zeros((4, 1))

    # 执行 Kannala-Brandt 鱼眼标定
    rms, K, D, rvecs, tvecs = cv2.fisheye.calibrate(
        objpoints, imgpoints, gray_shape, K, D, None, None, flags
    )

    print("=" * 45)
    print(" 🎯 ORB-SLAM3 超广角 YAML 配置参数 (KannalaBrandt8)")
    print("=" * 45)
    print(f"Camera.type: \"KannalaBrandt8\"")
    print(f"Camera.fx: {K[0, 0]:.5f}")
    print(f"Camera.fy: {K[1, 1]:.5f}")
    print(f"Camera.cx: {K[0, 2]:.5f}")
    print(f"Camera.cy: {K[1, 2]:.5f}")
    print(f"Camera.k1: {D[0][0]:.5f}")
    print(f"Camera.k2: {D[1][0]:.5f}")
    print(f"Camera.k3: {D[2][0]:.5f}")
    print(f"Camera.k4: {D[3][0]:.5f}")
    print("-" * 45)
    print(f"Camera.width: {gray_shape[0]}")
    print(f"Camera.height: {gray_shape[1]}")
    print(f"📊 重投影误差 (RMS): {rms:.5f} px (越小越好，通常应 < 1.0)")
    print("=" * 45)

if __name__ == '__main__':
    calibrate_fisheye()