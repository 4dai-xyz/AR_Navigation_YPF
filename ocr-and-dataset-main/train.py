import os
import time

import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader
from torchvision import datasets, models, transforms


# ================= Config =================
DATA_ROOT = r"G:\kejicompany\tracker\logo_dataset"
SAVE_DIR = "checkpoints_huichang_logo"
FINAL_MODEL_NAME = "huichang_logo_model_final.pth"

BATCH_SIZE = 32
EPOCHS = 20
LEARNING_RATE = 1e-4
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
# ==========================================


def train_logo_classifier():
    os.makedirs(SAVE_DIR, exist_ok=True)

    # Gentle augmentation only: booth IDs/logos should remain readable.
    data_transforms = transforms.Compose([
        transforms.RandomResizedCrop(224, scale=(0.9, 1.0), ratio=(0.95, 1.05)),
        transforms.RandomRotation(5),
        transforms.ColorJitter(brightness=0.12, contrast=0.12, saturation=0.12),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])

    train_dataset = datasets.ImageFolder(DATA_ROOT, transform=data_transforms)
    train_loader = DataLoader(
        train_dataset,
        batch_size=BATCH_SIZE,
        shuffle=True,
        num_workers=2,
    )

    num_classes = len(train_dataset.classes)
    print(f">>> Device: {DEVICE}")
    print(f">>> Data root: {DATA_ROOT}")
    print(f">>> Classes ({num_classes}): {train_dataset.classes}")
    print(f">>> Training samples: {len(train_dataset)}")

    model = models.resnet18(weights=models.ResNet18_Weights.IMAGENET1K_V1)
    model.fc = nn.Linear(model.fc.in_features, num_classes)
    model = model.to(DEVICE)

    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=LEARNING_RATE)

    print(">>> Start logo classifier training...")
    start_time = time.time()

    for epoch in range(EPOCHS):
        model.train()
        running_loss = 0.0
        correct = 0
        total = 0

        for inputs, labels in train_loader:
            inputs = inputs.to(DEVICE)
            labels = labels.to(DEVICE)

            optimizer.zero_grad()
            outputs = model(inputs)
            loss = criterion(outputs, labels)
            loss.backward()
            optimizer.step()

            running_loss += loss.item() * inputs.size(0)
            _, predicted = torch.max(outputs, 1)
            total += labels.size(0)
            correct += (predicted == labels).sum().item()

        epoch_loss = running_loss / max(len(train_dataset), 1)
        epoch_acc = 100.0 * correct / max(total, 1)
        print(f"[{epoch + 1}/{EPOCHS}] loss={epoch_loss:.4f} acc={epoch_acc:.2f}%")

        if (epoch + 1) % 10 == 0:
            ckpt_path = os.path.join(SAVE_DIR, f"huichang_logo_e{epoch + 1}.pth")
            torch.save(model.state_dict(), ckpt_path)
            print(f">>> Saved checkpoint: {ckpt_path}")

        if epoch_acc > 99.99 and epoch_loss < 0.001:
            print(">>> Early stop: near-perfect training fit.")
            break

    duration = (time.time() - start_time) / 60
    torch.save(model.state_dict(), FINAL_MODEL_NAME)
    print(f">>> Training finished. Time: {duration:.2f} min")
    print(f">>> Saved final model: {FINAL_MODEL_NAME}")


if __name__ == "__main__":
    train_logo_classifier()
