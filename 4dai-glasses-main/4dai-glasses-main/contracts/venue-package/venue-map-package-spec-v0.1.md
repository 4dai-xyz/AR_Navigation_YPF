# 场馆地图包字段说明 V0.1

更新日期：2026-04-30

## 1. 文档目的

本文档定义首版 Demo 使用的“场馆地图包”结构和字段约定，目标是让以下角色对齐同一份数据标准：

- 地图采集团队
- 数据标注团队
- 云端定位服务
- 室内路径规划服务
- 手机 App

本文档只覆盖首版 MVP 所需字段，不引入双目、IMU、众包建图等后续扩展字段。

## 2. 设计目标

首版场馆地图包需要同时满足四件事：

1. 支撑室内视觉重定位
2. 支撑室内路径规划
3. 支撑店铺门口级目标点导航
4. 支撑固定 1-2 个场馆的快速迭代更新

因此地图包不是单纯的点云，也不是单纯的平面图，而是一个“可定位 + 可导航 + 可发布”的组合数据包。

首版仍保持自定义轻量结构，但字段命名和层级尽量兼容 IMDF / GeoJSON 的表达习惯，避免后续多场馆扩展时重新设计整套格式。

## 3. 包级别约定

### 3.1 包格式

建议首版使用：

- 发布格式：`zip`
- 包内元数据格式：`json`
- 坐标单位：`meter`
- 时间格式：`ISO 8601`

### 3.2 包命名建议

```text
<venue_id>_<package_version>.zip
```

示例：

```text
venue_sh_mall_001_0.1.0.zip
```

说明：

- 当前发布脚本 `mapping/tools/publish_venue_package.py` 默认按 `manifest.venue_id` 与 `manifest.package_version` 生成 zip 文件名。
- 若交付方需要使用 `venue_code` 或其他外部命名，可在发布时显式传入 `--archive-name`，但 canonical 默认命名以脚本输出为准。

### 3.3 包目录结构建议

```text
venue-package/
├─ manifest.json
├─ venue.json
├─ floors.json
├─ pois.json
├─ entrances.json
├─ connectors.json
├─ route_graph.json
├─ localization/
│  ├─ cameras.json
│  ├─ keyframes.jsonl
│  ├─ images/
│  │  └─ *.jpg
│  ├─ coverage_report.json
│  ├─ sfm/
│  │  └─ colmap/
│  ├─ features/
│  │  └─ superpoint/
│  │     ├─ features_index.json
│  │     └─ *.npz
│  └─ retrieval/
│     └─ descriptors.faiss
└─ assets/
   ├─ floorplans/
   └─ preview/
```

说明：

- `manifest.json`：整个包的索引与版本入口
- `venue.json`：场馆基础信息
- `floors.json`：楼层定义
- `pois.json`：店铺门口和关键点位
- `entrances.json`：外部入场口定义
- `connectors.json`：扶梯等跨层连接点
- `route_graph.json`：室内路径网络
- `localization/`：视觉重定位所需资产

## 4. 顶层文件说明

## 4.1 manifest.json

用途：

- 标识地图包版本
- 描述文件清单
- 告诉云端如何加载这个包

建议字段如下：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `package_id` | string | 是 | 地图包唯一 ID |
| `package_version` | string | 是 | 地图包版本号，例如 `0.1.0` |
| `venue_id` | string | 是 | 场馆唯一 ID |
| `venue_code` | string | 是 | 人类可读短编码 |
| `created_at` | string | 是 | 包生成时间 |
| `created_by` | string | 否 | 生成人或生成流程 |
| `schema_version` | string | 是 | 当前数据结构版本 |
| `coordinate_system` | string | 是 | 坐标系名称，首版建议固定 |
| `unit` | string | 是 | 单位，首版固定为 `meter` |
| `default_floor_id` | string | 是 | 默认楼层，必须能在 `floors[].floor_id` 中找到 |
| `files` | array | 是 | 文件清单 |
| `checksums` | object | 否 | 文件校验值 |
| `notes` | string | 否 | 备注 |

