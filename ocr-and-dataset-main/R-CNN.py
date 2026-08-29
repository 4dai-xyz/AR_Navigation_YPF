import sys
from pathlib import Path

import cv2
import torch
import torch.nn as nn
from PIL import Image
from torchvision import models, transforms


# Single-image R-CNN style proposal/debug test.
# Usage:
#   python R-CNN.py "G:\path\to\image.jpg"

MODEL_PATH = r"G:\kejicompany\tracker\huichang_logo_detector_binary.pth"
CLASSIFIER_PATH = r"G:\kejicompany\tracker\huichang_logo_model_final.pth"
CLASS_DIR = r"G:\kejicompany\tracker\logo_dataset"
TEST_IMAGE = r"G:\kejicompany\tracker\huichang_images_10fps\video_001\video_001_frame_000039.jpg"
OUTPUT_IMAGE = "rcnn_single_test_result.jpg"

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

DETECTOR_THRESHOLD = 0.65
CLASSIFIER_THRESHOLD = 0.75
MIN_BOX_SIZE = 20
MIN_BOX_AREA_RATIO = 0.00012
MAX_BOX_AREA_RATIO = 0.012
MAX_BOX_WIDTH_RATIO = 0.8
MIN_BOX_ASPECT = 1.15
MAX_BOX_ASPECT = 5.0
EDGE_DENSITY_THRESHOLD = 0.025
BLUE_SIGN_MIN_AREA_RATIO = 0.012
BLUE_SIGN_MIN_TALL_ASPECT = 1.8
BLUE_SIGN_MAX_WIDTH_RATIO = 0.25
NMS_IOU_THRESHOLD = 0.25
NMS_MIN_OVERLAP_THRESHOLD = 0.55
DRAW_TOP_PROPOSALS = 25
SHOW_WINDOW = False


def load_class_names():
    root = Path(CLASS_DIR)
    return sorted([p.name for p in root.iterdir() if p.is_dir()])


def enhance_contrast(image):
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    l_chan, a_chan, b_chan = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    l_chan = clahe.apply(l_chan)
    return cv2.cvtColor(cv2.merge([l_chan, a_chan, b_chan]), cv2.COLOR_LAB2BGR)


def get_blue_mask(image):
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    lower_blue = (80, 70, 120)
    upper_blue = (130, 255, 255)
    mask = cv2.inRange(hsv, lower_blue, upper_blue)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel, iterations=2)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel, iterations=1)
    return mask


