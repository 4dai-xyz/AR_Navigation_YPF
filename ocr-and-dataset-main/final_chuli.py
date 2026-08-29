import os
import re
import cv2
import time
import difflib
from collections import defaultdict

import torch
import torch.nn as nn
from torchvision import transforms, models
from PIL import Image


# ================= 1. Global Config =================
VIDEO_PATH = r"G:\kejicompany\new\1000086258.mp4"
OUTPUT_VIDEO = "result_video.mp4"

DETECTOR_PTH = r"G:\kejicompany\tracker\logo_detector_binary.pth"
TOKEN_CLASSIFIER_PTH = r"G:\kejicompany\tracker\ocr_classifier_multi.pth"
LOGO_CLASSIFIER_PTH = r"G:\kejicompany\tracker\logo_classifier_with_nonlogo.pth"
MULTI_CLASS_DIR = r"G:\kejicompany\char_dataset"
MAPPING_PATH = r"G:\kejicompany\tracker\label_id_mapping.txt"

FRAME_SKIP = 3
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

TOP_REGION_RATIO = 0.6
MIN_BOX_SIZE = 60
MIN_BOX_AREA_RATIO = 0.002
ROTATE_CODE = None   

DETECTOR_CONF_THRESHOLD = 0.75
DISPLAY_CONF_THRESHOLD = 0.80
FINAL_RENDER_CONF_THRESHOLD = 0.80
TOKEN_CONF_THRESHOLD = 0.45
TOKEN_FALLBACK_CONF_THRESHOLD = 0.35
LOGO_CONF_THRESHOLD = 0.80
CLASS_MARGIN_THRESHOLD = 0.10
NON_LOGO_CLASS_NAME = "non_logo"
LOGO_MAX_BOX_AREA_RATIO = 0.20
LOGO_MIN_ASPECT_RATIO = 0.35
LOGO_MAX_ASPECT_RATIO = 3.5
LOGO_CONTAINMENT_RATIO = 0.90
LOGO_NMS_IOU_THRESHOLD = 0.45

# Mapping merge
MAX_SEQ_LEN = 8
ROW_TOLERANCE = 0.6
GAP_MULTIPLIER = 2.0
MATCH_CUTOFF = 0.72
MIN_CHAR_SEQ_LEN_TO_SHOW = 2
SUBSTRING_IOU_THRESHOLD = 0.16
TRACK_TTL_FRAMES = 15
TRACK_IOU_MATCH = 0.25
TRACK_CENTER_DIST = 120
TRACK_SUBSTRING_IOU_THRESHOLD = 0.25
TRACK_DUP_IOU_THRESHOLD = 0.12
TRACK_DUP_CENTER_DIST = 140
CHAR_DUP_IOU_THRESHOLD = 0.30
CHAR_DUP_CENTER_DIST_RATIO = 0.50
MAX_ALLOWED_X_OVERLAP_RATIO = 0.20
# =================================================


def normalize_for_match(text):
    text = (text or "").strip().lower()
    text = text.replace("logo_", "")
    text = text.replace("-", "")
    text = text.replace(" ", "")
    text = text.replace("_", "")
    text = re.sub(r"[^a-z0-9+]", "", text)
    return text


def visible_text_len(text):
    base = strip_logo_prefix(text or "")
    base = re.sub(r"[\s_\-]", "", base)
    return len(base)


def strip_logo_prefix(text):
    if not text:
        return text
    low = text.lower()
    if low.startswith("logo_"):
        return text[5:]
    return text


def load_label_id_mapping(mapping_path): 
    pattern = re.compile(r"^([A-Za-z0-9+_]+)_(\d+)$")
    rows = []
    norm_to_rows = defaultdict(list)

    if not os.path.exists(mapping_path):
        print(f"[WARN] Mapping file not found: {mapping_path}")
        return rows, norm_to_rows

    with open(mapping_path, "r", encoding="utf-8", errors="ignore") as f:
        for raw_line in f:
            line = raw_line.strip()
            m = pattern.match(line)
            if not m:
                continue
            label_base = m.group(1).lower()  # trailing id is historical, ignored
            norm = normalize_for_match(label_base)
            if not norm:
                continue
            item = {"base": label_base, "text": label_base, "norm": norm}
            rows.append(item)
            norm_to_rows[norm].append(item)

    print(f"[INFO] Loaded {len(rows)} mapping rows from {mapping_path}")
    return rows, norm_to_rows


