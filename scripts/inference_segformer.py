"""
基于 SegFormer-Cityscapes 的批处理推理脚本。

零标注思路：直接使用 HuggingFace 上 NVIDIA 在 Cityscapes 上预训练的 SegFormer，
将 19 类城市街景语义分割结果按"可行 / 人 / 障碍"三大类着色叠加到原视频上。

输入/输出与 inference_batch.py 保持一致：
  H:\\ForPengfei\\04142024\\Front\\{1,2,3}\\*.mp4
  → H:\\ForPengfei\\04142024\\Front\\output\\{1,2,3}\\{name}_segformer.mp4

首次运行会从 HuggingFace 自动下载约 100MB 模型权重，缓存在 ~/.cache/huggingface。
"""

import os

# 国内访问 huggingface.co 通常超时，走镜像。必须在 import transformers 之前设置。
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "60")

import cv2
import torch
import numpy as np
from PIL import Image
from transformers import SegformerImageProcessor, SegformerForSemanticSegmentation

DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'

# ---- 数据集 / 模型选择 -------------------------------------------------
# Cityscapes: 户外街景专长，无室内类别（商场地面会被错分到 road/building）
# ADE20K    : 150 类室内外通用（含 floor / road / sidewalk / earth / path /
#             stairs / escalator / grass / sand / runway / person 等），
#             商场+柏油马路+住宅混合场景的更优解
DATASET = "ade20k"                    # "cityscapes" | "ade20k"

# ---- 推理加速开关 -------------------------------------------------------
USE_FP16 = True                       # 半精度推理 (~2x 提速，仅在 CUDA 下生效)
INFER_LONG_SIDE = 720                 # 输入长边像素

# ---- 类别映射表（按 dataset 切换） --------------------------------------
# Cityscapes 19 类参考：
#   0 road, 1 sidewalk, 2 building, 3 wall, 4 fence, 5 pole, 6 traffic light,
#   7 traffic sign, 8 vegetation, 9 terrain, 10 sky, 11 person, 12 rider,
#   13 car, 14 truck, 15 bus, 16 train, 17 motorcycle, 18 bicycle
# ADE20K 150 类，从模型 config.id2label 中提取（见下方 ADE20K_WALKABLE）
_DATASET_CONFIG = {
    "cityscapes": {
        "model": "nvidia/segformer-b2-finetuned-cityscapes-1024-1024",
        "walkable": {0, 1, 9},             # road / sidewalk / terrain
        "person":   {11, 12},              # person / rider
        "output_suffix":        "_segformer_cs",
        "sample_output_suffix": "_segformer_cs_sample",
    },
    "ade20k": {
        # ADE20K 模型已手动下载至本地（hf-mirror etag 校验问题），用本地路径
        "model": os.path.join(os.path.dirname(os.path.abspath(__file__)),
                              "..", "models", "segformer-b2-ade20k"),
        "walkable": {
            3,   # floor
            6,   # road
            9,   # grass
            11,  # sidewalk
            13,  # earth
            28,  # rug
            29,  # field
            46,  # sand
            52,  # path
            53,  # stairs
            54,  # runway
            59,  # stairway
            96,  # escalator
        },
        "person": {12},                    # person
        "output_suffix":        "_segformer_ade",
        "sample_output_suffix": "_segformer_ade_sample",
    },
}
_cfg = _DATASET_CONFIG[DATASET]
MODEL_NAME     = _cfg["model"]
WALKABLE_IDS   = _cfg["walkable"]
PERSON_IDS     = _cfg["person"]

print(f"[Init] DATASET={DATASET}  加载模型 {MODEL_NAME} ...")
processor = SegformerImageProcessor.from_pretrained(MODEL_NAME)
seg_model = SegformerForSemanticSegmentation.from_pretrained(MODEL_NAME).to(DEVICE).eval()
if USE_FP16 and DEVICE == 'cuda':
    seg_model = seg_model.half()
print(f"[Init] 完成，DEVICE={DEVICE}, fp16={USE_FP16 and DEVICE=='cuda'}, long_side={INFER_LONG_SIDE}")

COLOR_WALKABLE = (0, 255, 0)         # 绿
COLOR_PERSON   = (0, 255, 255)       # 黄 (BGR)
COLOR_OBSTACLE = (0, 0, 255)         # 红

ALPHA_WALKABLE = 0.35
ALPHA_OBSTACLE = 0.20
ALPHA_PERSON   = 0.55                # 人物用更高不透明度，醒目

# ---- 批处理配置 ---------------------------------------------------------
INPUT_ROOT  = r"H:\ForPengfei\04142024\Front"
OUTPUT_ROOT = r"H:\ForPengfei\04142024\Front\output"
SUBDIRS = ["1", "2", "3"]
OUTPUT_SUFFIX        = _cfg["output_suffix"]
SAMPLE_OUTPUT_SUFFIX = _cfg["sample_output_suffix"]
SHOW_PREVIEW = False
SKIP_EXISTING = True

# ---- 抽样模式 -----------------------------------------------------------
# 开启后，每个视频只处理 begin / mid / end 三段，每段 SAMPLE_SECONDS 秒，
# 拼接到同一个输出文件并在每段开头插入文字标签，用于快速肉眼评估。
SAMPLE_MODE = True
SAMPLE_SECONDS = 30


