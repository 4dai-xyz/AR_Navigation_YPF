from ultralytics import YOLO

model = YOLO("yolov8n.pt")
model.train(
    data="data/logo_data.yaml", 
    epochs=50,
    imgsz=640,
    batch=8,
    device=0
)
model.save("models/logo.pth")