MAPPING_ROWS, NORM_TO_ROWS = load_label_id_mapping(MAPPING_PATH)
MAPPING_NORMS = list(NORM_TO_ROWS.keys())


def pick_canonical_row(norm):
    candidates = NORM_TO_ROWS.get(norm, [])
    if not candidates:
        return None
    candidates = sorted(candidates, key=lambda x: x["base"])
    return candidates[0]


def resolve_one_token_to_mapping(token):
    base = strip_logo_prefix(token).lower()
    norm = normalize_for_match(base)
    if not norm:
        return base, 0.0

    exact = pick_canonical_row(norm)
    if exact is not None:
        return exact["base"], 1.0

    if not MAPPING_NORMS:
        return base, 0.0

    best = difflib.get_close_matches(norm, MAPPING_NORMS, n=1, cutoff=0.84)
    if not best:
        return base, 0.0

    row = pick_canonical_row(best[0])
    ratio = difflib.SequenceMatcher(None, norm, best[0]).ratio()
    if row is None:
        return base, 0.0
    return row["base"], ratio


def is_geometry_compatible(det_a, det_b):
    ax, ay, aw, ah = det_a[:4]
    bx, by, bw, bh = det_b[:4]

    a_cy = ay + ah / 2.0
    b_cy = by + bh / 2.0
    y_diff = abs(a_cy - b_cy)
    avg_h = max((ah + bh) / 2.0, 1.0)

    gap_x = bx - (ax + aw)
    avg_w = max((aw + bw) / 2.0, 1.0)
    overlap_x = max(0.0, (ax + aw) - bx)

    same_row = y_diff <= ROW_TOLERANCE * avg_h
    not_too_far = gap_x <= GAP_MULTIPLIER * avg_w
    not_too_overlapped = overlap_x <= MAX_ALLOWED_X_OVERLAP_RATIO * avg_w
    return same_row and not_too_far and not_too_overlapped


def is_duplicate_neighbor(det_a, det_b):
    ax, ay, aw, ah, atxt, _ = det_a
    bx, by, bw, bh, btxt, _ = det_b
    if normalize_for_match(atxt) != normalize_for_match(btxt):
        return False
    iou = box_iou((ax, ay, aw, ah), (bx, by, bw, bh))
    acx, acy = ax + aw / 2.0, ay + ah / 2.0
    bcx, bcy = bx + bw / 2.0, by + bh / 2.0
    dist = ((acx - bcx) ** 2 + (acy - bcy) ** 2) ** 0.5
    avg_w = max((aw + bw) / 2.0, 1.0)
    avg_h = max((ah + bh) / 2.0, 1.0)
    return iou >= 0.15 or dist <= 0.6 * max(avg_w, avg_h)


def dedupe_char_candidates(chars):
    # Remove duplicate char detections in nearly the same position.
    if not chars:
        return []

    sorted_chars = sorted(chars, key=lambda x: x[5], reverse=True)
    kept = []
    for cand in sorted_chars:
        x, y, w, h, name, conf = cand
        n1 = normalize_for_match(name)
        c1 = (x + w / 2.0, y + h / 2.0)
        drop = False

        for k in kept:
            kx, ky, kw, kh, kname, _ = k
            n2 = normalize_for_match(kname)
            if n1 != n2:
                continue

            iou = box_iou((x, y, w, h), (kx, ky, kw, kh))
            c2 = (kx + kw / 2.0, ky + kh / 2.0)
            dist = ((c1[0] - c2[0]) ** 2 + (c1[1] - c2[1]) ** 2) ** 0.5
            avg_w = max((w + kw) / 2.0, 1.0)
            avg_h = max((h + kh) / 2.0, 1.0)

            near_same_center = (
                dist <= CHAR_DUP_CENTER_DIST_RATIO * max(avg_w, avg_h)
            )
            if iou >= CHAR_DUP_IOU_THRESHOLD or near_same_center:
                drop = True
                break

        if not drop:
            kept.append(cand)

    # Restore reading order for merge
    kept = sorted(kept, key=lambda item: (item[1], item[0]))
    return kept


