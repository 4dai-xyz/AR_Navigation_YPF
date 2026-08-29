import torch
from torch import nn
from torch.utils.data import DataLoader, random_split
from torchvision import transforms
from torchvision.models.segmentation import deeplabv3_resnet50
from dataset import SegmentationDataset
from torchvision.models.segmentation import fcn_resnet50
import matplotlib.pyplot as plt
import numpy as np

DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'
BATCH_SIZE = 8
EPOCHS = 30
LR = 1e-3
VAL_SPLIT = 0.2

if __name__ == '__main__':
    # -------------------------------
    # 数据增强和变换
    train_transform = transforms.Compose([
        transforms.Resize((320, 320)),
        transforms.RandomHorizontalFlip(p=0.5),
        transforms.RandomRotation(10),
        transforms.ColorJitter(brightness=0.2, contrast=0.2, saturation=0.2, hue=0.1),
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
    ])
    
    val_transform = transforms.Compose([
        transforms.Resize((320, 320)),
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
    ])

    # -------------------------------
    # 数据集
    full_dataset = SegmentationDataset(
        frames_dir=r"G:\ARProjects\AR_Navigation\data\frames1",
        masks_dir=r"G:\ARProjects\AR_Navigation\data\masks1",
        transform=train_transform
    )
    
    # 划分训练集和验证集
    val_size = int(VAL_SPLIT * len(full_dataset))
    train_size = len(full_dataset) - val_size
    train_dataset, val_dataset = random_split(full_dataset, [train_size, val_size])
    
    # 验证集使用验证变换
    val_dataset.dataset.transform = val_transform
    
    print(f"训练集大小: {len(train_dataset)}")
    print(f"验证集大小: {len(val_dataset)}")

    train_loader = DataLoader(train_dataset, batch_size=BATCH_SIZE, shuffle=True, num_workers=4, pin_memory=True)
    val_loader = DataLoader(val_dataset, batch_size=BATCH_SIZE, shuffle=False, num_workers=4, pin_memory=True)

    # -------------------------------
    # 模型
    from torchvision.models.segmentation import DeepLabV3_ResNet50_Weights
    model = deeplabv3_resnet50(weights=DeepLabV3_ResNet50_Weights.DEFAULT)
    model.classifier[4] = nn.Conv2d(256, 2, kernel_size=1)
    model = model.to(DEVICE)

    # -------------------------------
    # 损失函数和优化器
    criterion = nn.CrossEntropyLoss(weight=torch.tensor([1.0, 2.0]).to(DEVICE))  # 平衡类别权重
    optimizer = torch.optim.Adam(model.parameters(), lr=LR)
    scheduler = torch.optim.lr_scheduler.StepLR(optimizer, step_size=15, gamma=0.5)

    # -------------------------------
    # 训练循环
    train_losses = []
    val_losses = []
    
    for epoch in range(EPOCHS):
        model.train()
        total_train_loss = 0
        
        for imgs, masks in train_loader:
            imgs = imgs.to(DEVICE)
            masks = masks.squeeze(1).to(DEVICE)
            optimizer.zero_grad()
            outputs = model(imgs)['out']
            loss = criterion(outputs, masks)
            loss.backward()
            optimizer.step()
            total_train_loss += loss.item()
        
        # 验证
        model.eval()
        total_val_loss = 0
        with torch.no_grad():
            for imgs, masks in val_loader:
                imgs = imgs.to(DEVICE)
                masks = masks.squeeze(1).to(DEVICE)
                outputs = model(imgs)['out']
                loss = criterion(outputs, masks)
                total_val_loss += loss.item()
        
        train_loss = total_train_loss / len(train_loader)
        val_loss = total_val_loss / len(val_loader) if len(val_loader) > 0 else 0
        
        train_losses.append(train_loss)
        val_losses.append(val_loss)
        
        scheduler.step()
        
        print(f"Epoch {epoch+1}/{EPOCHS}, Train Loss: {train_loss:.4f}, Val Loss: {val_loss:.4f}, LR: {scheduler.get_last_lr()[0]:.6f}")

    # -------------------------------
    # 保存模型
    torch.save(model.state_dict(), r"G:\ARProjects\AR_Navigation\models\segmentation.pth")
    print("Segmentation model saved!")

    # -------------------------------
    # 绘制损失曲线
    plt.figure(figsize=(10, 5))
    plt.plot(train_losses, label='Train Loss')
    plt.plot(val_losses, label='Val Loss')
    plt.xlabel('Epoch')
    plt.ylabel('Loss')
    plt.title('Training and Validation Loss')
    plt.legend()
    plt.savefig(r"G:\ARProjects\AR_Navigation\data\output\loss_curve.png")
    plt.close()
    print("Loss curve saved!")