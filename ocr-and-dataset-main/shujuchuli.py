import cv2
import json
import os
import re


# ================= Config =================
# Logo classification dataset cropper.
IMG_DIR = r"G:\kejicompany\tracker\huichang_images_10fps\video_003"
JSON_PATH = r"G:\kejicompany\tracker\huichang_slam_data_10fps_video_003.json"
SAVE_ROOT = r"G:\kejicompany\tracker\logo_dataset"
OUTPUT_PREFIX = "out3"
CROP_PADDING = 10
# ==========================================


def safe_name(value):
    value = str(value)
    value = re.sub(r'[\\/:*?"<>|]+', "_", value)
    value = re.sub(r"\s+", "_", value).strip("_")
    return value or "unknown"


def frame_number_from_name(img_name):
    match = re.search(r"frame_(\d+)", img_name)
    if match:
        return match.group(1)
    match = re.search(r"(\d+)", os.path.splitext(img_name)[0])
    if match:
        return match.group(1)
    return safe_name(os.path.splitext(img_name)[0])


def prepare_cnn_dataset():
    with open(JSON_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)
    frames_data = data.get("frames", data)

    os.makedirs(SAVE_ROOT, exist_ok=True)

    saved = 0
    missing = 0
    skipped = 0
    print(">>> Cropping logo classification dataset...")

    for img_name, logos in frames_data.items():
        if not logos:
            continue

        img = cv2.imread(os.path.join(IMG_DIR, img_name))
        if img is None:
            missing += 1
            continue

        img_h, img_w = img.shape[:2]
        frame_no = frame_number_from_name(img_name)

        for idx, (full_name, info) in enumerate(logos.items()):
            bbox = info.get("bbox")
            if not bbox:
                skipped += 1
                continue

            class_name = safe_name(full_name)
            class_dir = os.path.join(SAVE_ROOT, class_name)
            os.makedirs(class_dir, exist_ok=True)

            x, y, w, h = [int(v) for v in bbox]
            y1 = max(0, y - CROP_PADDING)
            y2 = min(img_h, y + h + CROP_PADDING)
            x1 = max(0, x - CROP_PADDING)
            x2 = min(img_w, x + w + CROP_PADDING)
            crop = img[y1:y2, x1:x2]

            if crop.size == 0:
                skipped += 1
                continue

            save_name = f"{OUTPUT_PREFIX}_{frame_no}_{class_name}_{idx}.jpg"
            if cv2.imwrite(os.path.join(class_dir, save_name), crop):
                saved += 1

    print("=" * 50)
    print(f"Saved crops: {saved}")
    print(f"Missing source frames: {missing}")
    print(f"Skipped boxes: {skipped}")
    print(f"Output: {SAVE_ROOT}")
    print("=" * 50)


if __name__ == "__main__":
    prepare_cnn_dataset()
