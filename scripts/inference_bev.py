"""SegFormer-ADE20K + Depth Anything V2 + BEV (俯视雷达图) 批处理。

流水线：
  原始 BGR 帧
    ├─ SegFormer-ADE20K  → 19+ 可行类 + person + 其余障碍
    ├─ Depth Anything V2 → 单目深度 (相对/近似 metric)
    └─ 反投影到 ego 3D + 正交俯视 → BEV 雷达图小窗
  → 上色叠加 + 右下角合成 BEV → 输出 MP4

输入/输出沿用 inference_segformer.py 的目录结构，输出后缀 _bev[_sample]。
"""
import os

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")

import cv2
import torch
import numpy as np
from PIL import Image
from transformers import (
    SegformerImageProcessor, SegformerForSemanticSegmentation,
    AutoImageProcessor, AutoModelForDepthEstimation,
)

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
USE_FP16 = True
HERE = os.path.dirname(os.path.abspath(__file__))

# ---- 分割模型 (ADE20K) ---------------------------------------------------
# 双模型动态切换：明亮帧用 B2（更快、明亮场景已够准），暗帧用 B4（暗光鲁棒）
SEG_PATH_B2 = os.path.join(HERE, "..", "models", "segformer-b2-ade20k")
SEG_PATH_B4 = os.path.join(HERE, "..", "models", "segformer-b4-ade20k")
SEG_LONG_SIDE = 720          # 分割输入长边
# Lab.L 均值 < 阈值时切 B4
MODEL_SWITCH_L_THRESHOLD = 125

# ADE20K walkable / person 映射
WALKABLE_IDS = {3, 6, 9, 11, 13, 28, 29, 46, 52, 53, 54, 59, 96}
PERSON_IDS   = {12}

# 类别 → 单字节类型的查表 (0=obstacle, 1=walkable, 2=person)
# 比 np.isin 在 4K 图上快 ~30x
_CLS_LUT = np.zeros(256, dtype=np.uint8)
for _c in WALKABLE_IDS: _CLS_LUT[_c] = 1
for _c in PERSON_IDS:   _CLS_LUT[_c] = 2


# ---- BEV 时序状态（每个视频开始时 reset） -----------------------------
_bev_votes_ema = None
_person_history = []   # 每元素是上一帧 markers 列表 [(bx, bz), ...]

def reset_bev_state():
    """每个视频开始处理前调用，清空 EMA 累积。"""
    global _bev_votes_ema, _person_history
    _bev_votes_ema = None
    _person_history = []

# 显示用颜色 (BGR) 与 alpha
COLOR_WALKABLE = (0, 255, 0)
COLOR_PERSON   = (0, 255, 255)
COLOR_OBSTACLE = (0, 0, 255)
ALPHA_WALKABLE = 0.35
ALPHA_OBSTACLE = 0.20
ALPHA_PERSON   = 0.55

# ---- 深度模型 (Depth Anything V2 Small) ---------------------------------
DEPTH_PATH = os.path.join(HERE, "..", "models", "depth-anything-v2-small")

# ---- BEV 参数 -----------------------------------------------------------
BEV_SIZE        = 280        # BEV 小窗边长（像素，正方形）
BEV_RANGE_Z_M   = 12.0       # 前方深度范围（米）— 扩大以显示远处行人
BEV_RANGE_X_M   = 7.0        # 左右横向范围（米，±）
BEV_MARGIN      = 24         # BEV 距画面边距
BEV_BG          = (30, 30, 30)
BEV_GRID_COLOR  = (60, 60, 60)
# BEV 反投影前对 pred / depth 做下采样，加速 numpy 计算
BEV_DOWNSAMPLE  = 2          # 4→2，提高 BEV 点密度，避免稀疏空洞
# 相机内参近似（无标定数据时使用），用水平 FOV 估算
CAM_FOV_H_DEG   = 80.0       # 用户实测视角约 80°
# 深度映射：把 disparity 线性映射到 [DEPTH_NEAR_M, DEPTH_FAR_M] 米
DEPTH_NEAR_M    = 0.5
DEPTH_FAR_M     = 20.0       # 扩大映射上限以匹配 BEV 12m 范围
# 只保留相机下方一定 Y 高度的点（避免天花板/墙顶进 BEV，可调）
KEEP_BELOW_Y_M  = -0.3
# 同一格内三类点投票权重：>1 让稀疏的 walkable / person 也能压过密集 obstacle
VOTE_W_WALKABLE = 2.0
VOTE_W_OBSTACLE = 1.0
VOTE_W_PERSON   = 3.0
# 形态学闭运算 kernel：把稀疏的 walkable 像素连成片，通道更明显
WALKABLE_CLOSE_KSIZE = 5
# 时序平滑：对 BEV votes 做 EMA，减少帧间跳动
BEV_EMA_ALPHA = 0.35          # 新帧权重，越小越平滑（取值范围 0.1~0.5）
# Person 圆圈位置平滑：保留近 N 帧的检测，做位置中位数
PERSON_HISTORY_LEN = 5

