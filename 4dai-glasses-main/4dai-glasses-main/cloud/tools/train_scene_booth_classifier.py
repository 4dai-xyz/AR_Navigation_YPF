from __future__ import annotations

import argparse
import io
import json
import math
import os
import random
import time
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import mean
from typing import Callable

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter


IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}
NORMALIZE_MEAN = (0.485, 0.456, 0.406)
NORMALIZE_STD = (0.229, 0.224, 0.225)


@dataclass(frozen=True)
class ImageItem:
    path: Path
    label: int
    booth_id: str


class RandomJpegCompression:
    def __init__(self, *, p: float = 0.35, quality_range: tuple[int, int] = (35, 88)) -> None:
        self.p = p
        self.quality_range = quality_range

    def __call__(self, image: Image.Image) -> Image.Image:
        if random.random() > self.p:
            return image
        output = io.BytesIO()
        quality = random.randint(*self.quality_range)
        image.save(output, format="JPEG", quality=quality)
        output.seek(0)
        return Image.open(output).convert("RGB")


class RandomMotionBlur:
    def __init__(self, *, p: float = 0.25, radius_range: tuple[float, float] = (0.8, 2.2)) -> None:
        self.p = p
        self.radius_range = radius_range

    def __call__(self, image: Image.Image) -> Image.Image:
        if random.random() > self.p:
            return image
        return image.filter(ImageFilter.GaussianBlur(radius=random.uniform(*self.radius_range)))


class RandomGamma:
    def __init__(self, *, p: float = 0.35, gamma_range: tuple[float, float] = (0.65, 1.65)) -> None:
        self.p = p
        self.gamma_range = gamma_range

    def __call__(self, image: Image.Image) -> Image.Image:
        if random.random() > self.p:
            return image
        gamma = random.uniform(*self.gamma_range)
        lut = [min(255, max(0, int(((value / 255.0) ** gamma) * 255))) for value in range(256)]
        return image.point(lut * 3)


def collect_items(samples_dir: Path) -> tuple[list[ImageItem], list[str]]:
    booth_dirs = sorted(path for path in samples_dir.iterdir() if path.is_dir())
    classes = [path.name.upper() for path in booth_dirs]
    class_to_index = {booth_id: index for index, booth_id in enumerate(classes)}
    items: list[ImageItem] = []
    for booth_dir in booth_dirs:
        booth_id = booth_dir.name.upper()
        for image_path in sorted(path for path in booth_dir.iterdir() if path.suffix.lower() in IMAGE_SUFFIXES):
            items.append(ImageItem(path=image_path, label=class_to_index[booth_id], booth_id=booth_id))
    if not items:
        raise SystemExit(f"No images found under {samples_dir}")
    return items, classes


def split_items(items: list[ImageItem], *, val_stride: int) -> tuple[list[ImageItem], list[ImageItem]]:
    by_booth: dict[str, list[ImageItem]] = defaultdict(list)
    for item in items:
        by_booth[item.booth_id].append(item)

    train_items: list[ImageItem] = []
    val_items: list[ImageItem] = []
    for booth_id in sorted(by_booth):
        booth_items = sorted(by_booth[booth_id], key=lambda item: str(item.path))
        for index, item in enumerate(booth_items):
            if index % val_stride == 0:
                val_items.append(item)
            else:
                train_items.append(item)
    return train_items, val_items


def build_train_transform():
    from torchvision import transforms

    return transforms.Compose(
        [
            transforms.RandomResizedCrop(224, scale=(0.48, 1.0), ratio=(0.70, 1.45)),
            transforms.RandomHorizontalFlip(p=0.25),
            transforms.ColorJitter(brightness=0.45, contrast=0.45, saturation=0.35, hue=0.04),
            transforms.RandomGrayscale(p=0.08),
            transforms.ToTensor(),
            transforms.RandomApply([transforms.GaussianBlur(kernel_size=3, sigma=(0.1, 2.0))], p=0.20),
            transforms.Normalize(mean=NORMALIZE_MEAN, std=NORMALIZE_STD),
            transforms.RandomErasing(p=0.35, scale=(0.02, 0.18), ratio=(0.3, 3.3), value="random"),
        ]
    )


