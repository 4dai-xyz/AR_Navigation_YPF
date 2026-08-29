import cv2
import torch
import numpy as np
from torchvision import transforms
from torchvision.models.segmentation import deeplabv3_resnet50
from ultralytics import YOLO
import torchvision.models as models
import torch.nn as nn
from torchvision.models.detection import fasterrcnn_resnet50_fpn

DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'

# # 使用与训练相同的模型架构（例如 resnet18）
# logo_model = models.resnet18(weights=None)
# logo_model.fc = nn.Linear(logo_model.fc.in_features, 130)  # 匹配训练时的类别数
# logo_model.load_state_dict(torch.load("models/logo.pth", map_location=DEVICE))
# logo_model.to(DEVICE)
# logo_model.eval()


# 加载可行区域分割模型
seg_model = deeplabv3_resnet50(weights=None)
seg_model.classifier[4] = torch.nn.Conv2d(256, 2, kernel_size=1)
seg_model.load_state_dict(torch.load("models/segmentation.pth", map_location=DEVICE, weights_only=True), strict=False)
seg_model.to(DEVICE)
seg_model.eval()

# # 加载障碍物检测模型
# obstacle_model = YOLO("models/obstacle.pth")

# # 加载标志物检测模型
# logo_model = YOLO("models/logo.pth")

# 视频输入 (AR眼镜采集或本地视频)
# cap = cv2.VideoCapture(r"G:\ARProjects\AR_Navigation\data\videos\1.mp4")
cap = cv2.VideoCapture(r"I:\20000101000137156.mp4")


# 检查视频是否成功打开
if not cap.isOpened():
    print("Error: 无法打开视频文件")
    exit()

fps = cap.get(cv2.CAP_PROP_FPS)
width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

print(f"视频帧率: {fps}")
print(f"视频宽度: {width}")
print(f"视频高度: {height}")
print(f"视频总帧数: {total_frames}")

# 视频输出设置
output_path = r"I:\20000101000137156_output.mp4"
# output_path = r"H:\ForPengfei\04142024\Front\output\1\1_output.mp4"
# 确保输出目录存在
import os
os.makedirs(os.path.dirname(output_path), exist_ok=True)

# 创建视频写入器
fourcc = cv2.VideoWriter_fourcc(*'mp4v')
out = cv2.VideoWriter(output_path, fourcc, fps, (width, height))

transform = transforms.Compose([
    transforms.ToTensor(),
    transforms.Resize((320, 320)),
    transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
])

# 透明度参数 (0.0 = 完全透明, 1.0 = 完全不透明)
alpha = 0.8

frame_count = 0
while cap.isOpened():
    ret, frame = cap.read()
    if not ret:
        print("视频读取完毕或出错")
        break
    
    frame_count += 1
    if frame_count % 50 == 0:
        print(f"处理第 {frame_count}/{total_frames} 帧")
    
    # 保存原始尺寸
    original_h, original_w = frame.shape[:2]
    
    # 1. 可行区域分割
    input_img = transform(frame).unsqueeze(0).to(DEVICE)
    with torch.no_grad():
        output = seg_model(input_img)['out']
        pred_mask = torch.argmax(output.squeeze(), dim=0).cpu().numpy()
    
    # 将mask resize回原始尺寸
    pred_mask = cv2.resize(pred_mask.astype(np.uint8), (original_w, original_h), interpolation=cv2.INTER_NEAREST)
    
    # 创建半透明叠加效果
    mask_overlay = frame.copy()
    
    # # 创建颜色掩码
    # color_mask = np.zeros_like(frame)
    # color_mask[pred_mask == 1] = [0, 255, 0]  # 可行区域绿色
    # # color_mask[pred_mask == 0] = [0, 0, 255]  # 不可行区域红色
    
    # # 半透明叠加
    # cv2.addWeighted(color_mask, alpha, mask_overlay, 1 - alpha, 0, mask_overlay)
    
    # 创建颜色掩码（只有绿色）
    color_mask = np.zeros_like(frame)
    color_mask[pred_mask == 1] = [0, 255, 0]

    # 创建透明度掩码
    alpha_mask = np.zeros((frame.shape[0], frame.shape[1]), dtype=np.float32)
    alpha_mask[pred_mask == 1] = alpha  # 可行区域应用透明度
    alpha_mask[pred_mask == 0] = 0  # 不可行区域完全透明

    # 逐通道叠加
    mask_overlay = frame.copy()
    for c in range(3):
        mask_overlay[:, :, c] = frame[:, :, c] * (1 - alpha_mask) + color_mask[:, :, c] * alpha_mask


    # # 2. 障碍物检测
    # results_obstacle = obstacle_model(frame)
    # for r in results_obstacle.xyxy[0]:
    #     x1, y1, x2, y2, conf, cls = r
    #     cv2.rectangle(mask_overlay, (int(x1), int(y1)), (int(x2), int(y2)), (0,0,255), 2)
    #     cv2.putText(mask_overlay, f"Obstacle:{int(cls)}", (int(x1), int(y1)-5),
    #                 cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0,0,255), 1)
    
    # # 3. 标志物检测
    # frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    # input_tensor = transform(frame_rgb).unsqueeze(0).to(DEVICE)
    
    # results_logo = logo_model(input_tensor)
    # for r in results_logo.xyxy[0]:
    #     x1, y1, x2, y2, conf, cls = r
    #     cv2.rectangle(mask_overlay, (int(x1), int(y1)), (int(x2), int(y2)), (0,255,255), 2)
    #     cv2.putText(mask_overlay, f"Logo:{int(cls)}", (int(x1), int(y1)-5),
    #                 cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0,255,255), 1)
    
    # 写入输出视频
    out.write(mask_overlay)
    
    # 显示
    cv2.namedWindow("AR Navigation", cv2.WINDOW_NORMAL)
    cv2.resizeWindow("AR Navigation", 640, 480)
    cv2.imshow("AR Navigation", mask_overlay)
    
    # 使用更长的等待时间
    key = cv2.waitKey(1) & 0xFF
    if key == ord('q'):
        print("用户按q退出")
        break

cap.release()
out.release()
cv2.destroyAllWindows()
print(f"程序结束！结果已保存到: {output_path}")