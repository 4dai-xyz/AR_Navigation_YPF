# Tracker Logo/OCR 识别项目

> 干净 clone 的最短验证路径见 [`QUICKSTART.md`](QUICKSTART.md)。训练数据、视频和 `*.pth` 权重是外部/本地资产，不是 clone 后必然存在的目录。

本项目用于在视频帧中定位并识别展位牌、Logo 或文字编号。整体思路是先用候选框/检测模型筛出疑似目标区域，再用 ResNet18 分类器完成二阶段识别，最后把识别结果画回视频或调试图片中。

## 功能概览

- 视频抽帧与交互式标注，生成带 bbox 的 JSON 标注文件和 label 映射表。
- 从标注结果和裁剪图生成 Logo/文字分类数据集。
- 训练二分类检测器、Logo 多分类器、OCR/文字分类器。
- 对单张图片或整段视频进行推理，并输出带检测框和置信度的视频/图片。
- 支持会场蓝色立牌场景的候选框过滤、NMS、轨迹保持和文字片段合并。

## 项目结构

```text
.
├── final_chuli.py                     # 主推理脚本：Logo + OCR/token 合并识别，输出 result_video.mp4
├── final_test.py                      # 会场视频推理脚本，输出 huichang_test_result.mp4
├── R-CNN.py                           # 单图 R-CNN 风格候选框调试，输出 rcnn_single_test_result.jpg
├── tracker.py                         # 交互式图片序列标注工具，生成 JSON 和 label 映射
├── train.py                           # 会场 Logo 多分类模型训练
├── zifu_train.py                      # OCR/token、Logo、Logo+non_logo 等训练入口
├── 分割数据集.py                       # 根据标注构建二分类 R-CNN 数据集
├── 数据集扩充.py                       # 对 logo_dataset 做轻量数据增强
├── rcnn_video_visualize.py            # 早期视频推理/可视化脚本
├── logo_dataset/                      # Logo/展位编号分类数据集，按类别分文件夹
├── Huichang_RCNN_Dataset/             # 二分类检测数据集，positive/negative
├── huichang_images_10fps/             # 视频抽帧后的图片序列
├── *.json                             # 标注结果或 SLAM/跟踪数据
├── *.txt                              # label_id 映射表
└── *.pth                              # 已训练模型权重
```

## 环境依赖

建议使用 Python 3.9+，并安装以下依赖：

```bash
pip install torch torchvision opencv-python opencv-contrib-python pillow numpy
```

如果要使用 `tracker.py` 的 CSRT/KCF 交互式跟踪功能，需要安装 `opencv-contrib-python`，否则普通 `opencv-python` 可能缺少 `cv2.legacy.TrackerCSRT_create`。

## 数据准备

### 1. 视频抽帧

项目中已有 `huichang_images_10fps/`、`huichang_images_5fps/` 等抽帧目录。如果要替换数据，需要先把视频按固定帧率抽成图片序列，并在相关脚本顶部修改路径配置。

### 2. 交互式标注

在 `tracker.py` 顶部确认以下配置：

```python
IMG_DIR = r"G:\kejicompany\tracker\huichang_images_10fps\video_003"
SAVE_PATH = "huichang_slam_data_10fps_video_003.json"
MAP_PATH = "huichang_label_id_mapping_10fps_video_003.txt"
```

运行：

```bash
python tracker.py
```

快捷键：

- `SPACE`：选择 ROI 并输入标签。
- `N`：当前帧无目标，跳过。
- `Q`：保存并退出。

### 3. 构建检测数据集

`分割数据集.py` 会从 `logo_dataset/` 和标注 JSON 中生成：

- `Huichang_RCNN_Dataset/positive`
- `Huichang_RCNN_Dataset/negative`

运行：

```bash
python 分割数据集.py
```

### 4. 数据增强

`数据集扩充.py` 会把每个类别补到 `TARGET_PER_CLASS` 张，默认目标是 120 张/类：

```bash
python 数据集扩充.py
```

## 模型训练

### 训练会场 Logo 分类器

确认 `train.py` 顶部配置：

```python
DATA_ROOT = r"G:\kejicompany\tracker\logo_dataset"
SAVE_DIR = "checkpoints_huichang_logo"
FINAL_MODEL_NAME = "huichang_logo_model_final.pth"
```

运行：

```bash
python train.py
```

### 训练 OCR/Logo 相关模型

`zifu_train.py` 通过 `MODE` 控制任务：

```python
MODE = "build_ocr_assets"        # 生成 OCR 词表资源
MODE = "train_logo_classifier"   # 只训练 logo_ 开头类别
MODE = "train_logo_with_nonlogo" # 训练 logo_ 类别并加入 non_logo
MODE = "train_legacy_classifier" # 旧版全类别分类器
```

修改 `MODE` 后运行：

```bash
python zifu_train.py
```

## 推理与调试

### 主视频推理

`final_chuli.py` 是综合推理脚本，默认读取：

```python
VIDEO_PATH = r"G:\kejicompany\new\1000086258.mp4"
OUTPUT_VIDEO = "result_video.mp4"
```

运行：

```bash
python final_chuli.py
```

输出文件：

```text
result_video.mp4
```

### 会场视频推理

`final_test.py` 面向会场蓝色立牌场景：

```bash
python final_test.py
```

输出文件：

```text
huichang_test_result.mp4
```

### 单张图片调试

运行默认测试图片：

```bash
python R-CNN.py
```

指定图片：

```bash
python R-CNN.py "G:\path\to\image.jpg"
```

输出文件：

```text
rcnn_single_test_result.jpg
```

## 重要配置说明

大多数脚本的路径和阈值都写在文件顶部，运行前优先检查：

- `VIDEO_PATH`：输入视频路径。
- `OUTPUT_VIDEO`：输出视频路径。
- `DETECTOR_PTH` / `MODEL_PATH`：二分类检测器权重。
- `CLASSIFIER_PTH` / `LOGO_CLASSIFIER_PTH`：Logo 分类权重。
- `TOKEN_CLASSIFIER_PTH`：OCR/token 分类权重。
- `DATA_ROOT` / `CLASS_DIR`：分类数据集目录。
- `FRAME_SKIP`：每隔多少帧推理一次，值越大速度越快但漏检风险越高。
- `DETECTOR_CONF_THRESHOLD`、`DISPLAY_CONF_THRESHOLD`、`LOGO_CONF_THRESHOLD`：检测/显示阈值。

## 常见问题

### 无法打开视频

检查脚本顶部的 `VIDEO_PATH` 是否存在，路径中不要混用不存在的盘符或目录。

### OpenCV 没有 TrackerCSRT_create

安装 contrib 版本：

```bash
pip install opencv-contrib-python
```

### 类别数量和模型不匹配

分类器权重依赖训练时的类别顺序和类别数量。修改 `logo_dataset/` 或 `char_dataset/` 后，需要重新训练对应模型，或者确保推理脚本读取的类别目录与训练时完全一致。

### 中文显示乱码

本 README 使用 UTF-8 编码。PowerShell 或编辑器中如果仍有乱码，请把文件编码切换为 UTF-8。

## 推荐工作流

1. 抽帧到 `huichang_images_10fps/`。
2. 用 `tracker.py` 标注并生成 JSON / label 映射。
3. 用 `分割数据集.py` 构建 `Huichang_RCNN_Dataset/`。
4. 用 `数据集扩充.py` 补齐各类别样本。
5. 用 `train.py` 或 `zifu_train.py` 训练模型。
6. 用 `R-CNN.py` 做单图调试。
7. 用 `final_test.py` 或 `final_chuli.py` 跑整段视频。
