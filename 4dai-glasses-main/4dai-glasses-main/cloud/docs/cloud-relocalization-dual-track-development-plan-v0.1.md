# 云端重定位双轨开发方案 V0.1

更新日期：2026-04-30

## 1. 文档目的

本文档把云端室内视觉重定位的后续开发收敛为“双轨策略”，用于在真实场馆包和真实采图结果到位前，先推进不依赖真实数据的算法工程准备。

本文档回答：

1. 为什么不把深度特征重定位直接作为唯一主线一次性落地。
2. 轻量本地视觉原型和正式深度算法实验如何并行推进。
3. 哪些模块、测试、文档和验收门槛需要先做。
4. 真实场馆包到位后如何从原型切换到正式算法链路。

## 2. 当前判断

当前云端已经具备：

- `visual-locate` 接口骨架。
- 场馆包加载与 keyframe 元数据读取。
- Track A 本地视觉原型：图像质量评估、ORB keyframe 特征索引、BFMatcher 匹配和 RANSAC 几何验证。
- 错误码、超时、日志和联调 smoke。

当前尚未具备：

- 完整置信度融合与离线评估 harness。
- 深度全局检索 Top-K keyframe。
- `SuperPoint + LightGlue` 深度局部特征匹配。
- 基于真实 query 集的定位指标。
- 生产级深度特征推理链路。

因此当前阶段的算法目标不是“最终定位效果”，而是先把正式算法需要的工程管线、失败语义和评估抓手做稳。

## 3. 双轨策略总览

双轨策略定义为：

| 轨道 | 定位 | 是否进入主服务链路 | 目标 |
| --- | --- | --- | --- |
| Track A：服务主线轻量视觉原型 | 稳定可联调的本地算法基座 | 是 | 用 ORB/AKAZE + BFMatcher + RANSAC 打通算法管线 |
| Track B：深度算法实验线 | 正式算法候选验证 | 否，先离线/实验开关 | 验证 SuperPoint + LightGlue + hloc-style retrieval 的可行性 |

原则：

1. Track A 保证服务稳定、依赖轻、局域网可联调。
2. Track B 保证正式路线不被搁置，但不阻塞 App 联调。
3. 两条轨道共用统一输入输出模型，避免后续推倒重来。
4. 只有满足明确合入门槛后，Track B 才能替换 Track A 的在线实现。

## 4. 统一算法管线

无论 Track A 还是 Track B，都按同一条逻辑管线实现：

```text
image bytes
  -> image decode
  -> image quality evaluation
  -> candidate keyframe filtering
  -> global/local candidate ranking
  -> local feature matching
  -> geometry verification
  -> confidence fusion
  -> route snap
  -> LocalizationData
```

统一输出字段继续对齐现有 API：

- `status`
- `floor_id`
- `position`
- `confidence`
- `uncertainty_m`
- `matched_keyframe_id`
- `matched_keyframes`
- `inlier_count`
- `failure_stage`
- `route_snap`
- `next_capture_hint`
- `suggested_action`
- `trace_id`
- `latency_ms`

统一失败阶段：

- `image_quality_failed`
- `retrieval_no_hit`
- `match_insufficient`
- `geometry_failed`
- `route_snap_failed`
- `low_confidence`

## 5. Track A：服务主线轻量视觉原型

### 5.1 目标

Track A 的目标是用最小依赖实现真实图像内容级 baseline，让云端在本地和局域网环境能跑通完整视觉重定位流程。

### 5.2 技术路线

推荐实现：

- 图像解码：OpenCV。
- 图像质量：亮度、模糊度、尺寸、是否可解码。
- 局部特征：ORB 优先，AKAZE 作为可选对照。
- 匹配器：BFMatcher。
- 匹配筛选：ratio test 或 cross-check。
- 几何验证：Homography + RANSAC。
- 候选过滤：`candidate_floor_id`、`route_prior.edge_ids`。
- 置信度：匹配数、内点数、内点率、Top-1/Top-2 margin、先验命中融合。

### 5.3 模块建议

建议新增最小模块：

| 模块 | 职责 |
| --- | --- |
| `cloud/app/services/image_quality.py` | 解码图像并输出质量指标 |
| `cloud/app/services/visual_features.py` | 提取 ORB/AKAZE 特征并构建 keyframe 索引 |
| `cloud/app/services/visual_matching.py` | 候选匹配、排序、几何验证 |
| `cloud/app/services/relocalization.py` | 保持现有入口，编排完整管线 |

### 5.4 验收标准

Track A 完成的最低验收：

