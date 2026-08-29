# Mapping 两锚点 GCJ-02 Overlay 生成说明 v0.1

本文档面向 Mapping 侧，用于生成 Android App 在高德室内底图上叠加路线所需的 `nodes[].gcj02`。

当前目标不是逐点手工标注所有中间节点，而是基于两个已确认锚点自动推算整条 route-only DEMO 路线：

```text
西门(F1) -> 1F 扶梯 -> 2F 扶梯上口 -> 2F TATA 门口
```

## 1. 背景结论

如果 Mapping 的 `venue_xy`、高德室内底图和当前路线节点满足以下条件：

- 同一套平面坐标基准
- 1F / 2F 比例一致
- 1F / 2F 方向一致
- 1F / 2F 没有明显独立平移或旋转差异

则可以用两个锚点计算一个全局 2D 相似变换，并批量生成所有路线节点的 `gcj02`。

这比 App 侧逐点手工修正更合适，因为：

- App 只负责消费 overlay，不负责生产地图配准数据。
- 路线节点变化后，Mapping 可以重新生成 overlay。
- App 无需把 Mapping 内部 `venue_xy` 推导逻辑写死。
- 如果后续需要多锚点或分楼层配准，只需替换 Mapping 输出文件。

## 2. 输入文件

Mapping 侧至少需要读取：

```text
mapping/drafts/wudaokou-route-demo-v0.1/route_graph.json
mapping/drafts/wudaokou-route-demo-v0.1/app_handoff/wudaokou_tata_app_route_demo.json
```

其中：

- `route_graph.json` 提供路线节点的 `venue_xy`。
- `wudaokou_tata_app_route_demo.json` 提供 App 路线节点顺序和当前手动 DEMO 口径。

## 3. 输入锚点

当前已确认两个高德 GCJ-02 锚点：

| node_id | 语义 | floor_id | venue_xy | gcj02 |
| --- | --- | --- | --- | --- |
| `node_f1_west_gate_demo` | F1 西门入口 | `F1` | 来自 `route_graph.json` | `39.991583,116.338965` |
| `node_f2_tata_door_demo` | 2F TATA 店铺门口 | `F2` | 来自 `route_graph.json` | `39.991556,116.339568` |

要求：

- `venue_xy` 必须来自同一份 `route_graph.json`。
- `gcj02` 必须来自高德室内底图点击标注。
- 不使用 `app_preview_xy` 参与高德坐标计算。

## 4. 输出文件

Mapping 侧输出：

```text
mapping/drafts/wudaokou-route-demo-v0.1/app_handoff/wudaokou_tata_amap_gcj02_overlay.json
```

App 侧会复制到：

```text
android/ai-glasses-poc/app/src/main/assets/indoor_routes/wudaokou_tata_amap_gcj02_overlay.json
```

App 运行时只读取 Android assets，不读取 `mapping/` 目录。

## 5. 计算方式

### 5.1 坐标定义

设：

```text
A = 西门锚点
B = TATA 门口锚点
```

Mapping 坐标：

```text
A_xy = (Ax, Ay)
B_xy = (Bx, By)
```

高德坐标：

```text
A_gcj02 = (A_lat, A_lng)
B_gcj02 = (B_lat, B_lng)
```

把经纬度差转换成米制偏移：

```text
meters_per_degree_lat = 111320.0
meters_per_degree_lng = 111320.0 * cos(A_lat)

B_east_m  = (B_lng - A_lng) * meters_per_degree_lng
B_north_m = (B_lat - A_lat) * meters_per_degree_lat
```

注意：

- `venue_xy` 的 `y` 如果是向下为正，必须在公式里保持一致。
- 不要把 `venue_xy.y` 静默反向，除非 Mapping 明确更新坐标系口径。

### 5.2 求相似变换

设：

```text
dx = Bx - Ax
dy = By - Ay
```

求：

```text
east_m  = a * dx - b * dy
north_m = b * dx + a * dy
```

其中：

```text
den = dx * dx + dy * dy
a = (dx * B_east_m + dy * B_north_m) / den
b = (dx * B_north_m - dy * B_east_m) / den
```

对任意节点 `P`：

```text
pdx = Px - Ax
pdy = Py - Ay

P_east_m  = a * pdx - b * pdy
P_north_m = b * pdx + a * pdy

P_lat = A_lat + P_north_m / meters_per_degree_lat
P_lng = A_lng + P_east_m / meters_per_degree_lng
```

## 6. 输出 JSON 要求

`wudaokou_tata_amap_gcj02_overlay.json` 至少包含：