def match_sequence_to_mapping(seq_tokens):
    raw = "".join([t[4] for t in seq_tokens]).lower()
    norm = normalize_for_match(raw)
    if not norm:
        return None, 0.0

    exact_row = pick_canonical_row(norm)
    if exact_row is not None:
        return exact_row, 1.0

    if not MAPPING_NORMS:
        return None, 0.0

    best = difflib.get_close_matches(norm, MAPPING_NORMS, n=1, cutoff=MATCH_CUTOFF)
    if not best:
        return None, 0.0

    best_norm = best[0]
    row = pick_canonical_row(best_norm)
    if row is None:
        return None, 0.0

    ratio = difflib.SequenceMatcher(None, norm, best_norm).ratio()
    return row, ratio


def merge_chars_by_mapping(chars):
    if not chars:
        return []

    chars = sorted(chars, key=lambda item: (item[1], item[0]))
    proposals = []

    for i in range(len(chars)):
        seq = [chars[i]]
        for j in range(i, min(i + MAX_SEQ_LEN, len(chars))):
            if j > i:
                if not is_geometry_compatible(chars[j - 1], chars[j]):
                    break
                if is_duplicate_neighbor(chars[j - 1], chars[j]):
                    break
                seq.append(chars[j])

            token_len = j - i + 1
            if token_len < MIN_CHAR_SEQ_LEN_TO_SHOW:
                continue

            conf_avg = sum([x[5] for x in seq]) / len(seq)
            raw_text = "".join([t[4] for t in seq])
            row, ratio = match_sequence_to_mapping(seq)
            if row is None:
                # Realtime fallback: even when full mapping not matched yet,
                # show progressive merged text from visible sequence.
                display_text = raw_text
                score = 0.60 * conf_avg
            else:
                display_text = row["text"]
                score = (0.75 + 0.25 * ratio) * conf_avg

            min_x = min([x[0] for x in seq])
            min_y = min([x[1] for x in seq])
            max_r = max([x[0] + x[2] for x in seq])
            max_b = max([x[1] + x[3] for x in seq])

            proposals.append({
                "start": i,
                "end": j,
                "score": score,
                "conf": conf_avg,
                "token_len": token_len,
                "display_text": display_text,
                "bbox": (int(min_x), int(min_y), int(max_r - min_x), int(max_b - min_y)),
            })

    if not proposals:
        return []

    proposals.sort(key=lambda x: x["score"], reverse=True)
    used = set()
    chosen = []
    for p in proposals:
        idx_set = set(range(p["start"], p["end"] + 1))
        if used.intersection(idx_set):
            continue
        chosen.append(p)
        used.update(idx_set)

    out = []
    for p in chosen:
        if p["token_len"] < MIN_CHAR_SEQ_LEN_TO_SHOW:
            continue
        if visible_text_len(p["display_text"]) < MIN_CHAR_SEQ_LEN_TO_SHOW:
            continue
        x, y, w, h = p["bbox"]
        out.append((x, y, w, h, p["display_text"], p["conf"]))
    return out


def suppress_short_substrings(detections, iou_threshold=0.20):
    # Remove shorter text when a longer overlapping text already exists.
    # detections: [(x,y,w,h,text,conf), ...]
    if not detections:
        return []

    sorted_dets = sorted(
        detections,
        key=lambda d: (len(str(d[4])), d[5]),
        reverse=True
    )
    kept = []
    for det in sorted_dets:
        x, y, w, h, text, conf = det
        text_norm = normalize_for_match(text)
        drop = False
        for k in kept:
            ktext_norm = normalize_for_match(k[4])
            iou = box_iou((x, y, w, h), (k[0], k[1], k[2], k[3]))
            if iou < iou_threshold:
                continue
            # same area and short text is substring of long text -> drop short one
            if text_norm and ktext_norm and text_norm != ktext_norm and text_norm in ktext_norm:
                drop = True
                break
        if not drop:
            kept.append(det)
    return kept


