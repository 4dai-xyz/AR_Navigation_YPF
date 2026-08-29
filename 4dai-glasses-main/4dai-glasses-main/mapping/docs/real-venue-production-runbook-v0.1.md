# 真实场馆包生产演练与 QA 清单 V0.1

更新时间：2026-04-30

## 1. 文档目的

本文档把当前“样例包 + 规格 + SOP + 工具”收口成真实场馆数据到位后的固定生产流程。它用于首个真实场馆包装配、校验、发布前 QA 和交付验收，不用于伪造真实场馆数据。

适用范围：

- `mapping/examples/venue-package-example/`
- `contracts/venue-package/venue-map-package-spec-v0.1.md`
- `mapping/docs/real-venue-package-prep-v0.1.md`
- `mapping/tools/validate_venue_package.py`
- `mapping/tools/publish_venue_package.py`
- `mapping/algorithms/relocalization/`

## 2. 一致性基线

当前基线结论：

| 项目 | 结论 | 处理方式 |
| --- | --- | --- |
| 样例包必备文件 | 与 `validate_venue_package.py` 的 `TOP_LEVEL_REQUIRED` 一致 | 保持样例包 smoke 价值，不改样例数据 |
| `manifest.files` | 声明 10 个业务必备文件，不声明 `manifest.json` 本身 | 接受；校验脚本只要求 `manifest.json` 存在，不强制它出现在 `manifest.files` |
| `localization/images/` | 样例包和 keyframe 引用已使用该目录 | 已在规格中显式列出 |
| `pois[].arrival_radius_m` | 样例包已有该可选字段 | 已在规格中作为可选 Demo 字段列出 |
| 发布产物 | `publish_venue_package.py` 输出 zip、report、checksums | 真实发布沿用同一命令 |

不一致处理原则：

1. 若样例包、规格、脚本三者冲突，先以校验脚本实际行为作为发布门禁。
2. 若字段已被样例或真实装包流程使用，但规格缺失，优先补规格说明。
3. 不为未到位的真实数据制造占位真实值。

## 3. 真实生产输入盘点

真实装包前必须先建立一份素材盘点表。

| 类别 | 必需内容 | 文件或表格要求 | 进入装包条件 |
| --- | --- | --- | --- |
| 场馆信息 | 场馆名、城市、地址、场馆类型 | 文本表 | `venue_id / venue_code` 已冻结 |
| 楼层信息 | Demo 涉及楼层、展示名、排序、参考高度 | 表格 | `floor_id` 与命名规范一致 |
| S20 数据 | 原始工程、后处理点云、楼层切片 | 文件目录 | 每个 Demo 楼层有可标注底图 |
| 底图 | 每层俯视底图、坐标原点、x/y 方向 | 图片 + 坐标说明 | 能被 QGIS 作为标注底图 |
| 入口 | 最优入场口、经纬度、室内坐标、现场照片 | 标注表 | 至少 1 个入口进入主路线 |
| 目标 POI | 店铺门口位置、楼层、现场照片 | 标注表 | 至少 1 个目标店铺门口 |
| 跨层点 | 扶梯/楼梯/电梯上下口、方向 | 标注表 | 主路线跨层关系明确 |
| 主路网 | 节点、边、距离、通行模式 | QGIS 图层或表格 | 入口到目标 POI 可连通 |
| RGB 补采 | 原图、采集设备、采集路线、采集时间 | 图片目录 + 说明 | 覆盖入口、转角、扶梯、POI |
| keyframe | 筛选图、楼层、坐标、朝向、绑定边 | JSONL 或表格 | 每条引用可落到真实文件 |
| 特征资产 | `descriptors.faiss`、`features_index.json`、`*.npz` | 文件目录 | 与 keyframe ID 一一可追溯 |

## 4. 装配流程演练

### 4.1 准备工作目录

真实数据到位后，新建独立工作目录，不要覆盖样例包：