```json
{
  "schema_version": "amap_gcj02_overlay.v0.1",
  "status": "two_anchor_alignment",
  "coordinate_system": "GCJ-02",
  "route_id": "manual_demo_wudaokou_west_to_2f_tata",
  "venue_id": "venue_bj_wudaokou_shopping_center_demo",
  "target_poi_id": "poi_f2_tata_door_demo",
  "calibration": {},
  "nodes": [],
  "route_polylines": [],
  "known_limitations": []
}
```

### 6.1 `calibration`

必须记录：

```json
{
  "model": "2d_similarity_transform",
  "source_xy": "venue_xy",
  "local_y_axis": "down",
  "origin_node_id": "node_f1_west_gate_demo",
  "origin_gcj02": {
    "lat": 39.991583,
    "lng": 116.338965
  },
  "anchors": [],
  "parameters": {
    "meters_per_degree_lat": 111320.0,
    "meters_per_degree_lng_at_origin": 0.0,
    "transform_a": 0.0,
    "transform_b": 0.0,
    "scale_m_per_venue_xy_unit": 0.0,
    "rotation_degrees": 0.0
  }
}
```

### 6.2 `nodes`

每个路线节点必须输出：

```json
{
  "node_id": "node_f1_west_corridor_turn_demo",
  "floor_id": "F1",
  "label": "F1 西侧通道转角",
  "node_type": "waypoint",
  "venue_xy": {
    "x": 55.932,
    "y": 143.973
  },
  "gcj02": {
    "lat": 39.99152928,
    "lng": 116.3392861
  },
  "east_m": 27.385,
  "north_m": -5.98,
  "alignment_role": "estimated"
}
```

锚点要求：

- 西门入口 `alignment_role = anchor`
- TATA 门口 `alignment_role = anchor`
- 自动推算节点 `alignment_role = estimated`

### 6.3 `route_polylines`

必须按楼层输出：

```json
[
  {
    "floor_id": "F1",
    "node_ids": [
      "node_f1_west_gate_demo",
      "node_f1_west_corridor_turn_demo",
      "node_f1_escalator_up_demo_01"
    ]
  },
  {
    "floor_id": "F2",
    "node_ids": [
      "node_f2_escalator_out_demo_01",
      "node_f2_tata_corridor_turn_01_demo",
      "node_f2_tata_corridor_turn_02_demo",
      "node_f2_tata_door_demo"
    ]
  }
]
```

要求：

- F1 只包含 F1 平面路线。
- F2 只包含 F2 平面路线。
- 不输出跨楼层平面线。
- 扶梯跨层只通过 App 的“上楼”步骤切换楼层。

## 7. App 消费规则

App 已按以下口径消费该文件：

- 读取 Android assets 中的 `wudaokou_tata_amap_gcj02_overlay.json`。
- 用 `node_id` 将手动脚本节点与 `nodes[].gcj02` 关联。
- 当前楼层为 F1 时只画 F1 `route_polylines`。
- 当前楼层为 F2 时只画 F2 `route_polylines`。
- 高德室内底图使用 `gcj02`。
- fallback canvas preview 继续使用 `app_preview_xy`。

## 8. Mapping 侧验收

生成文件后，Mapping 侧至少检查：

- `nodes.length == 7`
- `route_polylines.length == 2`
- `route_polylines` 包含 `F1` 和 `F2`
- `node_f1_west_gate_demo.gcj02 == 39.991583,116.338965`
- `node_f2_tata_door_demo.gcj02 == 39.991556,116.339568`
- 中间节点的 `gcj02` 全部非空
- 所有 `node_ids` 都能在 `nodes` 中找到
- 没有把 `app_preview_xy` 写入高德 overlay 坐标
- 没有跨楼层 polyline

## 9. 如果仍然错位

如果两锚点生成后，App 真机上仍看到中间点明显偏离高德通道，不要在 App 侧逐点硬修。

Mapping 侧需要判断是哪类偏移：

| 现象 | 处理 |
| --- | --- |
| F1 整层方向或比例偏 | 增加 F1 第二个锚点，生成 F1 transform |
| F2 整层方向或比例偏 | 增加 F2 第二个锚点，生成 F2 transform |
| 局部转角偏 | 增加该区域 anchor 或直接把该节点标为 anchor |
| 只有跨层附近偏 | 单独校准扶梯上下口节点 |

升级后的输出可以继续沿用 `nodes[].gcj02`，App 不需要改变消费方式。

## 10. 当前不做

- 不要求 App 手工逐点调坐标。
- 不要求 Cloud 参与计算。
- 不输出视觉定位成功口径。
- 不把 route-only overlay 当成真实定位结果。