def build_eval_transform():
    from torchvision import transforms

    return transforms.Compose(
        [
            transforms.Resize((224, 224)),
            transforms.ToTensor(),
            transforms.Normalize(mean=NORMALIZE_MEAN, std=NORMALIZE_STD),
        ]
    )


class BoothDataset:
    def __init__(self, items: list[ImageItem], transform: Callable[[Image.Image], object]) -> None:
        self.items = items
        self.transform = transform

    def __len__(self) -> int:
        return len(self.items)

    def __getitem__(self, index: int):
        item = self.items[index]
        image = Image.open(item.path).convert("RGB")
        return self.transform(image), item.label


class FastAugBoothDataset:
    def __init__(self, items: list[ImageItem]) -> None:
        self.items = items

    def __len__(self) -> int:
        return len(self.items)

    def __getitem__(self, index: int):
        import cv2
        import numpy as np
        import torch

        item = self.items[index]
        image = cv2.imread(str(item.path), cv2.IMREAD_COLOR)
        if image is None:
            raise RuntimeError(f"Failed to read image: {item.path}")

        height, width = image.shape[:2]
        scale = random.uniform(0.50, 1.0)
        crop_w = max(16, int(width * scale))
        crop_h = max(16, int(height * random.uniform(0.62, 1.0)))
        crop_w = min(crop_w, width)
        crop_h = min(crop_h, height)
        x0 = random.randint(0, max(0, width - crop_w))
        y0 = random.randint(0, max(0, height - crop_h))
        image = image[y0 : y0 + crop_h, x0 : x0 + crop_w]
        image = cv2.resize(image, (224, 224), interpolation=cv2.INTER_AREA)

        if random.random() < 0.25:
            image = cv2.flip(image, 1)

        alpha = random.uniform(0.62, 1.48)
        beta = random.uniform(-38, 38)
        image = cv2.convertScaleAbs(image, alpha=alpha, beta=beta)

        if random.random() < 0.35:
            hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV).astype(np.float32)
            hsv[..., 1] *= random.uniform(0.55, 1.35)
            hsv[..., 2] *= random.uniform(0.70, 1.30)
            hsv = np.clip(hsv, 0, 255).astype(np.uint8)
            image = cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)

        if random.random() < 0.20:
            image = cv2.GaussianBlur(image, (3, 3), sigmaX=random.uniform(0.3, 1.4))

        if random.random() < 0.35:
            erase_w = random.randint(18, 78)
            erase_h = random.randint(18, 78)
            erase_x = random.randint(0, max(0, 224 - erase_w))
            erase_y = random.randint(0, max(0, 224 - erase_h))
            fill = random.randint(0, 255)
            image[erase_y : erase_y + erase_h, erase_x : erase_x + erase_w] = fill

        image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        image = (image - np.array(NORMALIZE_MEAN, dtype=np.float32)) / np.array(NORMALIZE_STD, dtype=np.float32)
        image = np.ascontiguousarray(image.transpose(2, 0, 1))
        return torch.from_numpy(image), item.label


class CachedBoothDataset:
    def __init__(self, items: list[ImageItem], *, image_size: int = 224) -> None:
        import cv2
        import torch

        self.items = items
        self.images = []
        self.labels = []
        started_at = time.perf_counter()
        for item in items:
            image = cv2.imread(str(item.path), cv2.IMREAD_COLOR)
            if image is None:
                raise RuntimeError(f"Failed to read image: {item.path}")
            image = cv2.resize(image, (image_size, image_size), interpolation=cv2.INTER_AREA)
            image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            self.images.append(torch.from_numpy(image).permute(2, 0, 1).contiguous())
            self.labels.append(item.label)
        print(
            f"Cached {len(self.images)} images at {image_size}x{image_size} in {time.perf_counter() - started_at:.2f}s",
            flush=True,
        )

    def __len__(self) -> int:
        return len(self.items)

    def __getitem__(self, index: int):
        return self.images[index], self.labels[index]


