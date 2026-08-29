import os
import re
import time
import random
from typing import List, Tuple

import torch
import torch.nn as nn
import torch.optim as optim 
from PIL import Image
from torchvision import transforms, models
from torch.utils.data import Dataset, DataLoader


# ================= 1. Config =================
# positive: 直接读取 char_dataset 里所有图片（不看子类）
POS_ROOT = r"G:\kejicompany\char_dataset"
# negative: 沿用原有 negative 样本
POS_ROOT = r"G:\kejicompany\tracker\Huichang_RCNN_Dataset\positive"
NEG_ROOT = r"G:\kejicompany\tracker\Huichang_RCNN_Dataset\negative"

SAVE_NAME = "huichang_logo_detector_binary.pth"
VAL_RATIO = 0.2
RANDOM_SEED = 42

BATCH_SIZE = 32
EPOCHS = 15
LEARNING_RATE = 1e-4
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".bmp", ".webp")
# ============================================


def is_image_file(name: str) -> bool:
    return name.lower().endswith(IMAGE_EXTS)


def collect_images_recursive(root: str) -> List[str]:
    paths = []
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            if is_image_file(fn):
                paths.append(os.path.join(dirpath, fn))
    return sorted(paths)


def source_group_key(path: str) -> str:
    name = os.path.splitext(os.path.basename(path))[0].lower()
    m = re.search(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", name)
    if m:
        return m.group(0)
    name = re.sub(r"_aug.*$", "", name)
    name = re.sub(r"_rot-?\d+(\.\d+)?$", "", name)
    name = re.sub(r"_flip.*$", "", name)
    name = re.sub(r"\s*副本.*$", "", name)
    return name


def split_group_holdout(samples: List[Tuple[str, int]], val_ratio: float, seed: int):
    grouped = {}
    for path, label in samples:
        key = f"{label}:{source_group_key(path)}"
        grouped.setdefault(key, []).append((path, label))

    keys = list(grouped.keys())
    rnd = random.Random(seed)
    rnd.shuffle(keys)

    n_val = max(1, int(len(keys) * val_ratio))
    val_keys = set(keys[:n_val])
    train_keys = set(keys[n_val:])
    if not train_keys:
        train_keys = set(keys[1:])
        val_keys = set(keys[:1])

    train_samples = []
    val_samples = []
    for k in train_keys:
        train_samples.extend(grouped[k])
    for k in val_keys:
        val_samples.extend(grouped[k])
    return train_samples, val_samples


class BinaryDataset(Dataset):
    def __init__(self, samples: List[Tuple[str, int]], transform=None):
        self.samples = samples
        self.transform = transform

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        path, label = self.samples[idx]
        img = Image.open(path).convert("RGB")
        if self.transform is not None:
            img = self.transform(img)
        return img, label


def train_binary_detector():
    print(f"[INFO] train binary detector on {DEVICE}")
    print(f"[INFO] POS_ROOT={POS_ROOT}")
    print(f"[INFO] NEG_ROOT={NEG_ROOT}")

    if not os.path.isdir(POS_ROOT):
        raise ValueError(f"POS_ROOT not found: {POS_ROOT}")
    if not os.path.isdir(NEG_ROOT):
        raise ValueError(f"NEG_ROOT not found: {NEG_ROOT}")

    pos_paths = collect_images_recursive(POS_ROOT)
    neg_paths = collect_images_recursive(NEG_ROOT)

    if len(pos_paths) < 20:
        raise ValueError(f"Too few positive images: {len(pos_paths)}")
    if len(neg_paths) < 20:
        raise ValueError(f"Too few negative images: {len(neg_paths)}")

    # label: negative=0, positive=1
    all_samples = [(p, 1) for p in pos_paths] + [(p, 0) for p in neg_paths]
    train_samples, val_samples = split_group_holdout(all_samples, VAL_RATIO, RANDOM_SEED)

    print(f"[INFO] positive images: {len(pos_paths)}")
    print(f"[INFO] negative images: {len(neg_paths)}")
    print(f"[INFO] train samples: {len(train_samples)} | val samples: {len(val_samples)}")

    train_transforms = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ColorJitter(brightness=0.2, contrast=0.2),
        transforms.RandomAffine(degrees=10, translate=(0.05, 0.05)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])
    val_transforms = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])

    train_dataset = BinaryDataset(train_samples, transform=train_transforms)
    val_dataset = BinaryDataset(val_samples, transform=val_transforms)
    train_loader = DataLoader(train_dataset, batch_size=BATCH_SIZE, shuffle=True)
    val_loader = DataLoader(val_dataset, batch_size=BATCH_SIZE, shuffle=False)

    model = models.resnet18(weights=models.ResNet18_Weights.IMAGENET1K_V1)
    model.fc = nn.Linear(model.fc.in_features, 2)
    model = model.to(DEVICE)

    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=LEARNING_RATE)

    for epoch in range(EPOCHS):
        model.train()
        correct = 0
        total = 0
        start_time = time.time()

        for inputs, labels in train_loader:
            inputs = inputs.to(DEVICE)
            labels = labels.to(DEVICE)

            optimizer.zero_grad()
            outputs = model(inputs)
            loss = criterion(outputs, labels)
            loss.backward()
            optimizer.step()

            _, predicted = torch.max(outputs, 1)
            total += labels.size(0)
            correct += (predicted == labels).sum().item()

        train_acc = 100.0 * correct / max(total, 1)

        model.eval()
        val_correct = 0
        val_total = 0
        with torch.no_grad():
            for inputs, labels in val_loader:
                inputs = inputs.to(DEVICE)
                labels = labels.to(DEVICE)
                outputs = model(inputs)
                _, predicted = torch.max(outputs, 1)
                val_total += labels.size(0)
                val_correct += (predicted == labels).sum().item()

        val_acc = 100.0 * val_correct / max(val_total, 1)
        cost_time = time.time() - start_time
        print(
            f"[{epoch + 1}/{EPOCHS}] "
            f"time={cost_time:.1f}s | train={train_acc:.2f}% | val={val_acc:.2f}%"
        )

    torch.save(model.state_dict(), SAVE_NAME)
    print(f"[OK] detector saved: {SAVE_NAME}")


if __name__ == "__main__":
    train_binary_detector()
