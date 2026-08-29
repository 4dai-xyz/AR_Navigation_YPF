import os
import re
import json
import time
import random
from typing import Dict, List, Set, Tuple

import torch
import torch.nn as nn
import torch.optim as optim
from torchvision import datasets, transforms, models
from torch.utils.data import DataLoader, random_split, Dataset
from PIL import Image

# 使用说明：
# 1) MODE = "train_logo_classifier"      -> 只训练以 logo_ 开头的类别
# 2) MODE = "build_ocr_assets"           -> 根据映射表和数据集生成 OCR 词表资源
# 3) MODE = "train_logo_with_nonlogo"    -> 训练 logo_ 类别，并把负样本作为 non_logo
# 4) MODE = "train_legacy_classifier"    -> 旧版全类别分类器训练
# 当前默认模式是 "train_logo_classifier"。
# 对于当前方案（logo 模型 + OCR 文本），这是合适的训练模式。
# logo 训练的验证方式：
# - VAL_SCHEME = "group_holdout": 按来源分组留出验证集，速度更快（建议先用）
# - VAL_SCHEME = "group_kfold": 按来源分组做 K 折交叉验证，更稳健但更慢


# ================= 1) 配置 =================
DATA_ROOT = r"G:\kejicompany\char_dataset"
MAPPING_PATH = r"G:\kejicompany\tracker\label_id_mapping.txt"
OCR_ASSET_PATH = r"G:\kejicompany\tracker\ocr_lexicon_assets.json"

# 模式：
# - "build_ocr_assets": 默认用于 OCR + 真实标签映射流程
# - "train_logo_classifier": 只训练以 logo_ 开头的类别
# - "train_logo_with_nonlogo": 训练 logo_ 类别，并加入 non_logo 类别
# - "train_legacy_classifier": 需要时保留旧版全类别训练
MODE = "train_legacy_classifier"
VAL_SCHEME = "group_holdout"  # "group_holdout" | "group_kfold"
VAL_RATIO = 0.2
NUM_FOLDS = 5
RANDOM_SEED = 42

SAVE_NAME = "ocr_classifier_multi.pth"
LOGO_SAVE_NAME = "logo_classifier_only.pth"
LOGO_NONLOGO_SAVE_NAME = "logo_classifier_with_nonlogo.pth"
NEGATIVE_DIR = r"G:\kejicompany\tracker\ResNet_Dataset\negative"
NON_LOGO_CLASS_NAME = "non_logo"
BATCH_SIZE = 64
EPOCHS = 20
LEARNING_RATE = 1e-4
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
# ============================================


def normalize_for_match(text: str) -> str:
    text = (text or "").strip().lower()
    text = text.replace("logo_", "")
    text = text.replace("-", "")
    text = text.replace(" ", "")
    text = text.replace("_", "")
    text = re.sub(r"[^a-z0-9+]", "", text)
    return text


def load_dataset_classes(data_root: str) -> Tuple[List[str], List[str]]:
    classes = sorted(
        [d for d in os.listdir(data_root) if os.path.isdir(os.path.join(data_root, d))]
    )
    logo_classes = [c for c in classes if c.lower().startswith("logo_")]
    token_classes = [c for c in classes if not c.lower().startswith("logo_")]
    return token_classes, logo_classes


def load_truth_labels(mapping_path: str) -> List[str]:
    line_pattern = re.compile(r"^([A-Za-z0-9+_]+)_(\d+)$")
    labels = []
    seen = set()

    if not os.path.exists(mapping_path):
        print(f"[WARN] Mapping not found: {mapping_path}")
        return labels

    with open(mapping_path, "r", encoding="utf-8", errors="ignore") as f:
        for raw in f:
            line = raw.strip()
            m = line_pattern.match(line)
            if not m:
                continue
            # 只保留真实标签文本，忽略末尾的历史编号。
            label_base = m.group(1).lower()
            if label_base in seen:
                continue
            seen.add(label_base)
            labels.append(label_base)

    return sorted(labels)


