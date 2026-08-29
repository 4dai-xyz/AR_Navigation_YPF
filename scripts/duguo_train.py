import torch
import torch.nn as nn
import torch.optim as optim
from torchvision import datasets, models, transforms
from torch.utils.data import DataLoader
import time
import os

# ================= 配置区 =================
DATA_ROOT = r"G:\kejicompany\cnn_dataset" # 你的数据路径
BATCH_SIZE = 32      # 2060 显存 6GB，32 是最稳的
EPOCHS = 20         # 既然要过拟合，轮数直接拉满
LEARNING_RATE = 1e-4 # 过拟合建议用较小的学习率，慢慢“磨”进局部最优解
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
SAVE_DIR = "checkpoints"
# ==========================================

def train_for_overfitting():
    if not os.path.exists(SAVE_DIR): os.makedirs(SAVE_DIR)
    
    # 1. 数据预处理（极简模式：只 Resize，不搞复杂的随机变换，方便过拟合）
    data_transforms = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
    ])

    # 2. 加载数据集 (不分验证集，全力冲击 100% 准确率)
    train_dataset = datasets.ImageFolder(DATA_ROOT, transform=data_transforms)
    train_loader = DataLoader(train_dataset, batch_size=BATCH_SIZE, shuffle=True, num_workers=2)
    
    num_classes = len(train_dataset.classes)
    print(f">>> 类别列表: {train_dataset.classes}")
    print(f">>> 训练样本总数: {len(train_dataset)}")

    # 3. 模型初始化 (ResNet18 足够记住几万张 Logo)
    # 开启预训练权重，收敛速度会快 10 倍
    model = models.resnet18(weights=models.ResNet18_Weights.IMAGENET1K_V1)
    
    # 替换最后的全连接层
    num_ftrs = model.fc.in_features
    model.fc = nn.Linear(num_ftrs, num_classes)
    model = model.to(DEVICE)

    # 4. 优化器（Adam 对过拟合非常友好）
    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=LEARNING_RATE)

    # 5. 训练循环
    print(">>> 开始“死记硬背”模式训练...")
    start_time = time.time()

    for epoch in range(EPOCHS):
        model.train()
        running_loss = 0.0
        correct = 0
        total = 0

        for inputs, labels in train_loader:
            inputs, labels = inputs.to(DEVICE), labels.to(DEVICE)
            
            optimizer.zero_grad()
            outputs = model(inputs)
            loss = criterion(outputs, labels)
            loss.backward()
            optimizer.step()

            running_loss += loss.item() * inputs.size(0)
            _, predicted = torch.max(outputs, 1)
            total += labels.size(0)
            correct += (predicted == labels).sum().item()

        epoch_loss = running_loss / len(train_dataset)
        epoch_acc = 100. * correct / total

        if (epoch + 1) % 10 == 0:
            print(f"Epoch [{epoch+1}/{EPOCHS}] Loss: {epoch_loss:.4f} Acc: {epoch_acc:.2f}%")
            # 自动保存
            torch.save(model.state_dict(), f"{SAVE_DIR}/logo_overfit_e{epoch+1}.pth")

        # 如果准确率已经 100% 且 Loss 极低，可以提前收工
        if epoch_acc > 99.99 and epoch_loss < 0.001:
            print(">>> 达成完美过拟合目标，提前停止。")
            break

    duration = (time.time() - start_time) / 60
    print(f">>> 训练结束！耗时: {duration:.2f} 分钟")
    torch.save(model.state_dict(), "logo_model_final.pth")

if __name__ == "__main__":
    train_for_overfitting()