1. 样例包 `kf_0001.jpg` 查询可稳定命中 `kf_0001`。
2. 错楼层先验会降低候选优先级或置信度。
3. `route_prior.edge_ids` 可影响候选过滤或置信度。
4. 非图片、过小图片、过暗/过糊图片有稳定失败语义。
5. `matched_keyframes`、`inlier_count`、`failure_stage` 有可解释输出。
6. `visual-locate` API 响应结构不变。
7. 云端测试和 smoke 全部通过。

## 6. Track B：深度算法实验线

### 6.1 目标

Track B 的目标是尽早验证正式路线，但在依赖、数据和运行环境未稳定前不进入默认服务链路。

### 6.2 技术路线

推荐正式路线：

- 全局检索：hloc-style retrieval，可基于 NetVLAD、DINOv2 或后续确定的全局 descriptor。
- 局部特征：SuperPoint。
- 匹配器：LightGlue。
- 几何验证：PnP/RANSAC 或 Homography/RANSAC，取决于场馆包是否提供 3D/位姿资产。
- 置信度：检索分、匹配分、几何内点、先验命中和路网一致性融合。

### 6.3 实验线边界

Track B 初期只允许：

- 离线脚本。
- 独立实验模块。
- 可选环境变量开关。
- 不影响默认 `visual-locate` 行为。

暂不允许：

- 默认启动时强依赖 `torch`。
- 默认下载模型权重。
- 默认占用 GPU。
- 默认改变 API 字段。
- 在没有评估集时替换主服务链路。

### 6.4 合入主线门槛

Track B 替换 Track A 需要同时满足：

1. 真实场馆包提供 keyframe 图像、相机参数和必要位姿/平面坐标。
2. 至少有一批真实 query 图片和期望楼层/区域/keyframe 标注。
3. 离线评估指标优于 Track A。
4. 95 分位接口耗时满足 Demo 目标。
5. CPU/GPU 运行环境和模型权重路径可重复部署。
6. 失败语义与现有 `failure_stage` 对齐。
7. App 不需要修改接口字段即可消费。

## 7. 开发计划

### P0：管线接口整理

状态：已完成。当前 `BaselineRelocalizer` 保持外部入口不变，内部已接入 Track A 视觉管线，并保留字节 fallback 方法作为调试兜底。

目标：

- 定义内部 `ImageQualityResult`、`CandidateMatch`、`RelocalizationEvidence` 等轻量数据结构。
- 保持 `LocalizationData` 对外不变。
- 保留当前字节 baseline 作为 fallback。

验收：

- 不改变外部 API。
- 现有测试通过。

### P1：Track A 图像质量评估

状态：已完成。当前已使用 OpenCV 解码并输出亮度、模糊度、尺寸、payload 大小和 `image_quality_failed` 语义。

目标：

- 引入 `opencv-python-headless`。
- 实现图像解码、亮度、模糊度、尺寸评估。
- 将质量结果写入日志或 debug evidence。

验收：

- 空图片/非图片/过小图返回稳定错误或 `image_quality_failed`。
- 样例 keyframe 图像可通过质量门槛。

### P2：Track A keyframe 特征索引

状态：已完成。当前已能从场馆包 keyframe 图像或 demo fallback 图像构建 ORB 特征索引，并支持候选过滤能力。

目标：

- 读取 `keyframes.jsonl` 的 `image_ref`。
- 为每个 keyframe 提取 ORB 特征。
- 支持按楼层和 route edge 过滤。

验收：

- 样例包能生成 2 个 keyframe 特征记录。
- 缺图或不可解码 keyframe 有可解释日志。

### P3：Track A 局部匹配与几何验证

状态：已完成。当前已使用 BFMatcher + ratio test + Homography/RANSAC 输出 Top-K、`inlier_count` 和匹配失败阶段。

目标：

- 查询图提取 ORB 特征。
- 对候选 keyframe 做 BFMatcher。
- 对 Top-K 做 RANSAC 几何验证。

验收：

- `kf_0001.jpg` 查询命中 `kf_0001`。
- 输出 `matched_keyframes` 和 `inlier_count`。
- 匹配不足返回 `match_insufficient`。
- 几何不一致返回 `geometry_failed` 或降级为低置信度。

### P4：Track A 置信度融合

状态：已完成。实现位置：`cloud/app/core/confidence.py`、`cloud/app/services/relocalization.py`、`cloud/app/services/visual_matching.py`。

目标：

- 用匹配数、内点数、内点率、margin、先验命中替换当前字节分数。
- 状态仍输出 `ok / low_confidence / not_found`。

验收：