def can_segment_label(label_norm: str, token_norm_set: Set[str]) -> bool:
    if not label_norm:
        return False
    n = len(label_norm)
    dp = [False] * (n + 1)
    dp[0] = True

    max_token_len = max((len(t) for t in token_norm_set), default=1)
    for i in range(1, n + 1):
        start = max(0, i - max_token_len)
        for j in range(start, i):
            if not dp[j]:
                continue
            if label_norm[j:i] in token_norm_set:
                dp[i] = True
                break
    return dp[n]


def build_ocr_assets():
    token_classes, logo_classes = load_dataset_classes(DATA_ROOT)
    truth_labels = load_truth_labels(MAPPING_PATH)

    token_norm_to_raw: Dict[str, str] = {}
    for t in token_classes:
        norm = normalize_for_match(t)
        if norm and norm not in token_norm_to_raw:
            token_norm_to_raw[norm] = t

    unresolved = []
    for label in truth_labels:
        norm = normalize_for_match(label)
        if not can_segment_label(norm, set(token_norm_to_raw.keys())):
            unresolved.append(label)

    payload = {
        "meta": {
            "created_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "mode": "ocr_assets",
            "note": "truth labels ignore trailing id in mapping rows",
        },
        "paths": {
            "data_root": DATA_ROOT,
            "mapping_path": MAPPING_PATH,
        },
        "truth_labels": truth_labels,
        "token_classes": sorted(token_classes),
        "logo_classes": sorted(logo_classes),
        "normalized_token_index": token_norm_to_raw,
        "stats": {
            "truth_label_count": len(truth_labels),
            "token_class_count": len(token_classes),
            "logo_class_count": len(logo_classes),
            "unresolved_truth_count": len(unresolved),
        },
        "unresolved_truth_labels": unresolved,
    }

    with open(OCR_ASSET_PATH, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    print(f"[OK] OCR assets saved to: {OCR_ASSET_PATH}")
    print(f"[INFO] truth labels: {len(truth_labels)}")
    print(f"[INFO] token classes: {len(token_classes)}")
    print(f"[INFO] logo classes: {len(logo_classes)}")
    print(f"[INFO] unresolved truth labels: {len(unresolved)}")
    if unresolved:
        print("[INFO] unresolved sample:", unresolved[:10])


class SimpleSamplesDataset(Dataset):
    def __init__(self, samples, transform=None):
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


def source_group_key(path: str) -> str:
    name = os.path.splitext(os.path.basename(path))[0].lower()

    # 如果存在类似 UUID 的前缀，优先用它作为来源分组键。
    m = re.search(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", name)
    if m:
        return m.group(0)

    # 去掉常见的数据增强后缀。
    name = re.sub(r"_aug.*$", "", name)
    name = re.sub(r"_rot-?\d+(\.\d+)?$", "", name)
    name = re.sub(r"_flip.*$", "", name)
    name = re.sub(r"\s*副本.*$", "", name)
    return name


def build_grouped_samples(samples: List[Tuple[str, int]]) -> Dict[str, List[Tuple[str, int]]]:
    grouped = {}
    for path, label in samples:
        key = source_group_key(path)
        grouped.setdefault(key, []).append((path, label))
    return grouped


def split_group_holdout(grouped: Dict[str, List[Tuple[str, int]]], val_ratio: float, seed: int):
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


def build_group_kfold_splits(grouped: Dict[str, List[Tuple[str, int]]], num_folds: int, seed: int):
    keys = list(grouped.keys())
    rnd = random.Random(seed)
    rnd.shuffle(keys)

    num_folds = max(2, min(num_folds, len(keys)))
    folds = [[] for _ in range(num_folds)]
    for i, key in enumerate(keys):
        folds[i % num_folds].append(key)

    splits = []
    for fold_idx in range(num_folds):
        val_keys = set(folds[fold_idx])
        train_keys = set(keys) - val_keys

        train_samples = []
        val_samples = []
        for k in train_keys:
            train_samples.extend(grouped[k])
        for k in val_keys:
            val_samples.extend(grouped[k])
        splits.append((fold_idx, train_samples, val_samples))
    return splits


def run_logo_training_once(
    train_samples: List[Tuple[str, int]],
    val_samples: List[Tuple[str, int]],
    logo_classes: List[str],
    train_transforms,
    val_transforms,
):
    train_dataset = SimpleSamplesDataset(train_samples, transform=train_transforms)
    val_dataset = SimpleSamplesDataset(val_samples, transform=val_transforms)

    train_loader = DataLoader(train_dataset, batch_size=BATCH_SIZE, shuffle=True)
    val_loader = DataLoader(val_dataset, batch_size=BATCH_SIZE, shuffle=False)

    model = models.resnet18(weights=models.ResNet18_Weights.IMAGENET1K_V1)
    model.fc = nn.Linear(model.fc.in_features, len(logo_classes))
    model = model.to(DEVICE)

    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=LEARNING_RATE)

    best_val_acc = -1.0
    best_state = None

    for epoch in range(EPOCHS):
        model.train()
        correct = 0
        total = 0
        start = time.time()

        for inputs, labels in train_loader:
            inputs = inputs.to(DEVICE)
            labels = labels.to(DEVICE)

            optimizer.zero_grad()
            outputs = model(inputs)
            loss = criterion(outputs, labels)
            loss.backward()
            optimizer.step()

            _, pred = torch.max(outputs, 1)
            total += labels.size(0)
            correct += (pred == labels).sum().item()

        train_acc = 100.0 * correct / max(total, 1)

        model.eval()
        val_correct = 0
        val_total = 0
        with torch.no_grad():
            for inputs, labels in val_loader:
                inputs = inputs.to(DEVICE)
                labels = labels.to(DEVICE)
                outputs = model(inputs)
                _, pred = torch.max(outputs, 1)
                val_total += labels.size(0)
                val_correct += (pred == labels).sum().item()

        val_acc = 100.0 * val_correct / max(val_total, 1)
        if val_acc > best_val_acc:
            best_val_acc = val_acc
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}

        cost = time.time() - start
        print(f"[{epoch + 1}/{EPOCHS}] {cost:.1f}s | train {train_acc:.2f}% | val {val_acc:.2f}%")

    if best_state is None:
        best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}

    return best_state, best_val_acc