def stress_variants(image: Image.Image) -> list[tuple[str, Image.Image]]:
    width, height = image.size
    variants: list[tuple[str, Image.Image]] = [("clean", image)]

    dark = ImageEnhance.Brightness(image).enhance(0.45)
    dark = ImageEnhance.Contrast(dark).enhance(1.25)
    variants.append(("dark_contrast", dark))

    bright = ImageEnhance.Brightness(image).enhance(1.45)
    bright = ImageEnhance.Color(bright).enhance(0.75)
    variants.append(("bright_desaturate", bright))

    variants.append(("blur", image.filter(ImageFilter.GaussianBlur(radius=1.8))))

    left_crop = image.crop((0, 0, int(width * 0.72), height)).resize((width, height))
    variants.append(("left_partial", left_crop))

    right_crop = image.crop((int(width * 0.28), 0, width, height)).resize((width, height))
    variants.append(("right_partial", right_crop))

    occluded = image.copy()
    draw = ImageDraw.Draw(occluded)
    draw.rectangle(
        (int(width * 0.25), int(height * 0.30), int(width * 0.70), int(height * 0.62)),
        fill=(35, 35, 35),
    )
    variants.append(("occlusion", occluded))

    output = io.BytesIO()
    image.save(output, format="JPEG", quality=38)
    output.seek(0)
    variants.append(("jpeg_38", Image.open(output).convert("RGB")))
    return variants


class StressBoothDataset:
    def __init__(self, items: list[ImageItem], transform: Callable[[Image.Image], object]) -> None:
        self.samples: list[tuple[ImageItem, str]] = []
        self.transform = transform
        for item in items:
            for variant_name in ("clean", "dark_contrast", "bright_desaturate", "blur", "left_partial", "right_partial", "occlusion", "jpeg_38"):
                self.samples.append((item, variant_name))

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, index: int):
        item, expected_variant = self.samples[index]
        image = Image.open(item.path).convert("RGB")
        for variant_name, variant_image in stress_variants(image):
            if variant_name == expected_variant:
                return self.transform(variant_image), item.label, item.booth_id, variant_name
        raise RuntimeError(f"Unknown stress variant: {expected_variant}")


def normalize_cv2_rgb(image):
    import numpy as np
    import torch

    image = image.astype(np.float32) / 255.0
    image = (image - np.array(NORMALIZE_MEAN, dtype=np.float32)) / np.array(NORMALIZE_STD, dtype=np.float32)
    image = np.ascontiguousarray(image.transpose(2, 0, 1))
    return torch.from_numpy(image)


class FastStressBoothDataset:
    VARIANTS = (
        "clean",
        "dark_contrast",
        "low_light_noise",
        "bright_desaturate",
        "overexposed",
        "blur",
        "motion_blur",
        "lowres",
        "left_partial",
        "right_partial",
        "center_crop_55",
        "left_crop_50",
        "right_crop_50",
        "occlusion",
        "large_occlusion",
        "color_shift",
        "jpeg_38",
    )

    def __init__(self, items: list[ImageItem]) -> None:
        self.samples: list[tuple[ImageItem, str]] = []
        for item in items:
            for variant_name in self.VARIANTS:
                self.samples.append((item, variant_name))

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, index: int):
        import cv2
        import numpy as np

        item, variant_name = self.samples[index]
        image = cv2.imread(str(item.path), cv2.IMREAD_COLOR)
        if image is None:
            raise RuntimeError(f"Failed to read image: {item.path}")
        height, width = image.shape[:2]
        if variant_name == "dark_contrast":
            image = cv2.convertScaleAbs(image, alpha=1.25, beta=-70)
        elif variant_name == "low_light_noise":
            image = cv2.convertScaleAbs(image, alpha=0.45, beta=-28)
            noise = np.random.normal(0, 11, image.shape).astype(np.int16)
            image = np.clip(image.astype(np.int16) + noise, 0, 255).astype(np.uint8)
        elif variant_name == "bright_desaturate":
            image = cv2.convertScaleAbs(image, alpha=0.82, beta=70)
            hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV).astype(np.float32)
            hsv[..., 1] *= 0.55
            image = cv2.cvtColor(np.clip(hsv, 0, 255).astype(np.uint8), cv2.COLOR_HSV2BGR)
        elif variant_name == "overexposed":
            image = cv2.convertScaleAbs(image, alpha=1.65, beta=45)
        elif variant_name == "blur":
            image = cv2.GaussianBlur(image, (5, 5), sigmaX=1.8)
        elif variant_name == "motion_blur":
            kernel = np.zeros((9, 9), dtype=np.float32)
            kernel[4, :] = 1.0 / 9.0
            image = cv2.filter2D(image, -1, kernel)
        elif variant_name == "lowres":
            small = cv2.resize(image, (80, 80), interpolation=cv2.INTER_AREA)
            image = cv2.resize(small, (width, height), interpolation=cv2.INTER_LINEAR)
        elif variant_name == "left_partial":
            image = image[:, : int(width * 0.72)]
        elif variant_name == "right_partial":
            image = image[:, int(width * 0.28) :]
        elif variant_name == "center_crop_55":
            x0, x1 = int(width * 0.225), int(width * 0.775)
            y0, y1 = int(height * 0.225), int(height * 0.775)
            image = image[y0:y1, x0:x1]
        elif variant_name == "left_crop_50":
            image = image[:, : int(width * 0.50)]
        elif variant_name == "right_crop_50":
            image = image[:, int(width * 0.50) :]
        elif variant_name == "occlusion":
            image = image.copy()
            y0, y1 = int(height * 0.30), int(height * 0.62)
            x0, x1 = int(width * 0.25), int(width * 0.70)
            image[y0:y1, x0:x1] = 35
        elif variant_name == "large_occlusion":
            image = image.copy()
            y0, y1 = int(height * 0.18), int(height * 0.70)
            x0, x1 = int(width * 0.18), int(width * 0.76)
            image[y0:y1, x0:x1] = 45
        elif variant_name == "color_shift":
            shifted = image.astype(np.float32)
            shifted[..., 0] *= 1.25
            shifted[..., 1] *= 0.82
            shifted[..., 2] *= 0.72
            image = np.clip(shifted, 0, 255).astype(np.uint8)
        elif variant_name == "jpeg_38":
            ok, encoded = cv2.imencode(".jpg", image, [int(cv2.IMWRITE_JPEG_QUALITY), 38])
            if ok:
                image = cv2.imdecode(encoded, cv2.IMREAD_COLOR)

        image = cv2.resize(image, (224, 224), interpolation=cv2.INTER_AREA)
        image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        return normalize_cv2_rgb(image), item.label, item.booth_id, variant_name


