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
_persist_obst = None       # v5.1: 障碍粘性 mask (与 EMA 同尺寸, pose-warp)
_person_history = []       # 保留兼容（不再使用）
_person_tracks = []        # list of {"id","bx","bz","age","misses"}
_next_person_id = 0

# ---- v8: World-frame occupancy grid state -----------------------------
# 相机第一帧位置 = grid 中心。整个 session 的观测在世界系累积。
WORLD_GRID_CELL_M = 0.10               # 10 cm per cell
WORLD_GRID_SIZE   = 600                # 60m × 60m 覆盖单视频通常的走动范围
WORLD_GRID_ORIGIN = WORLD_GRID_SIZE // 2

L_OCC_UPDATE_HIT  = 0.85               # 障碍观测每次 log-odds 增量
L_FREE_UPDATE_HIT = 0.35               # 空闲观测每次 log-odds 减量
L_MIN, L_MAX      = -6.0, 6.0

SEM_HIT_STEP      = 1.0                # 语义累积每次加 1
SEM_DECAY_PER_FRAME = 0.998            # 缓慢遗忘（几百帧后旧观测衰减到 1/2）

# 世界系累积图（在 reset_bev_state 里分配）
_world_log_odds = None                 # (S, S) float32
_world_walk     = None                 # (S, S) float32
_world_obst     = None                 # (S, S) float32
_world_person   = None                 # (S, S) float32
_world_semantic = None                 # (S, S) uint8: 0 unknown, 1 walkable, 2 obstacle

# ---- v5f: 世界系持久地图 + 轨迹（不随相机转，长期沉淀）------------------
_trajectory_xy = []                     # 每 valid 帧的相机 (x, y) 世界坐标
_current_out_dir = None                 # 用于保存最终地图 PNG
_current_video_stem = None
WORLD_MAP_W_PX = 420                    # 世界地图窗口宽 (px)  ← 矩形
WORLD_MAP_H_PX = 240                    # 世界地图窗口高 (px)


def reset_bev_state():
    """每个视频开始处理前调用，清空 EMA 累积 + tracker + world grid。"""
    global _bev_votes_ema, _persist_obst, _person_history, _person_tracks, _next_person_id
    global _current_pose_R, _current_pose_t, _prev_pose_R, _prev_pose_t
    global _world_log_odds, _world_walk, _world_obst, _world_person, _world_semantic
    global _trajectory_xy
    _bev_votes_ema = None
    _persist_obst = None
    _person_history = []
    _person_tracks = []
    _next_person_id = 0
    _current_pose_R = None; _current_pose_t = None
    _prev_pose_R = None; _prev_pose_t = None
    _reset_scale_history()
    _world_log_odds = np.zeros((WORLD_GRID_SIZE, WORLD_GRID_SIZE), dtype=np.float32)
    _world_walk     = np.zeros_like(_world_log_odds)
    _world_obst     = np.zeros_like(_world_log_odds)
    _world_person   = np.zeros_like(_world_log_odds)
    _world_semantic = np.zeros_like(_world_log_odds, dtype=np.uint8)
    _trajectory_xy  = []
    # v7: 每个视频重置 theta_init (让第一帧朝向决定地图 "上" 方向)
    if hasattr(render_world_overview, "_theta_init"):
        delattr(render_world_overview, "_theta_init")

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
BEV_SIZE        = 300        # v7: 280→300, 增加渲染分辨率，轮廓更清晰
BEV_RANGE_Z_M   = 12.0       # 前方深度范围（米）— 扩大以显示远处行人
BEV_RANGE_X_M   = 9.0        # v7: 7→9, 更宽横向范围，缓解 BEV 显示偏窄的观感
BEV_MARGIN      = 24         # BEV 距画面边距
BEV_BG          = (30, 30, 30)
BEV_GRID_COLOR  = (60, 60, 60)
# BEV 反投影前对 pred / depth 做下采样，加速 numpy 计算
BEV_DOWNSAMPLE  = 2          # 4→2，提高 BEV 点密度，避免稀疏空洞
# 相机内参：Rokid IMX681 已 Kalibr 精确标定 (原方向 fx=642.87 fy=641.74)
# 视频逆时针旋转 90° 后 fx↔fy 互换, W↔H 互换, 水平 FOV 变为 58.6°
FRAME_ROTATE_CCW_90 = True
CAM_FOV_H_DEG   = 58.6       # 旋转后的水平 FOV (1280 竖高变短, 720 竖宽)
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
BEV_EMA_ALPHA = 0.14          # v13: 0.22→0.14, 进一步降新帧权重, 更稳
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
# === POSE-AWARE 版本: 只跑 Rokid 眼镜的 motion session ===
INPUT_ROOT  = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android"
OUTPUT_ROOT = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\output"
SUBDIRS = ["session_20260710_154536_478"]
MAX_VIDEOS_PER_SUBDIR = None
SHOW_PREVIEW = False
SKIP_EXISTING = True
# 输入分辨率超出此长边时按比例缩放（4K → 1080p）
OUTPUT_MAX_LONG_SIDE = 1920
SAMPLE_MODE = False          # 完整视频，不再抽样
SAMPLE_SECONDS = 30
OUTPUT_SUFFIX        = "_bev"
SAMPLE_OUTPUT_SUFFIX = "_bev_sample_v6"
OUTPUT_SUFFIX        = "_bev_pose"        # v7: 加 COLMAP pose 累积 (world-frame BEV)


# =====================================================================
# POSE STREAM — 加载 build_pose_stream.py 生成的 npz
# =====================================================================
POSE_NPZ_PATH = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260710_154536_478\poses\all_frames_pose_metric.npz"
POSE_ENABLED = True
# 输出后缀区分 rotate / pose
_suf = "_bev_pose" if POSE_ENABLED else "_bev_baseline"
if FRAME_ROTATE_CCW_90:
    _suf += "_rot"
_suf += "_v16"  # v16: 稳定语义滞回 + person统一坐标系/多帧确认/去重
OUTPUT_SUFFIX = _suf

_pose_data = None
_pose_ts_to_idx = None
_current_pose_R = None
_current_pose_t = None
_prev_pose_R = None
_prev_pose_t = None


def load_pose_stream():
    global _pose_data, _pose_ts_to_idx
    if not POSE_ENABLED:
        print("[Pose] disabled (POSE_ENABLED=False) → running as no-pose baseline")
        return False
    if not os.path.exists(POSE_NPZ_PATH):
        print(f"[Pose] npz not found: {POSE_NPZ_PATH}  → pose accumulation OFF")
        return False
    d = np.load(POSE_NPZ_PATH)
    _pose_data = {
        "timestamps": d["timestamps_ns"].astype(np.int64),
        "R": d["R_world_cam"],
        "t": d["t_world_cam"],
        "valid": d["valid"],
    }
    _pose_ts_to_idx = {int(ts): i for i, ts in enumerate(_pose_data["timestamps"])}
    n_valid = int(_pose_data["valid"].sum())
    print(f"[Pose] loaded {len(_pose_data['timestamps'])} entries, valid={n_valid}")
    return True


# CCW 90° image rotation ⇒ 新相机绕 z 轴 CCW 90°: R_new_from_old = [[0,-1,0],[1,0,0],[0,0,1]]
# world→newcam = R_new_from_old @ world→oldcam ; 相机世界系姿态 R_world_cam_new = R_world_cam_old @ R_new_from_old^T
_ROT_Z90 = np.array([[0, 1, 0], [-1, 0, 0], [0, 0, 1]], dtype=np.float64)  # R_z(+90°) = R_new_from_old^T


def set_current_pose_by_ts(ts_ns):
    """按帧时间戳更新当前 pose。若该帧无 valid pose 则清空。"""
    global _current_pose_R, _current_pose_t
    if _pose_data is None or _pose_ts_to_idx is None:
        _current_pose_R = None; _current_pose_t = None; return
    idx = _pose_ts_to_idx.get(int(ts_ns))
    if idx is None or not bool(_pose_data["valid"][idx]):
        _current_pose_R = None; _current_pose_t = None; return
    R_wc = _pose_data["R"][idx]
    if FRAME_ROTATE_CCW_90:
        R_wc = R_wc @ _ROT_Z90
    _current_pose_R = R_wc
    _current_pose_t = _pose_data["t"][idx]


