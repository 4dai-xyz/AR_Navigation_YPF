from ultralytics import YOLO

# 使用YOLOv8n (nano) 轻量化模型
model = YOLO("yolov8n.pt")

# 训练
model.train(
    data="data/obstacle_data.yaml",  # 定义训练/验证路径及类别
    epochs=50,
    imgsz=640,
    batch=8,
    device=0
)

# 保存权重
model.save("models/obstacle.pth")