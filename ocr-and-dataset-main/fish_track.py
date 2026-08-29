import cv2
import numpy as np

# ================= 1. 鱼眼相机参数 (保持不变) =================
K = np.array([[583.16352, 0.0,       625.18886],
              [0.0,       581.04031, 365.10467],
              [0.0,       0.0,       1.0      ]])
D = np.array([0.34904, 0.12169, -0.20168, 0.39384])
DIM = (1280, 720)

new_K = cv2.fisheye.estimateNewCameraMatrixForUndistortRectify(K, D, DIM, np.eye(3), balance=0.5)
map1, map2 = cv2.fisheye.initUndistortRectifyMap(K, D, np.eye(3), new_K, DIM, cv2.CV_16SC2)
# ==========================================================

# 全局变量
points_to_track = []

def select_points(event, x, y, flags, param):
    """鼠标回调函数：左键点击添加追踪点"""
    global points_to_track
    if event == cv2.EVENT_LBUTTONDOWN:
        points_to_track.append([[np.float32(x), np.float32(y)]])
        print(f">>> 啪！钉入特征点: ({x}, {y})")

def run_optical_flow_tracker(video_path):
    global points_to_track
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        print(">>> 错误：无法打开视频文件")
        return

    cv2.namedWindow("Optical Flow Tracker", cv2.WINDOW_NORMAL)
    cv2.resizeWindow("Optical Flow Tracker", 1280, 720)
    cv2.setMouseCallback("Optical Flow Tracker", select_points)

    # 光流法参数
    lk_params = dict(winSize=(21, 21), maxLevel=3, 
                     criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 30, 0.01))

    # 💡 核心新增：状态控制
    is_paused = True   # 默认启动时先暂停，方便用户打点
    is_tracking = False
    old_gray = None
    p0 = None

    print("\n" + "="*50)
    print(">>> 🎯 狙击手模式已就绪 (视频已暂停)")
    print(">>> 【鼠标左键】: 在目标上打点")
    print(">>> 【回车键 Enter】: 锁定目标，恢复播放并开始追踪")
    print(">>> 【空格键 Space】: 随时暂停/恢复视频")
    print(">>> 【C 键】: 清除所有点重新来过")
    print(">>> 【Q 键】: 退出")
    print("="*50 + "\n")

    # 提前读取第一帧并去畸变，挂在屏幕上等你操作
    ret, frame = cap.read()
    if not ret: return
    undistorted_frame = cv2.remap(frame, map1, map2, interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
    frame_gray = cv2.cvtColor(undistorted_frame, cv2.COLOR_BGR2GRAY)

    while True:
        # 如果不是暂停状态，才去读取下一帧视频
        if not is_paused:
            ret, frame = cap.read()
            if not ret:
                print(">>> 视频播放完毕")
                break
            # 实时去畸变
            undistorted_frame = cv2.remap(frame, map1, map2, interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
            frame_gray = cv2.cvtColor(undistorted_frame, cv2.COLOR_BGR2GRAY)

        # 深拷贝一份用来画图，避免把原始数据画脏了
        display_frame = undistorted_frame.copy()

        # 右上角提示当前状态
        status_text = "PAUSED" if is_paused else "PLAYING"
        color = (0, 0, 255) if is_paused else (0, 255, 0)
        cv2.putText(display_frame, f"Status: {status_text}", (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1, color, 2)

        # ================= 执行追踪逻辑 =================
        if is_tracking and p0 is not None and len(p0) > 0:
            # 只有在画面播放时，才计算光流
            if not is_paused:
                p1, st, err = cv2.calcOpticalFlowPyrLK(old_gray, frame_gray, p0, None, **lk_params)
                if p1 is not None:
                    good_new = p1[st == 1]
                    good_old = p0[st == 1]
                    p0 = good_new.reshape(-1, 1, 2)
                    old_gray = frame_gray.copy() # 更新老图，为下一帧做准备
                
                if len(p0) == 0:
                    is_tracking = False
                    print(">>> 警告：所有特征点均已丢失！")

            # 无论视频是否暂停，都要把存活的点画出来
            for pt in p0:
                a, b = int(pt[0][0]), int(pt[0][1])
                cv2.circle(display_frame, (a, b), 5, (0, 0, 255), -1)
                cv2.drawMarker(display_frame, (a, b), (0, 255, 0), cv2.MARKER_CROSS, 15, 2)

        else:
            # 如果还没开始追踪，画出你正在打的蓝色准备点
            for pt in points_to_track:
                x, y = int(pt[0][0]), int(pt[0][1])
                cv2.circle(display_frame, (x, y), 5, (255, 0, 0), -1)

        cv2.imshow("Optical Flow Tracker", display_frame)

        # ================= 键盘交互控制 =================
        key = cv2.waitKey(30) & 0xFF

        if key == ord(' '):  # 按空格：仅仅用来暂停/恢复画面
            is_paused = not is_paused
            print(f">>> 视频 {'已暂停' if is_paused else '恢复播放'}")

        elif key == 13:      # 按回车 (Enter) 键：开始追踪
            if len(points_to_track) > 0:
                old_gray = frame_gray.copy()
                p0 = np.array(points_to_track, dtype=np.float32)
                is_tracking = True
                is_paused = False # 锁定后自动取消暂停，让子弹飞
                points_to_track = [] # 清空缓冲池
                print(">>> 目标锁定，开始追踪！")
            elif is_tracking:
                print(">>> 已经在追踪中了。")
            else:
                print(">>> 空仓操作！请先用鼠标打点。")
                
        elif key == ord('c'):  # 按 C 键：重置一切
            points_to_track = []
            p0 = None
            is_tracking = False
            is_paused = True   # 清除后强制暂停，等你重新打点
            print(">>> 追踪已清除，画面已暂停，请重新打点。")

        elif key == ord('q'):
            break

    cap.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    # 👇 换成你的视频路径
    video_file = r"G:\kejicompany\2026-05-06_105035_048.mp4" 
    run_optical_flow_tracker(video_file)