def build_model(*, class_count: int, pretrained: bool, model_arch: str):
    from torchvision import models
    import torch

    if model_arch == "mobilenet_v3_small":
        weights = models.MobileNet_V3_Small_Weights.DEFAULT if pretrained else None
        model = models.mobilenet_v3_small(weights=weights)
    elif model_arch == "mobilenet_v3_large":
        weights = models.MobileNet_V3_Large_Weights.DEFAULT if pretrained else None
        model = models.mobilenet_v3_large(weights=weights)
    else:
        raise ValueError(f"Unsupported model_arch: {model_arch}")
    in_features = model.classifier[-1].in_features
    model.classifier[-1] = torch.nn.Linear(in_features, class_count)
    return model


def load_checkpoint_model(checkpoint_path: Path, *, device: str):
    import torch

    try:
        checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    except TypeError:
        checkpoint = torch.load(checkpoint_path, map_location="cpu")
    classes = [str(item).upper() for item in checkpoint["classes"]]
    model_arch = checkpoint.get("model", "mobilenet_v3_small")
    model = build_model(class_count=len(classes), pretrained=False, model_arch=model_arch)
    model.load_state_dict(checkpoint["state_dict"])
    model.to(device).eval()
    return model, classes


def topk_accuracy(logits, labels, topk: tuple[int, ...] = (1, 3)) -> dict[int, int]:
    _, predictions = logits.topk(max(topk), dim=1)
    predictions = predictions.t()
    correct = predictions.eq(labels.reshape(1, -1).expand_as(predictions))
    return {k: int(correct[:k].reshape(-1).float().sum().item()) for k in topk}


def evaluate_clean(model, dataloader, *, device: str) -> dict:
    import torch

    model.eval()
    total = 0
    top1 = 0
    top3 = 0
    with torch.inference_mode():
        for images, labels in dataloader:
            images = images.to(device, non_blocking=True)
            labels = labels.to(device, non_blocking=True)
            logits = model(images)
            hits = topk_accuracy(logits, labels, topk=(1, 3))
            total += int(labels.numel())
            top1 += hits[1]
            top3 += hits[3]
    return {
        "count": total,
        "top1_accuracy": round(top1 / total, 4) if total else 0.0,
        "top3_accuracy": round(top3 / total, 4) if total else 0.0,
    }