```text
mapping/work/venue-packages/<venue_code>/
```

若只做流程演练，使用 `mapping/examples/venue-package-example/` 作为只读参照。

### 4.2 冻结 ID

先填 ID，不先填坐标：

1. 确定 `venue_id / venue_code / package_id`。
2. 确定所有 `floor_id`。
3. 为入口、目标 POI、扶梯、路网节点、路网边、keyframe 预分配 ID。
4. 把 ID 写入人工标注表，后续不随坐标微调而改名。

### 4.3 装配顶层文件

按顺序装配：

1. `venue.json`：只写场馆基础信息和默认策略。
2. `floors.json`：只写 Demo 涉及楼层。
3. `route_graph.json`：先写主路线节点和边。
4. `entrances.json`：入口绑定到入口节点。
5. `connectors.json`：扶梯/跨层点绑定上下口节点和跨层边。
6. `pois.json`：目标店铺门口绑定到 POI anchor 节点。

顺序原因：

- `pois.route_node_id`
- `entrances.route_node_id`
- `connectors.from_node_id`
- `connectors.to_node_id`

这些字段都依赖 `route_graph.nodes` 先存在。

### 4.4 装配定位资产

按顺序装配：

1. 把筛选后的 keyframe 图片放入 `localization/images/`。
2. 填写 `localization/cameras.json`。
3. 填写 `localization/keyframes.jsonl`。
4. 生成或复制 `localization/features/superpoint/*.npz`。
5. 填写 `localization/features/superpoint/features_index.json`。
6. 生成或复制 `localization/retrieval/descriptors.faiss`。

关键规则：

- `keyframes.jsonl.image_ref` 使用相对 `localization/` 的路径，例如 `images/kf_f1_0001.jpg`。
- `keyframes.jsonl.feature_ref` 使用相对 `localization/` 的路径，例如 `features/superpoint/kf_f1_0001.npz`。
- `features_index.json.feature_files[].keyframe_id` 必须存在于 `keyframes.jsonl`。

### 4.5 装配 manifest

`manifest.files` 必须声明 10 个业务必备文件：

- `venue.json`
- `floors.json`
- `pois.json`
- `entrances.json`
- `connectors.json`
- `route_graph.json`
- `localization/cameras.json`
- `localization/keyframes.jsonl`
- `localization/retrieval/descriptors.faiss`
- `localization/features/superpoint/features_index.json`

装配期可先不回填 `manifest.checksums`；发布前必须回填并通过严格发布。

## 5. QA 检查清单

### 5.1 文件与 manifest

| 检查项 | 方法 | 通过标准 |
| --- | --- | --- |
| 必备文件存在 | 运行 `validate_venue_package.py` | 无 `missing required file` |
| `manifest.files` 路径有效 | 查看校验 JSON | 无 required file not found |
| checksums 正确 | 发布后回填再校验 | 无 `manifest.checksums mismatch` |
| zip 包内容完整 | 打开发布 zip 或查看 report | zip 内包含必备文件 |

### 5.2 楼层与坐标

| 检查项 | 方法 | 通过标准 |
| --- | --- | --- |
| `default_floor_id` 存在 | 校验脚本 | 能在 `floors[].floor_id` 找到 |
| 楼层排序正确 | 人工核对 `floor_index` | 扶梯方向与楼层顺序一致 |
| 坐标单位一致 | 对照底图比例 | 全包使用 meter |
| POI/入口/keyframe 坐标同源 | 抽查底图 | 坐标都来自同一局部坐标系 |

### 5.3 路网连通性

| 高风险问题 | 检查方法 | 修复口径 |
| --- | --- | --- |
| 路网断裂 | 从入口节点手工追到目标 POI 节点 | 补 `route_graph.edges` 或修正节点 ID |
| 边引用未知节点 | 运行校验脚本 | 修正 `from_node_id / to_node_id` |
| 单向边方向错 | 核对 `bidirectional` 和现场动线 | 修正边方向或新增反向边 |
| 距离明显异常 | 对照底图量距 | 修正 `distance` |

