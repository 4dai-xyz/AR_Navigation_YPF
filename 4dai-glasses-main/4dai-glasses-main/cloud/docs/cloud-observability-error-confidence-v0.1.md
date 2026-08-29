# 云端日志、错误码、置信度策略 V0.1

更新日期：2026-04-30

## 1. 文档目的

本文档定义当前云端骨架使用的：

- 结构化日志字段
- 错误码策略
- 超时策略
- 置信度判定策略

本文档与以下实现保持一致：

- `cloud/app/core/error_codes.py`
- `cloud/app/core/confidence.py`
- `cloud/app/main.py`

## 2. 日志策略

当前云端统一输出结构化 JSON 日志。

最低字段：

- `timestamp`
- `method`
- `path`
- `status_code`
- `request_id`
- `trace_id`
- `venue_id`
- `capture_mode`
- `candidate_floor_id`
- `resolved_floor_id`
- `status`
- `confidence`
- `failure_stage`
- `latency_ms`

当前已落地事件：

- `request_completed`
- `api_error`
- `validation_error`
- `internal_error`
- `operation_timeout`
- `visual_locate`
- `indoor_route`

## 3. 错误码策略

沿用现有 API 草案中的业务码：

| code | 含义 |
| --- | --- |
| `0` | 成功 |
| `1001` | 参数错误 |
| `1002` | 场馆不存在 |
| `1003` | 楼层不存在 |
| `1004` | 目标点不存在 |
| `2001` | 图像解析失败 |
| `2002` | 重定位失败 |
| `2003` | 重定位超时 |
| `2004` | 图像质量不足 |
| `3001` | 路径规划失败 |
| `3002` | 路径规划超时 |
| `4001` | 鉴权占位拒绝 |
| `4002` | 限流占位拒绝 |
| `9001` | 服务内部错误 |

当前工程约定：

1. HTTP 层错误和业务层错误分离。
2. `visual-locate` 的 `ok / low_confidence / not_found` 仍通过 `code=0` 返回。
3. 真正的参数或资源错误，返回非 `0` 业务码。
4. 场馆包根目录缺失、必需文件缺失、JSON 解析失败、字段或路网引用异常，统一返回 `9001`，并在 `data.error_type` 标记为 `venue_package_error`。
5. 鉴权占位默认关闭；启用后，缺失或错误 Bearer token 返回 `401 + 4001`。
6. 限流占位默认关闭；启用后，单机内存限流命中返回 `429 + 4002`。

场馆包错误详情字段：

| 字段 | 说明 |
| --- | --- |
| `error_type` | 固定为 `venue_package_error` |
| `stage` | `root_not_found`、`missing_file`、`json_parse`、`validation` 等阶段 |
| `package_root` | 当前服务加载的场馆包根目录 |
| `missing_files` | 缺失文件列表，仅文件缺失时返回 |
| `validation_errors` | 字段、楼层、POI、路网、入口、连接点、相机、keyframe 引用错误列表 |

## 4. 超时策略

当前骨架把视觉重定位和路径规划都包在超时保护中。

默认阈值：

- `AI_GLASSES_RELOCALIZATION_TIMEOUT_MS = 3000`
- `AI_GLASSES_ROUTE_TIMEOUT_MS = 1500`

超时约定：

1. 重定位超时返回 `2003`
2. 路径规划超时返回 `3002`
3. 超时响应保留原始 `request_id`
4. 超时响应 `data.operation` 标记 `visual_locate` 或 `indoor_route`
5. `operation_timeout` 日志记录 `request_id / operation / timeout_ms / code`
6. App 可直接基于业务码区分“算法失败”和“超时失败”

## 5. 失败阶段策略

当前骨架会返回以下 `failure_stage`：

- `image_quality_failed`
- `retrieval_no_hit`
- `low_confidence`

其余枚举：

- `match_insufficient`
- `geometry_failed`
- `route_snap_failed`

保留给后续更完整的视觉定位实现。

## 6. 置信度策略

当前 baseline 不是最终算法分数，而是面向首版链路的工程化置信度。

Track A 在线视觉链路使用 `VisualRelocalizationEvidence` 做融合；字节 fallback 链路仍保留旧版 `assess_relocalization` 作为兼容入口。

Track A 输入因子：

- `match_score`
- `second_match_score`
- `good_match_count`
- `inlier_count`
- `inlier_ratio`
- `payload_bytes`
- `image_decoded`
- `brightness_score`
- `blur_score`
- `floor_prior_hit`
- `floor_prior_mismatch`
- `route_prior_hit`

核心阈值：

- `accepted = 0.75`
- `low = 0.40`
- `retrieval_min = 0.20`
- `margin_good = 0.12`
- `visual_margin_good = 0.18`
- `min_good_matches = 4`
- `min_inliers = 4`
- `min_inlier_ratio = 0.12`
- `min_payload_bytes = 16`

状态判定：

1. 图片过小，直接返回 `not_found + image_quality_failed`
2. 匹配点不足，返回 `not_found + match_insufficient`
3. 几何内点不足或内点率过低，返回 `not_found + geometry_failed`
4. 置信度大于等于 `0.75`，返回 `ok`
5. 置信度位于 `0.40` 到 `0.75`，返回 `low_confidence`
6. 其余情况返回 `not_found + retrieval_no_hit`

当前调试字段还会记录：

- `match_count_score`
- `inlier_count_score`
- `inlier_ratio_score`
- `margin_score`
- `quality_score`
- `prior_score`
- `prior_penalty`
- `confidence`

样例包内 keyframe 图片当前为占位字节。为保持 smoke 可跑，Track A 对 decode 失败但 payload 非空的样例图会进入 fallback 图像匹配；这类图像质量分按中性值参与融合。真实场馆包接入后，置信度稳定性以真实 keyframe/query 图像的离线评估报告为准。

## 7. 建议动作策略

| status | suggested_action |
| --- | --- |
| `ok` | `continue_navigation` |
| `low_confidence` | `request_more_images` |
| `not_found` | `retry_after_move` |

## 8. 当前边界

当前骨架中的置信度是可运行 baseline，作用是：

- 跑通 App 状态机
- 跑通日志与评估
- 为后续真实算法替换预留接口

它不等同于最终上线版的定位置信度模型。
