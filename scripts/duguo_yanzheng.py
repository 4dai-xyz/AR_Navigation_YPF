import torch
import torch.nn as nn
from torchvision import datasets, models, transforms
from torch.utils.data import DataLoader
import os

# ================= 配置区 =================
DATA_ROOT = r"G:\kejicompany\cnn_dataset"
MODEL_PATH = r"G:\kejicompany\tracker\logo_model_final.pth"  # 确保名字对得上
BATCH_SIZE = 32
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
# ==========================================

def evaluate_with_jitter():
    print(f">>> 正在加载压力测试环境 (使用设备: {DEVICE})...")
    
    # --- 核心修改区：引入随机裁剪模拟框偏移 ---
    test_transforms = transforms.Compose([
        # 随机裁剪原图 70% 到 100% 的区域，长宽比允许微小形变
        transforms.RandomResizedCrop(224, scale=(0.7, 1.0), ratio=(0.9, 1.1)),
        # 可以再稍微加一点点亮度随机，模拟 SLAM 中不同角度的反光
        transforms.ColorJitter(brightness=0.1, contrast=0.1),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
    ])

    test_dataset = datasets.ImageFolder(DATA_ROOT, transform=test_transforms)
    # shuffle=True 让每次跑输出的扰动顺序稍微有点随机感（虽然算总分没影响）
    test_loader = DataLoader(test_dataset, batch_size=BATCH_SIZE, shuffle=False)
    
    classes = test_dataset.classes
    num_classes = len(classes)
    
    print(f">>> 载入数据集成功。共 {len(test_dataset)} 张图片参与随机裁剪抗性测试。")

    model = models.resnet18(weights=None)
    num_ftrs = model.fc.in_features
    model.fc = nn.Linear(num_ftrs, num_classes)
    
    if os.path.exists(MODEL_PATH):
        model.load_state_dict(torch.load(MODEL_PATH, map_location=DEVICE))
    else:
        print(f">>> 错误：找不到 {MODEL_PATH}")
        return
        
    model = model.to(DEVICE)
    model.eval()

    class_correct = {classname: 0 for classname in classes}
    class_total = {classname: 0 for classname in classes}
    total_correct = 0

    print(">>> 开始进行带 BBox Jitter 的压力测试...\n")
    with torch.no_grad():
        for inputs, labels in test_loader:
            inputs, labels = inputs.to(DEVICE), labels.to(DEVICE)
            outputs = model(inputs)
            _, predictions = torch.max(outputs, 1)
            
            total_correct += (predictions == labels).sum().item()
            for label, prediction in zip(labels, predictions):
                if label == prediction:
                    class_correct[classes[label]] += 1
                class_total[classes[label]] += 1

    print("=" * 45)
    print(" 🧨 模型鲁棒性压力测试报告 (Random Crop)")
    print("=" * 45)
    
    overall_acc = 100.0 * total_correct / len(test_dataset)
    print(f"🌟 抗干扰整体准确率: {overall_acc:.2f}%\n")
    
    print("🏷️ 各类别抗干扰准确率:")
    for classname in classes:
        if class_total[classname] > 0:
            acc = 100.0 * class_correct[classname] / class_total[classname]
            # 重点标记那些跌破 90% 的危险类别
            warning = " ⚠️ 脆弱" if acc < 90.0 else ""
            print(f"   - {classname:<15}: {acc:>6.2f}% ({class_correct[classname]}/{class_total[classname]}){warning}")
            
    print("=" * 45)

if __name__ == "__main__":
    evaluate_with_jitter()