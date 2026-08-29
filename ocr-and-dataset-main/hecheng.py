import cv2
import json
import os
import re

# ================= 配置区 =================
IMG_DIR = r"G:\kejicompany\tracker\images1_5fps"
JSON_PATH = "slam_data_final.json"
OUTPUT_VIDEO = "tracking_result_demo.mp4"
FPS = 5  # 建议和你之前的采样率一致
# ==========================================

def natural_sort_key(s):
    return [int(text) if text.isdigit() else text.lower()
            for text in re.split('([0-9]+)', s)]

def visualize_results():
    # 1. 加载数据
    if not os.path.exists(JSON_PATH):
        print(f">>> 错误：找不到数据文件 {JSON_PATH}")
        return
    
    with open(JSON_PATH, 'r') as f:
        data = json.load(f)
        # 兼容之前带 metadata 的格式
        frames_data = data.get("frames", data)

    # 2. 获取图片列表并排序（必须与标注时顺序一致）
    img_names = [f for f in os.listdir(IMG_DIR) if f.endswith(('.jpg', '.png'))]
    img_names.sort(key=natural_sort_key)

    if not img_names:
        print(">>> 错误：图片文件夹为空")
        return

    # 3. 初始化视频写入器
    first_img = cv2.imread(os.path.join(IMG_DIR, img_names[0]))
    h, w, _ = first_img.shape
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    out_video = cv2.VideoWriter(OUTPUT_VIDEO, fourcc, FPS, (w, h))

    print(f">>> 开始合成视频，共 {len(img_names)} 帧...")

    for idx, name in enumerate(img_names):
        frame = cv2.imread(os.path.join(IMG_DIR, name))
        if frame is None: continue

        # 4. 如果这一帧有标注数据，就画出来
        if name in frames_data:
            logos = frames_data[name]
            for label, info in logos.items():
                # 获取矩形框坐标 [x, y, w, h]
                bbox = info.get("bbox")
                if bbox:
                    x, y, bw, bh = [int(v) for v in bbox]
                    # 画矩形框
                    cv2.rectangle(frame, (x, y), (x + bw, y + bh), (0, 255, 0), 2)
                    # 画中心点
                    center = info.get("center")
                    if center:
                        cv2.circle(frame, (int(center[0]), int(center[1])), 5, (0, 0, 255), -1)
                    # 写名字和 ID
                    cv2.putText(frame, label, (x, y - 10), 
                                cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)

        # 在左上角印上文件名，方便定位问题
        cv2.putText(frame, f"File: {name}", (20, 40), 0, 0.8, (255, 255, 255), 2)
        
        # 写入视频并预览
        out_video.write(frame)
        cv2.imshow("Review Result", cv2.resize(frame, (w//2, h//2))) # 缩小一半显示，防止超出屏幕
        
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

        if idx % 500 == 0:
            print(f"处理进度: {idx}/{len(img_names)}")

    out_video.release()
    cv2.destroyAllWindows()
    print(f"\n>>> 视频合成完毕！请查看: {os.path.abspath(OUTPUT_VIDEO)}")

if __name__ == "__main__":
    visualize_results()