def get_blue_sign_boxes(image):
    mask = get_blue_mask(image)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    frame_h, frame_w = image.shape[:2]
    frame_area = frame_h * frame_w
    sign_boxes = []

    def add_box(x, y, w, h):
        area = w * h
        if area < frame_area * BLUE_SIGN_MIN_AREA_RATIO:
            return
        if h < w * BLUE_SIGN_MIN_TALL_ASPECT:
            return
        if w > frame_w * BLUE_SIGN_MAX_WIDTH_RATIO:
            return
        sign_boxes.append((x, y, w, h))

    def split_vertical_blue_stripes(x, y, w, h):
        crop = mask[y:y + h, x:x + w]
        if crop.size == 0:
            return
        col_counts = cv2.countNonZero(crop) if False else (crop > 0).sum(axis=0)
        threshold = max(8, int(h * 0.28))
        active = col_counts >= threshold
        start = None

        for i, ok in enumerate(list(active) + [False]):
            if ok and start is None:
                start = i
            elif not ok and start is not None:
                end = i
                run_w = end - start
                if MIN_BOX_SIZE <= run_w <= frame_w * BLUE_SIGN_MAX_WIDTH_RATIO:
                    stripe = crop[:, start:end]
                    row_counts = (stripe > 0).sum(axis=1)
                    row_active = row_counts >= max(4, int(run_w * 0.25))
                    ys = [idx for idx, row_ok in enumerate(row_active) if row_ok]
                    if ys:
                        yy0, yy1 = min(ys), max(ys) + 1
                        add_box(x + start, y + yy0, run_w, yy1 - yy0)
                start = None

    for cnt in contours:
        x, y, w, h = cv2.boundingRect(cnt)
        before = len(sign_boxes)
        add_box(x, y, w, h)
        if len(sign_boxes) == before:
            split_vertical_blue_stripes(x, y, w, h)

    deduped = []
    seen = set()
    for x, y, w, h in sorted(sign_boxes, key=lambda b: b[2] * b[3], reverse=True):
        key = (x // 8, y // 8, w // 8, h // 8)
        if key in seen:
            continue
        seen.add(key)
        deduped.append((x, y, w, h))
    return deduped


def get_proposals(image):
    enhanced = enhance_contrast(image)
    gray = cv2.cvtColor(enhanced, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred, 50, 150)
    blue_sign_boxes = get_blue_sign_boxes(image)

    blue_proposals = []
    frame_h, frame_w = image.shape[:2]
    frame_area = frame_h * frame_w
    min_area = int(frame_h * frame_w * MIN_BOX_AREA_RATIO)
    max_area = int(frame_h * frame_w * MAX_BOX_AREA_RATIO)
    max_w = frame_w * MAX_BOX_WIDTH_RATIO

    def append_if_good(box, bucket):
        x, y, w, h = box
        pad_x = max(3, int(w * 0.14))
        pad_y = max(2, int(h * 0.08))
        x0 = max(0, x - pad_x)
        y0 = max(0, y - pad_y)
        x1 = min(frame_w, x + w + pad_x)
        y1 = min(frame_h, y + h + pad_y)
        x, y, w, h = x0, y0, x1 - x0, y1 - y0
        if x < 0 or y < 0 or x + w > frame_w or y + h > frame_h:
            return
        area = w * h
        if w < MIN_BOX_SIZE or h < MIN_BOX_SIZE or area < min_area or area > max_area or w >= max_w:
            return
        aspect = w / max(h, 1)
        if aspect < MIN_BOX_ASPECT or aspect > MAX_BOX_ASPECT:
            return
        roi_edges = edges[y:y + h, x:x + w]
        edge_density = cv2.countNonZero(roi_edges) / float(area)
        if edge_density < EDGE_DENSITY_THRESHOLD:
            return
        bucket.append((x, y, w, h))

    def append_blue_windows(x, y, w, h):
        # Booth ids like B-05 are usually horizontal text blocks at the top of
        # the blue guide board, so these windows stay near the top and include
        # a few full-width bands to avoid cutting off the last digit.
        top_limit = y + min(h, int(max(w * 1.15, MIN_BOX_SIZE * 2)))
        for aspect in (1.7, 2.2, 2.8):
            win_h = max(MIN_BOX_SIZE, int(w / aspect))
            if win_h <= h:
                yy = y
                while yy <= min(top_limit, y + h - win_h):
                    append_if_good((x, yy, w, win_h), blue_proposals)
                    yy += max(10, int(win_h * 0.55))

        for scale in (0.78, 1.0):
            win_w = max(MIN_BOX_SIZE, int(w * scale))
            for aspect in (1.8, 2.5):
                win_h = max(MIN_BOX_SIZE, int(win_w / aspect))
                if win_w > w or win_h > h:
                    continue
                step_x = max(12, int(win_w * 0.55))
                step_y = max(10, int(win_h * 0.55))
                yy = y
                while yy <= min(top_limit, y + h - win_h):
                    xx = x
                    while xx <= x + w - win_w:
                        append_if_good((xx, yy, win_w, win_h), blue_proposals)
                        xx += step_x
                    yy += step_y

    for x, y, w, h in blue_sign_boxes:
        append_blue_windows(x, y, w, h)

    proposals = blue_proposals

    filtered = []
    seen = set()
    for x, y, w, h in proposals:
        area = w * h
        aspect = w / max(h, 1)
        if area > frame_area * MAX_BOX_AREA_RATIO:
            continue
        if aspect < MIN_BOX_ASPECT or aspect > MAX_BOX_ASPECT:
            continue
        key = (x // 4, y // 4, w // 4, h // 4)
        if key in seen:
            continue
        seen.add(key)
        filtered.append((x, y, w, h))

    # Put more likely useful boxes first: small/medium boxes before huge blocks.
    proposals = sorted(filtered, key=lambda b: (b[2] * b[3], b[1], b[0]))
    return proposals, blue_sign_boxes


def get_classifier_crops(crop_img):
    h, w = crop_img.shape[:2]
    crops = [crop_img, enhance_contrast(crop_img)]
    if h > w * 1.2:
        top_h = max(MIN_BOX_SIZE, int(w * 0.75))
        crops.insert(0, crop_img[:min(top_h, h), :])
        crops.insert(1, enhance_contrast(crop_img[:min(top_h, h), :]))
    for angle in (-7, 7):
        center = (w / 2, h / 2)
        matrix = cv2.getRotationMatrix2D(center, angle, 1.0)
        rotated = cv2.warpAffine(crop_img, matrix, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
        crops.append(rotated)
    return crops


def box_iou(box_a, box_b):
    ax, ay, aw, ah = box_a
    bx, by, bw, bh = box_b
    ax2, ay2 = ax + aw, ay + ah
    bx2, by2 = bx + bw, by + bh

    inter_x1 = max(ax, bx)
    inter_y1 = max(ay, by)
    inter_x2 = min(ax2, bx2)
    inter_y2 = min(ay2, by2)
    inter_w = max(0, inter_x2 - inter_x1)
    inter_h = max(0, inter_y2 - inter_y1)
    inter_area = inter_w * inter_h
    if inter_area <= 0:
        return 0.0

    union = aw * ah + bw * bh - inter_area
    return inter_area / union if union > 0 else 0.0


def box_min_overlap(box_a, box_b):
    ax, ay, aw, ah = box_a
    bx, by, bw, bh = box_b
    ax2, ay2 = ax + aw, ay + ah
    bx2, by2 = bx + bw, by + bh

    inter_x1 = max(ax, bx)
    inter_y1 = max(ay, by)
    inter_x2 = min(ax2, bx2)
    inter_y2 = min(ay2, by2)
    inter_w = max(0, inter_x2 - inter_x1)
    inter_h = max(0, inter_y2 - inter_y1)
    inter_area = inter_w * inter_h
    min_area = min(aw * ah, bw * bh)
    return inter_area / min_area if min_area > 0 else 0.0


def nms(detections, iou_threshold=0.35):
    if not detections:
        return []
    dets = sorted(detections, key=lambda d: d[5], reverse=True)
    kept = []
    for det in dets:
        box = det[:4]
        if all(
            box_iou(box, k[:4]) <= iou_threshold
            and box_min_overlap(box, k[:4]) <= NMS_MIN_OVERLAP_THRESHOLD
            for k in kept
        ):
            kept.append(det)
    return kept


def load_models(class_count):
    detector = models.resnet18(weights=None)
    detector.fc = nn.Linear(detector.fc.in_features, 2)
    detector.load_state_dict(torch.load(MODEL_PATH, map_location=DEVICE))
    detector.to(DEVICE).eval()

    classifier = models.resnet18(weights=None)
    classifier.fc = nn.Linear(classifier.fc.in_features, class_count)
    classifier.load_state_dict(torch.load(CLASSIFIER_PATH, map_location=DEVICE))
    classifier.to(DEVICE).eval()
    return detector, classifier


def main():
    image_path = sys.argv[1] if len(sys.argv) > 1 else TEST_IMAGE
    class_names = load_class_names()
    detector, classifier = load_models(len(class_names))

    transform = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])

    img = cv2.imread(image_path)
    if img is None:
        print(f"Cannot read test image: {image_path}")
        raise SystemExit(1)

    proposals, blue_sign_boxes = get_proposals(img)
    print(f"Blue vertical signs: {len(blue_sign_boxes)}")
    print(f"Stage-1 proposals: {len(proposals)}")

    display = img.copy()
    for x, y, w, h in blue_sign_boxes:
        cv2.rectangle(display, (x, y), (x + w, y + h), (0, 200, 0), 3)
        cv2.putText(display, "blue_sign", (x, max(18, y - 8)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 200, 0), 2)

    for x, y, w, h in proposals[:DRAW_TOP_PROPOSALS]:
        cv2.rectangle(display, (x, y), (x + w, y + h), (255, 0, 0), 1)

    detections = []
    for (x, y, w, h) in proposals:
        crop = img[y:y + h, x:x + w]
        if crop.size == 0:
            continue

        pil_img = Image.fromarray(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB))
        input_tensor = transform(pil_img).unsqueeze(0).to(DEVICE)

        with torch.no_grad():
            det_out = detector(input_tensor)
            det_prob = torch.nn.functional.softmax(det_out, dim=1)
            det_conf, det_class = torch.max(det_prob, 1)

        if det_class.item() != 1 or det_conf.item() < DETECTOR_THRESHOLD:
            continue

        best_cls_conf = 0.0
        best_cls_idx = -1
        for cls_crop in get_classifier_crops(crop):
            pil_cls = Image.fromarray(cv2.cvtColor(cls_crop, cv2.COLOR_BGR2RGB))
            cls_tensor = transform(pil_cls).unsqueeze(0).to(DEVICE)
            with torch.no_grad():
                cls_out = classifier(cls_tensor)
                cls_prob = torch.nn.functional.softmax(cls_out, dim=1)
                cls_conf, cls_class = torch.max(cls_prob, 1)
            if cls_conf.item() > best_cls_conf:
                best_cls_conf = cls_conf.item()
                best_cls_idx = cls_class.item()

        if best_cls_conf < CLASSIFIER_THRESHOLD:
            continue

        label = class_names[best_cls_idx]
        detections.append((x, y, w, h, label, best_cls_conf, det_conf.item()))

    final_detections = nms(detections, iou_threshold=NMS_IOU_THRESHOLD)
    print(f"Detector+classifier hits before NMS: {len(detections)}")
    print(f"Final detections after NMS: {len(final_detections)}")

    for x, y, w, h, label, cls_conf, det_conf in final_detections:
        print(f"{label}: box=({x},{y},{w},{h}) cls={cls_conf:.2f} det={det_conf:.2f}")
        cv2.rectangle(display, (x, y), (x + w, y + h), (0, 0, 255), 2)
        text = f"{label} {cls_conf:.2f}/{det_conf:.2f}"
        cv2.putText(display, text, (x, max(18, y - 6)), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2)

    cv2.imwrite(OUTPUT_IMAGE, display)
    print(f"Saved debug image: {Path.cwd() / OUTPUT_IMAGE}")
    if SHOW_WINDOW:
        cv2.imshow("R-CNN Single Image Test", display)
        cv2.waitKey(0)
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
