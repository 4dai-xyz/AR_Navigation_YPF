---
name: tracker-logo-ocr-training
description: Use when working on the G:\kejicompany\tracker exhibition booth recognition project, including frame labeling, logo/booth-id crop datasets, binary detector training, booth-id classifier training, and final video debugging with blue sign crops.
---

# 会场展位号识别项目说明

## 项目目标

这个项目现在主要做会场/展馆里的展位号定位识别。当前重点不是完整 AR 定位服务，而是先把视频帧里的蓝色竖牌和顶部展位号识别出来，例如：

```text
A02 / A04 / B03 / B05 / B06 / E01
```

理想流程是：

```text
视频帧
-> 找蓝色竖牌绿色框
-> 在绿框顶部生成多个候选裁剪蓝框
-> 每个候选框跑 detector/classifier
-> 取当前帧通过阈值的结果
-> 没识别到就不显示，不沿用旧结果
```

注意：展位号是文字类目标，不是真正的 logo。当前用 ResNet 分类器识别展位号，容易出现相邻编号混淆，例如 `B03 -> B04`。裁剪问题和分类器问题要分开排查。

## 关键文件

- `G:\kejicompany\tracker\tracker.py`
  交互式标注脚本。用户选择 ROI 后输入标签名，例如 `B05`，脚本跟踪并生成 json 标注。

- `G:\kejicompany\tracker\shujuchuli.py`
  根据标注 json 从原始帧里裁剪正样本，输出到 `logo_dataset`。这是多分类展位号/标志数据集。

- `G:\kejicompany\tracker\数据集扩充.py`
  数据增强脚本，把每类补到约 120 张。增强包括轻微颜色变化、旋转、裁剪/平移。

- `G:\kejicompany\tracker\分割数据集.py`
  生成二分类 detector 数据集。`positive` 来自 `logo_dataset`，`negative` 来自没有标注或非重叠背景区域。

- `G:\kejicompany\tracker\pos.py`
  训练二分类 detector，输出 `huichang_logo_detector_binary.pth`。

- `G:\kejicompany\tracker\train.py`
  训练 46 类展位号/标志分类器，输出 `huichang_logo_model_final.pth`。

- `G:\kejicompany\tracker\final_test.py`
  当前最重要的视频测试脚本。会画绿色蓝牌框、蓝色候选裁剪框、候选分数和最终识别结果。

- `G:\kejicompany\tracker\R-CNN.py`
  单张图片调试脚本，用于快速看候选框和模型输出。

## 当前数据和模型

主要数据目录：

```text
G:\kejicompany\tracker\huichang_images_10fps
G:\kejicompany\tracker\logo_dataset
G:\kejicompany\tracker\Huichang_RCNN_Dataset
```

主要模型：

```text
G:\kejicompany\tracker\huichang_logo_detector_binary.pth
G:\kejicompany\tracker\huichang_logo_model_final.pth
```

`logo_dataset` 当前大约 46 类，约 5430 张。很多类已经扩充到 120 张。

## 环境使用

训练模型用 base 环境，因为 base 里有 CUDA 版 PyTorch：

```powershell
D:/anacon/python.exe train.py
D:/anacon/python.exe pos.py
```

不要在 `(tracker)` conda 环境里训练分类器。`tracker` 环境主要用于 OpenCV tracker，因为它有 OpenCV contrib。训练时如果看到：

```text
>>> Device: cpu
```

说明环境用错了。应该用：

```powershell
D:/anacon/python.exe -c "import torch; print(torch.__version__, torch.cuda.is_available())"
```

期望看到 CUDA 为 `True`。

交互式跟踪标注用：

```powershell
D:/anacon/envs/tracker/python.exe tracker.py
```

## 标注和数据集流程

1. 用 ffmpeg 抽帧到 `huichang_images_10fps/video_xxx`。

2. 修改 `tracker.py` 的输入帧目录和 json 输出路径。

3. 运行 `tracker.py`：

```powershell
D:/anacon/envs/tracker/python.exe tracker.py
```

4. 按空格选择 ROI，输入标签名，例如：

```text
B05
```

5. 标注完成后运行 `shujuchuli.py`，把标注框裁剪到 `logo_dataset/<类别名>`。

6. 如需补齐每类数量，运行：

```powershell
D:/anacon/python.exe 数据集扩充.py
```

7. 如需 detector 数据集，运行：

```powershell
D:/anacon/python.exe 分割数据集.py
```

## 训练流程

训练二分类 detector：

```powershell
D:/anacon/python.exe pos.py
```

训练多分类展位号分类器：

```powershell
D:/anacon/python.exe train.py
```

如果只是快速验证，不想跑很久，可以临时把 `train.py` 里的：

```python
EPOCHS = 20
BATCH_SIZE = 32
```

改成：

```python
EPOCHS = 5
BATCH_SIZE = 64
```