`files` 内元素建议字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 文件逻辑名 |
| `path` | string | 是 | 包内路径 |
| `type` | string | 是 | 文件类型，如 `json`、`pcd`、`bin` |
| `required` | boolean | 是 | 是否必须 |

示例：

```json
{
  "package_id": "pkg_sh_mall_001_0.1.0",
  "package_version": "0.1.0",
  "venue_id": "venue_sh_mall_001",
  "venue_code": "sh_mall_001",
  "created_at": "2026-04-28T11:30:00+08:00",
  "schema_version": "0.1.0",
  "coordinate_system": "venue_local_2d",
  "unit": "meter",
  "default_floor_id": "F1",
  "files": [
    {
      "name": "venue",
      "path": "venue.json",
      "type": "json",
      "required": true
    },
    {
      "name": "route_graph",
      "path": "route_graph.json",
      "type": "json",
      "required": true
    }
  ]
}
```

## 4.2 venue.json

用途：

- 描述场馆整体信息
- 定义与室外导航衔接的基础上下文

建议字段如下：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `venue_id` | string | 是 | 场馆唯一 ID |
| `venue_name` | string | 是 | 场馆名称 |
| `venue_type` | string | 是 | 如 `mall`、`office` |
| `city` | string | 否 | 城市 |
| `address` | string | 否 | 地址 |
| `geo_center` | object | 否 | 场馆地理中心点 |
| `bounding_box` | object | 否 | 场馆边界框 |
| `supported_modes` | array | 否 | 支持模式，如 `indoor_navigation` |
| `default_entry_strategy` | string | 否 | 默认入口策略 |
| `default_target_type` | string | 否 | 首版建议为 `store_door` |

`geo_center` 建议字段：

- `lat`
- `lng`

`bounding_box` 建议字段：

- `min_lat`
- `max_lat`
- `min_lng`
- `max_lng`

## 4.3 floors.json

用途：

- 定义楼层信息和楼层顺序

建议结构：

