import random
import re
from pathlib import Path

from PIL import Image, ImageEnhance, ImageOps


# ================= Config =================
DATA_ROOT = Path(r"G:\kejicompany\tracker\logo_dataset")
TARGET_PER_CLASS = 120
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".bmp", ".webp")

# Keep augmentation gentle: booth numbers/logos should stay readable.
MAX_ROTATE_DEG = 6
COLOR_JITTER = 0.12
MAX_CROP_RATIO = 0.06
MAX_TRANSLATE_RATIO = 0.04
RANDOM_SEED = 42
# ==========================================


def natural_sort_key(value):
    return [int(text) if text.isdigit() else text.lower()
            for text in re.split(r"([0-9]+)", str(value))]


def image_files(class_dir):
    return sorted(
        [
            p for p in class_dir.iterdir()
            if p.is_file()
            and p.suffix.lower() in IMAGE_EXTS
            and "_aug" not in p.stem
        ],
        key=lambda p: natural_sort_key(p.name),
    )


def all_image_files(class_dir):
    return sorted(
        [p for p in class_dir.iterdir() if p.is_file() and p.suffix.lower() in IMAGE_EXTS],
        key=lambda p: natural_sort_key(p.name),
    )


def apply_color_jitter(img):
    img = ImageEnhance.Brightness(img).enhance(random.uniform(1 - COLOR_JITTER, 1 + COLOR_JITTER))
    img = ImageEnhance.Contrast(img).enhance(random.uniform(1 - COLOR_JITTER, 1 + COLOR_JITTER))
    img = ImageEnhance.Color(img).enhance(random.uniform(1 - COLOR_JITTER, 1 + COLOR_JITTER))
    return img


def gentle_spatial_aug(img):
    w, h = img.size

    angle = random.uniform(-MAX_ROTATE_DEG, MAX_ROTATE_DEG)
    img = img.rotate(angle, resample=Image.Resampling.BILINEAR, expand=False)

    # Slight crop/translate, then resize back to original size.
    crop_w = int(w * random.uniform(1 - MAX_CROP_RATIO, 1.0))
    crop_h = int(h * random.uniform(1 - MAX_CROP_RATIO, 1.0))
    max_dx = max(0, w - crop_w)
    max_dy = max(0, h - crop_h)

    tx = int(w * random.uniform(-MAX_TRANSLATE_RATIO, MAX_TRANSLATE_RATIO))
    ty = int(h * random.uniform(-MAX_TRANSLATE_RATIO, MAX_TRANSLATE_RATIO))
    left = min(max((max_dx // 2) + tx, 0), max_dx)
    top = min(max((max_dy // 2) + ty, 0), max_dy)

    img = img.crop((left, top, left + crop_w, top + crop_h))
    img = img.resize((w, h), resample=Image.Resampling.BILINEAR)

    if random.random() < 0.08:
        img = ImageOps.autocontrast(img)

    return img


def augment_one(src_path):
    with Image.open(src_path) as img:
        img = img.convert("RGB")
        img = gentle_spatial_aug(img)
        img = apply_color_jitter(img)
        return img


def run_augmentation():
    random.seed(RANDOM_SEED)
    if not DATA_ROOT.exists():
        raise FileNotFoundError(f"DATA_ROOT not found: {DATA_ROOT}")

    class_dirs = sorted([p for p in DATA_ROOT.iterdir() if p.is_dir()], key=lambda p: natural_sort_key(p.name))
    total_generated = 0
    summary = []

    for class_dir in class_dirs:
        current_files = all_image_files(class_dir)
        current_count = len(current_files)
        if current_count >= TARGET_PER_CLASS:
            summary.append((class_dir.name, current_count, 0))
            continue

        source_files = image_files(class_dir)
        if not source_files:
            print(f"[WARN] skip empty class: {class_dir.name}")
            summary.append((class_dir.name, current_count, 0))
            continue

        need = TARGET_PER_CLASS - current_count
        generated = 0
        for i in range(need):
            src = random.choice(source_files)
            try:
                aug_img = augment_one(src)
                save_name = f"{src.stem}_aug_{current_count + i + 1:04d}{src.suffix.lower()}"
                aug_img.save(class_dir / save_name, quality=95)
                generated += 1
            except Exception as exc:
                print(f"[WARN] failed {src}: {exc}")

        total_generated += generated
        summary.append((class_dir.name, current_count, generated))

    print("=" * 60)
    print(f"Augmentation done. Target per class: {TARGET_PER_CLASS}")
    print(f"Total generated: {total_generated}")
    print(f"Dataset: {DATA_ROOT}")
    print("-" * 60)
    for name, before, generated in summary:
        if generated:
            print(f"{name}: {before} -> {before + generated}")
    print("=" * 60)


if __name__ == "__main__":
    run_augmentation()