# ---- 暗光增强（仅作用于分割 / 深度模型输入，不污染显示帧） -----------
# CLAHE 局部对比度均衡。在楼梯间、暗商场等场景明显改善地面分割。
CLAHE_ENABLED = True
CLAHE_AUTO_L_THRESHOLD = 150  # 帧 Lab.L 均值 < 此阈值才启用（明亮户外不动）
CLAHE_CLIP_LIMIT = 4.0
CLAHE_TILE_GRID  = (8, 8)
_clahe = cv2.createCLAHE(clipLimit=CLAHE_CLIP_LIMIT, tileGridSize=CLAHE_TILE_GRID)


def maybe_enhance_for_model(frame_bgr):
    """对偏暗的帧做 CLAHE。返回增强帧（或原帧）。

    仅供推理使用；显示用的 overlay 始终用未增强的原帧，避免视觉过曝失真。
    """
    if not CLAHE_ENABLED:
        return frame_bgr
    lab = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    if float(l.mean()) >= CLAHE_AUTO_L_THRESHOLD:
        return frame_bgr
    l_eq = _clahe.apply(l)
    return cv2.cvtColor(cv2.merge([l_eq, a, b]), cv2.COLOR_LAB2BGR)

# ---- 批处理配置 ---------------------------------------------------------
INPUT_ROOT  = r"H:\SHmvBJ\ARHelmetData\04152024\360"
OUTPUT_ROOT = r"H:\SHmvBJ\ARHelmetData\04152024\360\output"
# INPUT_ROOT  = r"I:"
# OUTPUT_ROOT = r"I:\成品视频\output"
SUBDIRS = ["Front"]    # 全跑 sub1/2/3 所有视频；SKIP_EXISTING 跳过已完成
MAX_VIDEOS_PER_SUBDIR = None # 不限制
SHOW_PREVIEW = False
SKIP_EXISTING = True
# 输入分辨率超出此长边时按比例缩放（4K → 1080p）
OUTPUT_MAX_LONG_SIDE = 1920
SAMPLE_MODE = False          # 完整视频，不再抽样
SAMPLE_SECONDS = 30
OUTPUT_SUFFIX        = "_bev"
SAMPLE_OUTPUT_SUFFIX = "_bev_sample_v6"   # v6: B2/B4 按亮度动态切换 + CLAHE


# =====================================================================
# 模型加载
# =====================================================================
print(f"[Init] Seg B2 -> {SEG_PATH_B2}")
seg_proc_b2  = SegformerImageProcessor.from_pretrained(SEG_PATH_B2)
seg_model_b2 = SegformerForSemanticSegmentation.from_pretrained(SEG_PATH_B2).to(DEVICE).eval()

print(f"[Init] Seg B4 -> {SEG_PATH_B4}")
seg_proc_b4  = SegformerImageProcessor.from_pretrained(SEG_PATH_B4)
seg_model_b4 = SegformerForSemanticSegmentation.from_pretrained(SEG_PATH_B4).to(DEVICE).eval()

print(f"[Init] Depth Anything V2 -> {DEPTH_PATH}")
depth_proc = AutoImageProcessor.from_pretrained(DEPTH_PATH)
depth_model = AutoModelForDepthEstimation.from_pretrained(DEPTH_PATH).to(DEVICE).eval()

if USE_FP16 and DEVICE == "cuda":
    seg_model_b2 = seg_model_b2.half()
    seg_model_b4 = seg_model_b4.half()
    depth_model  = depth_model.half()
