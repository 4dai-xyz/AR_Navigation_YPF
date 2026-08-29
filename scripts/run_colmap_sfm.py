"""一键跑 COLMAP Sequential SfM，输出稀疏重建 + 相机轨迹。

流程：
  1. feature_extractor  — SIFT 特征 (CUDA)
  2. sequential_matcher — 时序邻近匹配（比 exhaustive 快得多）
  3. mapper             — 增量式 SfM
  4. model_converter    — 导出 TXT 便于 Python 读取

内参预估：IMX681 @ 1280x720，f≈16mm(35mm 等效)，水平 FOV≈95° → fx≈600
COLMAP 会在 BA 里 refine 这些参数，初值粗糙没关系。
"""
import os
import shutil
import subprocess
import sys
import time

COLMAP    = r"G:\ARProjects\colmap-x64-windows-cuda\bin\colmap.exe"
SESSION   = r"G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260710_154536_478"
WORK      = os.path.join(SESSION, "colmap")
IMAGES    = os.path.join(WORK, "images")
DB        = os.path.join(WORK, "database.db")
SPARSE    = os.path.join(WORK, "sparse")
SPARSE_TXT= os.path.join(WORK, "sparse_txt")

# 相机内参（初值；SIMPLE_RADIAL: f, cx, cy, k1）
CAMERA_MODEL  = "SIMPLE_RADIAL"
CAMERA_PARAMS = "600,640,360,0.0"

SEQ_OVERLAP    = 10   # 每帧和后 N 帧配对
QUADRATIC_OVER = 1    # 额外加倍匹配范围（对循环场景有帮助）


def run(cmd, tag):
    print(f"\n===== {tag} =====")
    print("$", " ".join(f'"{c}"' if " " in c else c for c in cmd))
    t0 = time.time()
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    dt = time.time() - t0
    # 打印最后 40 行防止刷屏
    lines = r.stdout.splitlines() if r.stdout else []
    for l in lines[-40:]:
        print(l)
    print(f"[{tag}] exit={r.returncode}  elapsed={dt:.1f}s")
    if r.returncode != 0:
        sys.exit(f"COLMAP step failed: {tag}")


def main():
    if not os.path.isdir(IMAGES):
        sys.exit(f"images dir not found: {IMAGES}\n先跑 extract_frames_for_colmap.py")

    # 清理旧输出
    if os.path.exists(DB):
        os.remove(DB)
    for d in (SPARSE, SPARSE_TXT):
        if os.path.exists(d):
            shutil.rmtree(d)
    os.makedirs(SPARSE, exist_ok=True)
    os.makedirs(SPARSE_TXT, exist_ok=True)

    n_imgs = len([f for f in os.listdir(IMAGES) if f.lower().endswith(".jpg")])
    print(f"输入图像数: {n_imgs}")
    print(f"相机模型: {CAMERA_MODEL}  参数: {CAMERA_PARAMS}")

    # 1. Feature extraction
    run([
        COLMAP, "feature_extractor",
        "--database_path", DB,
        "--image_path", IMAGES,
        "--ImageReader.camera_model", CAMERA_MODEL,
        "--ImageReader.single_camera", "1",
        "--ImageReader.camera_params", CAMERA_PARAMS,
        "--FeatureExtraction.use_gpu", "1",
        "--SiftExtraction.max_num_features", "8192",
    ], "feature_extractor")

    # 2. Sequential matching
    run([
        COLMAP, "sequential_matcher",
        "--database_path", DB,
        "--SequentialMatching.overlap", str(SEQ_OVERLAP),
        "--SequentialMatching.quadratic_overlap", str(QUADRATIC_OVER),
        "--FeatureMatching.use_gpu", "1",
    ], "sequential_matcher")

    # 3. Mapper (稀疏重建)
    run([
        COLMAP, "mapper",
        "--database_path", DB,
        "--image_path", IMAGES,
        "--output_path", SPARSE,
    ], "mapper")

    # 4. Export TXT (可能有 0/, 1/, ... 多子模型；只导 0/)
    subs = sorted(os.listdir(SPARSE))
    if not subs:
        sys.exit("mapper 未产生任何 sparse 模型；重建失败")
    print(f"\nmapper 产生子模型: {subs}")
    src = os.path.join(SPARSE, subs[0])
    run([
        COLMAP, "model_converter",
        "--input_path", src,
        "--output_path", SPARSE_TXT,
        "--output_type", "TXT",
    ], "model_converter (→ TXT)")

    print("\n=========================")
    print("COLMAP SfM 完成")
    print(f"  database:  {DB}")
    print(f"  sparse:    {SPARSE}  (子模型: {subs})")
    print(f"  TXT 导出:  {SPARSE_TXT}   (images.txt / points3D.txt / cameras.txt)")
    print("下一步：跑 visualize_colmap.py 画轨迹 + 点云")


if __name__ == "__main__":
    main()
