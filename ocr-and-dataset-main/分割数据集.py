import json
import random
import re
import shutil
from pathlib import Path

import cv2


# ================= Config =================
# Build the R-CNN / binary detector dataset.
# positive: all images from logo_dataset
# negative: random crops from uncropped regions and skipped/no-label frames
BASE_DIR = Path(__file__).resolve().parent
LOGO_DATASET = BASE_DIR / "logo_dataset"
OUT_DIR = BASE_DIR / "Huichang_RCNN_Dataset"
POS_DIR = OUT_DIR / "positive"
NEG_DIR = OUT_DIR / "negative"

VIDEO_SOURCES = [
    {
        "video_tag": "out1",
        "frame_dir": BASE_DIR / "huichang_images_10fps" / "video_001",
        "json_path": BASE_DIR / "huichang_slam_data_10fps.json",
    },
    {
        "video_tag": "out2",
        "frame_dir": BASE_DIR / "huichang_images_10fps" / "video_002",
        "json_path": BASE_DIR / "huichang_slam_data_10fps_video_002.json",
    },
    {
        "video_tag": "out3",
        "frame_dir": BASE_DIR / "huichang_images_10fps" / "video_003",
        "json_path": BASE_DIR / "huichang_slam_data_10fps_video_003.json",
    },
]

IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".bmp", ".webp")
RANDOM_SEED = 42
NEGATIVE_PER_LABELED_FRAME = 2
NEGATIVE_PER_EMPTY_FRAME = 4
MAX_RANDOM_TRIES = 80
MIN_NEG_SIZE = 60
BOX_PADDING = 8
# ==========================================


def safe_name(value):
    value = str(value)
    value = re.sub(r'[\\/:*?"<>|]+', "_", value)
    value = re.sub(r"\s+", "_", value).strip("_")
    return value or "unknown"


def natural_sort_key(value):
    return [int(text) if text.isdigit() else text.lower()
            for text in re.split(r"([0-9]+)", str(value))]


def is_image(path):
    return path.is_file() and path.suffix.lower() in IMAGE_EXTS


def frame_number_from_name(name):
    match = re.search(r"frame_(\d+)", str(name))
    if match:
        return match.group(1)
    match = re.search(r"(\d+)", Path(str(name)).stem)
    if match:
        return match.group(1)
    return safe_name(Path(str(name)).stem)


def box_intersects(a, b):
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    return not (ax2 <= bx1 or bx2 <= ax1 or ay2 <= by1 or by2 <= ay1)


def bbox_to_xyxy(bbox, img_w, img_h):
    x, y, w, h = [int(v) for v in bbox]
    x1 = max(0, x - BOX_PADDING)
    y1 = max(0, y - BOX_PADDING)
    x2 = min(img_w, x + w + BOX_PADDING)
    y2 = min(img_h, y + h + BOX_PADDING)
    if x2 <= x1 or y2 <= y1:
        return None
    return x1, y1, x2, y2


def load_frames(json_path):
    if not json_path.exists():
        print(f"[WARN] missing json: {json_path}")
        return {}
    with open(json_path, "r", encoding="utf-8") as f:
        payload = json.load(f)
    frames = payload.get("frames", payload)
    return frames if isinstance(frames, dict) else {}


def reset_output_dirs():
    if OUT_DIR.exists():
        shutil.rmtree(OUT_DIR)
    POS_DIR.mkdir(parents=True, exist_ok=True)
    NEG_DIR.mkdir(parents=True, exist_ok=True)


def copy_positive_from_logo_dataset():
    if not LOGO_DATASET.exists():
        raise FileNotFoundError(f"LOGO_DATASET not found: {LOGO_DATASET}")

    copied = 0
    class_dirs = sorted([p for p in LOGO_DATASET.iterdir() if p.is_dir()], key=lambda p: natural_sort_key(p.name))
    for class_dir in class_dirs:
        label = safe_name(class_dir.name)
        for img_path in sorted([p for p in class_dir.iterdir() if is_image(p)], key=lambda p: natural_sort_key(p.name)):
            dst_name = f"{label}_{safe_name(img_path.stem)}{img_path.suffix.lower()}"
            shutil.copy2(img_path, POS_DIR / dst_name)
            copied += 1
    return copied