print(f"[Init] OK, device={DEVICE}, fp16={USE_FP16 and DEVICE=='cuda'}, "
      f"L_switch={MODEL_SWITCH_L_THRESHOLD}")


# =====================================================================
# 推理：分割
# =====================================================================
def _pick_seg_model(frame_bgr):
    L_mean = float(cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2LAB)[..., 0].mean())
#  `  `    """根据帧亮度选择 B2 / B4。返回 (processor, model, used_label)。"""
    if L_mean < MODEL_SWITCH_L_THRESHOLD:
        return seg_proc_b4, seg_model_b4, "B4"
    return seg_proc_b2, seg_model_b2, "B2"


@torch.no_grad()
def segment_frame(frame_bgr):
    proc, model, _ = _pick_seg_model(frame_bgr)
    frame_in = maybe_enhance_for_model(frame_bgr)
    h, w = frame_in.shape[:2]
    rgb = cv2.cvtColor(frame_in, cv2.COLOR_BGR2RGB)
    scale = SEG_LONG_SIDE / max(h, w)
    if scale < 1.0:
        rgb_s = cv2.resize(rgb,   (int(round(w * scale)), int(round(h * scale))),
                           interpolation=cv2.INTER_LINEAR)
    else:
        rgb_s = rgb
    inputs = proc(images=Image.fromarray(rgb_s), return_tensors="pt").to(DEVICE)
    if USE_FP16 and DEVICE == "cuda":
        inputs = {k: (v.half() if v.dtype == torch.float32 else v) for k, v in inputs.items()}
    logits = model(**inputs).logits
    up = torch.nn.functional.interpolate(logits, size=(h, w),
                                         mode="bilinear", align_corners=False)
    return up.argmax(dim=1)[0].cpu().numpy().astype(np.int32)


# =====================================================================
# 推理：深度
# =====================================================================
@torch.no_grad()
def estimate_depth(frame_bgr):
    """返回与原帧同分辨率的深度（值大=近，值小=远；近似 disparity 量纲）。"""
    frame_in = maybe_enhance_for_model(frame_bgr)
    h, w = frame_in.shape[:2]
    rgb = cv2.cvtColor(frame_in, cv2.COLOR_BGR2RGB)
    inputs = depth_proc(images=Image.fromarray(rgb), return_tensors="pt").to(DEVICE)
    if USE_FP16 and DEVICE == "cuda":
        inputs = {k: (v.half() if v.dtype == torch.float32 else v) for k, v in inputs.items()}
    out = depth_model(**inputs).predicted_depth   # (1, Hd, Wd)
    up = torch.nn.functional.interpolate(out.unsqueeze(1), size=(h, w),
                                         mode="bilinear", align_corners=False)
    return up[0, 0].float().cpu().numpy()


# =====================================================================
# 上色叠加
# =====================================================================
def colorize(frame_bgr, pred_mask):
    """整图一次性 uint16 算术，避免 boolean indexing 的副本与 int64 索引数组。

    内存占用 ~3-4x 低于原版（float32 临时副本 → uint16 中间数组）。
    """
    types = _CLS_LUT[pred_mask.astype(np.uint8)]   # (H,W) uint8: 0/1/2

    # 颜色与 alpha 查表，整图一次写入（无 boolean-mask 副本）
    color_lut = np.array(
        [COLOR_OBSTACLE, COLOR_WALKABLE, COLOR_PERSON], dtype=np.uint8
    )                                                            # (3, 3)
    alpha_lut = np.array(
        [int(round(ALPHA_OBSTACLE * 255)),
         int(round(ALPHA_WALKABLE * 255)),
         int(round(ALPHA_PERSON   * 255))], dtype=np.uint16
    )                                                            # (3,)
    color_map = color_lut[types]                                 # (H,W,3) uint8
    alpha_map = alpha_lut[types][..., None]                      # (H,W,1) uint16
    inv = (255 - alpha_map)                                      # uint16

    # out = (frame*(255-a) + color*a) // 255  全 uint16，写回 uint8
    out16 = frame_bgr.astype(np.uint16) * inv
    out16 += color_map.astype(np.uint16) * alpha_map
    out16 //= 255
    return out16.astype(np.uint8)


# =====================================================================
# BEV 反投影 + 着色
# =====================================================================
# def _depth_to_metric(disp):
#     """把 Depth Anything 输出（值越大越近）映射到 [NEAR, FAR] 米。