def post_process_and_merge_boxes(predictions):
    if not predictions:
        return []

    logos = []
    chars = []
    for x, y, w, h, cls_name, conf in predictions:
        cls_clean = strip_logo_prefix(cls_name)
        if cls_name.lower().startswith("logo_"):
            label_base, _ = resolve_one_token_to_mapping(cls_clean)
            logos.append((x, y, w, h, label_base, conf))
        else:
            chars.append((x, y, w, h, cls_clean, conf))

    chars = dedupe_char_candidates(chars)
    merged_chars = merge_chars_by_mapping(chars)
    merged_chars = suppress_short_substrings(
        merged_chars,
        iou_threshold=SUBSTRING_IOU_THRESHOLD
    )
    return logos + merged_chars


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
    area_a = aw * ah
    area_b = bw * bh
    union = area_a + area_b - inter_area
    return inter_area / union if union > 0 else 0.0


def box_contains(outer_box, inner_box, containment_ratio=0.90):
    ox, oy, ow, oh = outer_box
    ix, iy, iw, ih = inner_box
    ox2, oy2 = ox + ow, oy + oh
    ix2, iy2 = ix + iw, iy + ih

    inter_x1 = max(ox, ix)
    inter_y1 = max(oy, iy)
    inter_x2 = min(ox2, ix2)
    inter_y2 = min(oy2, iy2)
    inter_w = max(0, inter_x2 - inter_x1)
    inter_h = max(0, inter_y2 - inter_y1)
    inter_area = inter_w * inter_h
    inner_area = iw * ih
    if inner_area <= 0:
        return False
    return (inter_area / inner_area) >= containment_ratio


def suppress_nested_large_boxes(detections, containment_ratio=0.90):
    # detections: [(x,y,w,h,name,conf), ...]
    if not detections:
        return []
    dets = sorted(detections, key=lambda d: d[5], reverse=True)
    suppressed = [False] * len(dets)

    for i in range(len(dets)):
        if suppressed[i]:
            continue
        box_i = dets[i][:4]
        area_i = dets[i][2] * dets[i][3]
        for j in range(i + 1, len(dets)):
            if suppressed[j]:
                continue
            box_j = dets[j][:4]
            area_j = dets[j][2] * dets[j][3]
            if area_i > area_j and box_contains(box_i, box_j, containment_ratio):
                suppressed[i] = True
                break
            if area_j > area_i and box_contains(box_j, box_i, containment_ratio):
                suppressed[j] = True

    return [d for idx, d in enumerate(dets) if not suppressed[idx]]


def nms_logo_detections(detections, iou_threshold=0.45):
    if not detections:
        return []
    dets = sorted(detections, key=lambda d: d[5], reverse=True)
    kept = []
    for det in dets:
        box = det[:4]
        should_keep = True
        for kd in kept:
            if box_iou(box, kd[:4]) > iou_threshold:
                should_keep = False
                break
        if should_keep:
            kept.append(det)
    return kept


def filter_logo_detections(detections, frame_h, frame_w):
    if not detections:
        return []
    frame_area = frame_h * frame_w
    filtered = []
    for det in detections:
        x, y, w, h, name, conf = det
        area = w * h
        if area > frame_area * LOGO_MAX_BOX_AREA_RATIO:
            continue
        aspect = w / max(h, 1)
        if aspect < LOGO_MIN_ASPECT_RATIO or aspect > LOGO_MAX_ASPECT_RATIO:
            continue
        filtered.append(det)

    nested_filtered = suppress_nested_large_boxes(
        filtered, containment_ratio=LOGO_CONTAINMENT_RATIO
    )
    final = nms_logo_detections(
        nested_filtered, iou_threshold=LOGO_NMS_IOU_THRESHOLD
    )
    return final


def center_distance(box_a, box_b):
    ax, ay, aw, ah = box_a
    bx, by, bw, bh = box_b
    acx, acy = ax + aw / 2.0, ay + ah / 2.0
    bcx, bcy = bx + bw / 2.0, by + bh / 2.0
    return ((acx - bcx) ** 2 + (acy - bcy) ** 2) ** 0.5


