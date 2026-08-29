# Cloud Scene Classifier 技术方案汇总 v0.1

更新时间：2026-06-07

## 1. 目标与边界

当前 Cloud / PC 后台的室内重定位方案面向单展馆 Demo：Rokid 或手机上传单目 RGB 图片，PC 后台在同 Wi-Fi 局域网内完成展台级 scene recognition，并返回 `floor_id`、`position`、`confidence`、`matched_landmark`。

本方案只覆盖 Cloud / PC 后台侧：

- 视觉定位接口：`POST /api/v1/localization/visual-locate`
- PC 后台可视化与最近请求排查
- 展台分类器训练、评估、HTTP smoke
- 场馆样例包坐标映射

不覆盖：

- Android / Rokid 图传实现
- 建图侧数据采集规范
- 生产级多场馆模型管理平台

## 2. 当前默认路线

默认识别模式为 `scene_classifier`，当前优先级：

1. `scene_classifier_v3`
2. `scene_classifier_v2`
3. `scene_classifier_v1`
4. 若分类器 checkpoint 都不存在，启动脚本回退到 `scene_retrieval` / `template`

当前默认模型：

```text
cloud/tmp_scene_recognition_probe/yjdd_hd_booth_classifier_mobilenet_v3_small_v3_gpu_hard_partial/booth_classifier_mobilenet_v3_small_v3_gpu_hard_partial.pt
```

默认加载逻辑：

- `cloud/app/core/settings.py`
- `cloud/tools/run_pc_backend.ps1`

后台 adapter 支持：

- `mobilenet_v3_small`
- `mobilenet_v3_large`

## 3. 数据与坐标

训练源数据来自 `PC/yjdd_hd.mov` 按人工时间段绑定展台后抽帧得到的样本：

```text
cloud/tmp_scene_recognition_probe/yjdd_hd_scene_samples_8fps_blur80/images
```

展台坐标映射来自：

```text
cloud/data/exhibition_demo/localization/booth_coordinates.json
```

当前忽略 `A11`，因为现场确认不存在。

## 4. 模型版本对比

| 版本 | 路线 | 结论 |
| --- | --- | --- |
| v1 | `MobileNetV3 Small` 初版分类器 | 同源样本效果好，但对真实跨摄像头/光照风险缺少针对性增强 |
| v2 | v1 初始化 + 强增强微调 | 提升合成压力集，HTTP `ok_rate=98.72%` |
| v3 | v2 初始化 + GPU batch hard-partial 增强 | 当前默认；hard-stress 相比 v2 提升，HTTP `ok_rate=99.11%` |
| v4 | `MobileNetV3 Large` 大容量路线 | hard-stress 略高，但 HTTP `ok_rate=78.54%`，需置信度校准，暂不默认 |

当前选择 v3 的原因：

- 保持 `ok_top1_accuracy=100%`
- `ok_rate` 高于 v2 / v4
- 服务内延迟稳定低于 `200ms`
- 相比 v4 更轻量，适合 4FPS Rokid 图传

## 5. 当前验证指标

### v3 HTTP 全量自测

报告：

```text
cloud/tmp_scene_recognition_probe/yjdd_hd_booth_classifier_mobilenet_v3_small_v3_gpu_hard_partial/scene_classifier_v3_http_selftest_report.json
```

指标：

| 指标 | 数值 |
| --- | --- |
| query_count | `2349` |
| booth_group_count | `52` |
| raw_top1_accuracy | `99.87%` |
| ok_top1_accuracy | `100%` |
| ok_rate | `99.11%` |
| status_counts | `ok=2328` / `low_confidence=20` / `not_found=1` |
| service latency p95 / max | `22ms` / `80ms` |
| HTTP wall latency p95 / max | `47.46ms` / `202.38ms` |
| ok_wrong_count | `0` |
| latency_over_limit_count | `0` |

说明：

- 服务内识别延迟满足 `200ms`。
- HTTP wall latency 有 `202.38ms` 单点离群，现场严格验收端到端最大值时需热态复测。
- 当前没有 `status=ok` 的错误放行。

### hard-stress 合成压力集

hard-stress 动态生成，不单独保存为静态图片目录。生成逻辑在：

```text
cloud/tools/train_scene_booth_classifier.py
```

覆盖场景：

- 暗光 + 噪声
- 过曝
- 色偏
- 模糊 / 运动模糊
- 低清
- 左/右半视角
- 中心裁剪
- 局部遮挡 / 大面积遮挡
- JPEG 压缩

结果：

| 版本 | hard-stress top1 |
| --- | --- |
| v2 | `95.03%` |
| v3 | `95.75%` |
| v4 | `95.83%` |

注意：hard-stress 是合成评估，不等价于真实跨摄像头、真实现场光照和真实 Rokid 运动模糊。

## 6. GPU 训练与训练效率

当前 `.venv-ocr` 已验证 CUDA 可用：

```text
torch 2.12.0+cu126
torchvision 0.27.0+cu126
NVIDIA GeForce RTX 4060 Laptop GPU
```

训练脚本：

```text
cloud/tools/train_scene_booth_classifier.py
```

已支持：

- `--model-arch mobilenet_v3_small`
- `--model-arch mobilenet_v3_large`
- `--cache-images`
- `--gpu-augment`
- `--gpu-augment-profile balanced`
- `--gpu-augment-profile hard_partial`
- `--mixup-alpha`
- `--init-checkpoint`

v3 训练命令：

```powershell
.\.venv-ocr\Scripts\python.exe cloud\tools\train_scene_booth_classifier.py `
  --epochs 2 `
  --batch-size 128 `
  --lr 0.00003 `
  --model-arch mobilenet_v3_small `
  --experiment-name v3_gpu_hard_partial `
  --output-dir cloud\tmp_scene_recognition_probe\yjdd_hd_booth_classifier_mobilenet_v3_small_v3_gpu_hard_partial `
  --init-checkpoint cloud\tmp_scene_recognition_probe\yjdd_hd_booth_classifier_mobilenet_v3_small_v2_aug\booth_classifier_mobilenet_v3_small_v2_aug.pt `
  --compare-checkpoint cloud\tmp_scene_recognition_probe\yjdd_hd_booth_classifier_mobilenet_v3_small_v2_aug\booth_classifier_mobilenet_v3_small_v2_aug.pt `
  --cache-images `
  --gpu-augment `
  --gpu-augment-profile hard_partial
```

## 7. PC 后台启动

启动命令：

```powershell
cd F:\hz\codex\AI_Glasses
.\cloud\tools\run_pc_backend.ps1 -Port 8000 -RecognitionMode scene_classifier
```

健康检查：

```text
http://127.0.0.1:8000/api/v1/health
http://<PC局域网IP>:8000/api/v1/health
```

如果当前机器存在 v3 checkpoint，health 中会显示：

```text
recognition_mode=scene_classifier
checkpoint_path=...v3_gpu_hard_partial...
```

## 8. 当前限制与下一步

当前限制：

- 训练数据仍主要来自 `yjdd_hd.mov` 单条一镜到底视频。
- hard-stress 为合成压力集，不代表真实跨摄像头结果。
- v4 大模型需要置信度校准后才能考虑替换默认。
- 真实 Rokid 4FPS 图传下仍需现场热态复测。

下一步不应继续只在合成压力集上卷指标，应优先补采真实 hard examples：

- Rokid 视角
- 手机不同摄像头
- 强光 / 暗光
- 近距离局部海报
- 只拍到半个展台
- 行走运动模糊

补采后将这些样本加入 hard-example 回归集，再决定是否继续微调 v3 或校准 v4。
