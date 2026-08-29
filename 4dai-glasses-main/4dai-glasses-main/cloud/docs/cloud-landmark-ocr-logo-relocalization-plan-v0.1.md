# 云端 Landmark OCR / LOGO 重定位方案 V0.1

更新日期：2026-05-01

## 1. 方案定位

本方案用于单个商场 Demo 的快速落地：通过拍摄商场内部店招、品牌 LOGO、导视牌、楼层牌等视觉地标，建立轻量模板库，再由云端通过 OCR 文本识别和 LOGO/图像模板匹配，估计用户当前位置。

该方案定位为：

- 单商场 Demo 的快速重定位 baseline
- 高精视觉重定位失败或低置信度时的 fallback
- 面向 App 联调的可解释定位能力

该方案不定位为：

- 生产级高精度视觉定位
- 替代 SfM / SuperPoint / LightGlue / hloc-style 重定位
- 多商场通用品牌识别平台
- 商场采集和地图包生产规范的大改

## 2. 核心思路

```text
App / 眼镜上传店招或导视牌图片
        ↓
云端 OCR 提取文字 + LOGO/模板图像匹配
        ↓
匹配 landmark / poi / route_node / route_edge
        ↓
结合 candidate_floor_id、route_prior、上一定位结果做置信度融合
        ↓
返回 ok / low_confidence / not_found
```

定位语义应表达为：

> 当前用户大概率位于可见该地标的位置附近。

不要直接把“识别到某品牌”解释为“用户就在该店铺门口”，因为同一品牌可能出现在广告牌、导视牌或多个楼层。

## 3. 模板库内容

建议优先采集真实用户视角下可见的完整店招，而不是孤立 LOGO。

每个 landmark 建议包含：

- 店招正面图
- 斜角图
- 远距离图
- 近距离图
- 不同光照图
- 导视牌上的店名图
- 中文名、英文名、别名
- 与场馆路网的绑定关系

建议内部 JSON 结构如下：

```json
{
  "landmark_id": "lm_starbucks_f1_001",
  "venue_id": "venue_demo_001",
  "floor_id": "F1",
  "poi_id": "poi_starbucks",
  "display_name": "星巴克",
  "aliases": ["STARBUCKS", "Starbucks", "星巴克咖啡"],
  "route_node_id": "node_f1_starbucks",
  "route_edge_id": "edge_f1_main_corridor_003",
  "visibility_hint": "visible_from_corridor",
  "template_images": [
    "landmarks/starbucks/front_01.jpg",
    "landmarks/starbucks/side_01.jpg"
  ]
}
```

首版可以放在云端私有配置或样例数据目录中验证，不要求立即修改场馆包规范。

## 4. 云端识别流程

### 4.1 OCR 文本匹配

适合识别：

- 店名
- 品牌英文名
- 楼层导视牌
- 出入口标识
- 电梯厅 / 扶梯标识

处理流程：

1. 解码上传图片。
2. OCR 提取文本候选。
3. 对文本做归一化：
   - 大小写归一
   - 去空格和常见标点
   - 中文/英文别名映射
4. 与 `aliases` 做精确或模糊匹配。
5. 输出候选 landmark 与 OCR 置信度。

### 4.2 LOGO / 模板图像匹配

适合识别：

- 纯图形 LOGO
- 艺术字体
- OCR 不稳定的门头图

首版建议复用当前 Track A 的轻量图像匹配路线：

- ORB / AKAZE 特征
- BFMatcher
- ratio test
- RANSAC 几何验证
- Top-K 候选输出

不建议首版直接引入重型训练或在线大模型依赖。

## 5. 置信度策略

建议将 landmark 识别作为 `visual-locate` 内部候选来源之一，与已有视觉匹配结果融合。

输入因子：