def random_negative_size(logo_boxes, img_w, img_h):
    if logo_boxes:
        x1, y1, x2, y2 = random.choice(logo_boxes)
        bw = max(MIN_NEG_SIZE, x2 - x1)
        bh = max(MIN_NEG_SIZE, y2 - y1)
        scale = random.uniform(0.8, 1.5)
        rw = int(bw * scale)
        rh = int(bh * scale)
    else:
        rw = rh = random.randint(80, 160)

    rw = max(MIN_NEG_SIZE, min(rw, img_w))
    rh = max(MIN_NEG_SIZE, min(rh, img_h))
    return rw, rh


def save_negative_crops(img, frame_name, labels, video_tag, target_count):
    img_h, img_w = img.shape[:2]
    if img_w < MIN_NEG_SIZE or img_h < MIN_NEG_SIZE:
        return 0

    logo_boxes = []
    for info in labels.values():
        bbox = info.get("bbox") if isinstance(info, dict) else None
        if not bbox:
            continue
        box = bbox_to_xyxy(bbox, img_w, img_h)
        if box is not None:
            logo_boxes.append(box)

    frame_no = frame_number_from_name(frame_name)
    saved = 0
    tries = 0
    while saved < target_count and tries < MAX_RANDOM_TRIES * max(target_count, 1):
        tries += 1
        rw, rh = random_negative_size(logo_boxes, img_w, img_h)
        if rw >= img_w or rh >= img_h:
            continue

        rx = random.randint(0, img_w - rw)
        ry = random.randint(0, img_h - rh)
        neg_box = (rx, ry, rx + rw, ry + rh)

        if any(box_intersects(neg_box, logo_box) for logo_box in logo_boxes):
            continue

        crop = img[ry:ry + rh, rx:rx + rw]
        if crop.size == 0:
            continue

        save_name = f"{video_tag}_{frame_no}_bg_{rx}_{ry}_{saved}.jpg"
        if cv2.imwrite(str(NEG_DIR / save_name), crop):
            saved += 1

    return saved


def build_negative_dataset():
    total_neg = 0
    total_frames = 0
    empty_frames = 0
    labeled_frames = 0
    missing_images = 0

    for source in VIDEO_SOURCES:
        video_tag = source["video_tag"]
        frame_dir = source["frame_dir"]
        frames = load_frames(source["json_path"])

        if not frame_dir.exists():
            print(f"[WARN] missing frame dir: {frame_dir}")
            continue

        frame_names = sorted([p.name for p in frame_dir.iterdir() if is_image(p)], key=natural_sort_key)
        for frame_name in frame_names:
            labels = frames.get(frame_name, {})
            img = cv2.imread(str(frame_dir / frame_name))
            if img is None:
                missing_images += 1
                continue

            if labels:
                target_count = NEGATIVE_PER_LABELED_FRAME
                labeled_frames += 1
            else:
                target_count = NEGATIVE_PER_EMPTY_FRAME
                empty_frames += 1

            total_neg += save_negative_crops(img, frame_name, labels, video_tag, target_count)
            total_frames += 1

    return {
        "negative": total_neg,
        "frames": total_frames,
        "empty_frames": empty_frames,
        "labeled_frames": labeled_frames,
        "missing_images": missing_images,
    }


def build_rcnn_dataset():
    random.seed(RANDOM_SEED)
    reset_output_dirs()

    pos_count = copy_positive_from_logo_dataset()
    neg_stats = build_negative_dataset()

    print("=" * 60)
    print("R-CNN / binary detector dataset built.")
    print(f"Positive samples copied from logo_dataset: {pos_count}")
    print(f"Negative samples generated: {neg_stats['negative']}")
    print(f"Frames scanned: {neg_stats['frames']}")
    print(f"Labeled frames: {neg_stats['labeled_frames']}")
    print(f"Empty/no-logo frames: {neg_stats['empty_frames']}")
    if neg_stats["missing_images"]:
        print(f"Missing images: {neg_stats['missing_images']}")
    print(f"Output: {OUT_DIR}")
    print("=" * 60)


if __name__ == "__main__":
    build_rcnn_dataset()
