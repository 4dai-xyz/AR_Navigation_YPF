# AR Navigation：视觉导航实验与眼镜端原型

本仓库是 AR 眼镜视觉导航工作的汇总入口，保存了两条互相衔接的主线：

1. **离线纯视觉导航实验**：从 Rokid 眼镜视频和 IMU session 出发，完成相机标定、COLMAP 稀疏重建、位姿时间对齐、尺度/重力校准、语义分割、单目深度和 BEV（俯视图）地图生成。
2. **VisionRoute 端到端原型**：将眼镜图传、Android 手机 App、PC 视觉定位后台、室内地图和 HUD 导航串起来，面向会场等室内场景演示。

仓库中的 `scripts/` 是快速实验和数据处理入口；较完整的模块化实现位于 `chx-main/` 和 `4dai-glasses-main/`。第三方算法源码、模型权重、原始视频和大体积中间结果通常不进入 Git，由独立 Release 或本地目录提供。

## 1. 总体数据流

### 1.1 离线视觉导航主线

```text
Rokid session
  ├─ video.mp4
  ├─ frames.csv
  └─ imu.jsonl
        │
        ├─ 质量诊断 / 相机内参 / IMU 噪声估计
        ├─ 抽帧 → COLMAP Sequential SfM → 稀疏相机轨迹
        ├─ 轨迹与视频 timestamp 对齐 → all_frames_pose.npz
        └─ IMU 重力与位移尺度校准 → all_frames_pose_metric.npz
                                      │
                                      ▼
                       SegFormer 语义分割 + Depth Anything V2
                                      │
                 动态行人过滤、深度反投影、地面约束、尺度对齐
                                      │
                                      ▼
                    世界坐标 / BEV 点云 / occupancy 导航栅格
                                      │
                     MP4 可视化 + JSON/NPZ/PNG 可复现结果
```

### 1.2 VisionRoute 产品原型主线

```text
Rokid 眼镜 HTTP 图传 / HUD
        │
        ▼
Android 手机 App ── 同 Wi-Fi ──► PC cloud/visual-locate
        │                              │
        │                              ├─ OCR / logo / landmark / scene recognition
        │                              └─ 返回当前位置与置信度
        ▼
会场室内地图、展台搜索、路径规划、模拟步行
        │
        └──────────────► Rokid HUD：当前位置、路线、下一步动作、距离/时间
```

离线 BEV 流程用于算法验证和数据分析；VisionRoute 流程用于设备联调和现场演示。二者共享视觉定位、动态目标和地图表达的设计，但不是同一个运行时进程。

## 2. 目录导航

| 路径 | 内容 |
| --- | --- |
| `scripts/` | 当前仓库的实验脚本：分割、深度、BEV、COLMAP、标定、session 转换和训练入口 |
| `chx-main/mall_visual_slam/` | DPVO、ORB-SLAM3、VGGT、KV-Track3r、people BEV 的模块化实验工程 |
| `chx-main/mall_visual_slam/src/people_bev_tracker/` | 行人检测/跟踪、脚点投影、静态地图、BEV 渲染和 Route A 流程 |
| `chx-main/mall_visual_slam/src/dpvo_localization/` | DPVO 视频轨迹封装与标定准备 |
| `chx-main/mall_visual_slam/launch/` | ROS2 视频发布、ORB-SLAM3 和 DPVO 相关 launch |
| `4dai-glasses-main/4dai-glasses-main/android/` | VisionRoute Android App、Rokid Bridge、真实 session 与现场输出 |
| `4dai-glasses-main/4dai-glasses-main/cloud/` | PC/FastAPI 视觉定位后台、场馆包和联调工具 |
| `ocr-and-dataset-main/` | OCR、logo/检测数据集和训练实验 |
| `artifacts/` | v14 Release 下载说明和 SHA-256 manifest |

进一步的模块说明见：

- `chx-main/docs/系统运行总说明.md`
- `chx-main/docs/src代码结构与数据流说明.md`
- `chx-main/docs/纯视觉SLAM商场导航项目完整知识库.md`
- `chx-main/mall_visual_slam/src/people_bev_tracker/README.md`
- `4dai-glasses-main/4dai-glasses-main/README.md`
- `4dai-glasses-main/4dai-glasses-main/android/README.md`
- `4dai-glasses-main/4dai-glasses-main/cloud/README.md`

## 3. `scripts/` 脚本索引

