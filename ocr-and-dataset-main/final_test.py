import os
import re
import cv2
import torch
import torch.nn as nn
from torchvision import transforms, models
from PIL import Image
import time

# ================= 1. 全局配置区 =================
# 视频路径 (你要测试的 VR 视频)
VIDEO_PATH = r"G:\kejicompany\huichang_video\76eefd128de33c4f6ab3f5d85b97cf91.mp4"
# 结果视频保存路径 (可选)
OUTPUT_VIDEO = "huichang_test_result.mp4"
DEBUG_DRAW_CROPS = True
DEBUG_MAX_DRAW_PROPOSALS = 120
DEBUG_DRAW_CANDIDATE_SCORES = True

# 两个大模型路径
DETECTOR_PTH = r"G:\kejicompany\tracker\huichang_logo_detector_binary.pth"
CLASSIFIER_PTH = r"G:\kejicompany\tracker\huichang_logo_model_final.pth"

# 你的多分类数据集目录，用于自动获取品牌名称
MULTI_CLASS_DIR = r"G:\kejicompany\tracker\logo_dataset"

# 抽帧设置：每几帧处理一次？(5 帧约等于一秒处理 6 次，保证流畅度)
FRAME_SKIP = 8
HOLD_LAST_RESULT = False
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
# 优先识别画面上方区域（0~1，值越小越偏上）
TOP_REGION_RATIO = 1.0
# 裁剪框最小尺寸和面积阈值（过滤掉太小的框）
MIN_BOX_SIZE = 20
MIN_BOX_AREA_RATIO = 0.00012
MAX_CANDIDATE_AREA_RATIO = 0.035
MIN_CANDIDATE_ASPECT = 0.75
MAX_CANDIDATE_ASPECT = 5.0
EDGE_DENSITY_THRESHOLD = 0.025
BLUE_SIGN_MIN_AREA_RATIO = 0.006
BLUE_SIGN_MIN_TALL_ASPECT = 1.55
BLUE_SIGN_MAX_WIDTH_RATIO = 0.32
BLUE_SIGN_TOP_SCAN_RATIO = 0.36
#
DETECTOR_CONF_THRESHOLD = 0.65
DISPLAY_CONF_THRESHOLD = 0.80
CLASSIFIER_MARGIN_THRESHOLD = 0.15
FRAME_VOTE_MIN_COUNT = 2
SINGLE_CROP_CONF_THRESHOLD = 0.93
# 如果视频方向不对，设置旋转；不需要就设为 None
ROTATE_CODE = None
# =================================================

# ================= 2. 自动获取品牌列表 =================
BRAND_NAMES = sorted([d for d in os.listdir(MULTI_CLASS_DIR) 
                      if os.path.isdir(os.path.join(MULTI_CLASS_DIR, d))])
NUM_BRANDS = len(BRAND_NAMES)
print(f"✅ 成功读取 {NUM_BRANDS} 个品牌类别。")

# ================= 3. 加载双阶段大脑 =================
print(f"🧠 正在将模型加载至 {DEVICE}...")

# 加载模型A
detector = models.resnet18()
detector.fc = nn.Linear(detector.fc.in_features, 2)
detector.load_state_dict(torch.load(DETECTOR_PTH, map_location=DEVICE))
detector.to(DEVICE).eval()

# 加载模型B
classifier = models.resnet18()
classifier.fc = nn.Linear(classifier.fc.in_features, NUM_BRANDS)
classifier.load_state_dict(torch.load(CLASSIFIER_PTH, map_location=DEVICE))
classifier.to(DEVICE).eval()

# 统一的图像预处理 (必须和训练时一模一样)
transform = transforms.Compose([
    transforms.Resize((224, 224)),
    transforms.ToTensor(),
    transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
])