def train_logo_classifier():
    print(f"[INFO] train logo-only classifier on {DEVICE}")

    train_transforms = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ColorJitter(brightness=0.2, contrast=0.2),
        transforms.RandomAffine(degrees=5, translate=(0.05, 0.05)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])
    val_transforms = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])

    full_dataset = datasets.ImageFolder(DATA_ROOT)
    logo_classes = [c for c in full_dataset.classes if c.lower().startswith("logo_")]
    if not logo_classes:
        raise ValueError("No logo_ classes found in dataset.")

    old_to_new = {}
    for new_idx, cls_name in enumerate(logo_classes):
        old_idx = full_dataset.class_to_idx[cls_name]
        old_to_new[old_idx] = new_idx

    logo_samples = []
    for img_path, old_label in full_dataset.samples:
        if old_label in old_to_new:
            logo_samples.append((img_path, old_to_new[old_label]))

    if len(logo_samples) < 10:
        raise ValueError(f"Too few logo samples: {len(logo_samples)}")

    print(f"[INFO] logo classes: {len(logo_classes)}")
    print(f"[INFO] logo samples: {len(logo_samples)}")
    grouped = build_grouped_samples(logo_samples)
    print(f"[INFO] source groups: {len(grouped)}")

    if VAL_SCHEME == "group_kfold":
        splits = build_group_kfold_splits(grouped, NUM_FOLDS, RANDOM_SEED)
        fold_metrics = []
        best_state = None
        best_fold = -1
        best_acc = -1.0

        for fold_idx, train_samples, val_samples in splits:
            print("=" * 60)
            print(
                f"[INFO] Fold {fold_idx + 1}/{len(splits)} "
                f"| train {len(train_samples)} | val {len(val_samples)}"
            )
            state, fold_acc = run_logo_training_once(
                train_samples, val_samples, logo_classes, train_transforms, val_transforms
            )
            fold_metrics.append({"fold": fold_idx + 1, "best_val_acc": fold_acc})
            if fold_acc > best_acc:
                best_acc = fold_acc
                best_state = state
                best_fold = fold_idx + 1

        mean_acc = sum([x["best_val_acc"] for x in fold_metrics]) / len(fold_metrics)
        print(f"[INFO] KFold mean best val acc: {mean_acc:.2f}%")
        print(f"[INFO] Best fold: {best_fold} ({best_acc:.2f}%)")

        torch.save(
            {
                "state_dict": best_state,
                "logo_classes": logo_classes,
                "val_scheme": VAL_SCHEME,
                "num_folds": len(splits),
                "best_fold": best_fold,
                "best_val_acc": best_acc,
                "fold_metrics": fold_metrics,
            },
            LOGO_SAVE_NAME,
        )
    else:
        train_samples, val_samples = split_group_holdout(grouped, VAL_RATIO, RANDOM_SEED)
        print(
            f"[INFO] Holdout split | train {len(train_samples)} | val {len(val_samples)} "
            f"| ratio {VAL_RATIO}"
        )
        best_state, best_acc = run_logo_training_once(
            train_samples, val_samples, logo_classes, train_transforms, val_transforms
        )
        torch.save(
            {
                "state_dict": best_state,
                "logo_classes": logo_classes,
                "val_scheme": VAL_SCHEME,
                "val_ratio": VAL_RATIO,
                "best_val_acc": best_acc,
            },
            LOGO_SAVE_NAME,
        )

    print(f"[OK] logo model saved: {LOGO_SAVE_NAME}")