@torch.no_grad()
def segment_frame(frame_bgr):
    """返回与原帧同分辨率的类别索引 mask (H, W) int64。"""
    h, w = frame_bgr.shape[:2]
    rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)

    # 等比缩放到目标长边，processor 内部还会再 normalize
    scale = INFER_LONG_SIDE / max(h, w)
    if scale < 1.0:
        new_w = int(round(w * scale))
        new_h = int(round(h * scale))
        rgb_small = cv2.resize(rgb, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    else:
        rgb_small = rgb

    inputs = processor(images=Image.fromarray(rgb_small), return_tensors="pt").to(DEVICE)
    if USE_FP16 and DEVICE == 'cuda':
        inputs = {k: (v.half() if v.dtype == torch.float32 else v) for k, v in inputs.items()}
    logits = seg_model(**inputs).logits  # (1, num_classes, H/4, W/4)

    # 直接上采样到原图尺寸
    upsampled = torch.nn.functional.interpolate(
        logits, size=(h, w), mode="bilinear", align_corners=False
    )
    pred = upsampled.argmax(dim=1)[0].cpu().numpy().astype(np.int32)
    return pred


def colorize(frame_bgr, pred_mask):
    """根据三组类别在 BGR 帧上叠加半透明颜色。"""
    out = frame_bgr.copy()

    walkable_mask = np.isin(pred_mask, list(WALKABLE_IDS))
    person_mask   = np.isin(pred_mask, list(PERSON_IDS))
    obstacle_mask = ~(walkable_mask | person_mask)

    # 分三层独立 alpha 叠加，避免一刀切
    for region, color, alpha in (
        (walkable_mask, COLOR_WALKABLE, ALPHA_WALKABLE),
        (obstacle_mask, COLOR_OBSTACLE, ALPHA_OBSTACLE),
        (person_mask,   COLOR_PERSON,   ALPHA_PERSON),
    ):
        if not region.any():
            continue
        layer = np.zeros_like(out)
        layer[region] = color
        # 仅在该 region 处做加权混合
        idx = region
        out[idx] = (out[idx].astype(np.float32) * (1 - alpha)
                    + np.array(color, dtype=np.float32) * alpha).astype(np.uint8)
    return out


def _annotate_label(frame, text):
    """在帧左上角绘制大号黄色标签（用于抽样段开头）。"""
    annotated = frame.copy()
    cv2.rectangle(annotated, (0, 0), (520, 70), (0, 0, 0), -1)
    cv2.putText(annotated, text, (15, 50),
                cv2.FONT_HERSHEY_SIMPLEX, 1.4, (0, 255, 255), 3)
    return annotated


def _process_range(cap, writer, start_frame, end_frame, label=None, progress_prefix=""):
    """处理 [start_frame, end_frame) 区间内的帧。label 非空则前 12 帧叠加标签。"""
    cap.set(cv2.CAP_PROP_POS_FRAMES, start_frame)
    total = max(0, end_frame - start_frame)
    user_quit = False
    for i in range(total):
        ret, frame = cap.read()
        if not ret:
            break
        if (i + 1) % 50 == 0:
            print(f"  {progress_prefix}处理第 {i+1}/{total} 帧")

        pred = segment_frame(frame)
        overlay = colorize(frame, pred)
        if label and i < 12:
            overlay = _annotate_label(overlay, label)

        writer.write(overlay)

        if SHOW_PREVIEW:
            cv2.namedWindow("AR Navigation", cv2.WINDOW_NORMAL)
            cv2.resizeWindow("AR Navigation", 960, 540)
            cv2.imshow("AR Navigation", overlay)
            if (cv2.waitKey(1) & 0xFF) == ord('q'):
                print("  用户按q退出")
                user_quit = True
                break
    return user_quit


def process_video(input_path, output_path):
    cap = cv2.VideoCapture(input_path)
    if not cap.isOpened():
        print(f"  Error: 无法打开视频文件 {input_path}")
        return True

    fps = cap.get(cv2.CAP_PROP_FPS) or 24.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    print(f"  帧率: {fps}, 尺寸: {width}x{height}, 总帧数: {total_frames}")
    print(f"  输出: {output_path}")

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    writer = cv2.VideoWriter(output_path, fourcc, fps, (width, height))

    user_quit = False
    if SAMPLE_MODE:
        seg_len = int(round(SAMPLE_SECONDS * fps))
        # 三段起始帧；若视频过短则收缩/合并
        seg_len = min(seg_len, max(1, total_frames // 3))
        ranges = [
            ("BEGIN", 0, seg_len),
            ("MID",   max(0, total_frames // 2 - seg_len // 2),
                       min(total_frames, total_frames // 2 - seg_len // 2 + seg_len)),
            ("END",   max(0, total_frames - seg_len), total_frames),
        ]
        for label, s, e in ranges:
            print(f"  [{label}] 帧区间 [{s}, {e})  共 {e - s} 帧")
            if _process_range(cap, writer, s, e, label=label, progress_prefix=f"{label} "):
                user_quit = True
                break
    else:
        user_quit = _process_range(cap, writer, 0, total_frames, label=None)

    cap.release()
    writer.release()
    if SHOW_PREVIEW:
        cv2.destroyAllWindows()
    print(f"  完成: {output_path}")
    return not user_quit


def collect_videos(root, subdirs):
    tasks = []
    for sub in subdirs:
        in_dir = os.path.join(root, sub)
        if not os.path.isdir(in_dir):
            print(f"跳过：输入目录不存在 {in_dir}")
            continue
        files = sorted(
            f for f in os.listdir(in_dir)
            if f.lower().endswith(".mp4")
            and not f.startswith("._")  # 跳过 macOS AppleDouble 元数据残留
            and os.path.isfile(os.path.join(in_dir, f))
        )
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
