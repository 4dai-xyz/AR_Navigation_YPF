import os
import time
import cv2
import torch
import torch.nn as nn
import numpy as np
from torchvision import transforms, models
from PIL import Image

# ================= 1. Config =================
VIDEO_PATH = r"G:\kejicompany\new\1000086252.mp4"
OUTPUT_VIDEO = "rcnn_visualize.mp4"

DETECTOR_PTH = r"G:\kejicompany\tracker\logo_detector_binary.pth"
CLASSIFIER_PTH = r"G:\kejicompany\tracker\logo_model_final.pth"
MULTI_CLASS_DIR = r"G:\kejicompany\cnn_dataset"

FRAME_SKIP = 5
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
TOP_REGION_RATIO = 0.6
MIN_BOX_SIZE = 60
MIN_BOX_AREA_RATIO = 0.002
SHOW_CONF_THRESHOLD = 0.6
LOGO_CONF_THRESHOLD = 0.85

PANEL_ENABLED = True
PANEL_WIDTH = 320
PANEL_MAX_ITEMS = 6

SHOW_WINDOW = True
ROTATE_CODE = None
# ============================================


def load_brand_names():
    if not os.path.isdir(MULTI_CLASS_DIR):
        return []
    return sorted(
        d for d in os.listdir(MULTI_CLASS_DIR)
        if os.path.isdir(os.path.join(MULTI_CLASS_DIR, d))
    )


def build_models(brand_names):
    if not os.path.isfile(DETECTOR_PTH):
        raise FileNotFoundError(f"Detector not found: {DETECTOR_PTH}")

    detector = models.resnet18()
    detector.fc = nn.Linear(detector.fc.in_features, 2)
    detector.load_state_dict(torch.load(DETECTOR_PTH, map_location=DEVICE))
    detector.to(DEVICE).eval()

    classifier = None
    if brand_names and os.path.isfile(CLASSIFIER_PTH):
        classifier = models.resnet18()
        classifier.fc = nn.Linear(classifier.fc.in_features, len(brand_names))
        classifier.load_state_dict(torch.load(CLASSIFIER_PTH, map_location=DEVICE))
        classifier.to(DEVICE).eval()

    return detector, classifier