# ================= 4. 智能提框算法 (替代纯随机裁剪) =================
def get_smart_proposals(frame):
    """
    使用边缘检测抓取画面中有明显轮廓的物体，比随机瞎切快几十倍。
    """
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred, 50, 150)
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    proposals = []
    frame_h, frame_w = frame.shape[:2]
    
    min_area = int(frame_h * frame_w * MIN_BOX_AREA_RATIO)

    for cnt in contours:
        x, y, w, h = cv2.boundingRect(cnt)
        # 过滤掉太小(噪点)或太大(整面墙)的框
        if w >= MIN_BOX_SIZE and h >= MIN_BOX_SIZE and (w * h) >= min_area and w < frame_w * 0.5:
            proposals.append((x, y, w, h))

    if not proposals:
        return proposals

    # Add focused top crops for tall signs. Booth IDs are often at the top of
    # vertical guide boards; the contour may cover the whole board, which does
    # not match the classifier's training crops.
    focused = []
    for x, y, w, h in proposals:
        focused.append((x, y, w, h))
        if h > w * 1.2:
            top_h = max(MIN_BOX_SIZE, int(w * 0.75))
            top_h = min(top_h, h)
            focused.append((x, y, w, top_h))
    proposals = focused

    frame_area = frame_h * frame_w
    filtered = []
    for x, y, w, h in proposals:
        area = w * h
        aspect = w / max(h, 1)
        if area > frame_area * MAX_CANDIDATE_AREA_RATIO:
            continue
        if aspect < MIN_CANDIDATE_ASPECT or aspect > MAX_CANDIDATE_ASPECT:
            continue
        filtered.append((x, y, w, h))
    proposals = filtered

    # 优先使用上方区域的候选框，若没有则回退到全部
    cutoff_y = int(frame_h * TOP_REGION_RATIO)
    upper = [p for p in proposals if p[1] + (p[3] // 2) <= cutoff_y]
    return upper if upper else proposals


def get_classifier_crops(crop_img):
    h, w = crop_img.shape[:2]
    crops = [crop_img]

    # The detector often captures the whole vertical booth sign, while the
    # classifier was trained on the small booth-id area near the top.
    if h > w * 1.2:
        top_h = max(30, int(h * 0.38))
        top_h = min(top_h, h)
        crops.insert(0, crop_img[:top_h, :])

    return crops

# ================= 5. 视频流处理主循环 =================
# Strict blue-sign proposal logic. These definitions intentionally override the
# older contour-based functions above: no blue vertical sign means no proposal.
def enhance_contrast(image):
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    l_chan, a_chan, b_chan = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    l_chan = clahe.apply(l_chan)
    return cv2.cvtColor(cv2.merge([l_chan, a_chan, b_chan]), cv2.COLOR_LAB2BGR)


def get_blue_mask(image):
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    mask = cv2.inRange(hsv, (80, 70, 120), (130, 255, 255))
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel, iterations=2)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel, iterations=1)
    return mask


def get_blue_sign_boxes(frame):
    mask = get_blue_mask(frame)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    frame_h, frame_w = frame.shape[:2]
    frame_area = frame_h * frame_w
    sign_boxes = []

    def is_vertical_blue_component(cnt, x, y, w, h):
        if h / max(w, 1) >= BLUE_SIGN_MIN_TALL_ASPECT:
            return True
        rect = cv2.minAreaRect(cnt)
        rw, rh = rect[1]
        if rw <= 1 or rh <= 1:
            return False
        long_side = max(rw, rh)
        short_side = min(rw, rh)
        if long_side / max(short_side, 1) < BLUE_SIGN_MIN_TALL_ASPECT:
            return False
        angle = rect[2]
        if rw > rh:
            angle += 90
        return abs(angle) <= 25

    def add_box(x, y, w, h, cnt=None):
        area = w * h
        if area < frame_area * BLUE_SIGN_MIN_AREA_RATIO:
            return
        if w < MIN_BOX_SIZE or h < MIN_BOX_SIZE:
            return
        if w > frame_w * BLUE_SIGN_MAX_WIDTH_RATIO:
            return
        if cnt is not None and not is_vertical_blue_component(cnt, x, y, w, h):
            return
        if cnt is None and h / max(w, 1) < BLUE_SIGN_MIN_TALL_ASPECT:
            return
        sign_boxes.append((x, y, w, h))

    def split_vertical_blue_stripes(x, y, w, h):
        crop = mask[y:y + h, x:x + w]
        if crop.size == 0:
            return
        col_counts = (crop > 0).sum(axis=0)
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
        add_box(x, y, w, h, cnt)
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