- 强匹配为 `ok`。
- 弱匹配为 `low_confidence`。
- 无匹配为 `not_found`。
- 超时仍返回 `2003`。

### P5：离线评估 harness

状态：已完成。实现位置：`cloud/app/services/offline_relocalization_eval.py`、`cloud/tools/evaluate_relocalization.py`。

目标：

- 定义 query JSONL。
- 输出 report JSON。
- 导出失败样本摘要。

建议 query 格式：

```json
{
  "query_id": "q_001",
  "image_path": "queries/q_001.jpg",
  "venue_id": "venue_demo_001",
  "expected_floor_id": "F1",
  "expected_keyframe_id": "kf_0001",
  "candidate_floor_id": "F1",
  "route_prior": {
    "route_id": "route_001",
    "edge_ids": ["edge_f1_entry_to_escalator"],
    "corridor_window_m": 2.0
  }
}
```

验收：

- 样例包可跑出 Top-1、Top-3、楼层准确率、平均耗时和失败阶段分布。
- `image_path` 缺失会进入 `failure_stage_counts.image_file_missing`，不需要进入源码排查。

### P6：Track B 离线实验骨架

状态：已完成 adapter 骨架。实现位置：`cloud/app/services/deep_relocalization_adapter.py`。当前不安装 `torch`、`lightglue`、`hloc`，不接入默认服务。

目标：

- 预留 `SuperPoint + LightGlue` adapter。
- 明确模型权重路径、设备选择、输入输出格式。
- 先做离线实验，不接入默认服务。

验收：

- 不安装 Track B 依赖时，adapter 明确返回 `missing_optional_dependencies` 或 `not_configured`。
- 不安装 Track B 依赖时，主服务仍可启动。

### P7：真实包接入与算法切换评审

目标：

- 用真实包和真实 query 集比较 Track A 与 Track B。
- 决定是否把 Track B 合入默认在线链路。

验收：

- 有对比报告。
- 有明确切换风险。
- App 侧无需改接口。

## 8. 时间与优先级建议

建议优先顺序：

1. P0-P1：先把质量评估和内部结构定下来。
2. P2-P3：打通 Track A 视觉匹配。
3. P4-P5：稳定置信度和离线评估。（已完成）
4. P6：并行准备 Track B adapter。（已完成骨架）
5. P7：真实数据到位后评审切换。

如果只做一个最小闭环，优先完成 P0-P3。

## 9. 本地与上云策略

本地局域网阶段：

- 默认启用 Track A。
- 不依赖 GPU。
- 服务保持 `uvicorn cloud.app.main:app --host 0.0.0.0 --port 8000`。
- App 通过局域网 IP 调用。

上云阶段：

- Track A 可直接迁移到 CPU 云服务器。
- Track B 需要单独确认 GPU/CPU、模型权重、依赖安装和冷启动时间。
- 不允许因 Track B 依赖导致基础 `health / meta / route` 不可用。

## 10. 风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| ORB/AKAZE 对弱纹理和重复纹理不稳 | Track A 定位效果有限 | 只作为工程基座和对照 baseline |
| Track B 依赖重 | 本地和云端部署复杂 | 实验线隔离，默认不进入主链路 |
| 真实包缺少 keyframe 原图 | Track A 和 Track B 都受阻 | Mapping AI 必须提供原图或统一特征读取格式 |
| 没有真实 query 标注 | 无法判断算法优劣 | 先定义评估格式，等待真实样本补齐 |
| 置信度阈值不准 | App 状态机误判 | 用离线评估报告持续校准 |

## 11. 需 Mapping AI 配合项

- 提供真实 keyframe 原图。
- 保证 `keyframes.jsonl` 中 `image_ref`、`floor_id`、`route_edge_id` 准确。
- 提供相机参数和必要位姿/平面坐标。
- 后续提供少量 query 图片和期望楼层/keyframe/区域标注。
- 如果只提供特征文件，需明确特征格式和读取方式。

## 12. 需 App AI 配合项

- 持续传递 `request_id`、`capture_mode`、`candidate_floor_id`、`route_prior`。
- 真实采图时保留原图或可复现样本，用于失败分析。
- 区分 `low_confidence`、`not_found`、`timeout` 和系统错误。
- 支持局域网 baseUrl 切换，便于本地联调。

## 13. 决策记录

当前决策：

1. 不直接把 `SuperPoint + LightGlue + hloc-style retrieval` 作为唯一主线。
2. 主服务先采用 Track A 轻量视觉原型，保证联调稳定。
3. Track B 并行做实验和 adapter，真实数据到位后再评审合入。
4. 对外 API、错误码和日志字段保持稳定，算法实现可内部替换。
