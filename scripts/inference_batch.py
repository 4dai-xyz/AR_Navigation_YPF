import os
import cv2
import torch
import numpy as np
from torchvision import transforms
from torchvision.models.segmentation import deeplabv3_resnet50

DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'

# 加载可行区域分割模型
seg_model = deeplabv3_resnet50(weights=None)
seg_model.classifier[4] = torch.nn.Conv2d(256, 2, kernel_size=1)
seg_model.load_state_dict(
    torch.load("models/segmentation.pth", map_location=DEVICE, weights_only=True),
    strict=False,
)
seg_model.to(DEVICE)
seg_model.eval()

# # 加载障碍物检测模型
# from ultralytics import YOLO
# obstacle_model = YOLO("models/obstacle.pth")

# # 加载标志物检测模型
# logo_model = YOLO("models/logo.pth")

transform = transforms.Compose([
    transforms.ToTensor(),
    transforms.Resize((320, 320)),
    transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
])

# 透明度参数 (0.0 = 完全透明, 1.0 = 完全不透明)
alpha = 0.3

# 批处理输入/输出根目录
INPUT_ROOT = r"H:\ForPengfei\04142024\Front"
OUTPUT_ROOT = r"H:\ForPengfei\04142024\Front\output"
SUBDIRS = ["1", "2", "3"]
SHOW_PREVIEW = False  # 批处理时建议关闭实时预览
SKIP_EXISTING = True  # 已存在输出文件时是否跳过


def process_video(input_path, output_path):
    cap = cv2.VideoCapture(input_path)
    if not cap.isOpened():
        print(f"  Error: 无法打开视频文件 {input_path}")
        return True  # 不算用户中断，继续处理后续

    fps = cap.get(cv2.CAP_PROP_FPS)
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    print(f"  帧率: {fps}, 尺寸: {width}x{height}, 总帧数: {total_frames}")
    print(f"  输出: {output_path}")

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    out = cv2.VideoWriter(output_path, fourcc, fps, (width, height))

    frame_count = 0
    user_quit = False
    while cap.isOpened():
        ret, frame = cap.read()
        if not ret:
            break

        frame_count += 1
        if frame_count % 50 == 0:
            print(f"  处理第 {frame_count}/{total_frames} 帧")

        original_h, original_w = frame.shape[:2]

        # 1. 可行区域分割
        input_img = transform(frame).unsqueeze(0).to(DEVICE)
        with torch.no_grad():
            output = seg_model(input_img)['out']
            pred_mask = torch.argmax(output.squeeze(), dim=0).cpu().numpy()

        pred_mask = cv2.resize(
            pred_mask.astype(np.uint8), (original_w, original_h),
            interpolation=cv2.INTER_NEAREST,
        )

        mask_overlay = frame.copy()
        color_mask = np.zeros_like(frame)
        color_mask[pred_mask == 1] = [0, 255, 0]  # 可行区域绿色
        color_mask[pred_mask == 0] = [0, 0, 255]  # 不可行区域红色
        cv2.addWeighted(color_mask, alpha, mask_overlay, 1 - alpha, 0, mask_overlay)

        # # 2. 障碍物检测
        # results_obstacle = obstacle_model(frame)
        # for r in results_obstacle.xyxy[0]:
        #     x1, y1, x2, y2, conf, cls = r
        #     cv2.rectangle(mask_overlay, (int(x1), int(y1)), (int(x2), int(y2)), (0,0,255), 2)
        #     cv2.putText(mask_overlay, f"Obstacle:{int(cls)}", (int(x1), int(y1)-5),
        #                 cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0,0,255), 1)

        # # 3. 标志物检测（模型未加载，已跳过）
        # frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        # input_tensor = transform(frame_rgb).unsqueeze(0).to(DEVICE)
        # results_logo = logo_model(input_tensor)
        # for r in results_logo.xyxy[0]:
        #     x1, y1, x2, y2, conf, cls = r
        #     cv2.rectangle(mask_overlay, (int(x1), int(y1)), (int(x2), int(y2)), (0,255,255), 2)
        #     cv2.putText(mask_overlay, f"Logo:{int(cls)}", (int(x1), int(y1)-5),
        #                 cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0,255,255), 1)

        out.write(mask_overlay)

        if SHOW_PREVIEW:
            cv2.namedWindow("AR Navigation", cv2.WINDOW_NORMAL)
            cv2.resizeWindow("AR Navigation", 640, 480)
            cv2.imshow("AR Navigation", mask_overlay)
            key = cv2.waitKey(1) & 0xFF
            if key == ord('q'):
                print("  用户按q退出")
                user_quit = True
                break

    cap.release()
    out.release()
    if SHOW_PREVIEW:
        cv2.destroyAllWindows()
    print(f"  完成: {output_path}")
    return not user_quit


def collect_videos(root, subdirs):
    tasks = []
    for sub in subdirs:
        in_dir = os.path.join(root, sub)
        if not os.path.isdir(in_dir):
            print(f"跳过：输入目录不存在 {in_dir}")
            continue
        files = sorted(
            f for f in os.listdir(in_dir)
            if f.lower().endswith(".mp4") and os.path.isfile(os.path.join(in_dir, f))
        )
        for fname in files:
            in_path = os.path.join(in_dir, fname)
            stem, _ = os.path.splitext(fname)
            out_path = os.path.join(OUTPUT_ROOT, sub, f"{stem}_output.mp4")
            tasks.append((in_path, out_path))
    return tasks


def main():
    tasks = collect_videos(INPUT_ROOT, SUBDIRS)
    if not tasks:
        print("未找到任何 MP4 文件。")
        return

    print(f"共发现 {len(tasks)} 个待处理视频。")
    for idx, (in_path, out_path) in enumerate(tasks, 1):
        print(f"\n[{idx}/{len(tasks)}] {in_path}")
        if SKIP_EXISTING and os.path.exists(out_path):
            print(f"  已存在，跳过: {out_path}")
            continue
        proceed = process_video(in_path, out_path)
        if not proceed:
            print("用户中断，停止后续处理。")
            break

    print("\n全部任务结束。")


if __name__ == "__main__":
    main()