#     用 p5/p95 而非 min/max 作归一化锚点：
#       - min(disp) 经常被天空 / 镜头反光等极端 outlier 占据，会把"中等近"的
#         前景（如远处行人）挤到远端 → BEV 上行人 Z 推到 14m+ 被过滤。
#       - p5/p95 给出稳健的"远端/近端"参考。clip 让超出范围的极端值
#         归位到 NEAR 或 FAR。
#     """
#     p5, p95 = np.percentile(disp, [5, 95])
#     if p95 - p5 < 1e-6:
#         return np.full_like(disp, (DEPTH_NEAR_M + DEPTH_FAR_M) / 2.0, dtype=np.float32)
#     norm = np.clip((disp - p5) / (p95 - p5), 0.0, 1.0)
#     return (DEPTH_NEAR_M + (1.0 - norm) * (DEPTH_FAR_M - DEPTH_NEAR_M)).astype(np.float32)


# def _make_bev_background():
#     bg = np.full((BEV_SIZE, BEV_SIZE, 3), BEV_BG, dtype=np.uint8)
#     # 同心圆距离参考（每 2m 一圈，直至 BEV_RANGE_Z_M）
#     cx, cy = BEV_SIZE // 2, BEV_SIZE - 5
#     r_m = 2.0
#     while r_m <= BEV_RANGE_Z_M:
#         r_px = int(round(r_m / BEV_RANGE_Z_M * BEV_SIZE))
#         cv2.circle(bg, (cx, cy), r_px, BEV_GRID_COLOR, 1)
#         r_m += 2.0
#     # 前向 / 左右轴
#     cv2.line(bg, (cx, cy), (cx, 0), BEV_GRID_COLOR, 1)
#     cv2.line(bg, (0, cy), (BEV_SIZE, cy), BEV_GRID_COLOR, 1)
#     # 自身位置（白点）
#     cv2.circle(bg, (cx, cy), 4, (255, 255, 255), -1)
#     return bg


# _BEV_BG = _make_bev_background()


# def compute_bev(pred_mask, depth_disp):
#     """返回 (BEV_SIZE, BEV_SIZE, 3) BGR 雷达图。

#     投影策略：
#       1. 反投影到 (X, Y, Z) ego 系
#       2. 过滤越界 / 天花板 / 太近 Z
#       3. 每个 BEV bin 累计三类（walk / obstacle / person）加权投票得票
#       4. argmax 决定该格颜色
#       5. 对 walkable 区域做闭运算，让通道连成片
#     """
#     if BEV_DOWNSAMPLE > 1:
#         depth_disp = depth_disp[::BEV_DOWNSAMPLE, ::BEV_DOWNSAMPLE]
#         pred_mask  = pred_mask[::BEV_DOWNSAMPLE, ::BEV_DOWNSAMPLE]

#     h, w = depth_disp.shape
#     fx = w / (2.0 * np.tan(np.deg2rad(CAM_FOV_H_DEG) / 2.0))
#     fy = fx
#     cx, cy = w / 2.0, h / 2.0

#     Z = _depth_to_metric(depth_disp)
#     us = np.arange(w, dtype=np.float32)
#     vs = np.arange(h, dtype=np.float32)
#     uu, vv = np.meshgrid(us, vs)
#     X = (uu - cx) * Z / fx
#     Y = (vv - cy) * Z / fy

#     types = _CLS_LUT[pred_mask.astype(np.uint8)]  # 0=obst, 1=walk, 2=person

#     bev_x = ((X + BEV_RANGE_X_M) / (2.0 * BEV_RANGE_X_M) * BEV_SIZE).astype(np.int32)
#     bev_z = (BEV_SIZE - 1 - (Z / BEV_RANGE_Z_M) * BEV_SIZE).astype(np.int32)
#     valid = (
#         (bev_x >= 0) & (bev_x < BEV_SIZE) &
#         (bev_z >= 0) & (bev_z < BEV_SIZE) &
#         (Z > 0.1) & (Y > KEEP_BELOW_Y_M)
#     )

#     flat_x = bev_x[valid]
#     flat_z = bev_z[valid]
#     flat_t = types[valid]

