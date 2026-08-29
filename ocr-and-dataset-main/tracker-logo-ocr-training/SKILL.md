---
name: tracker-logo-ocr-training
description: Use when working on the G:\kejicompany\tracker logo/OCR pipeline, especially zifu_train.py and final_chuli.py. Covers when to retrain logo classifiers, how train_logo_classifier and train_logo_with_nonlogo differ, how non_logo is used, and how final_chuli.py displays or filters recognition results.
---

# Tracker Logo/OCR Training

## 核心文件

- `G:\kejicompany\tracker\zifu_train.py`：训练脚本，用于训练 OCR/token 分类器、logo 分类器、logo+non_logo 分类器，以及生成 OCR 词表资源。
- `G:\kejicompany\tracker\final_chuli.py`：最终处理脚本，用训练好的模型做检测、分类、过滤和画框显示。
- `G:\kejicompany\tracker\logo_classifier_with_nonlogo.pth`：当前 `final_chuli.py` 使用的 logo 二阶段分类模型。
- `G:\kejicompany\tracker\logo_classifier_only.pth`：只包含 logo_ 类别的 logo 分类模型。

## zifu_train.py 的 MODE 选择

根据任务切换 `MODE`：

```python
MODE = "train_logo_classifier"
```

只训练以 `logo_` 开头的类别。
适合：更新了 logo 图片、增加了新的 `logo_xxx` 类别、只想训练 logo 分类器。

```python
MODE = "train_logo_with_nonlogo"
```

训练 `logo_` 类别，并额外加入 `non_logo` 负类。
适合：需要让模型学会“这个候选框不是 logo”，减少误识别。

```python
MODE = "train_legacy_classifier"
```

旧版全类别分类器训练。
适合：需要重新训练全部字符/token/logo 类别时使用。

```python
MODE = "build_ocr_assets"
```

根据映射表和数据集生成 OCR 词表资源。

## 更新 logo 图片后怎么做

如果用户刚更新了 logo 图片，通常要重新跑训练。

如果只是更新或新增 `logo_` 文件夹下的图片：

```python
MODE = "train_logo_classifier"
```

如果还想让模型识别“不是 logo 的负样本”，并且 `NEGATIVE_DIR` 里有负样本图片：

```python
MODE = "train_logo_with_nonlogo"
```

`final_chuli.py` 当前使用的是：

```python
LOGO_CLASSIFIER_PTH = r"G:\kejicompany\tracker\logo_classifier_with_nonlogo.pth"
```

所以如果最终流程要使用二阶段 logo/non_logo 过滤，优先训练 `train_logo_with_nonlogo`。

## non_logo 的作用

`non_logo` 是负类，不是要显示出来的类别。

它的含义是：这个候选区域不像真正的 logo。
在 `final_chuli.py` 中，如果二阶段 logo 分类器预测为 `non_logo`，不会把 `non_logo` 画到画面上。

关键逻辑：

```python
logo_name != NON_LOGO_CLASS_NAME
```

只有当预测结果不是 `non_logo`，并且置信度、显示阈值、类别间隔都满足要求时，才会显示 logo。

如果预测成 `non_logo`：

- 有可信字符候选时，回退显示字符；
- 没有可信字符候选时，直接跳过，不显示。

## 注释规范

`zifu_train.py` 中的注释使用中文，方便后续维护。
修改注释时只改说明文字，不改训练逻辑、路径、模型名和阈值配置。

## 常见判断

用户问“更新了 logo 图片，要不要重跑？”
回答：要。新增图片或类别后，模型不会自动学到，需要重新训练。

用户问“non_logo 是不是不显示？”
回答：对，`non_logo` 是过滤用的负类，不作为最终文字或 logo 标签显示。

用户问“我应该跑哪个模式？”
判断方式：

- 只更新 logo 正样本：用 `train_logo_classifier`
- 想减少误报、加入负样本：用 `train_logo_with_nonlogo`
- 重新训练全部字符和 logo：用 `train_legacy_classifier`
- 只生成 OCR 资源：用 `build_ocr_assets`
