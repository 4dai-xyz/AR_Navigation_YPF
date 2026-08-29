import cv2
import torch
import numpy as np
from torchvision import transforms
from torchvision.models.segmentation import deeplabv3_resnet50
from dataset import SegmentationDataset

DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'

# 加载模型
seg_model = deeplabv3_resnet50(weights=None)
seg_model.classifier[4] = torch.nn.Conv2d(256, 2, kernel_size=1)
seg_model.load_state_dict(torch.load("models/segmentation.pth", map_location=DEVICE, weights_only=True), strict=False)
seg_model.to(DEVICE)
seg_model.eval()

# 测试数据
test_transform = transforms.Compose([
    transforms.ToTensor(),
    transforms.Resize((320, 320)),
    transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
])

test_dataset = SegmentationDataset(
    frames_dir=r"G:\ARProjects\AR_Navigation\data\frames1",
    masks_dir=r"G:\ARProjects\AR_Navigation\data\masks1",
    transform=test_transform
)

# 检查前几个样本
print(f"数据集大小: {len(test_dataset)}")

for i in range(min(5, len(test_dataset))):
    img, mask = test_dataset[i]
    print(f"\n样本 {i}:")
    print(f"  图像形状: {img.shape}")
    print(f"  Mask 形状: {mask.shape}")
    print(f"  Mask 唯一值: {np.unique(mask.numpy())}")
    print(f"  Mask 值统计 - 0的数量: {torch.sum(mask == 0)}, 1的数量: {torch.sum(mask == 1)}")
    
    # 模型预测
    input_img = img.unsqueeze(0).to(DEVICE)
    with torch.no_grad():
        output = seg_model(input_img)['out']
        pred_mask = torch.argmax(output.squeeze(), dim=0).cpu().numpy()
    
    print(f"  预测结果唯一值: {np.unique(pred_mask)}")
    print(f"  预测值统计 - 0的数量: {np.sum(pred_mask == 0)}, 1的数量: {np.sum(pred_mask == 1)}")
    
    # 计算准确率
    mask_np = mask.squeeze().numpy()
    accuracy = np.mean(pred_mask == mask_np)
    print(f"  准确率: {accuracy:.4f}")

# 检查是否模型预测全为0
print("\n=== 检查模型是否总是预测为0 ===")
total_zeros = 0
total_ones = 0
for i in range(min(20, len(test_dataset))):
    img, mask = test_dataset[i]
    input_img = img.unsqueeze(0).to(DEVICE)
    with torch.no_grad():
        output = seg_model(input_img)['out']
        pred_mask = torch.argmax(output.squeeze(), dim=0).cpu().numpy()
    total_zeros += np.sum(pred_mask == 0)
    total_ones += np.sum(pred_mask == 1)

print(f"预测统计（20个样本）:")
print(f"  预测为0的像素数: {total_zeros}")
print(f"  预测为1的像素数: {total_ones}")
print(f"  1占比: {total_ones / (total_zeros + total_ones) * 100:.2f}%")

# 检查训练数据中的类别分布
print("\n=== 检查训练数据类别分布 ===")
total_pixels_0 = 0
total_pixels_1 = 0
for i in range(min(20, len(test_dataset))):
    img, mask = test_dataset[i]
    total_pixels_0 += torch.sum(mask == 0)
    total_pixels_1 += torch.sum(mask == 1)

print(f"训练数据统计（20个样本）:")
print(f"  类别0像素数: {total_pixels_0}")
print(f"  类别1像素数: {total_pixels_1}")
print(f"  类别1占比: {total_pixels_1 / (total_pixels_0 + total_pixels_1) * 100:.2f}%")