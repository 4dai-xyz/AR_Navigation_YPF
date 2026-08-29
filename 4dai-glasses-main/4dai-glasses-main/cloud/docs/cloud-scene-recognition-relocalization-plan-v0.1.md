# Cloud Scene Recognition 重定位方案 v0.1

## 当前素材

- 主视频：`PC/yjdd_hd.mov`
- 视频信息：`1080x1920`，约 `29.987 FPS`，约 `666.11s`，OpenCV 可读。
- 平面坐标源：`OCR/huichang.jpg` 与 `OCR/huichang.json`
- 展台坐标表：`cloud/data/exhibition_demo/localization/booth_coordinates.json`

`yjdd_hd.mov` 相比旧版 `yjdd.mp4` 分辨率更高，海报文字、展台侧板和整体场景更清晰，更适合作为 scene retrieval 的训练/建库素材。

## 推荐路线

当前不再把 OCR/展台号识别作为主定位路线，而采用分层 scene recognition：

1. 从一镜到底视频抽取关键帧。
2. 人工标注关键帧对应的 `booth_id`、`position` 或时间段范围。
3. 对关键帧预计算图像 embedding，形成检索库。
4. Rokid 以约 4FPS 上传 RGB 图片后，PC 后台做 top-k 场景检索。
5. 结合相似度、top-k 间隔和连续多帧投票输出 `floor_id`、`position`、`confidence`、`matched_landmark`。

OCR/LOGO/展台编号识别保留为辅助证据：当画面中编号或明显 Logo 可读时提升置信度，但不再作为唯一定位依据。

## 坐标表

`cloud/tools/build_scene_recognition_assets.py` 会从 `OCR/huichang.json` 读取 LabelMe 点标注，只保留符合 `A/B/E/F + 两位数字` 的展台标签，并生成 `booth_coordinates.json`。

当前坐标表包含：

- `A01-A11`
- `B01-B19`
- `E01-E12`
- `F01-F11`

坐标说明：

- 单位：米
- 坐标范围：只裁剪展台点覆盖区域，去除平面图其它区域
- `x`：近似平面图向右
- `y`：近似平面图向下
- 展台尺寸：按 `3m x 3m` 近似
- 精度：Demo 级展台中心点，需要后续用真实关键帧标注校正

## 关键帧资产

当前已验证可以从 `PC/yjdd_hd.mov` 抽取关键帧清单和缩略图：

```powershell
python cloud\tools\build_scene_recognition_assets.py `
  --booth-json OCR\huichang.json `
  --booth-output cloud\data\exhibition_demo\localization\booth_coordinates.json `
  --source-image OCR/huichang.jpg `
  --video PC\yjdd_hd.mov `
  --keyframe-output-root cloud\tmp_scene_recognition_probe\yjdd_hd_scene_assets_smoke `
  --sample-fps 1 `
  --max-frames 120 `
  --image-width 540
```

完整 1FPS 元数据可生成约 667 条关键帧记录。关键帧默认 `label_status=unlabeled`，在人工完成时间段/展台绑定前，不应直接作为可信定位结果输出。

## 快捷人工绑定方式

不要逐帧标注。当前最省时间的方式是按视频时间段绑定：

1. 打开 `yjdd_hd.mov` 或抽帧缩略图。
2. 记录稳定看到某个展台/海报的时间段，例如 `128.0s-137.5s -> B10`。
3. 经过过道、快速转身、遮挡、无法判断的时间段标为 `transition` 或 `ignore`。
4. 将时间段填入 `cloud/data/exhibition_demo/localization/scene_keyframe_segments.template.csv` 的同类格式。
5. 用 `cloud/tools/apply_scene_keyframe_labels.py` 自动把时间段标签展开到关键帧 JSONL。

CSV 字段：

| 字段 | 说明 |
| --- | --- |
| `start_sec` | 时间段开始秒数，闭区间 |
| `end_sec` | 时间段结束秒数，开区间 |
| `booth_id` | 展台编号，如 `B17`；`transition/ignore` 可留空 |
| `label_status` | `labeled`、`transition`、`ignore` 或 `unlabeled` |
| `confidence` | 人工标注置信度，建议稳定正对展台填 `1.0`，转场填 `0.0-0.3` |
| `notes` | 备注，例如“海报正面”“斜视角”“遮挡” |

应用示例：

```powershell
python cloud\tools\apply_scene_keyframe_labels.py `
  --keyframes cloud\tmp_scene_recognition_probe\yjdd_hd_scene_assets_smoke\scene_keyframes_yjdd_hd.jsonl `
  --segments cloud\data\exhibition_demo\localization\scene_keyframe_segments.template.csv `
  --booth-coordinates cloud\data\exhibition_demo\localization\booth_coordinates.json `
  --output cloud\tmp_scene_recognition_probe\yjdd_hd_scene_assets_smoke\scene_keyframes_yjdd_hd_labeled.jsonl `
  --summary-output cloud\tmp_scene_recognition_probe\yjdd_hd_scene_assets_smoke\scene_keyframes_yjdd_hd_label_summary.json