def should_replace_track_text(old_text, new_text, old_conf, new_conf):
    old_norm = normalize_for_match(old_text)
    new_norm = normalize_for_match(new_text)

    if old_norm and new_norm:
        if old_norm == new_norm:
            return new_conf >= old_conf
        if old_norm in new_norm and len(new_norm) >= len(old_norm):
            return True
        if new_norm in old_norm:
            return False

    old_len = visible_text_len(old_text)
    new_len = visible_text_len(new_text)
    if new_len > old_len:
        return True
    return new_conf >= old_conf + 0.05


def find_best_track(active_tracks, det, frame_idx):
    x, y, w, h, _, _ = det
    best_idx = -1
    best_score = -1.0
    for idx, tr in enumerate(active_tracks):
        if frame_idx - tr["last_seen"] > TRACK_TTL_FRAMES:
            continue
        iou = box_iou((x, y, w, h), tr["bbox"])
        dist = center_distance((x, y, w, h), tr["bbox"])
        if iou < TRACK_IOU_MATCH and dist > TRACK_CENTER_DIST:
            continue
        score = iou + 0.001 * max(0.0, TRACK_CENTER_DIST - dist)
        if score > best_score:
            best_score = score
            best_idx = idx
    return best_idx


def suppress_track_substrings(active_tracks):
    if not active_tracks:
        return []

    tracks = sorted(
        active_tracks,
        key=lambda t: (visible_text_len(t["text"]), t["conf"], t["last_seen"]),
        reverse=True
    )
    kept = []
    for tr in tracks:
        t_norm = normalize_for_match(tr["text"])
        drop = False
        for k in kept:
            iou = box_iou(tr["bbox"], k["bbox"])
            if iou < TRACK_SUBSTRING_IOU_THRESHOLD:
                continue
            k_norm = normalize_for_match(k["text"])
            if t_norm and k_norm and t_norm != k_norm and t_norm in k_norm:
                drop = True
                break
        if not drop:
            kept.append(tr)
    return kept


def suppress_nearby_duplicate_tracks(active_tracks):
    # For very close tracks with the same text, keep only the higher-confidence one.
    if not active_tracks:
        return []
    tracks = sorted(active_tracks, key=lambda t: t["conf"], reverse=True)
    kept = []
    for tr in tracks:
        t_norm = normalize_for_match(tr["text"])
        drop = False
        for k in kept:
            if t_norm != normalize_for_match(k["text"]):
                continue
            iou = box_iou(tr["bbox"], k["bbox"])
            dist = center_distance(tr["bbox"], k["bbox"])
            if iou >= TRACK_DUP_IOU_THRESHOLD or dist <= TRACK_DUP_CENTER_DIST:
                drop = True
                break
        if not drop:
            kept.append(tr)
    return kept


def update_active_tracks(active_tracks, detections, frame_idx, next_track_id):
    dets = sorted(
        detections,
        key=lambda d: (visible_text_len(d[4]), d[5]),
        reverse=True
    )
    used_track_indices = set()

    for det in dets:
        x, y, w, h, text, conf = det

        # Keep realtime behavior but suppress single-char noise.
        if visible_text_len(text) < MIN_CHAR_SEQ_LEN_TO_SHOW:
            continue

        match_idx = find_best_track(active_tracks, det, frame_idx)
        if match_idx >= 0 and match_idx not in used_track_indices:
            tr = active_tracks[match_idx]
            if should_replace_track_text(tr["text"], text, tr["conf"], conf):
                tr["text"] = text
            tr["bbox"] = (x, y, w, h)
            tr["conf"] = max(tr["conf"] * 0.7, conf)
            tr["last_seen"] = frame_idx
            used_track_indices.add(match_idx)
            continue

        active_tracks.append({
            "id": next_track_id,
            "bbox": (x, y, w, h),
            "text": text,
            "conf": conf,
            "last_seen": frame_idx,
        })
        next_track_id += 1

    active_tracks = [t for t in active_tracks if frame_idx - t["last_seen"] <= TRACK_TTL_FRAMES]
    active_tracks = suppress_track_substrings(active_tracks)
    active_tracks = suppress_nearby_duplicate_tracks(active_tracks)
    return active_tracks, next_track_id


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