def evaluate_stress(model, dataloader, *, device: str) -> dict:
    import torch

    model.eval()
    total = 0
    correct = 0
    by_variant: dict[str, dict[str, int]] = defaultdict(lambda: {"total": 0, "correct": 0})
    by_booth: dict[str, dict[str, int]] = defaultdict(lambda: {"total": 0, "correct": 0})
    with torch.inference_mode():
        for images, labels, booth_ids, variant_names in dataloader:
            images = images.to(device, non_blocking=True)
            labels = labels.to(device, non_blocking=True)
            logits = model(images)
            predictions = logits.argmax(dim=1)
            matches = predictions.eq(labels).detach().cpu().tolist()
            total += int(labels.numel())
            correct += sum(1 for item in matches if item)
            for booth_id, variant_name, matched in zip(booth_ids, variant_names, matches):
                by_variant[variant_name]["total"] += 1
                by_variant[variant_name]["correct"] += int(bool(matched))
                by_booth[booth_id]["total"] += 1
                by_booth[booth_id]["correct"] += int(bool(matched))
    low_booths = {
        booth_id: round(stats["correct"] / stats["total"], 4)
        for booth_id, stats in sorted(by_booth.items())
        if stats["total"] and stats["correct"] / stats["total"] < 0.85
    }
    return {
        "count": total,
        "top1_accuracy": round(correct / total, 4) if total else 0.0,
        "by_variant": {
            variant: round(stats["correct"] / stats["total"], 4)
            for variant, stats in sorted(by_variant.items())
            if stats["total"]
        },
        "low_booths_lt_0_85": low_booths,
    }


def measure_latency(model, dataset, *, device: str, samples: int) -> dict:
    import torch

    count = min(samples, len(dataset))
    if count <= 0:
        return {"samples": 0}
    model.eval()
    timings: list[float] = []
    with torch.inference_mode():
        for warmup_index in range(min(5, count)):
            image, _ = dataset[warmup_index]
            image = image.unsqueeze(0).to(device)
            _ = model(image)
        if device.startswith("cuda"):
            torch.cuda.synchronize()
        for index in range(count):
            image, _ = dataset[index]
            image = image.unsqueeze(0).to(device)
            if device.startswith("cuda"):
                torch.cuda.synchronize()
            started_at = time.perf_counter()
            _ = model(image)
            if device.startswith("cuda"):
                torch.cuda.synchronize()
            timings.append((time.perf_counter() - started_at) * 1000)
    timings = sorted(timings)
    return {
        "samples": count,
        "avg": round(mean(timings), 2),
        "p50": round(timings[int(count * 0.50)], 2),
        "p95": round(timings[min(count - 1, math.ceil(count * 0.95) - 1)], 2),
        "max": round(max(timings), 2),
    }


def augment_batch_on_device(images, *, profile: str):
    import torch

    images = images.float() / 255.0
    batch_size = images.shape[0]
    device = images.device

    flip_mask = torch.rand((batch_size, 1, 1, 1), device=device) < 0.25
    images = torch.where(flip_mask, torch.flip(images, dims=[3]), images)

    if profile == "hard_partial":
        brightness = torch.empty((batch_size, 1, 1, 1), device=device).uniform_(0.48, 1.62)
        contrast = torch.empty((batch_size, 1, 1, 1), device=device).uniform_(0.55, 1.55)
        saturation = torch.empty((batch_size, 1, 1, 1), device=device).uniform_(0.45, 1.45)
        erase_prob = 0.55
        side_prob = 0.40
    else:
        brightness = torch.empty((batch_size, 1, 1, 1), device=device).uniform_(0.62, 1.42)
        contrast = torch.empty((batch_size, 1, 1, 1), device=device).uniform_(0.70, 1.35)
        saturation = torch.empty((batch_size, 1, 1, 1), device=device).uniform_(0.60, 1.30)
        erase_prob = 0.35
        side_prob = 0.25

    mean = images.mean(dim=(2, 3), keepdim=True)
    images = (images - mean) * contrast + mean
    gray = images.mean(dim=1, keepdim=True)
    images = (images - gray) * saturation + gray
    images = images * brightness
    images = images.clamp(0.0, 1.0)

    for index in range(batch_size):
        if torch.rand((), device=device).item() < side_prob:
            side = int(torch.randint(0, 4, (), device=device).item())
            ratio = float(torch.empty((), device=device).uniform_(0.14, 0.34).item())
            pixels = max(1, int(images.shape[-1] * ratio))
            if side == 0:
                images[index, :, :, :pixels] = 0
            elif side == 1:
                images[index, :, :, -pixels:] = 0
            elif side == 2:
                images[index, :, :pixels, :] = 0
            else:
                images[index, :, -pixels:, :] = 0
        if torch.rand((), device=device).item() < erase_prob:
            erase_w = int(torch.randint(18, 76, (), device=device).item())
            erase_h = int(torch.randint(18, 76, (), device=device).item())
            erase_x = int(torch.randint(0, max(1, images.shape[-1] - erase_w), (), device=device).item())
            erase_y = int(torch.randint(0, max(1, images.shape[-2] - erase_h), (), device=device).item())
            fill = torch.rand((3, 1, 1), device=device)
            images[index, :, erase_y : erase_y + erase_h, erase_x : erase_x + erase_w] = fill

    mean_tensor = torch.tensor(NORMALIZE_MEAN, device=device).view(1, 3, 1, 1)
    std_tensor = torch.tensor(NORMALIZE_STD, device=device).view(1, 3, 1, 1)
    return (images - mean_tensor) / std_tensor