def _pose_to_bev_affine(R_prev, t_prev, R_curr, t_curr):
    """把上一帧 BEV grid 从上一相机系 warp 到当前相机系。

    v5d 修正：FRAME_ROTATE_CCW_90 补偿后，相机 X/Y 轴变成 新X=旧上, 新Y=旧右, 新Z=旧前。
    重力方向从"新Y"变到"新-X"了，绕重力 yaw 要从 R[1,2] 提，横向平移要用 t[1]。
    原代码用 R[0,2]/t[0] 是在错的轴上取。
    """
    R_ccurr_cprev = R_curr.T @ R_prev
    t_ccurr_cprev = R_curr.T @ (t_prev - t_curr)
    if FRAME_ROTATE_CCW_90:
        # 新 cam 系下 yaw 绕新-X 轴, 用 R[1,2]/R[2,2]; 横向 = t[1]
        yaw = float(np.arctan2(R_ccurr_cprev[1, 2], R_ccurr_cprev[2, 2]))
        t_right = float(t_ccurr_cprev[1])
    else:
        yaw = float(np.arctan2(R_ccurr_cprev[0, 2], R_ccurr_cprev[2, 2]))
        t_right = float(t_ccurr_cprev[0])

    px_per_m_x = BEV_SIZE / (2.0 * BEV_RANGE_X_M)
    px_per_m_z = BEV_SIZE / BEV_RANGE_Z_M

    dx_px = t_right * px_per_m_x
    dz_px = -float(t_ccurr_cprev[2]) * px_per_m_z

    center = (BEV_SIZE // 2, BEV_SIZE - 5)
    M = cv2.getRotationMatrix2D(center, np.degrees(-yaw), 1.0)
    M[0, 2] += dx_px
    M[1, 2] += dz_px
    return M


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
    """根据帧亮度选择 B2 / B4。返回 (processor, model, used_label)。"""
    L_mean = float(cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2LAB)[..., 0].mean())
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
        rgb_s = cv2.resize(rgb, (int(round(w * scale)), int(round(h * scale))),
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
CAMERA_HEIGHT_M = 1.68   # 相机光心离地高度（Rokid 眼镜戴在头上）
# 跨帧 depth scale 平滑：解决每帧独立校准带来的 BEV 闪烁
_scale_k_history = []
_SCALE_K_WINDOW = 90     # v7: 60→90, 更长窗口, k 更稳
# 有 has_any 累积次数（用于滞后阈值决定像素显示/隐藏）
_bev_hits_count = None

def _reset_scale_history():
    global _scale_k_history, _bev_hits_count
    _scale_k_history = []
    _bev_hits_count = None


def _depth_to_metric_legacy(disp):
    """老版本：p5/p95 归一化到 [NEAR, FAR] 米（fallback）。"""
    p5, p95 = np.percentile(disp, [5, 95])
    if p95 - p5 < 1e-6:
        return np.full_like(disp, (DEPTH_NEAR_M + DEPTH_FAR_M) / 2.0, dtype=np.float32)
    norm = np.clip((disp - p5) / (p95 - p5), 0.0, 1.0)
    return (DEPTH_NEAR_M + (1.0 - norm) * (DEPTH_FAR_M - DEPTH_NEAR_M)).astype(np.float32)


def _depth_to_metric(disp, pred_mask=None):
    """Ground-plane 校准的 metric depth。

    对 walkable 像素（v > cy，画面下半的地面）:
      假设 Depth Anything 输出 disp = k / Z_metric (视差-深度反比)
      地面像素 Y_cam = (v - cy) * Z_metric / fy = h_cam (相机高度)
      → k = h_cam * fy * disp / (v - cy)
    取所有 walkable 像素的 median(k) → 稳健校准
    然后 Z_metric = k / disp 对全图应用
    """
    if pred_mask is None:
        return _depth_to_metric_legacy(disp)

    h, w = disp.shape
    fy = w / (2.0 * np.tan(np.deg2rad(CAM_FOV_H_DEG) / 2.0))
    cy_c = h / 2.0

    types = _CLS_LUT[pred_mask.astype(np.uint8)]
    walk = types == 1
    v_idx = np.arange(h, dtype=np.float32)[:, None].repeat(w, axis=1)
    v_below = v_idx - cy_c
    # 只用地平线下 ≥ 15 像素 + 有效 disp
    valid = walk & (v_below > 15) & (disp > 0.05)
    if valid.sum() < 200:
        # walkable 太少或全在画面上半 → 回退到 legacy
        return _depth_to_metric_legacy(disp)

    ks = CAMERA_HEIGHT_M * fy * disp[valid] / v_below[valid]
    ks = ks[np.isfinite(ks) & (ks > 0)]
    if ks.size < 100:
        return _depth_to_metric_legacy(disp)
    k_this = float(np.median(ks))

    # 跨帧 median smoothing 避免每帧 k 跳变导致的 BEV 闪烁
    global _scale_k_history
    _scale_k_history.append(k_this)
    if len(_scale_k_history) > _SCALE_K_WINDOW:
        _scale_k_history = _scale_k_history[-_SCALE_K_WINDOW:]
    k = float(np.median(_scale_k_history))

    Z = k / (disp + 1e-6)
    return np.clip(Z, 0.1, 40.0).astype(np.float32)


def _make_bev_background():
    bg = np.full((BEV_SIZE, BEV_SIZE, 3), BEV_BG, dtype=np.uint8)
    cx, cy = BEV_SIZE // 2, BEV_SIZE - 5
    # 同心圆距离参考（每 2m 一圈，直至 BEV_RANGE_Z_M）+ 距离标签
    r_m = 2.0
    while r_m <= BEV_RANGE_Z_M:
        r_px = int(round(r_m / BEV_RANGE_Z_M * BEV_SIZE))
        cv2.circle(bg, (cx, cy), r_px, BEV_GRID_COLOR, 1)
        # 在圆的正前方（12 点方向）标注米数
        label = f"{int(r_m)}m"
        y_lbl = cy - r_px + 10
        if 0 < y_lbl < BEV_SIZE:
            cv2.putText(bg, label, (cx + 4, y_lbl),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.32, (110, 110, 110), 1, cv2.LINE_AA)
        r_m += 2.0
    # 前向 / 左右轴
    cv2.line(bg, (cx, cy), (cx, 0), BEV_GRID_COLOR, 1)
    cv2.line(bg, (0, cy), (BEV_SIZE, cy), BEV_GRID_COLOR, 1)
    # 自身位置（白点 + 三角前向指示）
    cv2.circle(bg, (cx, cy), 4, (255, 255, 255), -1)
    tri = np.array([[cx, cy - 12], [cx - 6, cy - 2], [cx + 6, cy - 2]], dtype=np.int32)
    cv2.fillPoly(bg, [tri], (200, 200, 200))
    return bg


_BEV_BG = _make_bev_background()


def _stable_cam_to_world_R():
    """返回与 world grid/person 共用的稳定 cam→world 旋转。"""
    R = _current_pose_R.astype(np.float32)
    if len(_trajectory_xy) >= 10:
        n_recent = min(30, len(_trajectory_xy))
        dx = _trajectory_xy[-1][0] - _trajectory_xy[-n_recent][0]
        dy = _trajectory_xy[-1][1] - _trajectory_xy[-n_recent][1]
        d_mag = (dx * dx + dy * dy) ** 0.5
        if d_mag > 0.3:
            fwd_w = np.array([dx / d_mag, dy / d_mag, 0.0], dtype=np.float32)
            down_w = np.array([0.0, 0.0, 1.0], dtype=np.float32)
            right_w = np.cross(fwd_w, down_w)
            right_w /= np.linalg.norm(right_w) + 1e-9
            R = np.stack([right_w, down_w, fwd_w], axis=1).astype(np.float32)
    return R


def _update_world_grid(pred_mask, depth_disp):
    """v8 核心：把当前帧的稠密深度 + 语义 累积到世界系 log-odds occupancy grid。

    每帧做的事：
      1. 反投影到相机 ego 系 (X_c, Y_c, Z_c)
      2. 用 pose 转到世界系 (Xw, Yw, Zw)
      3. 端点更新: obstacle/person 在网格上 log_odds ++, walkable 微降
      4. 沿视线 raycast: 从相机到端点之间的 cells log_odds --（空闲）
      5. 语义通道 (walk/obst/person hits) 独立累积
      6. 每 K 帧 slow decay 遗忘旧观测（防止累积错误无限漂）

    依赖 _current_pose_R, _current_pose_t 有效；无 pose 帧直接 return（继续用累积图）。
    """
    global _world_log_odds, _world_walk, _world_obst, _world_person, _world_semantic

    if _current_pose_R is None or _current_pose_t is None:
        return
    if _world_log_odds is None:
        return  # 应该在 reset_bev_state 中已初始化

    # 下采样以加速（H/4 × W/4 依然稠密到覆盖 grid cells）
    ds = 4
    pred_ds = pred_mask[::ds, ::ds]
    disp_ds = depth_disp[::ds, ::ds]
    h, w = disp_ds.shape

    fx = w / (2.0 * np.tan(np.deg2rad(CAM_FOV_H_DEG) / 2.0))
    fy = fx
    cxp, cyp = w / 2.0, h / 2.0

    Z = _depth_to_metric(disp_ds, pred_ds)
    us = np.arange(w, dtype=np.float32)
    vs = np.arange(h, dtype=np.float32)
    uu, vv = np.meshgrid(us, vs)
    Xc = (uu - cxp) * Z / fx
    Yc = (vv - cyp) * Z / fy
    # v11: 恢复标准 OpenCV 前方 = +Z_cam (跨段 R[:,2] 符号不一致, 固定 flip 无解)
    Zc = Z

    # v13: 用合成 R (gravity 向下 + 轨迹切线为前) 代替 R 做深度反投影
    # 目的: R[:,2] 跨段符号翻转不可靠, 会把障碍点投错到相机后方/左右
    # 合成 R 用两个可靠信号: 重力方向(=+Z world after R_align) + 走行方向 (dt)
    t = _current_pose_t.astype(np.float32).reshape(3)
    R = _stable_cam_to_world_R()

    pts_c = np.stack([Xc, Yc, Zc], axis=-1).reshape(-1, 3)  # (N, 3)
    pts_w = pts_c @ R.T + t.reshape(1, 3)                    # (N, 3)
    Xw = pts_w[:, 0]
    Yw = pts_w[:, 1]
    Zw = pts_w[:, 2]

    # v11: world Z = gravity (down). Horizontal plane = XY. Vertical filter on Z.
    types_flat = _CLS_LUT[pred_ds.astype(np.uint8)].ravel()  # 0=obst 1=walk 2=person
    # v10 fix: 用原始 depth Z (正值) 做距离过滤, 不用 Zc (被 flip 成负值)
    Zc_flat    = Z.ravel()
    z_rel_cam  = Zw - float(t[2])   # 每点相对相机的 gravity-方向偏移 (+ = 更靠地面 / 下方)
    valid = ((Zc_flat > 0.3) & (Zc_flat < BEV_RANGE_Z_M + 3.0)
             & (z_rel_cam > -2.5) & (z_rel_cam < 3.0))
    Xw_v = Xw[valid]; Yw_v = Yw[valid]; types_v = types_flat[valid]

    # 水平平面: 用 (Xw, Yw) 做 grid 索引；grid row = Y 方向, col = X 方向
    gx_end = np.floor(Xw_v / WORLD_GRID_CELL_M + WORLD_GRID_ORIGIN).astype(np.int32)  # col
    gy_end = np.floor(Yw_v / WORLD_GRID_CELL_M + WORLD_GRID_ORIGIN).astype(np.int32)  # row
    in_grid = ((gx_end >= 0) & (gx_end < WORLD_GRID_SIZE)
               & (gy_end >= 0) & (gy_end < WORLD_GRID_SIZE))
    gx_end = gx_end[in_grid]; gy_end = gy_end[in_grid]
    types_v = types_v[in_grid]; Xw_v = Xw_v[in_grid]; Yw_v = Yw_v[in_grid]

    # ---- 端点更新 —— 每帧每 cell 只累加一次 log-odds（去重）------------------
    is_obst   = types_v == 0
    is_walk   = types_v == 1
    is_person = types_v == 2

    def _unique_cells(mask):
        if not mask.any():
            return None, None
        flat = gy_end[mask].astype(np.int64) * WORLD_GRID_SIZE + gx_end[mask].astype(np.int64)
        uniq = np.unique(flat)
        return (uniq // WORLD_GRID_SIZE).astype(np.int32), (uniq % WORLD_GRID_SIZE).astype(np.int32)

    uy_o, ux_o = _unique_cells(is_obst)
    if uy_o is not None:
        walk_evidence = _world_walk[uy_o, ux_o]
        obst_gain = L_OCC_UPDATE_HIT * np.clip(1.0 - walk_evidence / 8.0, 0.15, 1.0)
        _world_log_odds[uy_o, ux_o] += obst_gain.astype(np.float32)
        _world_obst[uy_o, ux_o] += SEM_HIT_STEP

    uy_w, ux_w = _unique_cells(is_walk)
    if uy_w is not None:
        _world_log_odds[uy_w, ux_w] -= L_FREE_UPDATE_HIT * 2.3
        _world_walk[uy_w, ux_w] += SEM_HIT_STEP

    uy_p, ux_p = _unique_cells(is_person)
    if uy_p is not None:
        _world_person[uy_p, ux_p] += SEM_HIT_STEP * 2.0

    # ---- Free-space raycast: 从相机沿射线到端点之间的 cells 都是 free ----
    cam_gx = t[0] / WORLD_GRID_CELL_M + WORLD_GRID_ORIGIN
    cam_gy = t[1] / WORLD_GRID_CELL_M + WORLD_GRID_ORIGIN

    # 对每个端点采样一条射线的中间 N 段：t ∈ [0.1, 0.85]
    # 端点数太多时抽样降低开销
    n_pts = Xw_v.size
    if n_pts == 0:
        pass
    else:
        # 每 4 个端点采样一条射线（否则 raycast 太慢）
        stride = max(1, n_pts // 6000)
        gx_e = gx_end[::stride]; gy_e = gy_end[::stride]
        n_rays = gx_e.size
        n_samp = 24
        ts_lin = np.linspace(0.08, 0.90, n_samp, dtype=np.float32)   # (n_samp,)
        # (n_rays, n_samp) grid coords along ray
        gxs = cam_gx + (gx_e[:, None] - cam_gx) * ts_lin[None, :]
        gys = cam_gy + (gy_e[:, None] - cam_gy) * ts_lin[None, :]
        gxs_i = np.clip(np.floor(gxs).astype(np.int32), 0, WORLD_GRID_SIZE - 1)
        gys_i = np.clip(np.floor(gys).astype(np.int32), 0, WORLD_GRID_SIZE - 1)
        flat = gys_i.ravel() * WORLD_GRID_SIZE + gxs_i.ravel()
        uniq_flat, counts = np.unique(flat, return_counts=True)
        uy = uniq_flat // WORLD_GRID_SIZE
        ux = uniq_flat % WORLD_GRID_SIZE
        _world_log_odds[uy, ux] -= L_FREE_UPDATE_HIT * np.minimum(counts, 3).astype(np.float32) / 3.0

    # ---- log-odds 截断; v5g: 世界通道禁用 decay 使地图永久沉淀 ----
    np.clip(_world_log_odds, L_MIN, L_MAX, out=_world_log_odds)
    # 语义证据缓慢衰减，允许环境更新；稳定状态用滞回避免闪烁/擦黑
    _world_walk *= 0.9998
    _world_obst *= 0.9998
    np.clip(_world_walk, 0, 100.0, out=_world_walk)
    np.clip(_world_obst, 0, 100.0, out=_world_obst)
    _world_person *= 0.985   # person 仍然快速遗忘 (临时占用)
    np.clip(_world_person, 0, 40.0, out=_world_person)

    state = _world_semantic
    was_unknown = state == 0
    was_walk = state == 1
    was_obst = state == 2
    # 未知格首次分类门槛较低；已有类别只有强反证才切换，中间冲突保持原色
    state[was_unknown & (_world_walk >= 1.0) &
          (_world_walk > _world_obst * 0.8)] = 1
    state[was_unknown & (_world_obst >= 2.0) &
          (_world_obst > _world_walk * 2.0)] = 2
    state[was_walk & (_world_obst > np.maximum(_world_walk * 2.5,
                                                _world_walk + 4.0))] = 2
    state[was_obst & (_world_walk > np.maximum(_world_obst * 1.5,
                                                _world_obst + 3.0))] = 1


def _render_bev_from_world():
    """从世界系累积图，以当前相机 pose 为中心截取相机朝向的 mini-map。

    渲染步骤：
      1. 相机世界位置 + yaw 计算变换矩阵
      2. cv2.warpAffine 从 world_log_odds 抽取 BEV 大小的 patch
      3. 按 log_odds + semantic 通道决定每 pixel 颜色
      4. 叠加背景 (同心圆/前向指示)
    """
    bev = _BEV_BG.copy()
    if _world_log_odds is None or _current_pose_R is None or _current_pose_t is None:
        return bev

    cam_wx = float(_current_pose_t[0])
    cam_wy = float(_current_pose_t[1])
    R_map = _stable_cam_to_world_R()
    yaw = float(np.arctan2(R_map[1, 2], R_map[0, 2]))

    m_per_bev_px = BEV_RANGE_Z_M / BEV_SIZE                          # ~0.04 m/px
    world_cell_per_bev_px = m_per_bev_px / WORLD_GRID_CELL_M          # ~0.4

    cam_ggx = cam_wx / WORLD_GRID_CELL_M + WORLD_GRID_ORIGIN          # grid col
    cam_ggy = cam_wy / WORLD_GRID_CELL_M + WORLD_GRID_ORIGIN          # grid row

    cx_b = BEV_SIZE / 2.0
    cy_b = BEV_SIZE - 5.0

    cos_y = float(np.cos(yaw))
    sin_y = float(np.sin(yaw))

    # 逆变换 (BEV pixel → world grid)：
    #   cam_right = (bx - cx_b) * m_per_bev_px
    #   cam_fwd   = (cy_b - by) * m_per_bev_px
    #   世界系 (水平面): forward = (cos yaw, sin yaw), right = (sin yaw, -cos yaw)
    #   world_dx = cam_right*sin + cam_fwd*cos
    #   world_dy = -cam_right*cos + cam_fwd*sin
    w2b = world_cell_per_bev_px
    a =  sin_y * w2b     # 系数 of (bx - cx_b) for source_col (gx)
    b =  cos_y * w2b     # 系数 of (cy_b - by) for source_col (gx)
    c = -cos_y * w2b     # 系数 of (bx - cx_b) for source_row (gy)
    d =  sin_y * w2b     # 系数 of (cy_b - by) for source_row (gy)
    # source_col = a*bx - b*by + (cam_ggx - a*cx_b + b*cy_b)
    # source_row = c*bx - d*by + (cam_ggy - c*cx_b + d*cy_b)
    M = np.array([
        [a, -b, cam_ggx - a * cx_b + b * cy_b],
        [c, -d, cam_ggy - c * cx_b + d * cy_b],
    ], dtype=np.float32)

    warp_kwargs = dict(dsize=(BEV_SIZE, BEV_SIZE),
                       flags=cv2.INTER_LINEAR + cv2.WARP_INVERSE_MAP,
                       borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    lo = cv2.warpAffine(_world_log_odds, M, **warp_kwargs)
    wk = cv2.warpAffine(_world_walk,     M, **warp_kwargs)
    ob = cv2.warpAffine(_world_obst,     M, **warp_kwargs)
    pn = cv2.warpAffine(_world_person,   M, **warp_kwargs)
    semantic = cv2.warpAffine(_world_semantic, M,
                              dsize=(BEV_SIZE, BEV_SIZE),
                              flags=cv2.INTER_NEAREST + cv2.WARP_INVERSE_MAP,
                              borderMode=cv2.BORDER_CONSTANT, borderValue=0)

    # v10 决策：栅格只画 walk/obst 两类；person 完全由圈子（下方 track 逻辑）负责。
    #   walk：wk 明显多于 ob，或 log_odds 明显偏 free —— 门槛低
    #   obst：log_odds 明确正 且 ob 显著超过 wk —— 门槛高
    is_walk = semantic == 1
    is_obst = semantic == 2
    is_person = np.zeros_like(is_walk)   # placeholder，不在栅格里画人

    bev[is_walk]   = COLOR_WALKABLE
    bev[is_obst & ~is_person]   = COLOR_OBSTACLE
    bev[is_person] = COLOR_PERSON

    # 障碍物形状轮廓（粗白线）
    obst_mask = is_obst.astype(np.uint8) * 255
    obst_mask = cv2.morphologyEx(obst_mask, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    contours, _ = cv2.findContours(obst_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    for c in contours:
        if cv2.contourArea(c) >= 15:
            cv2.drawContours(bev, [c], -1, (255, 255, 255), 2, cv2.LINE_AA)

    return bev


def compute_bev(pred_mask, depth_disp):
    """v5f: v5 legacy 主 BEV (相机朝上) + 同步更新世界系累积图 & 轨迹."""
    # 主 mini-map (相机朝上, 旋转)
    bev = _compute_bev_v7_legacy(pred_mask, depth_disp)
    # 世界系累积 (不旋转, 长期沉淀)
    _update_world_grid(pred_mask, depth_disp)
    # 记录轨迹
    if _current_pose_t is not None:
        _trajectory_xy.append((float(_current_pose_t[0]), float(_current_pose_t[1])))
    return bev


def render_world_overview():
    """世界系持久地图 (矩形): 累积 walk/obst + 相机轨迹 + 当前朝向.

    - 不随相机转 (世界固定视角)
    - 自动缩放使轨迹居中并填充窗口
    - 使用 v11 已有的 _world_walk/_world_obst 累积
    """
    W, H = WORLD_MAP_W_PX, WORLD_MAP_H_PX
    img = np.full((H, W, 3), 25, dtype=np.uint8)

    if not _trajectory_xy or _world_walk is None:
        return img

    # v7: 用初始相机朝向作为地图"上"方向 —— 消除镜像歧义, 与用户第一人称心智对齐
    # 每次 render 时把整个世界系旋转 (2D): 初始 forward 方向 → 图像 +y (up).
    # v12: theta_init 从 trajectory 首次可靠 dt 计算 (R[:,2] 不可靠, 跨段翻转)
    # 一直未设置直到累积 >= 30 帧 (0.5s) 且总位移 > 0.5m; 之前用 0 (map 默认 world +X 向右)
    if not hasattr(render_world_overview, "_theta_init"):
        if len(_trajectory_xy) >= 30:
            head = _trajectory_xy[0]
            for tail_idx in range(29, len(_trajectory_xy)):
                dx = _trajectory_xy[tail_idx][0] - head[0]
                dy = _trajectory_xy[tail_idx][1] - head[1]
                if (dx*dx + dy*dy) ** 0.5 > 0.5:
                    render_world_overview._theta_init = float(np.arctan2(dy, dx))
                    break
    # theta_init 未确定时用 0 (world +X 向右 as default) —— 之后走够路会锁定
    theta = getattr(render_world_overview, "_theta_init", 0.0)
    ct, st = float(np.cos(theta)), float(np.sin(theta))
    # 旋转函数: (x, y) → (x', y') 使得 (cos(theta), sin(theta)) 变为 (0, 1)
    def rotate_world(x, y):
        # 让 forward 方向对齐到 +y: rot by (-theta + π/2)
        # rot(x, y, angle) = (x*cos - y*sin, x*sin + y*cos)
        # 我们要转 angle = π/2 - theta
        ang = np.pi / 2 - theta
        ca, sa = np.cos(ang), np.sin(ang)
        return x * ca - y * sa, x * sa + y * ca

    xs_raw = np.array([p[0] for p in _trajectory_xy], dtype=np.float32)
    ys_raw = np.array([p[1] for p in _trajectory_xy], dtype=np.float32)
    xs, ys = rotate_world(xs_raw, ys_raw)
    cam_x_raw = float(_current_pose_t[0]) if _current_pose_t is not None else float(xs_raw[-1])
    cam_y_raw = float(_current_pose_t[1]) if _current_pose_t is not None else float(ys_raw[-1])
    cam_x, cam_y = rotate_world(np.array([cam_x_raw]), np.array([cam_y_raw]))
    cam_x, cam_y = float(cam_x[0]), float(cam_y[0])

    trajectory_x_span = float(xs.max() - xs.min())
    trajectory_y_span = float(ys.max() - ys.min())
    view_w_m = max(20.0, trajectory_x_span * 1.2)
    view_h_m = max(12.0, trajectory_y_span * 1.2)

    x_min = cam_x - view_w_m / 2
    x_max = cam_x + view_w_m / 2
    y_min = cam_y - view_h_m / 2
    y_max = cam_y + view_h_m / 2
    w_m = view_w_m
    h_m = view_h_m

    m_per_px = max(w_m / W, h_m / H)

    def world_to_px(x, y):
        # v14: X 轴翻转 (镜像修正 - COLMAP+R_align 世界系手性)
        px = int(W - 1 - (x - x_min) / m_per_px)
        py = int(H - 1 - (y - y_min) / m_per_px)
        return px, py

    # 世界 grid → 旋转对齐后的像素 (向量化)
    gy_mg, gx_mg = np.mgrid[0:WORLD_GRID_SIZE, 0:WORLD_GRID_SIZE]
    wx_raw = (gx_mg - WORLD_GRID_ORIGIN) * WORLD_GRID_CELL_M
    wy_raw = (gy_mg - WORLD_GRID_ORIGIN) * WORLD_GRID_CELL_M
    wx_r, wy_r = rotate_world(wx_raw, wy_raw)
    # v14 (简化): 只显示明确的 walkable/obstacle, 无弱观测灰色
    # 先构造两张二值图 (world grid 分辨率), 再形态学膨胀让区域连成片
    walk_grid = (_world_semantic == 1).astype(np.uint8)
    obst_grid = (_world_semantic == 2).astype(np.uint8)
    # 膨胀 3x3 让 稀疏点连成大块
    kernel = np.ones((3, 3), np.uint8)
    walk_grid = cv2.dilate(walk_grid, kernel, iterations=2)
    obst_grid = cv2.dilate(obst_grid, kernel, iterations=2)
    # obstacle 优先 (安全):walkable 与 obstacle 冲突时算 obstacle
    walk_grid = walk_grid & (~obst_grid.astype(bool)).astype(np.uint8)

    # 将两张 world grid 图 warp 到 mini-map 显示空间 (旋转 + 缩放 + X翻转)
    # 用 cv2.warpAffine 构造仿射矩阵一步到位
    ang = np.pi / 2 - theta  # 与 rotate_world 一致
    # 世界 grid cell (gx, gy) → 世界坐标 wx, wy → 旋转后 wx_r, wy_r → mini-map (px, py)
    # 组合仿射:
    #   1. gx * cell_m - origin_m -> wx (类似 wy)
    #   2. (wx, wy) 旋转 ang 得到 (wx_r, wy_r)
    #   3. px = W-1 - (wx_r - x_min)/m_per_px; py = H-1 - (wy_r - y_min)/m_per_px
    # 由于 warp 输入是 world grid, 输出是 mini-map, 我们直接 warpAffine 从 grid 到显示
    ca, sa = np.cos(ang), np.sin(ang)
    # 前向映射 (grid_col, grid_row) → (px, py):
    #   wx = (gx - origin) * cell
    #   wy = (gy - origin) * cell
    #   wx_r = wx*ca - wy*sa
    #   wy_r = wx*sa + wy*ca
    #   px = (W-1) - (wx_r - x_min)/m_per_px
    #   py = (H-1) - (wy_r - y_min)/m_per_px
    # 用像素平面 warpAffine (destination -> source):
    #   给 mini-map (px, py) 反查 grid (gx, gy)
    # 反解:
    #   wx_r = -(px - (W-1)) * m_per_px + x_min = x_min + (W-1-px)*m_per_px
    #   wy_r = y_min + (H-1-py)*m_per_px
    #   wx = wx_r * ca + wy_r * sa
    #   wy = -wx_r * sa + wy_r * ca
    #   gx = wx / cell + origin
    #   gy = wy / cell + origin
    # 构造 2x3 仿射矩阵 M 使 [gx, gy] = M @ [px, py, 1]:
    cell = WORLD_GRID_CELL_M
    inv_m = m_per_px / cell   # 每 mini-map 像素跨多少 grid cells
    # source_x (grid col) 关于 (px, py):
    #   gx = (x_min + (W-1-px)*m_per_px)*ca/cell + (y_min + (H-1-py)*m_per_px)*sa/cell + origin
    #   = -ca*inv_m * px  - sa*inv_m * py + [常数]
    a = -ca * inv_m; b = -sa * inv_m
    tx_const = (x_min + (W - 1) * m_per_px) * ca / cell + (y_min + (H - 1) * m_per_px) * sa / cell + WORLD_GRID_ORIGIN
    # source_y (grid row):
    #   gy = -(x_min + (W-1-px)*m_per_px)*sa/cell + (y_min + (H-1-py)*m_per_px)*ca/cell + origin
    c = sa * inv_m; d = -ca * inv_m
    ty_const = -(x_min + (W - 1) * m_per_px) * sa / cell + (y_min + (H - 1) * m_per_px) * ca / cell + WORLD_GRID_ORIGIN
    M = np.array([[a, b, tx_const], [c, d, ty_const]], dtype=np.float32)

    warp_kwargs = dict(dsize=(W, H), flags=cv2.INTER_NEAREST + cv2.WARP_INVERSE_MAP,
                       borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    walk_view = cv2.warpAffine(walk_grid, M, **warp_kwargs)
    obst_view = cv2.warpAffine(obst_grid, M, **warp_kwargs)

    # 上色优先级：可行 < 不可行 < 人点
    img[walk_view > 0] = COLOR_WALKABLE
    img[obst_view > 0] = COLOR_OBSTACLE

    # v7: 轨迹通过的 cells 强制标记为 walkable (走过的地方 = 一定能走)
    # v14 fix: 补上 X 轴翻转; radius 加大更明显
    if _trajectory_xy:
        radius_m = 0.5
        radius_px = max(2, int(radius_m / m_per_px))
        for tx, ty in _trajectory_xy:
            rx, ry = rotate_world(np.array([tx]), np.array([ty]))
            tpx = int(W - 1 - (float(rx[0]) - x_min) / m_per_px)   # X 翻转
            tpy = int(H - 1 - (float(ry[0]) - y_min) / m_per_px)
            if 0 <= tpx < W and 0 <= tpy < H:
                cv2.circle(img, (tpx, tpy), radius_px, COLOR_WALKABLE, -1, cv2.LINE_AA)
    # 只显示多帧确认且当前仍有效的人物 track，避免一个人留下多个历史点
    for tr in _person_tracks:
        if tr["age"] < 5 or tr["misses"] > 3:
            continue
        rx, ry = rotate_world(np.array([tr["xw"]]), np.array([tr["yw"]]))
        center = world_to_px(float(rx[0]), float(ry[0]))
        if 0 <= center[0] < W and 0 <= center[1] < H:
            cv2.circle(img, center, 6, (0, 0, 0), -1)
            cv2.circle(img, center, 4, COLOR_PERSON, -1)

    # 轨迹线 (旋转后)
    def rw2p(x_raw, y_raw):
        rx, ry = rotate_world(np.array([x_raw]), np.array([y_raw]))
        return world_to_px(float(rx[0]), float(ry[0]))

    if len(_trajectory_xy) >= 2:
        pts = [rw2p(x, y) for x, y in _trajectory_xy]
        pts_np = np.array(pts, dtype=np.int32).reshape(-1, 1, 2)
        cv2.polylines(img, [pts_np], False, (255, 200, 0), 2, cv2.LINE_AA)

    if _trajectory_xy:
        p0 = rw2p(_trajectory_xy[0][0], _trajectory_xy[0][1])
        cv2.circle(img, p0, 5, (200, 200, 200), -1)

    # v11: FOV 方向改用**轨迹切线** (走路时可靠, 静止时后备 R 方向)
    if _current_pose_R is not None and _current_pose_t is not None:
        cx = float(_current_pose_t[0]); cy = float(_current_pose_t[1])
        cur = rw2p(cx, cy)
        R = _current_pose_R
        # 主源: 用近期 ±10 帧轨迹切线 (~0.3s 位移) 定 forward
        fx, fy = 0.0, 0.0
        if len(_trajectory_xy) >= 10:
            recent = _trajectory_xy[-10:]
            dx = recent[-1][0] - recent[0][0]
            dy = recent[-1][1] - recent[0][1]
            m = (dx*dx + dy*dy) ** 0.5
            if m > 0.05:   # 有明显位移
                fx, fy = dx / m, dy / m
        if fx == 0.0 and fy == 0.0:
            # 后备: 用 R[:,2] (静止时)
            fx = -float(R[0, 2]); fy = -float(R[1, 2])
        nrm = (fx * fx + fy * fy) ** 0.5
        if nrm > 1e-3:
            fx /= nrm; fy /= nrm
            yaw_deg = float(np.degrees(np.arctan2(fy, fx)))
            fov_half = np.deg2rad(58.6 / 2)
            radius_m = min(w_m, h_m) * 0.15
            n_arc = 20
            angles = np.linspace(-fov_half, fov_half, n_arc)
            fwd_ang = np.arctan2(fy, fx)
            arc_pts = []
            for a in angles:
                aa = fwd_ang + a
                ex = cx + np.cos(aa) * radius_m
                ey = cy + np.sin(aa) * radius_m
                arc_pts.append(rw2p(ex, ey))
            wedge = np.array([cur] + arc_pts, dtype=np.int32).reshape(-1, 1, 2)
            # 半透明填充: 先在临时图上填, 再 addWeighted
            overlay = img.copy()
            cv2.fillPoly(overlay, [wedge], (0, 220, 255))
            img[:] = cv2.addWeighted(overlay, 0.35, img, 0.65, 0)
            # 扇形边线
            cv2.polylines(img, [wedge], True, (0, 220, 255), 1, cv2.LINE_AA)
            # 相机中心大圆
            cv2.circle(img, cur, 7, (0, 220, 255), -1)
            cv2.circle(img, cur, 8, (0, 0, 0), 1)
            # yaw 数值
            cv2.putText(img, f"yaw {yaw_deg:+.0f} deg", (W - 100, 14),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.36, (0, 220, 255), 1, cv2.LINE_AA)
        else:
            cv2.circle(img, cur, 7, (0, 220, 255), -1)

    # 比例尺 (右下)
    scale_m = 2.0 if m_per_px < 0.05 else (5.0 if m_per_px < 0.12 else 10.0)
    scale_px = int(scale_m / m_per_px)
    cv2.line(img, (W - scale_px - 10, H - 12),
             (W - 10, H - 12), (220, 220, 220), 2)
    cv2.putText(img, f"{scale_m:.0f}m", (W - scale_px - 10, H - 18),
                cv2.FONT_HERSHEY_SIMPLEX, 0.35, (220, 220, 220), 1, cv2.LINE_AA)
    # 边框
    cv2.rectangle(img, (0, 0), (W - 1, H - 1), (255, 255, 255), 1)
    cv2.putText(img, "WORLD MAP", (6, 14), cv2.FONT_HERSHEY_SIMPLEX,
                0.35, (200, 200, 200), 1, cv2.LINE_AA)
    return img


def save_world_map_png(out_dir, video_stem):
    """视频跑完后保存高分辨率世界地图 PNG."""
    if not _trajectory_xy or _world_walk is None:
        return None
    xs = np.array([p[0] for p in _trajectory_xy], dtype=np.float32)
    ys = np.array([p[1] for p in _trajectory_xy], dtype=np.float32)
    margin_m = 3.0
    x_min = float(xs.min()) - margin_m; x_max = float(xs.max()) + margin_m
    y_min = float(ys.min()) - margin_m; y_max = float(ys.max()) + margin_m
    w_m = x_max - x_min; h_m = y_max - y_min
    # 20 px/m
    pxpm = 20
    W = int(w_m * pxpm); H = int(h_m * pxpm)
    img = np.full((H, W, 3), 30, dtype=np.uint8)

    def w2p(x, y):
        # v14: X 翻转
        return int(W - 1 - (x - x_min) * pxpm), int(H - 1 - (y - y_min) * pxpm)

    # 与视频内 world map 使用同一套明确的三分类规则
    gy, gx = np.mgrid[0:WORLD_GRID_SIZE, 0:WORLD_GRID_SIZE]
    wx = (gx - WORLD_GRID_ORIGIN) * WORLD_GRID_CELL_M
    wy = (gy - WORLD_GRID_ORIGIN) * WORLD_GRID_CELL_M
    in_view = (wx >= x_min) & (wx <= x_max) & (wy >= y_min) & (wy <= y_max)
    walk_grid = (_world_semantic == 1).astype(np.uint8)
    obst_grid = (_world_semantic == 2).astype(np.uint8)
    kernel = np.ones((3, 3), np.uint8)
    walk_grid = cv2.dilate(walk_grid, kernel, iterations=2)
    obst_grid = cv2.dilate(obst_grid, kernel, iterations=2)
    walk_grid = walk_grid & (~obst_grid.astype(bool)).astype(np.uint8)

    def paint_grid(grid, color):
        mask = in_view & (grid > 0)
        if not mask.any():
            return
        px = (W - 1 - (wx[mask] - x_min) * pxpm).astype(np.int32)
        py = (H - 1 - (wy[mask] - y_min) * pxpm).astype(np.int32)
        keep = (px >= 0) & (px < W) & (py >= 0) & (py < H)
        # 一个 world cell 覆盖完整显示像素，避免只留下难辨认的淡散点
        radius = max(1, int(np.ceil(WORLD_GRID_CELL_M * pxpm / 2)))
        for x_px, y_px in zip(px[keep], py[keep]):
            cv2.circle(img, (int(x_px), int(y_px)), radius, color, -1)

    paint_grid(walk_grid, COLOR_WALKABLE)
    paint_grid(obst_grid, COLOR_OBSTACLE)

    # 走过的区域强制标为可行，并使用同一个镜像坐标变换
    walk_radius_px = max(2, int(0.5 * pxpm))
    for x, y in _trajectory_xy:
        cv2.circle(img, w2p(x, y), walk_radius_px, COLOR_WALKABLE, -1, cv2.LINE_AA)
    # 最终 PNG 只保留当前多帧确认人物，不使用分割历史残影
    person_points = [
        w2p(tr["xw"], tr["yw"]) for tr in _person_tracks
        if tr["age"] >= 5 and tr["misses"] <= 3
        and x_min <= tr["xw"] <= x_max and y_min <= tr["yw"] <= y_max
    ]

    # 轨迹
    pts = np.array([w2p(x, y) for x, y in _trajectory_xy], dtype=np.int32).reshape(-1, 1, 2)
    cv2.polylines(img, [pts], False, (255, 200, 0), 3, cv2.LINE_AA)
    cv2.circle(img, w2p(_trajectory_xy[0][0], _trajectory_xy[0][1]), 8, (200, 200, 200), -1)
    cv2.circle(img, w2p(_trajectory_xy[-1][0], _trajectory_xy[-1][1]), 8, (0, 200, 255), -1)
    # 人点最后绘制，保持黄色圆点清晰可见
    for center in person_points:
        cv2.circle(img, center, 9, (0, 0, 0), -1)
        cv2.circle(img, center, 6, COLOR_PERSON, -1)

    # 简洁图例：绿色可行、红色不可行、黄色人点
    legend = (("walkable", COLOR_WALKABLE), ("obstacle", COLOR_OBSTACLE),
              ("person", COLOR_PERSON))
    for i, (label, color) in enumerate(legend):
        y = 48 + i * 22
        cv2.circle(img, (18, y - 4), 6, color, -1)
        cv2.putText(img, label, (31, y), cv2.FONT_HERSHEY_SIMPLEX,
                    0.45, (235, 235, 235), 1, cv2.LINE_AA)

    # 网格 (每 5m)
    for x_m in np.arange(np.ceil(x_min / 5) * 5, x_max, 5):
        px = int((x_m - x_min) * pxpm)
        cv2.line(img, (px, 0), (px, H - 1), (55, 55, 55), 1)
    for y_m in np.arange(np.ceil(y_min / 5) * 5, y_max, 5):
        py = int(H - 1 - (y_m - y_min) * pxpm)
        cv2.line(img, (0, py), (W - 1, py), (55, 55, 55), 1)

    total_dist = float(np.sum(np.linalg.norm(np.diff(np.column_stack([xs, ys]), axis=0), axis=1)))
    cv2.putText(img, f"trajectory {total_dist:.1f} m,  {len(_trajectory_xy)} pose frames",
                (10, 25), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (220, 220, 220), 1, cv2.LINE_AA)

    png_path = os.path.join(out_dir, f"{video_stem}_worldmap.png")
    if not cv2.imwrite(png_path, img):
        raise OSError(f"无法写入 world map PNG: {png_path}")
    print(f"  saved world map: {png_path}")
    return png_path


def _compute_bev_v7_legacy(pred_mask, depth_disp):
    """v7 遗留版本，保留供对比。当前 compute_bev 使用 v8 world-grid。"""
    if BEV_DOWNSAMPLE > 1:
        depth_disp = depth_disp[::BEV_DOWNSAMPLE, ::BEV_DOWNSAMPLE]
        pred_mask  = pred_mask[::BEV_DOWNSAMPLE, ::BEV_DOWNSAMPLE]

    h, w = depth_disp.shape
    fx = w / (2.0 * np.tan(np.deg2rad(CAM_FOV_H_DEG) / 2.0))
    fy = fx
    cx, cy = w / 2.0, h / 2.0

    Z = _depth_to_metric(depth_disp, pred_mask)
    us = np.arange(w, dtype=np.float32)
    vs = np.arange(h, dtype=np.float32)
    uu, vv = np.meshgrid(us, vs)
    X = (uu - cx) * Z / fx
    Y = (vv - cy) * Z / fy

    types = _CLS_LUT[pred_mask.astype(np.uint8)]  # 0=obst, 1=walk, 2=person

    bev_x = ((X + BEV_RANGE_X_M) / (2.0 * BEV_RANGE_X_M) * BEV_SIZE).astype(np.int32)
    bev_z = (BEV_SIZE - 1 - (Z / BEV_RANGE_Z_M) * BEV_SIZE).astype(np.int32)
    valid = (
        (bev_x >= 0) & (bev_x < BEV_SIZE) &
        (bev_z >= 0) & (bev_z < BEV_SIZE) &
        (Z > 0.1) & (Y > KEEP_BELOW_Y_M)
    )

    # ============================================================
    # V3: Ray-cast BEV，替换点云 votes
    # ------------------------------------------------------------
    # 对每图像列 u：
    #   - 找该列最近的 obstacle 深度 z_obst → BEV 上 (X, Z) 一个 point
    #   - 该列所有 z < z_obst 的 walkable 像素 → BEV walkable
    #   - person 独立累积（不阻挡 free space）
    # 相比投票法，obstacle 只画"每方位第一堵墙"，避免整堵墙糊成大色块。
    # ============================================================
    is_obst   = (types == 0) & (Z > 0.1) & (Z < BEV_RANGE_Z_M) & (Y > KEEP_BELOW_Y_M)
    is_walk   = (types == 1) & (Z > 0.1) & (Z < BEV_RANGE_Z_M) & (Y > KEEP_BELOW_Y_M)
    is_person = (types == 2) & (Z > 0.1) & (Z < BEV_RANGE_Z_M) & (Y > KEEP_BELOW_Y_M)

    votes_walk     = np.zeros((BEV_SIZE, BEV_SIZE), dtype=np.float32)
    votes_obstacle = np.zeros((BEV_SIZE, BEV_SIZE), dtype=np.float32)
    votes_person   = np.zeros((BEV_SIZE, BEV_SIZE), dtype=np.float32)

    # 每列 obstacle 最近深度（向量化）
    Z_masked_obst = np.where(is_obst, Z, np.inf)     # (H, W)
    z_obst_per_col = Z_masked_obst.min(axis=0)       # (W,)  ; inf 表示该列无 obstacle

    # 每列 obstacle 击中点 → BEV 一个像素，然后膨胀成"墙带"
    us_valid = np.where(np.isfinite(z_obst_per_col))[0]
    if us_valid.size > 0:
        z_hit = z_obst_per_col[us_valid]
        x_hit = (us_valid.astype(np.float32) - cx) * z_hit / fx
        in_x = np.abs(x_hit) <= BEV_RANGE_X_M
        z_hit = z_hit[in_x]; x_hit = x_hit[in_x]
        bev_hit_x = ((x_hit + BEV_RANGE_X_M) / (2.0 * BEV_RANGE_X_M) * BEV_SIZE).astype(np.int32)
        bev_hit_z = (BEV_SIZE - 1 - z_hit / BEV_RANGE_Z_M * BEV_SIZE).astype(np.int32)
        clip = (bev_hit_x >= 0) & (bev_hit_x < BEV_SIZE) & (bev_hit_z >= 0) & (bev_hit_z < BEV_SIZE)
        # 单独 grid 打点后膨胀，让稀疏 hit 成为可见的墙带
        hit_grid = np.zeros((BEV_SIZE, BEV_SIZE), dtype=np.float32)
        hit_grid[bev_hit_z[clip], bev_hit_x[clip]] = 1.0
        hit_dilated = cv2.dilate(hit_grid, np.ones((5, 5), np.uint8), iterations=2)
        votes_obstacle += hit_dilated * VOTE_W_OBSTACLE * 8.0

    # 额外：在 obstacle first-hit 附近 ±0.6m 范围内的 obstacle 像素也算 votes
    # → 给墙"厚度"，处理墙不是一条完美线的情况
    z_obst_near = z_obst_per_col[None, :]
    obst_thick = is_obst & (Z < z_obst_near * 1.15) & (Z > z_obst_near * 0.85)
    if obst_thick.any():
        bx_t = bev_x[obst_thick]
        bz_t = bev_z[obst_thick]
        clip = (bx_t >= 0) & (bx_t < BEV_SIZE) & (bz_t >= 0) & (bz_t < BEV_SIZE)
        np.add.at(votes_obstacle, (bz_t[clip], bx_t[clip]), VOTE_W_OBSTACLE * 1.5)

    # Walkable：只保留 z < 该列 obstacle 命中距离（避免 obstacle 后面的地面误算）
    # 若该列无 obstacle，所有 walkable 都算
    z_obst_broadcast = z_obst_per_col[None, :]   # (1, W)
    walk_before_obst = is_walk & (Z < z_obst_broadcast * 0.98)  # 微 margin 避免边界
    if walk_before_obst.any():
        bx_walk = bev_x[walk_before_obst]
        bz_walk = bev_z[walk_before_obst]
        clip = (bx_walk >= 0) & (bx_walk < BEV_SIZE) & (bz_walk >= 0) & (bz_walk < BEV_SIZE)
        np.add.at(votes_walk, (bz_walk[clip], bx_walk[clip]), VOTE_W_WALKABLE)

    # Person：允许穿透（人不完全遮挡），累积所有 person 点
    if is_person.any():
        bx_p = bev_x[is_person]
        bz_p = bev_z[is_person]
        clip = (bx_p >= 0) & (bx_p < BEV_SIZE) & (bz_p >= 0) & (bz_p < BEV_SIZE)
        np.add.at(votes_person, (bz_p[clip], bx_p[clip]), VOTE_W_PERSON)

    # 深度边缘增强：Canny 深度不连续 → 强化 obstacle 边界
    depth_u8 = ((Z - Z.min()) / (Z.max() - Z.min() + 1e-6) * 255).astype(np.uint8)
    depth_edges = cv2.Canny(depth_u8, 30, 80)
    edge_mask = (depth_edges > 0) & is_obst
    if edge_mask.any():
        bx_e = bev_x[edge_mask]
        bz_e = bev_z[edge_mask]
        clip = (bx_e >= 0) & (bx_e < BEV_SIZE) & (bz_e >= 0) & (bz_e < BEV_SIZE)
        np.add.at(votes_obstacle, (bz_e[clip], bx_e[clip]), VOTE_W_OBSTACLE * 2.0)

    # 时序 EMA + Pose-aware warping
    global _bev_votes_ema, _prev_pose_R, _prev_pose_t
    current = np.stack([votes_walk, votes_obstacle, votes_person], axis=-1)

    # 若前后帧都有 pose，把 _bev_votes_ema 从上一相机系 warp 到当前相机系
    if (_bev_votes_ema is not None
        and _prev_pose_R is not None and _current_pose_R is not None):
        M = _pose_to_bev_affine(_prev_pose_R, _prev_pose_t,
                                _current_pose_R, _current_pose_t)
        _bev_votes_ema = cv2.warpAffine(
            _bev_votes_ema, M, (BEV_SIZE, BEV_SIZE),
            flags=cv2.INTER_LINEAR,
            borderMode=cv2.BORDER_CONSTANT, borderValue=0,
        )

    if _bev_votes_ema is None or _bev_votes_ema.shape != current.shape:
        _bev_votes_ema = current
    else:
        # Pose-aware 时更慢的 EMA 让墙线在世界系持久累积
        alpha = BEV_EMA_ALPHA if _current_pose_R is None else 0.08
        _bev_votes_ema = (alpha * current + (1.0 - alpha) * _bev_votes_ema)
    smoothed = _bev_votes_ema

    # 更新 pose_prev 供下一帧用
    _prev_pose_R = _current_pose_R
    _prev_pose_t = _current_pose_t

    # v7: 滞后阈值 — 显示需连续 >= HIGH_TH，隐藏需 < LOW_TH，避免临界闪烁
    global _bev_hits_count
    scores = smoothed.sum(axis=-1)
    has_any_raw = scores > 0.35
    if _bev_hits_count is None or _bev_hits_count.shape != scores.shape:
        _bev_hits_count = has_any_raw.astype(np.int8)
    else:
        _bev_hits_count = np.where(has_any_raw,
                                   np.minimum(_bev_hits_count + 1, 6),
                                   np.maximum(_bev_hits_count - 1, 0)).astype(np.int8)
    has_any = _bev_hits_count >= 2   # 需连续 2 帧命中才显示
    main = smoothed.argmax(axis=-1).astype(np.uint8)   # 0/1/2

    # 形态学闭运算让通道连成片（先 dilate 后 erode）
    if WALKABLE_CLOSE_KSIZE >= 3:
        walk_mask = ((main == 0) & has_any).astype(np.uint8)
        k = np.ones((WALKABLE_CLOSE_KSIZE, WALKABLE_CLOSE_KSIZE), np.uint8)
        walk_mask = cv2.morphologyEx(walk_mask, cv2.MORPH_CLOSE, k)
        # 闭运算扩展出的格子也算 walkable（但不覆盖已存在的 person）
        gained = (walk_mask > 0) & (main != 2)
        main = np.where(gained, 0, main)
        has_any = has_any | gained

    bev = _BEV_BG.copy()
    palette = np.array([COLOR_WALKABLE, COLOR_OBSTACLE, COLOR_PERSON], dtype=np.uint8)
    color_layer = palette[main]
    bev[has_any] = color_layer[has_any]

    # 各类边界轮廓，让形状更易读
    _draw_bev_contours(bev, main, has_any)

    # 行人跨帧跟踪 + pose-warp 平滑
    _update_and_draw_person_tracks(bev, pred_mask, depth_disp)

    cx_b, cy_b = BEV_SIZE // 2, BEV_SIZE - 5
    cv2.circle(bev, (cx_b, cy_b), 4, (255, 255, 255), -1)
    cv2.putText(bev, f"BEV {BEV_RANGE_Z_M:.0f}m fwd  {2*BEV_RANGE_X_M:.0f}m wide",
                (6, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.40, (220, 220, 220), 1, cv2.LINE_AA)
    return bev


def _draw_bev_contours(bev, main, has_any):
    """为每类 argmax 区域画边界轮廓（walkable/obstacle/person），提升可读性。

    v7:
      - obstacle 用更粗轮廓 (2px) + 更大 min_area, 避免碎片飞舞
      - obstacle 先 open 后 close 去噪, 得到更连贯的墙体形状
      - walkable 保持细线, person 由圆圈另外画
    """
    for cls_id, contour_color, thick, min_area, use_open in [
        (0, (255, 255, 255), 1, 12, False),   # walkable
        (1, (255, 255, 255), 2, 20, True),    # obstacle: 粗轮廓 + 去碎片
        (2, (255, 255, 255), 1, 4,  False),   # person
    ]:
        mask = ((main == cls_id) & has_any).astype(np.uint8) * 255
        if not mask.any():
            continue
        if use_open:
            mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for c in contours:
            if cv2.contourArea(c) >= min_area:
                cv2.drawContours(bev, [c], -1, contour_color, thick, cv2.LINE_AA)


def _update_and_draw_person_tracks(bev, pred_mask, depth_disp,
                                    min_area_px=120, base_radius=6,
                                    assoc_dist_m=2.0, ema_alpha=0.22,
                                    max_misses=6, min_show_age=5):
    """v8: person tracks 存世界坐标 (xw, zw)，渲染时投到当前相机 mini-map。

    结果：稳定的黄圈，只显示"在多帧中持续存在"的 track，闪一帧的假阳性会被吞掉。
    """
    global _person_tracks, _next_person_id

    _, w = pred_mask.shape
    person_mask = np.isin(pred_mask, list(PERSON_IDS)).astype(np.uint8)

    # ---- 当前帧检测（在相机 ego 系，然后转世界系）----
    dets = []          # list of (xw, zw, z_dist)
    if person_mask.any() and _current_pose_R is not None and _current_pose_t is not None:
        num, labels, stats, centroids = cv2.connectedComponentsWithStats(person_mask, connectivity=8)
        if num > 1:
            fx = w / (2.0 * np.tan(np.deg2rad(CAM_FOV_H_DEG) / 2.0))
            cx = w / 2.0
            _, h_pm = pred_mask.shape[0], pred_mask.shape[0]  # placeholder
            h = pred_mask.shape[0]
            fy = fx
            cy = h / 2.0
            Z = _depth_to_metric(depth_disp, pred_mask)
            # 与 world grid 使用完全相同的稳定旋转，避免人物左右/前后翻转
            R = _stable_cam_to_world_R()
            t = _current_pose_t
            for i in range(1, num):
                area = int(stats[i, cv2.CC_STAT_AREA])
                if area < min_area_px:
                    continue
                bw = int(stats[i, cv2.CC_STAT_WIDTH])
                bh = int(stats[i, cv2.CC_STAT_HEIGHT])
                # v10: 排除自己的手 —— 手往往矮胖 (aspect < 1) 或极近 (z < 1.2m)
                if bh < 1.3 * bw:
                    continue                        # 人应该竖高 (height > 1.3 * width)
                if bh < 40:
                    continue                        # 太小的块（远处噪声或碎手）
                cu = float(centroids[i, 0])
                cv_ = float(centroids[i, 1])
                z_region = Z[labels == i]
                if z_region.size == 0:
                    continue
                z_med = float(np.median(z_region))
                if z_med < 1.2:
                    continue                        # 排除自己的手（贴近相机）
                if z_med > BEV_RANGE_Z_M:
                    continue
                X_cam = (cu - cx) * z_med / fx
                Y_cam = (cv_ - cy) * z_med / fy
                Z_cam = z_med
                p_w = R @ np.array([X_cam, Y_cam, Z_cam], dtype=np.float32) + t
                # v11: 世界 Z 是重力方向；人身高度 Z 相对相机应在 [-2.5, +2.5]m 内
                if abs(float(p_w[2]) - float(t[2])) > 2.5:
                    continue
                dets.append((float(p_w[0]), float(p_w[1]), z_med))

    # 同一人的分割可能裂成多个连通块；关联前先按世界距离合并
    merged_dets = []
    for xw, yw, z_m in dets:
        merge_idx = next((i for i, d in enumerate(merged_dets)
                          if ((d[0] - xw) ** 2 + (d[1] - yw) ** 2) ** 0.5 < 1.0),
                         None)
        if merge_idx is None:
            merged_dets.append([xw, yw, z_m, 1])
        else:
            d = merged_dets[merge_idx]
            n = d[3] + 1
            d[0] = (d[0] * d[3] + xw) / n
            d[1] = (d[1] * d[3] + yw) / n
            d[2] = min(d[2], z_m)
            d[3] = n
    dets = [(d[0], d[1], d[2]) for d in merged_dets]

    # ---- 关联：贪心最近邻（世界系米，水平面 XY）----
    matched_det = set()
    for tr in _person_tracks:
        best_d = 1e9; best_j = -1
        for j, (xw, yw, _z) in enumerate(dets):
            if j in matched_det:
                continue
            d = ((tr["xw"] - xw) ** 2 + (tr["yw"] - yw) ** 2) ** 0.5
            if d < assoc_dist_m and d < best_d:
                best_d = d; best_j = j
        if best_j >= 0:
            xw, yw, z_m = dets[best_j]
            tr["xw"] = ema_alpha * xw + (1 - ema_alpha) * tr["xw"]
            tr["yw"] = ema_alpha * yw + (1 - ema_alpha) * tr["yw"]
            tr["z_dist"] = z_m
            tr["misses"] = 0
            tr["age"] += 1
            matched_det.add(best_j)
        else:
            tr["misses"] += 1

    _person_tracks[:] = [tr for tr in _person_tracks if tr["misses"] <= max_misses]

    for j, (xw, yw, z_m) in enumerate(dets):
        if j not in matched_det:
            _person_tracks.append({
                "id": _next_person_id, "xw": xw, "yw": yw,
                "z_dist": z_m, "misses": 0, "age": 1,
            })
            _next_person_id += 1

    # 合并重复 track：保留年龄更大的主 track，避免单人显示多个黄点
    _person_tracks.sort(key=lambda tr: (-tr["age"], tr["misses"]))
    deduped_tracks = []
    for tr in _person_tracks:
        duplicate = next((keep for keep in deduped_tracks
                          if ((keep["xw"] - tr["xw"]) ** 2 +
                              (keep["yw"] - tr["yw"]) ** 2) ** 0.5 < 1.2),
                         None)
        if duplicate is None:
            deduped_tracks.append(tr)
        elif tr["misses"] < duplicate["misses"]:
            duplicate["xw"] = 0.7 * duplicate["xw"] + 0.3 * tr["xw"]
            duplicate["yw"] = 0.7 * duplicate["yw"] + 0.3 * tr["yw"]
            duplicate["misses"] = tr["misses"]
    _person_tracks[:] = deduped_tracks

    # ---- 绘制：把世界坐标 track 投影到当前 mini-map ----
    if _current_pose_R is None or _current_pose_t is None:
        return
    R_map = _stable_cam_to_world_R()
    yaw = float(np.arctan2(R_map[1, 2], R_map[0, 2]))
    cos_y = float(np.cos(yaw)); sin_y = float(np.sin(yaw))
    cam_wx = float(_current_pose_t[0]); cam_wy = float(_current_pose_t[1])
    m_per_bev_px = BEV_RANGE_Z_M / BEV_SIZE
    cx_b = BEV_SIZE / 2.0
    cy_b = BEV_SIZE - 5.0
    for tr in _person_tracks:
        if tr["age"] < min_show_age:
            continue
        # world → camera-local (right, forward)
        # cam-forward in world (horizontal) = (cos yaw, sin yaw)
        # cam-right   in world (horizontal) = (sin yaw, -cos yaw)   (俯视下, 前向 90° CW)
        dx = tr["xw"] - cam_wx
        dy = tr["yw"] - cam_wy
        cr =  dx * sin_y - dy * cos_y   # 投影到 cam-right
        cf =  dx * cos_y + dy * sin_y   # 投影到 cam-forward
        # to BEV pixel (相机在底部中心, forward = 向上)
        bx = cx_b + cr / m_per_bev_px
        by = cy_b - cf / m_per_bev_px
        bx_i, by_i = int(bx), int(by)
        if not (0 <= bx_i < BEV_SIZE and 0 <= by_i < BEV_SIZE):
            continue
        z_dist = tr.get("z_dist", BEV_RANGE_Z_M / 2)
        r = int(base_radius + max(0, (BEV_RANGE_Z_M - z_dist) * 0.35))
        cv2.circle(bev, (bx_i, by_i), r + 2, (0, 0, 0), -1)
        cv2.circle(bev, (bx_i, by_i), r,     COLOR_PERSON, -1)


def composite_bev(overlay_frame, bev_img):
    """把 BEV 小窗合成到原画面右下角，外加白色边框。"""
    h, w = overlay_frame.shape[:2]
    bh, bw = bev_img.shape[:2]
    x1 = w - bw - BEV_MARGIN
    y1 = h - bh - BEV_MARGIN
    overlay_frame[y1:y1 + bh, x1:x1 + bw] = bev_img
    cv2.rectangle(overlay_frame, (x1 - 1, y1 - 1), (x1 + bw, y1 + bh),
                  (255, 255, 255), 1)
    return overlay_frame


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
                   resize_to=None, frame_timestamps=None):
    cap.set(cv2.CAP_PROP_POS_FRAMES, start_frame)
    total = max(0, end_frame - start_frame)
    user_quit = False
    for i in range(total):
        ret, frame = cap.read()
        if not ret:
            break
        if resize_to is not None:
            frame = cv2.resize(frame, resize_to, interpolation=cv2.INTER_AREA)
        if FRAME_ROTATE_CCW_90:
            frame = cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
        if (i + 1) % 50 == 0:
            print(f"  {progress_prefix}处理第 {i+1}/{total} 帧")

        # 设置当前帧 pose（若可用）
        if frame_timestamps is not None:
            fi = start_frame + i
            if 0 <= fi < len(frame_timestamps):
                set_current_pose_by_ts(int(frame_timestamps[fi]))
            else:
                set_current_pose_by_ts(-1)  # 无匹配 → 清空

        pred = segment_frame(frame)
        depth = estimate_depth(frame)
        overlay = colorize(frame, pred)
        bev = compute_bev(pred, depth)
        composite_bev(overlay, bev)
        # v5f: 世界系持久地图 (左下)
        world_map = render_world_overview()
        wh, ww = world_map.shape[:2]
        wx1 = BEV_MARGIN
        wy1 = overlay.shape[0] - wh - BEV_MARGIN
        overlay[wy1:wy1 + wh, wx1:wx1 + ww] = world_map
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
    reset_bev_state()

    # 尝试加载同目录的 frames.csv 拿每帧 timestamp
    frame_timestamps = None
    session_dir = os.path.dirname(input_path)
    csv_path = os.path.join(session_dir, "frames.csv")
    if os.path.exists(csv_path):
        arr = np.loadtxt(csv_path, delimiter=",", skiprows=1, dtype=np.int64)
        frame_timestamps = arr[:, 5]  # elapsed_realtime_ns
        print(f"  loaded frame_timestamps: {len(frame_timestamps)}")

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

    if FRAME_ROTATE_CCW_90:
        out_w, out_h = out_h, out_w   # swap for CCW 90 output

    print(f"  帧率: {fps}, 输入: {width}x{height} → 输出: {out_w}x{out_h}, 总帧数: {total_frames}"
          f"{' (rot CCW 90)' if FRAME_ROTATE_CCW_90 else ''}")
    print(f"  输出: {output_path}")

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    writer = cv2.VideoWriter(output_path, fourcc, fps, (out_w, out_h))
    if not writer.isOpened():
        cap.release()
        raise OSError(f"无法创建输出视频: {output_path}")

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
                              progress_prefix=f"{label} ", resize_to=resize_to,
                              frame_timestamps=frame_timestamps):
                user_quit = True
                break
    else:
        user_quit = _process_range(cap, writer, 0, total_frames, label=None,
                                   resize_to=resize_to,
                                   frame_timestamps=frame_timestamps)

    cap.release()
    writer.release()
    if SHOW_PREVIEW:
        cv2.destroyAllWindows()
    # v5f: 保存最终世界地图 + 轨迹 PNG
    try:
        out_dir = os.path.dirname(output_path)
        stem = os.path.splitext(os.path.basename(output_path))[0]
        save_world_map_png(out_dir, stem)
    except Exception as e:
        print(f"  world map PNG 保存失败: {e}")
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
    load_pose_stream()   # 加载 COLMAP 插值 pose
    # 关掉抽样模式，跑完整视频（60s）
    global SAMPLE_MODE
    SAMPLE_MODE = False
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