# ================= 2. Load Label Set =================
token_brand_names_fallback = sorted([
    d for d in os.listdir(MULTI_CLASS_DIR)
    if os.path.isdir(os.path.join(MULTI_CLASS_DIR, d))
])
logo_brand_names_fallback = sorted(
    [x for x in token_brand_names_fallback if x.lower().startswith("logo_")] + [NON_LOGO_CLASS_NAME]
)


# ================= 3. Load Models =================
print(f"[INFO] Loading models on {DEVICE}...")
detector = models.resnet18()
detector.fc = nn.Linear(detector.fc.in_features, 2)
detector.load_state_dict(torch.load(DETECTOR_PTH, map_location=DEVICE))
detector.to(DEVICE).eval()

# Token classifier (chars + logo tokens)
token_ckpt = torch.load(TOKEN_CLASSIFIER_PTH, map_location=DEVICE)
if isinstance(token_ckpt, dict) and "state_dict" in token_ckpt:
    token_state = token_ckpt["state_dict"]
    TOKEN_BRAND_NAMES = token_ckpt.get("classes", token_brand_names_fallback)
else:
    token_state = token_ckpt
    TOKEN_BRAND_NAMES = token_brand_names_fallback

token_classifier = models.resnet18()
token_classifier.fc = nn.Linear(token_classifier.fc.in_features, len(TOKEN_BRAND_NAMES))
token_classifier.load_state_dict(token_state)
token_classifier.to(DEVICE).eval()
print(f"[INFO] Token labels: {len(TOKEN_BRAND_NAMES)}")

# Logo verifier classifier (logo + non_logo)
logo_ckpt = torch.load(LOGO_CLASSIFIER_PTH, map_location=DEVICE)
if isinstance(logo_ckpt, dict) and "state_dict" in logo_ckpt:
    logo_state = logo_ckpt["state_dict"]
    LOGO_BRAND_NAMES = logo_ckpt.get("logo_classes", logo_brand_names_fallback)
else:
    logo_state = logo_ckpt
    LOGO_BRAND_NAMES = logo_brand_names_fallback

logo_classifier = models.resnet18()
logo_classifier.fc = nn.Linear(logo_classifier.fc.in_features, len(LOGO_BRAND_NAMES))
logo_classifier.load_state_dict(logo_state)
logo_classifier.to(DEVICE).eval()
print(f"[INFO] Logo verifier labels: {len(LOGO_BRAND_NAMES)}")

transform = transforms.Compose([
    transforms.Resize((224, 224)),
    transforms.ToTensor(),
    transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
])


# ================= 4. Main =================
cap = cv2.VideoCapture(VIDEO_PATH)
if not cap.isOpened():
    print(f"[ERROR] Cannot open video file: {VIDEO_PATH}")
    raise SystemExit(1)

fps = int(cap.get(cv2.CAP_PROP_FPS))
width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
out_width, out_height = width, height
if ROTATE_CODE in (cv2.ROTATE_90_CLOCKWISE, cv2.ROTATE_90_COUNTERCLOCKWISE):
    out_width, out_height = height, width

fourcc = cv2.VideoWriter_fourcc(*"mp4v")
out_video = cv2.VideoWriter(OUTPUT_VIDEO, fourcc, fps, (out_width, out_height))

frame_count = 0
active_tracks = []
next_track_id = 1
start_time = time.time()