#     # 三层独立得票（加权）
#     votes_walk     = np.zeros((BEV_SIZE, BEV_SIZE), dtype=np.float32)
#     votes_obstacle = np.zeros((BEV_SIZE, BEV_SIZE), dtype=np.float32)
#     votes_person   = np.zeros((BEV_SIZE, BEV_SIZE), dtype=np.float32)

#     m_walk     = flat_t == 1
#     m_obstacle = flat_t == 0
#     m_person   = flat_t == 2
#     if m_walk.any():
#         np.add.at(votes_walk,     (flat_z[m_walk],     flat_x[m_walk]),     VOTE_W_WALKABLE)
#     if m_obstacle.any():
#         np.add.at(votes_obstacle, (flat_z[m_obstacle], flat_x[m_obstacle]), VOTE_W_OBSTACLE)
#     if m_person.any():
#         np.add.at(votes_person,   (flat_z[m_person],   flat_x[m_person]),   VOTE_W_PERSON)

#     # 时序 EMA：让 BEV 在帧间稳定，避免跳动
#     global _bev_votes_ema
#     current = np.stack([votes_walk, votes_obstacle, votes_person], axis=-1)
#     if _bev_votes_ema is None or _bev_votes_ema.shape != current.shape:
#         _bev_votes_ema = current
#     else:
#         _bev_votes_ema = (BEV_EMA_ALPHA * current
#                           + (1.0 - BEV_EMA_ALPHA) * _bev_votes_ema)
#     smoothed = _bev_votes_ema

#     has_any = smoothed.sum(axis=-1) > 0.5    # 阈值剔除噪声尾巴
#     main = smoothed.argmax(axis=-1).astype(np.uint8)   # 0/1/2

#     # 形态学闭运算让通道连成片（先 dilate 后 erode）
#     if WALKABLE_CLOSE_KSIZE >= 3:
#         walk_mask = ((main == 0) & has_any).astype(np.uint8)
#         k = np.ones((WALKABLE_CLOSE_KSIZE, WALKABLE_CLOSE_KSIZE), np.uint8)
#         walk_mask = cv2.morphologyEx(walk_mask, cv2.MORPH_CLOSE, k)
#         # 闭运算扩展出的格子也算 walkable（但不覆盖已存在的 person）
#         gained = (walk_mask > 0) & (main != 2)
#         main = np.where(gained, 0, main)
#         has_any = has_any | gained

#     bev = _BEV_BG.copy()
#     palette = np.array([COLOR_WALKABLE, COLOR_OBSTACLE, COLOR_PERSON], dtype=np.uint8)
#     color_layer = palette[main]
#     bev[has_any] = color_layer[has_any]

#     # 行人单独标记：用 connected components 找每个 person 实例，
#     # 用区域深度中位定位，画明显黄圈（不依赖 grid 投票，绝不会被淹没）
#     _draw_person_markers(bev, pred_mask, depth_disp)

#     cx_b, cy_b = BEV_SIZE // 2, BEV_SIZE - 5
#     cv2.circle(bev, (cx_b, cy_b), 4, (255, 255, 255), -1)
#     cv2.putText(bev, f"BEV ({BEV_RANGE_Z_M:.0f}m fwd)", (8, 18),
#                 cv2.FONT_HERSHEY_SIMPLEX, 0.45, (200, 200, 200), 1, cv2.LINE_AA)
#     return bev


# def _draw_person_markers(bev, pred_mask, depth_disp,
#                          min_area_px=50, marker_radius=7):
#     """在 BEV 上为每个独立 person 连通块画显眼黄圈。

#     时序平滑：把当前帧检测的 markers 与历史 N 帧合并，用 (bx, bz)
#     空间聚类后取每簇中位数，输出稳定位置。
#     """
#     _, w = pred_mask.shape
#     person_mask = np.isin(pred_mask, list(PERSON_IDS)).astype(np.uint8)