def maybe_mixup(images, labels, *, alpha: float):
    if alpha <= 0:
        return images, labels, None, 1.0
    import torch

    lam = float(torch.distributions.Beta(alpha, alpha).sample().item())
    indices = torch.randperm(images.shape[0], device=images.device)
    mixed_images = images * lam + images[indices] * (1.0 - lam)
    return mixed_images, labels, labels[indices], lam


def make_dataloader(dataset, *, batch_size: int, shuffle: bool, num_workers: int):
    from torch.utils.data import DataLoader

    return DataLoader(
        dataset,
        batch_size=batch_size,
        shuffle=shuffle,
        num_workers=num_workers,
        pin_memory=True,
    )


def safe_torch_save(torch_module, payload: dict, checkpoint_path: Path) -> Path:
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = checkpoint_path.with_name(f"{checkpoint_path.stem}.tmp.{os.getpid()}.pt")
    torch_module.save(payload, tmp_path)
    for _ in range(3):
        try:
            os.replace(tmp_path, checkpoint_path)
            return checkpoint_path
        except OSError:
            time.sleep(0.5)
    fallback_path = checkpoint_path.with_name(f"{checkpoint_path.stem}.{int(time.time())}.pt")
    os.replace(tmp_path, fallback_path)
    print(f"WARNING: checkpoint path was locked; wrote fallback checkpoint: {fallback_path}", flush=True)
    return fallback_path