print("[INFO] Start processing...")
while True:
    ret, frame = cap.read()
    if not ret:
        break

    if ROTATE_CODE is not None:
        frame = cv2.rotate(frame, ROTATE_CODE)

    frame_count += 1
    display_frame = frame.copy()

    if frame_count % FRAME_SKIP == 0:
        proposals = get_smart_proposals(frame)
        raw_char_predictions = []
        raw_logo_predictions = []

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

            if det_class.item() != 1 or det_conf.item() < DETECTOR_CONF_THRESHOLD:
                continue

            with torch.no_grad():
                token_out = token_classifier(input_tensor)
                token_prob = torch.nn.functional.softmax(token_out, dim=1)
                k_token = min(5, token_prob.shape[1])
                token_topk_vals, token_topk_idx = torch.topk(token_prob, k=k_token, dim=1)

            # best non-logo char candidate from top-k
            best_char_name = None
            best_char_conf = 0.0
            for cval, cidx in zip(token_topk_vals[0], token_topk_idx[0]):
                cand_conf = cval.item()
                cand_name = TOKEN_BRAND_NAMES[cidx.item()]
                if cand_name.lower().startswith("logo_"):
                    continue
                if cand_conf >= TOKEN_FALLBACK_CONF_THRESHOLD:
                    best_char_name = cand_name
                    best_char_conf = cand_conf
                    break

            top1_conf = token_topk_vals[0, 0].item()
            top1_name = TOKEN_BRAND_NAMES[token_topk_idx[0, 0].item()]

            final_name = None
            final_conf = 0.0

            if top1_name.lower().startswith("logo_"):
                # Logo candidate: run second-stage logo/non_logo verifier
                with torch.no_grad():
                    logo_out = logo_classifier(input_tensor)
                    logo_prob = torch.nn.functional.softmax(logo_out, dim=1)
                    k_logo = min(2, logo_prob.shape[1])
                    logo_topk_vals, logo_topk_idx = torch.topk(logo_prob, k=k_logo, dim=1)

                logo_conf = logo_topk_vals[0, 0].item()
                logo_name = LOGO_BRAND_NAMES[logo_topk_idx[0, 0].item()]
                logo_margin = logo_conf - (logo_topk_vals[0, 1].item() if logo_topk_vals.shape[1] > 1 else 0.0)

                if (
                    logo_name != NON_LOGO_CLASS_NAME
                    and logo_conf >= LOGO_CONF_THRESHOLD
                    and logo_conf >= DISPLAY_CONF_THRESHOLD
                    and logo_margin >= CLASS_MARGIN_THRESHOLD
                ):
                    final_name = logo_name
                    final_conf = logo_conf
                elif best_char_name is not None and best_char_conf >= TOKEN_FALLBACK_CONF_THRESHOLD:
                    # fallback to character candidate
                    final_name = best_char_name
                    final_conf = best_char_conf
            else:
                if top1_conf >= TOKEN_CONF_THRESHOLD:
                    final_name = top1_name
                    final_conf = top1_conf
                elif best_char_name is not None and best_char_conf >= TOKEN_FALLBACK_CONF_THRESHOLD:
                    final_name = best_char_name
                    final_conf = best_char_conf

            if final_name is None:
                continue

            det_item = (x, y, w, h, final_name, final_conf)
            if final_name.lower().startswith("logo_"):
                raw_logo_predictions.append(det_item)
            else:
                raw_char_predictions.append(det_item)

        filtered_logo_predictions = filter_logo_detections(
            raw_logo_predictions, frame.shape[0], frame.shape[1]
        )
        raw_predictions = filtered_logo_predictions + raw_char_predictions

        frame_boxes = post_process_and_merge_boxes(raw_predictions)
        active_tracks, next_track_id = update_active_tracks(
            active_tracks, frame_boxes, frame_count, next_track_id
        )
    else:
        active_tracks = [
            t for t in active_tracks
            if frame_count - t["last_seen"] <= TRACK_TTL_FRAMES
        ]

    for tr in active_tracks:
        x, y, w, h = tr["bbox"]
        display_text = tr["text"]
        conf = tr["conf"]
        if conf < FINAL_RENDER_CONF_THRESHOLD:
            continue
        cv2.rectangle(display_frame, (x, y), (x + w, y + h), (0, 255, 255), 3)
        text = f"{display_text} {conf:.2f}"
        (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, 0.8, 2)
        cv2.rectangle(display_frame, (x, y - th - 10), (x + tw, y), (0, 255, 255), -1)
        cv2.putText(display_frame, text, (x, y - 5), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 0, 0), 2)

    out_video.write(display_frame)

cap.release()
out_video.release()

elapsed = time.time() - start_time
avg_fps = frame_count / elapsed if elapsed > 0 else 0.0
print(f"[INFO] Done. Saved: {OUTPUT_VIDEO}")
print(f"[INFO] Total: {elapsed:.2f}s | Avg FPS: {avg_fps:.2f}")