如果 2060 显存不够，`BATCH_SIZE` 改回 32。

## final_test.py 当前识别逻辑

当前 `final_test.py` 的核心逻辑是：

```text
1. HSV 找蓝色区域
2. 过滤出竖向蓝牌，画绿色框
3. 在绿色框顶部生成候选裁剪框，画蓝色框
4. 每个蓝框先跑 detector
5. detector 通过后跑 classifier
6. classifier 需要满足置信度和 top1/top2 margin
7. 通过后画最终结果
```

运行：

```powershell
D:/anacon/python.exe final_test.py
```

输出：

```text
G:\kejicompany\tracker\huichang_test_result.mp4
```

调试显示含义：

```text
绿色框：蓝色竖牌区域
蓝色细框：实际裁剪候选框
黄色/粗框：最终识别结果
左上角 blue=... crops=...：当前处理帧检测到几个蓝牌、几个候选框
候选框文字 c=... d=... m=...：
  c = classifier confidence
  d = detector confidence
  m = classifier top1 - top2 margin
det0 = detector 没通过
```

当前重要开关：

```python
FRAME_SKIP = 8
HOLD_LAST_RESULT = False
DEBUG_DRAW_CROPS = True
DEBUG_DRAW_CANDIDATE_SCORES = True
```

`HOLD_LAST_RESULT` 必须默认是 `False`。之前出现过一个明显 bug：第一次错识别成 `B04` 后，后面几帧一直沿用旧结果。现在不应该再沿用旧结果；没有新识别就清空。

## 裁剪策略

当前最重要的经验是：绿色框定位已经比较准，主要问题在蓝色裁剪框。

正确思路：

```text
绿色框确定整块蓝牌
蓝框左右直接等于绿色框宽度
蓝框只在顶部展位号附近生成
上下要加厚，不能切断编号
多个蓝框应重叠，避免 B-05 / B-06 被上下切断
```

不要做均匀切条。均匀切条容易把编号上下切断，模型看到残缺字符后会漏检或误判。

如果看到蓝框已经完整包住编号但仍然识别错，例如：

```text
B03 -> B04
A04 -> B04
```

这通常不是裁剪问题，而是分类器混淆问题。

## 阈值策略

当前阈值大概是：

```python
DETECTOR_CONF_THRESHOLD = 0.65
DISPLAY_CONF_THRESHOLD = 0.80
CLASSIFIER_MARGIN_THRESHOLD = 0.15
```

如果漏检很高，先看候选框分数：

- `det0 0.43`：detector 没过。
- `B05 c=0.78 d=0.91 m=0.12`：分类器接近但被阈值或 margin 过滤。
- `B04 c=0.95 d=0.90 m=0.30` 但画面是 B03：分类器严重混淆。

不要盲目调阈值。先确认是 detector、classifier、margin 还是裁剪框的问题。

## 已知问题

1. **分类器会混淆相邻编号**

例如 `B03` 被识别成 `B04`，即使蓝框已经包住 `B03`。这是多分类 ResNet 的问题，不是绿框问题。

2. **softmax 置信度不能完全相信**

模型在不确定时也会给一个高分，所以 `0.95` 不一定是真的可靠。

3. **OCR 尚未稳定接入**

EasyOCR 已安装过，但 base 环境中曾触发 MKL DLL 问题：

```text
mkl_intel_thread.2.dll
```

所以 `final_test.py` 默认关闭 EasyOCR：

```python
OCR_READER = None
```

只有设置：

```powershell
$env:ENABLE_EASYOCR='1'
```

才会尝试启用。

4. **展位号本质上更适合 OCR**

`A02 / B06 / E01` 是文字结构目标。长期更合理的方案是：

```text
蓝牌定位 -> 裁剪顶部编号 -> OCR/字符识别 -> 规则校验 [A-F][0-9][0-9]
```

ResNet 分类器可以作为辅助，但不应该无条件硬猜。

## 推荐排查顺序

遇到漏检或误检时，按这个顺序排查：

1. 看绿色框是否框住蓝色竖牌。
2. 看蓝色候选框是否完整包住顶部编号。
3. 看候选框分数：
   - detector 是否通过；
   - classifier top1 是什么；
   - confidence 和 margin 是否过阈值。
4. 如果蓝框完整、分数高但类别错，优先考虑分类器混淆，不要继续调裁剪。
5. 如果蓝框切断编号，继续调裁剪框上下 padding 和重叠策略。

## 当前工作原则

- 不要把旧结果沿用到后续帧，避免错误结果粘住。
- 调试时保留绿色框、蓝色候选框和候选分数。
- 先解决裁剪，再解决分类器。
- 分类器错得离谱时，不要只降阈值；需要 OCR、模板复核或重新训练。
- 4fps 输入是可以接受的，目标不是处理 30fps，而是每秒几帧里有稳定结果即可。
