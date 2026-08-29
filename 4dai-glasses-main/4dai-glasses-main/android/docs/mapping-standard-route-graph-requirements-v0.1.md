# Mapping 标准化室内路网图交付说明 v0.1

本文档面向 Mapping 侧，用于产出 Android App 后续支持“从任意入口到目标店铺自动规划室内路径”所需的标准化路网图。

当前 App 已具备：

- 高德室外导航与手动 `Enter Venue`
- 高德室内底图宿主
- route-only 手动室内演示
- 高德室内底图 GCJ-02 overlay 叠加
- fallback canvas preview

当前 App 不具备：

- 真实视觉定位闭环
- 自动入场判断
- 自动选择真实入口
- 基于完整室内路网的自动路径规划

本轮 Mapping 交付目标是补齐“可计算路网”，不是补视觉定位资产。

## 1. 目标

Mapping 侧需要为目标场馆输出一个标准化路网包，使 App 能够：

1. 读取入口、店铺、通道转角、扶梯、楼梯、电梯等节点。
2. 读取节点之间的可通行边。
3. 根据 `start_node_id` 和 `target_poi_id` 在 App 侧执行最短路搜索。
4. 按楼层拆分路径，用于高德室内底图叠加。
5. 保留 `app_preview_xy`，用于无高德底图时的 fallback preview。
6. 保留 `gcj02`，用于高德室内底图 overlay。

## 2. 交付文件

Mapping 侧在对应场馆草稿目录下输出：

```text
mapping/drafts/<venue_id>/standard_route_graph.json
mapping/drafts/<venue_id>/APP_ROUTE_GRAPH_INTEGRATION.md
```

如果继续沿用当前五道口目录，文件路径为：

```text
mapping/drafts/wudaokou-route-demo-v0.1/standard_route_graph.json
mapping/drafts/wudaokou-route-demo-v0.1/APP_ROUTE_GRAPH_INTEGRATION.md
```

App 后续会将 `standard_route_graph.json` 复制到：

```text
android/ai-glasses-poc/app/src/main/assets/indoor_routes/<venue_id>_standard_route_graph.json
```

App 不在运行时直接读取 `mapping/` 目录。

## 3. 坐标口径

每个路网节点必须同时区分以下坐标用途：

| 字段 | 必填 | 用途 | 说明 |
| --- | --- | --- | --- |
| `venue_xy` | 是 | Mapping 内部路网计算 | 可用于边长度计算，不直接画到高德地图 |
| `app_preview_xy` | 是 | App fallback canvas preview | 必须落在当前预览范围 `x: 0..50, y: 0..30` 内 |
| `gcj02` | 条件必填 | 高德室内底图 overlay | 已校准节点必须提供；未校准节点可标为 `estimated` |

约束：

- `app_preview_xy` 不能用于高德地图叠加。
- `venue_xy` 不能当作经纬度。
- `gcj02` 使用高德坐标系。
- 如果只有两锚点配准，必须在节点里标注 `alignment_role=estimated`。
- 如果每层有独立配准，必须在 `floor_transforms` 中声明。

## 4. 标准 JSON Schema 口径

`standard_route_graph.json` 顶层结构：

```json
{
  "schema_version": "standard_route_graph.v0.1",
  "venue_id": "venue_bj_wudaokou_shopping_center_demo",
  "venue_name": "北京五道口购物中心",
  "coordinate_system": {
    "map_overlay": "GCJ-02",
    "preview": "app_preview_xy",
    "routing": "venue_xy"
  },
  "floors": [],
  "nodes": [],
  "edges": [],
  "pois": [],
  "entrances": [],
  "connectors": [],
  "routing_profiles": []
}
```

## 5. `floors`

楼层定义：

```json
{
  "floor_id": "F1",
  "display_name": "1F",
  "amap_floor_name": "1F",
  "order": 1
}
```

要求：

- `floor_id` 使用 App 内部稳定 ID，例如 `F1 / F2 / B1`。
- `amap_floor_name` 使用高德室内底图真实楼层名，例如 `1F / 2F`。
- `order` 用于判断上楼 / 下楼方向。

## 6. `nodes`

节点定义：

```json
{
  "node_id": "node_f1_west_gate_demo",
  "floor_id": "F1",
  "label": "F1 西门入口",
  "node_type": "entrance",
  "venue_xy": {
    "x": 18.669,
    "y": 143.439
  },
  "app_preview_xy": {
    "x": 6.0,
    "y": 23.7
  },
  "gcj02": {
    "lat": 39.991583,
    "lng": 116.338965,
    "alignment_role": "anchor"
  },
  "tags": [
    "route_demo"
  ]
}
```

节点类型枚举：

```text
entrance
waypoint
store_door
escalator
stairs
elevator
connector
service_point
```

要求：

- `node_id` 全场馆唯一且稳定。
- 店铺门口使用 `store_door`。
- 扶梯 / 楼梯 / 电梯的楼层内端点使用独立节点。
- 跨楼层连接不通过平面线表达，使用 `connectors` 表达。

## 7. `edges`

边定义：

```json
{
  "edge_id": "edge_f1_west_gate_to_turn",
  "from_node_id": "node_f1_west_gate_demo",
  "to_node_id": "node_f1_west_corridor_turn_demo",
  "floor_id": "F1",
  "edge_type": "walkway",
  "bidirectional": true,
  "distance_m": 28.0,
  "weight": 28.0,
  "instruction": "沿一层通道直行",
  "geometry_node_ids": [
    "node_f1_west_gate_demo",
    "node_f1_west_corridor_turn_demo"
  ]
}
```

边类型枚举：

```text
walkway
turn
escalator
stairs
elevator
restricted
```

要求：