### 5.4 入口、POI、扶梯

| 高风险问题 | 检查方法 | 通过标准 |
| --- | --- | --- |
| 入口不在主路径上 | `entrances.route_node_id` 对照主路线节点序列 | 入口节点是主路线起点或可连到起点 |
| POI 坐标错 | `pois.position` 与 `route_node_id` 节点坐标对照 | 坐标在店铺门口附近 |
| POI 绑定错节点 | 校验脚本 + 人工看图 | `route_node_id` 在同楼层且是 POI anchor |
| 扶梯上下口反了 | 核对 `from_floor_id / to_floor_id / direction` | 与真实上/下行一致 |
| connector 无边连接 | 校验脚本 | 上下口节点之间存在跨层边 |

### 5.5 keyframe 覆盖

| 检查项 | 方法 | 通过标准 |
| --- | --- | --- |
| 入口覆盖 | 查看 keyframe 表 | 每个主入口至少 2 张 |
| 转角覆盖 | 查看 keyframe 表和底图 | 每个关键转角至少 1-2 张 |
| 扶梯覆盖 | 查看 keyframe 表 | 上下口各至少 2 张 |
| POI 覆盖 | 查看 keyframe 表 | 目标店铺门口至少 2 张 |
| 引用文件存在 | 校验脚本 | 无 `referenced file not found` |
| 特征索引对齐 | 校验脚本 | 无 unknown `keyframe_id` 或 missing feature file |

## 6. 校验与发布流程

装配期校验：

```bash
python mapping/tools/validate_venue_package.py <真实场馆包目录> --json
```

发布前校验和打包：

```bash
python mapping/tools/publish_venue_package.py <真实场馆包目录> --output-dir dist/mapping-publish-check --strict-warnings
```

发布前硬门禁：

- `ok=true`
- `errors=[]`
- `warnings=[]`
- `*.report.json` 已生成
- `*.checksums.json` 已生成
- zip 包路径已记录

## 7. 离线评估准备

Mapping baseline fixture 从以下模板复制：

```text
mapping/algorithms/relocalization/templates/real_venue_eval_fixture_template.json
```

填写规则：

- `venue_id` 必须等于真实包 `manifest.venue_id`。
- `floors[].floor_id` 只写参与评估的楼层。
- `keyframes[].keyframe_id` 必须能在真实包 `keyframes.jsonl` 找到。
- `queries[].expected_floor_id` 来自人工 ground truth。
- `queries[].expected_status` 用于声明预期定位状态；负样本可填 `not_found`。
- `queries[].expected_position` 来自人工标定或复核点。
- `queries[].expected_route_node_id` 用于记录最近路网节点。
- `queries[].expected_poi_id` 仅在查询点确实对应目标 POI 或 POI 附近时填写。

Mapping baseline 评估脚本使用 `payload_token` 验证工程链路，不读取真实图片；真实图片评估使用下面的 Cloud 图片评估查询模板。

Cloud 图片评估查询从以下模板复制：

```text
mapping/algorithms/relocalization/templates/cloud_image_eval_queries_template.jsonl
```

Cloud 图片评估查询由 `cloud/tools/evaluate_relocalization.py` 消费，必填 `query_id / image_path / venue_id`，并通过 `--query-root` 读取真实图片。不要把 baseline fixture 的 `payload_token` 字段用于 Cloud 图片评估。

## 8. 交付验收

真实场馆包进入 Cloud 前必须交付：

- 场馆包目录。
- `validate_venue_package.py --json` 输出。
- 发布 zip。
- `*.report.json`。
- `*.checksums.json`。
- QA 检查表结论。
- Mapping baseline fixture、Cloud 图片评估查询和对应评估输出。