```json
{
  "floors": [
    {
      "floor_id": "B1",
      "floor_name": "B1",
      "floor_index": -1,
      "is_public": true,
      "z": -4.5
    },
    {
      "floor_id": "F1",
      "floor_name": "1F",
      "floor_index": 1,
      "is_public": true,
      "z": 0.0
    }
  ]
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `floor_id` | string | 是 | 楼层唯一 ID |
| `floor_name` | string | 是 | 展示名称 |
| `floor_index` | integer | 是 | 排序索引 |
| `is_public` | boolean | 否 | 是否公共开放 |
| `z` | number | 否 | 楼层参考高度 |

## 4.4 pois.json

用途：

- 定义导航目标点
- 首版重点用于“店铺门口级目标点”

建议字段如下：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `poi_id` | string | 是 | POI 唯一 ID |
| `poi_type` | string | 是 | 如 `store_door`、`service_desk` |
| `poi_name` | string | 是 | POI 名称 |
| `venue_id` | string | 是 | 所属场馆 |
| `floor_id` | string | 是 | 所属楼层 |
| `position` | object | 是 | 平面坐标 |
| `arrival_radius_m` | number | 否 | 到达判定半径，首版 Demo 可用于店铺门口到达判断 |
| `heading` | number | 否 | 朝向角 |
| `route_node_id` | string | 是 | 绑定的路径图节点 |
| `tags` | array | 否 | 分类标签 |
| `status` | string | 否 | 是否可用 |

`position` 建议字段：

- `x`
- `y`

示例：

```json
{
  "pois": [
    {
      "poi_id": "poi_store_001",
      "poi_type": "store_door",
      "poi_name": "瑞幸咖啡门口",
      "venue_id": "venue_sh_mall_001",
      "floor_id": "F2",
      "position": {
        "x": 42.3,
        "y": 18.6
      },
      "heading": 90,
      "route_node_id": "node_f2_102",
      "tags": ["coffee", "pickup"],
      "status": "active"
    }
  ]
}
```

## 4.5 entrances.json

用途：

- 定义室外导航和室内导航的衔接点
- 室外终点建议规划到“最接近目标店铺的入场口”

建议字段如下：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `entrance_id` | string | 是 | 入场口唯一 ID |
| `entrance_name` | string | 是 | 入口名称 |
| `venue_id` | string | 是 | 所属场馆 |
| `floor_id` | string | 是 | 通向的室内楼层 |
| `geo_position` | object | 是 | 对应高德侧地理点 |
| `route_node_id` | string | 否 | 若已人工锚定，可直接绑定入口对应路径节点 |
| `indoor_position` | object | 是 | 对应室内坐标 |
| `supported_vehicle` | array | 否 | 如 `ebike`、`walk` |
| `status` | string | 否 | 是否启用 |

## 4.6 connectors.json

用途：

- 定义跨层连接点
- 首版重点考虑扶梯

建议字段如下：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `connector_id` | string | 是 | 跨层点唯一 ID |
| `connector_type` | string | 是 | 首版可为 `escalator` |
| `name` | string | 否 | 名称 |
| `from_floor_id` | string | 是 | 起始楼层 |
| `to_floor_id` | string | 是 | 目标楼层 |
| `from_node_id` | string | 是 | 起始节点 |
| `to_node_id` | string | 是 | 目标节点 |
| `edge_id` | string | 否 | 若已明确映射，可直接绑定跨层路径边 |
| `direction` | string | 否 | `up`、`down`、`bidirectional` |
| `cost_seconds` | number | 否 | 预计通行耗时 |
| `status` | string | 否 | 是否可用 |

## 4.7 route_graph.json

用途：

- 定义室内可通行路径网络
- 云端路径规划模块直接依赖它

建议结构：

```json
{
  "nodes": [],
  "edges": []
}
```

`nodes` 建议字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `node_id` | string | 是 | 节点 ID |
| `floor_id` | string | 是 | 所属楼层 |
| `x` | number | 是 | 平面坐标 x |
| `y` | number | 是 | 平面坐标 y |
| `node_type` | string | 是 | 如 `walkway`、`entrance`、`poi_anchor`、`connector` |
| `ref_id` | string | 否 | 关联的 entrance、poi、connector ID |

`edges` 建议字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `edge_id` | string | 是 | 边 ID |
| `from_node_id` | string | 是 | 起点 |
| `to_node_id` | string | 是 | 终点 |
| `distance` | number | 是 | 距离，单位米 |
| `travel_mode` | string | 是 | 如 `walk`、`escalator` |
| `bidirectional` | boolean | 是 | 是否双向 |
| `cost_seconds` | number | 否 | 建议路径权重 |
| `status` | string | 否 | 是否可用 |

示例：

```json
{
  "nodes": [
    {
      "node_id": "node_f2_102",
      "floor_id": "F2",
      "x": 42.0,
      "y": 18.1,
      "node_type": "poi_anchor",
      "ref_id": "poi_store_001"
    }
  ],
  "edges": [
    {
      "edge_id": "edge_f2_201",
      "from_node_id": "node_f2_101",
      "to_node_id": "node_f2_102",
      "distance": 6.4,
      "travel_mode": "walk",
      "bidirectional": true,
      "cost_seconds": 7
    }
  ]
}
```

## 4.8 localization/

用途：

- 存放视觉重定位服务使用的核心资产

首版建议至少包含：

| 文件 | 必须 | 说明 |
| --- | --- | --- |
| `cameras.json` | 是 | 相机内参与采集设备信息 |
| `keyframes.jsonl` | 是 | 关键帧索引，每行一个关键帧 |
| `images/` | 是 | `keyframes.jsonl.image_ref` 引用的关键帧图片目录 |
| `retrieval/descriptors.faiss` | 是 | 全局检索索引 |
| `features/superpoint/features_index.json` | 是 | 局部特征文件索引 |
| `features/superpoint/` | 是 | 局部特征缓存目录 |
| `sfm/colmap/` | 否 | COLMAP / SfM 重建资产 |
| `coverage_report.json` | 否 | 采集覆盖率和高风险区域报告 |

`keyframes.jsonl` 建议字段如下：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `keyframe_id` | string | 是 | 关键帧 ID |
| `floor_id` | string | 是 | 所属楼层 |
| `venue_xy` | object | 是 | 场馆局部平面坐标 |
| `camera_pose` | object | 否 | 相机在场馆坐标系下的位姿 |
| `intrinsics_id` | string | 否 | 相机内参引用 |
| `heading` | number | 否 | 朝向 |
| `capture_device` | string | 否 | 采集设备，如手机、眼镜、运动相机 |
| `time_bucket` | string | 否 | 采集时段，如午间、夜间、周末 |
| `quality_score` | number | 否 | 模糊、曝光、遮挡等质量评分 |
| `route_edge_id` | string | 否 | 吸附到的路径边 |
| `image_ref` | string | 否 | 参考图片路径 |
| `feature_ref` | string | 否 | 特征索引引用 |

## 5. 坐标与空间约定

首版建议统一采用场馆局部二维平面坐标：

- 原点：场馆自定义固定点
- `x`：平面横向
- `y`：平面纵向
- `z`：楼层参考高度，仅用于楼层辅助

建议规则：

1. 所有 POI、入口、路网节点都必须落在统一坐标系中
2. 同一场馆不同楼层允许共享 `x/y` 平面语义，但 `floor_id` 必须明确
3. 与室外导航衔接时，只在入口层面使用地理坐标

## 6. 版本控制建议

建议拆成两个版本概念：

1. `schema_version`
说明字段结构版本，影响解析逻辑

2. `package_version`
说明场馆数据内容版本，影响上线数据

建议规则：

- 字段结构变化时，更新 `schema_version`
- 场馆 POI、路网、定位资产调整时，更新 `package_version`

## 7. MVP 必填字段最小集合

如果首版只做最小闭环，建议保证下面这些字段先齐：

- `manifest.package_id`
- `manifest.package_version`
- `manifest.venue_id`
- `manifest.default_floor_id`
- `venue.venue_id`
- `venue.venue_name`
- `floors[].floor_id`
- `pois[].poi_id`
- `pois[].floor_id`
- `pois[].position`
- `pois[].route_node_id`
- `entrances[].entrance_id`
- `entrances[].geo_position`
- `entrances[].indoor_position`
- `connectors[].connector_id`
- `connectors[].from_floor_id`
- `connectors[].to_floor_id`
- `route_graph.nodes`
- `route_graph.edges`
- `localization.cameras.json`
- `localization.keyframes.jsonl`
- `localization.images/`
- `localization.retrieval/descriptors.faiss`
- `localization.features/superpoint/features_index.json`
- `localization.features/superpoint/`

## 8. 发布前校验建议

场馆地图包发布前，建议至少校验：

1. 所有 `poi.route_node_id` 都能在 `route_graph.nodes` 中找到
2. 所有 `connector.from_node_id` 和 `connector.to_node_id` 都存在
3. 所有 `floor_id` 都能在 `floors.json` 中找到
4. 所有入口都同时有 `geo_position` 和 `indoor_position`
5. `manifest.files` 中声明的必须文件都真实存在
6. `keyframes.jsonl` 中引用的 `image_ref / feature_ref` 都真实存在
7. `features_index.json` 中列出的 `keyframe_id / path` 与关键帧索引一致
8. 若 `entrance.route_node_id` 已填写，则节点楼层与入口楼层一致
9. 若 `connector.edge_id` 已填写，则对应边存在且与跨层方向一致
10. 场馆包版本号和发布时间已更新

发布脚本当前会额外产出：

- `*.report.json`
- `*.checksums.json`

用于保存本次发布的校验摘要和声明文件校验值。

## 9. 首版暂不纳入的字段

为了避免地图包被设计得过重，以下字段建议首版先不做：

- 动态人流热度图
- 店铺营业时间
- 电梯拥挤度
- 临时施工封路实时更新
- IMU 融合参数
- 双目深度参数
- 众包上传审核信息