- OCR 文本置信度
- 文本 alias 匹配强度
- LOGO/模板匹配分数
- 几何内点数和内点率
- `candidate_floor_id` 是否命中
- `route_prior.edge_ids` 是否命中
- landmark 是否唯一
- 同一品牌是否多楼层或多位置重复出现

状态建议：

| 状态 | 条件 |
| --- | --- |
| `ok` | landmark 唯一、楼层/路线先验一致、识别置信度高 |
| `low_confidence` | landmark 命中但存在多位置歧义、楼层先验冲突或识别置信度中等 |
| `not_found` | OCR/LOGO 均无可靠命中 |

建议 `failure_stage` 增量候选：

- `landmark_no_hit`
- `landmark_ambiguous`
- `landmark_floor_conflict`

以上字段若进入公开响应，需要同步 OpenAPI；在未改契约前可先用于内部日志和离线评估。

## 6. API 集成方式

首版不新增公开 API，复用现有接口：

```http
POST /api/v1/localization/visual-locate
```

内部路线：

```text
visual-locate
  ├─ Track A keyframe visual matching
  ├─ Landmark OCR / LOGO matching
  └─ confidence fusion
```

公开响应仍保持：

- `status`
- `venue_id`
- `floor_id`
- `position`
- `confidence`
- `failure_stage`
- `matched_keyframes`
- `suggested_action`
- `trace_id`
- `latency_ms`

如后续需要对 App 展示“识别到哪个店招”，再评估是否扩展：

- `matched_landmark_id`
- `matched_text`
- `match_source`

## 7. 离线评估

建议扩展现有 `cloud/tools/evaluate_relocalization.py` 的 query JSONL，增加 landmark 评估字段。

示例：

```json
{
  "query_id": "q_landmark_001",
  "venue_id": "venue_demo_001",
  "image_path": "queries/f1/starbucks_front_01.jpg",
  "expected_floor_id": "F1",
  "expected_landmark_id": "lm_starbucks_f1_001",
  "expected_poi_id": "poi_starbucks",
  "candidate_floor_id": "F1",
  "route_prior": {
    "route_id": "route_001",
    "edge_ids": ["edge_f1_main_corridor_003"],
    "corridor_window_m": 2.0
  }
}
```

报告建议新增：

- landmark Top-1 accuracy
- OCR hit rate
- ambiguous landmark count
- floor conflict count
- by_landmark failure distribution

## 8. 数据采集要求

Mapping / 采集侧需要提供：

- landmark 图片
- landmark 与 `floor_id` 的关系
- landmark 与 `poi_id` 的关系
- landmark 与 `route_node_id` 或 `route_edge_id` 的关系
- 同品牌多位置标注
- 不可用或易混淆地标列表

采集时需要注意：

- 避免拍摄可识别个人信息
- 避免把广告海报误标为店铺入口
- 同一品牌在多个楼层或多个位置时必须拆成不同 landmark
- 拍摄应覆盖真实用户行进视角

## 9. App 联调方式

App 侧无需新增交互形态，可以继续上传拍摄图片到 `visual-locate`。

建议交互：

1. 进入商场后提示用户拍摄附近店招或导视牌。
2. 低置信度时提示“请面向店招/导视牌再拍一张”。
3. 若返回 `low_confidence`，App 不要直接强制导航，可请求更多图片或展示候选楼层。
4. 若返回 `not_found`，App 提示移动 1-2 米后重拍。

## 10. 风险与边界

主要风险：

- 同一品牌多位置导致歧义。
- OCR 对艺术字体、反光、模糊、倾斜角度不稳定。
- LOGO 出现在广告牌或导视牌上，不一定代表用户就在店门口。
- 单张图片只能提供地标附近约束，不等同于米级定位。
- 商场内部拍摄和品牌素材使用需要获得现场许可。

当前结论：

- 单商场 Demo 可以采用该方案作为快速落地路线。
- 云端应保持可解释返回和失败样本导出。
- 真实效果依赖 landmark 标注质量和 App 拍摄引导质量。
