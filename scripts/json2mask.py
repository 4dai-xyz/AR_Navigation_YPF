import os
import json
import cv2
import numpy as np
from PIL import Image

# -------------------------------
# 配置路径
# frames_dir = r"G:\ARProjects\AR_Navigation\data\frames1"   # 图片+JSON文件目录
# masks_dir  = r"G:\ARProjects\AR_Navigation\data\masks2"    # 输出mask目录
frames_dir = r"G:\ARProjects\gaode2\frames"   # 图片+JSON文件目录
masks_dir  = r"G:\ARProjects\gaode2\masks"    # 输出mask目录
os.makedirs(masks_dir, exist_ok=True)

# 支持的类别
# 可行区域 = 1, 不可行区域 = 0 (默认)
walkable_labels = ['walkable', '1']
unwalkable_labels = ['unwalkable', '0']

# -------------------------------
# 批量处理
json_files = [f for f in os.listdir(frames_dir) if f.endswith(".json")]

for json_file in json_files:
    json_path = os.path.join(frames_dir, json_file)
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    
    # 获取对应图片
    img_file = json_file.replace(".json", ".jpg")
    img_path = os.path.join(frames_dir, img_file)
    if not os.path.exists(img_path):
        print(f"Warning: {img_file} not found, skipping.")
        continue
    
    img = cv2.imread(img_path)
    h, w = img.shape[:2]
    
    # 初始化 mask 全部为0（不可行区域）
    mask = np.zeros((h, w), dtype=np.uint8)
    
    # 遍历标注形状
    for shape in data.get('shapes', []):
        points = np.array(shape['points'], dtype=np.int32)
        label = shape['label'].lower()
        if label in walkable_labels:
            cv2.fillPoly(mask, [points], 255)  # 可行区域标1
        elif label in unwalkable_labels:
            cv2.fillPoly(mask, [points], 0)  # 不可行区域标0
        # 其他未标注或其他label都保持0
    
    # 保存 mask
    mask_file = os.path.join(masks_dir, img_file)
    Image.fromarray(mask).save(mask_file)

print(f"批量处理完成，共生成 {len(json_files)} 个 mask 文件。")