脚本默认值中仍保留了开发机路径；跨机器运行时优先修改文件顶部的输入/输出常量，或使用脚本已经提供的 session 参数。

### 3.1 Session、标定与格式转换

| 脚本 | 作用 | 主要输出 |
| --- | --- | --- |
| `scripts/diag_session.py` | 检查视频、`frames.csv`、IMU 时间戳覆盖、采样间隔、静止段和加速度统计 | 终端诊断报告 |
| `scripts/check_checkerboard.py` | 扫描标定视频并比较多种棋盘格内角点配置 | 检测率和预览图 |
| `scripts/calibrate_intrinsics.py` | OpenCV 棋盘格相机内参标定 | `intrinsics/*.yaml`、重投影误差图 |
| `scripts/estimate_imu_noise.py` | 用静止 session 估计加速度/陀螺仪噪声起点 | `imu_noise.yaml` |
| `scripts/session_to_euroc.py` | 将 `video.mp4 + frames.csv + imu.jsonl` 转为 EuRoC MAV 格式 | `euroc/mav0/` |
| `scripts/session_to_rosbag.py` | 将 session 转为 Kalibr 可读 rosbag1，按 gyro 时间插值加速度 | `kalibr.bag` |
| `scripts/extract.py` | 从普通视频按固定间隔抽帧 | `data/frames/` |

### 3.2 COLMAP 与位姿流

| 脚本 | 作用 | 主要输出 |
| --- | --- | --- |
| `scripts/extract_frames_for_colmap.py` | 从 Rokid session 按 stride 抽取 JPEG，并保存对应 timestamp | `<session>/colmap/images/`、`timestamps.txt` |
| `scripts/run_colmap_sfm.py` | 一键执行 SIFT 特征、Sequential Matcher、增量 SfM 和 TXT 导出 | `colmap/database.db`、`sparse_txt/` |
| `scripts/build_pose_stream.py` | 读取 COLMAP 相机轨迹，按视频时间戳插值到全帧 | `poses/all_frames_pose.npz` |
| `scripts/calibrate_colmap_imu.py` | 用静止段重力方向和运动段 IMU 位移估计世界方向与尺度 | `poses/all_frames_pose_metric.npz` |
| `scripts/visualize_colmap.py` | 绘制 COLMAP 相机轨迹、稀疏点云和俯视图 | `trajectory.png` |

位姿数组约定：`t_world_cam` 保存相机光心世界坐标，`R_world_cam` 保存相机到世界的旋转，`valid` 表示是否在 COLMAP 覆盖范围内，`registered` 表示是否为直接注册帧。后续 BEV 需要明确使用 `T_wc` 还是 `T_cw`，不能仅凭变量名猜测。

### 3.3 语义分割、深度与 BEV

| 脚本 | 作用 |
| --- | --- |
| `scripts/inference_segformer.py` | 使用 Hugging Face SegFormer（Cityscapes/ADE20K）做视频语义分割，将可行走区、行人和障碍物叠加到原视频 |
| `scripts/inference_batch.py` | 批量运行自定义 DeepLabV3 二分类可行走区域模型 |
| `scripts/inference_bev.py` | SegFormer + Depth Anything V2，单目深度反投影并生成局部 BEV 小窗 |
| `scripts/inference_bev_pose.py` | 在 BEV 基础上接入 COLMAP/IMU 校准后的全帧位姿，累积世界坐标地图和轨迹；当前 v14 主实验入口 |
| `scripts/_smoke_segformer.py` | 单帧 SegFormer 冒烟测试 |
| `scripts/_smoke_bev.py` | 抽取代表帧检查分割、深度、BEV 合成效果 |
| `scripts/_smoke_clahe.py` | 对暗场帧检查 CLAHE 增强前后差异 |
| `scripts/_compare_models.py` | 对比 Cityscapes 与 ADE20K SegFormer 的分割结果 |
| `scripts/_debug_person_bev.py` | 针对已知行人帧诊断脚点、深度和 BEV 投影 |
| `scripts/_kill_seg.py` | 终止残留的分割推理进程 |

核心几何流程如下：

1. SegFormer 将像素分成可行走、行人和障碍物语义类别。
2. Depth Anything V2 为每帧提供单目相对/近似 metric 深度。
3. 由相机内参将像素反投影为相机射线，再与地面平面求交。
4. 使用 COLMAP/DPVO 位姿将相机系点变换到世界系。
5. 通过 voxel/EMA 累积多帧观测，生成 BEV 点云、静态地图和导航栅格。
6. 行人作为动态 overlay 展示，不写入静态地图；行人脚点可单独记录到 JSON。