def train(args: argparse.Namespace) -> dict:
    import torch

    random.seed(args.seed)
    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)

    device = "cuda" if args.device == "auto" and torch.cuda.is_available() else args.device
    if device == "auto":
        device = "cpu"
    if device.startswith("cuda"):
        torch.backends.cudnn.benchmark = True
    if args.cache_images and not args.gpu_augment:
        raise SystemExit("--cache-images requires --gpu-augment so cached uint8 images are normalized on device")

    items, classes = collect_items(args.samples_dir)
    train_items, val_items = split_items(items, val_stride=args.val_stride)
    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    checkpoint_path = output_dir / f"booth_classifier_{args.model_arch}_{args.experiment_name}.pt"
    report_path = output_dir / f"booth_classifier_{args.model_arch}_{args.experiment_name}_report.json"

    if args.cache_images:
        train_dataset = CachedBoothDataset(train_items, image_size=args.cached_image_size)
    else:
        train_dataset = FastAugBoothDataset(train_items) if args.fast_augment else BoothDataset(train_items, build_train_transform())
    val_dataset = BoothDataset(val_items, build_eval_transform())
    stress_dataset = FastStressBoothDataset(val_items) if args.fast_augment else StressBoothDataset(val_items, build_eval_transform())
    train_loader = make_dataloader(
        train_dataset,
        batch_size=args.batch_size,
        shuffle=True,
        num_workers=args.num_workers,
    )
    val_loader = make_dataloader(val_dataset, batch_size=args.batch_size, shuffle=False, num_workers=args.num_workers)
    stress_loader = make_dataloader(
        stress_dataset,
        batch_size=args.batch_size,
        shuffle=False,
        num_workers=args.num_workers,
    )

    model = build_model(class_count=len(classes), pretrained=not args.no_pretrained, model_arch=args.model_arch).to(device)
    if args.init_checkpoint and args.init_checkpoint.exists():
        init_model, init_classes = load_checkpoint_model(args.init_checkpoint, device=device)
        if init_classes != classes:
            raise SystemExit(f"Init checkpoint class list mismatch: {args.init_checkpoint}")
        model.load_state_dict(init_model.state_dict())
        print(f"Loaded init checkpoint: {args.init_checkpoint}", flush=True)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=args.epochs)
    criterion = torch.nn.CrossEntropyLoss(label_smoothing=args.label_smoothing)
    scaler = torch.amp.GradScaler("cuda", enabled=device.startswith("cuda"))

    best_score = -1.0
    best_metrics: dict | None = None
    history: list[dict] = []
    started_at = time.perf_counter()
    for epoch in range(1, args.epochs + 1):
        model.train()
        total_loss = 0.0
        total_seen = 0
        for images, labels in train_loader:
            images = images.to(device, non_blocking=True)
            labels = labels.to(device, non_blocking=True)
            if args.gpu_augment:
                images = augment_batch_on_device(images, profile=args.gpu_augment_profile)
            optimizer.zero_grad(set_to_none=True)
            with torch.amp.autocast("cuda", enabled=device.startswith("cuda")):
                images, labels_a, labels_b, mixup_lambda = maybe_mixup(images, labels, alpha=args.mixup_alpha)
                logits = model(images)
                if labels_b is None:
                    loss = criterion(logits, labels_a)
                else:
                    loss = criterion(logits, labels_a) * mixup_lambda + criterion(logits, labels_b) * (1.0 - mixup_lambda)
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            total_loss += float(loss.detach().cpu()) * int(labels.numel())
            total_seen += int(labels.numel())
        scheduler.step()

        clean_metrics = evaluate_clean(model, val_loader, device=device)
        stress_metrics = (
            evaluate_stress(model, stress_loader, device=device)
            if args.stress_every and epoch % args.stress_every == 0
            else None
        )
        epoch_row = {
            "epoch": epoch,
            "loss": round(total_loss / total_seen, 4),
            "lr": round(float(scheduler.get_last_lr()[0]), 8),
            "clean_top1": clean_metrics["top1_accuracy"],
            "clean_top3": clean_metrics["top3_accuracy"],
            "stress_top1": stress_metrics["top1_accuracy"] if stress_metrics else None,
        }
        print(json.dumps(epoch_row, ensure_ascii=False), flush=True)
        history.append(epoch_row)
        score = (
            clean_metrics["top1_accuracy"] * 0.55 + stress_metrics["top1_accuracy"] * 0.45
            if stress_metrics
            else clean_metrics["top1_accuracy"]
        )
        if score > best_score:
            best_score = score
            best_metrics = {"clean": clean_metrics, "stress": stress_metrics}
            checkpoint_path = safe_torch_save(
                torch,
                {
                    "schema_version": "booth_classifier_checkpoint_v0.2",
                    "model": args.model_arch,
                    "classes": classes,
                    "state_dict": model.state_dict(),
                    "pretrained": not args.no_pretrained,
                    "train_augmentation": (
                        f"gpu_batch_{args.gpu_augment_profile}_v0.5"
                        if args.gpu_augment
                        else (
                            "opencv_fast_strong_camera_light_partial_v0.4"
                            if args.fast_augment
                            else "torchvision_strong_camera_light_partial_v0.3"
                        )
                    ),
                    "input_size": 224,
                    "normalize_mean": NORMALIZE_MEAN,
                    "normalize_std": NORMALIZE_STD,
                },
                checkpoint_path,
            )

    trained_model, _ = load_checkpoint_model(checkpoint_path, device=device)
    v2_clean = evaluate_clean(trained_model, val_loader, device=device)
    v2_stress = evaluate_stress(trained_model, stress_loader, device=device)
    v2_latency = measure_latency(trained_model, val_dataset, device=device, samples=args.latency_samples)

    comparison: dict[str, dict] = {
        args.experiment_name: {
            "checkpoint_path": str(checkpoint_path),
            "clean": v2_clean,
            "stress": v2_stress,
            "latency_ms": v2_latency,
        }
    }
    if args.compare_checkpoint and args.compare_checkpoint.exists():
        baseline_model, baseline_classes = load_checkpoint_model(args.compare_checkpoint, device=device)
        if baseline_classes == classes:
            comparison["compare_baseline"] = {
                "checkpoint_path": str(args.compare_checkpoint),
                "clean": evaluate_clean(baseline_model, val_loader, device=device),
                "stress": evaluate_stress(baseline_model, stress_loader, device=device),
                "latency_ms": measure_latency(baseline_model, val_dataset, device=device, samples=args.latency_samples),
            }
        else:
            comparison["compare_baseline"] = {
                "checkpoint_path": str(args.compare_checkpoint),
                "error": "class list mismatch",
            }

    report = {
        "schema_version": "booth_classifier_training_v0.2",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "device": device,
        "samples_dir": str(args.samples_dir),
        "class_count": len(classes),
        "train_count": len(train_items),
        "val_count": len(val_items),
        "stress_count": len(stress_dataset),
        "split": f"per_booth_every_{args.val_stride}_holdout",
        "epochs": args.epochs,
        "batch_size": args.batch_size,
        "model_arch": args.model_arch,
        "experiment_name": args.experiment_name,
        "pretrained": not args.no_pretrained,
        "init_checkpoint": str(args.init_checkpoint) if args.init_checkpoint else None,
        "cache_images": args.cache_images,
        "gpu_augment": args.gpu_augment,
        "gpu_augment_profile": args.gpu_augment_profile,
        "mixup_alpha": args.mixup_alpha,
        "history": history,
        "best_metrics": best_metrics,
        "comparison": comparison,
        "checkpoint_path": str(checkpoint_path),
        "train_seconds": round(time.perf_counter() - started_at, 2),
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {checkpoint_path}")
    print(f"Wrote {report_path}")
    print(json.dumps({"comparison": comparison, "train_seconds": report["train_seconds"]}, ensure_ascii=False, indent=2))
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train scene booth classifier with strong augmentation.")
    parser.add_argument(
        "--samples-dir",
        type=Path,
        default=Path("cloud/tmp_scene_recognition_probe/yjdd_hd_scene_samples_8fps_blur80/images"),
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("cloud/tmp_scene_recognition_probe/yjdd_hd_booth_classifier_mobilenet_v3_small_v2_aug"),
    )
    parser.add_argument("--model-arch", default="mobilenet_v3_small", choices=["mobilenet_v3_small", "mobilenet_v3_large"])
    parser.add_argument("--experiment-name", default="v2_aug")
    parser.add_argument(
        "--compare-checkpoint",
        type=Path,
        default=Path(
            "cloud/tmp_scene_recognition_probe/yjdd_hd_booth_classifier_mobilenet_v3_small/"
            "booth_classifier_mobilenet_v3_small.pt"
        ),
    )
    parser.add_argument(
        "--init-checkpoint",
        type=Path,
        default=None,
    )
    parser.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"])
    parser.add_argument("--epochs", type=int, default=12)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--num-workers", type=int, default=0)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--weight-decay", type=float, default=0.01)
    parser.add_argument("--label-smoothing", type=float, default=0.10)
    parser.add_argument("--val-stride", type=int, default=4)
    parser.add_argument("--stress-every", type=int, default=0)
    parser.add_argument("--latency-samples", type=int, default=96)
    parser.add_argument("--seed", type=int, default=20260607)
    parser.add_argument("--no-pretrained", action="store_true")
    parser.add_argument("--fast-augment", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--cache-images", action=argparse.BooleanOptionalAction, default=False)
    parser.add_argument("--cached-image-size", type=int, default=224)
    parser.add_argument("--gpu-augment", action=argparse.BooleanOptionalAction, default=False)
    parser.add_argument("--gpu-augment-profile", default="balanced", choices=["balanced", "hard_partial"])
    parser.add_argument("--mixup-alpha", type=float, default=0.0)
    return parser.parse_args()


if __name__ == "__main__":
    train(parse_args())