def get_smart_proposals(frame):
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred, 50, 150)
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    proposals = []
    frame_h, frame_w = frame.shape[:2]
    min_area = int(frame_h * frame_w * MIN_BOX_AREA_RATIO)

    for cnt in contours:
        x, y, w, h = cv2.boundingRect(cnt)
        if w >= MIN_BOX_SIZE and h >= MIN_BOX_SIZE and (w * h) >= min_area and w < frame_w * 0.5:
            proposals.append((x, y, w, h))

    if not proposals:
        return proposals

    cutoff_y = int(frame_h * TOP_REGION_RATIO)
    upper = [p for p in proposals if p[1] + (p[3] // 2) <= cutoff_y]
    return upper if upper else proposals


def build_panel(frame, results, panel_width, max_items):
    frame_h, frame_w = frame.shape[:2]
    panel = np.zeros((frame_h, panel_width, 3), dtype=np.uint8)
    panel[:] = (20, 20, 20)

    if not results:
        cv2.putText(panel, "No proposals", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (200, 200, 200), 2)
        return panel

    items = sorted(results, key=lambda r: r["conf"], reverse=True)[:max_items]

    y = 10
    padding = 10
    text_h = 22
    for item in items:
        x, y0, w, h = item["bbox"]
        crop = frame[y0:y0 + h, x:x + w]
        if crop.size == 0:
            continue

        thumb_w = panel_width - (2 * padding)
        scale = thumb_w / max(1, w)
        thumb_h = int(h * scale)
        if y + thumb_h + text_h + padding > frame_h:
            break

        thumb = cv2.resize(crop, (thumb_w, thumb_h))
        panel[y:y + thumb_h, padding:padding + thumb_w] = thumb

        label = item["label"]
        if item.get("brand_name"):
            label = f"{label}:{item['brand_name']}"
        text = f"{label} {item['conf']:.2f}"
        color = (0, 255, 0) if item["is_logo"] else (0, 0, 255)
        cv2.putText(panel, text, (padding, y + thumb_h + text_h - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.55, color, 2)

        y += thumb_h + text_h + padding

    return panel


def main():
    brand_names = load_brand_names()
    detector, classifier = build_models(brand_names)

    transform = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
    ])

    cap = cv2.VideoCapture(VIDEO_PATH)
    if not cap.isOpened():
        raise RuntimeError(f"Unable to open video: {VIDEO_PATH}")

    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = 25

    base_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    base_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    if ROTATE_CODE in (cv2.ROTATE_90_CLOCKWISE, cv2.ROTATE_90_COUNTERCLOCKWISE):
        base_w, base_h = base_h, base_w

    out_w = base_w + (PANEL_WIDTH if PANEL_ENABLED else 0)
    out_h = base_h

    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    out_video = cv2.VideoWriter(OUTPUT_VIDEO, fourcc, fps, (out_w, out_h))

    frame_idx = 0
    last_results = []
    start_time = time.time()

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        if ROTATE_CODE is not None:
            frame = cv2.rotate(frame, ROTATE_CODE)

        frame_idx += 1
        display_frame = frame.copy()

        if frame_idx % FRAME_SKIP == 0:
            results = []
            proposals = get_smart_proposals(frame)

            for (x, y, w, h) in proposals:
                crop_img = frame[y:y + h, x:x + w]
                if crop_img.size == 0:
                    continue

                pil_img = Image.fromarray(cv2.cvtColor(crop_img, cv2.COLOR_BGR2RGB))
                input_tensor = transform(pil_img).unsqueeze(0).to(DEVICE)

                with torch.no_grad():
                    det_out = detector(input_tensor)
                    det_prob = torch.nn.functional.softmax(det_out, dim=1)
                    det_conf, det_class = torch.max(det_prob, 1)

                det_conf_val = det_conf.item()
                if det_conf_val < SHOW_CONF_THRESHOLD:
                    continue

                is_logo = det_class.item() == 1
                label = "logo" if is_logo else "non-logo"

                brand_name = None
                if is_logo and det_conf_val >= LOGO_CONF_THRESHOLD and classifier is not None:
                    with torch.no_grad():
                        cls_out = classifier(input_tensor)
                        cls_prob = torch.nn.functional.softmax(cls_out, dim=1)
                        cls_conf, cls_class = torch.max(cls_prob, 1)
                    brand_name = brand_names[cls_class.item()]
                    det_conf_val = cls_conf.item()

                results.append({
                    "bbox": (x, y, w, h),
                    "is_logo": is_logo,
                    "label": label,
                    "conf": det_conf_val,
                    "brand_name": brand_name,
                })

            last_results = results

        for item in last_results:
            x, y, w, h = item["bbox"]
            color = (0, 255, 0) if item["is_logo"] else (0, 0, 255)
            cv2.rectangle(display_frame, (x, y), (x + w, y + h), color, 2)

            label = item["label"]
            if item.get("brand_name"):
                label = f"{label}:{item['brand_name']}"
            text = f"{label} {item['conf']:.2f}"

            (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, 0.6, 2)
            cv2.rectangle(display_frame, (x, y - th - 8), (x + tw + 4, y), color, -1)
            cv2.putText(display_frame, text, (x + 2, y - 5), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 0), 2)

        if PANEL_ENABLED:
            panel = build_panel(frame, last_results, PANEL_WIDTH, PANEL_MAX_ITEMS)
            combined = np.zeros((base_h, base_w + PANEL_WIDTH, 3), dtype=np.uint8)
            combined[:, :base_w] = display_frame
            combined[:, base_w:] = panel
        else:
            combined = display_frame

        out_video.write(combined)

        if SHOW_WINDOW:
            cv2.imshow("RCNN Visualize", combined)
            if cv2.waitKey(1) & 0xFF == ord("q"):
                break

    cap.release()
    out_video.release()
    if SHOW_WINDOW:
        cv2.destroyAllWindows()

    elapsed = time.time() - start_time
    avg_fps = frame_idx / elapsed if elapsed > 0 else 0.0
    print(f"Done. Saved to {OUTPUT_VIDEO}")
    print(f"Elapsed: {elapsed:.2f}s | Avg FPS: {avg_fps:.2f}")


if __name__ == "__main__":
    main()