#     current = []
#     if person_mask.any():
#         num, labels, stats, centroids = cv2.connectedComponentsWithStats(person_mask, connectivity=8)
#         if num > 1:
#             fx = w / (2.0 * np.tan(np.deg2rad(CAM_FOV_H_DEG) / 2.0))
#             cx = w / 2.0
#             Z = _depth_to_metric(depth_disp)
#             for i in range(1, num):
#                 if stats[i, cv2.CC_STAT_AREA] < min_area_px:
#                     continue
#                 cu = centroids[i, 0]
#                 z_region = Z[labels == i]
#                 if z_region.size == 0:
#                     continue
#                 z_med = float(np.median(z_region))
#                 if z_med < 0.1 or z_med > BEV_RANGE_Z_M:
#                     continue
#                 X = (cu - cx) * z_med / fx
#                 if abs(X) > BEV_RANGE_X_M:
#                     continue
#                 bx = int((X + BEV_RANGE_X_M) / (2.0 * BEV_RANGE_X_M) * BEV_SIZE)
#                 bz = int(BEV_SIZE - 1 - z_med / BEV_RANGE_Z_M * BEV_SIZE)
#                 current.append((bx, bz))

#     # 维护时序历史；汇总最近 N 帧的 markers 后做空间聚类
#     global _person_history
#     _person_history.append(current)
#     if len(_person_history) > PERSON_HISTORY_LEN:
#         _person_history = _person_history[-PERSON_HISTORY_LEN:]

#     all_pts = [p for frame in _person_history for p in frame]
#     if not all_pts:
#         return

#     # 简单聚类：以马氏距离 < cluster_r 像素的点合并，输出每簇中位数
#     cluster_r = 18      # ≈1.5m 在 BEV 12m/280px 比例下
#     used = [False] * len(all_pts)
#     pts_arr = np.array(all_pts, dtype=np.float32)
#     for i in range(len(pts_arr)):
#         if used[i]:
#             continue
#         d = np.linalg.norm(pts_arr - pts_arr[i], axis=1)
#         idx = np.where(d < cluster_r)[0]
#         # 该簇至少要在历史多帧中出现（避免单帧闪现）
#         if len(idx) < 2 and len(_person_history) >= 2:
#             for j in idx: used[j] = True
#             continue
#         cluster_pts = pts_arr[idx]
#         bx, bz = np.median(cluster_pts, axis=0).astype(int)
#         cv2.circle(bev, (int(bx), int(bz)), marker_radius + 2, (0, 0, 0), -1)
#         cv2.circle(bev, (int(bx), int(bz)), marker_radius,     COLOR_PERSON, -1)
#         for j in idx:
#             used[j] = True


# def composite_bev(overlay_frame, bev_img):
#     """把 BEV 小窗合成到原画面右下角，外加白色边框。"""
#     h, w = overlay_frame.shape[:2]
#     bh, bw = bev_img.shape[:2]
#     x1 = w - bw - BEV_MARGIN
#     y1 = h - bh - BEV_MARGIN
#     overlay_frame[y1:y1 + bh, x1:x1 + bw] = bev_img
#     cv2.rectangle(overlay_frame, (x1 - 1, y1 - 1), (x1 + bw, y1 + bh),
#                   (255, 255, 255), 1)
#     return overlay_frame


# =====================================================================
# 视频遍历 (与 inference_segformer.py 同款逻辑)
# =====================================================================
def _annotate_label(frame, text):
    annotated = frame.copy()
    cv2.rectangle(annotated, (0, 0), (520, 70), (0, 0, 0), -1)
    cv2.putText(annotated, text, (15, 50),
                cv2.FONT_HERSHEY_SIMPLEX, 1.4, (0, 255, 255), 3)
    return annotated


def _process_range(cap, writer, start_frame, end_frame, label=None, progress_prefix="",
                   resize_to=None):
    cap.set(cv2.CAP_PROP_POS_FRAMES, start_frame)
    total = max(0, end_frame - start_frame)
    user_quit = False
    for i in range(total):
        ret, frame = cap.read()
        if not ret:
            break
        if resize_to is not None:
            frame = cv2.resize(frame, resize_to, interpolation=cv2.INTER_AREA)
        if (i + 1) % 50 == 0:
            print(f"  {progress_prefix}处理第 {i+1}/{total} 帧")

        pred = segment_frame(frame)
        depth = estimate_depth(frame)
        overlay = colorize(frame, pred)
        # bev = compute_bev(pred, depth)
        # composite_bev(overlay, bev)
        if label and i < 12:
            overlay = _annotate_label(overlay, label)

        writer.write(overlay)

        if SHOW_PREVIEW:
            cv2.namedWindow("BEV", cv2.WINDOW_NORMAL)
            cv2.resizeWindow("BEV", 960, 540)
            cv2.imshow("BEV", overlay)
            if (cv2.waitKey(1) & 0xFF) == ord('q'):
                print("  用户按q退出")
                user_quit = True
                break
    return user_quit