```

标注建议：

- 第一轮只标“稳定看清海报/展台”的时间段，宁可少标，不要把转场硬绑定。
- 同一展台可有多个时间段，覆盖不同距离和角度。
- 每段建议 3-10 秒；过短会样本少，过长容易混入转场。
- 如果一帧同时看到多个展台，优先标画面中心、面积最大、最容易被检索命中的展台。
- 标注后先看 summary 中每个 `booth_id` 的关键帧数量，明显为 0 的展台需要补时间段。

## 样本扩增与模糊过滤

如果某些展台样本偏少，可以在不重新标注的情况下提高抽帧频率，例如从 4FPS 提高到 8FPS。时间段标签仍然复用同一份 CSV，脚本会自动把更多关键帧绑定到对应展台。

样本帧会记录 `quality.blur_laplacian_var`，数值越低通常越模糊。当前 Demo 建库建议先使用：

- `sample_fps=8`
- `min_blur=80`
- `min_gap_ms=0`
- `image_width=540`

导出示例：

```powershell
python cloud\tools\export_scene_retrieval_samples.py `
  --labeled-keyframes cloud\tmp_scene_recognition_probe\yjdd_hd_scene_assets_8fps_meta\scene_keyframes_yjdd_hd_labeled.jsonl `
  --video PC\yjdd_hd.mov `
  --output-root cloud\tmp_scene_recognition_probe\yjdd_hd_scene_samples_8fps_blur80 `
  --min-blur 80 `
  --max-per-booth 160 `
  --min-gap-ms 0 `
  --image-width 540 `
  --jpeg-quality 90
```

当前 8FPS + `min_blur=80` 后可导出 2349 张样本图，除 `B01=13` 外，其余展台均不少于 15 张。

## 当前检索基线评估

已基于 `cloud/tmp_scene_recognition_probe/yjdd_hd_scene_samples_8fps_blur80` 构建 2349 张样本的离线检索库，并采用 `exclude_near_ms=2000` 做严格评估：查询时排除前后 2 秒近邻帧，避免相邻帧过像导致评估虚高。

| 特征路线 | top1 | top3 | top5 | 结论 |
| --- | ---: | ---: | ---: | --- |
| `hybrid_v1` OpenCV 颜色/灰度/边缘 | 0.0609 | 0.0937 | 0.1166 | 只适合 smoke，不适合作主算法 |
| `torchvision_resnet50` | 0.5960 | 0.6411 | 0.6696 | 当前单模型最好 |
| `dinov2_vits14` | 0.5347 | 0.5849 | 0.6198 | 可作为辅助特征 |
| `open_clip_vit_b32_openai` | 0.5551 | 0.6356 | 0.6675 | 对海报/文字有帮助，但单独不优于 ResNet |
| `fusion_resnet50_clip_vitb32` | 0.6526 | 0.7109 | 0.7327 | 融合后明显优于单模型 |
| `fusion_resnet50_clip_vitb32_dinov2` | 0.6671 | 0.7114 | 0.7318 | 当前最佳严格评估结果 |

当前可落地选择：

- Demo 主线先采用 `fusion_resnet50_clip_vitb32_dinov2` 或 `fusion_resnet50_clip_vitb32`。
- 在线单帧结果不要直接作为最终定位，应结合 top-k 分数差、连续 4FPS 多帧投票和路线连续性。
- `A11` 现场确认不存在，坐标表可保留原始点位，但当前检索库、人工标注和 Demo 联调目标均忽略 `A11`。

依赖与缓存：

- `torch/torchvision` 使用 `.venv-ocr`。
- `open_clip_torch` 已安装在 `.venv-ocr`。
- `TORCH_HOME=F:\hz\codex\models\torch`
- `OPENCLIP_CACHE_DIR=F:\hz\codex\models\open_clip`

## PC 后台接入

`scene_retrieval` 已接入 PC 后台 `/api/v1/localization/visual-locate`。启动方式：

```powershell
.\cloud\tools\run_pc_backend.ps1 -RecognitionMode scene_retrieval
```

启动脚本会在 `scene_retrieval` 模式下自动配置：

- `VenvPath=.venv-ocr`，如果该环境存在
- `AI_GLASSES_SCENE_RETRIEVAL_INDEX_PATH`
- `AI_GLASSES_SCENE_RETRIEVAL_METADATA_PATH`
- `AI_GLASSES_SCENE_RETRIEVAL_BOOTH_COORDINATES_PATH`
- `AI_GLASSES_SCENE_RETRIEVAL_FEATURE_EXTRACTOR=fusion_resnet50_clip_vitb32_dinov2`
- `AI_GLASSES_SCENE_RETRIEVAL_TIMEOUT_MS=60000`

健康检查：

```powershell
curl http://127.0.0.1:8000/api/v1/health
```

`algorithm_backend_status.available=true` 表示索引、metadata 和展台坐标表都可用。

注意：

- 首次 visual-locate 会加载 ResNet50、CLIP、DINOv2 三个模型，当前 CPU 环境约 18 秒。
- 模型加载后热态单帧推理约 400ms。
- App 联调前建议先用一张样本图或手机拍摄图做一次 warm-up 请求，避免首帧等待过长。
- 当前 PC 后台已经能在 `/debug/visual-locate` 准实时显示 App/Rokid 上传帧、识别阶段、最近 5 秒接收 FPS 和识别完成 FPS。

## MVP 接入边界

第一版 scene retrieval adapter 应只做最小闭环：

- 读取 `booth_coordinates.json`
- 读取已人工标注的关键帧 manifest
- 预计算或加载 embedding
- 在线输入单张 RGB 图片，返回 top-k 匹配关键帧
- 将匹配关键帧绑定到 `booth_id` 与 `position`
- 多帧窗口内做投票和平滑

不在本阶段做：

- 生产级地图平台
- 自动视频 SLAM 建图
- 未标注关键帧的自动位置推断
- 依赖手机 IMU 或 Rokid IMU 才能返回结果

## 风险

- 只有单条一镜到底视频时，反向视角、遮挡、人流、亮度变化覆盖不足。
- 展台坐标来自平面图点标注和 3m 尺寸估算，不是实测坐标。
- 如果直接用未标注关键帧做检索，只能返回“相似帧”，不能可靠返回“当前位置”。
- 现场 Demo 前至少需要人工标注视频时间段到展台或坐标，否则重定位结果无法解释。