def train_logo_with_nonlogo():
    print(f"[INFO] train logo + non_logo classifier on {DEVICE}")

    train_transforms = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ColorJitter(brightness=0.2, contrast=0.2),
        transforms.RandomAffine(degrees=5, translate=(0.05, 0.05)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])
    val_transforms = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])

    full_dataset = datasets.ImageFolder(DATA_ROOT)
    logo_classes = [c for c in full_dataset.classes if c.lower().startswith("logo_")]
    if not logo_classes:
        raise ValueError("No logo_ classes found in dataset.")

    if not os.path.isdir(NEGATIVE_DIR):
        raise ValueError(f"NEGATIVE_DIR not found: {NEGATIVE_DIR}")

    old_to_new = {}
    for new_idx, cls_name in enumerate(logo_classes):
        old_to_new[full_dataset.class_to_idx[cls_name]] = new_idx

    non_logo_idx = len(logo_classes)
    final_classes = logo_classes + [NON_LOGO_CLASS_NAME]

    samples = []
    for img_path, old_label in full_dataset.samples:
        if old_label in old_to_new:
            samples.append((img_path, old_to_new[old_label]))

    neg_files = []
    for name in os.listdir(NEGATIVE_DIR):
        lower = name.lower()
        if lower.endswith((".jpg", ".jpeg", ".png", ".bmp", ".webp")):
            neg_files.append(os.path.join(NEGATIVE_DIR, name))
    for p in neg_files:
        samples.append((p, non_logo_idx))

    if len(samples) < 20 or len(neg_files) < 10:
        raise ValueError(
            f"Too few samples. total={len(samples)}, negative={len(neg_files)}"
        )

    print(f"[INFO] logo classes: {len(logo_classes)}")
    print(f"[INFO] negative samples: {len(neg_files)}")
    print(f"[INFO] total samples: {len(samples)}")

    grouped = build_grouped_samples(samples)
    print(f"[INFO] source groups: {len(grouped)}")

    if VAL_SCHEME == "group_kfold":
        splits = build_group_kfold_splits(grouped, NUM_FOLDS, RANDOM_SEED)
        fold_metrics = []
        best_state = None
        best_fold = -1
        best_acc = -1.0

        for fold_idx, train_samples, val_samples in splits:
            print("=" * 60)
            print(
                f"[INFO] Fold {fold_idx + 1}/{len(splits)} "
                f"| train {len(train_samples)} | val {len(val_samples)}"
            )
            state, fold_acc = run_logo_training_once(
                train_samples, val_samples, final_classes, train_transforms, val_transforms
            )
            fold_metrics.append({"fold": fold_idx + 1, "best_val_acc": fold_acc})
            if fold_acc > best_acc:
                best_acc = fold_acc
                best_state = state
                best_fold = fold_idx + 1

        mean_acc = sum([x["best_val_acc"] for x in fold_metrics]) / len(fold_metrics)
        print(f"[INFO] KFold mean best val acc: {mean_acc:.2f}%")
        print(f"[INFO] Best fold: {best_fold} ({best_acc:.2f}%)")

        torch.save(
            {
                "state_dict": best_state,
                "logo_classes": final_classes,
                "non_logo_class_name": NON_LOGO_CLASS_NAME,
                "val_scheme": VAL_SCHEME,
                "num_folds": len(splits),
                "best_fold": best_fold,
                "best_val_acc": best_acc,
                "fold_metrics": fold_metrics,
            },
            LOGO_NONLOGO_SAVE_NAME,
        )
    else:
        train_samples, val_samples = split_group_holdout(grouped, VAL_RATIO, RANDOM_SEED)
        print(
            f"[INFO] Holdout split | train {len(train_samples)} | val {len(val_samples)} "
            f"| ratio {VAL_RATIO}"
        )
        best_state, best_acc = run_logo_training_once(
            train_samples, val_samples, final_classes, train_transforms, val_transforms
        )
        torch.save(
            {
                "state_dict": best_state,
                "logo_classes": final_classes,
                "non_logo_class_name": NON_LOGO_CLASS_NAME,
                "val_scheme": VAL_SCHEME,
                "val_ratio": VAL_RATIO,
                "best_val_acc": best_acc,
            },
            LOGO_NONLOGO_SAVE_NAME,
        )

    print(f"[OK] logo+non_logo model saved: {LOGO_NONLOGO_SAVE_NAME}")