`inference_bev_pose.py` 当前包含旋转、CLAHE、B2/B4 模型切换、世界地图累积和姿态轨迹叠加等 v14 实验参数。它适合复现已保存的实验路径，不应直接视为通用命令行工具。

### 3.4 数据集、训练与 mask

| 脚本 | 作用 |
| --- | --- |
| `scripts/dataset.py` | 定义二分类语义分割 Dataset（可行走/不可行走） |
| `scripts/json2mask.py` | 将标注 JSON 转为二值 mask |
| `scripts/train_segmentation.py` | 训练 DeepLabV3/FCN 可行走区域模型 |
| `scripts/debug_segmentation.py` | 加载分割权重并检查预测可视化 |
| `scripts/train_detection.py` | 训练 YOLO 障碍物检测模型 |
| `scripts/train_logo.py` | 训练 YOLO logo 检测模型 |
| `scripts/inference.py` | 早期检测、logo、分割推理实验入口 |
| `scripts/duguo_train.py` | logo 分类模型过拟合/小数据训练实验 |
| `scripts/duguo_yanzheng.py` | 对 logo 分类模型做带随机裁剪抖动的验证 |

## 4. 推荐复现顺序

以下命令以 Windows PowerShell 为例。首先准备 Python、PyTorch、OpenCV、NumPy、Transformers、Ultralytics、Pillow、SciPy 等依赖；GPU 推理建议使用与 CUDA 匹配的 PyTorch。ROS2/ORB-SLAM3 依赖请按 `chx-main` 下的文档单独准备，不要把 ROS2 Python 环境和深度学习 conda 环境混用。

### 4.1 检查 session

```powershell
python scripts/diag_session.py `
  "G:\ARProjects\AR_Navigation\4dai-glasses-main\4dai-glasses-main\android\session_20260710_154536_478"
```

### 4.2 生成 COLMAP 位姿

```powershell
python scripts/extract_frames_for_colmap.py
python scripts/run_colmap_sfm.py
python scripts/build_pose_stream.py
python scripts/calibrate_colmap_imu.py
python scripts/visualize_colmap.py
```

`extract_frames_for_colmap.py`、`run_colmap_sfm.py`、`build_pose_stream.py` 和 `calibrate_colmap_imu.py` 当前主要通过文件顶部常量选择 session；换数据集时请先检查 `SESSION`、`STATIC_SESSION`、COLMAP 可执行文件路径和标定参数。

### 4.3 先做单帧/短片验证

```powershell
python scripts/_smoke_segformer.py
python scripts/_smoke_bev.py
```

确认模型加载、显存和投影方向正常后，再运行完整视频：

```powershell
python scripts/inference_bev_pose.py
```

输出通常写入 session 的 `output/` 子目录，文件名带有 `_bev_pose` 后缀。完整输入、模型权重和输出目录可能被 `.gitignore` 排除；不要将它们误认为代码缺失。

### 4.4 模块化 people BEV 流程

`chx-main/mall_visual_slam/src/people_bev_tracker/` 提供更易复用的离线流水线：

```bash
python src/people_bev_tracker/scripts/offline_pipeline.py \
  --video resources/input_video.mp4 \
  --calib config/KannalaBrandt8_1280x720.yaml \
  --pose output/dpvo/trajectory_tum.txt \
  --output-dir output/people_bev
```

主要输出包括 `bev_tracking.mp4`、`debug_overlay.mp4`、`people_tracks.json` 和 `camera_trajectory.json`。如果只是调整颜色、范围或是否绘制轨迹，使用 `render_bev_from_json.py` 重渲染，不必重新运行 YOLO。

## 5. 视觉导航关键设计

### 5.1 定位前端的取舍

- **DPVO**：作为单目视觉里程计主轨迹来源，适合离线视频和后续 BEV 对齐。
- **ORB-SLAM3**：作为传统特征法 SLAM 对照和 ROS2 在线节点，通过 `orbslam3_wrapper` 调用官方库。
- **COLMAP**：用于关键帧 SfM 和相机轨迹恢复，再插值到完整视频帧。
- **VGGT / KV-Track3r**：用于点云/位姿质量对比和论文路线复现，不是 v14 默认主轨迹。

单目系统存在尺度不确定性。当前工作流使用地面约束、相机高度、静止段重力和运动段 IMU 位移进行尺度与方向校准；如果需要绝对米制精度，仍应使用已知相机高度、地面尺寸、CAD 或更完整的视觉惯性标定进行复核。

### 5.2 行人和动态物体

YOLO/YOLO-seg + BoT-SORT/ByteTrack 输出跨帧 track；mask 或 bbox 的底部中位点作为脚点。脚点射线与地面相交后得到行人世界坐标，再投影到 BEV。动态行人只参与实时叠加和轨迹记录，避免把人写入静态导航地图。

### 5.3 BEV 坐标和地图

相机内参矩阵为：

```text
K = [[fx,  0, cx],
     [ 0, fy, cy],
     [ 0,  0,  1]]