def get_smart_proposals(frame):
    enhanced = enhance_contrast(frame)
    gray = cv2.cvtColor(enhanced, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred, 50, 150)

    proposals = []
    seen = set()
    frame_h, frame_w = frame.shape[:2]
    frame_area = frame_h * frame_w
    min_area = int(frame_area * MIN_BOX_AREA_RATIO)
    max_area = int(frame_area * MAX_CANDIDATE_AREA_RATIO)

    def append_if_good(box):
        x, y, w, h = box
        pad_x = 0
        pad_y = max(5, int(h * 0.16))
        x0 = max(0, x - pad_x)
        y0 = max(0, y - pad_y)
        x1 = min(frame_w, x + w + pad_x)
        y1 = min(frame_h, y + h + pad_y)
        x, y, w, h = x0, y0, x1 - x0, y1 - y0

        area = w * h
        aspect = w / max(h, 1)
        if w < MIN_BOX_SIZE or h < MIN_BOX_SIZE or area < min_area or area > max_area:
            return
        if aspect < MIN_CANDIDATE_ASPECT or aspect > MAX_CANDIDATE_ASPECT:
            return
        roi_edges = edges[y:y + h, x:x + w]
        edge_density = cv2.countNonZero(roi_edges) / float(area)
        if edge_density < EDGE_DENSITY_THRESHOLD * 0.5:
            return
        key = (x // 4, y // 4, w // 4, h // 4)
        if key in seen:
            return
        seen.add(key)
        proposals.append((x, y, w, h))

    def append_text_guided_crops(sign_x, sign_y, sign_w, sign_h):
        before_count = len(proposals)
        top_h = min(sign_h, max(int(sign_h * 0.22), int(sign_w * 1.25), MIN_BOX_SIZE * 2))
        top_roi = frame[sign_y:sign_y + top_h, sign_x:sign_x + sign_w]
        if top_roi.size == 0:
            return False

        hsv = cv2.cvtColor(top_roi, cv2.COLOR_BGR2HSV)
        white_mask = cv2.inRange(hsv, (0, 0, 105), (179, 130, 255))
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (max(5, sign_w // 6), 3))
        white_mask = cv2.morphologyEx(white_mask, cv2.MORPH_CLOSE, kernel, iterations=1)
        white_mask = cv2.dilate(white_mask, kernel, iterations=1)

        row_counts = (white_mask > 0).sum(axis=1)
        active = row_counts >= max(3, int(sign_w * 0.10))
        row_runs = []
        start = None
        for i, ok in enumerate(list(active) + [False]):
            if ok and start is None:
                start = i
            elif not ok and start is not None:
                end = i
                if end - start >= max(3, int(top_h * 0.04)) and start <= top_h * 0.72:
                    row_runs.append((start, end))
                start = None

        # Keep only the earliest text bands. The booth id is above the vertical
        # Chinese text, so later runs are more likely to be the wrong target.
        for y1, y2 in row_runs[:1]:
            band_h = y2 - y1
            center_y = sign_y + (y1 + y2) // 2
            crop_heights = [
                max(MIN_BOX_SIZE, int(sign_w * 0.60)),
                max(MIN_BOX_SIZE, int(sign_w * 0.80)),
                max(MIN_BOX_SIZE, int(sign_w * 1.00)),
            ]
            for crop_h in crop_heights:
                crop_h = min(crop_h, sign_h)
                for offset in (-int(crop_h * 0.08), 0, int(crop_h * 0.08)):
                    crop_y = center_y - crop_h // 2 + offset
                    crop_y = max(sign_y, min(crop_y, sign_y + sign_h - crop_h))
                    append_if_good((sign_x, crop_y, sign_w, crop_h))

        return len(proposals) > before_count

    for x, y, w, h in get_blue_sign_boxes(frame):
        guided_ok = append_text_guided_crops(x, y, w, h)

        # The booth id is near the top, but a uniform grid can cut letters in
        # half. Use overlapping full-width crops with several heights so at
        # least one crop contains the whole booth id.
        top_span = min(h, max(int(w * 1.10), MIN_BOX_SIZE * 3))
        crop_heights = [
            max(MIN_BOX_SIZE, int(w * 0.75)),
            max(MIN_BOX_SIZE, int(w * 0.95)),
            max(MIN_BOX_SIZE, int(w * 1.10)),
        ]

        # Anchor several crops at the very top of the sign. This is the most
        # important set for booth ids because the text is often close to the
        # upper edge and should not be clipped.
        for crop_h in crop_heights + [top_span]:
            crop_h = max(MIN_BOX_SIZE, min(crop_h, h))
            append_if_good((x, y, w, crop_h))

        for crop_h in crop_heights:
            crop_h = min(crop_h, h)
            offset_cap = int(h * (0.025 if guided_ok else 0.05))
            max_offset = max(0, min(top_span - crop_h, offset_cap))
            if max_offset == 0:
                offsets = [0]
            else:
                step = max(6, int(crop_h * 0.28))
                offsets = list(range(0, max_offset + 1, step))
                offsets.extend([max_offset // 2, max_offset])

            for offset in offsets:
                crop_y = y + offset
                if crop_y + crop_h <= y + h:
                    append_if_good((x, crop_y, w, crop_h))

        # A couple of deliberately larger top crops are useful when the booth id
        # is shifted down or slightly tilted.
        for crop_h in (int(top_span * 0.70), top_span):
            crop_h = max(MIN_BOX_SIZE, min(crop_h, h))
            append_if_good((x, y, w, crop_h))

    return sorted(proposals, key=lambda b: (b[2] * b[3], b[1], b[0]))


def get_classifier_crops(crop_img):
    h, w = crop_img.shape[:2]
    crops = [crop_img, enhance_contrast(crop_img)]
    for angle in (-7, 7):
        center = (w / 2, h / 2)
        matrix = cv2.getRotationMatrix2D(center, angle, 1.0)
        rotated = cv2.warpAffine(crop_img, matrix, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
        crops.append(rotated)
    return crops


def normalize_booth_text(text):
    clean = re.sub(r"[^A-Za-z0-9]", "", text).upper()
    match = re.search(r"([A-F])(\d{2})", clean)
    if not match:
        return None
    booth_id = f"{match.group(1)}{match.group(2)}"
    return booth_id if booth_id in BRAND_NAMES else None


OCR_READER = None
if os.environ.get("ENABLE_EASYOCR") == "1":
    try:
        import easyocr
        OCR_READER = easyocr.Reader(["en"], gpu=torch.cuda.is_available(), verbose=False)
        print(">>> EasyOCR enabled")
    except Exception as exc:
        print(f">>> EasyOCR unavailable, using classifier guard only: {exc}")
else:
    print(">>> EasyOCR disabled. Set ENABLE_EASYOCR=1 to enable it.")


def recognize_booth_by_ocr(crop_img):
    if OCR_READER is None:
        return None, 0.0
    candidates = []
    for cls_crop in get_classifier_crops(crop_img):
        rgb = cv2.cvtColor(cls_crop, cv2.COLOR_BGR2RGB)
        try:
            results = OCR_READER.readtext(rgb, detail=1, paragraph=False)
        except Exception:
            continue
        for _, text, conf in results:
            booth_id = normalize_booth_text(text)
            if booth_id:
                candidates.append((booth_id, float(conf)))
    if not candidates:
        return None, 0.0
    return max(candidates, key=lambda x: x[1])


cap = cv2.VideoCapture(VIDEO_PATH)
if not cap.isOpened():
    print(f"❌ 无法打开视频文件: {VIDEO_PATH}")
    exit()

# 获取视频属性用于保存
fps = int(cap.get(cv2.CAP_PROP_FPS))
width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
out_width, out_height = width, height
if ROTATE_CODE in (cv2.ROTATE_90_CLOCKWISE, cv2.ROTATE_90_COUNTERCLOCKWISE):
    out_width, out_height = height, width
fourcc = cv2.VideoWriter_fourcc(*'mp4v')
out_video = cv2.VideoWriter(OUTPUT_VIDEO, fourcc, fps, (out_width, out_height))

frame_count = 0
last_boxes = []  # 保留多个候选框
last_proposals = []
last_blue_regions = []
last_debug_candidates = []
start_time = time.time()
total_proposals = 0
total_detector_hits = 0
total_classifier_hits = 0

print("🎬 开始处理视频... (Ctrl+C 中断)")

while True:
    ret, frame = cap.read()
    if not ret:
        break
        
    if ROTATE_CODE is not None:
        frame = cv2.rotate(frame, ROTATE_CODE)

    frame_count += 1
    display_frame = frame.copy()
    process_this_frame = (frame_count % FRAME_SKIP == 0)
    
    # 【触发机制】：每逢 5 的倍数帧，启动 AI 进行深度计算
    if process_this_frame:
        last_boxes = []
        last_proposals = []
        last_blue_regions = []
        last_debug_candidates = []

        proposals = get_smart_proposals(frame)
        blue_regions = get_blue_sign_boxes(frame)
        total_proposals += len(proposals)
        current_boxes = []
        debug_candidates = []

        for (x, y, w, h) in proposals:
            crop_img = frame[y:y+h, x:x+w]
            if crop_img.size == 0:
                continue

            # 转为 Tensor 喂给模型
            pil_img = Image.fromarray(cv2.cvtColor(crop_img, cv2.COLOR_BGR2RGB))
            input_tensor = transform(pil_img).unsqueeze(0).to(DEVICE)

            with torch.no_grad():
                # 阶段 1：探测器
                det_out = detector(input_tensor)
                det_prob = torch.nn.functional.softmax(det_out, dim=1)
                det_conf, det_class = torch.max(det_prob, 1)

            # 假设索引 1 是 positive (依赖文件夹字母排序)
            det_conf_val = det_conf.item()
            debug_label = f"det0 {det_conf_val:.2f}"
            if det_class.item() == 1 and det_conf_val >= DETECTOR_CONF_THRESHOLD:
                total_detector_hits += 1
                ocr_name, ocr_conf = recognize_booth_by_ocr(crop_img)
                if ocr_name and ocr_conf >= 0.35:
                    current_boxes.append((x, y, w, h, ocr_name, ocr_conf))
                    debug_candidates.append((x, y, w, h, f"{ocr_name} ocr={ocr_conf:.2f}"))
                    total_classifier_hits += 1
                    continue
                with torch.no_grad():
                    # 阶段 2：分类器
                    cls_out = classifier(input_tensor)
                    cls_prob = torch.nn.functional.softmax(cls_out, dim=1)
                    cls_conf, cls_class = torch.max(cls_prob, 1)

                cls_conf_val = cls_conf.item()
                cls_class_idx = cls_class.item()
                cls_margin_val = 0.0
                top_vals, _ = torch.topk(cls_prob, k=2, dim=1)
                cls_margin_val = (top_vals[0, 0] - top_vals[0, 1]).item()
                for cls_crop in get_classifier_crops(crop_img):
                    pil_cls = Image.fromarray(cv2.cvtColor(cls_crop, cv2.COLOR_BGR2RGB))
                    cls_tensor = transform(pil_cls).unsqueeze(0).to(DEVICE)
                    with torch.no_grad():
                        cls_out_extra = classifier(cls_tensor)
                        cls_prob_extra = torch.nn.functional.softmax(cls_out_extra, dim=1)
                        cls_conf_extra, cls_class_extra = torch.max(cls_prob_extra, 1)
                        top_vals_extra, _ = torch.topk(cls_prob_extra, k=2, dim=1)
                        cls_margin_extra = (top_vals_extra[0, 0] - top_vals_extra[0, 1]).item()
                    if cls_conf_extra.item() > cls_conf_val:
                        cls_conf_val = cls_conf_extra.item()
                        cls_class_idx = cls_class_extra.item()
                        cls_margin_val = cls_margin_extra
                debug_label = f"{BRAND_NAMES[cls_class_idx]} c={cls_conf_val:.2f} d={det_conf_val:.2f} m={cls_margin_val:.2f}"
                if cls_conf_val >= DISPLAY_CONF_THRESHOLD and cls_margin_val >= CLASSIFIER_MARGIN_THRESHOLD:
                    brand_name = BRAND_NAMES[cls_class_idx]
                    current_boxes.append((x, y, w, h, brand_name, cls_conf_val))
                    total_classifier_hits += 1
            debug_candidates.append((x, y, w, h, debug_label))

        if current_boxes:
            vote_scores = {}
            vote_counts = {}
            best_box_by_label = {}
            for item in current_boxes:
                label = item[4]
                conf = item[5]
                vote_scores[label] = vote_scores.get(label, 0.0) + conf
                vote_counts[label] = vote_counts.get(label, 0) + 1
                if label not in best_box_by_label or conf > best_box_by_label[label][5]:
                    best_box_by_label[label] = item

            best_label = max(
                vote_scores,
                key=lambda label: (vote_counts[label], vote_scores[label], best_box_by_label[label][5]),
            )
            best_box = best_box_by_label[best_label]

            if vote_counts[best_label] >= FRAME_VOTE_MIN_COUNT or best_box[5] >= SINGLE_CROP_CONF_THRESHOLD:
                current_boxes = [best_box]
            else:
                current_boxes = []

        last_boxes = current_boxes
        last_proposals = proposals
        last_blue_regions = blue_regions
        last_debug_candidates = debug_candidates
    elif not HOLD_LAST_RESULT:
        last_boxes = []
        last_proposals = []
        last_blue_regions = []
        last_debug_candidates = []

    if DEBUG_DRAW_CROPS:
        for bx, by, bw, bh in last_blue_regions:
            cv2.rectangle(display_frame, (bx, by), (bx + bw, by + bh), (0, 180, 0), 2)
            cv2.putText(display_frame, "blue", (bx, max(18, by - 6)), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 180, 0), 2)

        for px, py, pw, ph in last_proposals[:DEBUG_MAX_DRAW_PROPOSALS]:
            cv2.rectangle(display_frame, (px, py), (px + pw, py + ph), (255, 0, 0), 1)

        if DEBUG_DRAW_CANDIDATE_SCORES:
            for px, py, pw, ph, label in last_debug_candidates[:DEBUG_MAX_DRAW_PROPOSALS]:
                cv2.putText(
                    display_frame,
                    label,
                    (px, max(16, py - 4)),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.42,
                    (255, 0, 0),
                    1,
                )

        cv2.putText(
            display_frame,
            f"blue={len(last_blue_regions)} crops={len(last_proposals)}",
            (12, 28),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.7,
            (255, 0, 0),
            2,
        )

    # 【画面渲染】：无论是 AI 刚刚算出来的，还是沿用前几帧的，统统画出来
    for (x, y, w, h, brand_name, conf) in last_boxes:
        cv2.rectangle(display_frame, (x, y), (x+w, y+h), (0, 255, 255), 3)
        
        # 画一个文字背景底色，让字看得更清楚
        text = f"{brand_name} {conf:.2f}"
        (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, 0.8, 2)
        cv2.rectangle(display_frame, (x, y-th-10), (x+tw, y), (0, 255, 255), -1)
        cv2.putText(display_frame, text, (x, y-5), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 0, 0), 2)
        
    # 仅保存，不显示（适配无 GUI 环境）
    out_video.write(display_frame)
    
    # 无 GUI 环境下用 Ctrl+C 终止

cap.release()
out_video.release()
elapsed = time.time() - start_time
avg_fps = frame_count / elapsed if elapsed > 0 else 0.0
print(f"[DEBUG] proposals={total_proposals} detector_hits={total_detector_hits} classifier_hits={total_classifier_hits}")
print(f"✅ 处理完毕！结果已保存至 {OUTPUT_VIDEO}")
print(f"⏱️ 总耗时: {elapsed:.2f} 秒 | 平均处理帧率: {avg_fps:.2f} FPS")
