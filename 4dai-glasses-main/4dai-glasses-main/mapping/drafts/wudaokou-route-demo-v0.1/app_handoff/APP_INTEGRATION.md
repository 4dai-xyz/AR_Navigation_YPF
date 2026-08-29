# 五道口 TATA 路线 App 接入交付说明

本目录面向 Android App 接入当前已确认的 route-only DEMO：

```text
西门(F1) -> 1F 扶梯 -> 2F 扶梯上口 -> TATA 门口
```

## 交付文件

- `wudaokou_tata_app_route_demo.json`：App 可读取或手工迁移的路线数据。
- `wudaokou_tata_amap_gcj02_overlay.json`：高德室内底图叠加使用的节点 GCJ-02 坐标与两点配准参数。
- `ManualIndoorDemoScripts.defaultScript.replacement.kt.txt`：按当前 Android `ManualIndoorDemoScript` 数据类生成的替换片段。

## App 接入现状口径

Mapping 侧已提供 TATA 路线的 App handoff 数据。App 侧接入时需要重点关注以下文件：

- `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/indoor/ManualIndoorDemoController.kt`
- `android/ai-glasses-poc/app/src/test/java/com/aiglasses/poc/ManualIndoorDemoControllerTest.kt`
- `android/ai-glasses-poc/app/src/main/res/layout/activity_main.xml`
- `android/ai-glasses-poc/app/src/main/res/values/strings.xml`
- `android/ai-glasses-poc/app/src/main/java/com/aiglasses/poc/MainActivity.kt`

本目录不要求 App 运行时直接读取 `mapping/`；App 可复制 `wudaokou_tata_app_route_demo.json` 到 Android assets，或按 `ManualIndoorDemoScripts.defaultScript.replacement.kt.txt` 做最小硬编码接入。

## 最小接入方式

App 侧最小改动建议：

1. 将 `ManualIndoorDemoScripts.defaultScript` 替换为 TATA 路线。
2. 将默认 `targetPoiId` 改为 `poi_f2_tata_door_demo`。
3. 将相关文案从旧目标改为 `TATA`。
4. 同步更新 `ManualIndoorDemoControllerTest`。
5. 运行 Android 单元测试，确认手动步骤、跨层事件和到达事件通过。

## 坐标口径

`wudaokou_tata_app_route_demo.json` 同时包含两套坐标：

| 坐标 | 用途 | 说明 |
| --- | --- | --- |
| `venue_xy` | Mapping 草案与后续场馆包 | 来自 `route_graph.json`，数值较大，不适合直接塞进当前 App fallback preview |
| `app_preview_xy` | Android 手动 DEMO | 已缩放进当前 `IndoorRoutePreviewView` 的 `50 x 30` 画布范围 |

当前 Android `IndoorRoutePreviewView` 的预览范围固定为：

```text
x: 0..50
y: 0..30
```

因此 `ManualIndoorDemoPoint` 应使用 `app_preview_xy`，不要直接使用 `venue_xy`。

高德室内底图叠加必须使用 `wudaokou_tata_amap_gcj02_overlay.json`：

| 字段 | 用途 |
| --- | --- |
| `calibration.anchors` | 西门与 TATA 门口两个高德 GCJ-02 锚点 |
| `calibration.parameters` | 从 `venue_xy` 到东/北米制偏移的两点相似变换参数 |
| `nodes[].gcj02` | 每个路线节点预计算后的高德经纬度 |
| `route_polylines` | 按楼层分组的路线节点顺序 |

App 侧不要再用固定 `DEMO_ANCHOR_X / DEMO_ANCHOR_Y / DEMO_METERS_PER_UNIT` 去投影这条路线，也不要把 `app_preview_xy` 投到高德地图上。

如需运行时重算，使用以下公式：

```text
dx = venue_x - origin_venue_x
dy = venue_y - origin_venue_y
east_m = transform_a * dx - transform_b * dy
north_m = transform_b * dx + transform_a * dy
lat = origin_lat + north_m / meters_per_degree_lat
lng = origin_lng + east_m / meters_per_degree_lng_at_origin
```

## 手动演示动作序列

| step | 当前点 | 期望动作 |
| --- | --- | --- |
| `step_001` | F1 西门入口 | `UP` |
| `step_002` | F1 西侧通道转角 | `LEFT` |
| `step_003` | F1 上行扶梯口 | `FLOOR_UP` |
| `step_004` | F2 扶梯出口 | `UP` |
| `step_005` | F2 TATA 连廊转角 01 | `RIGHT` |
| `step_006` | F2 TATA 连廊转角 02 | `LEFT` |
| `step_007` | 2F TATA 店铺门口 | arrived |

## 高德室内底图注意事项

当前 App 的 `IndoorBasemapController` 是把本地 `x / y` 通过近似比例投影到 `LatLng` 后叠加到高德室内底图，不是严格的高德室内矢量坐标。

因此：

- 这条线能支持路线演示和手动引导。
- 若要让叠加线贴合高德室内底图，App 应优先使用 `wudaokou_tata_amap_gcj02_overlay.json` 中的 `nodes[].gcj02`。
- 当前只使用两个锚点做相似变换，无法修正楼层间偏移、截图非线性畸变或高德室内矢量底图自身偏差。
- 如果 F1 / F2 叠加仍有系统性偏移，需要分别补每层至少两个锚点，生成 floor-specific transform。
- 本轮没有真实定位资产，不应把它当成视觉定位可用路线。

## App 侧验收

接入后建议确认：

1. 进入室内手动演示时，目标显示为 `2F TATA 店铺门口`。
2. 路线步骤总数为 `7`。
3. 点击动作顺序 `直行 -> 左转 -> 上楼 -> 直行 -> 右转 -> 左转` 后进入到达状态。
4. 跨层时产生 `manual_demo_floor_changed from=F1 to=F2` 日志。
5. 到达时产生 `manual_demo_arrived target=2F TATA 店铺门口` 日志。
