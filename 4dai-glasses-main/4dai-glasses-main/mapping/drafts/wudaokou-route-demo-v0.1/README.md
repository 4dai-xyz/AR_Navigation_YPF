# 北京五道口购物中心路线 DEMO 草案 v0.1

本目录是基于高德室内底图截图制作的 `route-only` 标注草案，只用于快速验证：

```text
西门(F1) -> 1F 扶梯 -> 2F 扶梯上口 -> TATA 门口
```

当前状态：路线点位已人工确认，确认日期 `2026-05-07`。

## 当前边界

- 使用截图：`C:/Users/Administrator/Desktop/AI建图/五道口1F.jpg`、`C:/Users/Administrator/Desktop/AI建图/五道口2F.jpg`
- 不包含真实 S20 点云、真实 RGB 关键帧、特征库或视觉重定位资产。
- 不作为可发布真实场馆包；当前 `mapping/tools/validate_venue_package.py` 会要求 `localization/` 资产，本草案不补空资产伪装通过。
- 本草案只冻结一条可演示路线的最小标注对象，等人工确认点位后再装入正式场馆包。

## 坐标口径

当前坐标从原始截图像素粗略换算：

| 字段 | 值 |
| --- | --- |
| source image size | `1440 x 3200` |
| origin pixel | `(180, 650)` |
| scale | `0.10 meter / pixel` |
| x direction | 向右为正 |
| route JSON y direction | 向下为正 |
| QGIS GeoJSON y direction | 向上为正 |
| accuracy | 仅用于路线 DEMO 草案，非实测坐标 |

`route_graph.json / entrances.json / connectors.json / pois.json` 的临时坐标换算公式：

```text
x = (pixel_x - 180) * 0.10
y = (pixel_y - 650) * 0.10
```

QGIS 为避免底图上下镜像，使用常规 GIS 坐标方向：

```text
x = (pixel_x - 180) * 0.10
y = (650 - pixel_y) * 0.10
```

因此手工编辑后我会把 QGIS 中的 `y` 再反号同步回包内 JSON。

## 已标注对象

| 类型 | ID | 楼层 | 用途 |
| --- | --- | --- | --- |
| entrance | `entrance_f1_west_gate` | `F1` | 西门入场点 |
| connector | `connector_escalator_f1_f2_demo_01` | `F1 -> F2` | 本路线使用的扶梯 |
| poi | `poi_f2_tata_door_demo` | `F2` | TATA 门口目标点 |
| route node | `node_f1_west_gate_demo` | `F1` | 西门入口路网节点 |
| route node | `node_f1_west_corridor_turn_demo` | `F1` | 1F 入口到扶梯的转折点 |
| route node | `node_f1_escalator_up_demo_01` | `F1` | 1F 扶梯上行入口 |
| route node | `node_f2_escalator_out_demo_01` | `F2` | 2F 扶梯出口 |
| route node | `node_f2_tata_corridor_turn_01_demo` | `F2` | 2F 去 TATA 的第一个转折点 |
| route node | `node_f2_tata_corridor_turn_02_demo` | `F2` | 2F 去 TATA 的第二个转折点 |
| route node | `node_f2_tata_door_demo` | `F2` | TATA 门口到达点 |

## 文件说明

- `venue.json`：DEMO 场馆基础信息。
- `floors.json`：本轮只启用 `F1 / F2`。
- `entrances.json`：西门入口草案。
- `connectors.json`：F1 到 F2 扶梯草案。
- `pois.json`：F2 TATA 门口草案。
- `route_graph.json`：路线节点和边。
- `qgis/route_nodes.geojson`：可导入 QGIS 的节点草案。
- `qgis/route_edges.geojson`：可导入 QGIS 的路线边草案。
- `app_handoff/wudaokou_tata_app_route_demo.json`：给 Android 手动室内 DEMO 使用的路线数据。
- `app_handoff/wudaokou_tata_amap_gcj02_overlay.json`：给 Android 高德室内底图叠加使用的预计算 GCJ-02 节点坐标。
- `app_handoff/ManualIndoorDemoScripts.defaultScript.replacement.kt.txt`：当前 Android 硬编码脚本的 TATA 替换片段。
- `app_handoff/APP_INTEGRATION.md`：App 接入口径、坐标口径与验收说明。

## QGIS 手工编辑

已在截图目录生成 JPEG world file：

- `C:/Users/Administrator/Desktop/AI建图/五道口1F.jgw`
- `C:/Users/Administrator/Desktop/AI建图/五道口2F.jgw`

这两个文件让 QGIS 加载截图时使用与 `qgis/*.geojson` 一致的临时局部坐标，便于叠加手工修点。

最快操作：

1. 打开 QGIS，新建空白工程。
2. 拖入 `C:/Users/Administrator/Desktop/AI建图/五道口1F.jpg` 和 `C:/Users/Administrator/Desktop/AI建图/五道口2F.jpg`。
3. 拖入 `qgis/route_nodes.geojson` 和 `qgis/route_edges.geojson`。
4. 在图层面板里只打开当前要编辑的楼层底图；避免 1F/2F 同时显示导致混淆。
5. 右键 `route_nodes` -> `Toggle Editing`，用 `Move Feature` 拖动点位。
6. 如路线折线也要同步，右键 `route_edges` -> `Toggle Editing`，用顶点工具调整对应线段端点。
7. 保存图层编辑后，把 `route_nodes.geojson`、`route_edges.geojson` 发回或保留在本目录，我再同步回 `route_graph.json / entrances.json / connectors.json / pois.json`。

编辑优先级：

1. 先修 `route_nodes.geojson` 的点位。
2. 再修 `route_edges.geojson` 的线段。
3. 不建议在 QGIS 里直接改 ID，避免 JSON 引用断裂。

## 可视化预览

已在截图目录输出路线预览图：

- `C:/Users/Administrator/Desktop/AI建图/_demo_out/wudaokou_1f_route_overlay.jpg`
- `C:/Users/Administrator/Desktop/AI建图/_demo_out/wudaokou_2f_route_overlay.jpg`

## 最快复核方式

1. 打开上面的两张 overlay 图片。
2. 只确认四件事：
   - `entrance_f1_west_gate` 是否落在真实西门进场点。
   - `node_f1_escalator_up_demo_01` 是否是要乘坐的 1F 扶梯。
   - `node_f2_escalator_out_demo_01` 是否是同一组扶梯到达 2F 后的位置。
   - `poi_f2_tata_door_demo` 是否落在 TATA 门口而不是店铺中心。
3. 如果点位不准，优先修改 `qgis/route_nodes.geojson` 的 `source_pixel_x / source_pixel_y`，再同步修改 `route_graph.json`、`entrances.json`、`connectors.json`、`pois.json` 的 `x / y`。
4. 人工确认后，再决定是给 App 做截图路线叠加 DEMO，还是补正式 `localization/` 后走完整地图包校验和发布。

## 需要人工确认

- 西门是否指截图左侧下方的西门入口。
- 当前选择的扶梯是否是现场真实推荐上行扶梯。
- 2F 扶梯出口与 1F 扶梯入口是否属于同一组扶梯。
- TATA 到达点是否应为店铺门口中心，而不是高德 POI 标签中心。
- 若 App 需要室外到室内衔接，还需要补西门的高德/GCJ-02 坐标。