def train_legacy_classifier():
    print(f"[INFO] train legacy all-class classifier on {DEVICE}")

    train_transforms = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ColorJitter(brightness=0.2, contrast=0.2),
        transforms.RandomAffine(degrees=5, translate=(0.05, 0.05)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])

    val_transforms = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])

    full_dataset = datasets.ImageFolder(DATA_ROOT, transform=train_transforms)
    num_classes = len(full_dataset.classes)
    print(f"[INFO] dataset classes: {num_classes}")

    train_size = int(0.8 * len(full_dataset))
    val_size = len(full_dataset) - train_size
    train_dataset, val_dataset = random_split(full_dataset, [train_size, val_size])
    val_dataset.dataset.transform = val_transforms

    train_loader = DataLoader(train_dataset, batch_size=BATCH_SIZE, shuffle=True)
    val_loader = DataLoader(val_dataset, batch_size=BATCH_SIZE, shuffle=False)

    model = models.resnet18(weights=models.ResNet18_Weights.IMAGENET1K_V1)
    model.fc = nn.Linear(model.fc.in_features, num_classes)
    model = model.to(DEVICE)

    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=LEARNING_RATE)

    for epoch in range(EPOCHS):
        model.train()
        running_loss = 0.0
        correct = 0
        total = 0
        start = time.time()

        for inputs, labels in train_loader:
            inputs = inputs.to(DEVICE)
            labels = labels.to(DEVICE)

            optimizer.zero_grad()
            outputs = model(inputs)
            loss = criterion(outputs, labels)
            loss.backward()
            optimizer.step()

            running_loss += loss.item()
            _, pred = torch.max(outputs, 1)
            total += labels.size(0)
            correct += (pred == labels).sum().item()

        train_acc = 100.0 * correct / max(total, 1)

        model.eval()
        val_correct = 0
        val_total = 0
        with torch.no_grad():
            for inputs, labels in val_loader:
                inputs = inputs.to(DEVICE)
                labels = labels.to(DEVICE)
                outputs = model(inputs)
                _, pred = torch.max(outputs, 1)
                val_total += labels.size(0)
                val_correct += (pred == labels).sum().item()

        val_acc = 100.0 * val_correct / max(val_total, 1)
        cost = time.time() - start
        print(f"[{epoch + 1}/{EPOCHS}] {cost:.1f}s | train {train_acc:.2f}% | val {val_acc:.2f}%")

    torch.save(
        {
            "state_dict": model.state_dict(),
            "classes": full_dataset.classes,
        },
        SAVE_NAME,
    )
    print(f"[OK] model saved: {SAVE_NAME}")


if __name__ == "__main__":
    if MODE == "build_ocr_assets":
        build_ocr_assets()
    elif MODE == "train_logo_classifier":
        train_logo_classifier()
    elif MODE == "train_logo_with_nonlogo":
        train_logo_with_nonlogo()
    elif MODE == "train_legacy_classifier":
        train_legacy_classifier()
    else:
        raise ValueError(f"Unsupported MODE: {MODE}")