- 同层通行边必须有 `floor_id`。
- 跨层边不直接画在地图上，只用于路径搜索。
- `distance_m` 用于展示和基础权重。
- `weight` 用于路径规划，可在 `distance_m` 基础上加入转弯、跨层、拥堵或无障碍代价。
- `geometry_node_ids` 用于绘制路径，不要求每条边只有两个端点。

## 8. `connectors`

跨层连接定义：

```json
{
  "connector_id": "connector_escalator_f1_f2_demo_01",
  "connector_type": "escalator",
  "from_node_id": "node_f1_escalator_up_demo_01",
  "to_node_id": "node_f2_escalator_out_demo_01",
  "from_floor_id": "F1",
  "to_floor_id": "F2",
  "direction": "up",
  "bidirectional": false,
  "weight": 20.0,
  "instruction": "乘扶梯上行至二层"
}
```

要求：

- 扶梯必须标明方向。
- 楼梯 / 电梯可双向。
- App 绘制时只在当前楼层显示对应端点，不画跨楼层平面线。

## 9. `pois`

POI 定义：

```json
{
  "poi_id": "poi_f2_tata_door_demo",
  "poi_name": "TATA",
  "poi_type": "store",
  "floor_id": "F2",
  "door_node_id": "node_f2_tata_door_demo",
  "display_label": "2F TATA 店铺门口",
  "arrival_radius_m": 3.0,
  "tags": [
    "pickup"
  ]
}
```

要求：

- App 规划目标使用 `poi_id`。
- 真实路径终点使用 `door_node_id`。
- 一个店铺后续可有多个门口节点，但当前 Demo 只要求一个。

## 10. `entrances`

入口定义：

```json
{
  "entrance_id": "entrance_f1_west_gate",
  "entrance_name": "西门",
  "floor_id": "F1",
  "entry_node_id": "node_f1_west_gate_demo",
  "gcj02": {
    "lat": 39.991583,
    "lng": 116.338965
  },
  "supported_handoff": [
    "manual",
    "outdoor_navigation"
  ]
}
```

要求：

- App 室外到室内 handoff 使用 `entrance_id`。
- 室内自动规划起点使用 `entry_node_id`。
- 入口 GCJ-02 坐标用于室外导航终点和接近入口判断。

## 11. `routing_profiles`

规划策略定义：

```json
{
  "profile_id": "delivery_default",
  "display_name": "外卖员默认",
  "edge_type_weights": {
    "walkway": 1.0,
    "turn": 1.1,
    "escalator": 1.4,
    "stairs": 2.0,
    "elevator": 1.8
  },
  "avoid_edge_types": [
    "restricted"
  ]
}
```

当前 App 首版只需要 `delivery_default`。

## 12. App 侧最小规划用例

Mapping 侧必须保证以下输入能规划成功：

```json
{
  "start_entrance_id": "entrance_f1_west_gate",
  "target_poi_id": "poi_f2_tata_door_demo",
  "profile_id": "delivery_default"
}
```

期望输出路径节点顺序：

```text
node_f1_west_gate_demo
node_f1_west_corridor_turn_demo
node_f1_escalator_up_demo_01
node_f2_escalator_out_demo_01
node_f2_tata_corridor_turn_01_demo
node_f2_tata_corridor_turn_02_demo
node_f2_tata_door_demo
```

期望楼层分组：

```text
F1:
node_f1_west_gate_demo
node_f1_west_corridor_turn_demo
node_f1_escalator_up_demo_01

F2:
node_f2_escalator_out_demo_01
node_f2_tata_corridor_turn_01_demo
node_f2_tata_corridor_turn_02_demo
node_f2_tata_door_demo
```

## 13. Mapping 侧执行步骤

1. 整理目标场馆所有可通行节点。
2. 为节点补齐 `venue_xy / app_preview_xy / gcj02`。
3. 建立同层 `edges`。
4. 建立跨层 `connectors`。
5. 建立 `entrances` 和 `pois` 到节点的绑定。
6. 使用 `delivery_default` 权重在 Mapping 侧跑一次最短路验证。
7. 输出 `standard_route_graph.json`。
8. 输出 `APP_ROUTE_GRAPH_INTEGRATION.md`，记录坐标来源、锚点、未校准点和已验证路径。

## 14. 验收检查

Mapping 侧交付前必须确认：

- `nodes.length >= 7`
- `edges` 能连通西门入口到 2F TATA 门口
- `entrance_f1_west_gate.entry_node_id == node_f1_west_gate_demo`
- `poi_f2_tata_door_demo.door_node_id == node_f2_tata_door_demo`
- `connectors` 包含 F1 到 F2 的扶梯连接
- F1 / F2 路线可按楼层拆分
- 所有用于高德叠加的节点都有 `gcj02`
- 所有用于 fallback preview 的节点都有 `app_preview_xy`
- `app_preview_xy` 没有超出 `x: 0..50, y: 0..30`
- 文档明确标注哪些 `gcj02` 是 anchor，哪些是 estimated

## 15. 当前不做

- 不输出视觉定位 keyframe。
- 不输出 SLAM 点云。
- 不把 route-only 路线包装成真实定位。
- 不要求云端服务参与规划。
- 不要求 App 自动判断用户真实所在节点。

## 16. App 消费口径

App 后续消费该路网时会按以下顺序处理：

1. 从 assets 读取 `standard_route_graph.json`。
2. 根据 `entrance_id` 找 `entry_node_id`。
3. 根据 `poi_id` 找 `door_node_id`。
4. 用 `edges + connectors + routing_profile` 在本地算路。
5. 将路径按 `floor_id` 拆分。
6. 高德底图用 `gcj02` 绘制。
7. fallback preview 用 `app_preview_xy` 绘制。
8. 顶部状态卡显示 route-only / 手动或自动规划口径，不显示视觉定位成功。