```

像素通过 `K⁻¹` 反投影为相机射线，使用 `T_wc = [R | t]` 变换到世界系。BEV 通常选择世界 `x-z` 平面；画布像素的 y 轴向下，因此需要处理世界坐标方向与图像方向的镜像问题。多帧地图使用语义投票、log-odds/occupancy、体素聚合和 EMA 平滑减小单帧深度噪声。

已验证的工程经验：相机系地面约束对头戴相机通常比直接假设 DPVO 世界 `y=0` 更稳定，因为单目 SLAM 的第一帧世界轴不一定与重力对齐。

## 6. VisionRoute 端到端系统

`4dai-glasses-main/4dai-glasses-main/` 是面向真实设备的另一条主线：

- `android/ai-glasses-poc/`：手机 App、Rokid Bridge、图传、地图、路径和 HUD 状态。
- `cloud/app/`：FastAPI PC 后台和 `visual-locate` 接口。
- `cloud/data/`：会场样例、地标和场馆包。
- `android/docs/`、`cloud/docs/`、`docs/`：安装、联调、测试和现场迁移 runbook。

默认现场链路为：PC 与手机连接同一 Wi-Fi → 启动 PC 后台 → 安装 APK → 连接 Rokid Bridge → 上传眼镜图像 → PC 返回地标定位 → App 规划室内路线 → HUD 同步显示。高德室外导航、五道口历史地图、HeyCyan/USB 调试页和 manual demo 仍保留，但不是当前会场演示默认入口。

## 7. v14 复现资产

大文件已发布在 GitHub Release：

**[data-v14 Release](https://github.com/4dai-xyz/AR_Navigation_YPF/releases/tag/data-v14)**

Release 包含分片后的 OCR 数据、运行模型、源 session、源视频和 v14 成品视频，共 29 个资产。大于可靠上传窗口的文件拆成不超过约 90 MB 的 `.partNN` 文件；下载后按 `artifacts/README.md` 中的 PowerShell 函数合并，并使用：

```text
artifacts/data-v14-assets.sha256
```

进行 SHA-256 校验。Release 资产不纳入普通 Git clone，`artifacts/release_parts/` 也已加入忽略规则。

## 8. 已知限制与排障

- 根目录脚本仍有部分绝对路径和实验参数，换机器前必须检查文件顶部配置。
- 单目深度和 DPVO/传统 SfM 的尺度不是天然绝对米制；`all_frames_pose_metric.npz` 依赖校准数据质量。
- 当前部分 Kannala-Brandt/鱼眼标定仍按近似 pinhole 处理，未在所有路径真正反畸变。
- YOLO-seg 远距离小目标的 mask 较粗；遮挡严重时脚点会回退到 bbox 底边。
- `inference_bev*.py` 是离线视频处理脚本，不是低延迟在线 ROS 节点。
- ROS2 节点和 DPVO/Transformers 推理必须分开环境；混用 NumPy/CUDA 动态库会导致 `cv_bridge` 或 PyTorch 加载失败。
- 首次运行 SegFormer/Depth Anything V2 可能需要下载权重；国内网络可按脚本设置使用 Hugging Face 镜像。
- 原始视频、模型权重、session 输出和构建目录默认被 `.gitignore` 排除；需要共享时应使用 Release 或明确的外部存储。

## 9. 版本与提交说明

README 和 v14 资产清单属于轻量文档，应直接提交到 Git；大体积数据通过 Release 管理。提交前建议执行：

```powershell
git status --short
git diff --check
git log -1 --oneline
```

当前公开仓库：`https://github.com/4dai-xyz/AR_Navigation_YPF`