def process_video(input_path, output_path):
    reset_bev_state()    # 清空跨视频的时序状态
    cap = cv2.VideoCapture(input_path)
    if not cap.isOpened():
        print(f"  Error: 无法打开视频文件 {input_path}")
        return True

    fps = cap.get(cv2.CAP_PROP_FPS) or 24.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    # 按 OUTPUT_MAX_LONG_SIDE 等比降采样
    resize_to = None
    if max(width, height) > OUTPUT_MAX_LONG_SIDE:
        scale = OUTPUT_MAX_LONG_SIDE / max(width, height)
        new_w = int(round(width * scale) / 2) * 2  # 双数对 mp4v 友好
        new_h = int(round(height * scale) / 2) * 2
        resize_to = (new_w, new_h)
        out_w, out_h = new_w, new_h
    else:
        out_w, out_h = width, height

    print(f"  帧率: {fps}, 输入: {width}x{height} → 输出: {out_w}x{out_h}, 总帧数: {total_frames}")
    print(f"  输出: {output_path}")

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    writer = cv2.VideoWriter(output_path, fourcc, fps, (out_w, out_h))

    user_quit = False
    if SAMPLE_MODE:
        seg_len = int(round(SAMPLE_SECONDS * fps))
        seg_len = min(seg_len, max(1, total_frames // 3))
        ranges = [
            ("BEGIN", 0, seg_len),
            ("MID",   max(0, total_frames // 2 - seg_len // 2),
                       min(total_frames, total_frames // 2 - seg_len // 2 + seg_len)),
            ("END",   max(0, total_frames - seg_len), total_frames),
        ]
        for label, s, e in ranges:
            print(f"  [{label}] 帧区间 [{s}, {e})  共 {e - s} 帧")
            reset_bev_state()   # 跨段画面跳变，重置时序累积
            if _process_range(cap, writer, s, e, label=label,
                              progress_prefix=f"{label} ", resize_to=resize_to):
                user_quit = True
                break
    else:
        user_quit = _process_range(cap, writer, 0, total_frames, label=None,
                                   resize_to=resize_to)

    cap.release()
    writer.release()
    if SHOW_PREVIEW:
        cv2.destroyAllWindows()
    print(f"  完成: {output_path}")
    return not user_quit


def collect_videos(root, subdirs):
    tasks = []
    if subdirs == []:
        return [(root, OUTPUT_ROOT)]
    for sub in subdirs:
        in_dir = os.path.join(root, sub)
        if not os.path.isdir(in_dir):
            print(f"跳过：输入目录不存在 {in_dir}")
            continue
        files = sorted(
            f for f in os.listdir(in_dir)
            if f.lower().endswith(".mp4")
            and not f.startswith("._")
            and os.path.isfile(os.path.join(in_dir, f))
        )
        if MAX_VIDEOS_PER_SUBDIR is not None:
            files = files[:MAX_VIDEOS_PER_SUBDIR]
        suffix = SAMPLE_OUTPUT_SUFFIX if SAMPLE_MODE else OUTPUT_SUFFIX
        for fname in files:
            in_path = os.path.join(in_dir, fname)
            stem, _ = os.path.splitext(fname)
            out_path = os.path.join(OUTPUT_ROOT, sub, f"{stem}{suffix}.mp4")
            tasks.append((in_path, out_path))
    return tasks


def main():
    tasks = collect_videos(INPUT_ROOT, SUBDIRS)
    if not tasks:
        print("未找到任何 MP4 文件。")
        return
    print(f"共发现 {len(tasks)} 个待处理视频。")
    for idx, (in_path, out_path) in enumerate(tasks, 1):
        print(f"\n[{idx}/{len(tasks)}] {in_path}")
        if SKIP_EXISTING and os.path.exists(out_path):
            print(f"  已存在，跳过: {out_path}")
            continue
        proceed = process_video(in_path, out_path)
        if not proceed:
            print("用户中断，停止后续处理。")
            break
    print("\n全部任务结束。")


if __name__ == "__main__":
    main()
