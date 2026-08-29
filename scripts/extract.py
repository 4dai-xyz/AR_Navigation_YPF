import cv2
import os

video_dir = r"G:\ARProjects\AR_Navigation\data\videos"
frame_dir = r"G:\ARProjects\AR_Navigation\data\frames"
os.makedirs(frame_dir, exist_ok=True)

# 设置每隔多少帧提取一次
frame_interval = 30  # 每秒提取1帧，假设30FPS视频

for video_file in os.listdir(video_dir):
    if not video_file.endswith((".mp4", ".avi")):
        continue
    cap = cv2.VideoCapture(os.path.join(video_dir, video_file))
    count = 0
    saved_count = 0
    while True:
        ret, frame = cap.read()
        if not ret:
            break
        if count % frame_interval == 0:
            frame_name = f"{os.path.splitext(video_file)[0]}_frame{saved_count:05d}.jpg"
            cv2.imwrite(os.path.join(frame_dir, frame_name), frame)
            saved_count += 1
        count += 1
    cap.release()
print("间隔帧提取完成！")